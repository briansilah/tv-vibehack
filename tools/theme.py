#!/usr/bin/env python3
"""Push a theme folder to Home, or --reset to remove it, without stopping its accessibility service."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import b64push  # noqa: E402
import tv  # noqa: E402

PACKAGE = "com.gathin.tvhome"
REMOTE_DIR = "/sdcard/Android/data/%s/files" % PACKAGE
FILES = ("config.json", "wallpaper.jpg")
RELOAD = ("am start -W -n %s/.MainActivity --ez default_config false "
          "--ez com.gathin.tvhome.RELOAD_THEME true" % PACKAGE)


def read_theme(folder):
    """Validate before connecting; snapshot both files so uploads cannot race local edits."""
    folder = Path(folder)
    config = (folder / FILES[0]).read_bytes()
    if len(config) > 64 * 1024:
        raise ValueError("config.json is larger than the launcher's 64 KB limit")
    parsed = json.loads(config.decode("utf-8-sig"))
    if not isinstance(parsed, dict):
        raise ValueError("config.json must contain a JSON object")
    if "rows_below_dock" in parsed:
        raise ValueError("rows_below_dock is not supported by this fork's Top Shelf")
    wall = folder / FILES[1]
    return {FILES[0]: config, FILES[1]: wall.read_bytes() if wall.is_file() else None}


def checked_shell(dev, command):
    marker = "tvhome_theme_ok"
    out = dev.shell(command + " && printf '\\n" + marker + "\\n'")
    if not out.rstrip().endswith("\n" + marker) and out.strip() != marker:
        raise tv.TvError("theme command failed: %s\n%s" % (command, out.strip()))
    return out.rsplit(marker, 1)[0].strip()


def apply(dev, files):
    """Stage and verify both files before replacing the current theme and reloading Home."""
    checked_shell(dev, "mkdir -p " + REMOTE_DIR)
    try:
        for name, data in files.items():
            if data is None:
                continue
            remote = "%s/%s.part" % (REMOTE_DIR, name)
            if dev.native_push:
                with tempfile.NamedTemporaryFile(delete=False) as f:
                    f.write(data)
                try:
                    dev.push(f.name, remote)
                finally:
                    os.remove(f.name)
            elif not b64push.push_base64(dev, data, remote):
                raise tv.TvError("could not send " + name)
            got = checked_shell(dev, "md5sum " + remote).split()
            if not got or got[0] != hashlib.md5(data).hexdigest():
                raise tv.TvError(name + " arrived damaged; the current theme was not replaced")
        for name in reversed(FILES):
            final = "%s/%s" % (REMOTE_DIR, name)
            checked_shell(dev, "mv -f %s.part %s" % (final, final) if files[name] is not None
                          else "rm -f " + final)
    finally:
        checked_shell(dev, "rm -f " + " ".join(
            "%s/%s%s" % (REMOTE_DIR, name, suffix) for name in FILES for suffix in (".part", ".part.b64")))


def reload_home(dev):
    out = dev.shell(RELOAD)
    if "Status: ok" not in out:
        raise tv.TvError("theme files changed, but Home did not confirm its restart:\n" + out.strip())


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("theme", nargs="?", help="folder containing config.json and optionally wallpaper.jpg")
    p.add_argument("--reset", action="store_true", help="remove only the two theme files")
    p.add_argument("--serial", help="adb device, unless this worktree is bound to an emulator")
    a = p.parse_args(argv)
    if a.reset == bool(a.theme):
        p.error("give a theme folder, or --reset")
    try:
        files = read_theme(a.theme) if a.theme else None
        dev = tv.connect(serial=a.serial)
        try:
            if a.reset:
                checked_shell(dev, "rm -f " + " ".join(REMOTE_DIR + "/" + name for name in FILES))
            else:
                apply(dev, files)
            reload_home(dev)
        finally:
            dev.close()
    except (OSError, ValueError, tv.TvError) as e:
        sys.exit("theme.py: %s" % e)
    print("Theme %s; Home reloaded without stopping its service." % ("reset" if a.reset else "applied"))


if __name__ == "__main__":
    main()
