[CmdletBinding()]
param(
    [string]$LauncherPath = $env:MC_LAUNCHER_PATH,
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
if (-not $LauncherPath) {
    throw "LauncherPath is required. Select a launcher on this computer."
}
if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf)) {
    throw "HMCL launcher does not exist: $LauncherPath"
}

$process = Start-Process -FilePath $LauncherPath -PassThru
if ($Wait) {
    $process.WaitForExit()
    exit $process.ExitCode
}
