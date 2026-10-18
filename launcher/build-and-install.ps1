# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher com.google.android.tvlauncher] [-KeepStockLauncher 0|1]
#
# Google TV (-StockLauncher com.google.android.apps.tv.launcherx): the stock launcher stays enabled by default,
# because the quick settings panel that the remote's Settings button opens lives inside it. An accessibility
# service (StockLauncherBridge) keeps this launcher on Home instead. -KeepStockLauncher 0 disables the stock
# launcher like on other TVs: Home then needs no accessibility service, but the quick settings panel is gone.
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = 'com.google.android.tvlauncher',
    [string]$KeepStockLauncher = $env:KEEP_STOCK_LAUNCHER
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
if (-not $KeepStockLauncher) {
    $KeepStockLauncher = if ($StockLauncher -eq 'com.google.android.apps.tv.launcherx') { '1' } else { '0' }
}
$bridge = 'com.gathin.tvhome/.StockLauncherBridge'
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

.\gradlew.bat assembleDebug -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
$apk = 'app\build\outputs\apk\debug\app-debug.apk'
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
if ($KeepStockLauncher -eq '1') {
    # Keep the stock launcher (and its quick settings panel); the bridge brings this launcher back over its home.
    # The service only reacts to windows coming to the front, so open this launcher once at the end.
    # Appends to enabled_accessibility_services instead of overwriting other services.
    $enableBridge = 's=$(settings get secure enabled_accessibility_services); case "$s" in *StockLauncherBridge*) ;; null|"") settings put secure enabled_accessibility_services ' + $bridge + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $bridge + '" ;; esac; settings put secure accessibility_enabled 1; echo bridge-enabled'
    @(
        'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
        "pm enable $StockLauncher",
        $enableBridge,
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
