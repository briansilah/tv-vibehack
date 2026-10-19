# Windows version of disable.sh: disable every package listed in a debloat file on the TV
# (one package per line, # comments allowed). Packages are disabled for user 0, never uninstalled.
# Undo with tools\restore.ps1 <same file>.
#
# Usage: tools\disable.ps1 examples\xiaomi-mi-tv-4a\debloat.txt                       # adb binary
#        tools\disable.ps1 -TvHost 192.168.1.50 examples\xiaomi-mi-tv-4a\debloat.txt  # adb_shell, port 5555
#        tools\disable.ps1 -Serial <adb serial> examples\...                           # one of several adb devices
# tools\tv.py explains how it picks the connection.
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$List,
    [string]$TvHost = $env:TV_HOST,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
$repo = Split-Path -Parent $PSScriptRoot
$List = (Resolve-Path $List).Path
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }
# Connection settings for tv.py, put back afterwards so they don't stick to the PowerShell session.
$savedEnv = @{}
foreach ($name in 'TV_HOST', 'ANDROID_SERIAL', 'TV_TRANSPORT') { $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name) }
if ($TvHost) { $env:TV_HOST = $TvHost }
if ($Serial) { $env:ANDROID_SERIAL = $Serial; $env:TV_TRANSPORT = 'adb' }
try {
    $pkgs = Get-Content $List | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
    $cmds = @($pkgs | ForEach-Object { "pm disable-user --user 0 $_" }) + 'pm list packages -d'
    $cmds | & $py -I (Join-Path $repo 'tools\tv.py') --batch
    exit $LASTEXITCODE
} finally {
    foreach ($name in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnv[$name]) }
}
