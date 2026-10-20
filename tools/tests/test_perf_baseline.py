"""Offline tests for tools/perf_baseline.py: cache keys, the harness hash, and `baseline ensure` / `compare --against`
against a fake device, a throwaway git repository and a fake Gradle build.

    python3 -m unittest discover -s tools/tests -v
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import perf  # noqa: E402
import perf_baseline as baseline  # noqa: E402
from test_perf import DUMPSYS_PACKAGE, FakeTv  # noqa: E402

RELEASE_SPEED = DUMPSYS_PACKAGE.replace("DEBUGGABLE ", "").replace("[status=verify]", "[status=speed]")
OTHER_APP = "com.netflix.ninja/.MainActivity"


class FakeDevice(FakeTv):
    """FakeTv plus what the baseline needs: identity props, settings, push, pm install and the installed APK."""

    def __init__(self, fingerprint="TCL/G17/G17:14/UKR1/1:user/release-keys", serialno="8C00F07E0160CF00F", avd=""):
        super().__init__(debuggable=False)
        self.package = RELEASE_SPEED
        self.props = {"ro.product.manufacturer": "TCL", "ro.product.model": "Smart TV Pro", "ro.product.device": "G17",
                      "ro.build.version.release": "14", "ro.build.version.sdk": "34",
                      "ro.build.fingerprint": fingerprint, "ro.serialno": serialno, "ro.boot.qemu.avd_name": avd}
        self.settings = {"window_animation_scale": "1.0"}
        self.installed = b"branch build"
        self.pushed = None
        self.installs = 0

    def shell(self, cmd, timeout=120):
        if cmd.startswith("getprop"):
            self.commands.append(cmd)
            return self.props.get(cmd.split()[1], "") + "\n"
        if cmd.startswith("settings get global"):
            self.commands.append(cmd)
            return self.settings.get(cmd.split()[-1], "null") + "\n"
        if cmd.startswith("sha256sum"):
            self.commands.append(cmd)
            return "%s  /data/app/base.apk\n" % hashlib.sha256(self.installed).hexdigest()
        if cmd.startswith("pm install -r"):
            self.commands.append(cmd)
            self.installed, self.installs = self.pushed, self.installs + 1
            self.package = DUMPSYS_PACKAGE.replace("DEBUGGABLE ", "")   # at `verify` until compiled
            return "Success\n"
        if cmd.startswith("cmd package compile -m speed"):
            self.commands.append(cmd)
            self.package = RELEASE_SPEED
            return "compiled\n"
        return super().shell(cmd, timeout)

    def push(self, local, remote, timeout=300):
        with open(local, "rb") as f:
            self.pushed = f.read()
        return "1 file pushed"

    def measured(self):
        """Commands that measure (or change) something, as opposed to reading the device's identity."""
        return [c for c in self.commands if c.startswith(("am kill", "am start", "for k in", "dumpsys gfxinfo",
                                                          "dumpsys meminfo", "pm install", "input"))]


def git(repo, *args):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t",
               GIT_COMMITTER_EMAIL="t@t")
    return subprocess.run(["git", "-C", repo] + list(args), check=True, capture_output=True, text=True,
                          env=env).stdout.strip()


def commit(repo, path, text, msg):
    os.makedirs(os.path.dirname(os.path.join(repo, path)), exist_ok=True)
    with open(os.path.join(repo, path), "w") as f:
        f.write(text)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", msg)
    git(repo, "update-ref", "refs/remotes/origin/main", "HEAD")
    return git(repo, "rev-parse", "HEAD")


def fake_build(launcher_dir, log):
    """Stands in for Gradle: an "APK" whose bytes follow launcher/'s contents."""
    with open(os.path.join(launcher_dir, "app.txt")) as f:
        src = f.read()
    out = os.path.join(launcher_dir, "app", "build", "outputs", "apk", "release", "app-release.apk")
    os.makedirs(os.path.dirname(out))
    with open(out, "wb") as f:
        f.write(b"APK " + src.encode())
    fake_build.count += 1
    return out


fake_build.count = 0


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(self.repo)
        git(self.repo, "init", "-q")
        self.main1 = commit(self.repo, "launcher/app.txt", "launcher v1", "launcher")
        self.cache = os.path.join(self.tmp, "cache")
        for target, attr, value in ((perf.time, "sleep", lambda s: None), (baseline, "ROOT", self.repo),
                                    (baseline, "build_release", fake_build), (perf, "RESULTS_DIR", self.tmp)):
            p = mock.patch.object(target, attr, value)
            p.start()
            self.addCleanup(p.stop)
        p = mock.patch.dict(os.environ, {baseline.CACHE_ENV: self.cache})
        p.start()
        self.addCleanup(p.stop)
        fake_build.count = 0

    def perf(self, dev, *argv):
        logs = []
        with mock.patch("builtins.print") as p:
            ret = perf.main(list(argv), shell=dev, log=logs.append)
        printed = "\n".join(str(c.args[0]) for c in p.call_args_list if c.args)
        return ret, logs, printed

    def ensure(self, dev, *extra):
        out = os.path.join(self.tmp, "main.json")
        ret, logs, _ = self.perf(dev, "baseline", "ensure", "--runs", "3", "--rounds", "2", "--other-app", OTHER_APP,
                                 "--out", out, *extra)
        with open(out) as f:
            return json.load(f), logs, ret

    def run_mine(self, dev, label="mine", *extra):
        out = os.path.join(self.tmp, label + ".json")
        self.perf(dev, "run", "--label", label, "--runs", "3", "--rounds", "2", "--other-app", OTHER_APP,
                  "--out", out, *extra)
        return out


class HarnessTests(unittest.TestCase):
    SRC = textwrap.dedent('''
        import re
        PKG = "com.gathin.tvhome"
        KEYS = {"UP": 19}
        _RX = re.compile(r"ms=(\\d+)")

        def helper(x):
            return x + 1

        def parse(out):
            """Parses."""
            return _RX.findall(out)

        def scenario_cold(sh, runs):
            """Cold starts."""
            # a comment
            return [helper(i) for i in range(runs)] + [PKG]

        def scenario_jank(sh, rounds):
            return parse(sh.shell("dumpsys gfxinfo")) * rounds + [KEYS]
    ''')

    def module(self, src):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        path = os.path.join(d, "fakeperf.py")
        with open(path, "w") as f:
            f.write(src)
        spec = importlib.util.spec_from_file_location("fakeperf_%d" % id(src), path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    def test_ignores_comments_docstrings_and_blank_lines(self):
        a = self.module(self.SRC)
        b = self.module(self.SRC.replace("# a comment", "# another comment\n\n").replace('"""Cold starts."""',
                                                                                       '"""Cold\n    starts, 10 times."""'))
        self.assertEqual(baseline.harness("cold", a), baseline.harness("cold", b))

    def test_follows_helpers_and_constants_per_scenario(self):
        a = self.module(self.SRC)
        helper = self.module(self.SRC.replace("return x + 1", "return x + 2"))
        self.assertNotEqual(baseline.harness("cold", a), baseline.harness("cold", helper))
        self.assertEqual(baseline.harness("jank", a), baseline.harness("jank", helper))   # jank doesn't use it
        regex = self.module(self.SRC.replace('r"ms=(\\d+)"', 'r"ms=([\\d.]+)"'))
        self.assertNotEqual(baseline.harness("jank", a), baseline.harness("jank", regex))
        self.assertEqual(baseline.harness("cold", a), baseline.harness("cold", regex))
        const = self.module(self.SRC.replace('"UP": 19', '"UP": 20'))
        self.assertNotEqual(baseline.harness("jank", a), baseline.harness("jank", const))

    def test_unknown_scenario(self):
        self.assertIsNone(baseline.harness("nope", self.module(self.SRC)))

    def test_real_perf_has_one_per_scenario(self):
        hashes = {s: baseline.harness(s) for s in perf.ALL_SCENARIOS}
        self.assertTrue(all(h and len(h) == 12 for h in hashes.values()))
        self.assertEqual(len(set(hashes.values())), len(hashes))

    def test_epoch_changes_every_hash(self):
        before = baseline.harness("cold")
        with mock.patch.object(baseline, "HARNESS_EPOCH", baseline.HARNESS_EPOCH + 1):
            self.assertNotEqual(baseline.harness("cold"), before)


class KeyTests(unittest.TestCase):
    RESULT = {"device": {"id": "dev1", "state": {"animation_scales": {"window": "1.0"}, "tv_apps": {"hash": "a"}}},
              "params": {"runs": 10, "rounds": 3, "settle_s": 1.5, "other_app": OTHER_APP, "via_home": False,
                         "keys": perf.DEFAULT_KEYS, "key_interval_s": 0.35, "dock_moves": 8, "dock_interval_s": 1.2,
                         "scenarios": ["cold", "warm", "jank", "dock", "mem"]}}

    def key(self, scenario, result=None, tree="t1", harness="h1"):
        return baseline.key_of(baseline.key_material(scenario, tree, result or self.RESULT, harness))

    def changed(self, **params):
        r = json.loads(json.dumps(self.RESULT))
        r["params"].update(params)
        return r

    def test_each_scenario_keys_on_its_own_params(self):
        keys = self.changed(keys="UP DOWN")
        self.assertNotEqual(self.key("jank"), self.key("jank", keys))
        for s in ("cold", "warm", "dock"):
            self.assertEqual(self.key(s), self.key(s, keys), s)
        runs = self.changed(runs=5)
        self.assertNotEqual(self.key("cold"), self.key("cold", runs))
        self.assertEqual(self.key("jank"), self.key("jank", runs))
        self.assertNotEqual(self.key("warm"), self.key("warm", self.changed(via_home=True)))
        self.assertEqual(self.key("cold"), self.key("cold", self.changed(via_home=True)))
        self.assertNotEqual(self.key("dock"), self.key("dock", self.changed(dock_interval_s=1.0)))

    def test_mem_keys_on_what_ran_before_it(self):
        self.assertNotEqual(self.key("mem"), self.key("mem", self.changed(scenarios=["dock", "mem"])))
        self.assertEqual(self.key("mem", self.changed(scenarios=["dock", "mem"])),
                         self.key("mem", self.changed(scenarios=["dock", "mem"], runs=3)))

    def test_tree_device_state_and_harness(self):
        self.assertNotEqual(self.key("cold"), self.key("cold", tree="t2"))
        self.assertNotEqual(self.key("cold"), self.key("cold", harness="h2"))
        other = json.loads(json.dumps(self.RESULT))
        other["device"]["id"] = "dev2"
        self.assertNotEqual(self.key("cold"), self.key("cold", other))
        slow = json.loads(json.dumps(self.RESULT))
        slow["device"]["state"]["animation_scales"]["window"] = "0.5"
        self.assertNotEqual(self.key("cold"), self.key("cold", slow))

    def test_params_of_an_older_result(self):
        """Results from before the baseline cache keep keys, intervals and --via-home in each scenario's section."""
        old = {"params": {"runs": 10, "rounds": 3, "settle_s": 1.5, "other_app": OTHER_APP},
               "cold": {}, "warm": {"via_home": False}, "jank": {"keys": perf.DEFAULT_KEYS, "interval_s": 0.35},
               "dock": {"keys": "RIGHT LEFT " * 4, "interval_s": 1.2}, "mem": {}}
        for s in perf.ALL_SCENARIOS:
            self.assertEqual(baseline.scenario_params(old, s), baseline.scenario_params(self.RESULT, s), s)


class EnsureTests(Base):
    def test_miss_measures_and_caches_then_a_hit_measures_nothing(self):
        dev = FakeDevice()
        first, logs, _ = self.ensure(dev)
        self.assertEqual(fake_build.count, 1)
        self.assertEqual(dev.installs, 1)
        self.assertEqual(dev.installed, b"APK launcher v1")
        self.assertEqual(dev.cold_runs, 3)
        self.assertIn("waiting 60 s for the device to settle after the install", logs)
        self.assertEqual(first["baseline"]["launcher_tree"], baseline.launcher_tree("HEAD", root=self.repo))
        self.assertEqual(set(first["baseline"]["scenarios"]), set(perf.ALL_SCENARIOS))
        self.assertEqual(first["build"]["apk_sha256"], hashlib.sha256(b"APK launcher v1").hexdigest())
        self.assertEqual(first["cold"]["summary"]["total_ms"]["median"], 1234)
        self.assertFalse(any(c.startswith("am force-stop") for c in dev.commands))
        # no git worktree is left behind
        self.assertEqual(len(git(self.repo, "worktree", "list").splitlines()), 1)

        again = FakeDevice()
        second, logs, _ = self.ensure(again)
        self.assertEqual(again.measured(), [])
        self.assertEqual(fake_build.count, 1)
        self.assertIn("nothing to measure: every scenario is cached for this device", logs)
        for s in perf.ALL_SCENARIOS:
            self.assertEqual(second[s], first[s])

    def test_only_missing_scenarios_are_measured(self):
        self.ensure(FakeDevice(), "--scenarios", "cold,jank")
        dev = FakeDevice()
        dev.installed = b"APK launcher v1"   # main's build is still installed: no need to install it again
        result, logs, _ = self.ensure(dev, "--scenarios", "cold,warm,jank")
        self.assertEqual(dev.installs, 0)
        self.assertIn("main's release build is already installed", logs)
        self.assertEqual(dev.cold_runs, 0)
        self.assertEqual(sum(c.startswith("for k in") for c in dev.commands), 0)   # jank: cached
        self.assertTrue(any(c == "dumpsys gfxinfo %s reset" % perf.PKG for c in dev.commands))   # warm: measured
        self.assertEqual(set(result["baseline"]["scenarios"]), {"cold", "warm", "jank"})

    def test_mem_measures_what_runs_before_it_again(self):
        self.ensure(FakeDevice(), "--scenarios", "dock")
        dev = FakeDevice()
        self.ensure(dev, "--scenarios", "dock,mem")
        self.assertTrue(any(c.startswith("for k in") for c in dev.commands))   # dock again, then mem after it
        self.assertTrue(any(c.startswith("dumpsys meminfo") for c in dev.commands))
        dev = FakeDevice()
        self.ensure(dev, "--scenarios", "dock,mem")
        self.assertEqual(dev.measured(), [])

    def test_options_device_and_launcher_changes_are_misses(self):
        self.ensure(FakeDevice(), "--scenarios", "cold,jank")
        dev = FakeDevice()
        self.ensure(dev, "--scenarios", "cold,jank", "--keys", "UP DOWN")
        self.assertEqual(dev.cold_runs, 0)
        self.assertTrue(any(c.startswith("for k in") for c in dev.commands))

        dev = FakeDevice(fingerprint="TCL/G17/G17:14/UKR2/2:user/release-keys")   # a firmware update
        self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.cold_runs, 3)
        dev = FakeDevice(serialno="another TV of the same model")
        self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.cold_runs, 3)
        dev = FakeDevice()
        dev.settings["animator_duration_scale"] = "0.5"
        self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.cold_runs, 3)

        commit(self.repo, "docs/notes.md", "docs", "docs only")   # main moves, launcher/ doesn't
        dev = FakeDevice()
        self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.measured(), [])
        self.assertEqual(fake_build.count, 1)

        commit(self.repo, "launcher/app.txt", "launcher v2", "launcher change")
        dev = FakeDevice()
        result, _, _ = self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.cold_runs, 3)
        self.assertEqual(fake_build.count, 2)
        self.assertEqual(dev.installed, b"APK launcher v2")
        self.assertEqual(result["baseline"]["commit"], git(self.repo, "rev-parse", "HEAD"))

    def test_emulators_from_one_avd_share_a_baseline(self):
        self.ensure(FakeDevice(avd="tvperf", serialno="EMULATOR37X2X12X0"), "--scenarios", "cold")
        dev = FakeDevice(avd="tvperf", serialno="EMULATOR37X2X12X0")
        self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.measured(), [])
        dev = FakeDevice(avd="Television_4K", serialno="EMULATOR37X2X12X0")
        self.ensure(dev, "--scenarios", "cold")
        self.assertEqual(dev.cold_runs, 3)

    def test_refresh_and_max_age(self):
        self.ensure(FakeDevice(), "--scenarios", "cold")
        dev = FakeDevice()
        self.perf(dev, "baseline", "refresh", "--scenarios", "cold", "--runs", "3", "--other-app", OTHER_APP,
                  "--out", os.path.join(self.tmp, "r.json"))
        self.assertEqual(dev.cold_runs, 3)
        later = datetime.datetime.now() + datetime.timedelta(days=8)
        with mock.patch.object(baseline, "age_hours",
                               lambda created, now=None: (later - datetime.datetime.fromisoformat(created))
                               .total_seconds() / 3600):
            dev = FakeDevice()
            _, logs, _ = self.ensure(dev, "--scenarios", "cold")
            self.assertEqual(dev.cold_runs, 3)
            self.assertTrue(any("older than 7 days" in line for line in logs))
            dev = FakeDevice()
            self.ensure(dev, "--scenarios", "cold", "--max-age", "30")
            self.assertEqual(dev.cold_runs, 0)

    def test_needs_the_launcher_installed_once(self):
        dev = FakeDevice()
        dev.package = "Unable to find package: com.gathin.tvhome\n"
        with self.assertRaises(SystemExit) as e:
            self.ensure(dev, "--scenarios", "cold")
        self.assertIn("build-and-install.sh", str(e.exception))

    def test_a_debug_build_is_not_cached(self):
        dev = FakeDevice()
        dev.shell = (lambda orig: lambda cmd, timeout=120: (
            "compiled\n" if cmd.startswith("cmd package compile") else
            DUMPSYS_PACKAGE if cmd.startswith("dumpsys package") and dev.installs else orig(cmd, timeout)))(dev.shell)
        with self.assertRaises(SystemExit) as e:
            self.ensure(dev, "--scenarios", "cold")
        self.assertIn("debuggable", str(e.exception))
        self.assertEqual(dev.cold_runs, 0)
        self.assertEqual(baseline.entries_for(self.cache, baseline.launcher_tree("HEAD", root=self.repo)), [])

    def test_home_app_compiled_verify_is_recorded_and_compare_checks_it(self):
        """ART compiles the default Home app with speed-profile, `verify` without a profile (the emulator)."""
        def home_app(dev):
            dev.shell = (lambda orig: lambda cmd, timeout=120: (
                "compiled\n" if cmd.startswith("cmd package compile") else orig(cmd, timeout)))(dev.shell)
            return dev
        main, logs, _ = self.ensure(home_app(FakeDevice()), "--scenarios", "jank")
        self.assertEqual(main["build"]["compiler_filter"], "verify")
        self.assertTrue(any("ART compiled it verify, not speed" in line for line in logs))
        speed = self.run_mine(FakeDevice(), "speed", "--scenarios", "jank")
        with self.assertRaises(SystemExit) as e:
            self.perf(None, "compare", "--against", "main", speed)
        self.assertIn("main's baseline for jank was compiled verify, yours speed", str(e.exception))
        self.assertIn("tools/perf.py baseline refresh --scenarios jank", str(e.exception))
        dev = FakeDevice()
        dev.package = DUMPSYS_PACKAGE.replace("DEBUGGABLE ", "")   # a fresh install as Home: verify
        verify = self.run_mine(dev, "verify", "--scenarios", "jank")
        _, _, table = self.perf(None, "compare", "--against", "main", verify)
        self.assertIn("| Metric (median) | main | verify | change |", table)

    def test_cache_problem(self):
        ident = {"id": "d", "state": {"animation_scales": {"window": "1.0"}}}
        ok = {"build": {"debuggable": False, "compiler_filter": "speed", "apk_sha256": "aa"},
              "device": {"id": "d", "state": ident["state"]}}
        self.assertIsNone(baseline.cache_problem(ok, ident, "aa", "t"))
        self.assertIsNone(baseline.cache_problem(dict(ok, build=dict(ok["build"], compiler_filter="verify")),
                                                 ident, "aa", "t"))
        self.assertIn("debuggable", baseline.cache_problem(dict(ok, build=dict(ok["build"], debuggable=True)),
                                                           ident, "aa", "t"))
        self.assertIn("not main's", baseline.cache_problem(ok, ident, "bb", "t"))
        stamped = json.loads(json.dumps(ok))
        stamped["build"]["provenance"] = {"launcher_tree": "u" * 40}
        self.assertIn("built from launcher/", baseline.cache_problem(stamped, ident, "aa", "t" * 40))
        custom = dict(ok, config={"launcher": {"default_config": False}})
        self.assertIn("default configuration", baseline.cache_problem(custom, ident, "aa", "t"))
        scaled = dict(ok, config={"launcher": {"default_config": True}, "animation_scales": {"window": 0.5}})
        self.assertIn("animation scales", baseline.cache_problem(scaled, ident, "aa", "t"))

    def test_run_records_what_the_cache_keys_on(self):
        dev = FakeDevice()
        with open(self.run_mine(dev, "mine", "--scenarios", "jank,mem")) as f:
            r = json.load(f)
        self.assertEqual(len(r["device"]["id"]), 16)
        self.assertNotIn("8C00F07E0160CF00F", json.dumps(r))   # the serial number is only kept hashed
        self.assertEqual(r["device"]["state"]["tv_apps"]["count"], 2)   # the launcher itself doesn't count
        self.assertEqual(r["device"]["state"]["animation_scales"], {"window": "1.0", "transition": "1.0",
                                                                    "animator": "1.0"})
        self.assertEqual(r["harness"], {"jank": baseline.harness("jank"), "mem": baseline.harness("mem")})
        self.assertEqual(r["params"]["scenarios"], ["jank", "mem"])
        self.assertEqual(r["params"]["keys"], perf.DEFAULT_KEYS)
        self.assertIsNone(r["params"]["other_app"])
        self.assertEqual(r["build"]["apk_sha256"], hashlib.sha256(b"branch build").hexdigest())


class CompareAgainstTests(Base):
    def test_compares_against_the_cached_baseline_without_a_device(self):
        self.ensure(FakeDevice(), "--scenarios", "cold,warm,jank,dock,mem")
        mine = self.run_mine(FakeDevice(), "mine", "--scenarios", "dock,mem")
        with self.assertRaises(SystemExit) as e:   # mem after dock alone isn't cached: only after all four
            self.perf(None, "compare", "--against", "main", mine)
        self.assertIn("isn't cached for: mem", str(e.exception))
        self.assertIn("tools/perf.py baseline ensure --scenarios dock,mem --runs 3 --rounds 2", str(e.exception))

        self.ensure(FakeDevice(), "--scenarios", "dock,mem")
        _, _, table = self.perf(None, "compare", "--against", "main", mine)
        self.assertIn("| Metric (median) | main | mine | change |", table)
        self.assertIn("| Dock move: top shelf shows the new app | 603.5 ms | 603.5 ms | +0 % |", table)
        self.assertNotIn("Cold start", table)
        self.assertIn("main's baseline from the cache: origin/main %s" % self.main1[:7], table)
        self.assertIn("dock, mem measured 0 min ago", table)
        self.assertNotIn("Different background apps", table)

    def test_explains_a_parameter_mismatch(self):
        self.ensure(FakeDevice(), "--scenarios", "cold")
        mine = self.run_mine(FakeDevice(), "mine", "--scenarios", "cold", "--runs", "5")
        with self.assertRaises(SystemExit) as e:
            self.perf(None, "compare", "--against", "main", mine)
        self.assertIn("runs 3 (yours 5)", str(e.exception))
        self.assertIn("--scenarios cold --runs 5", str(e.exception))

    def test_refuses_a_debug_build_and_an_old_result(self):
        self.ensure(FakeDevice(), "--scenarios", "jank")
        dev = FakeDevice()
        dev.package = DUMPSYS_PACKAGE
        mine = self.run_mine(dev, "debug", "--scenarios", "jank")
        with self.assertRaises(SystemExit) as e:
            self.perf(None, "compare", "--against", "main", mine)
        self.assertIn("debug build", str(e.exception))
        with open(mine) as f:
            old = json.load(f)
        old["build"] = {"debuggable": False, "compiler_filter": "speed"}
        del old["device"]["id"]
        with open(mine, "w") as f:
            json.dump(old, f)
        with self.assertRaises(SystemExit) as e:
            self.perf(None, "compare", "--against", "main", mine)
        self.assertIn("no device identity", str(e.exception))

    def test_notes_an_old_baseline(self):
        self.ensure(FakeDevice(), "--scenarios", "jank")
        mine = self.run_mine(FakeDevice(), "mine", "--scenarios", "jank")
        with mock.patch.object(baseline, "age_hours", lambda created, now=None: 50.0):
            _, _, table = self.perf(None, "compare", "--against", "main", mine)
        self.assertIn("jank measured 2 days ago", table)
        self.assertIn("perf.py baseline refresh", table)


class ShowTests(Base):
    def test_show_list_and_apk_work_offline(self):
        _, _, out = self.perf(None, "baseline", "show")
        self.assertIn("not built yet", out)
        self.assertIn("no measurements cached", out)
        _, _, path = self.perf(None, "baseline", "apk")
        self.assertTrue(path.strip().endswith(baseline.launcher_tree("HEAD", root=self.repo) + ".apk"))
        self.perf(None, "baseline", "apk")
        self.assertEqual(fake_build.count, 1)
        self.ensure(FakeDevice(), "--scenarios", "cold,dock")
        self.ensure(FakeDevice(avd="tvperf"), "--scenarios", "jank")
        _, _, out = self.perf(None, "baseline", "show")
        self.assertIn("TCL Smart TV Pro, device", out)
        self.assertIn("(emulator AVD tvperf)", out)
        self.assertIn("cold  0 min ago at %s: other_app=%s, runs=3, settle_s=1.5" % (self.main1[:7], OTHER_APP), out)
        self.assertIn("jank  0 min ago", out)
        _, _, out = self.perf(None, "baseline", "list")
        self.assertIn("main %s): 3 measurement(s) on 2 device(s)" % self.main1[:7], out)
        self.assertIn("APK cached", out)

    def test_install_puts_main_back(self):
        dev = FakeDevice()
        self.perf(dev, "baseline", "install")
        self.assertEqual(dev.installed, b"APK launcher v1")
        self.assertIn("am start -n " + perf.ACTIVITY, dev.commands)
        self.perf(dev, "baseline", "install")
        self.assertEqual(dev.installs, 1)

    def test_main_means_origin_main(self):
        commit(self.repo, "launcher/app.txt", "launcher v2", "ahead")
        git(self.repo, "update-ref", "refs/remotes/origin/main", self.main1)   # origin/main is behind HEAD
        self.assertEqual(baseline.resolve_ref("main")[1], self.main1)
        with self.assertRaises(baseline.BaselineError):
            baseline.resolve_ref("no-such-ref")


if __name__ == "__main__":
    unittest.main()
