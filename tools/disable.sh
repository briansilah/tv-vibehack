#!/usr/bin/env bash
# Disable every package listed in a debloat file on the TV (one package per line, # comments allowed).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same file>.
#
# Usage: tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt                       # adb binary
#        TV_HOST=192.168.1.50 tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt  # adb_shell, port 5555
# tools/tv.py explains how it picks the connection (ANDROID_SERIAL, TV_TRANSPORT).
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: disable.sh <debloat list>}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | awk '{print "pm disable-user --user 0 " $1}'
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
