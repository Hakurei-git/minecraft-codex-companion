[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 8766
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\palette-matrix-live"
$profileRoot = Join-Path $projectRoot "runtime\live-hmcl-profile"
$configPath = Join-Path $profileRoot (
    "AppData\Roaming\.minecraft\versions\Dragons_ZH_1.20.1-Codex" +
    "\config\minecraft-codex-companion.json"
)
$stateRoot = Join-Path $runtimeRoot "state"
$backupPath = Join-Path $runtimeRoot "minecraft-codex-companion.before-matrix.json"
$pidPath = Join-Path $runtimeRoot "control.pid"

foreach ($path in @($profileRoot, $configPath)) {
    $resolved = [IO.Path]::GetFullPath($path)
    $prefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Isolated acceptance path escaped the project"
    }
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Isolated Minecraft bridge configuration is missing"
}
if (Test-Path -LiteralPath $pidPath -PathType Leaf) {
    $oldPid = 0
    [void][int]::TryParse((Get-Content -Raw -LiteralPath $pidPath).Trim(), [ref]$oldPid)
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $listener) {
        $listenerProcess = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
        if ($oldPid -gt 0 -and
            $listener.OwningProcess -eq $oldPid -and
            $null -ne $listenerProcess -and
            $listenerProcess.ProcessName -eq "node") {
            throw "An isolated palette control service is already running"
        }
        throw "Port $Port is already used by an unexpected process; no process was stopped"
    }
}

New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) {
    Copy-Item -LiteralPath $configPath -Destination $backupPath
}

$env:PORT = $Port.ToString()
$env:MC_COMPANION_STATE_DIR = $stateRoot
$env:MC_ENABLE_LIVE_FIXTURES = "1"
$env:MC_MCP_URL = "http://127.0.0.1:$Port/mcp"
Remove-Item Env:MC_BRIDGE_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:MC_ANTIGRAVITY_HOME -ErrorAction SilentlyContinue
Remove-Item Env:MC_ANTIGRAVITY_LOG_PATH -ErrorAction SilentlyContinue

$node = (Get-Command node.exe -ErrorAction Stop).Source
# Codex desktop can expose both PATH and Path. Windows PowerShell's
# Start-Process rejects that case-insensitive duplicate, so normalize only
# this short-lived launcher process while retaining the original value.
$processEnvironment = [Environment]::GetEnvironmentVariables()
$pathValue = $null
foreach ($entry in $processEnvironment.GetEnumerator()) {
    if ([string]$entry.Key -ceq "PATH") { $pathValue = [string]$entry.Value; break }
}
if ($null -ne $pathValue) {
    [Environment]::SetEnvironmentVariable("Path", $null, "Process")
    [Environment]::SetEnvironmentVariable("PATH", $pathValue, "Process")
}
$process = Start-Process `
    -FilePath $node `
    -ArgumentList "apps\control-plane\dist\server.js" `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -PassThru

$deadline = (Get-Date).AddSeconds(30)
$health = $null
do {
    Start-Sleep -Milliseconds 250
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
    } catch {
        $health = $null
    }
} while ($null -eq $health -and (Get-Date) -lt $deadline)

if ($null -eq $health -or -not $health.ok) {
    if (-not $process.HasExited) { $process.Kill() }
    throw "Isolated palette control service did not become healthy"
}

$tokenPath = Join-Path $stateRoot "bridge-token.txt"
if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
    if (-not $process.HasExited) { $process.Kill() }
    throw "Isolated control service did not create its bridge token"
}
$token = (Get-Content -Raw -Encoding UTF8 -LiteralPath $tokenPath).Trim()
if ($token.Length -lt 16) {
    if (-not $process.HasExited) { $process.Kill() }
    throw "Isolated bridge token is invalid"
}

$config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
$config.serverUrl = "ws://127.0.0.1:$Port/bridge"
$config.token = $token
[IO.File]::WriteAllText(
    $configPath,
    ($config | ConvertTo-Json -Depth 20),
    [Text.UTF8Encoding]::new($false)
)
[IO.File]::WriteAllText($pidPath, $process.Id.ToString(), [Text.Encoding]::ASCII)

[PSCustomObject]@{
    Started = $true
    ProcessId = $process.Id
    Port = $Port
    Healthy = [bool]$health.ok
    ConfigBackup = (Test-Path -LiteralPath $backupPath -PathType Leaf)
    TokenStoredLocally = $true
}
