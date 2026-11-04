"""Offline tests for tools/emulator.py: slots, ports, queue timeouts, bindings, reaping and the measuring lock,
against a fake Mac.

    python3 -m unittest discover -s tools/tests -v
"""
import contextlib
import io
import os
import signal
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import emulator as em  # noqa: E402

QEMU = "/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64 -avd tvperf -read-only -port %d -no-window"


class FakeHost:
    """A Mac with some processes, an adb that lists emulators, and a clock that moves only when asked."""

    def __init__(self, others=(), boots_after=2):
        self.clock = 1000.0
        self.procs = {}                 # pid: (process group, command line)
        self.next_pid = 500
        self.killed = []
        self.launched = []
        self.boots_after = boots_after  # booted() calls before it says yes
        self.boot_calls = {}
        self.busy_ports = set()
        self.port_thief = None          # a pid that grabs the next launched emulator's port
        for port in others:             # emulators started some other way
            pid = self._pid()
            self.procs[pid] = (pid, QEMU % port)

    def _pid(self):
        self.next_pid += 1
        return self.next_pid

    def processes(self):
        return [(pid, pgid, cmd) for pid, (pgid, cmd) in self.procs.items()]

    def listeners(self, port):
        if self.port_thief:
            return {self.port_thief}
        return {pid for pid, (_, cmd) in self.procs.items() if "-port %d " % port in cmd + " "}

    def adb_serials(self):
        return ["emulator-%s" % em.PORT_ARG.search(c).group(1) for _, c in self.procs.values() if em.QEMU.search(c)]

    def port_busy(self, port):
        return port in self.busy_ports

    def launch(self, argv, log):
        pid = self._pid()               # the emulator becomes qemu, leading a group of its own, as on macOS
        self.procs[pid] = (pid, QEMU % int(argv[argv.index("-port") + 1]))
        self.procs[self._pid()] = (pid, "/sdk/emulator/netsimd")
        self.launched.append(argv)
        return pid

    def killpg(self, pgid, sig):
        self.killed.append((pgid, sig))
        self.procs = {p: v for p, v in self.procs.items() if v[0] != pgid}

    def booted(self, serial):
        self.boot_calls[serial] = self.boot_calls.get(serial, 0) + 1
        return self.boot_calls[serial] > self.boots_after

    def now(self):
        return self.clock

    def sleep(self, s):
        self.clock += s


def slots(host, maximum=3, directory=None, root="/worktrees/a"):
    return em.Slots(directory or tempfile.mkdtemp(), host, log=lambda m: None, maximum=maximum, root=root)


class SlotTest(unittest.TestCase):
    def test_adb_reserved_port_is_skipped_without_a_visible_qemu_or_listener(self):
        host = FakeHost()
        with tempfile.TemporaryDirectory() as d, \
                mock.patch.object(host, "adb_serials", return_value=["emulator-5554", "192.0.2.1:5555"]):
            slot = slots(host, directory=d).start("daily-testing", emulator="emulator")
        self.assertEqual(slot["port"], 5556)
        self.assertEqual(len(host.launched), 1)

    def test_starting_emulator_reserves_capacity_before_qemu_appears(self):
        host = FakeHost()
        with tempfile.TemporaryDirectory() as d:
            s = slots(host, maximum=1, directory=d)
            first = s.claim("daily-testing", em.DEFAULT_AVD, 6, "emulator")
            host.procs[first["pid"]] = (first["pgid"], "/sdk/emulator/emulator -port %d" % first["port"])
            self.assertIsNone(s.claim("daily-ui", em.DEFAULT_AVD, 6, "emulator"))
            self.assertEqual(len(host.launched), 1)

    def test_requested_queue_timeout_expires_without_starting_or_stopping_an_emulator(self):
        host = FakeHost(others=[5554])
        began = host.now()
        with tempfile.TemporaryDirectory() as d:
            s = slots(host, maximum=1, directory=d)
            with self.assertRaisesRegex(em.EmulatorError, "no slot came free"):
                s.start("daily-testing", timeout=60, emulator="emulator")
        self.assertGreaterEqual(host.now() - began, 60)
        self.assertLessEqual(host.now() - began, 90)
        self.assertEqual(host.launched, [])
        self.assertEqual(host.killed, [])
        self.assertEqual([cmd for _, cmd in host.procs.values()], [QEMU % 5554])

    def test_start_takes_a_free_port_and_waits_for_the_boot(self):
        host = FakeHost(others=[5554])
        host.busy_ports.add(5556)       # something else listens there
        s = slots(host)
        slot = s.start("daily-ux", emulator="emulator")
        self.assertEqual(slot["serial"], "emulator-5558")
        self.assertEqual(host.launched[0], ["emulator", "-avd", "tvperf", "-read-only", "-port", "5558",
                                            "-no-window", "-no-audio", "-no-boot-anim"])
        self.assertEqual(host.boot_calls["emulator-5558"], 3)
        self.assertIn("emulator-5558 (daily-ux", s.describe())
        self.assertIn("emulator-5554 (started some other way)", s.describe())

    def test_every_running_emulator_counts(self):
        host = FakeHost(others=[5554, 5556])
        s = slots(host)
        s.start("daily-ui", emulator="emulator")
        with self.assertRaisesRegex(em.EmulatorError, "all 3 slots are in use"):
            s.start("daily-ux", wait=False, emulator="emulator")

    def test_waits_until_one_stops(self):
        host = FakeHost(others=[5554, 5556, 5558])
        s = slots(host)
        sleeps = []

        def sleep(sec):
            sleeps.append(sec)
            host.clock += sec
            if len(sleeps) == 3:        # someone stops theirs
                host.procs = {p: v for p, v in host.procs.items() if "-port 5556" not in v[1]}
        host.sleep = sleep
        slot = s.start("daily-ux", emulator="emulator")
        self.assertEqual(slot["port"], 5556)
        self.assertEqual(sleeps[:3], [30, 30, 30])

    def test_stop_only_what_it_started(self):
        host = FakeHost(others=[5554])
        s = slots(host)
        slot = s.start("daily-ux", emulator="emulator")
        with self.assertRaisesRegex(em.EmulatorError, "never someone else's"):
            s.stop("emulator-5554")
        s.stop(slot["serial"])
        self.assertEqual([c for _, c in host.procs.values()], [QEMU % 5554])
        self.assertEqual(s.describe(), "emulator-5554 (started some other way)")
        self.assertEqual(host.killed, [(slot["pgid"], signal.SIGTERM)])     # its own group, nothing else

    def test_an_emulator_past_its_hours_is_stopped_by_the_next_start(self):
        host = FakeHost()
        s = slots(host, maximum=1)
        first = s.start("daily-ux", hours=2, emulator="emulator")
        host.clock += 2 * 3600 + 1
        second = s.start("daily-ui", wait=False, emulator="emulator")
        self.assertEqual(second["port"], first["port"])
        self.assertIn((first["pgid"], signal.SIGTERM), host.killed)

    def test_a_slot_whose_emulator_died_is_forgotten(self):
        host = FakeHost()
        s = slots(host, maximum=1)
        first = s.start("daily-ux", emulator="emulator")
        host.procs = {}                 # crashed, or stopped by hand
        self.assertEqual(s.start("daily-ui", wait=False, emulator="emulator")["port"], first["port"])

    def test_never_takes_someone_elses_emulator_on_its_old_port_for_its_own(self):
        host = FakeHost()
        s = slots(host)
        first = s.start("daily-ux", emulator="emulator")
        host.procs = {}                 # it died; someone starts one by hand on the same port
        host.procs[900] = (900, QEMU % first["port"])
        self.assertNotIn("daily-ux", s.describe())
        s.stop(first["serial"])                         # its own dead group only
        s.start("daily-ui", emulator="emulator")
        self.assertIn(900, host.procs)
        self.assertFalse([k for k in host.killed if k[0] == 900])

    def test_a_port_someone_else_grabbed_is_given_up(self):
        host = FakeHost()
        host.port_thief = 4242
        s = slots(host)
        with self.assertRaisesRegex(em.EmulatorError, "taken by another process"):
            s.start("daily-ux", emulator="emulator")
        self.assertEqual([c for _, c in host.procs.values()], [])

    def test_an_old_owner_cant_stop_the_serials_next_owner(self):
        host = FakeHost()
        d = tempfile.mkdtemp()
        a, b = slots(host, directory=d, root="/worktrees/a"), slots(host, directory=d, root="/worktrees/b")
        first = a.start("black-art", emulator="emulator")
        host.procs = {}                                 # a's emulator is gone (stopped, crashed or reaped)
        second = b.start("daily-reliability", emulator="emulator")
        self.assertEqual(second["serial"], first["serial"])      # the same port, so the same serial
        host.killed.clear()
        with self.assertRaisesRegex(em.EmulatorError, "daily-reliability's now"):
            a.stop(first["serial"])
        with self.assertRaisesRegex(em.EmulatorError, "daily-reliability's now"):
            a.renew(first["serial"], 4)
        self.assertEqual(host.killed, [])
        b.stop(second["serial"])
        self.assertEqual(host.killed, [(second["pgid"], signal.SIGTERM)])

    def test_renew(self):
        host = FakeHost()
        s = slots(host, maximum=1)
        first = s.start("upstream-sync", hours=1, emulator="emulator")
        host.clock += 3000
        s.renew(first["serial"], 2)
        host.clock += 3000                              # past the first hour, within the renewal
        with self.assertRaisesRegex(em.EmulatorError, "all 1 slots are in use"):
            s.start("daily-ui", wait=False, emulator="emulator")

    def test_one_that_never_boots_is_stopped(self):
        host = FakeHost(boots_after=10 ** 6)
        s = slots(host)
        with self.assertRaisesRegex(em.EmulatorError, "didn't boot"):
            s.start("daily-ux", emulator="emulator")
        self.assertEqual(host.procs, {})
        self.assertIn("none", s.describe())


@unittest.skipIf(os.name == "nt", "fcntl")
class ExclusiveTest(unittest.TestCase):
    def test_runs_the_command_and_returns_its_code(self):
        d = tempfile.mkdtemp()
        self.assertEqual(em.exclusive(d, "t", [sys.executable, "-c", "raise SystemExit(3)"], log=lambda m: None), 3)

    def test_one_at_a_time(self):
        d = tempfile.mkdtemp()
        out = os.path.join(d, "order")
        cmd = [sys.executable, "-c", "import sys,time; open(sys.argv[1],'a').write('<'); time.sleep(0.4); "
               "open(sys.argv[1],'a').write('>')", out]
        threads = [threading.Thread(target=em.exclusive, args=(d, "t%d" % i, cmd), kwargs={"log": lambda m: None})
                   for i in range(2)]
        for t in threads:
            t.start()
            time.sleep(0.05)
        for t in threads:
            t.join()
        with open(out) as f:
            self.assertEqual(f.read(), "<><>")


class CliTest(unittest.TestCase):
    def test_unbind_preserves_a_different_serial_then_removes_the_matching_one(self):
        with tempfile.TemporaryDirectory() as root:
            em.bind("emulator-5556", root)
            em.unbind("emulator-5554", root)
            self.assertEqual(em.tv.bound_device(root), "emulator-5556")
            em.unbind("emulator-5556", root)
            self.assertFalse(os.path.exists(em.bind_file(root)))
            em.unbind("emulator-5556", root)

    def test_start_status_stop(self):
        host = FakeHost()
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as root, \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()), \
                mock.patch.dict(os.environ, {em.STATE_ENV: d}), \
                mock.patch.object(em, "find_emulator", return_value="emulator"), \
                mock.patch.object(em, "bind_file", lambda r=None: os.path.join(root, em.tv.DEVICE_FILE)):
            self.assertEqual(em.main(["start", "--owner", "daily-ux"], host=host), 0)
            with open(os.path.join(root, em.tv.DEVICE_FILE)) as f:
                self.assertEqual(f.read().strip(), "emulator-5554")        # the worktree is bound to it
            self.assertEqual(em.tv.bound_device(root), "emulator-5554")
            em.main(["status"], host=host)
            em.main(["stop", "emulator-5554"], host=host)
            self.assertFalse(os.path.exists(os.path.join(root, em.tv.DEVICE_FILE)))
        self.assertEqual(out.getvalue().splitlines()[0], "emulator-5554")
        self.assertIn("emulator-5554 (daily-ux", out.getvalue())


if __name__ == "__main__":
    unittest.main()
