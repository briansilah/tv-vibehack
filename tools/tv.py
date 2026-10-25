#!/usr/bin/env python3
"""Run shell commands on an Android TV over ADB, with the adb binary or the pure-Python adb_shell client.

    tv.py --probe                      connect and print model / Android version (also with no arguments)
    tv.py <shell command...>           run one shell command
    tv.py --batch < commands.txt       one command per line, each run on its own, printed as "$ <command>"
    tv.py --push <local> <remote>      with adb_shell, small files only (b64push.py handles APKs)
    tv.py --pull <remote> <local>

How it connects (options go before the command):
    TV_HOST set              adb_shell over TCP to TV_HOST:TV_PORT (port 5555 by default). No adb binary needed,
                             only `pip install -r tools/requirements.txt`. For TVs with network ADB on port 5555,
                             and for macOS setups that block the adb binary.
    TV_HOST unset            the adb binary (PATH, ANDROID_HOME, ANDROID_SDK_ROOT or Android Studio's SDK folder)
                             with the device in ANDROID_SERIAL, or else the only device `adb devices` lists.
                             Needed for Android 11+ wireless debugging (pair with `adb pair` first) and USB.
    --adb, TV_TRANSPORT=adb  the adb binary even with TV_HOST set; it runs `adb connect TV_HOST:TV_PORT` first.
    --serial SERIAL          the adb binary with this device.
    TV_TRANSPORT=adb_shell   adb_shell; needs TV_HOST.

A worktree bound to an emulator (a .tvhome-device file at its root, written by tools/emulator.py start) talks only
to that emulator, whatever TV_HOST, ANDROID_SERIAL or --serial say: runs that must never touch a TV can't.
"""
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KEYDIR = os.path.join(HERE, "adbkey")
KEY = os.path.join(KEYDIR, "adbkey")
DEVICE_FILE = ".tvhome-device"   # see the module docstring; tools/emulator.py writes it
DEFAULT_PORT = 5555
PROBE_PROPS = ("ro.product.model", "ro.product.device", "ro.build.version.release",
               "ro.build.version.sdk", "ro.build.display.id", "ro.product.manufacturer")
VENV_HINT = "python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"
NO_DEVICE = ("adb lists no device. For wireless debugging (Android 11+): on the TV open Developer options → "
             "Wireless debugging → Pair device with pairing code, then run `adb pair <tv ip>:<pairing port>` and "
             "`adb connect <tv ip>:<port>` (`adb mdns services` lists both). Or set TV_HOST=<tv ip> to use "
             "network ADB on port 5555 without the adb binary.")


class TvError(Exception):
    """A connection problem, worded for the user."""


# ---- choosing a transport ----------------------------------------------------------------------

def choose_transport(env, adb=False):
    """'adb' (the adb binary) or 'adb_shell', from --adb, TV_TRANSPORT and TV_HOST."""
    want = "adb" if adb else (env.get("TV_TRANSPORT") or "auto").strip().lower().replace("-", "_")
    if want not in ("auto", "adb", "adb_shell"):
        raise TvError("TV_TRANSPORT must be adb or adb_shell, not %r" % env.get("TV_TRANSPORT"))
    if want == "auto":
        return "adb_shell" if env.get("TV_HOST") else "adb"
    if want == "adb_shell" and not env.get("TV_HOST"):
        raise TvError("TV_TRANSPORT=adb_shell needs TV_HOST=<tv ip>")
    return want


def tv_port(env):
    try:
        return int(env.get("TV_PORT") or DEFAULT_PORT)
    except ValueError:
        raise TvError("TV_PORT must be a port number, not %r" % env.get("TV_PORT")) from None


def bound_device(root=None):
    """The emulator this worktree is bound to (DEVICE_FILE), or None."""
    path = os.path.join(root or os.path.dirname(HERE), DEVICE_FILE)
    try:
        with open(path) as f:
            serial = f.read().strip()
    except OSError:
        return None
    if not re.match(r"^emulator-\d+$", serial):
        raise TvError("%s names %r, not an emulator: delete it, or bind with tools/emulator.py start" % (path, serial))
    return serial


def connect(adb=False, serial=None, timeout=120, env=None, root=None):
    """Connect as the module docstring describes. The result has shell(), push(), pull() and close()."""
    env = os.environ if env is None else env
    bound = bound_device(root)
    if bound:
        if serial and serial != bound:
            raise TvError("this worktree is bound to %s (%s), so it won't talk to %s"
                          % (bound, DEVICE_FILE, serial))
        return AdbBinary.open(bound, None, None, env)
    if choose_transport(env, adb or bool(serial)) == "adb_shell":
        return AdbShell(env["TV_HOST"], tv_port(env), timeout)
    host = env.get("TV_HOST")
    return AdbBinary.open(serial or env.get("ANDROID_SERIAL"), host, tv_port(env) if host else None, env)


# ---- the adb binary ----------------------------------------------------------------------------

def find_adb(env=None):
    env = os.environ if env is None else env
    exe = "adb.exe" if os.name == "nt" else "adb"
    candidates = [shutil.which(exe)]
    homes = [env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT"), os.path.expanduser("~/Library/Android/sdk"),
             os.path.expanduser("~/Android/Sdk")]
    if env.get("LOCALAPPDATA"):
        homes.append(os.path.join(env["LOCALAPPDATA"], "Android", "Sdk"))
    candidates += [os.path.join(h, "platform-tools", exe) for h in homes if h]
    for c in candidates:
        if c and os.path.isfile(c):
            return c
    return None


def run_adb(argv, timeout):
    """Run adb with no stdin (it would swallow the rest of a --batch) → (exit code, stdout and stderr)."""
    try:
        r = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=timeout)
    except subprocess.TimeoutExpired:
        raise TvError("no answer from `adb %s` within %g s" % (" ".join(argv[1:]), timeout)) from None
    except OSError as e:
        raise TvError("could not run %s: %s" % (argv[0], e)) from None
    return r.returncode, r.stdout.decode("utf-8", "replace")


def parse_devices(out):
    """`adb devices` → [(serial, state)]. Serials can contain spaces (`adb-… (2)._adb-tls-connect._tcp`), so
    split on the tab only."""
    devices = []
    for line in out.splitlines():
        if "\t" in line:
            serial, state = line.split("\t", 1)
            devices.append((serial, state.strip()))
    return devices


def _state_hint(serial, state):
    if state == "unauthorized":
        return "%s is unauthorized: accept the \"Allow debugging?\" prompt on the TV and run this again" % serial
    if state == "offline":
        return ("%s is offline: reconnect it (`adb disconnect`, then `adb connect`, or turn wireless debugging "
                "off and on)" % serial)
    return "%s is %s, not ready" % (serial, state)


def pick_device(devices, serial=None, note=""):
    """The serial to use: the requested one if it's ready, else the only ready device. TvError otherwise."""
    listing = ", ".join("%s (%s)" % d for d in devices) or "none"
    if serial:
        state = dict(devices).get(serial)
        if state == "device":
            return serial
        if state:
            raise TvError(_state_hint(serial, state))
        raise TvError("adb has no device %r. %sAttached: %s" % (serial, note + ". " if note else "", listing))
    ready = [s for s, state in devices if state == "device"]
    if len(ready) == 1:
        return ready[0]
    if ready:
        raise TvError("adb has %d devices: %s. Pick one with ANDROID_SERIAL='<serial>' (or --serial, "
                      "or -Serial in the PowerShell scripts)" % (len(ready), ", ".join(ready)))
    if devices:
        raise TvError(_state_hint(*devices[0]))
    raise TvError(NO_DEVICE)


class AdbBinary:
    """The adb binary, one adb process per command."""
    native_push = True

    def __init__(self, exe, serial):
        self.exe, self.serial = exe, serial

    @classmethod
    def open(cls, serial=None, host=None, port=None, env=None):
        exe = find_adb(env)
        if not exe:
            raise TvError("adb not found. Install Android platform-tools (or set ANDROID_HOME to the SDK), "
                          "or set TV_HOST=<tv ip> to use network ADB on port 5555 without the adb binary.")
        note = ""
        if host and not serial:
            serial = "%s:%d" % (host, port or DEFAULT_PORT)
            note = "adb connect %s: %s" % (serial, run_adb([exe, "connect", serial], 30)[1].strip())
        devices = parse_devices(run_adb([exe, "devices"], 30)[1])
        if serial and serial not in dict(devices):
            # adb also matches serials loosely (a host without :5555, usb:…, model:…); let it decide.
            dev = cls(exe, serial)
            if dev.attached():
                return dev
        return cls(exe, pick_device(devices, serial, note))

    def describe(self):
        return "adb binary %s, device %s" % (self.exe, self.serial)

    def _adb(self, args, timeout):
        return run_adb([self.exe, "-s", self.serial] + args, timeout)

    def attached(self):
        code, out = self._adb(["get-state"], 30)
        return code == 0 and out.strip() == "device"

    def shell(self, cmd, timeout=120):
        code, out = self._adb(["shell", cmd], timeout)
        # adb passes on the command's exit status; adb_shell can't, so only a lost device is an error here.
        if code != 0 and not self.attached():
            raise TvError("lost the connection to %s: %s" % (self.serial, out.strip()))
        return out

    def push(self, local, remote, timeout=300):
        code, out = self._adb(["push", local, remote], timeout)
        if code != 0:
            raise TvError("adb push %s %s failed: %s" % (local, remote, out.strip()))
        return out

    def pull(self, remote, local, timeout=300):
        code, out = self._adb(["pull", remote, local], timeout)
        if code != 0:
            raise TvError("adb pull %s %s failed: %s" % (remote, local, out.strip()))
        return out

    def close(self):
        pass


# ---- adb_shell ---------------------------------------------------------------------------------

def get_signer():
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner
    os.makedirs(KEYDIR, exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    return PythonRSASigner(pub, priv)


class AdbShell:
    """adb_shell: ADB over TCP in pure Python, for setups where the adb binary is blocked or missing."""
    native_push = False   # its push hangs on larger files; b64push.py sends those as base64 through the shell

    def __init__(self, host, port=DEFAULT_PORT, timeout=120):
        try:
            from adb_shell.adb_device import AdbDeviceTcp
        except ImportError:
            raise TvError("TV_HOST is set, which connects through the adb_shell package. Install it with\n  %s\n"
                          "or unset TV_HOST to use the adb binary." % VENV_HINT) from None
        self.host, self.port = host, port
        self.dev = AdbDeviceTcp(host, port, default_transport_timeout_s=float(timeout))
        try:
            self.dev.connect(rsa_keys=[get_signer()], transport_timeout_s=float(timeout), auth_timeout_s=timeout)
        except Exception as e:
            if "STLS" in str(e):   # the TV asked for TLS: a wireless debugging port
                hint = ("That is a wireless debugging (TLS) port, which adb_shell can't use. Unset TV_HOST to use "
                        "the adb binary, or set TV_TRANSPORT=adb to `adb connect` it.")
            else:
                hint = ("Is network debugging on? A TV with only wireless debugging (Android 11+) needs the adb "
                        "binary: unset TV_HOST.")
            raise TvError("could not connect to %s:%d (TV_HOST:TV_PORT) through adb_shell: %s: %s\n%s"
                          % (host, port, type(e).__name__, e, hint)) from None

    def describe(self):
        return "adb_shell, %s:%d" % (self.host, self.port)

    def shell(self, cmd, timeout=120):
        return self.dev.shell(cmd, read_timeout_s=float(timeout))

    def push(self, local, remote, timeout=300):
        self.dev.push(local, remote, transport_timeout_s=float(timeout), read_timeout_s=float(timeout))

    def pull(self, remote, local, timeout=300):
        self.dev.pull(remote, local, transport_timeout_s=float(timeout), read_timeout_s=float(timeout))

    def close(self):
        self.dev.close()


# ---- command line ------------------------------------------------------------------------------

def parse_options(args):
    """Leading --adb / --serial SERIAL → (adb, serial, the rest)."""
    args = list(args)
    adb, serial = False, None
    while args and (args[0] in ("--adb", "--serial") or args[0].startswith("--serial=")):
        opt = args.pop(0)
        if opt == "--adb":
            adb = True
        elif opt == "--serial":
            if not args:
                raise TvError("--serial needs a device serial (see `adb devices`)")
            serial = args.pop(0)
        else:
            serial = opt.split("=", 1)[1]
    return adb, serial, args


def run(dev, args):
    if not args or args == ["--probe"]:
        print("tv.py: connected with " + dev.describe(), file=sys.stderr)
        for p in PROBE_PROPS:
            print(f"{p} = {dev.shell(f'getprop {p}').strip()}")
    elif args[0] == "--push" and len(args) == 3:
        dev.push(args[1], args[2])
        print(f"pushed {args[1]} -> {args[2]}")
    elif args[0] == "--pull" and len(args) == 3:
        dev.pull(args[1], args[2])
        print(f"pulled {args[1]} -> {args[2]}")
    elif args[0] == "--batch":
        # one shell command per stdin line, each run separately.
        # utf-8-sig drops the BOM Windows PowerShell may prepend to piped stdin.
        sys.stdin.reconfigure(encoding="utf-8-sig")
        for line in sys.stdin:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            out = dev.shell(line, timeout=120)
            sys.stdout.write(f"$ {line}\n{out}")
            if out and not out.endswith("\n"):
                sys.stdout.write("\n")
            sys.stdout.flush()
    else:
        sys.stdout.write(dev.shell(" ".join(args), timeout=120))


def main(argv=None):
    try:
        adb, serial, args = parse_options(sys.argv[1:] if argv is None else argv)
        dev = connect(adb=adb, serial=serial)
        try:
            run(dev, args)
        finally:
            dev.close()
    except TvError as e:
        sys.exit("tv.py: %s" % e)


if __name__ == "__main__":
    main()
