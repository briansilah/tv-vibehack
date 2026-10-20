#!/usr/bin/env python3
"""Measure `main` once, compare every branch against it: a cache of main's perf.py results shared by all worktrees.

A baseline is reused only while nothing that changes its numbers has changed. Each scenario's measurement is keyed on
  - main's launcher/ tree (`git rev-parse origin/main:launcher`), so merges of docs or tools don't invalidate it;
  - the device: model, build fingerprint and the unit (its serial number, hashed, or an emulator's AVD name), plus its
    animation scales and its set of TV apps;
  - the scenario's own measuring code in perf.py (a hash of its functions and constants: the "harness");
  - that scenario's parameters (runs, rounds, keys, intervals, settle, background app, --via-home; for mem, the
    scenarios that ran before it);
  - the build: main's release APK. Its compiler filter is recorded, and `compare --against` checks yours matches.
Main's release APK is cached by its launcher/ tree too, built once from a detached worktree.

    perf.py baseline show                          what's cached for origin/main's launcher/ (no device needed)
    perf.py baseline ensure --scenarios dock,mem   measure main on this device for what isn't cached yet
    perf.py baseline refresh --scenarios warm      measure main again even if cached (a TV drifts over days)
    perf.py baseline apk                           build main's release APK (once per launcher/ tree), print its path
    perf.py baseline install                       put main's release build back on the device
    perf.py baseline list                          everything in the cache
    perf.py compare --against main tools/perf-results/mine.json

The cache lives in $(git rev-parse --git-common-dir)/tvhome-perf (TVHOME_PERF_CACHE overrides it) and is never
committed. See docs/performance.md, "Reusing main's baseline".
"""
import argparse
import ast
import contextlib
import datetime
import hashlib
import inspect
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import time
import tokenize

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

CACHE_ENV = "TVHOME_PERF_CACHE"
DEFAULT_REF = "origin/main"
# Bump to invalidate every cached measurement when `perf.py run` changes how it runs scenarios outside their own
# functions: their order, what happens between them, mem's sample count.
HARNESS_EPOCH = 1
# Main's baseline is always its release build. The compiler filter is recorded, not keyed: install scripts compile
# with `speed`, but ART compiles the device's default Home app with speed-profile (`verify` until it has a profile),
# which the launcher is on TVs other than Google TV and on emulators. `compare --against` checks that both match.
RELEASE_BUILD = {"debuggable": False}
REMOTE_APK = "/data/local/tmp/tvhome-baseline.apk"
GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
HOME_SERVICE = "com.gathin.tvhome/.HomeButtonService"
INSTALL_WAIT_S = 60.0
MAX_AGE_DAYS = 7.0
# A baseline older than this gets a note in `compare`: a TV drifts (app updates, memory pressure).
OLD_HOURS = 24.0
ANIMATION_SCALES = (("window", "window_animation_scale"), ("transition", "transition_animation_scale"),
                    ("animator", "animator_duration_scale"))


class BaselineError(Exception):
    """A problem worded for the user."""


def _perf():
    import perf
    return perf


# ---- git -------------------------------------------------------------------------------------

def git(*args, root=None):
    """`git <args>` in the repository; stdout stripped, or None when git fails."""
    try:
        p = subprocess.run(["git", "-C", root or ROOT] + list(args), capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return p.stdout.strip() if p.returncode == 0 else None


try:
    from provenance import launcher_tree   # the shared helper, from "Measure the main that ships"
except ImportError:
    def launcher_tree(ref="HEAD", root=None):
        """Full hash of launcher/'s tree at `ref`, or None without git or for an unknown ref."""
        out = git("rev-parse", "--verify", "--quiet", "%s:launcher" % ref, root=root)
        return out if out and re.fullmatch(r"[0-9a-f]{40}", out) else None


def resolve_ref(name, root=None):
    """(ref, commit, launcher/ tree) for `name`. "main" means origin/main when there is one: the main that ships."""
    ref = name or DEFAULT_REF
    root = root or ROOT
    if ref == "main" and git("rev-parse", "--verify", "--quiet", "origin/main^{commit}", root=root):
        ref = "origin/main"
    commit = git("rev-parse", "--verify", "--quiet", ref + "^{commit}", root=root)
    if not commit:
        raise BaselineError("unknown git ref %r" % ref)
    tree = launcher_tree(commit, root=root)
    if not tree:
        raise BaselineError("%s has no launcher/ folder" % ref)
    return ref, commit, tree


def cache_dir(root=None):
    if os.environ.get(CACHE_ENV):
        return os.environ[CACHE_ENV]
    common = git("rev-parse", "--path-format=absolute", "--git-common-dir", root=root)
    return os.path.join(common, "tvhome-perf") if common else os.path.expanduser("~/.cache/tvhome-perf")


# ---- what a scenario's numbers depend on -------------------------------------------------------

def _source_lines(src):
    """A function's source without comments, docstrings or blank lines, the same on every Python version."""
    src = textwrap.dedent(src)
    skip = set()
    for node in ast.walk(ast.parse(src)):
        body = getattr(node, "body", None)
        if isinstance(body, list) and body and isinstance(body[0], ast.Expr) \
                and isinstance(body[0].value, ast.Constant) and isinstance(body[0].value.value, str):
            skip.update(range(body[0].lineno, body[0].end_lineno + 1))
    lines = src.splitlines()
    for tok in tokenize.generate_tokens(io.StringIO(src).readline):
        if tok.type == tokenize.COMMENT:
            row, col = tok.start
            lines[row - 1] = lines[row - 1][:col]
    return [line.rstrip() for i, line in enumerate(lines, 1) if i not in skip and line.strip()]


def harness(scenario, module=None):
    """Hash of the code that measures `scenario`: perf.scenario_<name> and every perf function and constant it
    reaches. Comments and docstrings don't count, so editing them keeps the cache. None for an unknown scenario."""
    perf = module or _perf()
    g = vars(perf)
    entry = "scenario_" + scenario
    if not inspect.isfunction(g.get(entry)):
        return None
    parts, seen, todo = {}, set(), [entry]
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        obj = g[name]
        if inspect.isfunction(obj) and obj.__globals__ is g:
            src = inspect.getsource(obj)
            parts[name] = _source_lines(src)
            todo += [n.id for n in ast.walk(ast.parse(textwrap.dedent(src)))
                     if isinstance(n, ast.Name) and n.id in g and n.id not in seen]
        elif isinstance(obj, re.Pattern):
            parts[name] = [obj.pattern, obj.flags]
        elif isinstance(obj, (str, int, float, bool, tuple, list, dict)) and not name.startswith("__"):
            parts[name] = repr(obj)
    blob = json.dumps([HARNESS_EPOCH, sorted(parts.items())], sort_keys=True)
    return hashlib.sha256(blob.encode()).hexdigest()[:12]


def _getprop(sh, name):
    return sh.shell("getprop " + name).strip()


def device_identity(sh):
    """What makes measurements on this device comparable, and an id for it. The serial number is only kept
    hashed. Emulators started from the same AVD share an id: their numbers are relative anyway."""
    perf = _perf()
    facts = {k: _getprop(sh, p) for k, p in (("manufacturer", "ro.product.manufacturer"),
                                             ("model", "ro.product.model"), ("device", "ro.product.device"),
                                             ("fingerprint", "ro.build.fingerprint"))}
    avd = _getprop(sh, "ro.boot.qemu.avd_name") or _getprop(sh, "ro.kernel.qemu.avd_name")
    unit = ("avd:" + avd) if avd else _getprop(sh, "ro.serialno")
    scales = {}
    for k, setting in ANIMATION_SCALES:
        v = sh.shell("settings get global " + setting).strip()
        scales[k] = v if v and v != "null" else "1.0"   # unset means the default, 1
    apps = sorted(c for c in perf.parse_components(sh.shell(perf.LEANBACK_APPS)) if not c.startswith(perf.PKG + "/"))
    unit_hash = hashlib.sha256(unit.encode()).hexdigest()[:12]
    ident = "|".join([facts["manufacturer"], facts["model"], facts["device"], facts["fingerprint"], unit_hash])
    return {
        "id": hashlib.sha256(ident.encode()).hexdigest()[:16],
        "fingerprint": facts["fingerprint"],
        "unit": unit_hash,
        "emulator_avd": avd or None,
        "state": {"animation_scales": scales,
                  "tv_apps": {"count": len(apps),
                              "hash": hashlib.sha256("\n".join(apps).encode()).hexdigest()[:12]}},
    }


def installed_apk_sha256(sh):
    """sha256 of the launcher's installed APK (pm copies the file as it is), or None."""
    perf = _perf()
    m = re.search(r"package:(\S+)", sh.shell("pm path " + perf.PKG))
    if not m:
        return None
    m = re.match(r"\s*([0-9a-f]{64})\b", sh.shell("sha256sum " + m.group(1)))
    return m.group(1) if m else None


def run_params(args, other_app):
    """Every `run` option that changes a number, for result["params"]."""
    perf = _perf()
    scenarios = parse_scenarios(args.scenarios)
    return {"runs": args.runs, "rounds": args.rounds, "settle_s": args.settle, "other_app": other_app,
            "via_home": bool(args.via_home), "keys": " ".join(args.keys.split()), "key_interval_s": args.key_interval,
            "dock_moves": args.dock_moves, "dock_interval_s": args.dock_interval,
            "scenarios": [s for s in perf.ALL_SCENARIOS if s in scenarios]}


def parse_scenarios(text):
    perf = _perf()
    want = [s.strip() for s in (text or "").split(",") if s.strip()]
    bad = [s for s in want if s not in perf.ALL_SCENARIOS]
    if bad:
        raise BaselineError("unknown scenario(s): %s" % ", ".join(bad))
    return [s for s in perf.ALL_SCENARIOS if s in want]   # `run` measures them in this order


def scenarios_in(result):
    perf = _perf()
    return [s for s in perf.ALL_SCENARIOS if isinstance(result.get(s), dict)]


def scenario_params(result, scenario):
    """The parameters that `scenario`'s numbers depend on, from a result (older results keep some of them in the
    scenario's own section)."""
    p = result.get("params", {})
    sec = result.get(scenario) if isinstance(result.get(scenario), dict) else {}
    if scenario == "cold":
        return {"runs": p.get("runs"), "settle_s": p.get("settle_s"), "other_app": p.get("other_app")}
    if scenario == "warm":
        return {"runs": p.get("runs"), "settle_s": p.get("settle_s"), "other_app": p.get("other_app"),
                "via_home": bool(p.get("via_home", sec.get("via_home", False)))}
    if scenario == "jank":
        return {"rounds": p.get("rounds"), "keys": " ".join((p.get("keys") or sec.get("keys") or "").split()),
                "key_interval_s": p.get("key_interval_s", sec.get("interval_s"))}
    if scenario == "dock":
        moves = p.get("dock_moves", len((sec.get("keys") or "").split()) or None)
        return {"rounds": p.get("rounds"), "dock_moves": moves,
                "dock_interval_s": p.get("dock_interval_s", sec.get("interval_s"))}
    if scenario == "mem":   # it measures what the scenarios before it left in memory
        ran = p.get("scenarios") or scenarios_in(result)
        return {"after": ran[:ran.index("mem")] if "mem" in ran else list(ran)}
    return {k: v for k, v in p.items() if k != "scenarios"}


def key_material(scenario, tree, result, harness_hash):
    d = result.get("device", {})
    return {"v": 1, "scenario": scenario, "launcher_tree": tree, "device": d.get("id"),
            "state": d.get("state"), "harness": harness_hash, "params": scenario_params(result, scenario),
            "build": RELEASE_BUILD}


def key_of(material):
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode()).hexdigest()[:16]


# ---- the cache -----------------------------------------------------------------------------------

def _entry_path(cache, material):
    return os.path.join(cache, "baselines", material["launcher_tree"], material["device"],
                        "%s-%s.json" % (material["scenario"], key_of(material)))


def _write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)   # atomic: sessions read and write the cache at the same time


def _read_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def load_entry(cache, material):
    e = _read_json(_entry_path(cache, material))
    return e if e and e.get("material") == material else None


def save_entry(cache, material, commit, result):
    s = material["scenario"]
    entry = {"material": material, "key": key_of(material), "commit": commit, "created": result.get("created"),
             "device": result.get("device"), "build": result.get("build"), "params": result.get("params"),
             "config": result.get("config"), "data": result[s]}
    _write_json(_entry_path(cache, material), entry)
    return entry


def entries_for(cache, tree, device=None):
    base = os.path.join(cache, "baselines", tree)
    out = []
    for d in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        if device and d != device:
            continue
        for name in sorted(os.listdir(os.path.join(base, d))):
            e = _read_json(os.path.join(base, d, name)) if name.endswith(".json") else None
            if e:
                out.append(e)
    return out


def age_hours(created, now=None):
    try:
        t = datetime.datetime.fromisoformat(created)
    except (TypeError, ValueError):
        return None
    return ((now or datetime.datetime.now()) - t).total_seconds() / 3600.0


def fmt_age(hours):
    if hours is None:
        return "at an unknown time"
    if hours < 1:
        return "%d min ago" % max(0, round(hours * 60))
    if hours < 48:
        return "%.0f h ago" % hours
    return "%.0f days ago" % (hours / 24)


def assemble(entries, ref, commit, tree):
    """One result, like `run`'s, from cached scenario entries (newest device and build facts win)."""
    entries = sorted(entries.items(), key=lambda kv: kv[1].get("created") or "")
    newest = entries[-1][1]
    result = {"label": "main", "created": newest.get("created"), "device": newest.get("device"),
              "build": newest.get("build"), "config": newest.get("config"), "params": {},
              "baseline": {"ref": ref, "commit": commit, "launcher_tree": tree, "scenarios": {}}}
    for s, e in entries:
        if s != "mem":
            result["params"].update(scenario_params({"params": e.get("params", {}), s: e["data"]}, s))
        result["baseline"]["scenarios"][s] = {"created": e.get("created"), "commit": e.get("commit"),
                                              "key": e.get("key")}
        result[s] = e["data"]
    result["params"]["scenarios"] = [s for s in _perf().ALL_SCENARIOS if s in result]
    return result


# ---- main's APK ----------------------------------------------------------------------------------

@contextlib.contextmanager
def _lock(path, log):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    f = open(path, "a")
    try:
        try:
            import fcntl
        except ImportError:   # Windows: no lock; two sessions might build the same APK at once
            fcntl = None
        if fcntl:
            try:
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                log("waiting for another session that is building the same APK")
                fcntl.flock(f, fcntl.LOCK_EX)
        yield
    finally:
        f.close()


def _build_env():
    env = dict(os.environ)
    if not env.get("JAVA_HOME"):
        home = None
        if os.path.exists("/usr/libexec/java_home"):
            p = subprocess.run(["/usr/libexec/java_home", "-v", "17"], capture_output=True, text=True)
            home = p.stdout.strip() if p.returncode == 0 else None
        env["JAVA_HOME"] = home or "/opt/homebrew/opt/openjdk@17"
    if not env.get("ANDROID_HOME"):
        sdk = os.path.expanduser("~/Library/Android/sdk")
        env["ANDROID_HOME"] = sdk if os.path.isdir(sdk) else "/opt/homebrew/share/android-commandlinetools"
    env["PATH"] = os.path.join(env["JAVA_HOME"], "bin") + os.pathsep + env.get("PATH", "")
    return env


def build_release(launcher_dir, log):
    """`./gradlew assembleRelease` in a launcher/ folder; the APK's path."""
    env = _build_env()
    props = os.path.join(launcher_dir, "local.properties")
    if not os.path.exists(props):
        with open(props, "w") as f:
            f.write("sdk.dir=%s\n" % env["ANDROID_HOME"])
    gradlew = os.path.join(launcher_dir, "gradlew.bat" if os.name == "nt" else "gradlew")
    t = time.time()
    p = subprocess.run([gradlew, "assembleRelease", "-q", "--console=plain"], cwd=launcher_dir, env=env,
                       capture_output=True, text=True)
    if p.returncode != 0:
        raise BaselineError("building main's release APK failed:\n" + "\n".join((p.stdout + p.stderr).splitlines()[-30:]))
    log("built in %.0f s" % (time.time() - t))
    return os.path.join(launcher_dir, "app", "build", "outputs", "apk", "release", "app-release.apk")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def apk_meta(cache, tree):
    return _read_json(os.path.join(cache, "apks", tree + ".json"))


def ensure_apk(cache, commit, tree, log, root=None, build=None):
    """Main's release APK for this launcher/ tree: from the cache, or built once from a detached worktree of
    `commit` (other sessions wait for it rather than build it too). Returns (path, sha256)."""
    path = os.path.join(cache, "apks", tree + ".apk")
    meta = apk_meta(cache, tree)
    if os.path.exists(path) and meta:
        return path, meta["sha256"]
    with _lock(path + ".lock", log):
        meta = apk_meta(cache, tree)
        if os.path.exists(path) and meta:
            return path, meta["sha256"]
        wt = tempfile.mkdtemp(prefix="tvhome-main-%s-" % commit[:7])
        os.rmdir(wt)
        log("building main's release APK (%s, launcher/ %s) in %s" % (commit[:7], tree[:7], wt))
        if git("worktree", "add", "--detach", "--quiet", wt, commit, root=root) is None:
            raise BaselineError("git worktree add %s %s failed" % (wt, commit))
        try:
            apk = (build or build_release)(os.path.join(wt, "launcher"), log)
            tmp = path + ".tmp"
            shutil.copyfile(apk, tmp)
            os.replace(tmp, path)
            sha = sha256_file(path)
            _write_json(path[:-4] + ".json", {"commit": commit, "launcher_tree": tree, "sha256": sha,
                                              "bytes": os.path.getsize(path),
                                              "built": datetime.datetime.now().isoformat(timespec="seconds")})
        finally:
            git("worktree", "remove", "--force", wt, root=root)
            shutil.rmtree(wt, ignore_errors=True)
            git("worktree", "prune", root=root)
    return path, sha


def install_apk(sh, apk, log):
    """Installs over the launcher that is there (`pm install -r`: an uninstall would drop the Home button service's
    settings), compiles it with `speed` like the install scripts, and brings it up."""
    perf = _perf()
    if not perf.parse_package(sh.shell("dumpsys package " + perf.PKG)):
        raise BaselineError("%s is not installed on this device; install it once with launcher/build-and-install.sh "
                            "(that also makes it Home), then run this again" % perf.PKG)
    if getattr(sh, "native_push", True):
        sh.push(apk, REMOTE_APK)
    else:
        import b64push
        with open(apk, "rb") as f:
            if not b64push.push_base64(sh, f.read(), REMOTE_APK):
                raise BaselineError("could not send the APK to the device")
    out = sh.shell("pm install -r " + REMOTE_APK, timeout=300)
    sh.shell("rm -f " + REMOTE_APK)
    if "Success" not in out:
        raise BaselineError("pm install -r failed: %s" % out.strip())
    sh.shell("cmd package compile -m speed -f %s >/dev/null 2>&1; echo compiled" % perf.PKG, timeout=600)
    for perm in ("ACCESS_FINE_LOCATION", "READ_TV_LISTINGS"):
        sh.shell("pm grant %s android.permission.%s 2>/dev/null; echo granted" % (perf.PKG, perm))
    if "package:" in sh.shell("pm path " + GOOGLE_TV_LAUNCHER):
        # As launcher/build-and-install.sh does: keep the Home button service allowed to start, and on.
        sh.shell("appops set %s AUTO_START allow 2>/dev/null; appops write-settings; echo auto-start" % perf.PKG)
        services = sh.shell("settings get secure enabled_accessibility_services").strip()
        if HOME_SERVICE not in services:
            log("turning the Home button service back on")
            sh.shell("settings put secure enabled_accessibility_services %s" % (
                HOME_SERVICE if services in ("", "null") else "%s:%s" % (services, HOME_SERVICE)))
            sh.shell("settings put secure accessibility_enabled 1")
    sh.shell("am start -n " + perf.ACTIVITY)
    pkg = perf.parse_package(sh.shell("dumpsys package " + perf.PKG)) or {}
    if pkg.get("debuggable") is not False:
        raise BaselineError("main's build is installed but debuggable; its release build was expected")
    if pkg.get("compiler_filter") != "speed":
        log("note: ART compiled it %s, not speed: it does that to the device's default Home app. Your build gets the "
            "same when installed the same way; `compare --against` checks." % pkg.get("compiler_filter"))


# ---- commands --------------------------------------------------------------------------------------

def _log(args):
    stream = sys.stdout   # bound now: cmd_ensure hides `run`'s own table, not its progress
    return getattr(args, "log", None) or (lambda msg: print(msg, file=stream, flush=True))


def _describe_device(d):
    d = d or {}
    name = "%s %s" % (d.get("manufacturer") or "?", d.get("model") or "?")
    if d.get("emulator_avd"):
        name += " (emulator AVD %s)" % d["emulator_avd"]
    return name


def _params_text(material):
    p = material.get("params", {})
    return ", ".join("%s=%s" % (k, " ".join(v) if isinstance(v, list) else v) for k, v in sorted(p.items())
                     if k != "keys") + (", keys=%d presses" % len(p["keys"].split()) if p.get("keys") else "")


def cmd_ensure(args):
    """Measures main on the device for each requested scenario that isn't cached, caches it, and writes the whole
    baseline as one result file. A full hit installs and measures nothing."""
    perf = _perf()
    log = _log(args)
    resolver = getattr(perf, "resolve_run_args", None)   # "Scoped perf runs": --scenarios auto, presets, --quick
    if resolver:
        resolver(args, log)
    scenarios = parse_scenarios(args.scenarios)
    if not scenarios:
        log("no scenarios to measure")
        return None
    root = getattr(args, "root", None)
    ref, commit, tree = resolve_ref(args.ref, root)
    cache = cache_dir(root)
    log("main: %s %s, launcher/ %s" % (ref, commit[:7], tree[:7]))
    sh = args.shell or perf.connect(args)
    try:
        ident = device_identity(sh)
        other = perf.resolve_other_app(sh, args.other_app, log) if {"cold", "warm"} & set(scenarios) else None
        probe = {"device": ident, "params": run_params(args, other)}
        hits, need, why = {}, [], {}
        for s in scenarios:
            material = key_material(s, tree, probe, harness(s))
            e = load_entry(cache, material)
            age = age_hours(e.get("created")) if e else None
            if e and not args.refresh and (age is None or age <= args.max_age * 24):
                hits[s] = e
            else:
                need.append(s)
                why[s] = "asked to" if e and args.refresh else ("older than %g days" % args.max_age if e else "not cached")
        if "mem" in need:   # mem measures what ran before it, so those run again too
            need = [s for s in scenarios if s in need or s in scenario_params(probe, "mem")["after"]]
        for s, e in hits.items():
            if s not in need:
                log("cached: %s, measured %s at %s" % (s, fmt_age(age_hours(e.get("created"))), (e.get("commit") or "?")[:7]))
        if need:
            log("measuring main: %s (%s)" % (", ".join(need), "; ".join("%s %s" % (s, why.get(s, "measured with mem"))
                                                                       for s in need)))
            apk, sha = ensure_apk(cache, commit, tree, log, root=root)
            if installed_apk_sha256(sh) == sha:
                log("main's release build is already installed")
            else:
                log("installing main's release build (%s)" % apk)
                install_apk(sh, apk, log)
                if args.install_wait > 0:
                    log("waiting %g s for the device to settle after the install" % args.install_wait)
                    time.sleep(args.install_wait)
            run = argparse.Namespace(**vars(args))
            run.scenarios, run.label, run.shell, run.log = ",".join(need), "main", sh, log
            run.out = os.path.join(cache, "runs", "%s-%s-%s.json" % (
                tree[:12], ident["id"], datetime.datetime.now().strftime("%Y%m%d-%H%M%S")))
            with contextlib.redirect_stdout(io.StringIO()):   # its table covers only what it measured
                result = perf.cmd_run(run)
            problem = cache_problem(result, ident, sha, tree)
            if problem:
                raise BaselineError("not cached: " + problem)
            for s in need:
                hits[s] = save_entry(cache, key_material(s, tree, result, result.get("harness", {}).get(s)),
                                     commit, result)
        else:
            log("nothing to measure: every scenario is cached for this device")
    finally:
        if not args.shell:
            sh.close()
    baseline = assemble({s: hits[s] for s in scenarios}, ref, commit, tree)
    out = args.out or os.path.join(perf.RESULTS_DIR, "main.json")
    _write_json(out, baseline)
    log("saved %s" % out)
    print(perf.render_table([baseline]))
    return baseline


def cache_problem(result, ident, sha, tree):
    """Why a fresh measurement of main must not be cached, or None."""
    b = result.get("build", {})
    if b.get("debuggable") is not False:
        return "the measured build was debuggable, not main's release build"
    if b.get("apk_sha256") and b["apk_sha256"] != sha:
        return "the APK on the device is not main's (sha256 %s, expected %s)" % (b["apk_sha256"][:12], sha[:12])
    prov = b.get("provenance") or {}
    if prov.get("launcher_tree") and prov["launcher_tree"] != tree:
        return "the APK on the device was built from launcher/ %s, not main's %s" % (prov["launcher_tree"][:7], tree[:7])
    if result.get("device", {}).get("id") != ident["id"] or result["device"].get("state") != ident["state"]:
        return "the device changed while it was measured"
    cfg = result.get("config") or {}
    if cfg:
        if (cfg.get("launcher") or {}).get("default_config") is not True:
            return "the launcher did not run in its default configuration"
        scales = cfg.get("animation_scales")
        if scales and {k: str(v) for k, v in scales.items()} != ident["state"]["animation_scales"]:
            return "the animation scales changed while it was measured"
    return None


def cmd_show(args):
    root = getattr(args, "root", None)
    ref, commit, tree = resolve_ref(args.ref, root)
    cache = cache_dir(root)
    print("%s is %s; launcher/ %s" % (ref, commit[:7], tree[:7]))
    meta = apk_meta(cache, tree)
    print("release APK: %s" % (os.path.join(cache, "apks", tree + ".apk") + " (built from %s)" % meta["commit"][:7]
                               if meta else "not built yet (`perf.py baseline apk`)"))
    entries = entries_for(cache, tree)
    if not entries:
        print("no measurements cached for this launcher/ tree yet (`perf.py baseline ensure` on a device)")
        return entries
    current = {}
    by_device = {}
    for e in entries:
        by_device.setdefault(e["material"]["device"], []).append(e)
    for dev, es in by_device.items():
        print("\n%s, device %s:" % (_describe_device(es[-1].get("device")), dev))
        for e in sorted(es, key=lambda e: (e["material"]["scenario"], e.get("created") or "")):
            s = e["material"]["scenario"]
            current.setdefault(s, harness(s))
            stale = "" if e["material"]["harness"] == current[s] else "  [measured with different %s code]" % s
            print("  %-5s %s at %s: %s%s" % (s, fmt_age(age_hours(e.get("created"))), (e.get("commit") or "?")[:7],
                                           _params_text(e["material"]), stale))
    return entries


def cmd_list(args):
    root = getattr(args, "root", None)
    cache = cache_dir(root)
    print("cache: %s" % cache)
    trees = sorted(os.listdir(os.path.join(cache, "baselines"))) if os.path.isdir(os.path.join(cache, "baselines")) else []
    apks = {n[:-5] for n in os.listdir(os.path.join(cache, "apks")) if n.endswith(".json")} \
        if os.path.isdir(os.path.join(cache, "apks")) else set()
    for tree in sorted(set(trees) | apks):
        es = entries_for(cache, tree)
        meta = apk_meta(cache, tree) or {}
        commits = sorted({(e.get("commit") or "?")[:7] for e in es} | ({meta["commit"][:7]} if meta else set()))
        newest = max((e.get("created") or "" for e in es), default=None)
        print("launcher/ %s (main %s): %d measurement(s) on %d device(s)%s%s" % (
            tree[:7], ", ".join(commits), len(es), len({e["material"]["device"] for e in es}),
            ", newest %s" % fmt_age(age_hours(newest)) if newest else "", ", APK cached" if meta else ""))


def cmd_apk(args):
    root = getattr(args, "root", None)
    ref, commit, tree = resolve_ref(args.ref, root)
    path, sha = ensure_apk(cache_dir(root), commit, tree, _log(args), root=root)
    print(path)
    return path, sha


def cmd_install(args):
    perf = _perf()
    log = _log(args)
    root = getattr(args, "root", None)
    ref, commit, tree = resolve_ref(args.ref, root)
    apk, sha = ensure_apk(cache_dir(root), commit, tree, log, root=root)
    sh = args.shell or perf.connect(args)
    try:
        if installed_apk_sha256(sh) == sha:
            log("main's release build (%s) is already installed" % commit[:7])
        else:
            install_apk(sh, apk, log)
            log("installed main's release build (%s)" % commit[:7])
    finally:
        if not args.shell:
            sh.close()


def compare_against(name, path, force=False, root=None):
    """Markdown table of main's cached baseline against a result file, for the same device and parameters."""
    perf = _perf()
    with open(path) as f:
        mine = json.load(f)
    ref, commit, tree = resolve_ref(name, root)
    b = mine.get("build", {})
    if b.get("debuggable") is not False:
        raise BaselineError("%s measured a debug build; main's baseline is its release build. Install yours with "
                            "launcher/build-and-install.sh and measure again." % path)
    if not mine.get("device", {}).get("id"):
        raise BaselineError("%s has no device identity (an older perf.py measured it); measure again" % path)
    scenarios = scenarios_in(mine)
    cache = cache_dir(root)
    found, missing = {}, []
    for s in scenarios:
        material = key_material(s, tree, mine, (mine.get("harness") or {}).get(s) or harness(s))
        e = load_entry(cache, material)
        if e:
            found[s] = e
        else:
            missing.append((s, material))
    if missing:
        lines = ["main's baseline (%s %s, launcher/ %s) isn't cached for: %s." % (
            ref, commit[:7], tree[:7], ", ".join(s for s, _ in missing))]
        for s, material in missing:
            others = [e["material"] for e in entries_for(cache, tree, material["device"])
                      if e["material"]["scenario"] == s]
            for o in others[:3]:
                diff = [k for k in sorted(set(o) | set(material)) if k != "params" and o.get(k) != material.get(k)]
                diff += ["%s %s (yours %s)" % (k, v, material["params"].get(k))
                         for k, v in o["params"].items() if material["params"].get(k) != v]
                lines.append("  cached %s differs: %s" % (s, "; ".join(map(str, diff))))
        lines.append("Measure it on the same device with the same options:\n  " + ensure_command(mine, missing))
        raise BaselineError("\n".join(lines))
    compiled = {s: (e.get("build") or {}).get("compiler_filter") for s, e in found.items()}
    differ = sorted((s for s, f in compiled.items() if f != b.get("compiler_filter")), key=scenarios.index)
    if differ:
        raise BaselineError(
            "main's baseline for %s was compiled %s, yours %s: ART compiles the default Home app with speed-profile "
            "(verify until it has a profile), so it depends on how each was installed. Measure main again the way "
            "yours was installed:\n  %s" % (", ".join(differ), "/".join(sorted({str(compiled[s]) for s in differ})),
                                           b.get("compiler_filter"),
                                           ensure_command(mine, [(s, None) for s in differ], "refresh")))
    baseline = assemble(found, ref, commit, tree)
    notes = []
    provenance = sys.modules.get("provenance")
    problems = getattr(provenance, "problems", None) if provenance else None
    if problems:
        for level, text in problems(baseline, mine, main_ref=ref):
            if level == "mismatch" and not force:
                raise BaselineError(text + " (--force compares anyway)")
            notes.append(text)
    ages = {s: age_hours(e.get("created")) for s, e in found.items()}
    by_age = {}
    for s in scenarios:
        by_age.setdefault(fmt_age(ages[s]), []).append(s)
    notes.append("main's baseline from the cache: %s %s, launcher/ %s; %s." % (
        ref, commit[:7], tree[:7], "; ".join("%s measured %s" % (", ".join(v), k) for k, v in by_age.items())))
    if any(a is not None and a > OLD_HOURS for a in ages.values()):
        notes.append("A TV drifts over days (app updates, memory). When a change is within the noise, measure main "
                     "again right before or after yours: `perf.py baseline refresh` with the same options.")
    if (mine.get("build", {}).get("apk_sha256") and baseline.get("build", {}).get("apk_sha256")
            == mine["build"]["apk_sha256"]):
        notes.append("Your result measured main's own build.")
    table = perf.render_table([baseline, mine])
    return table + "".join("\n- " + n for n in notes)


def ensure_command(result, missing, verb="ensure"):
    p = result.get("params", {})
    want = sorted({s for s, _ in missing}, key=_perf().ALL_SCENARIOS.index)
    if "mem" in want:
        want = sorted(set(want) | set(scenario_params(result, "mem")["after"]), key=_perf().ALL_SCENARIOS.index)
    cmd = ["tools/perf.py", "baseline", verb, "--scenarios", ",".join(want)]
    for opt, key in (("--runs", "runs"), ("--rounds", "rounds"), ("--settle", "settle_s"),
                     ("--key-interval", "key_interval_s"), ("--dock-moves", "dock_moves"),
                     ("--dock-interval", "dock_interval_s"), ("--other-app", "other_app")):
        if p.get(key) is not None:
            cmd += [opt, str(p[key])]
    if p.get("keys"):
        cmd += ["--keys", "'%s'" % p["keys"]]
    if p.get("via_home"):
        cmd.append("--via-home")
    return " ".join(cmd)


def add_arguments(sub, conn, add_run_options):
    """`perf.py baseline ...`: perf.py's build_parser passes its connection and run-option helpers."""
    b = sub.add_parser("baseline", help="main's baseline, measured once and shared by every worktree",
                       description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    bsub = b.add_subparsers(dest="baseline_cmd", required=True)

    def ref(p):
        p.add_argument("--ref", default=DEFAULT_REF, help="main to measure (default origin/main; git fetch first)")

    for name, refresh, help_text in (("ensure", False, "measure main for the scenarios not cached for this device"),
                                     ("refresh", True, "measure main again for these scenarios, cached or not")):
        p = bsub.add_parser(name, help=help_text)
        conn(p)
        add_run_options(p)
        ref(p)
        p.add_argument("--max-age", type=float, default=MAX_AGE_DAYS,
                       help="measure again when the cached one is older than this many days (default %g)" % MAX_AGE_DAYS)
        p.add_argument("--install-wait", type=float, default=INSTALL_WAIT_S,
                       help="seconds to let the device settle after installing main (default %g)" % INSTALL_WAIT_S)
        p.add_argument("--out", help="baseline result file (default tools/perf-results/main.json)")
        p.set_defaults(func=cmd_ensure, refresh=refresh)
    p = bsub.add_parser("show", help="what's cached for main's launcher/ tree (no device needed)")
    ref(p)
    p.set_defaults(func=cmd_show)
    p = bsub.add_parser("list", help="everything in the cache")
    p.set_defaults(func=cmd_list)
    p = bsub.add_parser("apk", help="build main's release APK once per launcher/ tree and print its path")
    ref(p)
    p.set_defaults(func=cmd_apk)
    p = bsub.add_parser("install", help="install main's release build (from the cache) on the device")
    conn(p)
    ref(p)
    p.set_defaults(func=cmd_install)
    return b
