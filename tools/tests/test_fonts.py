"""Offline tests for tools/fonts.py and the launcher's font resources (no fontTools needed).

    python3 -m unittest discover -s tools/tests -v
"""
import glob
import os
import re
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, TOOLS)
import fonts  # noqa: E402


class RangeTests(unittest.TestCase):
    def test_single_points_and_ranges(self):
        self.assertEqual(fonts.parse_ranges("U+0041-0043, U+00E9"), {0x41, 0x42, 0x43, 0xE9})

    def test_bad_ranges_are_rejected(self):
        with self.assertRaises(ValueError):
            fonts.parse_ranges("0041-0043")
        with self.assertRaises(ValueError):
            fonts.parse_ranges("U+0043-0041")

    def test_subset_covers_german_and_central_european_letters(self):
        for ch in "ÄÖÜäöüßẞ„“‚‘–—…€ąćęłńśźżčřšžőűăâîșț":
            self.assertIn(ord(ch), fonts.UNICODES, ch)


class FamilyTests(unittest.TestCase):
    def test_every_family_has_both_optical_sizes(self):
        for name, family in fonts.FAMILIES.items():
            self.assertEqual(set(family["opsz"]), {"text", "display"}, name)
            for url, sha256 in (family["font"], family["license"]):
                self.assertTrue(url.startswith("https://"), url)
                self.assertRegex(sha256, r"^[0-9a-f]{64}$")

    def test_cut_axes(self):
        self.assertEqual(fonts.cut_axes("inter", "text_semibold"), {"wght": 600, "opsz": 14})
        self.assertEqual(fonts.cut_axes("inter", "display_medium"), {"wght": 500, "opsz": 32})
        self.assertEqual(fonts.cut_axes("geist", "display_medium"), {"wght": 500})


class StringTests(unittest.TestCase):
    def test_escapes_are_undone(self):
        with tempfile.TemporaryDirectory() as res:
            os.makedirs(os.path.join(res, "values-xx"))
            with open(os.path.join(res, "values-xx", "strings.xml"), "w", encoding="utf-8") as f:
                f.write('<resources><string name="a">Can\\\'t &amp; \\u00e9 <b>x</b></string>'
                        '<string-array name="b"><item>Ж</item></string-array></resources>')
            self.assertEqual([t for _, t in fonts.string_resources(res)], ["Can't & é x", "Ж"])
            self.assertEqual(fonts.missing_characters(res), {"Ж": ["values-xx/strings.xml"]})

    def test_every_launcher_string_is_in_the_subset(self):
        self.assertEqual(fonts.missing_characters(), {})


class FontResourceTests(unittest.TestCase):
    """The fonts in res/font are exactly the ones tools/fonts.py builds, and every one is used."""

    def font_files(self):
        return {os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(fonts.RES, "font*", "*"))}

    def references(self):
        refs = set()
        for path in glob.glob(os.path.join(fonts.RES, "*", "*.xml")):
            with open(path, encoding="utf-8") as f:
                refs.update(re.findall(r"@font/(\w+)", f.read()))
        java = os.path.join(os.path.dirname(fonts.RES), "java")
        for path in glob.glob(os.path.join(java, "**", "*.java"), recursive=True):
            with open(path, encoding="utf-8") as f:
                refs.update(re.findall(r"R\.font\.(\w+)", f.read()))
        return refs

    def test_res_font_holds_one_font_per_role(self):
        self.assertEqual(self.font_files(), set(fonts.ROLES))

    def test_every_font_is_used_and_exists(self):
        self.assertEqual(self.references(), self.font_files())


if __name__ == "__main__":
    unittest.main()
