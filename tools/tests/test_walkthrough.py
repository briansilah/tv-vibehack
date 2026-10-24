"""Offline tests for tools/walkthrough.py: checking plans, the PR comment round trip, and runs against a fake device.

    python3 -m unittest discover -s tools/tests -v
"""
import base64
import contextlib
import copy
import io
import json
import os
import sys
import re
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import tv  # noqa: E402
import walkthrough as wt  # noqa: E402
from test_perf import DUMPSYS_PACKAGE, QUERY_ACTIVITIES  # noqa: E402

PLAN = {
    "version": 1, "title": "Home Settings on an app", "pr": 34,
    "steps": [
        {"launcher": True},
        {"to_first_dock_app": True},
        {"keys": "DOWN RIGHT", "interval": 0.2},
        {"longpress": "CENTER"},
        {"wait": 1},
        {"screenshot": "actions", "expect": "The app's actions, Move first"},
        {"keys": "BACK"},
        {"activity": "SettingsActivity", "package": "com.netflix.ninja"},
        {"expect_top": "SettingsActivity"},
        {"record": "dock-moves", "seconds": 3, "expect": "The Top Shelf follows each Dock app",
         "steps": [{"keys": "BACK"}, {"keys": "RIGHT LEFT"}]},
        {"cold_restart": True},
    ],
    "perf": {"scenarios": "dock,mem"},
}

PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="app_order">a|b|c</string>
    <int name="liquid_glass" value="70" />
</map>
"""


class FakeDevice:
    """Answers walkthrough.py's shell commands, keeps the files it would write, and records every command."""

    def __init__(self, emulator=True, debuggable=True, prefs=PREFS, top="com.gathin.tvhome/.SettingsActivity"):
        self.commands = []
        self.files = {}
        self.emulator, self.prefs, self.top = emulator, prefs, top
        self.package = DUMPSYS_PACKAGE if debuggable else DUMPSYS_PACKAGE.replace("DEBUGGABLE ", "")
        self.debuggable = debuggable
        self.running = True
        self.on_keys = None          # a hook: what pressing keys does to the saved settings
        self.key_commands = []
        self.restores = 0

    def describe(self):
        return "fake"

    def shell(self, cmd, timeout=120):
        self.commands.append(cmd)
        if cmd.startswith("getprop ro.kernel.qemu") or cmd.startswith("getprop ro.boot.qemu"):
            return "1\n" if self.emulator else "\n"
        if cmd.startswith("getprop"):
            return "x\n"
        if cmd == "cat /proc/meminfo":
            return "MemTotal:        2335948 kB\n"
        if cmd.startswith("dumpsys package"):
            return self.package
        if cmd.startswith("run-as"):
            if not self.debuggable:
                return "run-as: package not debuggable: com.gathin.tvhome\n"
            write = re.search(r"echo (\S+) \| base64 -d", cmd)
            if write:
                self.prefs = base64.b64decode(write.group(1)).decode()
                self.restores += 1
                return ""
            if " rm -f " in cmd:
                self.prefs = None
                self.restores += 1
                return ""
            return self.prefs if self.prefs is not None else "cat: shared_prefs/home.xml: No such file or directory\n"
        if cmd.startswith("screencap -p "):
            self.files[cmd.split()[-1]] = b"\x89PNG..."
            return ""
        if cmd.startswith("screenrecord"):
            self.files[cmd.split()[3]] = b"mp4"
            return ""
        if cmd.startswith("for k in"):
            self.key_commands.append(cmd)
            if self.on_keys:
                self.on_keys(self, cmd)
        if cmd.startswith("dumpsys activity activities"):
            return "  topResumedActivity=ActivityRecord{1 u0 %s t2}\n" % self.top
        if cmd.startswith("cmd package query-activities"):
            return QUERY_ACTIVITIES
        if cmd.startswith("am kill"):
            self.running = False
            return ""
        if cmd.startswith("pidof"):
            return "4400\n" if "screenrecord" not in cmd and self.running else ""
        if cmd.startswith("am start"):
            if "com.gathin.tvhome/" in cmd:
                self.running = True
            return "Status: ok\nComplete\n"
        if cmd.startswith(("am force-stop", "pm uninstall", "reboot", "pm disable")):
            raise AssertionError("never: " + cmd)
        return ""

    def pull(self, remote, local):
        with open(local, "wb") as f:
            f.write(self.files.pop(remote))

    def close(self):
        pass


def run(plan, device, allow_tv=False):
    out = tempfile.mkdtemp()
    runner = wt.Runner(device, wt.check(copy.deepcopy(plan)), out, allow_tv=allow_tv, log=lambda m: None,
                       sleep=lambda s: None)
    return runner.run(), out


class CheckTest(unittest.TestCase):
    def test_a_good_plan_has_no_problems(self):
        self.assertEqual(wt.problems(PLAN), [])

    def assertProblem(self, plan, words):
        found = " | ".join(wt.problems(plan))
        self.assertIn(words, found)

    def with_step(self, step):
        plan = copy.deepcopy(PLAN)
        plan["steps"].append(step)
        return plan

    def test_refuses_what_would_break_a_shared_tv(self):
        self.assertProblem(self.with_step({"keys": "HOME"}), "not HOME")
        self.assertProblem(self.with_step({"keys": "POWER"}), "not POWER")
        self.assertProblem(self.with_step({"shell": "am force-stop com.gathin.tvhome"}), "exactly one of")
        self.assertProblem(self.with_step({"launcher": True, "liquid_glass": 50}), "doesn't take liquid_glass")
        self.assertProblem(self.with_step({"activity": "com.android.tv.settings/.MainSettings"}), "launcher's own")
        plan = copy.deepcopy(PLAN)
        plan["perf"] = {"variant": "--ei liquid_glass 50"}
        self.assertProblem(plan, "glass_test_art")

    def test_pictures_need_a_name_and_what_they_should_show(self):
        self.assertProblem(self.with_step({"screenshot": "after"}), '"expect"')
        self.assertProblem(self.with_step({"screenshot": "Bad Name", "expect": "x"}), "lower case")
        self.assertProblem(self.with_step({"screenshot": "actions", "expect": "x"}), "used twice")

    def test_limits(self):
        self.assertProblem(self.with_step({"wait": 120}), "up to 30")
        self.assertProblem(self.with_step({"record": "long", "seconds": 90, "expect": "x"}), "1 to 30")
        self.assertProblem(self.with_step({"longpress": "CENTER DOWN"}), "one key")
        self.assertProblem(self.with_step({"record": "outer", "seconds": 5, "expect": "x", "steps": [
            {"record": "inner", "seconds": 2, "expect": "y"}]}), "not inside another record")

    def test_plan_fields(self):
        self.assertProblem({"title": "t", "steps": []}, '"version": 1')
        self.assertProblem({"version": 1, "title": "t", "steps": [], "shell": "x"}, "unknown fields: shell")
        plan = copy.deepcopy(PLAN)
        plan["perf"] = {"scenarios": "cold,fast"}
        self.assertProblem(plan, '"perf.scenarios"')


def proven(plan):
    """The manifest of a passing run on an emulator."""
    return run(plan, FakeDevice())[0]


class CommentTest(unittest.TestCase):
    def test_round_trip_through_a_pr_comment(self):
        plan = {k: v for k, v in PLAN.items() if k != "pr"}     # written before the pull request existed
        body = wt.comment_body(plan, proven(plan), pr=34)
        self.assertTrue(body.startswith(wt.MARKER))
        self.assertIn("Dry run: all 11 steps passed on fake (emulator", body)
        comments = [{"body": "LGTM", "author_association": "OWNER"},
                    {"body": body, "author_association": "OWNER"}]
        self.assertEqual(wt.plan_from_comments(comments, 34), dict(plan, pr=34))

    def test_only_a_passing_emulator_run_of_the_same_plan(self):
        manifest = proven(PLAN)
        with self.assertRaisesRegex(wt.PlanError, "another plan"):
            wt.comment_body(dict(PLAN, title="changed since"), manifest)
        with self.assertRaisesRegex(wt.PlanError, "passing run on an emulator"):
            wt.comment_body(PLAN, dict(manifest, ok=False))
        with self.assertRaisesRegex(wt.PlanError, "passing run on an emulator"):
            wt.comment_body(PLAN, dict(manifest, emulator=False))

    def test_the_newest_trusted_comment_for_this_pr_wins(self):
        manifest = proven(PLAN)
        older = wt.comment_body(PLAN, manifest).replace(PLAN["title"], "older")
        stranger = wt.comment_body(PLAN, manifest).replace(PLAN["title"], "from a stranger")
        comments = [{"body": older, "author_association": "OWNER"},
                    {"body": wt.comment_body(PLAN, manifest), "author_association": "COLLABORATOR"},
                    {"body": stranger, "author_association": "NONE"}]
        self.assertEqual(wt.plan_from_comments(comments, 34)["title"], PLAN["title"])
        with self.assertRaisesRegex(wt.PlanError, "for pull request 34, not 35"):
            wt.plan_from_comments(comments, 35)

    def test_origin_repo(self):
        for url in ("https://github.com/briansilah/tv-vibehack.git", "https://github.com/briansilah/tv-vibehack",
                    "git@github.com:briansilah/tv-vibehack.git", "ssh://git@github.com/briansilah/tv-vibehack.git"):
            self.assertEqual(wt.origin_repo(url + "\n"), "briansilah/tv-vibehack", url)
        self.assertIsNone(wt.origin_repo("https://gitlab.com/a/b.git"))

    def test_fetch_reads_origins_repository(self):
        with mock.patch.object(wt.subprocess, "run") as run_:
            run_.side_effect = [mock.Mock(stdout="https://github.com/briansilah/tv-vibehack.git\n"),
                                mock.Mock(returncode=0, stdout="", stderr="")]
            with self.assertRaisesRegex(wt.PlanError, "no walkthrough comment"):
                wt.fetch(34)
        self.assertEqual(run_.call_args_list[1][0][0][:3],
                         ["gh", "api", "repos/briansilah/tv-vibehack/issues/34/comments"])

    def test_no_plan(self):
        with self.assertRaises(wt.PlanError):
            wt.plan_from_comments([{"body": "nothing here", "author_association": "OWNER"}])


class RunTest(unittest.TestCase):
    def assertCleanedUp(self, dev):
        """Home on screen, then nothing of ours left on the device."""
        rm = dev.commands.index("rm -rf " + wt.DEVICE_DIR)
        self.assertTrue(dev.commands[rm - 1].startswith("am start -W -n com.gathin.tvhome/.MainActivity"))
        self.assertEqual(dev.files, {})

    def test_runs_every_step_and_keeps_the_pictures(self):
        dev = FakeDevice()
        manifest, out = run(PLAN, dev)
        self.assertTrue(manifest["ok"], manifest)
        self.assertEqual([s["ok"] for s in manifest["steps"]], [True] * 11)
        files = sorted(os.listdir(out))
        self.assertEqual(files, ["01-actions.png", "02-dock-moves.mp4", "manifest.json"])
        with open(os.path.join(out, "manifest.json")) as f:
            self.assertEqual(json.load(f)["settings"], {"checked": True, "changed": []})
        self.assertIn("input keyevent --longpress 23", dev.commands)
        self.assertIn("am start -W -n com.gathin.tvhome/.SettingsActivity --es com.gathin.tvhome.PACKAGE "
                      "com.netflix.ninja", dev.commands)
        self.assertTrue(any(c.startswith("am kill com.gathin.tvhome") for c in dev.commands))
        self.assertCleanedUp(dev)

    def test_refuses_a_device_that_isnt_an_emulator(self):
        dev = FakeDevice(emulator=False)
        with self.assertRaisesRegex(wt.PlanError, "--tv"):
            run(PLAN, dev)
        self.assertFalse(any(c.startswith(("am ", "input", "screencap")) for c in dev.commands))

    def test_on_the_tv_with_tv(self):
        dev = FakeDevice(emulator=False, debuggable=False)
        out = tempfile.mkdtemp()
        manifest = wt.Runner(dev, wt.check(copy.deepcopy(PLAN)), out, allow_tv=True,
                             other_app="com.google.android.youtube.tv", log=lambda m: None,
                             sleep=lambda s: None).run()
        self.assertTrue(manifest["ok"])
        self.assertFalse(manifest["settings"]["checked"])
        self.assertIn("am start -W -n com.google.android.youtube.tv/"
                      "com.google.android.apps.youtube.tv.activity.ShellActivity", dev.commands)

    def test_fails_when_the_plan_leaves_settings_changed_and_puts_them_back(self):
        dev = FakeDevice()

        def save_order(d, cmd):
            d.prefs = PREFS.replace("a|b|c", "b|a|c")
        dev.on_keys = save_order
        manifest, _ = run(PLAN, dev)
        self.assertFalse(manifest["ok"])
        self.assertEqual(manifest["settings"], {"checked": True, "changed": ["app_order"], "restored": True})
        self.assertEqual(dev.prefs, PREFS)

    def test_puts_settings_back_when_a_step_fails_before_the_plan_undoes_them(self):
        dev = FakeDevice(prefs=None, top="com.google.android.tvlauncher/.MainActivity")   # nothing saved yet

        def save_order(d, cmd):
            d.prefs = PREFS
        dev.on_keys = save_order
        manifest, _ = run(PLAN, dev)
        self.assertFalse(manifest["steps"][-1]["ok"])
        self.assertTrue(manifest["settings"]["restored"])
        self.assertIsNone(dev.prefs)        # removed again, as before the run
        kill = max(i for i, c in enumerate(dev.commands) if c.startswith("am kill"))
        rm = dev.commands.index("run-as com.gathin.tvhome rm -f " + wt.PREFS)
        self.assertLess(kill, rm)           # the launcher's process ends first, or it would write them again

    def test_never_presses_keys_again_after_a_dropped_connection(self):
        dev = FakeDevice(emulator=False, debuggable=False)
        dev.serial, dev.exe = "192.168.8.23:5555", "adb"
        dev.attached = lambda: True
        real = dev.shell

        def flaky(cmd, timeout=120):
            if cmd.startswith("for k in") and not dev.key_commands:
                real(cmd)                   # some keys may have arrived
                raise tv.TvError("lost the connection to 192.168.8.23:5555")
            return real(cmd, timeout)
        dev.shell = flaky
        with mock.patch.object(tv, "run_adb", return_value=(0, "connected")) as connect:
            manifest, _ = run(PLAN, dev, allow_tv=True)
        connect.assert_called_with(["adb", "connect", "192.168.8.23:5555"], 30)
        self.assertEqual(len(dev.key_commands), 1)
        self.assertFalse(manifest["ok"])
        self.assertIn("lost the connection", manifest["steps"][-1]["error"])
        self.assertCleanedUp(dev)

    def test_stops_at_the_first_failed_step_and_still_cleans_up(self):
        dev = FakeDevice(top="com.google.android.tvlauncher/.MainActivity")
        manifest, _ = run(PLAN, dev)
        self.assertFalse(manifest["ok"])
        self.assertEqual(len(manifest["steps"]), 9)
        self.assertIn("not SettingsActivity", manifest["steps"][-1]["error"])
        self.assertCleanedUp(dev)

    def test_undoes_what_it_turned_on(self):
        plan = copy.deepcopy(PLAN)
        plan["default_config"] = True
        plan["steps"] = [{"launcher": True, "glass_test_art": True},
                         {"screenshot": "art", "expect": "stand-in art behind the Dock"}]
        dev = FakeDevice()
        manifest, _ = run(plan, dev)
        self.assertTrue(manifest["ok"])
        starts = [c for c in dev.commands if c.startswith("am start")]
        self.assertIn("--ez default_config true --ez glass_test_art true", starts[1])
        self.assertTrue(starts[-1].endswith("--ez glass_test_art false --ez default_config false"), starts[-1])
        self.assertNotIn("default_config true", starts[-1])


class CliTest(unittest.TestCase):
    def test_check_and_run(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "plan.json")
            with open(path, "w") as f:
                json.dump(PLAN, f)
            with contextlib.redirect_stdout(io.StringIO()) as printed, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(wt.main(["check", path]), 0)
                self.assertEqual(wt.main(["run", path, "--out", os.path.join(d, "out")], shell=FakeDevice(),
                                         sleep=lambda s: None), 0)
            self.assertIn("4. hold CENTER", printed.getvalue())
            self.assertIn("passed: 11 of 11 steps passed", printed.getvalue())
            bad = os.path.join(d, "bad.json")
            with open(bad, "w") as f:
                json.dump({"version": 1, "title": "t", "steps": [{"keys": "HOME"}]}, f)
            with self.assertRaisesRegex(SystemExit, "not HOME"):
                wt.main(["check", bad])
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                wt.main(["comment", path])        # only with a passing run's manifest


if __name__ == "__main__":
    unittest.main()
