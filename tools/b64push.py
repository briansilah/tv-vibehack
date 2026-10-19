#!/usr/bin/env python3
"""Send a file to the TV and check its md5 there.

With the adb binary this is a plain `adb push`. With adb_shell, whose own push hangs on larger files, the file
goes as base64 chunks through plain shell commands. See tools/tv.py for which one is used.

Usage: b64push.py <local file> <remote path>
"""
import base64
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tv  # noqa: E402

CHUNK = 48 * 1024  # raw bytes per shell command (base64 grows it by 4/3)


def push_base64(dev, data, remote):
    tmp = remote + ".b64"
    dev.shell(f"rm -f {remote} {tmp}")
    n = (len(data) + CHUNK - 1) // CHUNK
    for i in range(n):
        part = base64.b64encode(data[i * CHUNK:(i + 1) * CHUNK]).decode()
        out = dev.shell(f"echo '{part}' >> {tmp} && echo ok{i}", timeout=60)
        if f"ok{i}" not in out:
            print(f"chunk {i} failed: {out}")
            dev.shell(f"rm -f {tmp}")
            return False
        print(f"\rchunk {i + 1}/{n}", end="", flush=True)
    print()
    dev.shell(f"base64 -d {tmp} > {remote}; rm -f {tmp}", timeout=120)
    return True


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 2:
        sys.exit(__doc__)
    local, remote = argv
    with open(local, "rb") as f:
        data = f.read()
    want = hashlib.md5(data).hexdigest()
    try:
        dev = tv.connect()
        try:
            if dev.native_push:
                out = dev.push(local, remote).strip()
                print(out.splitlines()[-1] if out else f"pushed {local} -> {remote}")
            elif not push_base64(dev, data, remote):
                sys.exit(1)
            out = dev.shell(f"md5sum {remote}", timeout=120)
        finally:
            dev.close()
    except tv.TvError as e:
        sys.exit("b64push.py: %s" % e)
    got = out.split()[0] if out.strip() else ""
    print("md5 remote:", got, "local:", want, "MATCH" if got == want else "MISMATCH")
    sys.exit(0 if got == want else 1)


if __name__ == "__main__":
    main()
