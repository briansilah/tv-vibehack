#!/usr/bin/env python3
"""Walk through a change the same way every time, on an emulator or a TV: the launcher's D-pad keys, waits,
screenshots and clips from a plan that says what each picture should show.

A daily improvement run writes a plan and proves it on its own emulator; /test-on-tv replays it on the TV
(.github/skills/daily-improvement and .github/skills/test-on-tv).

    walkthrough.py check plan.json                     check a plan and list its steps
    walkthrough.py run plan.json --out DIR             run it on an emulator (ANDROID_SERIAL=emulator-5570)
    walkthrough.py run plan.json --out DIR --tv        run it on a real device: the TV (--other-app as perf.py's)
    walkthrough.py comment plan.json --manifest DIR/manifest.json --pr 34    the comment for pull request 34
    walkthrough.py fetch 34 -o plan.json               the plan from pull request 34's newest walkthrough comment

A plan only does what keeps a shared TV working: no shell commands, no Home or Power keys, no force-stop, no
uninstall, no Liquid Glass value. Without --tv it refuses any device that isn't an emulator. After a connection
drop it reconnects, but never presses keys again. On a debuggable build it fails when the plan leaves the
launcher's saved settings (home.xml) changed, and puts them back exactly as they were, even when a step failed
before the plan could undo them. `comment` takes only a plan that passed on an emulator.

A plan (JSON):

    {"version": 1, "title": "Home Settings: one row per choice", "pr": 34,
     "default_config": false,
     "steps": [
       {"launcher": true},
       {"to_first_dock_app": true},
       {"keys": "DOWN DOWN RIGHT", "interval": 0.4},
       {"longpress": "CENTER"},
       {"wait": 1.5},
       {"screenshot": "actions", "expect": "The app's actions, in three groups, Move first"},
       {"keys": "BACK"},
       {"activity": "SettingsActivity", "package": "com.netflix.ninja"},
       {"expect_top": "SettingsActivity"},
       {"record": "dock-moves", "seconds": 6, "expect": "The Top Shelf follows each Dock app",
        "steps": [{"keys": "BACK"}, {"keys": "RIGHT RIGHT LEFT LEFT", "interval": 0.8}]},
       {"cold_restart": true},
       {"screenshot": "after-restart", "expect": "Home as before, the first Dock app highlighted"}
     ],
     "perf": {"scenarios": "auto"}}

Steps: launcher (shows Home; "glass_test_art": true or false), activity (MainActivity or SettingsActivity,
"package" opens Home Settings on that app), keys (UP DOWN LEFT RIGHT CENTER BACK MENU; "interval" in seconds),
longpress (one key), wait (seconds), screenshot and record (a name, and "expect": what it should show; record takes
"seconds" up to 30 and the "steps" to do while it records), cold_restart (another app in front, `am kill`, Home
again), expect_top (text the resumed activity must contain), to_first_dock_app (from anywhere on Home).
"default_config": true shows config.xml's look for the process instead of the owner's saved choices. "perf" is for
/test-on-tv: perf.py's --scenarios ("auto" by default) and, rarely, --variant.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tv  # noqa: E402
import perf  # noqa: E402

PKG = perf.PKG
KEYCODES = dict(perf.KEYCODES, MENU=82)
ACTIVITIES = ("MainActivity", "SettingsActivity")
SETTINGS_PACKAGE_EXTRA = "com.gathin.tvhome.PACKAGE"   # SettingsActivity.EXTRA_PACKAGE
DEVICE_DIR = "/data/local/tmp/tvhome-walkthrough"
PREFS = "shared_prefs/home.xml"
MARKER = "<!-- tv-walkthrough v1 -->"
TRUSTED = ("OWNER", "MEMBER", "COLLABORATOR")
RESULTS_DIR = os.path.join(HERE, "walkthrough-results")
MAX_WAIT, MAX_RECORD = 30, 30
NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,40}$")
PACKAGE_NAME = re.compile(r"^[A-Za-z]\w*(\.[A-Za-z]\w*)+$")
TOP_LEVEL = ("version", "title", "pr", "default_config", "steps", "perf")
# The step kinds and the options each takes besides its own key.
STEPS = {
    "launcher": ("glass_test_art",),
    "activity": ("package",),
    "keys": ("interval",),
    "longpress": (),
    "wait": (),
    "screenshot": ("expect",),
    "record": ("seconds", "expect", "steps"),
    "cold_restart": (),
    "expect_top": (),
    "to_first_dock_app": (),
}


class PlanError(Exception):
    """A plan that can't run, worded for whoever wrote it."""


# ---- checking a plan ---------------------------------------------------------------------------

def _number(v, low, high):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and low <= v <= high


def _step_problems(step, where, names, inside_record=False):
    if not isinstance(step, dict):
        return ['%s: a step is an object, such as {"keys": "DOWN"}' % where]
    kinds = [k for k in step if k in STEPS]
    if len(kinds) != 1:
        return ["%s: each step is exactly one of %s; this one has %s"
                % (where, ", ".join(STEPS), ", ".join(sorted(step)) or "nothing")]
    kind = kinds[0]
    v = step[kind]
    where = "%s (%s)" % (where, kind)
    problems = []
    extra = sorted(set(step) - {kind} - set(STEPS[kind]))
    if extra:
        problems.append("%s: doesn't take %s" % (where, ", ".join(extra)))
    if kind in ("launcher", "cold_restart", "to_first_dock_app") and v is not True:
        problems.append('%s: write {"%s": true}' % (where, kind))
    if kind == "launcher" and "glass_test_art" in step and not isinstance(step["glass_test_art"], bool):
        problems.append("%s: glass_test_art is true or false" % where)
    if kind == "activity":
        if v not in ACTIVITIES:
            problems.append("%s: only the launcher's own %s" % (where, " and ".join(ACTIVITIES)))
        pkg = step.get("package")
        if pkg is not None and (v != "SettingsActivity" or not isinstance(pkg, str) or not PACKAGE_NAME.match(pkg)):
            problems.append("%s: package is an app's package name, and only for SettingsActivity" % where)
    if kind in ("keys", "longpress"):
        keys = v.split() if isinstance(v, str) else []
        bad = [k for k in keys if k.upper() not in KEYCODES]
        if not keys or bad:
            problems.append("%s: keys are %s, separated by spaces%s. Home, Power and the rest are left out on "
                            "purpose: an injected Home lands on the stock home on Google TV"
                            % (where, " ".join(KEYCODES), "; not " + " ".join(bad) if bad else ""))
        if kind == "longpress" and len(keys) > 1:
            problems.append("%s: one key" % where)
        if "interval" in step and not _number(step["interval"], 0.05, 5):
            problems.append("%s: interval is seconds between keys, 0.05 to 5" % where)
    if kind == "wait" and not _number(v, 0.1, MAX_WAIT):
        problems.append("%s: seconds, up to %d" % (where, MAX_WAIT))
    if kind in ("screenshot", "record"):
        if not isinstance(v, str) or not NAME.match(v):
            problems.append("%s: a name in lower case, digits and dashes, such as dock-moves" % where)
        elif v in names:
            problems.append("%s: %s is used twice" % (where, v))
        else:
            names.add(v)
        if not isinstance(step.get("expect"), str) or not step["expect"].strip():
            problems.append('%s: "expect" says what it should show, so whoever replays it can tell a pass' % where)
    if kind == "record":
        if inside_record:
            problems.append("%s: not inside another record" % where)
        if not _number(step.get("seconds"), 1, MAX_RECORD):
            problems.append("%s: seconds, 1 to %d" % (where, MAX_RECORD))
        inner = step.get("steps", [])
        if not isinstance(inner, list):
            problems.append("%s: steps is a list" % where)
        else:
            for i, s in enumerate(inner, 1):
                problems += _step_problems(s, "%s step %d" % (where, i), names, inside_record=True)
    if kind == "expect_top" and (not isinstance(v, str) or not v.strip()):
        problems.append("%s: the text the resumed activity must contain, such as SettingsActivity" % where)
    return problems


def problems(plan):
    """What's wrong with a plan, as sentences; [] when it can run."""
    if not isinstance(plan, dict):
        return ["a plan is a JSON object"]
    found = []
    extra = sorted(set(plan) - set(TOP_LEVEL))
    if extra:
        found.append("unknown fields: %s (a plan has %s)" % (", ".join(extra), ", ".join(TOP_LEVEL)))
    if plan.get("version") != 1:
        found.append('"version": 1')
    if not isinstance(plan.get("title"), str) or not plan["title"].strip():
        found.append('"title": what the walkthrough shows, in a few words')
    if "pr" in plan and (not isinstance(plan["pr"], int) or isinstance(plan["pr"], bool) or plan["pr"] < 1):
        found.append('"pr" is the pull request number')
    if "default_config" in plan and not isinstance(plan["default_config"], bool):
        found.append('"default_config" is true or false')
    steps = plan.get("steps")
    if not isinstance(steps, list) or not steps:
        found.append('"steps": a list of steps')
    else:
        names = set()
        for i, s in enumerate(steps, 1):
            found += _step_problems(s, "step %d" % i, names)
    p = plan.get("perf")
    if p is not None:
        if not isinstance(p, dict) or set(p) - {"scenarios", "variant"}:
            found.append('"perf" has "scenarios" and, rarely, "variant"')
        else:
            sc = p.get("scenarios", "auto")
            parts = [s.strip() for s in sc.split(",")] if isinstance(sc, str) else [None]
            if sc not in ("auto", "none") and not all(s in perf.ALL_SCENARIOS for s in parts):
                found.append('"perf.scenarios": "auto", "none" or some of %s' % ",".join(perf.ALL_SCENARIOS))
            var = p.get("variant")
            if var is not None and (not isinstance(var, str) or not perf._VARIANT.match(var) or "liquid_glass" in var):
                found.append('"perf.variant": only --ez glass_test_art true/false on a shared TV')
    return found


def check(plan):
    found = problems(plan)
    if found:
        raise PlanError("the plan can't run:\n  - " + "\n  - ".join(found))
    return plan


def load(path):
    try:
        with open(path) as f:
            return check(json.load(f))
    except (OSError, ValueError) as e:
        raise PlanError("%s: %s" % (path, e)) from None


def describe_step(step):
    kind = next(k for k in step if k in STEPS)
    v = step[kind]
    if kind == "launcher":
        art = step.get("glass_test_art")
        return "show Home" + ("" if art is None else " (stand-in art %s)" % ("on" if art else "off"))
    if kind == "activity":
        return "open %s%s" % (v, " on " + step["package"] if step.get("package") else "")
    if kind == "keys":
        return "press %s" % v
    if kind == "longpress":
        return "hold %s" % v
    if kind == "wait":
        return "wait %g s" % v
    if kind == "screenshot":
        return "screenshot %s: %s" % (v, step["expect"])
    if kind == "record":
        return "record %s for %g s (%s): %s" % (v, step["seconds"], "; ".join(
            describe_step(s) for s in step.get("steps", [])) or "nothing pressed", step["expect"])
    if kind == "cold_restart":
        return "another app in front, am kill, Home again (a cold start)"
    if kind == "expect_top":
        return "the resumed activity contains %s" % v
    return "go to the first Dock app"


def perf_args(plan):
    """perf.py's options for this plan: (--scenarios value, --variant value or None)."""
    p = plan.get("perf") or {}
    return p.get("scenarios", "auto"), p.get("variant")


# ---- the PR comment that carries a plan --------------------------------------------------------

def plan_hash(plan):
    """What a dry run proved: the plan without its pull request number, which is only known afterwards."""
    body = {k: v for k, v in plan.items() if k != "pr"}
    return hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:16]


def comment_body(plan, manifest, pr=None):
    """The pull request comment for a plan that passed on an emulator (manifest: that run's manifest.json)."""
    if not manifest.get("ok") or not manifest.get("emulator"):
        raise PlanError("the manifest isn't a passing run on an emulator: run the plan on yours until it passes")
    if manifest.get("plan_hash") != plan_hash(plan):
        raise PlanError("the manifest is from another plan, or the plan changed since: run it again")
    plan = dict(plan, pr=pr) if pr else plan
    dev = manifest["device"]
    lines = [MARKER, "**TV walkthrough** for `/test-on-tv`: %s" % plan["title"], "",
             "Dry run: all %d steps passed on %s (emulator, Android %s), build `%s`, %s; settings %s; plan `%s`."
             % (len(manifest["steps"]), manifest["serial"], dev.get("android"),
                manifest["build"].get("version_name"), manifest["finished"][:10],
                "unchanged" if manifest["settings"].get("checked") else "not checked (a release build)",
                manifest["plan_hash"]), "",
             "<details><summary>Steps</summary>", ""]
    lines += ["%d. %s" % (i, describe_step(s)) for i, s in enumerate(plan["steps"], 1)]
    scenarios, variant = perf_args(plan)
    lines += ["", "Perf: `--scenarios %s`%s" % (scenarios, " `--variant '%s'`" % variant if variant else ""), "",
              "</details>", "", "```json", json.dumps(plan, indent=2), "```", ""]
    return "\n".join(lines)


def plan_from_comments(comments, pr=None):
    """The newest walkthrough plan among a pull request's comments ({"body", "author_association", ...}), from
    someone with write access only, and written for this pull request. PlanError when there's none."""
    for c in reversed(comments):
        body = c.get("body") or ""
        if MARKER not in body:
            continue
        if c.get("author_association") not in TRUSTED:
            continue
        m = re.search(r"```json\s*\n(.*?)\n```", body.split(MARKER, 1)[1], re.S)
        if not m:
            raise PlanError("the newest walkthrough comment has no ```json block")
        try:
            plan = check(json.loads(m.group(1)))
        except ValueError as e:
            raise PlanError("the newest walkthrough comment's JSON: %s" % e) from None
        if pr is not None and plan.get("pr") != pr:
            raise PlanError("the newest walkthrough comment is for pull request %s, not %d: prove a plan for this "
                            "one on an emulator and post it" % (plan.get("pr"), pr))
        return plan
    raise PlanError("no walkthrough comment (%s) from the repository's owner or collaborators" % MARKER)


def origin_repo(url):
    """owner/name from a GitHub remote URL, or None."""
    m = re.match(r"^(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)([\w.-]+/[\w.-]+?)(?:\.git)?/?$",
                 (url or "").strip())
    return m.group(1) if m else None


def fetch(pr, repo=None):
    """The plan from pull request pr's comments, on origin's repository: gh would pick an `upstream` remote."""
    if not repo:
        url = subprocess.run(["git", "remote", "get-url", "origin"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, cwd=HERE).stdout
        repo = origin_repo(url)
        if not repo:
            raise PlanError("origin isn't a GitHub repository (%s): pass --repo owner/name" % url.strip())
    out = subprocess.run(["gh", "api", "repos/%s/issues/%d/comments" % (repo, pr), "--paginate",
                          "--jq", ".[] | {body, author_association, created_at}"],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if out.returncode != 0:
        raise PlanError("gh api: %s" % out.stderr.strip())
    return plan_from_comments([json.loads(l) for l in out.stdout.splitlines() if l.strip()], pr)


# ---- running a plan ----------------------------------------------------------------------------

def parse_prefs(xml):
    """home.xml → {name: its whole element}, to say which saved settings changed."""
    return {m.group(1): m.group(0).strip() for m in re.finditer(r'<\w+ name="([^"]+)"[^>]*?(?:/>|>.*?</\w+>)', xml, re.S)}


class Runner:
    """Runs a checked plan on a connected device (tools/tv.py's shell(), pull())."""

    def __init__(self, sh, plan, out, allow_tv=False, other_app=None, log=None, sleep=time.sleep, clock=time.time):
        self.sh, self.plan, self.out, self.allow_tv, self.other_app = sh, plan, out, allow_tv, other_app
        self.log = log or (lambda m: print(m, file=sys.stderr))
        self.sleep, self.clock = sleep, clock
        self.default_config = bool(plan.get("default_config"))
        self.test_art = False
        self.count = 0

    def shell(self, cmd, timeout=120, retry=False):
        """The TV's Wi-Fi drops now and then (tv-lease): reconnect, and run the command once more only when
        running it twice is harmless. Keys and starts are never replayed: some may already have arrived."""
        try:
            return self.sh.shell(cmd, timeout=timeout)
        except tv.TvError as e:
            if not self._reconnect(e) or not retry:
                raise
            return self.sh.shell(cmd, timeout=timeout)

    def _reconnect(self, error):
        serial = getattr(self.sh, "serial", None)
        if not serial or ":" not in serial or not hasattr(self.sh, "exe"):
            return False
        self.log("  lost the device (%s); adb connect %s, then once more" % (error, serial))
        tv.run_adb([self.sh.exe, "connect", serial], 30)
        for _ in range(15):
            if self.sh.attached():
                return True
            self.sleep(2)
        return False

    def start(self, activity="MainActivity", extras=""):
        cmd = "am start -W -n %s/.%s" % (PKG, activity)
        if activity == "MainActivity" and self.default_config:
            cmd += " " + perf.DEFAULT_CONFIG + " true"
        out = self.shell((cmd + " " + extras).strip())
        if "Error" in out or "Exception" in out:
            raise PlanError("could not start %s: %s" % (activity, out.strip()))
        return out

    def press(self, keys, interval=0.4):
        codes = " ".join(str(KEYCODES[k.upper()]) for k in keys.split())
        self.shell("for k in %s; do input keyevent $k; sleep %s; done" % (codes, interval), timeout=120)

    def capture(self, name, ext):
        self.count += 1
        base = "%02d-%s.%s" % (self.count, name, ext)
        return DEVICE_DIR + "/" + base, os.path.join(self.out, base)

    def pull(self, remote, local):
        try:
            self.sh.pull(remote, local)
        except tv.TvError as e:
            if not self._reconnect(e):
                raise
            self.sh.pull(remote, local)
        self.shell("rm -f " + remote, retry=True)
        if not os.path.isfile(local) or os.path.getsize(local) == 0:
            raise PlanError("nothing came back from " + remote)
        return os.path.relpath(local, self.out)

    def top_activity(self):
        return self.shell("dumpsys activity activities | grep -m1 topResumedActivity", retry=True).strip()

    def do(self, step):
        kind = next(k for k in step if k in STEPS)
        v = step[kind]
        result = {}
        if kind == "launcher":
            art = step.get("glass_test_art")
            self.start(extras="" if art is None else "--ez glass_test_art %s" % ("true" if art else "false"))
            self.test_art = self.test_art if art is None else art
        elif kind == "activity":
            pkg = step.get("package")
            self.start(v, "--es %s %s" % (SETTINGS_PACKAGE_EXTRA, pkg) if pkg else "")
        elif kind == "keys":
            self.press(v, step.get("interval", 0.4))
        elif kind == "longpress":
            self.shell("input keyevent --longpress %d" % KEYCODES[v.strip().upper()])
        elif kind == "wait":
            self.sleep(v)
        elif kind == "to_first_dock_app":
            self.press(perf.TO_FIRST_DOCK_APP, 0.15)
        elif kind == "expect_top":
            top = self.top_activity()
            result["top"] = top
            if v not in top:
                raise PlanError("the resumed activity is %r, not %s" % (top, v))
        elif kind == "cold_restart":
            other = perf.resolve_other_app(self.sh, self.other_app, self.log)
            perf.open_other_app(self.sh, other)
            self.sleep(2)
            gone = perf.kill_launcher(self.sh, self.log)
            result["process_ended"] = gone
            self.start(extras="--ez glass_test_art true" if self.test_art else "")
            if not gone:
                raise PlanError("the launcher's process survived am kill, so this wasn't a cold start")
        elif kind == "screenshot":
            remote, local = self.capture(v, "png")
            self.shell("screencap -p " + remote, retry=True)
            result["file"] = self.pull(remote, local)
        elif kind == "record":
            remote, local = self.capture(v, "mp4")
            began = self.clock()
            self.shell("screenrecord --time-limit %d %s > /dev/null 2>&1 &" % (round(step["seconds"]), remote))
            self.sleep(1)
            inner = [self.run_step(s) for s in step.get("steps", [])]
            result["steps"] = inner
            left = step["seconds"] - (self.clock() - began)
            if left > 0:
                self.sleep(left)
            for _ in range(10):   # screenrecord writes the file's end after the time limit
                if not self.shell("pidof screenrecord", retry=True).strip():
                    break
                self.sleep(1)
            result["file"] = self.pull(remote, local)
            failed = [s for s in inner if not s["ok"]]
            if failed:
                raise PlanError("a step while recording failed: " + failed[0]["error"])
        return result

    def run_step(self, step):
        began = self.clock()
        entry = {"step": describe_step(step), "kind": next(k for k in step if k in STEPS), "ok": True}
        if "expect" in step:
            entry["expect"] = step["expect"]
        try:
            entry.update(self.do(step))
        except (PlanError, tv.TvError) as e:
            entry.update(ok=False, error=str(e))
        entry["seconds"] = round(self.clock() - began, 1)
        mark = "ok  " if entry["ok"] else "FAIL"
        self.log("  %s %s%s" % (mark, entry["step"], "" if entry["ok"] else ": " + entry["error"]))
        return entry

    def saved_settings(self):
        """home.xml as the launcher saved it: its text, "" when there's none yet, None when the build can't show
        it (a release build: run-as needs a debuggable one)."""
        out = self.shell("run-as %s cat %s" % (PKG, PREFS), retry=True)
        if "run-as:" in out or "not debuggable" in out or "Permission denied" in out:
            return None
        return "" if "No such file" in out else out

    def restore_settings(self, saved):
        """Puts home.xml back exactly as it was, for a plan that failed before it undid what it changed. The
        launcher keeps its settings in memory and writes them back, so its process ends first."""
        perf.open_other_app(self.sh, perf.resolve_other_app(self.sh, self.other_app, self.log))
        self.sleep(2)
        if not perf.kill_launcher(self.sh, self.log):
            self.start()
            return False
        if saved:
            data = base64.b64encode(saved.encode()).decode()
            self.shell("run-as %s sh -c 'echo %s | base64 -d > %s'" % (PKG, data, PREFS), retry=True)
        else:
            self.shell("run-as %s rm -f %s" % (PKG, PREFS), retry=True)
        self.start()
        self.sleep(1.5)
        return self.saved_settings() == saved

    def run(self):
        sh = self.sh
        emulator = perf.is_emulator(sh)
        if not emulator and not self.allow_tv:
            raise PlanError("this device isn't an emulator. A walkthrough runs on a TV only with --tv, from "
                            "/test-on-tv: daily improvement runs use their own emulator")
        package = perf.parse_package(self.shell("dumpsys package " + PKG, retry=True))
        if not package:
            raise PlanError("the launcher (%s) isn't installed: launcher/build-and-install.sh" % PKG)
        os.makedirs(self.out, exist_ok=True)
        manifest = {
            "title": self.plan["title"], "pr": self.plan.get("pr"), "plan_hash": plan_hash(self.plan),
            "serial": getattr(sh, "serial", None) or sh.describe(), "emulator": emulator,
            "device": perf.device_facts(sh), "build": package,
            "started": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
            "steps": [], "settings": None,
        }
        self.log("walkthrough: %s, on %s (%s)" % (self.plan["title"], manifest["serial"],
                                                  "emulator" if emulator else "a TV"))
        self.shell("mkdir -p " + DEVICE_DIR, retry=True)
        before = None
        try:
            self.start()
            self.sleep(1.5)
            before = self.saved_settings() if package["debuggable"] else None
            for step in self.plan["steps"]:
                entry = self.run_step(step)
                manifest["steps"].append(entry)
                if not entry["ok"]:
                    break
        finally:
            try:
                self.clean_up()
            finally:
                if before is not None:
                    manifest["settings"] = self.check_settings(before)
        if before is None:
            manifest["settings"] = {"checked": False, "why": "a release build: run-as can't read its settings"}
        manifest["finished"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        manifest["ok"] = (len(manifest["steps"]) == len(self.plan["steps"]) and
                          all(s["ok"] for s in manifest["steps"]) and not manifest["settings"].get("changed"))
        with open(os.path.join(self.out, "manifest.json"), "w") as f:
            json.dump(manifest, f, indent=2)
            f.write("\n")
        return manifest

    def check_settings(self, before):
        """Which saved settings the plan left changed; puts them back if any."""
        after = self.saved_settings()
        if after == before:
            return {"checked": True, "changed": []}
        old, new = parse_prefs(before or ""), parse_prefs(after or "")
        changed = sorted(k for k in set(old) | set(new) if old.get(k) != new.get(k)) or ["home.xml"]
        restored = self.restore_settings(before)
        self.log("  FAIL the plan left saved settings changed: %s; %s. Add steps that put them back."
                 % (", ".join(changed), "put back as they were" if restored else
                    "COULD NOT PUT THEM BACK: check them on the device"))
        return {"checked": True, "changed": changed, "restored": restored}

    def clean_up(self):
        """Stand-in art off and the owner's saved choices back, Home on screen, no files of ours left."""
        extras = []
        if self.test_art:
            extras.append("--ez glass_test_art false")
        if self.default_config:
            extras.append(perf.DEFAULT_CONFIG + " false")
        try:
            self.default_config = False
            self.start(extras=" ".join(extras))
        finally:
            self.shell("rm -rf " + DEVICE_DIR, retry=True)


# ---- command line ------------------------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check", help="check a plan and list its steps")
    c.add_argument("plan")
    r = sub.add_parser("run", help="run a plan on an emulator, or with --tv on a TV")
    r.add_argument("plan")
    r.add_argument("--out", help="where screenshots, clips and manifest.json go "
                                 "(default tools/walkthrough-results/<plan>-<time>)")
    r.add_argument("--serial", help="the device (default ANDROID_SERIAL); keep TV_HOST unset")
    r.add_argument("--adb", action="store_true", help="the adb binary even with TV_HOST set")
    r.add_argument("--tv", action="store_true", help="allow a device that isn't an emulator: only from /test-on-tv")
    r.add_argument("--other-app", help="the full-screen TV app cold_restart puts in front, as perf.py's --other-app "
                                       "(default: the first installed TV app by name)")
    m = sub.add_parser("comment", help="the pull request comment that carries a plan that passed on an emulator")
    m.add_argument("plan")
    m.add_argument("--manifest", required=True, help="the passing emulator run's manifest.json")
    m.add_argument("--pr", type=lambda v: int(v.lstrip("#")), required=True, help="the pull request it's for")
    f = sub.add_parser("fetch", help="a pull request's newest walkthrough plan")
    f.add_argument("pr", type=lambda s: int(s.lstrip("#")))
    f.add_argument("-o", "--output", help="write it here (default: print it)")
    f.add_argument("--repo", help="owner/name (default: origin's)")
    return p


def main(argv=None, shell=None, sleep=time.sleep):
    args = build_parser().parse_args(argv)
    try:
        if args.cmd == "check":
            plan = load(args.plan)
            print("%s: %d steps" % (plan["title"], len(plan["steps"])))
            for i, s in enumerate(plan["steps"], 1):
                print("  %d. %s" % (i, describe_step(s)))
            scenarios, variant = perf_args(plan)
            print("perf: --scenarios %s%s" % (scenarios, " --variant '%s'" % variant if variant else ""))
        elif args.cmd == "comment":
            plan = load(args.plan)
            with open(args.manifest) as fh:
                sys.stdout.write(comment_body(plan, json.load(fh), args.pr))
        elif args.cmd == "fetch":
            text = json.dumps(fetch(args.pr, args.repo), indent=2) + "\n"
            if args.output:
                with open(args.output, "w") as fh:
                    fh.write(text)
                print("wrote " + args.output, file=sys.stderr)
            else:
                sys.stdout.write(text)
        else:
            plan = load(args.plan)
            stem = os.path.splitext(os.path.basename(args.plan))[0]
            out = args.out or os.path.join(RESULTS_DIR, "%s-%s" % (stem, time.strftime("%Y%m%d-%H%M%S")))
            sh = shell or tv.connect(adb=args.adb, serial=args.serial)
            try:
                manifest = Runner(sh, plan, out, allow_tv=args.tv, other_app=args.other_app, sleep=sleep).run()
            finally:
                sh.close()
            ok = sum(1 for s in manifest["steps"] if s["ok"])
            print("%s: %d of %d steps passed%s; %s" % (
                "passed" if manifest["ok"] else "FAILED", ok, len(plan["steps"]),
                "; settings changed: " + ", ".join(manifest["settings"]["changed"])
                if manifest["settings"].get("changed") else "", os.path.join(out, "manifest.json")))
            return 0 if manifest["ok"] else 1
    except (PlanError, tv.TvError) as e:
        sys.exit("walkthrough.py: %s" % e)
    return 0


if __name__ == "__main__":
    sys.exit(main())
