"""Offline tests for tools/tv.py, tools/b64push.py and the scripts that use them, with no TV and no real adb.

    python3 -m unittest discover -s tools/tests -v
"""
import argparse
import base64
import contextlib
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
REPO = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS)
import b64push  # noqa: E402
import perf  # noqa: E402
import tv  # noqa: E402

MDNS = "adb-8C00F07E0160CF00F-pLsp3G (2)._adb-tls-connect._tcp"
DEVICES = "List of devices attached\n%s\tdevice\n\n" % MDNS


class TransportChoiceTests(unittest.TestCase):
    def test_tv_host_keeps_adb_shell(self):
        self.assertEqual(tv.choose_transport({"TV_HOST": "192.168.1.50"}), "adb_shell")

    def test_no_tv_host_uses_the_adb_binary(self):
        self.assertEqual(tv.choose_transport({}), "adb")
        self.assertEqual(tv.choose_transport({"TV_HOST": ""}), "adb")
        self.assertEqual(tv.choose_transport({"ANDROID_SERIAL": "x"}), "adb")

    def test_forcing_the_adb_binary(self):
        self.assertEqual(tv.choose_transport({"TV_HOST": "192.168.1.50"}, adb=True), "adb")
        self.assertEqual(tv.choose_transport({"TV_HOST": "192.168.1.50", "TV_TRANSPORT": "adb"}), "adb")
        self.assertEqual(tv.choose_transport({"TV_HOST": "h", "TV_TRANSPORT": " ADB "}), "adb")

    def test_forcing_adb_shell(self):
        self.assertEqual(tv.choose_transport({"TV_HOST": "h", "TV_TRANSPORT": "adb-shell"}), "adb_shell")
        with self.assertRaisesRegex(tv.TvError, "needs TV_HOST"):
            tv.choose_transport({"TV_TRANSPORT": "adb_shell"})

    def test_unknown_transport(self):
        with self.assertRaisesRegex(tv.TvError, "must be adb or adb_shell"):
            tv.choose_transport({"TV_TRANSPORT": "usb"})

    def test_port(self):
        self.assertEqual(tv.tv_port({}), 5555)
        self.assertEqual(tv.tv_port({"TV_PORT": "37123"}), 37123)
        with self.assertRaisesRegex(tv.TvError, "TV_PORT"):
            tv.tv_port({"TV_PORT": "abc"})


class DeviceTests(unittest.TestCase):
    def test_parse_devices_keeps_spaces_in_serials(self):
        out = ("* daemon not running; starting now at tcp:5037\n* daemon started successfully\n"
               "List of devices attached\r\n%s\tdevice\r\n192.168.1.50:5555\toffline\r\nR58M\tunauthorized\r\n\r\n"
               % MDNS)
        self.assertEqual(tv.parse_devices(out), [(MDNS, "device"), ("192.168.1.50:5555", "offline"),
                                                 ("R58M", "unauthorized")])
        self.assertEqual(tv.parse_devices("List of devices attached\n\n"), [])

    def test_the_only_ready_device(self):
        self.assertEqual(tv.pick_device([(MDNS, "device")]), MDNS)
        self.assertEqual(tv.pick_device([("a", "offline"), (MDNS, "device")]), MDNS)

    def test_no_device(self):
        with self.assertRaises(tv.TvError) as e:
            tv.pick_device([])
        self.assertIn("adb pair", str(e.exception))
        self.assertIn("TV_HOST", str(e.exception))

    def test_unauthorized_or_offline(self):
        with self.assertRaisesRegex(tv.TvError, "R58M is unauthorized: accept"):
            tv.pick_device([("R58M", "unauthorized")])
        with self.assertRaisesRegex(tv.TvError, "offline"):
            tv.pick_device([("1.2.3.4:5555", "offline")])

    def test_several_devices(self):
        with self.assertRaises(tv.TvError) as e:
            tv.pick_device([(MDNS, "device"), ("192.168.1.50:5555", "device")])
        self.assertIn("ANDROID_SERIAL", str(e.exception))
        self.assertIn(MDNS, str(e.exception))

    def test_requested_serial(self):
        devices = [(MDNS, "device"), ("192.168.1.50:5555", "device"), ("R58M", "unauthorized")]
        self.assertEqual(tv.pick_device(devices, "192.168.1.50:5555"), "192.168.1.50:5555")
        self.assertEqual(tv.pick_device(devices, MDNS), MDNS)
        with self.assertRaisesRegex(tv.TvError, "unauthorized"):
            tv.pick_device(devices, "R58M")
        with self.assertRaisesRegex(tv.TvError, "no device 'nope'.*refused"):
            tv.pick_device(devices, "nope", "adb connect nope: Connection refused")


class FindAdbTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        self.exe = "adb.exe" if os.name == "nt" else "adb"
        patcher = mock.patch.object(tv.os.path, "expanduser", lambda p: p.replace("~", self.tmp))
        patcher.start()
        self.addCleanup(patcher.stop)

    def make_sdk(self, name):
        path = os.path.join(self.tmp, name, "platform-tools", self.exe)
        os.makedirs(os.path.dirname(path))
        open(path, "w").close()
        return path

    def test_path_first(self):
        on_path = self.make_sdk("onpath")
        self.make_sdk("sdk")
        with mock.patch.object(tv.shutil, "which", return_value=on_path):
            self.assertEqual(tv.find_adb({"ANDROID_HOME": os.path.join(self.tmp, "sdk")}), on_path)

    def test_sdk_folders(self):
        with mock.patch.object(tv.shutil, "which", return_value=None):
            self.assertIsNone(tv.find_adb({}))
            root = self.make_sdk("root")
            self.assertEqual(tv.find_adb({"ANDROID_SDK_ROOT": os.path.join(self.tmp, "root")}), root)
            studio = self.make_sdk(os.path.join("Library", "Android", "sdk"))
            self.assertEqual(tv.find_adb({}), studio)
            home = self.make_sdk("home")
            self.assertEqual(tv.find_adb({"ANDROID_HOME": os.path.join(self.tmp, "home")}), home)

    def test_windows_local_app_data(self):
        win = self.make_sdk(os.path.join("AppData", "Android", "Sdk"))
        with mock.patch.object(tv.shutil, "which", return_value=None):
            self.assertEqual(tv.find_adb({"LOCALAPPDATA": os.path.join(self.tmp, "AppData")}), win)


class FakeRun:
    """Stands in for subprocess.run; `answer(args after the adb path)` returns (exit code, output)."""

    def __init__(self, answer):
        self.answer, self.calls = answer, []

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if isinstance(self.answer, BaseException):
            raise self.answer
        code, out = self.answer(argv[1:])
        return subprocess.CompletedProcess(argv, code, out.encode(), None)


class AdbBinaryTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(tv, "bound_device", return_value=None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def fake(self, answer):
        run = FakeRun(answer)
        patcher = mock.patch.object(tv.subprocess, "run", run)
        patcher.start()
        self.addCleanup(patcher.stop)
        return run

    def test_shell(self):
        run = self.fake(lambda a: (0, "Smart TV Pro\n"))
        self.assertEqual(tv.AdbBinary("/sdk/adb", MDNS).shell("getprop ro.product.model"), "Smart TV Pro\n")
        argv, kwargs = run.calls[0]
        self.assertEqual(argv, ["/sdk/adb", "-s", MDNS, "shell", "getprop ro.product.model"])
        # adb must not read stdin, or it eats the rest of a --batch; stderr is merged like adb_shell's output
        self.assertIs(kwargs["stdin"], subprocess.DEVNULL)
        self.assertIs(kwargs["stderr"], subprocess.STDOUT)
        self.assertEqual(kwargs["timeout"], 120)

    def test_failing_command_is_not_an_error(self):
        run = self.fake(lambda a: (0, "device\n") if a[2] == "get-state" else (1, ""))
        self.assertEqual(tv.AdbBinary("adb", MDNS).shell("pm path com.example.none"), "")
        self.assertEqual(run.calls[1][0][3], "get-state")

    def test_lost_device_is_an_error(self):
        self.fake(lambda a: (1, "adb: device '%s' not found\n" % MDNS))
        with self.assertRaisesRegex(tv.TvError, "lost the connection"):
            tv.AdbBinary("adb", MDNS).shell("true")

    def test_timeout(self):
        self.fake(subprocess.TimeoutExpired("adb", 5))
        with self.assertRaisesRegex(tv.TvError, "no answer"):
            tv.AdbBinary("adb", MDNS).shell("sleep 999", timeout=5)

    def test_push_and_pull(self):
        run = self.fake(lambda a: (0, "a.apk: 1 file pushed, 0 skipped.\n") if a[2] == "push" else (1, "nope\n"))
        dev = tv.AdbBinary("adb", MDNS)
        self.assertIn("1 file pushed", dev.push("a.apk", "/data/local/tmp/a.apk"))
        self.assertEqual(run.calls[0][0], ["adb", "-s", MDNS, "push", "a.apk", "/data/local/tmp/a.apk"])
        with self.assertRaisesRegex(tv.TvError, "adb pull .* failed: nope"):
            dev.pull("/sdcard/x", "x")

    def test_open_picks_the_only_device(self):
        run = self.fake(lambda a: (0, DEVICES))
        with mock.patch.object(tv, "find_adb", return_value="/sdk/adb"):
            dev = tv.connect(env={})
        self.assertIsInstance(dev, tv.AdbBinary)
        self.assertEqual(dev.serial, MDNS)
        self.assertEqual([c[0] for c in run.calls], [["/sdk/adb", "devices"]])

    def test_open_honours_android_serial(self):
        self.fake(lambda a: (0, DEVICES + "192.168.1.50:5555\tdevice\n"))
        with mock.patch.object(tv, "find_adb", return_value="adb"):
            self.assertEqual(tv.connect(env={"ANDROID_SERIAL": "192.168.1.50:5555"}).serial, "192.168.1.50:5555")
            self.assertEqual(tv.connect(serial=MDNS, env={"TV_HOST": "192.168.1.50"}).serial, MDNS)

    def test_open_lets_adb_match_a_serial_loosely(self):
        def answer(a):
            if a == ["devices"]:
                return 0, "List of devices attached\n192.168.1.50:5555\tdevice\n"
            return (0, "device\n") if a[1] == "192.168.1.50" else (1, "adb: device '%s' not found\n" % a[1])
        run = self.fake(answer)
        with mock.patch.object(tv, "find_adb", return_value="adb"):
            self.assertEqual(tv.connect(env={"ANDROID_SERIAL": "192.168.1.50"}).serial, "192.168.1.50")
            self.assertEqual(run.calls[-1][0], ["adb", "-s", "192.168.1.50", "get-state"])
            with self.assertRaisesRegex(tv.TvError, "no device '192.168.1.99'"):
                tv.connect(env={"ANDROID_SERIAL": "192.168.1.99"})

    def test_open_with_tv_host_runs_adb_connect(self):
        def answer(a):
            if a[0] == "connect":
                return 0, "connected to %s\n" % a[1]
            return 0, DEVICES + "192.168.1.50:37123\tdevice\n"
        run = self.fake(answer)
        with mock.patch.object(tv, "find_adb", return_value="adb"):
            dev = tv.connect(env={"TV_HOST": "192.168.1.50", "TV_PORT": "37123", "TV_TRANSPORT": "adb"})
        self.assertEqual(dev.serial, "192.168.1.50:37123")
        self.assertEqual(run.calls[0][0], ["adb", "connect", "192.168.1.50:37123"])

    def test_no_adb(self):
        with mock.patch.object(tv, "find_adb", return_value=None):
            with self.assertRaisesRegex(tv.TvError, "adb not found.*TV_HOST"):
                tv.connect(env={})


class AdbShellTests(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.object(tv, "bound_device", return_value=None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_missing_package(self):
        with mock.patch.dict(sys.modules, {"adb_shell": None, "adb_shell.adb_device": None}):
            with self.assertRaisesRegex(tv.TvError, "pip install -r tools/requirements.txt"):
                tv.connect(env={"TV_HOST": "192.168.1.50"})

    def test_wraps_adb_shell(self):
        dev = object.__new__(tv.AdbShell)
        dev.dev = mock.Mock()
        dev.dev.shell.return_value = "1\n"
        self.assertEqual(dev.shell("settings get secure accessibility_enabled", timeout=60), "1\n")
        dev.dev.shell.assert_called_once_with("settings get secure accessibility_enabled", read_timeout_s=60.0)
        self.assertFalse(dev.native_push)

    def test_connection_refused(self):
        try:
            import adb_shell  # noqa: F401
        except ImportError:
            self.skipTest("adb_shell is not installed")
        import socket
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        with mock.patch.object(tv, "get_signer", return_value=None):
            with self.assertRaisesRegex(tv.TvError, "(?s)could not connect to 127.0.0.1:%d.*unset TV_HOST" % port):
                tv.connect(env={"TV_HOST": "127.0.0.1", "TV_PORT": str(port)}, timeout=5)

    def test_wireless_debugging_port(self):
        stls = Exception("Unknown command: 1397511251 = 'b'STLS'' (arg0 = 16777216, arg1 = 0)")
        fake = mock.Mock()
        fake.return_value.connect.side_effect = stls
        module = mock.Mock(AdbDeviceTcp=fake)
        modules = {"adb_shell": mock.Mock(adb_device=module), "adb_shell.adb_device": module}
        with mock.patch.dict(sys.modules, modules), mock.patch.object(tv, "get_signer", return_value=None):
            with self.assertRaisesRegex(tv.TvError, "(?s)wireless debugging \\(TLS\\) port.*TV_TRANSPORT=adb"):
                tv.connect(env={"TV_HOST": "192.168.1.50", "TV_PORT": "37123"})


class FakeDev:
    native_push = False

    def __init__(self, answers=None):
        self.answers = answers or {}
        self.commands, self.timeouts, self.transfers = [], [], []
        self.closed = False

    def describe(self):
        return "fake"

    def shell(self, cmd, timeout=120):
        self.commands.append(cmd)
        self.timeouts.append(timeout)
        return self.answers.get(cmd, "")

    def push(self, local, remote):
        self.transfers.append(("push", local, remote))

    def pull(self, remote, local):
        self.transfers.append(("pull", remote, local))

    def close(self):
        self.closed = True


class CliTests(unittest.TestCase):
    def main(self, argv, dev=None, stdin=b""):
        dev = dev or FakeDev()
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(tv, "connect", return_value=dev) as connect, \
                mock.patch.object(sys, "stdin", io.TextIOWrapper(io.BytesIO(stdin))), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            tv.main(argv)
        self.assertTrue(dev.closed)
        return out.getvalue(), err.getvalue(), dev, connect

    def test_probe(self):
        dev = FakeDev({"getprop ro.product.model": "Smart TV Pro\n", "getprop ro.build.version.release": "14\n"})
        out, err, _, _ = self.main(["--probe"], dev)
        self.assertEqual(out.splitlines()[0], "ro.product.model = Smart TV Pro")
        self.assertIn("ro.build.version.release = 14", out.splitlines())
        self.assertEqual(len(out.splitlines()), len(tv.PROBE_PROPS))
        self.assertIn("connected with fake", err)
        self.assertEqual(self.main([], dev)[0], out)

    def test_one_command(self):
        out, _, dev, _ = self.main(["pm", "list", "packages"], FakeDev({"pm list packages": "package:a\n"}))
        self.assertEqual(out, "package:a\n")
        self.assertEqual((dev.commands, dev.timeouts), (["pm list packages"], [120]))

    def test_batch(self):
        dev = FakeDev({"echo one": "one\n", "echo -n two": "two"})
        out, _, dev, _ = self.main(["--batch"], dev, b"\xef\xbb\xbfecho one\n# comment\n\n  echo -n two  \nrm x\n")
        self.assertEqual(dev.commands, ["echo one", "echo -n two", "rm x"])
        self.assertEqual(out, "$ echo one\none\n$ echo -n two\ntwo\n$ rm x\n")

    def test_push_and_pull(self):
        out, _, dev, _ = self.main(["--push", "a.apk", "/data/local/tmp/a.apk"])
        self.assertEqual(out, "pushed a.apk -> /data/local/tmp/a.apk\n")
        self.assertEqual(dev.transfers, [("push", "a.apk", "/data/local/tmp/a.apk")])
        out, _, dev, _ = self.main(["--pull", "/sdcard/s.png", "s.png"])
        self.assertEqual(out, "pulled /sdcard/s.png -> s.png\n")

    def test_options(self):
        _, _, _, connect = self.main(["--adb", "--serial", MDNS, "--probe"])
        connect.assert_called_once_with(adb=True, serial=MDNS)
        _, _, dev, connect = self.main(["--serial=R58M", "getprop"])
        connect.assert_called_once_with(adb=False, serial="R58M")
        self.assertEqual(dev.commands, ["getprop"])
        _, _, _, connect = self.main(["echo", "--adb"])
        connect.assert_called_once_with(adb=False, serial=None)

    def test_connection_error_exits_with_the_message(self):
        with mock.patch.object(tv, "connect", side_effect=tv.TvError("adb lists no device")):
            with self.assertRaises(SystemExit) as e:
                tv.main(["--probe"])
        self.assertEqual(e.exception.code, "tv.py: adb lists no device")
        with self.assertRaises(SystemExit) as e:
            tv.main(["--serial"])
        self.assertIn("--serial needs", e.exception.code)

    def test_closes_after_a_lost_connection(self):
        dev = FakeDev()
        dev.shell = mock.Mock(side_effect=tv.TvError("lost the connection"))
        with mock.patch.object(tv, "connect", return_value=dev), self.assertRaises(SystemExit) as e:
            tv.main(["true"])
        self.assertEqual(e.exception.code, "tv.py: lost the connection")
        self.assertTrue(dev.closed)


class FakeTvFiles:
    """A TV that runs b64push.py's shell commands on an in-memory file system."""

    def __init__(self, native_push):
        self.native_push = native_push
        self.files, self.commands, self.pushed = {}, [], []
        self.closed = False

    def push(self, local, remote):
        with open(local, "rb") as f:
            self.files[remote] = f.read()
        self.pushed.append((local, remote))
        return "%s: 1 file pushed, 0 skipped. 31.2 MB/s (1660541 bytes in 0.051s)\n" % local

    def shell(self, cmd, timeout=120):
        self.commands.append(cmd)
        m = re.fullmatch(r"rm -f (.+)", cmd)
        if m:
            for name in m.group(1).split():
                self.files.pop(name, None)
            return ""
        m = re.fullmatch(r"echo '([A-Za-z0-9+/=]*)' >> (\S+) && echo (ok\d+)", cmd)
        if m:
            self.files[m.group(2)] = self.files.get(m.group(2), b"") + m.group(1).encode() + b"\n"
            return m.group(3) + "\n"
        m = re.fullmatch(r"base64 -d (\S+) > (\S+); rm -f (\S+)", cmd)
        if m:
            self.files[m.group(2)] = base64.b64decode(self.files[m.group(1)])
            del self.files[m.group(3)]
            return ""
        m = re.fullmatch(r"md5sum (\S+)", cmd)
        if m:
            return "%s  %s\n" % (hashlib.md5(self.files[m.group(1)]).hexdigest(), m.group(1))
        raise AssertionError("unexpected command " + cmd)

    def close(self):
        self.closed = True


class B64PushTests(unittest.TestCase):
    def push(self, dev, data):
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(data)
        self.addCleanup(os.remove, f.name)
        out = io.StringIO()
        with mock.patch.object(b64push.tv, "connect", return_value=dev), contextlib.redirect_stdout(out), \
                self.assertRaises(SystemExit) as e:
            b64push.main([f.name, "/data/local/tmp/tvhome.apk"])
        self.assertTrue(dev.closed)
        return e.exception.code, out.getvalue(), f.name

    def test_adb_binary_pushes_directly(self):
        data = os.urandom(200 * 1024)
        dev = FakeTvFiles(native_push=True)
        code, out, local = self.push(dev, data)
        self.assertEqual(code, 0)
        self.assertEqual(dev.pushed, [(local, "/data/local/tmp/tvhome.apk")])
        self.assertEqual(dev.commands, ["md5sum /data/local/tmp/tvhome.apk"])
        self.assertIn("1 file pushed", out)
        self.assertIn("MATCH", out)

    def test_adb_shell_sends_base64_chunks(self):
        data = os.urandom(2 * b64push.CHUNK + 1000)
        dev = FakeTvFiles(native_push=False)
        code, out, _ = self.push(dev, data)
        self.assertEqual(code, 0)
        self.assertEqual(dev.files, {"/data/local/tmp/tvhome.apk": data})   # the .b64 file is gone
        self.assertEqual(sum(c.startswith("echo '") for c in dev.commands), 3)
        self.assertIn("chunk 3/3", out)
        self.assertIn("md5 remote: %s local: %s MATCH" % ((hashlib.md5(data).hexdigest(),) * 2), out)

    def test_mismatch_fails(self):
        dev = FakeTvFiles(native_push=True)
        dev.push = lambda local, remote: dev.files.__setitem__(remote, b"truncated") or ""
        code, out, _ = self.push(dev, b"the whole apk")
        self.assertEqual(code, 1)
        self.assertIn("MISMATCH", out)

    def test_connection_error(self):
        with mock.patch.object(b64push.tv, "connect", side_effect=tv.TvError("adb lists no device")), \
                self.assertRaises(SystemExit) as e:
            b64push.main([__file__, "/data/local/tmp/x"])
        self.assertEqual(e.exception.code, "b64push.py: adb lists no device")


class PerfConnectionTests(unittest.TestCase):
    def test_uses_tv_connect(self):
        with mock.patch.object(perf.tv, "connect", return_value="dev") as connect:
            self.assertEqual(perf.connect(argparse.Namespace(adb=True, serial=MDNS)), "dev")
        connect.assert_called_once_with(adb=True, serial=MDNS)

    def test_connection_error_exits_with_the_message(self):
        with mock.patch.object(perf.tv, "connect", side_effect=tv.TvError("adb lists no device")), \
                self.assertRaises(SystemExit) as e:
            perf.main(["run", "--label", "x"])
        self.assertEqual(e.exception.code, "perf.py: adb lists no device")


FAKE_ADB = r'''
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_ADB_LOG"], "a") as f:
    f.write(json.dumps(args) + "\n")
if args == ["devices"]:
    sys.stdout.write(os.environ["FAKE_ADB_DEVICES"])
elif args[:1] == ["-s"] and args[1] in os.environ["FAKE_ADB_DEVICES"]:
    cmd = args[2:]
    if cmd == ["get-state"]:
        print("device")
    elif cmd[0] == "push":
        print("%s: 1 file pushed, 0 skipped." % cmd[1])
    elif cmd[0] == "shell":
        line = cmd[1]
        props = {"ro.product.model": "Smart TV Pro", "ro.product.manufacturer": "TCL"}
        if line.startswith("getprop "):
            print(props.get(line.split()[1], ""))
        elif line == "pm list packages -d":
            print("package:com.example.one")
        elif line.startswith("pm path"):
            sys.exit(1)
else:
    sys.stderr.write("adb: device '%s' not found\n" % args[1])
    sys.exit(1)
'''


@unittest.skipIf(os.name == "nt" or not shutil.which("bash"), "needs a POSIX shell")
class ScriptTests(unittest.TestCase):
    """tv.py and the shell scripts against a fake adb binary on PATH, without TV_HOST."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        bindir = os.path.join(self.tmp, "bin")
        os.makedirs(bindir)
        with open(os.path.join(bindir, "fake_adb.py"), "w") as f:
            f.write(FAKE_ADB)
        self.wrapper(os.path.join(bindir, "adb"), os.path.join(bindir, "fake_adb.py"))
        self.log = os.path.join(self.tmp, "adb.log")
        self.env = {k: v for k, v in os.environ.items()
                    if k not in ("TV_HOST", "TV_PORT", "TV_TRANSPORT", "ANDROID_SERIAL", "ANDROID_HOME",
                                 "ANDROID_SDK_ROOT")}
        self.env.update(PATH=bindir + os.pathsep + os.environ.get("PATH", ""), FAKE_ADB_LOG=self.log,
                        FAKE_ADB_DEVICES=DEVICES)
        # A copy of the repo's tools with a "venv" python that is this interpreter.
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(self.repo, "tools", "venv", "bin"))
        for name in ("tv.py", "b64push.py", "disable.sh", "restore.sh"):
            shutil.copy(os.path.join(TOOLS, name), os.path.join(self.repo, "tools", name))
        self.wrapper(os.path.join(self.repo, "tools", "venv", "bin", "python"))

    @staticmethod
    def wrapper(path, script=None):
        with open(path, "w") as f:
            f.write('#!/bin/sh\nexec "%s" %s"$@"\n' % (sys.executable, '"%s" ' % script if script else ""))
        os.chmod(path, 0o755)

    def run_tool(self, argv, stdin=b""):
        r = subprocess.run(argv, input=stdin, capture_output=True, env=self.env, timeout=60)
        return r.returncode, r.stdout.decode(), r.stderr.decode()

    def adb_calls(self):
        with open(self.log) as f:
            return [json.loads(line) for line in f]

    def shell_commands(self):
        return [a[3] for a in self.adb_calls() if a[:1] == ["-s"] and a[2] == "shell"]

    def test_probe_without_tv_host(self):
        code, out, err = self.run_tool([sys.executable, "-I", os.path.join(self.repo, "tools", "tv.py"), "--probe"])
        self.assertEqual(code, 0, err)
        self.assertIn("ro.product.model = Smart TV Pro", out)
        self.assertIn("device " + MDNS, err)
        self.assertEqual(self.adb_calls()[0], ["devices"])
        self.assertTrue(all(a[:2] == ["-s", MDNS] for a in self.adb_calls()[1:]))   # the space survives

    def test_batch_keeps_reading_stdin(self):
        code, out, err = self.run_tool([sys.executable, "-I", os.path.join(self.repo, "tools", "tv.py"), "--batch"],
                                       b"getprop ro.product.model\npm path com.example.none\npm list packages -d\n")
        self.assertEqual(code, 0, err)
        self.assertEqual(out, "$ getprop ro.product.model\nSmart TV Pro\n$ pm path com.example.none\n"
                              "$ pm list packages -d\npackage:com.example.one\n")

    def test_no_device(self):
        self.env["FAKE_ADB_DEVICES"] = "List of devices attached\n\n"
        code, _, err = self.run_tool([sys.executable, "-I", os.path.join(self.repo, "tools", "tv.py"), "--probe"])
        self.assertEqual(code, 1)
        self.assertIn("tv.py: adb lists no device", err)

    def test_b64push_uses_adb_push(self):
        code, out, err = self.run_tool([sys.executable, "-I", os.path.join(self.repo, "tools", "b64push.py"), __file__,
                                        "/data/local/tmp/x.py"])
        self.assertEqual(code, 1, err)   # the fake TV has no md5sum, so no MATCH; the push itself happened
        self.assertIn(["-s", MDNS, "push", __file__, "/data/local/tmp/x.py"], self.adb_calls())
        self.assertNotIn("echo '", "".join(self.shell_commands()))

    def write_list(self, text):
        path = os.path.join(self.tmp, "debloat.txt")
        with open(path, "w") as f:
            f.write(text)
        return path

    def test_disable_sh(self):
        lst = self.write_list("# Example TV\ncom.example.one   # trailing comment\n\n  # indented comment\n"
                              "com.example.two\n")
        code, out, err = self.run_tool(["bash", os.path.join(self.repo, "tools", "disable.sh"), lst])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.shell_commands(), ["pm disable-user --user 0 com.example.one",
                                                 "pm disable-user --user 0 com.example.two",
                                                 "pm list packages -d"])
        self.assertIn("$ pm disable-user --user 0 com.example.one\n", out)

    def test_restore_sh(self):
        lst = self.write_list("com.example.one # trailing comment\n")
        code, out, err = self.run_tool(["bash", os.path.join(self.repo, "tools", "restore.sh"), lst,
                                        "com.google.android.apps.tv.launcherx"])
        self.assertEqual(code, 0, err)
        cmds = self.shell_commands()
        self.assertEqual(cmds[:2], ["pm enable com.example.one", "pm enable com.google.android.apps.tv.launcherx"])
        self.assertTrue(cmds[2].startswith("s=$(settings get secure enabled_accessibility_services"))
        self.assertTrue(cmds[2].endswith("echo home-button-service-off"))
        self.assertTrue(cmds[3].startswith("appops set com.gathin.tvhome AUTO_START default"))
        self.assertTrue(cmds[4].startswith("pm revoke com.gathin.tvhome android.permission.DUMP"))
        self.assertIn("com.google.android.apps.tv.launcherx | tail -n 1)", cmds[5])
        self.assertEqual(cmds[-1], "pm list packages -d")
        self.assertEqual(len(cmds), 10)
        self.assertFalse(any("force-stop" in c for c in cmds))

    def test_restore_sh_with_an_empty_list(self):
        # Nothing to re-enable, but the stock launcher, the service and the animations are still restored.
        lst = self.write_list("# nothing disabled on this TV\n\n")
        code, out, err = self.run_tool(["bash", os.path.join(self.repo, "tools", "restore.sh"), lst])
        self.assertEqual(code, 0, err)
        cmds = self.shell_commands()
        self.assertEqual(cmds[0], "pm enable com.google.android.tvlauncher")
        self.assertEqual((len(cmds), cmds[-1]), (9, "pm list packages -d"))

    def test_disable_sh_with_an_empty_list(self):
        code, out, err = self.run_tool(["bash", os.path.join(self.repo, "tools", "disable.sh"),
                                        self.write_list("# nothing yet\n")])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.shell_commands(), ["pm list packages -d"])

    def test_missing_list_still_fails(self):
        code, _, _ = self.run_tool(["bash", os.path.join(self.repo, "tools", "restore.sh"),
                                    os.path.join(self.tmp, "missing.txt")])
        self.assertNotEqual(code, 0)
        self.assertFalse(os.path.exists(self.log) and self.shell_commands())

    def test_scripts_parse(self):
        for path in ("tools/disable.sh", "tools/restore.sh", "launcher/build-and-install.sh"):
            self.assertEqual(subprocess.run(["bash", "-n", os.path.join(REPO, path)]).returncode, 0, path)
        with open(os.path.join(REPO, "launcher", "build-and-install.sh")) as f:
            self.assertNotIn("TV_HOST:?", f.read())


if __name__ == "__main__":
    unittest.main()


class BoundWorktreeTests(unittest.TestCase):
    """A worktree bound to an emulator (tools/emulator.py start) talks to nothing else."""

    def bound(self, text):
        root = tempfile.mkdtemp()
        with open(os.path.join(root, tv.DEVICE_FILE), "w") as f:
            f.write(text)
        return root

    def test_unbound(self):
        self.assertIsNone(tv.bound_device(tempfile.mkdtemp()))

    def test_goes_to_the_bound_emulator_whatever_the_environment_says(self):
        root = self.bound("emulator-5556\n")
        with mock.patch.object(tv.AdbBinary, "open", return_value="dev") as open_:
            env = {"TV_HOST": "192.168.8.23", "ANDROID_SERIAL": "192.168.8.23:5555"}
            self.assertEqual(tv.connect(env=env, root=root), "dev")
        open_.assert_called_once_with("emulator-5556", None, None, env)

    def test_refuses_another_device(self):
        root = self.bound("emulator-5556\n")
        with mock.patch.object(tv.AdbBinary, "open") as open_:
            with self.assertRaisesRegex(tv.TvError, "bound to emulator-5556"):
                tv.connect(serial="192.168.8.23:5555", root=root)
        open_.assert_not_called()

    def test_a_file_that_names_a_tv_is_an_error(self):
        root = self.bound("192.168.8.23:5555\n")
        with self.assertRaisesRegex(tv.TvError, "not an emulator"):
            tv.connect(root=root)
