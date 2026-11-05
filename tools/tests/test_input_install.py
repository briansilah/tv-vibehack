"""Exercise installer routing offline: no SDK, build, adb, or device connection."""
import json
import itertools
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
FAKE_TV = """
import json, os, sys
commands = sys.stdin.read().splitlines() if '--batch' in sys.argv else [sys.argv[1]]
path = os.environ['INSTALL_LOG']
previous = open(path).read() if os.path.exists(path) else ''
switched = 'cmd package set-home-activity' in previous
with open(os.environ['INSTALL_LOG'], 'a') as log:
    for command in commands:
        log.write(json.dumps(command) + '\\n')
        if command.startswith('pm path ') and os.environ['FAKE_GOOGLE_TV'] == '1':
            print('package:/system/launcherx.apk')
        elif command.startswith('pm install '):
            print(os.environ.get('FAKE_INSTALL', 'Success'))
        elif command.startswith('am start -W'):
            print(os.environ.get('FAKE_START', 'Status: ok'))
        elif command.startswith('cmd package resolve-activity'):
            if switched and os.environ.get('FAKE_QUERY_FAILURE') == '1':
                sys.exit(1)
            package = os.environ.get('FAKE_HOME_AFTER', 'com.gathin.tvhome') if switched else os.environ.get('FAKE_HOME', 'com.google.android.tvlauncher')
            print(package + '/.MainActivity')
"""


class InputInstallTests(unittest.TestCase):
    def test_shell_installer_is_executable(self):
        self.assertTrue(os.access(REPO / "launcher/build-and-install.sh", os.X_OK))

    def install(self, google_tv, input_key, home_key, *, overrides=None, succeeds=True):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "launcher"
            tools = root / "tools"
            (tools / "venv/bin").mkdir(parents=True)
            config = launcher / "app/src/main/res/values/config.xml"
            config.parent.mkdir(parents=True)
            config.write_text('<resources><bool name="input_key_service">%s</bool>'
                              '<bool name="home_key_service">%s</bool></resources>'
                              % (str(input_key).lower(), str(home_key).lower()))
            shutil.copyfile(REPO / "launcher/build-and-install.sh", launcher / "build-and-install.sh")
            (launcher / "local.properties").touch()
            gradle = launcher / "gradlew"
            gradle.write_text("#!/bin/sh\nexit 0\n")
            gradle.chmod(0o755)
            (tools / "venv/bin/python").symlink_to(sys.executable)
            (tools / "b64push.py").write_text("")
            (tools / "tv.py").write_text(FAKE_TV)
            log = root / "commands.jsonl"
            env = dict(os.environ, INSTALL_LOG=str(log), FAKE_GOOGLE_TV=str(int(google_tv)))
            env.pop("STOCK_LAUNCHER", None)
            env.update(overrides or {})
            result = subprocess.run(["bash", str(launcher / "build-and-install.sh")], env=env,
                                    capture_output=True, text=True, timeout=30)
            if succeeds:
                self.assertEqual(result.returncode, 0, result.stderr)
            else:
                self.assertNotEqual(result.returncode, 0, result.stdout)
            return [json.loads(line) for line in log.read_text().splitlines()]

    def test_only_one_service_with_volume_support_in_all_install_modes(self):
        for google_tv, input_key, home_key in itertools.product((False, True), repeat=3):
            with self.subTest(google_tv=google_tv, input_key=input_key, home_key=home_key):
                commands = self.install(google_tv, input_key, home_key)
                filters = [c for c in commands if "enabled_accessibility_services" in c]
                self.assertEqual(len(filters), int(google_tv or input_key or home_key))
                if filters:
                    self.assertIn("com.gathin.tvhome/.HomeButtonService", filters[0])
                    self.assertNotIn("InputKeyService", filters[0])
                    self.assertNotIn("HomeKeyService", filters[0])
                    self.assertIn('"$s:com.gathin.tvhome/.HomeButtonService"', filters[0])
                    self.assertTrue(any("android.permission.DUMP" in c for c in commands))
                self.assertEqual("pm disable-user --user 0 com.google.android.tvlauncher" in commands,
                                 not google_tv)
                self.assertFalse(any("disable-user" in c and "launcherx" in c for c in commands))
                self.assertFalse(any("force-stop" in c for c in commands))

    def test_non_google_stock_is_detected_and_none_disables_nothing(self):
        for package in ("com.google.android.leanbacklauncher", "com.gathin.tvhome"):
            commands = self.install(False, False, False, overrides={"FAKE_HOME": package})
            disabled = [c for c in commands if c.startswith("pm disable-user")]
            self.assertEqual(disabled, [] if package == "com.gathin.tvhome"
                             else ["pm disable-user --user 0 " + package])
        commands = self.install(False, False, False, overrides={"STOCK_LAUNCHER": "none"})
        self.assertFalse(any("disable-user" in c for c in commands))

    def test_unknown_home_fails_without_disabling_anything(self):
        for package in ("android", "com.google.android.tungsten.setupwraith", "other.launcher"):
            commands = self.install(False, False, False, overrides={"FAKE_HOME": package}, succeeds=False)
            self.assertFalse(any("disable-user" in c or c.startswith("pm install") for c in commands))

    def test_install_or_launch_failure_leaves_stock_untouched(self):
        for override in ({"FAKE_INSTALL": "Failure [INSTALL_FAILED]"}, {"FAKE_START": "Error: activity missing"}):
            commands = self.install(False, False, False, overrides=override, succeeds=False)
            self.assertFalse(any("disable-user" in c for c in commands))

    def test_failed_home_verification_restores_stock(self):
        for override in ({"FAKE_HOME_AFTER": "android"}, {"FAKE_QUERY_FAILURE": "1"}):
            commands = self.install(False, False, False, overrides=override, succeeds=False)
            self.assertIn("pm enable --user 0 com.google.android.tvlauncher", commands)

    def test_start_disable_and_home_preference_are_ordered(self):
        commands = self.install(False, False, False)
        start = commands.index("am start -W -n com.gathin.tvhome/.MainActivity")
        disable = commands.index("pm disable-user --user 0 com.google.android.tvlauncher")
        home = commands.index("cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity")
        self.assertLess(start, disable)
        self.assertLess(disable, home)

    def test_google_tv_is_never_disabled_even_with_override(self):
        commands = self.install(True, False, False,
                                overrides={"STOCK_LAUNCHER": "com.google.android.apps.tv.launcherx"})
        self.assertFalse(any("disable-user" in c for c in commands))

    def test_default_has_no_input_interception_or_second_filter(self):
        resources = REPO / "launcher/app/src/main/res"
        config = ET.parse(resources / "values/config.xml")
        self.assertEqual(config.find("bool[@name='input_key_service']").text, "false")
        self.assertEqual(config.find("bool[@name='home_key_service']").text, "false")
        self.assertIsNone(config.find("integer[@name='rows_below_dock']"))
        manifest = ET.parse(REPO / "launcher/app/src/main/AndroidManifest.xml")
        name = "{http://schemas.android.com/apk/res/android}name"
        services = [s.get(name) for s in manifest.findall(".//service")
                    if s.get("{http://schemas.android.com/apk/res/android}permission")
                    == "android.permission.BIND_ACCESSIBILITY_SERVICE"]
        self.assertEqual(services, [".HomeButtonService"])

    def test_windows_uses_same_opt_in_and_grants_dump_before_enabling(self):
        script = (REPO / "launcher/build-and-install.ps1").read_text()
        self.assertIn('bool[@name="input_key_service"]', script)
        self.assertIn('bool[@name="home_key_service"]', script)
        optional = script.split("if ($googleTv -or $inputKeyService -or $homeKeyService)", 1)[1]
        self.assertLess(optional.index("android.permission.DUMP"),
                        optional.index("enabled_accessibility_services"))
        self.assertIn("com.gathin.tvhome/.HomeButtonService", optional)
        self.assertNotIn("InputKeyService", optional)
