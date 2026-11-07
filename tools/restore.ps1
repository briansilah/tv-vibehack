# Windows version of restore.sh: re-enable every package listed in a debloat file, restore the
# stock launcher as home and reset animation scales. Pass the same list you used with disable.ps1.
#
# Usage: tools\restore.ps1 examples\xiaomi-mi-tv-4a\debloat.txt [-Stock stock.launcher.package]  # adb binary
#        tools\restore.ps1 -TvHost 192.168.1.50 examples\...                                    # adb_shell, port 5555
#        tools\restore.ps1 -Serial <adb serial> examples\...                                    # one of several adb devices
# tools\tv.py explains how it picks the connection.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$List,
    [string]$Stock = 'com.google.android.tvlauncher',
    [string]$TvHost = $env:TV_HOST,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$repo = Split-Path -Parent $PSScriptRoot
$List = (Resolve-Path $List).Path
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
# Connection settings for tv.py, put back afterwards so they don't stick to the PowerShell session.
$savedEnv = @{}
foreach ($name in 'TV_HOST', 'ANDROID_SERIAL', 'TV_TRANSPORT') { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
if ($TvHost) { $env:TV_HOST = $TvHost }
if ($Serial) { $env:ANDROID_SERIAL = $Serial; $env:TV_TRANSPORT = 'adb' }
try {
    $pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
    $cmds = @($pkgs | ForEach-Object { "pm enable $_" }) + @(
        "pm enable $Stock",
        # Turn off the launcher's Home-button service (Google TV), keeping any other accessibility services.
        's=$(settings get secure enabled_accessibility_services | sed -E ''s#(^|:)com\.gathin\.tvhome/[^:]*##g; s#^:##''); if [ -z "$s" ] || [ "$s" = null ]; then settings delete secure enabled_accessibility_services; else settings put secure enabled_accessibility_services "$s"; fi; echo home-button-service-off',
        'appops set com.gathin.tvhome AUTO_START default 2>/dev/null; appops write-settings; echo auto-start-default',
        # Android 9 requires the resolved HOME component, not just its package.
        ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $Stock + ' | tail -n 1)'),
        'settings put global window_animation_scale 1.0',
        'settings put global transition_animation_scale 1.0',
        'settings put global animator_duration_scale 1.0',
        'pm list packages -d'
    )
    $cmds | & $py -I (Join-Path $repo 'tools\tv.py') --batch
    exit $LASTEXITCODE
} finally {
    foreach ($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name]) }
}
