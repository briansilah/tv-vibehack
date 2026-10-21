#!/usr/bin/env bash
# Re-enable every package listed in a debloat file, restore the stock launcher as home and reset
# animation scales. Pass the same list you used with tools/disable.sh.
#
# Usage: tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt [stock.launcher.package]  # adb binary
#        TV_HOST=192.168.1.50 tools/restore.sh ...                                        # adb_shell, port 5555
# tools/tv.py explains how it picks the connection (ANDROID_SERIAL, TV_TRANSPORT).
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: restore.sh <debloat list> [stock launcher package]}"
STOCK="${2:-com.google.android.tvlauncher}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
{
  { grep -vE '^\s*(#|$)' "$LIST" || [ $? = 1 ]; } | sed -E 's/\s+#.*$//' | awk '{print "pm enable " $1}'
  echo "pm enable $STOCK"
  # Turn off the launcher's Home-button service (Google TV), keeping any other accessibility services.
  cat <<'SH'
s=$(settings get secure enabled_accessibility_services | sed -E 's#(^|:)com\.gathin\.tvhome/[^:]*##g; s#^:##'); if [ -z "$s" ] || [ "$s" = null ]; then settings delete secure enabled_accessibility_services; else settings put secure enabled_accessibility_services "$s"; fi; echo home-button-service-off
SH
  echo "appops set com.gathin.tvhome AUTO_START default 2>/dev/null; appops write-settings; echo auto-start-default"
  echo "pm revoke com.gathin.tvhome android.permission.DUMP 2>/dev/null; echo dump-revoked"
  # resolve the stock HOME component on the TV (Android 9 needs pkg/activity, not just pkg)
  echo "cmd package set-home-activity --user 0 \$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK | tail -n 1)"
  echo "settings put global window_animation_scale 1.0"
  echo "settings put global transition_animation_scale 1.0"
  echo "settings put global animator_duration_scale 1.0"
  echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
