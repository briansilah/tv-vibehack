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


if __name__ == "__main__":
    unittest.main()
