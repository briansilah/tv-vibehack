# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher com.google.android.tvlauncher] [-BuildType debug]
#
# It builds a release APK (R8, not debuggable, signed with your local debug key) and compiles it ahead of time on
# the TV, which makes it much faster than a debug build. -BuildType debug builds and installs a debuggable one.
#
# Google TV (com.google.android.apps.tv.launcherx on the TV): the stock launcher stays enabled whatever
# -StockLauncher says. The quick settings panel that the remote's Settings button opens lives inside it, and on a
# TCL Google TV disabling it makes Home loop until the system crashes. HomeButtonService, an accessibility
# service, keeps Home on this launcher instead.
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = 'com.google.android.tvlauncher',
    [ValidateSet('release', 'debug')]
    [string]$BuildType = 'release'
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

$task = if ($BuildType -eq 'debug') { 'assembleDebug' } else { 'assembleRelease' }
.\gradlew.bat $task -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
$apk = "app\build\outputs\apk\$BuildType\app-$BuildType.apk"
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
# Compile it ahead of time now, instead of running it interpreted until the TV's idle-time compilation.
& $py -I ..\tools\tv.py 'cmd package compile -m speed -f com.gathin.tvhome >/dev/null 2>&1; echo compiled'
$googleTvLauncher = 'com.google.android.apps.tv.launcherx'
$googleTv = "$(& $py -I ..\tools\tv.py "pm path $googleTvLauncher")" -match 'package:'
if ($googleTv) {
    @(
        'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
        # Re-enable it in case an older version of this script disabled it.
        "pm enable $googleTvLauncher",
        # TCL only lets the system start an app's process for a service with its AUTO_START app op allowed; other
        # TVs have no such op. Allow it before turning the service on, or TCL blocks the first bind. App ops are saved
        # lazily (up to 30 min later) and not on `adb reboot`, so save now or the next boot forgets it.
        'appops set com.gathin.tvhome AUTO_START allow 2>/dev/null; appops write-settings; echo auto-start',
        # Add the service to enabled_accessibility_services, keeping any others.
        's=$(settings get secure enabled_accessibility_services); case "$s" in *com.gathin.tvhome/.HomeButtonService*) ;; null|"") settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeButtonService ;; *) settings put secure enabled_accessibility_services "$s:com.gathin.tvhome/.HomeButtonService" ;; esac; settings put secure accessibility_enabled 1; echo home-button-service-on',
        # Home still resolves to the stock launcher, so open this one now.
        'am start -n com.gathin.tvhome/.MainActivity'
    ) | & $py -I ..\tools\tv.py --batch
} else {
    $out = & $py -I ..\tools\tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome'
    Write-Host $out
    if ("$out" -match 'Error') { throw 'could not set the new launcher as home; the stock launcher was left untouched' }
    @(
        'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
        'cmd package set-home-activity --user 0 com.gathin.tvhome',
        "pm disable-user --user 0 $StockLauncher"
    ) | & $py -I ..\tools\tv.py --batch
}
Write-Host 'Installed. Press Home on the remote to see it.'
