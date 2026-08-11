[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\palette-matrix-live"
$profileRoot = Join-Path $projectRoot "runtime\live-hmcl-profile"
$configPath = Join-Path $profileRoot (
    "AppData\Roaming\.minecraft\versions\Dragons_ZH_1.20.1-Codex" +
    "\config\minecraft-codex-companion.json"
)
$backupPath = Join-Path $runtimeRoot "minecraft-codex-companion.before-matrix.json"
$pidPath = Join-Path $runtimeRoot "control.pid"

$stopped = $false
if (Test-Path -LiteralPath $pidPath -PathType Leaf) {
    $processId = 0
    [void][int]::TryParse((Get-Content -Raw -LiteralPath $pidPath).Trim(), [ref]$processId)
    if ($processId -gt 0) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -ne $process) {
            Stop-Process -Id $processId
            $process.WaitForExit(5000) | Out-Null
            $stopped = $process.HasExited
        }
    }
}

$restored = $false
if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
    Copy-Item -LiteralPath $backupPath -Destination $configPath -Force
    $restored = $true
}

[PSCustomObject]@{
    ProcessStopped = $stopped
    ConfigRestored = $restored
    RuntimeEvidenceRetained = (Test-Path -LiteralPath $runtimeRoot -PathType Container)
}
