# Push a theme without stopping the Home-button service; device selection is tools/tv.py's.
param(
    [Parameter(Position = 0)][string]$Theme,
    [switch]$Reset,
    [string]$TvHost = $env:TV_HOST,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$py = Join-Path $PSScriptRoot 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) { $py = 'python' }
$oldHost = $env:TV_HOST
try {
    if ($TvHost) { $env:TV_HOST = $TvHost }
    $arguments = @()
    if ($Reset) { $arguments += '--reset' }
    if ($Theme) { $arguments += $Theme }
    if ($Serial) { $arguments += @('--serial', $Serial) }
    & $py (Join-Path $PSScriptRoot 'theme.py') @arguments
    $result = $LASTEXITCODE
} finally {
    $env:TV_HOST = $oldHost
}
exit $result
