#!/usr/bin/env bash
# What .github/workflows/ci.yml runs, run here: GitHub Actions is off on this fork, so sessions run this instead of
# waiting for checks that never come. Keep it in step with ci.yml (the upstream sync merges both).
# Usage: tools/ci.sh    (from anywhere in the checkout; build only while you hold an emulator slot)
set -euo pipefail
cd "$(dirname "$0")/.."
. tools/devenv.sh
(cd launcher && ./gradlew -q --console=plain assembleDebug assembleRelease testDebugUnitTest)
PY=tools/venv/bin/python
[ -x "$PY" ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q --disable-pip-version-check -r tools/requirements.txt; }
"$PY" -m py_compile tools/*.py
for f in tools/*.sh launcher/*.sh; do bash -n "$f"; done
"$PY" -m unittest discover -s tools/tests
echo "ci.sh: every check in ci.yml passed at $(git rev-parse --short HEAD)$(git diff --quiet HEAD -- . ':!tools/venv' || echo ' (with uncommitted changes)')"
