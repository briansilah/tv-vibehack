#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
#
# It builds a release APK (R8, not debuggable, signed with your local debug key) and compiles it ahead of time on
# the TV, which makes it much faster than a debug build. BUILD_TYPE=debug builds and installs a debuggable one.
#
# Google TV (com.google.android.apps.tv.launcherx on the TV): the stock launcher stays enabled whatever
# STOCK_LAUNCHER says. The quick settings panel that the remote's Settings button opens lives inside it, and on a
# TCL Google TV disabling it makes Home loop until the system crashes. HomeButtonService, an accessibility
# service, keeps Home on this launcher instead.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
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
if [ "$BUILD_TYPE" = release ]; then ./gradlew assembleRelease -q --console=plain; else ./gradlew assembleDebug -q --console=plain; fi
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
APK=app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
GOOGLE_TV=0
case "$("$PY" -I ../tools/tv.py "pm path $GOOGLE_TV_LAUNCHER" || true)" in *package:*) GOOGLE_TV=1 ;; esac
{
  printf '%s\n' \
    "pm install -r /data/local/tmp/tvhome.apk" \
    "rm -f /data/local/tmp/tvhome.apk" \
    "cmd package compile -m speed -f com.gathin.tvhome >/dev/null 2>&1; echo compiled" \
    "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted"
  if [ "$GOOGLE_TV" = 1 ]; then
    # Re-enable it in case an older version of this script disabled it.
    echo "pm enable $GOOGLE_TV_LAUNCHER"
    # TCL only lets the system start an app's process for a service with its AUTO_START app op allowed; other
    # TVs have no such op. Allow it before turning the service on, or TCL blocks the first bind. App ops are saved
    # lazily (up to 30 min later) and not on `adb reboot`, so save now or the next boot forgets it.
    echo "appops set com.gathin.tvhome AUTO_START allow 2>/dev/null; appops write-settings; echo auto-start"
    # Add the service to enabled_accessibility_services, keeping any others.
    cat <<'SH'
s=$(settings get secure enabled_accessibility_services); case "$s" in *com.gathin.tvhome/.HomeButtonService*) ;; null|"") settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeButtonService ;; *) settings put secure enabled_accessibility_services "$s:com.gathin.tvhome/.HomeButtonService" ;; esac; settings put secure accessibility_enabled 1; echo home-button-service-on
SH
    # Home still resolves to the stock launcher, so open this one now.
    echo "am start -n com.gathin.tvhome/.MainActivity"
  else
    printf '%s\n' \
      "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
      "pm disable-user --user 0 $STOCK_LAUNCHER"
  fi
} | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote to see it."
