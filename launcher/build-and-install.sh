#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
#
# Google TV (STOCK_LAUNCHER=com.google.android.apps.tv.launcherx): the stock launcher stays enabled by default,
# because the quick settings panel that the remote's Settings button opens lives inside it. An accessibility
# service (StockLauncherBridge) keeps this launcher on Home instead. KEEP_STOCK_LAUNCHER=0 disables the stock
# launcher like on other TVs: Home then needs no accessibility service, but the quick settings panel is gone.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
if [ "$STOCK_LAUNCHER" = com.google.android.apps.tv.launcherx ]; then
  KEEP_STOCK_LAUNCHER="${KEEP_STOCK_LAUNCHER:-1}"
else
  KEEP_STOCK_LAUNCHER="${KEEP_STOCK_LAUNCHER:-0}"
fi
BRIDGE=com.gathin.tvhome/.StockLauncherBridge
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
APK=app/build/outputs/apk/debug/app-debug.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
{
  printf '%s\n' \
    "pm install -r /data/local/tmp/tvhome.apk" \
    "rm -f /data/local/tmp/tvhome.apk" \
    "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted"
  if [ "$KEEP_STOCK_LAUNCHER" = 1 ]; then
    # Keep the stock launcher (and its quick settings panel); the bridge brings this launcher back over its home.
    # Appends to enabled_accessibility_services instead of overwriting other services.
    printf '%s\n' \
      "pm enable $STOCK_LAUNCHER" \
      's=$(settings get secure enabled_accessibility_services); case "$s" in *StockLauncherBridge*) ;; null|"") settings put secure enabled_accessibility_services '"$BRIDGE"' ;; *) settings put secure enabled_accessibility_services "$s:'"$BRIDGE"'" ;; esac; settings put secure accessibility_enabled 1; echo bridge-enabled'
  else
    printf '%s\n' \
      "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
      "pm disable-user --user 0 $STOCK_LAUNCHER"
  fi
} | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."
