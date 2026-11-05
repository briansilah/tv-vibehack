# Build and install through tools/tv.py. Google TV keeps launcherx and HomeButtonService.
# Other TVs: detect a known stock Home app, or pass -StockLauncher <package>|none.
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$Serial,
    [string]$StockLauncher = '',
    [ValidateSet('release', 'debug')]
    [string]$BuildType = 'release'
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$savedEnv = @{}
foreach ($name in 'TV_HOST', 'ANDROID_SERIAL', 'TV_TRANSPORT') { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
if ($TvHost) { $env:TV_HOST = $TvHost }
if ($Serial) { $env:ANDROID_SERIAL = $Serial; $env:TV_TRANSPORT = 'adb' }
try {
    Set-Location $PSScriptRoot
    $py = '..\tools\venv\Scripts\python.exe'
    if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
    [xml]$config = Get-Content -Raw 'app\src\main\res\values\config.xml'
    $inputKeyService = $config.SelectSingleNode('/resources/bool[@name="input_key_service"]').InnerText.Trim() -eq 'true'
    $homeKeyService = $config.SelectSingleNode('/resources/bool[@name="home_key_service"]').InnerText.Trim() -eq 'true'
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
    $model = & $py -I ..\tools\tv.py 'getprop ro.product.model'
    if ($LASTEXITCODE -ne 0) { throw 'could not reach the TV, see the message above' }
    Write-Host "TV: $model"
    $googleTvLauncher = 'com.google.android.apps.tv.launcherx'
    $googlePath = & $py -I ..\tools\tv.py "pm path $googleTvLauncher"
    if ($LASTEXITCODE -ne 0) { throw 'could not query Google TV launcher' }
    $googleTv = "$googlePath" -match 'package:'
    if (-not $googleTv -and -not $StockLauncher) {
        $home = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
        if ($LASTEXITCODE -ne 0 -or -not $home) { throw 'could not resolve Home; stock launcher untouched' }
        $homePkg = "$($home | Select-Object -Last 1)".Trim().Split('/')[0]
        if (@('com.google.android.tvlauncher', 'com.google.android.leanbacklauncher') -contains $homePkg) { $StockLauncher = $homePkg }
        elseif ($homePkg -eq 'com.gathin.tvhome') { $StockLauncher = 'none' }
        else { throw "Home resolves to '$homePkg', not a recognised stock launcher. Use -StockLauncher <package> or -StockLauncher none." }
    }
    $task = if ($BuildType -eq 'debug') { 'assembleDebug' } else { 'assembleRelease' }
    .\gradlew.bat $task -q --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
    $apk = "app\build\outputs\apk\$BuildType\app-$BuildType.apk"
    & $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
    if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
    $out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
    Write-Host $out
    if ($LASTEXITCODE -ne 0 -or "$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
    @(
        'cmd package compile -m speed -f com.gathin.tvhome >/dev/null 2>&1; echo compiled',
        'pm grant com.gathin.tvhome android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; echo coarse-location-granted',
        'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
        'pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS 2>/dev/null; echo tv-listings-granted'
    ) | & $py -I ..\tools\tv.py --batch
    if ($LASTEXITCODE -ne 0) { throw 'post-install grants failed' }
    if ($googleTv) {
        & $py -I ..\tools\tv.py "pm enable $googleTvLauncher"
        if ($LASTEXITCODE -ne 0) { throw 'could not enable Google TV launcher' }
    } else {
        $out = & $py -I ..\tools\tv.py 'am start -W -n com.gathin.tvhome/.MainActivity'
        if ($LASTEXITCODE -ne 0 -or "$out" -notmatch 'Status: ok') { throw 'launcher did not start; stock launcher untouched' }
        if ($StockLauncher -ne 'none') {
            & $py -I ..\tools\tv.py "pm disable-user --user 0 $StockLauncher"
            if ($LASTEXITCODE -ne 0) { throw 'could not disable stock launcher' }
        }
        & $py -I ..\tools\tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
        $homeSet = $LASTEXITCODE -eq 0
        $home = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
        $resolved = "$($home | Select-Object -Last 1)".Trim()
        if (-not $homeSet -or $LASTEXITCODE -ne 0 -or $resolved -notlike 'com.gathin.tvhome/*') {
            if ($StockLauncher -ne 'none') {
                & $py -I ..\tools\tv.py "pm enable --user 0 $StockLauncher"
                if ($LASTEXITCODE -ne 0) { throw 'Home switch failed and stock launcher restore failed' }
            }
            throw "Home switch failed ($resolved); stock launcher restored if disabled."
        }
    }
    if ($googleTv -or $inputKeyService -or $homeKeyService) {
        @(
            'appops set com.gathin.tvhome AUTO_START allow 2>/dev/null; appops write-settings; echo auto-start',
            'pm grant com.gathin.tvhome android.permission.DUMP 2>/dev/null; echo dump-granted',
            's=$(settings get secure enabled_accessibility_services); case "$s" in *com.gathin.tvhome/.HomeButtonService*) ;; null|"") settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeButtonService ;; *) settings put secure enabled_accessibility_services "$s:com.gathin.tvhome/.HomeButtonService" ;; esac; settings put secure accessibility_enabled 1; echo home-button-service-on',
            'am start -n com.gathin.tvhome/.MainActivity'
        ) | & $py -I ..\tools\tv.py --batch
        if ($LASTEXITCODE -ne 0) { throw 'HomeButtonService setup failed' }
    }
    Write-Host 'Installed. Press Home on the remote to see it.'
} finally {
    foreach ($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name]) }
}
