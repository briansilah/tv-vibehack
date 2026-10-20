#!/usr/bin/env python3
"""Put an app's Apple TV Top Shelf image on the TV, for the launcher's top shelf.

On Apple TV, an app without Top Shelf content of its own (YouTube, for one) shows a static image behind the dock
while it's focused in the top row. The launcher shows the same for the apps in config.xml's top_shelf_images, once
the image is on the TV. This makes it from a screenshot of an Apple TV's home screen with that app focused, and puts
it on the TV. The image is yours: it never goes in the repository.

Take the screenshot on a Mac, with the Apple TV paired with Xcode (on the Apple TV: Settings > Remotes and Devices >
Remote App and Devices). Put the app in the Apple TV's top row and focus it, then in Xcode: Window > Devices and
Simulators > the Apple TV > Take Screenshot. That's a 3840 x 2160 PNG on the Desktop. A frame of a QuickTime
recording of the Apple TV works too (File > New Movie Recording, with the Apple TV as the camera; pause, Edit > Copy,
then in Preview File > New from Clipboard).

    python3 tools/top_shelf_image.py Screenshot.png                  YouTube's image, onto the TV
    python3 tools/top_shelf_image.py Screenshot.png --package PKG    another app's (list it in top_shelf_images)
    python3 tools/top_shelf_image.py Screenshot.png --out shelf.jpg  only write the image, for a look first
    python3 tools/top_shelf_image.py art.png --whole                 a picture that's the art already (an app's
                                                                     Top Shelf Image Wide): cut to tvOS's view only
    python3 tools/top_shelf_image.py --remove                        the app goes back to its cover art

From a screenshot it keeps the part above the dock, paints over the time and profile picture in the top right from
what's around them, and scales it to the width of the TV's screen (1920 pixels). The launcher draws it from the top
of the screen, as tvOS does, carrying its bottom edge on behind the dock, mirrored and blurred. Then this shows Home
again, which picks the image up.

Connects to the TV as tools/tv.py does. Needs Pillow: pip install -r tools/requirements.txt.
"""
import argparse
import hashlib
import io
import os
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import b64push  # noqa: E402
import tv  # noqa: E402

PKG = "com.gathin.tvhome"
ACTIVITY = PKG + "/.MainActivity"
YOUTUBE = "com.google.android.youtube.tv"
# The launcher's external files: the TV's shell can write there, and the launcher reads it without a permission.
REMOTE_DIR = "/sdcard/Android/data/%s/files/top-shelf" % PKG
TYPES = (".jpg", ".png", ".webp")
# The launcher draws the top shelf at the screen's size: Android TV draws apps at 1920 x 1080, even on a 4K panel.
WIDTH = 1920

# tvOS's home screen with focus on the top row, as fractions of the screen (1920 x 1080 points), measured with a
# test pattern in the tvOS 27 Simulator. An app's Top Shelf Image (Wide), 2320 x 720, is scaled to reach the dock's
# glass at 785 points (x 1.09) and centred, so the middle 1761 x 720 of it shows, from the top of the screen; behind
# the dock its bottom edge carries on, mirrored and blurred. Keep what's above the glass, with a margin for its rim.
# The time and profile picture sit at 1690-1870 x 50-110 points: paint them over, with a margin for their shadow.
SHELF_BOTTOM = 780 / 1080
SHELF_ASPECT = 1920 / 785
CLOCK = (0.868, 0.033, 0.986, 0.118)
# Captures within this of 16:9 (a QuickTime frame may be a few pixels off).
ASPECT_SLACK = 0.02


def _pil():
    try:
        from PIL import Image, ImageFilter
    except ImportError:
        sys.exit("top_shelf_image.py needs Pillow: %s" % tv.VENV_HINT)
    return Image, ImageFilter


def check_capture(size):
    """A screenshot of the whole screen is 16:9; anything else is likely already cropped (use --whole)."""
    w, h = size
    if h <= 0 or abs(w / h - 16 / 9) > 16 / 9 * ASPECT_SLACK:
        raise ValueError("%dx%d isn't a 16:9 screenshot of the whole Apple TV screen. For a picture that's the art "
                         "already, add --whole." % (w, h))


def box(size, frac):
    """A box given as fractions of the image, in whole pixels."""
    w, h = size
    return (round(frac[0] * w), round(frac[1] * h), round(frac[2] * w), round(frac[3] * h))


def patch(img, rect):
    """Paints over rect from its edges: each pixel blends the nearest pixels outside it, across and down,
    weighted by distance (a Coons patch), then softened. Enough for what's behind the time: shapes and gradients."""
    _, ImageFilter = _pil()
    w, h = img.size
    x0, y0, x1, y1 = max(0, rect[0]), max(0, rect[1]), min(w, rect[2]), min(h, rect[3])
    if x0 >= x1 or y0 >= y1:
        return img
    px = img.load()
    left = [px[x0 - 1, y] if x0 > 0 else None for y in range(y0, y1)]
    right = [px[x1, y] if x1 < w else None for y in range(y0, y1)]
    top = [px[x, y0 - 1] if y0 > 0 else None for x in range(x0, x1)]
    bottom = [px[x, y1] if y1 < h else None for x in range(x0, x1)]
    for j, y in enumerate(range(y0, y1)):
        for i, x in enumerate(range(x0, x1)):
            parts = []
            for edge, d in ((left[j], i + 1), (right[j], x1 - x), (top[i], j + 1), (bottom[i], y1 - y)):
                if edge is not None:
                    parts.append((edge, 1.0 / d))
            total = sum(wt for _, wt in parts)
            px[x, y] = tuple(round(sum(c[k] * wt for c, wt in parts) / total) for k in range(3))
    # Soften the patch only, from a little around it, so its seams don't show.
    pad = max(2, (x1 - x0) // 20)
    around = (max(0, x0 - pad), max(0, y0 - pad), min(w, x1 + pad), min(h, y1 + pad))
    soft = img.crop(around).filter(ImageFilter.GaussianBlur(pad))
    img.paste(soft.crop((x0 - around[0], y0 - around[1], x1 - around[0], y1 - around[1])), (x0, y0))
    return img


def make(img, whole=False, width=WIDTH):
    """The top shelf image from an Apple TV screenshot (or, whole, from a picture that's the art already), no wider
    than width. A picture wider than the space above tvOS's dock (an app's Top Shelf Image Wide) loses its sides as
    on tvOS."""
    Image, _ = _pil()
    img = img.convert("RGB")
    if not whole:
        check_capture(img.size)
        img = patch(img, box(img.size, CLOCK))
        img = img.crop(box(img.size, (0, 0, 1, SHELF_BOTTOM)))
    elif img.width > img.height * SHELF_ASPECT:
        w = round(img.height * SHELF_ASPECT)
        img = img.crop(((img.width - w) // 2, 0, (img.width - w) // 2 + w, img.height))
    if img.width > width:
        img = img.resize((width, max(1, round(img.height * width / img.width))), Image.LANCZOS)
    return img


def encode(img):
    """JPEG at quality 95 with full-resolution colour: as sharp as a PNG to the eye, a tenth of its size, and
    quicker for the TV to decode."""
    out = io.BytesIO()
    img.save(out, "JPEG", quality=95, subsampling=0, optimize=True)
    return out.getvalue()


def remote_path(pkg, ext=".jpg"):
    return "%s/%s%s" % (REMOTE_DIR, pkg, ext)


def show_home(dev):
    """Away from Home and back: the launcher looks for new top shelf images whenever Home shows."""
    dev.shell("am start -W -a android.settings.SETTINGS")
    time.sleep(1.5)
    dev.shell("am start -W -n " + ACTIVITY)


def install(dev, data, pkg):
    """Puts the image on the TV, replacing any other, and checks it arrived whole."""
    final = remote_path(pkg)
    part = final + ".part"
    dev.shell("mkdir -p %s && rm -f %s" % (REMOTE_DIR, " ".join(remote_path(pkg, t) for t in TYPES[1:])))
    if dev.native_push:
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as f:
            f.write(data)
        try:
            dev.push(f.name, part)
        finally:
            os.remove(f.name)
    elif not b64push.push_base64(dev, data, part):
        raise tv.TvError("couldn't send the image")
    got = dev.shell("md5sum %s" % part).split()
    if not got or got[0] != hashlib.md5(data).hexdigest():
        dev.shell("rm -f %s" % part)
        raise tv.TvError("the image arrived damaged; nothing changed on the TV")
    # In one step, so the launcher never reads half an image.
    dev.shell("mv -f %s %s" % (part, final))
    return final


def remove(dev, pkg):
    dev.shell("rm -f %s" % " ".join(remote_path(pkg, t) for t in TYPES))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog="\n\n".join(__doc__.split("\n\n")[1:]))
    p.add_argument("image", nargs="?", help="an Apple TV screenshot with the app focused in the top row")
    p.add_argument("--package", default=YOUTUBE, help="the app's package (default: YouTube's, %(default)s)")
    p.add_argument("--whole", action="store_true", help="the picture is the art already: don't crop it")
    p.add_argument("--out", help="write the image here instead of putting it on the TV")
    p.add_argument("--remove", action="store_true", help="take the app's image off the TV")
    p.add_argument("--serial", help="the TV's adb serial (else ANDROID_SERIAL, or the only device)")
    a = p.parse_args(argv)
    if a.remove == bool(a.image):
        p.error("give a screenshot, or --remove")
    data = None
    if a.image:
        Image, _ = _pil()
        try:
            with Image.open(a.image) as src:
                img = make(src, whole=a.whole)
        except (OSError, ValueError) as e:
            sys.exit("top_shelf_image.py: %s" % e)
        if img.width < WIDTH:
            print("note: the image is %d pixels wide, less than the screen's %d: it will look soft. A screenshot "
                  "from Xcode is 3840 wide." % (img.width, WIDTH), file=sys.stderr)
        data = encode(img)
        if a.out:
            with open(a.out, "wb") as f:
                f.write(data)
            print("wrote %s (%dx%d)" % (a.out, img.width, img.height))
            return
    try:
        dev = tv.connect(serial=a.serial)
        try:
            if a.remove:
                remove(dev, a.package)
                print("removed %s's top shelf image" % a.package)
            else:
                print("put %dx%d on the TV: %s" % (img.width, img.height, install(dev, data, a.package)))
            show_home(dev)
        finally:
            dev.close()
    except tv.TvError as e:
        sys.exit("top_shelf_image.py: %s" % e)


if __name__ == "__main__":
    main()
