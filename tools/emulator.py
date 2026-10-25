#!/usr/bin/env python3
"""Share this Mac's Android TV emulators between sessions that run at the same time (the daily improvement runs,
the upstream sync, anyone's own work): a few emulators at most, each on a port nobody else uses, stopped when
their run ends or, if it never says so, when their time is up.

    emulator.py start --owner daily-ux       wait for a slot, start an emulator in it, print its serial once booted,
                                             and bind this worktree to it
    emulator.py renew emulator-5560 --hours 4    keep it past its time
    emulator.py stop emulator-5560           stop an emulator this tool started, and unbind the worktree
    emulator.py status                       the slots, and emulators started some other way
    emulator.py exclusive -- CMD ...         run CMD while no other session runs one (perf.py measurements)

A slot is the emulator (about 2 GB) and the Gradle build that goes with it (up to 2 GB more): build only while you
hold one, so a few sessions at once don't run this Mac out of memory. Every running emulator counts, whoever
started it. At most TVHOME_MAX_EMULATORS (3) run at once. The emulator runs on its own, so it outlives the
command that started it: stop it when you're done. Past its --hours (renew to keep it), the next `start` stops it.
The tool owns exactly the process group it launched, and checks that its port's listener is that group's, so it
never mistakes or stops someone else's emulator.

`start` binds the worktree it runs from to the emulator: it writes the serial to .tvhome-device at the worktree's
root (git-ignored). tools/tv.py, and every tool that connects through it (perf.py, walkthrough.py, b64push.py,
launcher/build-and-install.sh), then talks only to that emulator, whatever ANDROID_SERIAL or TV_HOST say, so a run
that must never touch the TV can't, even from a fresh shell. `stop` removes the file.

`exclusive` keeps perf.py's emulator numbers comparable: one measurement at a time across the Mac, and main and
yours measured back to back in the same hold. The lock goes with the process, so a crash can't leave it held.

State: $(git rev-parse --git-common-dir)/tvhome-emulators, shared by every worktree (TVHOME_EMULATORS overrides it).
"""
import argparse
import datetime
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tv  # noqa: E402

STATE_ENV = "TVHOME_EMULATORS"
MAX_ENV = "TVHOME_MAX_EMULATORS"
DEFAULT_MAX = 3
PORTS = range(5554, 5586, 2)          # the emulator's own range: adb looks for emulators only there
DEFAULT_AVD = "tvperf"
BOOT_TIMEOUT = 360
QEMU = re.compile(r"qemu-system", re.I)
PORT_ARG = re.compile(r"(?:^|\s)-port\s+(\d+)")


class EmulatorError(Exception):
    """Worded for the session that asked."""


def state_dir():
    if os.environ.get(STATE_ENV):
        return os.environ[STATE_ENV]
    try:
        common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=HERE,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True).stdout.strip()
    except OSError:
        common = ""
    return os.path.join(common, "tvhome-emulators") if common else os.path.expanduser("~/.cache/tvhome-emulators")


def find_emulator(env=None):
    env = os.environ if env is None else env
    exe = "emulator.exe" if os.name == "nt" else "emulator"
    homes = [env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT"), os.path.expanduser("~/Library/Android/sdk"),
             os.path.expanduser("~/Android/Sdk")]
    for c in [os.path.join(h, "emulator", exe) for h in homes if h] + [shutil.which(exe)]:
        if c and os.path.isfile(c):
            return c
    raise EmulatorError("the Android emulator isn't installed: sdkmanager emulator, or set ANDROID_HOME")


class Host:
    """What the tool touches on this Mac; tests replace it."""

    def __init__(self):
        self.adb = tv.find_adb()

    def processes(self):
        """[(pid, process group, command line)] of every process."""
        out = subprocess.run(["ps", "-axo", "pid=,pgid=,command="], stdout=subprocess.PIPE, text=True).stdout
        rows = []
        for line in out.splitlines():
            parts = line.split(None, 2)
            if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
                rows.append((int(parts[0]), int(parts[1]), parts[2]))
        return rows

    def listeners(self, port):
        """The pids listening on 127.0.0.1:port."""
        out = subprocess.run(["lsof", "-nP", "-iTCP:%d" % port, "-sTCP:LISTEN", "-t"], stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, text=True).stdout
        return {int(p) for p in out.split() if p.isdigit()}

    def adb_serials(self):
        if not self.adb:
            return []
        return [s for s, _ in tv.parse_devices(tv.run_adb([self.adb, "devices"], 30)[1])]

    def port_busy(self, port):
        for p in (port, port + 1):
            with socket.socket() as s:
                s.settimeout(0.3)
                if s.connect_ex(("127.0.0.1", p)) == 0:
                    return True
        return False

    def launch(self, argv, log):
        with open(log, "ab") as f:
            return subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=f, stderr=subprocess.STDOUT,
                                    start_new_session=True).pid

    def killpg(self, pgid, sig):
        try:
            os.killpg(pgid, sig)
        except (ProcessLookupError, PermissionError):
            pass

    def booted(self, serial):
        if not self.adb:
            raise EmulatorError("adb not found (Android platform-tools)")
        code, out = tv.run_adb([self.adb, "-s", serial, "shell", "getprop", "sys.boot_completed"], 30)
        return code == 0 and out.strip() == "1"

    def now(self):
        return time.time()

    def sleep(self, s):
        time.sleep(s)


class Slots:
    """The slots file (slots.json) under a lock (lock), in the state directory."""

    def __init__(self, directory, host, log=None, maximum=None):
        self.dir, self.host = directory, host
        self.log = log or (lambda m: print(m, file=sys.stderr))
        self.max = maximum or int(os.environ.get(MAX_ENV) or DEFAULT_MAX)
        os.makedirs(directory, exist_ok=True)

    # ---- the file ----

    def _locked(self):
        import fcntl
        f = open(os.path.join(self.dir, "lock"), "a+")
        fcntl.flock(f, fcntl.LOCK_EX)
        return f

    def _read(self):
        try:
            with open(os.path.join(self.dir, "slots.json")) as f:
                return json.load(f)
        except (OSError, ValueError):
            return []

    def _write(self, slots):
        path = os.path.join(self.dir, "slots.json")
        with open(path + ".tmp", "w") as f:
            json.dump(slots, f, indent=2)
        os.replace(path + ".tmp", path)

    # ---- what's running ----

    def emulators(self):
        """{pid: (port or None, process group)} of every running emulator (its qemu process), whoever started it."""
        found = {}
        for pid, pgid, cmd in self.host.processes():
            if QEMU.search(cmd):
                m = PORT_ARG.search(cmd)
                found[pid] = (int(m.group(1)) if m else None, pgid)
        return found

    def _group(self, slot):
        """The live processes of the slot's own group that were started for its port: never a reused pid's."""
        mark = "-port %d" % slot["port"]
        return [pid for pid, pgid, cmd in self.host.processes() if pgid == slot["pgid"] and mark in cmd]

    def reap(self, slots):
        """Drops slots whose emulator is gone, and stops the ones past their time."""
        kept = []
        for s in slots:
            if not self._group(s):
                continue
            if self.host.now() > s["expires"]:
                self.log("stopping %s (%s): its time is up" % (s["serial"], s["owner"]))
                self._terminate(s)
                continue
            kept.append(s)
        return kept

    def _terminate(self, slot):
        self.host.killpg(slot["pgid"], signal.SIGTERM)
        for _ in range(20):
            if not self._group(slot):
                return
            self.host.sleep(1)
        self.host.killpg(slot["pgid"], signal.SIGKILL)

    def in_use(self, slots, running):
        """How many emulators count against the limit: every running one, and ours whose qemu isn't up yet."""
        qemu_groups = {pgid for _, pgid in running.values()}
        starting = [s for s in slots if s["pgid"] not in qemu_groups]
        return len(running) + len(starting)

    def free_port(self, slots, running):
        taken = {s["port"] for s in slots} | {p for p, _ in running.values() if p}
        taken |= {int(s.split("-")[1]) for s in self.host.adb_serials() if re.match(r"^emulator-\d+$", s)}
        for port in PORTS:
            if port not in taken and not self.host.port_busy(port):
                return port
        raise EmulatorError("no free emulator port from %d to %d" % (PORTS[0], PORTS[-1]))

    # ---- commands ----

    def claim(self, owner, avd, hours, emulator):
        """Starts an emulator in a free slot. None when every slot is taken."""
        with self._locked():
            slots = self.reap(self._read())
            running = self.emulators()
            if self.in_use(slots, running) >= self.max:
                self._write(slots)
                return None
            port = self.free_port(slots, running)
            serial = "emulator-%d" % port
            log = os.path.join(self.dir, serial + ".log")
            pid = self.host.launch([emulator, "-avd", avd, "-read-only", "-port", str(port), "-no-window",
                                    "-no-audio", "-no-boot-anim"], log)
            now = self.host.now()
            slot = {"serial": serial, "port": port, "pid": pid, "pgid": pid, "owner": owner, "avd": avd,
                    "started": now, "expires": now + hours * 3600, "log": log}   # launched in a session of its own
            self._write(slots + [slot])
            return slot

    def start(self, owner, avd=DEFAULT_AVD, hours=6.0, wait=True, timeout=6 * 3600, emulator=None):
        emulator = emulator or find_emulator()
        began, said = self.host.now(), None
        while True:
            slot = self.claim(owner, avd, hours, emulator)
            if slot:
                break
            if not wait:
                raise EmulatorError("all %d slots are in use: %s" % (self.max, self.describe()))
            if self.host.now() - began > timeout:
                raise EmulatorError("no slot came free in %g hours" % (timeout / 3600))
            if said is None or self.host.now() - said >= 300:
                self.log("waiting for a slot (%d in use, at most %d): %s"
                         % (self.in_use(self._read(), self.emulators()), self.max, self.describe()))
                said = self.host.now()
            self.host.sleep(30)
        self.log("starting %s for %s (AVD %s); booting" % (slot["serial"], owner, avd))
        deadline = self.host.now() + BOOT_TIMEOUT
        while self.host.now() < deadline:
            if not self._group(slot):
                self._forget(slot["serial"])
                raise EmulatorError("%s exited while booting; see %s" % (slot["serial"], slot["log"]))
            try:
                if self.host.booted(slot["serial"]):
                    if not self.host.listeners(slot["port"]) & set(self._group(slot)):
                        # Something else took the port first: the emulator then picks another, and the serial
                        # would lead to whoever holds this one.
                        self.stop(slot["serial"])
                        raise EmulatorError("port %d was taken by another process; try again" % slot["port"])
                    return slot
            except tv.TvError:
                pass
            self.host.sleep(3)
        self.stop(slot["serial"])
        raise EmulatorError("%s didn't boot in %d s; see %s" % (slot["serial"], BOOT_TIMEOUT, slot["log"]))

    def _forget(self, serial):
        with self._locked():
            self._write([s for s in self._read() if s["serial"] != serial])

    def renew(self, serial, hours):
        with self._locked():
            slots = self._read()
            mine = [s for s in slots if s["serial"] == serial]
            if not mine:
                raise EmulatorError("%s wasn't started by emulator.py" % serial)
            mine[0]["expires"] = self.host.now() + hours * 3600
            self._write(slots)
        self.log("%s kept for %g more hours" % (serial, hours))

    def stop(self, serial):
        with self._locked():
            slots = self._read()
            mine = [s for s in slots if s["serial"] == serial]
            if not mine:
                raise EmulatorError("%s wasn't started by emulator.py: stop it the way it was started, and never "
                                    "someone else's" % serial)
            self._terminate(mine[0])
            self._write([s for s in slots if s["serial"] != serial])
        self.log("stopped %s" % serial)

    def describe(self):
        running = self.emulators()
        slots = [s for s in self._read() if self._group(s)]
        ours = {s["pgid"] for s in slots}
        parts = ["%s (%s, %s left)" % (s["serial"], s["owner"], _hours(s["expires"] - self.host.now()))
                 for s in slots]
        parts += ["emulator-%s (started some other way)" % (port or "?")
                  for port, pgid in running.values() if pgid not in ours]
        return ", ".join(parts) or "none"


def bind_file(root=None):
    return os.path.join(root or os.path.dirname(HERE), tv.DEVICE_FILE)


def bind(serial, root=None):
    with open(bind_file(root), "w") as f:
        f.write(serial + "\n")


def unbind(serial, root=None):
    path = bind_file(root)
    try:
        with open(path) as f:
            bound = f.read().strip()
    except OSError:
        return
    if bound == serial:
        os.remove(path)


def _hours(seconds):
    return "%.1f h" % max(0.0, seconds / 3600)


def exclusive(directory, owner, argv, log=None):
    """Runs argv while holding the Mac's measuring lock; its exit code."""
    import fcntl
    log = log or (lambda m: print(m, file=sys.stderr))
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, "exclusive.lock"), "a+") as f:
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            f.seek(0)
            log("waiting for the measuring lock: %s" % (f.read().strip() or "someone"))
            fcntl.flock(f, fcntl.LOCK_EX)
        f.seek(0)
        f.truncate()
        f.write("%s since %s\n" % (owner, datetime.datetime.now().strftime("%H:%M")))
        f.flush()
        return subprocess.run(argv).returncode


def build_parser():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("start", help="wait for a slot, start an emulator, print its serial once booted")
    s.add_argument("--owner", required=True, help="who it's for, such as daily-ux or upstream-sync")
    s.add_argument("--avd", default=DEFAULT_AVD)
    s.add_argument("--hours", type=float, default=6.0, help="stopped by the next start after this (default 6)")
    s.add_argument("--no-wait", action="store_true", help="fail at once when every slot is taken")
    st = sub.add_parser("stop", help="stop an emulator this tool started")
    st.add_argument("serial")
    rn = sub.add_parser("renew", help="keep an emulator this tool started past its time")
    rn.add_argument("serial")
    rn.add_argument("--hours", type=float, default=4.0, help="from now (default 4)")
    sub.add_parser("status", help="the slots")
    e = sub.add_parser("exclusive", help="run a command while no other session runs one")
    e.add_argument("--owner", default=os.environ.get("USER", "someone"))
    e.add_argument("argv", nargs=argparse.REMAINDER, help="-- the command")
    return p


def main(argv=None, host=None):
    args = build_parser().parse_args(argv)
    directory = state_dir()
    try:
        if args.cmd == "exclusive":
            cmd = args.argv[1:] if args.argv[:1] == ["--"] else args.argv
            if not cmd:
                raise EmulatorError("exclusive -- <command>")
            return exclusive(directory, args.owner, cmd)
        slots = Slots(directory, host or Host())
        if args.cmd == "start":
            slot = slots.start(args.owner, args.avd, args.hours, wait=not args.no_wait)
            bind(slot["serial"])
            print(slot["serial"])
        elif args.cmd == "stop":
            slots.stop(args.serial)
            unbind(args.serial)
        elif args.cmd == "renew":
            slots.renew(args.serial, args.hours)
        else:
            print("at most %d at once; %s" % (slots.max, slots.describe()))
    except EmulatorError as e:
        sys.exit("emulator.py: %s" % e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
