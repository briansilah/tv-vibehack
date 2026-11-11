#!/usr/bin/env bash
# Push a theme without stopping the Home-button service; device selection is tools/tv.py's.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PY="$HERE/venv/bin/python"
[ -x "$PY" ] || PY="$HERE/venv/Scripts/python.exe"
[ -x "$PY" ] || PY=python3
export MSYS_NO_PATHCONV=1
exec "$PY" "$HERE/theme.py" "$@"
