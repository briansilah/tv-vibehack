"""Offline checks for the launcher's TV hardware requirements."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


MANIFEST = Path(__file__).resolve().parents[2] / "launcher/app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class HardwareTests(unittest.TestCase):
    def test_wifi_is_available_but_not_required(self):
        manifest = ET.parse(MANIFEST).getroot()
        permissions = {p.get(ANDROID + "name") for p in manifest.findall("uses-permission")}
        self.assertIn("android.permission.ACCESS_WIFI_STATE", permissions)
        wifi = [f for f in manifest.findall("uses-feature")
                if f.get(ANDROID + "name") == "android.hardware.wifi"]
        self.assertEqual(len(wifi), 1, "Declare Wi-Fi explicitly to override the permission's implied requirement")
        self.assertEqual(wifi[0].get(ANDROID + "required"), "false")

    def test_location_permissions_work_on_android_12_without_requiring_gps(self):
        manifest = ET.parse(MANIFEST).getroot()
        permissions = {p.get(ANDROID + "name") for p in manifest.findall("uses-permission")}
        self.assertIn("android.permission.ACCESS_FINE_LOCATION", permissions)
        self.assertIn("android.permission.ACCESS_COARSE_LOCATION", permissions)
        gps = [f for f in manifest.findall("uses-feature")
               if f.get(ANDROID + "name") == "android.hardware.location.gps"]
        self.assertEqual(len(gps), 1)
        self.assertEqual(gps[0].get(ANDROID + "required"), "false")
        location = [f for f in manifest.findall("uses-feature")
                    if f.get(ANDROID + "name") == "android.hardware.location"]
        self.assertEqual(len(location), 1)
        self.assertEqual(location[0].get(ANDROID + "required"), "false")


class HomeSettingsShortcutTests(unittest.TestCase):
    def test_system_picker_is_visible_and_internal_activity_is_not_exported(self):
        manifest = ET.parse(MANIFEST).getroot()
        actions = {a.get(ANDROID + "name") for a in manifest.findall("queries/intent/action")}
        self.assertIn("android.settings.HOME_SETTINGS", actions)
        activity = manifest.find("application/activity[@%s='.HomeSettingsActivity']" % (ANDROID + "name"))
        self.assertIsNotNone(activity)
        self.assertEqual(activity.get(ANDROID + "exported"), "false")
        self.assertEqual(activity.get(ANDROID + "excludeFromRecents"), "true")
        self.assertIsNotNone(manifest.find("application/activity[@%s='.SettingsActivity']" % (ANDROID + "name")))

    def test_shortcut_is_opt_in_and_does_not_rename_home_settings(self):
        resources = MANIFEST.parent / "res/values"
        config = ET.parse(resources / "config.xml").getroot()
        self.assertEqual(config.find("bool[@name='show_home_settings_shortcut']").text, "false")
        strings = ET.parse(resources / "strings.xml").getroot()
        self.assertEqual(strings.find("string[@name='home_settings']").text, "Home Settings")
        self.assertEqual(strings.find("string[@name='default_home']").text, "Default Home")


if __name__ == "__main__":
    unittest.main()
