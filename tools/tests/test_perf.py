"""Offline tests for tools/perf.py: parsers against real TV output, and a full run against a fake TV.

    python3 -m unittest discover -s tools/tests -v
"""
import json
import os
import re
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
import perf  # noqa: E402
import provenance  # noqa: E402
from test_provenance import HAS_GIT, Repo  # noqa: E402


def fixture(name):
    with open(os.path.join(HERE, "fixtures", name)) as f:
        return f.read()


AM_START_COLD = """Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }
Status: ok
LaunchState: COLD
Activity: com.gathin.tvhome/.MainActivity
TotalTime: 1234
WaitTime: 1251
Complete
"""

AM_START_HOT_ANDROID9 = """Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.HOME] }
Status: ok
Activity: com.gathin.tvhome/.MainActivity
ThisTime: 402
TotalTime: 402
WaitTime: 431
Complete
"""

QUERY_ACTIVITIES = """3 activities found:
  Activity #0:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
    com.netflix.ninja/.MainActivity
  Activity #1:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
    com.gathin.tvhome/.MainActivity
  Activity #2:
    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
    com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
"""

# Android 10+ when the activity was still visible: no launch is timed and TotalTime is left out.
AM_START_NOT_TIMED = """Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }
Status: ok
LaunchState: UNKNOWN (0)
Activity: com.gathin.tvhome/.MainActivity
WaitTime: 35
Complete
"""

DUMPSYS_PACKAGE = """Activity Resolver Table:
  Non-Data Actions:
      android.intent.action.MAIN:
        3c1d2e8 com.gathin.tvhome/.MainActivity filter 9a7f5c1
Packages:
  Package [com.gathin.tvhome] (5b3a0f2):
    userId=10123
    pkg=Package{8e0c6d1 com.gathin.tvhome}
    versionCode=2 minSdk=26 targetSdk=29
    versionName=2.0
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION ]
    timeStamp=2026-10-19 16:37:10
    firstInstallTime=2026-10-12 21:04:55
    lastUpdateTime=2026-10-19 16:37:12

Dexopt state:
  [com.gathin.tvhome]
    path: /data/app/~~a1==/com.gathin.tvhome-b2==/base.apk
      arm: [status=verify] [reason=install] [primary-abi]
"""

LOGCAT = """--------- beginning of main
"""
COMMIT, TREE = "3fac1fb61c1e53c7db0c9adea4c6fee546e41b90", "e9fb9311e5b786a1670d47e8a7782c932cd82bcd"
STAMPED = "2.0+commit-%s.launcher-%s" % (COMMIT, TREE)
LOGCAT = LOGCAT + """  1760800000.100  4321  4321 D TvHomePerf: resume ms=999.0
  1760800010.050  1001  1120 I ActivityTaskManager: START u0 {cmp=com.gathin.tvhome/.MainActivity} from uid 2000
  1760800010.480  4400  4400 D TvHomePerf: create ms=88.4
  1760800010.910  4400  4400 D TvHomePerf: reload ms=402.7 apps=37 query_ms=31.2 load_ms=355.0
  1760800010.950  4400  4400 D TvHomePerf: resume ms=430.2
  1760800011.120  1001  1140 I ActivityTaskManager: Displayed com.gathin.tvhome/.MainActivity for user 0: +1s70ms
  1760800011.300  4400  4400 D TvHomePerf: focus_restored ms=612.5 first=1
  1760800011.310  1001  1140 I ActivityTaskManager: Fully drawn com.gathin.tvhome/.MainActivity: +1s250ms
  1760800012.000  4400  4400 D TvHomePerf: ambient ms=3.1 pkg=com.netflix.ninja
  1760800013.000  4400  4400 D TvHomePerf: focus_restored ms=50.0 first=0
"""


class ParserTests(unittest.TestCase):
    def test_am_start_cold(self):
        r = perf.parse_am_start(AM_START_COLD)
        self.assertEqual((r["total_ms"], r["wait_ms"], r["launch_state"], r["status"]), (1234, 1251, "COLD", "ok"))
        self.assertEqual(r["activity"], "com.gathin.tvhome/.MainActivity")
        self.assertIsNone(r["warning"])

    def test_am_start_android9_has_no_launch_state(self):
        r = perf.parse_am_start(AM_START_HOT_ANDROID9)
        self.assertEqual((r["total_ms"], r["wait_ms"], r["launch_state"]), (402, 431, None))

    def test_am_start_warning(self):
        r = perf.parse_am_start("Warning: Activity not started, intent has been delivered to currently running "
                                "top-most instance.\nStatus: ok\nLaunchState: UNKNOWN (0)\nTotalTime: 0\n")
        self.assertTrue(r["warning"].startswith("Warning: Activity not started"))
        self.assertEqual(r["launch_state"], "UNKNOWN")

    def test_gfxinfo_real_android14(self):
        g = perf.parse_gfxinfo(fixture("gfxinfo_android14.txt"))
        self.assertEqual(g["total_frames"], 10516)
        self.assertEqual(g["janky_frames"], 137)      # not the "(legacy)" line
        self.assertAlmostEqual(g["janky_pct"], 1.30)
        self.assertEqual((g["p50_ms"], g["p90_ms"], g["p95_ms"], g["p99_ms"]), (5, 19, 21, 34))
        self.assertEqual((g["gpu_p50_ms"], g["gpu_p99_ms"]), (1, 7))
        self.assertEqual(g["slow_ui_thread"], 93)
        self.assertEqual(g["high_input_latency"], 6910)
        self.assertEqual(g["missed_vsync"], 48)
        self.assertEqual(g["deadline_missed"], 137)   # not the "(legacy)" line

    def test_gfxinfo_no_frames(self):
        g = perf.parse_gfxinfo("Total frames rendered: 0\nJanky frames: 0 (nan%)\n")
        self.assertEqual(g["total_frames"], 0)
        self.assertIsNone(g["janky_pct"])

    def test_meminfo_real_android14(self):
        m = perf.parse_meminfo(fixture("meminfo_android14.txt"))
        self.assertEqual(m["total_pss_kb"], 132630)
        self.assertEqual(m["total_rss_kb"], 203672)
        self.assertEqual(m["java_heap_kb"], 20232)
        self.assertEqual(m["native_heap_kb"], 38232)
        self.assertEqual(m["graphics_kb"], 15896)
        self.assertEqual(m["stack_kb"], 888)          # the summary row, not the "Stack" table row
        self.assertEqual((m["views"], m["activities"]), (441, 2))

    def test_gfxinfo_picks_the_launcher_process(self):
        service = ("** Graphics info for pid 4500 [com.gathin.tvhome:home] **\n\nTotal frames rendered: 0\n"
                   "Janky frames: 0 (nan%)\n\n")
        main = fixture("gfxinfo_android14.txt").replace("1390 [com.google.android.apps.tv.launcherx]",
                                                        "4400 [com.gathin.tvhome]")
        g = perf.parse_gfxinfo(service + main[main.index("** Graphics info"):])
        self.assertEqual((g["total_frames"], g["janky_frames"]), (10516, 137))

    def test_meminfo_picks_the_launcher_process(self):
        service = ("** MEMINFO in pid 4500 [com.gathin.tvhome:home] **\n App Summary\n"
                   "           Java Heap:     1000\n           TOTAL PSS:    9000\n Objects\n"
                   "               Views:        0\n")
        main = fixture("meminfo_android14.txt").replace("1390 [com.google.android.apps.tv.launcherx]",
                                                        "4400 [com.gathin.tvhome]")
        m = perf.parse_meminfo(service + main[main.index("** MEMINFO"):])
        self.assertEqual((m["total_pss_kb"], m["java_heap_kb"], m["views"]), (132630, 20232, 441))

    def test_meminfo_android9_summary(self):
        m = perf.parse_meminfo(" App Summary\n                       Pss(KB)\n                        ------\n"
                               "           Java Heap:     5464\n         Native Heap:     9136\n"
                               "            Graphics:     4316\n\n               TOTAL:    31332       "
                               "TOTAL SWAP PSS:      200\n")
        self.assertEqual((m["total_pss_kb"], m["java_heap_kb"], m["graphics_kb"]), (31332, 5464, 4316))
        self.assertIsNone(m["total_rss_kb"])

    def test_package(self):
        p = perf.parse_package(DUMPSYS_PACKAGE)
        self.assertEqual(p, {"version_name": "2.0", "provenance": None, "installed": "2026-10-19 16:37:12",
                             "debuggable": True, "compiler_filter": "verify", "compile_reason": "install"})
        self.assertIsNone(perf.parse_package("Unable to find package: com.gathin.tvhome\n"))
        release = DUMPSYS_PACKAGE.replace("DEBUGGABLE ", "").replace("status=verify", "status=speed")
        self.assertEqual(perf.parse_package(release)["debuggable"], False)
        self.assertEqual(perf.parse_package(release)["compiler_filter"], "speed")

    def test_package_provenance_stamp(self):
        p = perf.parse_package(DUMPSYS_PACKAGE.replace("versionName=2.0", "versionName=" + STAMPED))
        self.assertEqual(p["version_name"], STAMPED)
        self.assertEqual(p["provenance"], {"commit": COMMIT, "launcher_tree": TREE, "dirty": False})

    def test_logcat_leaves_config_lines_out(self):
        r = perf.parse_logcat(LOGCAT + "  1760800014.000  4400  4400 D TvHomePerf: config ms=0.0 default_config=1 "
                                       "glass=0 glass_default=0 glass_saved=70 glass_tier=lens test_art=0\n")
        self.assertNotIn("config_ms", r)
        self.assertNotIn("glass", r)

    def test_logcat_since_filters_older_runs(self):
        r = perf.parse_logcat(LOGCAT, since=1760800010.0)
        self.assertEqual(r["resume_ms"], 430.2)        # not the 999.0 from before `since`
        self.assertEqual(r["create_ms"], 88.4)
        self.assertEqual(r["reload_ms"], 402.7)
        self.assertEqual(r["reload_query_ms"], 31.2)
        self.assertEqual(r["reload_load_ms"], 355.0)
        self.assertEqual(r["apps"], 37)
        self.assertEqual(r["focus_restored_ms"], 612.5)  # first occurrence wins
        self.assertEqual(r["displayed_ms"], 1070)
        self.assertEqual(r["fully_drawn_ms"], 1250)
        self.assertEqual(r["pkg"], "com.netflix.ninja")

    def test_events_returns_every_occurrence(self):
        out = LOGCAT + ("  1760800020.000  4400  4400 D TvHomePerf: shelf ms=601.5 text_ms=601.5 glow_ms=500.2 "
                        "kind=swap pkg=com.amazon.amazonvideo.livingroom/.Main\n"
                        "  1760800021.200  4400  4400 D TvHomePerf: shelf ms=598.0 text_ms=598.0 glow_ms=499.0 "
                        "kind=swap pkg=com.netflix.ninja/.MainActivity\n")
        e = perf.parse_events(out, "shelf", since=1760800010.0)
        self.assertEqual([x["shelf_ms"] for x in e], [601.5, 598.0])
        self.assertEqual(e[0]["shelf_text_ms"], 601.5)
        self.assertEqual(e[0]["shelf_glow_ms"], 500.2)
        self.assertEqual(e[1]["kind"], "swap")
        self.assertEqual(e[1]["pkg"], "com.netflix.ninja/.MainActivity")
        self.assertEqual(perf.parse_events(out, "shelf", since=1760800021.0)[0]["shelf_ms"], 598.0)
        self.assertEqual(perf.parse_events(out, "nothing"), [])

    def test_to_first_dock_app_keys(self):
        keys = perf.TO_FIRST_DOCK_APP.split()
        # Back, not Down, from the top bar: Down stops at the top shelf's button when it shows one.
        self.assertEqual(keys.count("BACK"), 1)
        self.assertNotIn("DOWN", keys)
        self.assertLess(keys.index("UP"), keys.index("BACK"))
        self.assertGreaterEqual(keys.count("LEFT"), 3)   # four dock apps

    def test_components(self):
        self.assertEqual(perf.parse_components(QUERY_ACTIVITIES), [
            "com.netflix.ninja/.MainActivity", "com.gathin.tvhome/.MainActivity",
            "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"])

    def test_am_start_not_timed(self):
        r = perf.parse_am_start(AM_START_NOT_TIMED)
        self.assertEqual((r["total_ms"], r["launch_state"]), (None, "UNKNOWN"))

    def test_summarize(self):
        s = perf.summarize([{"a": 10, "b": True, "c": "x"}, {"a": 30}, {"a": 20}, {"a": None}])
        self.assertEqual(s, {"a": {"median": 20, "p90": 30, "min": 10, "max": 30, "n": 3}})

    def test_key_loop(self):
        self.assertEqual(perf.key_loop("RIGHT left Down", 0.35),
                         "for k in 22 21 20; do input keyevent $k; sleep 0.35; done")
        self.assertEqual(len(perf.DEFAULT_KEYS.split()), 16)

    def test_summary_queries_name_the_launcher(self):
        for sql in (perf.SUMMARY_SQL % (perf.PKG, 25), perf.ASYNC_SQL % perf.PKG, perf.FRAMES_SQL % perf.PKG):
            self.assertIn("'com.gathin.tvhome'", sql)
            self.assertNotIn("%", sql.replace("like 'tvhome:%'", ""))
        self.assertIn("p.name = 'com.gathin.tvhome' and", perf.INPUT_SQL % perf.PKG)
        self.assertIn("'com.gathin.tvhome:home'", perf.SERVICE_KEYS_SQL % perf.PKG)

    def test_key_latencies(self):
        offset = 24_000_000_000_000   # BOOTTIME - MONOTONIC
        rows = [
            ("deliverInputEvent src=0x301 eventTimeNano=8872868000000 id=0xc121cb96", 8872868000000 + offset + 2_900_000,
             210_000),
            ("deliverInputEvent src=0x301 eventTimeNano=8872900000000 id=0x1", 8872900000000 + offset + 6_700_000, 250_000),
            ("deliverInputEvent src=0x0 eventTimeNano=8873000000000 id=0x2", 8873000000000 + offset + 11_000_000, 480_000),
            ("deliverInputEvent", 1, 1),   # Android 11 and older: no event time
        ]
        keys = perf.key_latencies(rows, offset)
        self.assertEqual([k[0] for k in keys], ["0x301", "0x301", "0x0"])
        self.assertAlmostEqual(keys[0][1], 2.9)
        self.assertAlmostEqual(keys[0][2], 0.21)
        self.assertEqual(perf.key_table(keys), ["| 0x0 | 1 | 11.0 | 11.0 | 0.48 |",
                                                "| 0x301 | 2 | 4.8 | 6.7 | 0.23 |"])

    def test_perfetto_config_is_shell_safe(self):
        cfg = perf.perfetto_config(12000)
        self.assertNotIn("'", cfg)                       # it is wrapped in single quotes on the device
        self.assertIn('atrace_apps: "com.gathin.tvhome"', cfg)
        self.assertIn("duration_ms: 12000", cfg)
        self.assertEqual(cfg.count("{"), cfg.count("}"))


class FakeTv:
    """Answers the shell commands perf.py sends with canned output and records them."""

    def __init__(self, debuggable=True, version="2.0", reports_config=True, scales=None, compiler="verify"):
        self.commands = []
        self.clock = 1760800000
        self.package = DUMPSYS_PACKAGE if debuggable else DUMPSYS_PACKAGE.replace("DEBUGGABLE ", "")
        self.package = self.package.replace("versionName=2.0", "versionName=" + version).replace(
            "status=verify", "status=" + compiler)
        self.cold_runs = 0
        self.stopped = False
        self.launcher_visible = False
        self.survives_kill = False
        self.reports_config = reports_config   # a build from before default_config doesn't
        self.default_config = False
        self.glass, self.test_art = 70, True     # the owner's saved choice; stand-in art left on
        self.scales = scales or {}

    def shell(self, cmd, timeout=120):
        self.commands.append(cmd)
        if self.reports_config and cmd.startswith("am start") and perf.DEFAULT_CONFIG in cmd:
            on = perf.DEFAULT_CONFIG + " true" in cmd
            art = re.search(r"--ez glass_test_art (true|false)", cmd)
            if on != self.default_config and not art:
                self.test_art = False
            self.default_config = on
            if art:
                self.test_art = art.group(1) == "true"
            glass = re.search(r"--ei liquid_glass (\d+)", cmd)
            self.glass = int(glass.group(1)) if glass else 0 if on else 70
        if cmd.startswith("settings get global "):
            return self.scales.get(cmd.split()[-1], "null") + "\n"
        if cmd.startswith("dumpsys package"):
            return self.package
        if cmd.startswith("cmd package query-activities"):
            return QUERY_ACTIVITIES
        if cmd.startswith("getprop"):
            return {"ro.product.manufacturer": "TCL", "ro.product.model": "Smart TV Pro",
                    "ro.build.version.release": "14", "ro.build.version.sdk": "34"}.get(cmd.split()[1], "") + "\n"
        if cmd == "cat /proc/meminfo":
            return "MemTotal:        2335948 kB\n"
        if cmd.startswith("pm path"):
            return "package:/data/app/~~a1==/com.gathin.tvhome-b2==/base.apk\n"
        if cmd.startswith("stat -c %s"):
            return "1660541\n"
        if cmd == "date +%s":
            self.clock += 10
            return "%d\n" % self.clock
        if cmd.startswith("am force-stop"):
            raise AssertionError("force-stop turns the launcher's accessibility service off")
        if cmd.startswith("am kill"):
            self.stopped = not self.survives_kill
            return ""
        if cmd.startswith("pidof"):
            return "" if self.stopped else "4400\n"
        if cmd.startswith("am start -W -n " + perf.ACTIVITY) and self.stopped:
            self.stopped = False
            self.cold_runs += 1
            return AM_START_COLD
        if cmd.startswith("am start -W -n " + perf.ACTIVITY) and self.launcher_visible:
            return AM_START_NOT_TIMED
        if cmd.startswith("am start"):
            return AM_START_HOT_ANDROID9
        if cmd.startswith("logcat"):
            t = self.clock + 0.5
            config = ("  %.3f  4400  4400 D TvHomePerf: config ms=0.0 default_config=%d glass=%d glass_default=0 "
                      "glass_saved=70 glass_tier=lens test_art=%d\n"
                      % (t, self.default_config, self.glass, self.test_art)) if self.reports_config else ""
            return (config +
                    "  %.3f  4400  4400 D TvHomePerf: reload ms=400.0 apps=37 query_ms=30.0 load_ms=350.0\n"
                    "  %.3f  4400  4400 D TvHomePerf: resume ms=420.0\n"
                    "  %.3f  4400  4400 D TvHomePerf: focus_restored ms=600.0 first=1\n"
                    "  %.3f  1001  1140 I ActivityTaskManager: Fully drawn com.gathin.tvhome/.MainActivity: +1s250ms\n"
                    "  %.3f  4400  4400 D TvHomePerf: shelf ms=300.0 text_ms=260.0 glow_ms=300.0 kind=show pkg=a/.A\n"
                    % (t, t, t, t, t)
                    + "".join("  %.3f  4400  4400 D TvHomePerf: shelf ms=%.1f text_ms=%.1f glow_ms=500.0 kind=swap "
                              "pkg=b/.B\n" % (t + i, 600.0 + i, 600.0 + i) for i in range(8)))
        if cmd.startswith("dumpsys gfxinfo"):
            return fixture("gfxinfo_android14.txt")
        if cmd.startswith("dumpsys meminfo"):
            return fixture("meminfo_android14.txt")
        return ""

    def pull(self, remote, local):
        raise AssertionError("unexpected pull")

    def close(self):
        pass


class FakeEmulator(FakeTv):
    """An Android 14 emulator: ART compiles the default Home app `verify` whatever `-m speed` asks."""

    def __init__(self, home="com.gathin.tvhome/.MainActivity", stock=True, stock_disabled=False, **kw):
        super().__init__(debuggable=False, **kw)
        self.home = home
        self.stock = stock
        self.stock_disabled = stock_disabled   # as launcher/build-and-install.sh leaves it on an emulator

    def compile_as_home_allows(self):
        filt = "verify" if self.home.startswith(perf.PKG + "/") else "speed"
        self.package = re.sub(r"status=[\w-]+", "status=" + filt, self.package)

    def shell(self, cmd, timeout=120):
        if cmd.startswith("getprop ro.kernel.qemu"):
            self.commands.append(cmd)
            return "1\n"
        if cmd.startswith("cmd role get-role-holders android.app.role.HOME"):
            self.commands.append(cmd)
            return self.home.split("/")[0] + "\n"
        if cmd.startswith("cmd package resolve-activity"):
            self.commands.append(cmd)
            return "priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" \
                   "com.google.android.tvlauncher/.MainActivity\n"   # as on the emulator: not the role holder
        if cmd.startswith("cmd package query-activities") and "category.HOME" in cmd:
            self.commands.append(cmd)
            stock = ("  Activity #1:\n    com.google.android.tvlauncher/.MainActivity\n"
                     if self.stock and not self.stock_disabled else "")
            return ("  Activity #0:\n    com.android.tv.settings/.system.FallbackHome\n" + stock +
                    "  Activity #2:\n    com.gathin.tvhome/.MainActivity\n")
        if cmd == "pm list packages -d":
            self.commands.append(cmd)
            return "package:com.example.other\n" + ("package:com.google.android.tvlauncher\n" if self.stock_disabled else "")
        if cmd.startswith("pm enable "):
            self.commands.append(cmd)
            self.stock_disabled = self.stock_disabled and cmd.split()[-1] != "com.google.android.tvlauncher"
            return "Package com.google.android.tvlauncher new state: enabled\n"
        if cmd.startswith("cmd package set-home-activity"):
            self.commands.append(cmd)
            self.home = cmd.split()[-1]
            return "Success\n"
        if cmd.startswith("cmd package compile"):
            self.commands.append(cmd)
            self.compile_as_home_allows()
            return "compiled\n"
        return super().shell(cmd, timeout)


class EmulatorSpeedTests(unittest.TestCase):
    def test_a_real_tv_is_left_alone(self):
        tv = FakeTv(debuggable=False)   # compiled verify, but not an emulator
        self.assertFalse(perf.emulator_speed(tv, lambda m: None))
        self.assertFalse(any("set-home-activity" in c or "compile" in c for c in tv.commands))

    def test_launcher_as_home_gives_home_back_and_compiles_speed(self):
        emu = FakeEmulator()
        emu.compile_as_home_allows()
        logs = []
        self.assertTrue(perf.emulator_speed(emu, logs.append))
        self.assertEqual(emu.home, "com.google.android.tvlauncher/.MainActivity")   # not Settings' FallbackHome
        self.assertEqual(perf.parse_package(emu.package)["compiler_filter"], "speed")
        self.assertIn("is Home again", logs[0])

    def test_already_speed_does_nothing(self):
        emu = FakeEmulator(home="com.google.android.tvlauncher/.MainActivity")
        emu.compile_as_home_allows()
        self.assertFalse(perf.emulator_speed(emu, lambda m: None))
        self.assertFalse(any(c.startswith("cmd package") for c in emu.commands))

    def test_home_package_falls_back_to_resolve_activity(self):
        tv = FakeTv()   # no `cmd role` answer, as on Android 9
        tv.shell = lambda cmd, timeout=120: ("com.example.home/.Home\n" if "resolve-activity" in cmd
                                             else "Unknown command: role\n")
        self.assertEqual(perf.home_package(tv), "com.example.home")

    def test_a_disabled_stock_launcher_is_enabled_again(self):
        emu = FakeEmulator(stock_disabled=True)
        emu.compile_as_home_allows()
        logs = []
        self.assertTrue(perf.emulator_speed(emu, logs.append))
        self.assertIn("pm enable com.google.android.tvlauncher", emu.commands)
        self.assertNotIn("pm enable com.example.other", emu.commands)   # only launchers
        self.assertEqual(emu.home, "com.google.android.tvlauncher/.MainActivity")
        self.assertEqual(perf.parse_package(emu.package)["compiler_filter"], "speed")
        self.assertIn("enabled com.google.android.tvlauncher again", logs[0])

    def test_via_home_keeps_the_launcher_as_home(self):
        emu = FakeEmulator()
        emu.compile_as_home_allows()
        logs = []
        self.assertFalse(perf.emulator_speed(emu, logs.append, keep_home=True))
        self.assertEqual(emu.home, "com.gathin.tvhome/.MainActivity")
        self.assertIn("measure main the same way", logs[0])

    def test_no_stock_launcher_only_notes(self):
        emu = FakeEmulator(stock=False)
        emu.compile_as_home_allows()
        logs = []
        self.assertFalse(perf.emulator_speed(emu, logs.append))
        self.assertEqual(emu.home, "com.gathin.tvhome/.MainActivity")
        self.assertIn("only Home app", logs[0])

    def test_run_records_the_speed_build(self):
        emu = FakeEmulator()
        emu.compile_as_home_allows()
        out = os.path.join(tempfile.mkdtemp(), "r.json")
        with mock.patch.object(perf.time, "sleep", lambda s: None), mock.patch("builtins.print"):
            perf.main(["run", "--label", "x", "--scenarios", "mem", "--out", out], shell=emu, log=lambda m: None)
        with open(out) as f:
            self.assertEqual(json.load(f)["build"]["compiler_filter"], "speed")


class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        patcher = mock.patch.object(perf.time, "sleep", lambda s: None)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_perf(self, tv, label, *extra):
        out = os.path.join(self.tmp, label + ".json")
        logs = []
        with mock.patch("builtins.print"):
            perf.main(["run", "--label", label, "--runs", "3", "--rounds", "2", "--out", out] + list(extra),
                      shell=tv, log=logs.append)
        with open(out) as f:
            return json.load(f), logs, out

    def test_full_run(self):
        tv = FakeTv()
        result, logs, _ = self.run_perf(tv, "main")
        self.assertEqual(tv.cold_runs, 3)
        self.assertEqual(result["device"]["model"], "Smart TV Pro")
        self.assertEqual(result["device"]["ram_mb"], 2281)
        self.assertEqual(result["build"]["apk_bytes"], 1660541)
        self.assertTrue(result["build"]["debuggable"])
        self.assertEqual(result["cold"]["summary"]["total_ms"]["median"], 1234)
        self.assertEqual(result["cold"]["summary"]["fully_drawn_ms"]["median"], 1250)
        self.assertEqual(result["warm"]["summary"]["focus_restored_ms"]["median"], 600.0)
        self.assertEqual(result["warm"]["summary"]["reload_load_ms"]["median"], 350.0)
        self.assertEqual(result["warm"]["summary"]["home_frames"]["median"], 10516)
        self.assertEqual(result["jank"]["summary"]["janky_pct"]["n"], 2)
        # dock: 8 swaps per round (the "show" when focus lands on the dock is left out), frames per round
        self.assertEqual(result["dock"]["summary"]["shelf_ms"]["n"], 16)
        self.assertEqual(result["dock"]["summary"]["shelf_ms"]["median"], 603.5)
        self.assertEqual(result["dock"]["summary"]["shelf_glow_ms"]["median"], 500.0)
        self.assertEqual(result["dock"]["summary"]["janky_pct"]["n"], 2)
        self.assertFalse(any("moves changed the shelf" in line for line in logs))
        self.assertEqual(result["mem"]["summary"]["total_pss_kb"]["median"], 132630)
        self.assertTrue(any("debuggable" in line for line in logs))
        # backgrounded with a full-screen TV app (first by name, never the launcher), not translucent Settings
        self.assertEqual(result["params"]["other_app"], "com.google.android.youtube.tv/"
                         "com.google.android.apps.youtube.tv.activity.ShellActivity")
        self.assertIn("am start -W -n " + result["params"]["other_app"], tv.commands)
        self.assertNotIn(perf.SETTINGS_APP, tv.commands)
        self.assertFalse(any("did not time" in line for line in logs))
        # the TV is left as it was: logging switched off again and back on Home
        self.assertIn("setprop log.tag.TvHomePerf DEBUG", tv.commands)
        self.assertEqual(tv.commands[-2:], ["setprop log.tag.TvHomePerf ''", perf.HOME_INTENT])
        # jank: to the first dock app first, then the 16 default keys each round
        loops = [c for c in tv.commands if c.startswith("for k in")]
        self.assertEqual(loops[0], perf.key_loop(perf.TO_FIRST_DOCK_APP, 0.15))
        self.assertEqual(len(loops[1].split(";")[0].split()) - 3, 16)
        self.assertEqual(sum(c == perf.key_loop(perf.DEFAULT_KEYS, 0.35) for c in loops), 2)
        # dock: to the first dock app, a warm-up, then 8 moves alternating between the first two apps per round
        self.assertIn(perf.key_loop(perf.TO_FIRST_DOCK_APP, 0.15), loops)
        self.assertEqual(sum(c == perf.key_loop("RIGHT LEFT " * 4, 1.2) for c in loops), 2)
        # cold starts end the launcher with `am kill`, never `am force-stop`
        self.assertEqual(sum(c == "am kill " + perf.PKG for c in tv.commands), 3)
        # the launcher as it ships, every start (a cold start is a new process), and the saved choices back after
        self.assertEqual(result["config"], {
            "animation_scales": {"window": 1.0, "transition": 1.0, "animator": 1.0},
            "launcher": {"default_config": 1, "glass": 0, "glass_default": 0, "glass_saved": 70, "glass_tier": "lens",
                         "test_art": 0}})
        starts = [c for c in tv.commands if c.startswith("am start") and perf.ACTIVITY in c]
        self.assertTrue(all(c in (perf.START, perf.RESTORE, "am start -W -n " + perf.ACTIVITY) for c in starts), starts)
        self.assertGreaterEqual(starts.count(perf.START), 3 + 1 + 3 + 1 + 1 + 1)
        self.assertEqual(starts[-1], perf.RESTORE)
        self.assertFalse(tv.default_config)
        self.assertFalse(tv.test_art)
        self.assertIsNone(result["params"]["variant"])
        self.assertIn("launcher back to its saved configuration (Liquid Glass 70)", logs)
        self.assertFalse(any("liquid_glass" in c or "glass_test_art" in c for c in tv.commands))
        self.assertNotIn("glass", result["cold"]["samples"][0])
        self.assertTrue(any(line.startswith("measuring main: TCL Smart TV Pro (Android 14), debug build compiled "
                                            "verify, unstamped build") for line in logs))
        self.assertTrue(any("has no provenance stamp" in line for line in logs))
        self.assertIn("warning: labelled main, but it isn't main as it ships: the build has no provenance stamp "
                      "(built before stamping, or without git), so nothing says what was measured. Rebuild it and "
                      "measure again", logs)

    def test_variant(self):
        tv = FakeTv(debuggable=False, version=STAMPED)
        variant = "--ez glass_test_art true  --ei liquid_glass 50"
        result, logs, _ = self.run_perf(tv, "fix-art", "--scenarios", "warm,mem", "--variant", variant)
        self.assertEqual(result["params"]["variant"], "--ez glass_test_art true --ei liquid_glass 50")
        self.assertEqual((result["config"]["launcher"]["glass"], result["config"]["launcher"]["test_art"]), (50, 1))
        self.assertIn("note: measuring a variant: Liquid Glass at 50 (ships at 0), stand-in top shelf art "
                      "(glass_test_art)", logs)
        starts = [c for c in tv.commands if c.startswith("am start") and perf.DEFAULT_CONFIG + " true" in c]
        self.assertEqual(set(starts), {perf.START + " --ez glass_test_art true --ei liquid_glass 50"})
        self.assertEqual([c for c in tv.commands if perf.ACTIVITY in c][-1], perf.RESTORE)
        self.assertEqual((tv.glass, tv.test_art), (70, False))   # back to the owner's choice, no stand-in art
        perf.set_variant(None)
        self.assertEqual(perf.launch(), perf.START)

    def test_variant_takes_only_the_launchers_extras(self):
        for bad in ("--ei liquid_glass 50; reboot", "--es default_config false", "--ei liquid_glass"):
            tv = FakeTv()
            with self.assertRaises(SystemExit):
                self.run_perf(tv, "bad", "--variant", bad)
            self.assertEqual(tv.commands, [])

    def test_trace_starts_clean_and_restores(self):
        tv = FakeTv()
        pulls = []
        tv.pull = lambda remote, local: pulls.append(remote)
        with mock.patch("builtins.print"):
            perf.main(["trace", "--seconds", "1", "--action", "dock", "--out", os.path.join(self.tmp, "t")],
                      shell=tv, log=lambda m: None)
        starts = [c for c in tv.commands if c.startswith("am start") and perf.ACTIVITY in c]
        self.assertEqual(starts[0], "am start -W -n " + perf.ACTIVITY)   # the task's own intent has no extras
        self.assertEqual(starts[1], perf.START)
        self.assertEqual(starts[-1], perf.RESTORE)
        self.assertEqual(pulls, [perf.TRACE_REMOTE])

        def lost(remote, local):
            raise perf.tv.TvError("connection lost")
        tv = FakeTv()
        tv.pull = lost
        with self.assertRaises(SystemExit):
            perf.main(["trace", "--seconds", "1", "--action", "keys"], shell=tv, log=lambda m: None)
        self.assertIn(perf.RESTORE, tv.commands)
        self.assertFalse(tv.default_config)

    def test_stamped_build(self):
        result, logs, _ = self.run_perf(FakeTv(debuggable=False, version=STAMPED), "fix", "--scenarios", "mem")
        self.assertEqual(result["build"]["provenance"], {"commit": COMMIT, "launcher_tree": TREE, "dirty": False})
        self.assertFalse(any("provenance stamp" in line or "labelled" in line for line in logs))

    def test_old_build_without_config(self):
        result, logs, _ = self.run_perf(FakeTv(reports_config=False), "old", "--scenarios", "mem")
        self.assertIsNone(result["config"]["launcher"])
        self.assertTrue(any("doesn't report its configuration" in line for line in logs))

    def test_animation_scales(self):
        tv = FakeTv(scales={"animator_duration_scale": "0.5", "window_animation_scale": "1.0"})
        result, logs, _ = self.run_perf(tv, "slow", "--scenarios", "mem")
        self.assertEqual(result["config"]["animation_scales"], {"window": 1.0, "transition": 1.0, "animator": 0.5})
        self.assertTrue(any("animation scales aren't 1 (window 1, transition 1, animator 0.5)" in line
                            for line in logs))

    def test_cold_warns_when_the_process_survives(self):
        tv = FakeTv()
        tv.survives_kill = True
        _, logs, _ = self.run_perf(tv, "alive", "--scenarios", "cold")
        self.assertEqual(sum("not cold" in line for line in logs), 3)

    def test_compare_table(self):
        a, _, a_path = self.run_perf(FakeTv(), "main")
        b, _, b_path = self.run_perf(FakeTv(debuggable=False), "fix")
        table = perf.render_table([a, b])
        self.assertIn("| Metric (median) | main | fix | change |", table)
        self.assertIn("| Cold start (am start TotalTime) | 1234 ms | 1234 ms | +0 % |", table)
        self.assertIn("| Memory (PSS) | 129.5 MB | 129.5 MB | +0 % |", table)
        self.assertIn("| Dock move: top shelf shows the new app | 603.5 ms | 603.5 ms | +0 % |", table)
        self.assertIn("**Debug vs release build: part of the change comes from the build type alone.**", table)
        # unstamped, debug vs release: compare refuses, and --force prints the table with the reasons under it
        with mock.patch("builtins.print") as p, self.assertRaises(SystemExit) as refused:
            perf.main(["compare", a_path, b_path])
        p.assert_not_called()
        self.assertIn("these results would mislead", str(refused.exception.code))
        self.assertIn("- main: the build has no provenance stamp", str(refused.exception.code))
        self.assertIn("--force", str(refused.exception.code))
        with mock.patch("builtins.print") as p:
            perf.main(["compare", "--force", a_path, b_path])
        out = p.call_args[0][0]
        self.assertIn("| Janky frames (D-pad moves) | 1.3 % | 1.3 % | +0 % |", out)
        self.assertIn("- **fix: the build has no provenance stamp", out)

    @unittest.skipUnless(HAS_GIT, "needs git")
    def test_compare_against_main(self):
        repo = Repo(self)
        old = repo.commit({"launcher/a.txt": "old transitions"})
        new = repo.commit({"launcher/a.txt": "new transitions"})
        repo.main_at(new)
        repo.git("checkout", "-q", "-b", "fix")
        fix = repo.commit({"launcher/b.txt": "my fix"})

        def measure(lbl, ref):
            stamp = "2.0+commit-%s.launcher-%s" % (repo.git("rev-parse", ref), repo.tree(ref))
            tv = FakeTv(debuggable=False, version=stamp, compiler="speed")
            return self.run_perf(tv, lbl, "--scenarios", "warm,mem")[2]

        main_path, fix_path, old_path = measure("main", new), measure("fix", fix), measure("main-old", old)
        with mock.patch.object(provenance, "ROOT", repo.root):
            with mock.patch("builtins.print") as p:
                perf.main(["compare", main_path, fix_path])
            self.assertIn("| Home / warm start (TotalTime) | 402 ms | 402 ms | +0 % |", p.call_args[0][0])
            self.assertIn("- main: TCL Smart TV Pro (Android 14), release build compiled speed, commit %s"
                          % new[:10], p.call_args[0][0])
            with self.assertRaises(SystemExit) as refused:
                perf.main(["compare", old_path, fix_path])
            self.assertIn("main-old: its launcher/ (%s) isn't origin/main's" % repo.tree(old)[:10],
                          str(refused.exception.code))
            with self.assertRaises(SystemExit):
                perf.main(["compare", "--main", "refs/heads/main", main_path, old_path])

    def test_other_app_option(self):
        result, _, _ = self.run_perf(FakeTv(), "nf", "--scenarios", "warm", "--other-app", "com.netflix.ninja")
        self.assertEqual(result["params"]["other_app"], "com.netflix.ninja/.MainActivity")
        with self.assertRaises(SystemExit):
            self.run_perf(FakeTv(), "missing", "--other-app", "com.example.nothere")

    def test_warns_when_start_not_timed(self):
        tv = FakeTv()
        tv.launcher_visible = True
        result, logs, _ = self.run_perf(tv, "visible", "--scenarios", "warm")
        self.assertNotIn("total_ms", result["warm"]["summary"])
        self.assertEqual(sum("did not time" in line for line in logs), 3)

    def test_scenario_subset(self):
        result, _, _ = self.run_perf(FakeTv(), "jank-only", "--scenarios", "jank")
        self.assertIn("jank", result)
        self.assertNotIn("cold", result)

    def test_not_installed(self):
        tv = FakeTv()
        tv.package = "Unable to find package: com.gathin.tvhome\n"
        with self.assertRaises(SystemExit):
            self.run_perf(tv, "x")


if __name__ == "__main__":
    unittest.main()
