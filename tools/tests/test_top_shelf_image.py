"""Offline tests for tools/top_shelf_image.py, with a synthetic Apple TV screenshot and no TV.

    python3 -m unittest discover -s tools/tests -v
"""
import hashlib
import io
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import top_shelf_image as tsi  # noqa: E402

try:
    from PIL import Image, ImageDraw
except ImportError:  # pip install -r tools/requirements.txt
    Image = None

BACK = (30, 30, 30)
DOCK = (200, 0, 200)
CLOCK = (255, 255, 255)


def capture(w=3840, h=2160):
    """A tvOS home screen as Xcode saves it: art (a gradient down the screen, so a crop shows as a shift), the time
    and profile picture in the top right, and the dock from 72.7 % of the way down."""
    img = Image.new("RGB", (w, h), BACK)
    d = ImageDraw.Draw(img)
    for y in range(h):
        d.line([(0, y), (w // 3, y)], fill=(0, round(255 * y / h), 0))
    # tvOS 26's time and profile picture: 1690-1870 x 50-110 points of 1920 x 1080.
    d.rounded_rectangle([round(w * 1690 / 1920), round(h * 50 / 1080), round(w * 1870 / 1920), round(h * 110 / 1080)],
                        radius=h // 36, fill=CLOCK)
    d.rectangle([0, round(h * 785 / 1080), w, h], fill=DOCK)
    return img


def close(a, b, tol=12):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


@unittest.skipIf(Image is None, "needs Pillow")
class CropTests(unittest.TestCase):
    def test_keeps_the_art_above_the_dock_at_the_screens_width(self):
        out = tsi.make(capture())
        self.assertEqual(out.size, (1920, round(2160 * tsi.SHELF_BOTTOM / 2)))
        px = out.load()
        # No dock: the last row is still art.
        for x in range(0, out.width, 40):
            self.assertFalse(close(px[x, out.height - 1], DOCK, 60), x)
        # The gradient isn't moved or stretched: a row shows the green of the same height on the screen.
        y = out.height // 2
        self.assertTrue(close(px[50, y], (0, round(255 * y / 1080), 0), 4), px[50, y])

    def test_paints_over_the_time_and_profile_picture(self):
        out = tsi.make(capture())
        px = out.load()
        # Where they were, at the screen's 1920 x 1080.
        for y in range(48, 113, 3):
            for x in range(1688, 1873, 3):
                self.assertTrue(close(px[x, y], BACK), (x, y, px[x, y]))

    def test_a_1080p_capture_is_kept_at_its_size(self):
        self.assertEqual(tsi.make(capture(1920, 1080)).size, (1920, round(1080 * tsi.SHELF_BOTTOM)))

    def test_a_wide_top_shelf_image_shows_its_middle_as_on_tvos(self):
        img = Image.new("RGB", (2320, 720), (255, 0, 0))
        ImageDraw.Draw(img).rectangle([280, 0, 2040, 719], fill=(0, 0, 255))
        out = tsi.make(img, whole=True)
        # @1x: not scaled up here (the launcher draws it at the screen's width).
        self.assertEqual(out.size, (1761, 720))
        px = out.load()
        for x in (2, out.width // 2, out.width - 3):
            self.assertTrue(close(px[x, out.height // 2], (0, 0, 255), 40), (x, px[x, out.height // 2]))

    def test_a_cropped_picture_needs_whole(self):
        with self.assertRaisesRegex(ValueError, "--whole"):
            tsi.make(Image.new("RGB", (2320, 720)))
        # An app's Top Shelf Image Wide loses its sides as on tvOS: the middle 1761 x 720 of 2320 x 720.
        self.assertEqual(tsi.make(Image.new("RGB", (4640, 1440)), whole=True).size, (1920, 785))
        self.assertEqual(tsi.make(Image.new("RGB", (1280, 720)), whole=True).size, (1280, 720))

    def test_a_quicktime_frame_a_few_pixels_off_is_fine(self):
        tsi.check_capture((1920, 1088))
        with self.assertRaises(ValueError):
            tsi.check_capture((1920, 1200))

    def test_encodes_a_sharp_jpeg(self):
        data = tsi.encode(tsi.make(capture(1920, 1080)))
        img = Image.open(io.BytesIO(data))
        self.assertEqual(img.format, "JPEG")
        self.assertEqual(img.size, (1920, round(1080 * tsi.SHELF_BOTTOM)))


class FakeTv:
    """A TV with a shell and a filesystem of one directory."""

    def __init__(self, native_push=True):
        self.native_push = native_push
        self.files = {}
        self.commands = []

    def shell(self, cmd, timeout=120):
        self.commands.append(cmd)
        if cmd.startswith("md5sum "):
            path = cmd.split()[1]
            return "%s  %s\n" % (hashlib.md5(self.files[path]).hexdigest(), path) if path in self.files else ""
        if cmd.startswith("mv -f "):
            _, _, src, dst = cmd.split()
            self.files[dst] = self.files.pop(src)
        elif cmd.startswith("rm -f ") or " && rm -f " in cmd:
            for path in cmd.split("rm -f ", 1)[1].split():
                self.files.pop(path, None)
        return ""

    def push(self, local, remote, timeout=300):
        with open(local, "rb") as f:
            self.files[remote] = f.read()


class InstallTests(unittest.TestCase):
    def test_puts_it_where_the_launcher_reads_it_in_one_step(self):
        tv = FakeTv()
        tv.files[tsi.remote_path(tsi.YOUTUBE, ".png")] = b"old"
        path = tsi.install(tv, b"jpeg bytes", tsi.YOUTUBE)
        self.assertEqual(path, "/sdcard/Android/data/com.gathin.tvhome/files/top-shelf/com.google.android.youtube.tv.jpg")
        self.assertEqual(tv.files, {path: b"jpeg bytes"})
        self.assertTrue(any(c.startswith("mv -f ") and c.endswith(path) for c in tv.commands))

    def test_a_damaged_copy_changes_nothing(self):
        tv = FakeTv()
        tv.push = lambda local, remote, timeout=300: tv.files.__setitem__(remote, b"damaged")
        tv.files[tsi.remote_path(tsi.YOUTUBE)] = b"the one before"
        with self.assertRaisesRegex(tsi.tv.TvError, "damaged"):
            tsi.install(tv, b"jpeg bytes", tsi.YOUTUBE)
        self.assertEqual(tv.files, {tsi.remote_path(tsi.YOUTUBE): b"the one before"})

    def test_remove_takes_every_type_off(self):
        tv = FakeTv()
        for t in tsi.TYPES:
            tv.files[tsi.remote_path("com.example.app", t)] = b"x"
        tv.files[tsi.remote_path(tsi.YOUTUBE)] = b"kept"
        tsi.remove(tv, "com.example.app")
        self.assertEqual(tv.files, {tsi.remote_path(tsi.YOUTUBE): b"kept"})

    def test_shows_home_with_the_launcher_not_force_stopped(self):
        tv = FakeTv()
        with mock.patch.object(tsi.time, "sleep"):
            tsi.show_home(tv)
        self.assertEqual(tv.commands[-1], "am start -W -n com.gathin.tvhome/.MainActivity")
        self.assertFalse(any("force-stop" in c for c in tv.commands))


if __name__ == "__main__":
    unittest.main()
