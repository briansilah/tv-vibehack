#!/usr/bin/env python3
"""Build the launcher's fonts: small static cuts of an open-licence variable typeface.

The launcher's text styles (launcher/app/src/main/res/values/styles.xml) use three fonts, named for their role:

    text_medium      body text, buttons and tile names        weight 500, optical size for small text
    text_semibold    small capitalised labels and key caps    weight 600, optical size for small text
    display_medium   titles and clocks                        weight 500, optical size for large text

Each is a static font: the variable typeface at one weight and optical size. A static font needs no variation
instance, so every view that uses it shares one typeface that Android loads once, on every Android version
(docs/performance.md, "Font weights cost 10-12 ms per view"). Each is also cut down to:

- Latin characters: English, Western, Central and Eastern European languages (German included) and the
  punctuation, currency and arrows app names and the launcher's strings use. Anything else (an app named in
  Hindi, say) falls back to the TV's system font, as before.
- The OpenType features the launcher uses: kerning, ligatures, contextual alternates (such as the colon
  raised between digits in 9:41) and tabular figures (android:fontFeatureSettings="tnum").
- No hinting, which Android doesn't use.

The launcher uses Inter. The other typefaces it was compared with on the TV can replace it in one step: this
writes the three fonts and launcher/FONT-LICENSE-OFL.txt, from pinned downloads.

    pip install fonttools
    python3 tools/fonts.py                          rebuild the fonts in Inter
    python3 tools/fonts.py --family geist           ... or in another typeface (see FAMILIES)
    python3 tools/fonts.py --source Inter.ttf       use a local copy of the variable font
    python3 tools/fonts.py --check                  only check the subset covers every launcher string (no fontTools)
"""
import argparse
import glob
import hashlib
import html
import os
import re
import sys
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
RES = os.path.join(REPO, "launcher", "app", "src", "main", "res")
FONT_DIR = os.path.join(RES, "font")

# Basic Latin, Latin-1 Supplement and Latin Extended-A, plus the Latin Extended-B letters of Romanian, a few
# spacing and combining accents, capital sharp s, and the punctuation, currency and symbols in common use.
UNICODE_RANGES = (
    "U+0020-007E, U+00A0-017F, U+0192, U+0218-021B, U+02C6-02C7, U+02D8-02DD, U+0300-0304, U+0306-0308, "
    "U+030A-030C, U+0326-0328, U+1E9E, U+2007-200B, U+2010-2027, U+2030, U+2032-2033, U+2039-203A, U+2044, "
    "U+20AC, U+2122, U+2190-2193, U+2212, U+2215, U+FEFF, U+FFFD"
)
# Kerning, ligatures, contextual alternates and tabular figures (android:fontFeatureSettings="tnum").
LAYOUT_FEATURES = ["kern", "liga", "calt", "tnum"]

LICENSE_FILE = os.path.join(REPO, "launcher", "FONT-LICENSE-OFL.txt")

# Weight and optical size of each role. The optical size ("opsz") picks the design for the size the text is set
# at: looser spacing and more open shapes for small text, finer and tighter for large text. A typeface without
# one gets the same design for both.
ROLES = {
    "text_medium": {"wght": 500, "opsz": "text"},
    "text_semibold": {"wght": 600, "opsz": "text"},
    "display_medium": {"wght": 500, "opsz": "display"},
}

# Open-licence typefaces (SIL OFL 1.1, no Reserved Font Name, so a subset may keep the name): the variable font,
# its licence, and the values of its axes for each optical size. Downloads are pinned and checked.
FAMILIES = {
    "inter": {
        "name": "Inter 4.1",
        "font": ("https://raw.githubusercontent.com/rsms/inter/v4.1/docs/font-files/InterVariable.ttf",
                 "4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf"),
        "license": ("https://raw.githubusercontent.com/rsms/inter/v4.1/LICENSE.txt",
                    "262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a"),
        # Inter's optical sizes run from 14 (Inter) to 32 (Inter Display).
        "opsz": {"text": {"opsz": 14}, "display": {"opsz": 32}},
    },
    "geist": {
        "name": "Geist",
        "font": ("https://raw.githubusercontent.com/google/fonts/718e1db4deb9e4d9d85a0ead1b9f5fde2761ccfd/ofl/geist/"
                 "Geist%5Bwght%5D.ttf", "73894e0448cae90a92b6c2f8732b7bb9acb7b94c418bff559dad4a18e1de9659"),
        "license": ("https://raw.githubusercontent.com/google/fonts/718e1db4deb9e4d9d85a0ead1b9f5fde2761ccfd/ofl/geist/"
                    "OFL.txt", "1781d2806a07d91c4edf4740b88449fab7d0eadad53f7c351b94cd4d4eb8c00f"),
        "opsz": {"text": {}, "display": {}},
    },
    "google-sans-flex": {
        "name": "Google Sans Flex",
        "font": ("https://raw.githubusercontent.com/google/fonts/bd8f81ddb5c74d5c8897b36ad88b440266245103/ofl/"
                 "googlesansflex/GoogleSansFlex%5BROND,opsz,slnt,wdth,wght%5D.ttf",
                 "a9ac770798857d5e8ac593ced42b22e40a7c08cec98319f92b0eccc2783a77ba"),
        "license": ("https://raw.githubusercontent.com/google/fonts/bd8f81ddb5c74d5c8897b36ad88b440266245103/ofl/"
                    "googlesansflex/OFL.txt", "fc13d69f63e36d284b6e383d4d1463d8ad404f0aa065e57cf961252d51063137"),
        "opsz": {"text": {"opsz": 17}, "display": {"opsz": 40}},
    },
}
FAMILY = "inter"


def cut_axes(family, role):
    """Axis values for one role in one family, e.g. {"wght": 500, "opsz": 14}."""
    spec = ROLES[role]
    axes = {"wght": spec["wght"]}
    axes.update(FAMILIES[family]["opsz"][spec["opsz"]])
    return axes


def parse_ranges(spec):
    """'U+0041-005A, U+00E9' -> the set of code points."""
    points = set()
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if not part.upper().startswith("U+"):
            raise ValueError("not a Unicode range: %r" % part)
        start, _, end = part[2:].partition("-")
        first, last = int(start, 16), int(end or start, 16)
        if last < first:
            raise ValueError("backwards Unicode range: %r" % part)
        points.update(range(first, last + 1))
    return points


UNICODES = parse_ranges(UNICODE_RANGES)


def string_resources(res=RES):
    """Text of every <string> and <item> in values*/strings.xml, all languages, with escapes undone."""
    texts = []
    for path in sorted(glob.glob(os.path.join(res, "values*", "strings.xml"))):
        with open(path, encoding="utf-8") as f:
            xml = f.read()
        for m in re.finditer(r"<(string|item)\b[^>]*>(.*?)</\1>", xml, re.S):
            text = re.sub(r"<[^>]+>", "", m.group(2))           # inline markup such as <b>
            text = html.unescape(text)
            text = re.sub(r"\\u([0-9a-fA-F]{4})", lambda u: chr(int(u.group(1), 16)), text)
            text = re.sub(r"\\(.)", r"\1", text)                 # \' \" \n ...
            texts.append((os.path.relpath(path, res), text))
    return texts


def missing_characters(res=RES, unicodes=UNICODES):
    """Characters in the launcher's strings that the font subset would leave out: {char: [file, ...]}."""
    missing = {}
    for path, text in string_resources(res):
        for ch in text:
            if ch in "\n\t" or ord(ch) in unicodes:
                continue
            missing.setdefault(ch, [])
            if path not in missing[ch]:
                missing[ch].append(path)
    return missing


def fetch(url, sha256):
    """Download a pinned file and check it is the expected version. Returns its bytes."""
    with urllib.request.urlopen(url) as r:
        data = r.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != sha256:
        sys.exit("%s: SHA-256 %s, expected %s" % (url, digest, sha256))
    return data


def build_cut(source, axes, out_path):
    """One static font: the variable font instanced at `axes`, then subset. Returns the file size."""
    from fontTools import subset
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer

    font = TTFont(source)
    # Pin every axis: unlisted ones at their default, so the result is a plain static font.
    pinned = {a.axisTag: a.defaultValue for a in font["fvar"].axes}
    pinned.update(axes)
    try:
        font = instancer.instantiateVariableFont(font, pinned, updateFontNames=True)
    except ValueError:      # no named instance at these values to take the names from: keep the family's
        font = instancer.instantiateVariableFont(TTFont(source), pinned)

    options = subset.Options()
    options.layout_features = LAYOUT_FEATURES
    options.name_IDs = ["*"]              # keep the copyright, licence and font names
    options.name_languages = ["*"]
    options.hinting = False
    options.notdef_outline = True
    options.glyph_names = False
    options.drop_tables += ["STAT", "meta"]
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=sorted(UNICODES))
    subsetter.subset(font)
    font.recalcTimestamp = False          # the same source builds the same bytes
    font.save(out_path)
    return os.path.getsize(out_path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", help="local copy of the variable font instead of downloading it")
    parser.add_argument("--family", default=FAMILY, choices=sorted(FAMILIES))
    parser.add_argument("--out", default=FONT_DIR, help="output directory (default: the launcher's res/font)")
    parser.add_argument("--check", action="store_true", help="only check every launcher string is covered")
    args = parser.parse_args(argv)

    missing = missing_characters()
    if missing:
        for ch, paths in sorted(missing.items()):
            print("not in the font subset: %r U+%04X (%s)" % (ch, ord(ch), ", ".join(paths)), file=sys.stderr)
        print("Add them to UNICODE_RANGES in tools/fonts.py and rebuild the fonts.", file=sys.stderr)
        return 1
    if args.check:
        print("Every character in the launcher's strings is in the font subset.")
        return 0

    family = FAMILIES[args.family]
    source = args.source
    if not source:
        with tempfile.NamedTemporaryFile(suffix=".ttf", delete=False) as f:
            f.write(fetch(*family["font"]))
        source = f.name
    os.makedirs(args.out, exist_ok=True)
    total = 0
    for role in ROLES:
        axes = cut_axes(args.family, role)
        size = build_cut(source, axes, os.path.join(args.out, role + ".ttf"))
        total += size
        print("%-20s %s  %5.1f KB" % (role + ".ttf", " ".join("%s=%g" % kv for kv in axes.items()), size / 1024))
    print("%-20s %5.1f KB" % ("total", total / 1024))
    if args.out == FONT_DIR:
        with open(LICENSE_FILE, "wb") as f:
            f.write(fetch(*family["license"]))
        print("%s: %s licence" % (os.path.relpath(LICENSE_FILE, REPO), family["name"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
