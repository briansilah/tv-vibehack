"""Exercise installer routing offline: no SDK, build, adb, or device connection."""
import json
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
with open(os.environ['INSTALL_LOG'], 'a') as log:
    for command in commands:
        log.write(json.dumps(command) + '\\n')
        if command.startswith('pm path ') and os.environ['FAKE_GOOGLE_TV'] == '1':
            print('package:/system/launcherx.apk')
"""


class InputInstallTests(unittest.TestCase):
    def install(self, google_tv, input_key):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            launcher = root / "launcher"
            tools = root / "tools"
            (tools / "venv/bin").mkdir(parents=True)
            config = launcher / "app/src/main/res/values/config.xml"
            config.parent.mkdir(parents=True)
            config.write_text('<resources><bool name="input_key_service">%s</bool></resources>'
                              % str(input_key).lower())
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
            result = subprocess.run(["bash", str(launcher / "build-and-install.sh")], env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            return [json.loads(line) for line in log.read_text().splitlines()]

    def test_only_one_service_with_volume_support_in_all_install_modes(self):
        for google_tv in (False, True):
            for input_key in (False, True):
                with self.subTest(google_tv=google_tv, input_key=input_key):
                    commands = self.install(google_tv, input_key)
                    filters = [c for c in commands if "enabled_accessibility_services" in c]
                    self.assertEqual(len(filters), int(google_tv or input_key))
                    if filters:
                        self.assertIn("com.gathin.tvhome/.HomeButtonService", filters[0])
                        self.assertNotIn("InputKeyService", filters[0])
                        self.assertIn('"$s:com.gathin.tvhome/.HomeButtonService"', filters[0])
                        self.assertTrue(any("android.permission.DUMP" in c for c in commands))
                    self.assertEqual("pm disable-user --user 0 com.google.android.tvlauncher" in commands,
                                     not google_tv)
                    self.assertFalse(any("disable-user" in c and "launcherx" in c for c in commands))
                    self.assertFalse(any("force-stop" in c for c in commands))

    def test_default_has_no_input_interception_or_second_filter(self):
        resources = REPO / "launcher/app/src/main/res"
        config = ET.parse(resources / "values/config.xml")
        self.assertEqual(config.find("bool[@name='input_key_service']").text, "false")
        manifest = ET.parse(REPO / "launcher/app/src/main/AndroidManifest.xml")
        name = "{http://schemas.android.com/apk/res/android}name"
        services = [s.get(name) for s in manifest.findall(".//service")
                    if s.get("{http://schemas.android.com/apk/res/android}permission")
                    == "android.permission.BIND_ACCESSIBILITY_SERVICE"]
        self.assertEqual(services, [".HomeButtonService"])

    def test_windows_uses_same_opt_in_and_grants_dump_before_enabling(self):
        script = (REPO / "launcher/build-and-install.ps1").read_text()
        self.assertIn('bool[@name="input_key_service"]', script)
        optional = script.split("if ($googleTv -or $inputKeyService)", 1)[1]
        self.assertLess(optional.index("android.permission.DUMP"),
                        optional.index("enabled_accessibility_services"))
        self.assertIn("com.gathin.tvhome/.HomeButtonService", optional)
        self.assertNotIn("InputKeyService", optional)
