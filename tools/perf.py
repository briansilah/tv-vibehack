#!/usr/bin/env python3
"""Measure the launcher on a TV: start-up time, frame jank, memory, and Perfetto traces.

Each `run` repeats every scenario and saves medians plus raw samples to tools/perf-results/<label>.json
(git-ignored). `compare` turns two result files into a Markdown table for a pull request.
See docs/performance.md for the method and what each number means.

    perf.py run --label main                    all scenarios: cold, warm, jank, mem
    perf.py run --label fix --scenarios warm,jank --runs 5
    perf.py run --label fix --scenarios dock    moving between two dock apps: top shelf latency and frames
    perf.py compare tools/perf-results/main.json tools/perf-results/fix.json
    perf.py trace --seconds 15 --action keys    Perfetto trace while it presses D-pad keys
    perf.py trace --action dock                 ... while it moves between the first two dock apps
    perf.py summarize trace.perfetto-trace      slowest launcher trace sections (pip install perfetto)

Connection: with TV_HOST set it connects like tools/tv.py (adb_shell, no adb binary). Otherwise, or with
--adb, it uses the adb binary (--serial or ANDROID_SERIAL picks the device), e.g. for Android 11+
wireless debugging. The launcher must already be installed (launcher/build-and-install.sh).
"""
import argparse
import datetime
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS_DIR = os.path.join(HERE, "perf-results")

PKG = "com.gathin.tvhome"
ACTIVITY = PKG + "/.MainActivity"
PERF_TAG = "TvHomePerf"
# Fallback only: TV Settings is translucent, so the launcher stays visible (paused, not stopped) behind it.
# perf.py prefers an installed full-screen app; see resolve_other_app().
SETTINGS_APP = "am start -W -a android.settings.SETTINGS"
LEANBACK_APPS = ("cmd package query-activities --brief -a android.intent.action.MAIN "
                 "-c android.intent.category.LEANBACK_LAUNCHER")
HOME_INTENT = "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"
KEYCODES = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "CENTER": 23}
# 16 moves across the dock and the first grid rows, then back to the dock.
DEFAULT_KEYS = "RIGHT RIGHT RIGHT LEFT LEFT LEFT DOWN RIGHT RIGHT DOWN LEFT LEFT UP UP RIGHT LEFT"
# Puts focus on the first dock app from anywhere: Up climbs the grid to the top bar and stops there, Down
# goes back to the dock, Left stops at the dock's first app.
TO_FIRST_DOCK_APP = " ".join(["UP"] * 14 + ["DOWN"] + ["LEFT"] * 4)
DOCK_TRACE_KEYS = "RIGHT LEFT RIGHT LEFT RIGHT LEFT RIGHT LEFT"
ALL_SCENARIOS = ("cold", "warm", "jank", "dock", "mem")
TRACE_REMOTE = "/data/misc/perfetto-traces/tvhome.perfetto-trace"
TRACE_CONFIG_REMOTE = "/data/local/tmp/tvhome-perfetto.pbtx"


# ---- connection --------------------------------------------------------------------------------

def find_adb():
    candidates = [shutil.which("adb")]
    for home in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Library/Android/sdk"),
                 os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")):
        if home:
            candidates.append(os.path.join(home, "platform-tools", "adb.exe" if os.name == "nt" else "adb"))
    for c in candidates:
        if c and os.path.isfile(c):
            return c
    sys.exit("adb not found: install Android platform-tools, or set TV_HOST to use tools/tv.py instead")


class AdbBinary:
    def __init__(self, serial=None):
        self.base = [find_adb()] + (["-s", serial] if serial else [])

    def shell(self, cmd, timeout=120):
        r = subprocess.run(self.base + ["shell", cmd], capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr

    def pull(self, remote, local):
        subprocess.run(self.base + ["pull", remote, local], check=True, capture_output=True, timeout=300)

    def close(self):
        pass


class TvPy:
    """Same connection as tools/tv.py (adb_shell over TCP, TV_HOST / TV_PORT)."""

    def __init__(self):
        sys.path.insert(0, HERE)
        import tv  # noqa: E402  (exits with a hint when TV_HOST is unset)
        self.dev = tv.connect()

    def shell(self, cmd, timeout=120):
        return self.dev.shell(cmd, read_timeout_s=float(timeout))

    def pull(self, remote, local):
        self.dev.pull(remote, local, transport_timeout_s=300.0, read_timeout_s=300.0)

    def close(self):
        self.dev.close()


def connect(args):
    if args.adb or args.serial or not os.environ.get("TV_HOST"):
        return AdbBinary(args.serial or os.environ.get("ANDROID_SERIAL"))
    return TvPy()


# ---- parsers (pure functions, tested in tools/tests/test_perf.py) ------------------------------

def _int(pattern, text, flags=re.M):
    m = re.search(pattern, text, flags)
    return int(m.group(1)) if m else None


def parse_am_start(out):
    """`am start -W` output → total/wait ms, launch state (COLD/WARM/HOT on Android 10+), activity."""
    res = {
        "total_ms": _int(r"^TotalTime:\s*(\d+)", out),
        "wait_ms": _int(r"^WaitTime:\s*(\d+)", out),
    }
    for key, name in (("LaunchState", "launch_state"), ("Status", "status"), ("Activity", "activity")):
        m = re.search(r"^%s:\s*(\S+)" % key, out, re.M)
        res[name] = m.group(1) if m else None
    m = re.search(r"^(Warning|Error):.*$", out, re.M)
    res["warning"] = m.group(0).strip() if m else None
    return res


def main_process(out):
    """The launcher's own process section of a dumpsys that may list several processes (e.g. its :home
    service on Google TV); the whole output when there is only one."""
    parts = re.split(r"(?m)^\*\* .*? pid \d+ \[([^\]]+)\] \*\*[ \t]*$", out)
    if len(parts) < 3:
        return out
    sections = dict(zip(parts[1::2], parts[2::2]))
    return sections.get(PKG, parts[2])


def parse_gfxinfo(out):
    """Process-wide frame stats from `dumpsys gfxinfo <pkg>`."""
    out = main_process(out)
    res = {"total_frames": _int(r"^Total frames rendered:\s*(\d+)", out)}
    m = re.search(r"^Janky frames:\s*(\d+)\s*\(([\d.]+|nan)%\)", out, re.M)
    res["janky_frames"] = int(m.group(1)) if m else None
    res["janky_pct"] = float(m.group(2)) if m and m.group(2) != "nan" else None
    for p in (50, 90, 95, 99):
        res["p%d_ms" % p] = _int(r"^%dth percentile:\s*(\d+)ms" % p, out)
        res["gpu_p%d_ms" % p] = _int(r"^%dth gpu percentile:\s*(\d+)ms" % p, out)
    for label, name in (("Missed Vsync", "missed_vsync"), ("High input latency", "high_input_latency"),
                        ("Slow UI thread", "slow_ui_thread"), ("Slow bitmap uploads", "slow_bitmap_uploads"),
                        ("Slow issue draw commands", "slow_draw_commands"),
                        ("Frame deadline missed", "deadline_missed")):
        res[name] = _int(r"^Number %s:\s*(\d+)" % re.escape(label), out)
    return res


def parse_meminfo(out):
    """App Summary (PSS, KB) and object counts from `dumpsys meminfo <pkg>`."""
    out = main_process(out)
    i = out.find("App Summary")
    summary = out[i:] if i >= 0 else ""
    res = {}
    for label, name in (("Java Heap", "java_heap_kb"), ("Native Heap", "native_heap_kb"), ("Code", "code_kb"),
                        ("Stack", "stack_kb"), ("Graphics", "graphics_kb"), ("Private Other", "private_other_kb"),
                        ("System", "system_kb")):
        res[name] = _int(r"^\s*%s:\s+(\d+)" % label, summary)
    res["total_pss_kb"] = _int(r"TOTAL PSS:\s+(\d+)", summary)
    if res["total_pss_kb"] is None:   # Android 9 and older: "TOTAL:" in the summary
        res["total_pss_kb"] = _int(r"^\s*TOTAL:\s+(\d+)", summary)
    res["total_rss_kb"] = _int(r"TOTAL RSS:\s+(\d+)", summary)
    res["views"] = _int(r"\bViews:\s+(\d+)", out)
    res["activities"] = _int(r"\bActivities:\s+(\d+)", out)
    return res


def parse_package(out):
    """Build facts from `dumpsys package <pkg>` that change every number: debuggable and compiled state."""
    if not re.search(r"Package \[%s\]" % re.escape(PKG), out):
        return None
    m = re.search(r"^\s*(?:pkgFlags|flags)=\[([^\]]*)\]", out, re.M)
    flags = m.group(1).split() if m else []
    status = re.search(r"\[status=([\w-]+)\]", out)
    reason = re.search(r"\[reason=([\w-]+)\]", out)
    version = re.search(r"versionName=(\S+)", out)
    return {
        "version_name": version.group(1) if version else None,
        "debuggable": "DEBUGGABLE" in flags,
        "compiler_filter": status.group(1) if status else None,
        "compile_reason": reason.group(1) if reason else None,
    }


_LOG_LINE = re.compile(r"^\s*(\d+\.\d+)\s.*?\b%s\s*:\s+([\w.]+) ms=([\d.]+)(.*)$" % PERF_TAG)
_FULLY_DRAWN = re.compile(r"^\s*(\d+\.\d+)\s.*Fully drawn %s[^:]*:\s*\+(?:(\d+)s)?(\d+)ms" % re.escape(ACTIVITY))
_DISPLAYED = re.compile(r"^\s*(\d+\.\d+)\s.*Displayed %s[^:]*:\s*\+(?:(\d+)s)?(\d+)ms" % re.escape(ACTIVITY))


def parse_logcat(out, since=0.0):
    """Launcher timing events (`logcat -v epoch`) logged at or after `since` (device epoch seconds).

    Returns {"<event>_ms": first value, ...} plus integer extras (e.g. apps) and the system's
    "Displayed" / "Fully drawn" times for MainActivity."""
    res = {}
    for line in out.splitlines():
        m = _LOG_LINE.match(line)
        if m and float(m.group(1)) >= since:
            key = m.group(2) + "_ms"
            if key not in res:
                res[key] = float(m.group(3))
                for k, v in re.findall(r"(\w+)=(\S+)", m.group(4)):
                    try:
                        res.setdefault(m.group(2) + "_" + k if k.endswith("_ms") else k, float(v))
                    except ValueError:
                        res.setdefault(k, v)
            continue
        for rx, key in ((_FULLY_DRAWN, "fully_drawn_ms"), (_DISPLAYED, "displayed_ms")):
            m = rx.match(line)
            if m and float(m.group(1)) >= since and key not in res:
                res[key] = int(m.group(2) or 0) * 1000 + int(m.group(3))
    return res


def parse_events(out, event, since=0.0):
    """Every `event` timing line logged at or after `since`, oldest first, as dicts like parse_logcat's
    ({"<event>_ms": ..., "<event>_<part>_ms": ..., other extras})."""
    res = []
    for line in out.splitlines():
        m = _LOG_LINE.match(line)
        if not m or m.group(2) != event or float(m.group(1)) < since:
            continue
        e = {event + "_ms": float(m.group(3))}
        for k, v in re.findall(r"(\w+)=(\S+)", m.group(4)):
            try:
                e[event + "_" + k if k.endswith("_ms") else k] = float(v)
            except ValueError:
                e[k] = v
        res.append(e)
    return res


def parse_components(out):
    """Component names ("pkg/.Activity") from `cmd package query-activities --brief`."""
    return [m.group(1) for m in re.finditer(r"^\s*([A-Za-z][\w.]*/[\w.$]+)\s*$", out, re.M)]


def summarize(samples, keys=None):
    """{key: {median, p90, min, max, n}} over the numeric values of a list of dicts."""
    if keys is None:
        keys = sorted({k for s in samples for k, v in s.items() if isinstance(v, (int, float))
                       and not isinstance(v, bool)})
    out = {}
    for k in keys:
        vals = sorted(s[k] for s in samples if isinstance(s.get(k), (int, float)) and not isinstance(s.get(k), bool))
        if not vals:
            continue
        out[k] = {"median": statistics.median(vals), "p90": vals[min(len(vals) - 1, int(round(0.9 * (len(vals) - 1))))],
                  "min": vals[0], "max": vals[-1], "n": len(vals)}
    return out


# ---- scenarios ---------------------------------------------------------------------------------

def device_epoch(sh):
    try:
        return float(sh.shell("date +%s").strip())
    except ValueError:
        return 0.0


def launcher_logs(sh, since):
    out = sh.shell("logcat -d -v epoch -s %s:D ActivityTaskManager:I ActivityManager:I" % PERF_TAG)
    return parse_logcat(out, since)


def key_loop(keys, interval):
    codes = " ".join(str(KEYCODES[k.upper()]) for k in keys.split())
    return "for k in %s; do input keyevent $k; sleep %s; done" % (codes, interval)


def resolve_other_app(sh, wanted, log):
    """The app that sends the launcher to the background: `wanted` (package or component) or, by default,
    the first installed TV app in name order. It must cover the screen; TV Settings is translucent."""
    comps = sorted(c for c in parse_components(sh.shell(LEANBACK_APPS)) if not c.startswith(PKG + "/"))
    if wanted:
        match = [c for c in comps if c == wanted or c.split("/")[0] == wanted]
        if not match and "/" in wanted:
            match = [wanted]
        if not match:
            sys.exit("--other-app %s: no such TV app installed" % wanted)
        return match[0]
    if comps:
        return comps[0]
    log("warning: no other TV app found; using Settings, which is translucent on most TVs, so warm and "
        "Home numbers will not reflect returning from a full-screen app")
    return None


def open_other_app(sh, component):
    sh.shell("am start -W -n " + component if component else SETTINGS_APP)


def kill_launcher(sh, log):
    """Ends the launcher's main process while another app is in front, for a cold start.

    Not `am force-stop`: force-stopping a package turns its accessibility services off for good (Android
    removes them from Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), which would switch off the Google TV
    HomeButtonService. `am kill` only ends the package's background processes, so the service's own
    ":home" process, which the system keeps bound, survives. Returns True when the main process is gone."""
    sh.shell("am kill " + PKG)
    gone = not sh.shell("pidof " + PKG).strip()
    if not gone:
        log("  warning: the launcher's process survived `am kill` (still in front?); this start is not cold")
    return gone


def scenario_cold(sh, runs, settle, other, log):
    samples = []
    for i in range(runs):
        open_other_app(sh, other)
        time.sleep(settle)
        kill_launcher(sh, log)
        time.sleep(settle)
        t = device_epoch(sh)
        s = parse_am_start(sh.shell("am start -W -n " + ACTIVITY))
        time.sleep(settle * 2)   # entrance animation, focus restore, logs
        s.update(launcher_logs(sh, t))
        if s.get("launch_state") not in (None, "COLD"):
            log("  cold run %d was %s, not COLD (is the launcher the default home and restarted itself?)"
                % (i + 1, s["launch_state"]))
        log("  cold %d/%d: TotalTime %s ms, fully drawn %s ms" % (i + 1, runs, s.get("total_ms"), s.get("fully_drawn_ms")))
        samples.append(s)
    return {"samples": samples, "summary": summarize(samples)}


def scenario_warm(sh, runs, settle, via_home, other, log):
    sh.shell("am start -W -n " + ACTIVITY)
    time.sleep(settle * 2)
    samples = []
    for i in range(runs):
        open_other_app(sh, other)
        time.sleep(settle)
        t = device_epoch(sh)
        s = parse_am_start(sh.shell(HOME_INTENT if via_home else "am start -W -n " + ACTIVITY))
        time.sleep(settle)
        s.update(launcher_logs(sh, t))
        if s.get("activity") and not s["activity"].startswith(PKG):
            log("  warning: Home opened %s, not the launcher; is it the default home app?" % s["activity"])
        if s.get("total_ms") is None or s.get("launch_state") == "UNKNOWN":
            log("  warning: the system did not time this start (the launcher was probably still visible); "
                "pick a full-screen app with --other-app")
        log("  warm %d/%d: TotalTime %s ms, focus restored after %s ms"
            % (i + 1, runs, s.get("total_ms"), s.get("focus_restored_ms")))
        samples.append(s)
    return {"samples": samples, "summary": summarize(samples), "via_home": via_home}


def scenario_jank(sh, rounds, keys, interval, log):
    sh.shell("am start -W -n " + ACTIVITY)
    time.sleep(2.5)
    n = len(keys.split())
    samples = []
    for i in range(rounds):
        sh.shell("dumpsys gfxinfo %s reset" % PKG)
        sh.shell(key_loop(keys, interval), timeout=60 + n * 5)
        time.sleep(1.0)
        g = parse_gfxinfo(sh.shell("dumpsys gfxinfo " + PKG))
        log("  jank %d/%d: %s frames, %s%% janky, p90 %s ms, p99 %s ms"
            % (i + 1, rounds, g.get("total_frames"), g.get("janky_pct"), g.get("p90_ms"), g.get("p99_ms")))
        samples.append(g)
    return {"samples": samples, "summary": summarize(samples), "keys": keys, "interval_s": interval}


def scenario_dock(sh, rounds, moves, interval, log):
    """The top shelf: moves focus back and forth between the first two dock apps, one press every
    `interval` seconds so each change can finish, and records how long the shelf takes to show the new
    app (the launcher's `shelf` timing lines) and the frames drawn meanwhile."""
    sh.shell("am start -W -n " + ACTIVITY)
    time.sleep(2.5)
    sh.shell(key_loop(TO_FIRST_DOCK_APP, 0.15), timeout=60)
    time.sleep(1.5)
    sh.shell(key_loop("RIGHT LEFT", interval), timeout=60)   # warm-up, not counted
    time.sleep(2.0)
    keys = " ".join(["RIGHT", "LEFT"] * (moves // 2) + ["RIGHT"] * (moves % 2))
    events, frames = [], []
    for i in range(rounds):
        sh.shell("dumpsys gfxinfo %s reset" % PKG)
        t = device_epoch(sh)
        sh.shell(key_loop(keys, interval), timeout=60 + moves * (interval + 5))
        time.sleep(1.5)
        g = parse_gfxinfo(sh.shell("dumpsys gfxinfo " + PKG))
        e = parse_events(sh.shell("logcat -d -v epoch -s %s:D" % PERF_TAG), "shelf", t)
        e = [x for x in e if x.get("kind") != "show"]
        shelf = statistics.median([x["shelf_ms"] for x in e]) if e else None
        log("  dock %d/%d: %d shelf changes, median %s ms; %s frames, %s%% janky, p90 %s ms"
            % (i + 1, rounds, len(e), shelf, g.get("total_frames"), g.get("janky_pct"), g.get("p90_ms")))
        if len(e) < moves:
            log("  warning: %d of %d moves changed the shelf; was focus on the dock?" % (len(e), moves))
        events += e
        frames.append(g)
        time.sleep(2.0)
    summary = summarize(events)
    summary.update(summarize(frames))
    return {"samples": events, "frames": frames, "summary": summary, "keys": keys, "interval_s": interval}


def scenario_mem(sh, samples_n, log):
    sh.shell("am start -W -n " + ACTIVITY)
    time.sleep(3.0)
    samples = []
    for i in range(samples_n):
        m = parse_meminfo(sh.shell("dumpsys meminfo " + PKG))
        log("  mem %d/%d: PSS %s KB, graphics %s KB, views %s"
            % (i + 1, samples_n, m.get("total_pss_kb"), m.get("graphics_kb"), m.get("views")))
        samples.append(m)
        time.sleep(1.0)
    return {"samples": samples, "summary": summarize(samples)}


def device_facts(sh):
    props = {}
    for p, k in (("ro.product.manufacturer", "manufacturer"), ("ro.product.model", "model"),
                 ("ro.product.device", "device"), ("ro.build.version.release", "android"),
                 ("ro.build.version.sdk", "sdk"), ("ro.config.low_ram", "low_ram")):
        props[k] = sh.shell("getprop " + p).strip()
    m = re.search(r"MemTotal:\s+(\d+)", sh.shell("cat /proc/meminfo"))
    props["ram_mb"] = int(m.group(1)) // 1024 if m else None
    return props


def apk_size(sh):
    m = re.search(r"package:(\S+)", sh.shell("pm path " + PKG))
    if not m:
        return None
    try:
        return int(sh.shell("stat -c %s " + m.group(1)).strip())
    except ValueError:
        return None


def cmd_run(args):
    scenarios = [s.strip() for s in args.scenarios.split(",") if s.strip()]
    bad = [s for s in scenarios if s not in ALL_SCENARIOS]
    if bad:
        sys.exit("unknown scenario(s): %s" % ", ".join(bad))
    sh = args.shell or connect(args)
    log = args.log or (lambda msg: print(msg, flush=True))
    try:
        pkg = parse_package(sh.shell("dumpsys package " + PKG))
        if not pkg:
            sys.exit("%s is not installed; run launcher/build-and-install.sh first" % PKG)
        result = {
            "label": args.label,
            "created": datetime.datetime.now().isoformat(timespec="seconds"),
            "device": device_facts(sh),
            "build": dict(pkg, apk_bytes=apk_size(sh)),
            "params": {"runs": args.runs, "rounds": args.rounds, "settle_s": args.settle},
        }
        other = None
        if "cold" in scenarios or "warm" in scenarios:
            other = resolve_other_app(sh, args.other_app, log)
            result["params"]["other_app"] = other
            log("background app: %s" % (other or "Settings"))
        if pkg["debuggable"]:
            log("note: the installed launcher is a debuggable build; it runs slower than a release build. "
                "Compare like with like.")
        log("device: %(manufacturer)s %(model)s, Android %(android)s, %(ram_mb)s MB RAM" % result["device"])
        sh.shell("setprop log.tag.%s DEBUG" % PERF_TAG)
        try:
            if "cold" in scenarios:
                log("cold start x%d" % args.runs)
                result["cold"] = scenario_cold(sh, args.runs, args.settle, other, log)
            if "warm" in scenarios:
                log("warm start / Home x%d" % args.runs)
                result["warm"] = scenario_warm(sh, args.runs, args.settle, args.via_home, other, log)
            if "jank" in scenarios:
                log("D-pad jank x%d" % args.rounds)
                result["jank"] = scenario_jank(sh, args.rounds, args.keys, args.key_interval, log)
            if "dock" in scenarios:
                log("dock moves x%d" % args.rounds)
                result["dock"] = scenario_dock(sh, args.rounds, args.dock_moves, args.dock_interval, log)
            if "mem" in scenarios:
                log("memory")
                result["mem"] = scenario_mem(sh, 3, log)
        finally:
            sh.shell("setprop log.tag.%s ''" % PERF_TAG)
            sh.shell(HOME_INTENT)
    finally:
        if not args.shell:
            sh.close()
    out = args.out or os.path.join(RESULTS_DIR, re.sub(r"[^\w.-]+", "_", args.label) + ".json")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w") as f:
        json.dump(result, f, indent=2)
    log("saved %s" % out)
    print(render_table([result]))
    return result


# ---- reporting ---------------------------------------------------------------------------------

# (path into a result, label, unit). Lower is better for all of them.
METRICS = [
    ("cold.summary.total_ms", "Cold start (am start TotalTime)", "ms"),
    ("cold.summary.fully_drawn_ms", "Cold start to fully drawn (focus on a tile)", "ms"),
    ("cold.summary.reload_ms", "  of which: app list reload", "ms"),
    ("warm.summary.total_ms", "Home / warm start (TotalTime)", "ms"),
    ("warm.summary.focus_restored_ms", "Home: onResume to focus restored", "ms"),
    ("warm.summary.resume_ms", "  onResume main-thread time", "ms"),
    ("warm.summary.reload_ms", "  of which: app list reload", "ms"),
    ("warm.summary.reload_load_ms", "    loading banners/icons/labels", "ms"),
    ("jank.summary.janky_pct", "Janky frames (D-pad moves)", "%"),
    ("jank.summary.p50_ms", "50th percentile frame", "ms"),
    ("jank.summary.p90_ms", "90th percentile frame", "ms"),
    ("jank.summary.p99_ms", "99th percentile frame", "ms"),
    ("jank.summary.slow_ui_thread", "Slow UI thread frames", ""),
    ("jank.summary.high_input_latency", "High input latency frames", ""),
    ("dock.summary.shelf_ms", "Dock move: top shelf shows the new app", "ms"),
    ("dock.summary.shelf_text_ms", "  name shown", "ms"),
    ("dock.summary.shelf_glow_ms", "  glow colour reached", "ms"),
    ("dock.summary.janky_pct", "Janky frames (dock moves)", "%"),
    ("dock.summary.p50_ms", "  50th percentile frame", "ms"),
    ("dock.summary.p90_ms", "  90th percentile frame", "ms"),
    ("dock.summary.p99_ms", "  99th percentile frame", "ms"),
    ("dock.summary.gpu_p90_ms", "  90th percentile GPU", "ms"),
    ("mem.summary.total_pss_kb", "Memory (PSS)", "MB"),
    ("mem.summary.java_heap_kb", "  Java heap", "MB"),
    ("mem.summary.native_heap_kb", "  Native heap (bitmaps)", "MB"),
    ("mem.summary.graphics_kb", "  Graphics", "MB"),
    ("mem.summary.views", "Views", ""),
    ("build.apk_bytes", "APK size", "KB"),
]


def _get(result, path):
    node = result
    for part in path.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    if isinstance(node, dict):
        node = node.get("median")
    return node


def _fmt(v, unit):
    if v is None:
        return "–"
    if unit == "MB":
        return "%.1f MB" % (v / 1024.0)
    if unit == "KB":
        return "%d KB" % round(v / 1024.0)
    if unit == "%":
        return "%.1f %%" % v
    return ("%d" % v if float(v).is_integer() else "%.1f" % v) + (" " + unit if unit else "")


def render_table(results):
    """Markdown table of medians; with two results adds the change from the first to the second."""
    head = ["Metric (median)"] + [r.get("label", "?") for r in results]
    if len(results) == 2:
        head.append("change")
    lines = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    for path, label, unit in METRICS:
        vals = [_get(r, path) for r in results]
        if all(v is None for v in vals):
            continue
        row = [label] + [_fmt(v, unit) for v in vals]
        if len(results) == 2:
            a, b = vals
            row.append("%+.0f %%" % ((b - a) * 100.0 / a) if a and b is not None else "–")
        lines.append("| " + " | ".join(row) + " |")
    notes = []
    for r in results:
        b, d = r.get("build", {}), r.get("device", {})
        notes.append("%s: %s %s (Android %s), %s build, compiled %s" % (
            r.get("label"), d.get("manufacturer"), d.get("model"), d.get("android"),
            "debug" if b.get("debuggable") else "release", b.get("compiler_filter")))
    if len(results) == 2:
        a, b = results
        if a.get("device", {}).get("model") != b.get("device", {}).get("model"):
            notes.append("**Different devices: the numbers are not comparable.**")
        if a.get("params", {}).get("other_app") != b.get("params", {}).get("other_app"):
            notes.append("Different background apps for cold/warm starts.")
        if a.get("build", {}).get("debuggable") != b.get("build", {}).get("debuggable"):
            notes.append("Debug vs release build: part of the change comes from the build type alone.")
    return "\n".join(lines) + "\n\n" + "\n".join("- " + n for n in notes)


def cmd_compare(args):
    results = []
    for path in args.files:
        with open(path) as f:
            results.append(json.load(f))
    print(render_table(results))


# ---- tracing -----------------------------------------------------------------------------------

def perfetto_config(duration_ms):
    """Text-proto Perfetto config: scheduling, frames, binder, and the launcher's tvhome:* sections."""
    ftrace_events = ["sched/sched_switch", "sched/sched_wakeup", "sched/sched_waking", "sched/sched_process_exit",
                     "sched/sched_process_free", "task/task_newtask", "task/task_rename", "power/cpu_frequency",
                     "power/cpu_idle"]
    categories = ["am", "wm", "gfx", "view", "input", "res", "dalvik", "binder_driver"]
    ftrace = " ".join('ftrace_events: "%s"' % e for e in ftrace_events)
    ftrace += " " + " ".join('atrace_categories: "%s"' % c for c in categories)
    ftrace += ' atrace_apps: "%s"' % PKG
    return ("buffers { size_kb: 65536 fill_policy: DISCARD }\n"
            "buffers { size_kb: 4096 fill_policy: DISCARD }\n"
            "data_sources { config { name: \"linux.ftrace\" target_buffer: 0 ftrace_config { %s } } }\n"
            "data_sources { config { name: \"linux.process_stats\" target_buffer: 1 "
            "process_stats_config { scan_all_processes_on_start: true } } }\n"
            "data_sources { config { name: \"android.surfaceflinger.frametimeline\" } }\n"
            "duration_ms: %d\n" % (ftrace, duration_ms))


def cmd_trace(args):
    sh = args.shell or connect(args)
    log = args.log or (lambda msg: print(msg, flush=True))
    try:
        other = resolve_other_app(sh, args.other_app, log) if args.action in ("cold", "home") else None
        if args.action != "cold":
            sh.shell("am start -W -n " + ACTIVITY)
            time.sleep(2.0)
        if args.action == "dock":
            sh.shell(key_loop(TO_FIRST_DOCK_APP, 0.15), timeout=60)
            time.sleep(1.5)
        sh.shell("rm -f " + TRACE_REMOTE)
        sh.shell("printf '%%s' '%s' > %s" % (perfetto_config(int(args.seconds * 1000)), TRACE_CONFIG_REMOTE))
        out = sh.shell("cat %s | perfetto --txt -c - -o %s --background" % (TRACE_CONFIG_REMOTE, TRACE_REMOTE))
        pid = re.search(r"^(\d+)\s*$", out, re.M)
        log("tracing for %ss%s" % (args.seconds, " (perfetto pid %s)" % pid.group(1) if pid else ""))
        start = time.time()
        time.sleep(1.5)
        if args.action == "cold":
            open_other_app(sh, other)
            time.sleep(1.0)
            kill_launcher(sh, log)
            sh.shell("am start -W -n " + ACTIVITY)
        elif args.action == "home":
            open_other_app(sh, other)
            time.sleep(2.0)
            sh.shell(HOME_INTENT)
        elif args.action == "keys":
            sh.shell(key_loop(args.keys, args.key_interval or 0.35), timeout=120)
        elif args.action == "dock":
            sh.shell(key_loop(DOCK_TRACE_KEYS, args.key_interval or 1.2), timeout=120)
        remaining = args.seconds - (time.time() - start) + 2.0
        if remaining > 0:
            time.sleep(remaining)
        for _ in range(20):   # perfetto flushes after duration_ms
            running = sh.shell("pidof perfetto").split()
            if (pid.group(1) not in running) if pid else not running:
                break
            time.sleep(1.0)
        local = args.out or "tvhome-%s.perfetto-trace" % datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        sh.pull(TRACE_REMOTE, local)
        log("saved %s: open it at https://ui.perfetto.dev, or run `perf.py summarize %s`" % (local, local))
    finally:
        if not args.shell:
            sh.close()


SUMMARY_SQL = """
select case when t.tid = p.pid then 'main' else t.name end as thread, s.name, count(*) as count,
       round(sum(s.dur) / 1e6, 1) as total_ms, round(avg(s.dur) / 1e6, 2) as avg_ms,
       round(max(s.dur) / 1e6, 1) as max_ms
from slice s join thread_track tt on s.track_id = tt.id join thread t using (utid) join process p using (upid)
where p.name = '%s' and (s.name like 'tvhome:%%' or t.tid = p.pid or t.name = 'RenderThread')
group by thread, s.name order by total_ms desc limit %d
"""


# tvhome:* sections that span frames (Perf.Span, e.g. tvhome:shelf) live on process tracks.
ASYNC_SQL = """
select s.name, count(*) as count, round(sum(s.dur) / 1e6, 1) as total_ms, round(avg(s.dur) / 1e6, 2) as avg_ms,
       round(max(s.dur) / 1e6, 1) as max_ms
from slice s join process_track pt on s.track_id = pt.id join process p using (upid)
where p.name = '%s' and s.name like 'tvhome:%%' group by s.name order by total_ms desc
"""

# Frames as SurfaceFlinger saw them (android.surfaceflinger.frametimeline, Android 12+).
FRAMES_SQL = """
select a.jank_type, count(*) as count, round(avg(a.dur) / 1e6, 2) as avg_ms, round(max(a.dur) / 1e6, 1) as max_ms
from actual_frame_timeline_slice a join process p using (upid)
where p.name = '%s' group by a.jank_type order by count desc
"""


def cmd_summarize(args):
    sql = SUMMARY_SQL % (PKG, args.limit)
    try:
        from perfetto.trace_processor import TraceProcessor
    except ImportError:
        print("pip install perfetto for an automatic summary, or paste this into the Query (SQL) tab "
              "of https://ui.perfetto.dev:\n" + sql)
        return
    tp = TraceProcessor(trace=args.trace)
    try:
        rows = list(tp.query(sql))
        spans = list(tp.query(ASYNC_SQL % PKG))
        try:
            frames = list(tp.query(FRAMES_SQL % PKG))
        except Exception:   # no frame timeline in traces from Android 11 and older
            frames = []
    finally:
        tp.close()
    print("Slices on the launcher's main thread and RenderThread, plus its tvhome:* sections. Nested slices "
          "count in their parents too.\n\n| thread | slice | count | total ms | avg ms | max ms |\n"
          "|---|---|---|---|---|---|")
    for r in rows:
        print("| %s | %s | %d | %s | %s | %s |" % (r.thread, r.name, r.count, r.total_ms, r.avg_ms, r.max_ms))
    if spans:
        print("\nSections across frames:\n\n| section | count | total ms | avg ms | max ms |\n|---|---|---|---|---|")
        for r in spans:
            print("| %s | %d | %s | %s | %s |" % (r.name, r.count, r.total_ms, r.avg_ms, r.max_ms))
    if frames:
        total = sum(r.count for r in frames)
        print("\nFrames (SurfaceFlinger frame timeline), %d in all:\n\n| jank type | frames | avg ms | max ms |\n"
              "|---|---|---|---|" % total)
        for r in frames:
            print("| %s | %d | %s | %s |" % (r.jank_type, r.count, r.avg_ms, r.max_ms))


# ---- command line ------------------------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def conn(sp):
        sp.add_argument("--adb", action="store_true", help="use the adb binary even when TV_HOST is set")
        sp.add_argument("--serial", help="adb device serial (default: ANDROID_SERIAL)")
        sp.add_argument("--other-app", help="TV app (package or component) that sends the launcher to the "
                                            "background for cold/warm/Home; must be full-screen. Default: the "
                                            "first installed TV app by name")

    r = sub.add_parser("run", help="measure and save a result file")
    conn(r)
    r.add_argument("--label", required=True, help="name for this run, e.g. main or the branch")
    r.add_argument("--scenarios", default="cold,warm,jank,dock,mem")
    r.add_argument("--runs", type=int, default=10, help="cold and warm starts (default 10)")
    r.add_argument("--rounds", type=int, default=3, help="D-pad rounds for jank and dock (default 3)")
    r.add_argument("--keys", default=DEFAULT_KEYS, help="D-pad sequence for jank (UP DOWN LEFT RIGHT)")
    r.add_argument("--key-interval", type=float, default=0.35, help="seconds between key presses")
    r.add_argument("--dock-moves", type=int, default=8, help="moves between the first two dock apps per round")
    r.add_argument("--dock-interval", type=float, default=1.2, help="seconds between dock moves")
    r.add_argument("--settle", type=float, default=1.5, help="seconds to wait between steps")
    r.add_argument("--via-home", action="store_true",
                   help="warm start with the HOME intent instead of starting MainActivity directly "
                        "(needs the launcher as the default home app)")
    r.add_argument("--out", help="result file (default tools/perf-results/<label>.json)")
    r.set_defaults(func=cmd_run)

    c = sub.add_parser("compare", help="Markdown table of result files (change is relative to the first)")
    c.add_argument("files", nargs="+")
    c.set_defaults(func=cmd_compare)

    t = sub.add_parser("trace", help="record a Perfetto trace while doing something")
    conn(t)
    t.add_argument("--seconds", type=float, default=12.0)
    t.add_argument("--action", choices=("keys", "dock", "cold", "home", "none"), default="keys",
                   help="keys: --keys; dock: Right/Left between the first two dock apps")
    t.add_argument("--keys", default=DEFAULT_KEYS)
    t.add_argument("--key-interval", type=float, help="seconds between keys (default 0.35; 1.2 for dock)")
    t.add_argument("--out", help="local file (default tvhome-<time>.perfetto-trace)")
    t.set_defaults(func=cmd_trace)

    s = sub.add_parser("summarize", help="slowest launcher slices in a trace")
    s.add_argument("trace")
    s.add_argument("--limit", type=int, default=25)
    s.set_defaults(func=cmd_summarize)
    return p


def main(argv=None, shell=None, log=None):
    args = build_parser().parse_args(argv)
    args.shell, args.log = shell, log
    return args.func(args)


if __name__ == "__main__":
    main()
