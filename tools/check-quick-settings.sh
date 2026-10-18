#!/usr/bin/env bash
# Read-only check of Google TV's quick settings panel (the dashboard the remote's Settings button opens) with
# this launcher installed. Changes nothing on the TV: it only reads package state, settings, dumpsys and logcat.
#
# Usage: TV_HOST=192.168.1.50 tools/check-quick-settings.sh
#        USE_ADB=1 [ADB_SERIAL=192.168.1.50:5555] tools/check-quick-settings.sh   # use the adb binary instead
#
# Half-way through it asks you to press the remote's Settings button once, then shows which key the TV saw
# and which activity opened. Paste the output into an issue or PR when something doesn't work.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
export MSYS_NO_PATHCONV=1
STOCK="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"

if [ "${USE_ADB:-0}" = 1 ]; then
  tv() { adb ${ADB_SERIAL:+-s "$ADB_SERIAL"} shell "$1"; }
else
  PY=tools/venv/bin/python
  [ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
  [ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt (or USE_ADB=1)"; exit 1; }
  : "${TV_HOST:?set TV_HOST to the TV IP address, or USE_ADB=1}"
  tv() { "$PY" -I tools/tv.py "$1"; }
fi
section() { printf '\n== %s\n' "$1"; }

section "TV"
tv 'echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model) / $(getprop ro.product.device) / Android $(getprop ro.build.version.release) / $(getprop ro.build.display.id)"'

section "Stock launcher $STOCK (must be enabled for the quick settings panel)"
tv "pm list packages -e $STOCK | grep -qx package:$STOCK && echo enabled || echo 'DISABLED or missing'"

section "Where Home resolves (Google TV's launcher wins while enabled; the bridge then brings this launcher back)"
tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1'

section "Quick settings panel handler (expect $STOCK/...dashboard.DashboardHandler)"
tv 'cmd package query-activities --brief -a android.app.action.OPEN_NOTIFICATION_HANDLER_PANEL'

section "Launcher's Home bridge (expect com.gathin.tvhome/.StockLauncherBridge in the list, accessibility_enabled=1)"
tv 'echo "services=$(settings get secure enabled_accessibility_services) accessibility_enabled=$(settings get secure accessibility_enabled)"'
tv 'dumpsys accessibility | grep -i -E "Bound services|Enabled services|tvhome" | head -n 8'

section "Installed versions"
tv "dumpsys package com.gathin.tvhome | grep -m1 versionName; dumpsys package $STOCK | grep -m1 versionName"

section "Remote keys the system routes to an app (global keys)"
tv 'dumpsys window policy | sed -n "/mKeyMapping/,/}/p"'

section "Press test"
since=$(tv "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
read -r -p "Press the remote's Settings button once (wait for the panel or ~3 s), then press Enter here... " _
section "Last keys the TV received (look for KEYCODE_SETTINGS or similar)"
tv 'dumpsys input | grep -A 12 "RecentQueue:" | grep -o "keyCode=[^,]*" | tail -n 6'
section "Activities started and panel logs since $since"
tv "logcat -d -t '$since' 2>/dev/null | grep -E 'START u0|NOTIFICATION_HANDLER|Dashboard|StatusBar|GlobalKey|StockLauncherBridge|tvhome' | tail -n 40"
echo
echo "Done. Nothing on the TV was changed."
