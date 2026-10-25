# Source at the start of each shell command (each starts fresh): . tools/devenv.sh
# JDK 17, the Android SDK, adb and the emulator for this checkout, launcher/local.properties, and never TV_HOST.
# In a worktree bound to an emulator (tools/emulator.py start), ANDROID_SERIAL becomes that emulator.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
unset TV_HOST
_tvhome_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
[ -f "$_tvhome_root/launcher/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$_tvhome_root/launcher/local.properties"
if [ -f "$_tvhome_root/.tvhome-device" ]; then
  export ANDROID_SERIAL="$(cat "$_tvhome_root/.tvhome-device")"
fi
unset _tvhome_root
