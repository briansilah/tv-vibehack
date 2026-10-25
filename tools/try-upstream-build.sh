#!/usr/bin/env bash
# Runs a walkthrough plan on upstream's launcher build, then puts the fork's launcher and the owner's saved settings
# back, whatever happens in between: /test-on-tv's "Upstream proposals" (.github/skills/test-on-tv).
#
# Usage: ANDROID_SERIAL=<device> tools/try-upstream-build.sh <fork debug apk> <upstream debug apk> <plan.json> <out dir> [walkthrough.py run options, such as --tv --other-app PKG]
#
# Both APKs must be debug builds: run-as reads and writes the saved settings only on a debuggable app, and Android
# lets the fork's build back over upstream's newer versionCode (install -r -d) only because upstream's is
# debuggable. It ends with the fork's debug build installed and the settings exactly as they were; install the
# fork's release build after it (launcher/build-and-install.sh from a checkout of main).
set -uo pipefail
[ $# -ge 4 ] || { sed -n '4,4p' "$0" >&2; exit 2; }
FORK=$1 UPSTREAM=$2 PLAN=$3 OUT=$4
shift 4
WALK_ARGS=("$@")
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device}"
PKG=com.gathin.tvhome
TOOLS="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb")}"
OTHER=com.android.tv.settings/.MainSettings
for ((i = 0; i < ${#WALK_ARGS[@]}; i++)); do
  [ "${WALK_ARGS[$i]}" = --other-app ] && OTHER=$("$ADB" -s "$ANDROID_SERIAL" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER "${WALK_ARGS[$((i + 1))]}" | tail -1)
done
a() { "$ADB" -s "$ANDROID_SERIAL" "$@"; }
mkdir -p "$OUT/prefs"

echo "1. the fork's debug build, to back up the saved settings"
a install -r "$FORK" | tail -1 || exit 1
a shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
a shell run-as $PKG ls shared_prefs > "$OUT/prefs-files.txt" 2>/dev/null || true
if grep -q -i -E 'run-as|denied|not debuggable' "$OUT/prefs-files.txt"; then
  echo "run-as can't read the settings: the fork's APK must be a debug build" >&2; exit 1
fi
while read -r f; do
  [ -n "$f" ] && a shell run-as $PKG cat "shared_prefs/$f" > "$OUT/prefs/$f"
done < "$OUT/prefs-files.txt"
echo "   backed up: $(tr '\n' ' ' < "$OUT/prefs-files.txt")"

restore() {
  echo "3. the fork's build again, and the settings as they were"
  a install -r -d "$FORK" | tail -1
  a shell am start -n "$OTHER" >/dev/null 2>&1; sleep 2; a shell am kill $PKG
  for f in $(a shell run-as $PKG ls shared_prefs 2>/dev/null); do
    grep -qx "$f" "$OUT/prefs-files.txt" || a shell run-as $PKG rm "shared_prefs/$f"
  done
  local ok=1
  while read -r f; do
    [ -n "$f" ] || continue
    a shell "run-as $PKG sh -c 'echo $(base64 < "$OUT/prefs/$f" | tr -d '\n') | base64 -d > shared_prefs/$f'"
    cmp -s <(a shell run-as $PKG cat "shared_prefs/$f") "$OUT/prefs/$f" || { ok=0; echo "   NOT RESTORED: $f" >&2; }
  done < "$OUT/prefs-files.txt"
  a shell am start -n $PKG/.MainActivity >/dev/null
  if [ $ok = 1 ]; then echo "   settings-restored"; else echo "   SETTINGS NOT RESTORED: tell Brian" >&2; RESULT=3; fi
}
RESULT=1
trap restore EXIT

echo "2. upstream's build, and the walkthrough"
if a install -r "$UPSTREAM" | tail -1 | grep -q Success; then
  python3 "$TOOLS/walkthrough.py" run "$PLAN" --out "$OUT/walk" "${WALK_ARGS[@]}"
  RESULT=$?
fi
trap - EXIT
restore
exit $RESULT
