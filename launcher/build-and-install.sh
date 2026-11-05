#!/usr/bin/env bash
# Build and install the release launcher; BUILD_TYPE=debug installs the debuggable variant.
# Uses the device selection in tools/tv.py (adb, ANDROID_SERIAL or TV_HOST).
# Google TV's launcher stays enabled for Quick Settings and safe HomeButtonService takeover.
# Other TVs: detect a known stock Home app, or pass STOCK_LAUNCHER=<package>|none.
set -euo pipefail
cd "$(dirname "$0")"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-}"
BUILD_TYPE="${BUILD_TYPE:-release}"
case "$BUILD_TYPE" in
  release|debug) ;;
  *) echo "BUILD_TYPE must be release or debug" >&2; exit 1 ;;
esac
GOOGLE_TV_LAUNCHER=com.google.android.apps.tv.launcherx
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
INPUT_KEY_SERVICE=$("$PY" -c 'import xml.etree.ElementTree as E; print(E.parse("app/src/main/res/values/config.xml").find("bool[@name=\"input_key_service\"]").text.strip())')
HOME_KEY_SERVICE=$("$PY" -c 'import xml.etree.ElementTree as E; print(E.parse("app/src/main/res/values/config.xml").find("bool[@name=\"home_key_service\"]").text.strip())')
TV=$("$PY" -I ../tools/tv.py 'echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model), Android $(getprop ro.build.version.release)"')
echo "TV: $TV"
GOOGLE_TV=0
GOOGLE_PATH=$("$PY" -I ../tools/tv.py "pm path $GOOGLE_TV_LAUNCHER")
case "$GOOGLE_PATH" in *package:*) GOOGLE_TV=1 ;; esac
if [ "$GOOGLE_TV" = 0 ] && [ -z "$STOCK_LAUNCHER" ]; then
  HOME_NOW=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
  HOME_PKG=$(printf '%s\n' "$HOME_NOW" | tail -n 1 | tr -d '\r' | cut -d/ -f1)
  case "$HOME_PKG" in
    com.google.android.tvlauncher|com.google.android.leanbacklauncher) STOCK_LAUNCHER=$HOME_PKG ;;
    com.gathin.tvhome) STOCK_LAUNCHER=none ;;
    *) echo "ERROR: Home resolves to '${HOME_PKG:-nothing}', not a recognised stock launcher. Use STOCK_LAUNCHER=<package> or STOCK_LAUNCHER=none." >&2; exit 1 ;;
  esac
fi
if [ "$BUILD_TYPE" = release ]; then ./gradlew assembleRelease -q --console=plain; else ./gradlew assembleDebug -q --console=plain; fi
APK=app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
INSTALL=$("$PY" -I ../tools/tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk')
echo "$INSTALL"
case "$INSTALL" in *Success*) ;; *) echo "ERROR: pm install failed; stock launcher untouched" >&2; exit 1 ;; esac
{
  printf '%s\n' \
    "cmd package compile -m speed -f com.gathin.tvhome >/dev/null 2>&1; echo compiled" \
    "pm grant com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; echo coarse-location-granted" \
    "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
    "pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS 2>/dev/null; echo tv-listings-granted"
  if [ "$GOOGLE_TV" = 1 ]; then
    echo "pm enable $GOOGLE_TV_LAUNCHER"
  fi
  if [ "$GOOGLE_TV" = 1 ] || [ "$INPUT_KEY_SERVICE" = true ] || [ "$HOME_KEY_SERVICE" = true ]; then
    echo "appops set com.gathin.tvhome AUTO_START allow 2>/dev/null; appops write-settings; echo auto-start"
    echo "pm grant com.gathin.tvhome android.permission.DUMP 2>/dev/null; echo dump-granted"
    cat <<'SH'
s=$(settings get secure enabled_accessibility_services); case "$s" in *com.gathin.tvhome/.HomeButtonService*) ;; null|"") settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeButtonService ;; *) settings put secure enabled_accessibility_services "$s:com.gathin.tvhome/.HomeButtonService" ;; esac; settings put secure accessibility_enabled 1; echo home-button-service-on
SH
    echo "am start -n com.gathin.tvhome/.MainActivity"
  fi
} | "$PY" -I ../tools/tv.py --batch
if [ "$GOOGLE_TV" = 0 ]; then
  STARTED=$("$PY" -I ../tools/tv.py 'am start -W -n com.gathin.tvhome/.MainActivity')
  case "$STARTED" in *"Status: ok"*) ;; *) echo "ERROR: launcher did not start; stock launcher untouched: $STARTED" >&2; exit 1 ;; esac
  {
    [ "$STOCK_LAUNCHER" = none ] || echo "pm disable-user --user 0 $STOCK_LAUNCHER"
    echo "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity"
  } | "$PY" -I ../tools/tv.py --batch || {
    [ "$STOCK_LAUNCHER" = none ] || "$PY" -I ../tools/tv.py "pm enable --user 0 $STOCK_LAUNCHER"
    echo "ERROR: Home switch failed; stock launcher restored if disabled." >&2; exit 1;
  }
  if ! HOME_NOW=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'); then
    [ "$STOCK_LAUNCHER" = none ] || "$PY" -I ../tools/tv.py "pm enable --user 0 $STOCK_LAUNCHER"
    echo "ERROR: could not verify Home; stock launcher restored if disabled." >&2; exit 1
  fi
  case "$(printf '%s\n' "$HOME_NOW" | tail -n 1 | tr -d '\r')" in
    com.gathin.tvhome/*) ;;
    *)
      [ "$STOCK_LAUNCHER" = none ] || "$PY" -I ../tools/tv.py "pm enable --user 0 $STOCK_LAUNCHER"
      echo "ERROR: Home resolves to ${HOME_NOW:-nothing}, not this launcher; stock launcher restored if disabled." >&2
      exit 1 ;;
  esac
fi
echo "Installed. Press Home on the remote to see it."
