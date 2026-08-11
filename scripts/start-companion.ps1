[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8765,
    [switch]$SkipBuild,
    [switch]$OpenDashboard,
    [switch]$Background,
    [switch]$EnableLiveFixtures
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & npm.cmd run build:control
        if ($LASTEXITCODE -ne 0) {
            throw "Control service build failed with exit code $LASTEXITCODE"
        }
    }

    $env:PORT = $Port.ToString()
    if ([string]::IsNullOrWhiteSpace([string]$env:MC_COMPANION_STATE_DIR)) {
        $env:MC_COMPANION_STATE_DIR = Join-Path $env:LOCALAPPDATA "MinecraftCodexCompanion"
    }
    if ([string]::IsNullOrWhiteSpace([string]$env:MC_MCP_URL)) {
        $env:MC_MCP_URL = "http://127.0.0.1:$Port/mcp"
    }

    # Match the portable launcher without copying configuration or secrets
    # into the project. Only the parent directory is passed to the bridge.
    if ([string]::IsNullOrWhiteSpace([string]$env:MC_ANTIGRAVITY_HOME)) {
        $launcherConfigPath = Join-Path $env:LOCALAPPDATA "MinecraftCodexCompanion\launcher-config.json"
        if (Test-Path -LiteralPath $launcherConfigPath -PathType Leaf) {
            $launcherConfig = Get-Content -LiteralPath $launcherConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $antigravityConfigPath = [string]$launcherConfig.antigravityConfigPath
            if (-not [string]::IsNullOrWhiteSpace($antigravityConfigPath)) {
                $env:MC_ANTIGRAVITY_HOME = Split-Path -Parent $antigravityConfigPath
            }
            $configuredConversationTitle = [string]$launcherConfig.antigravityConversationTitle
            if (-not [string]::IsNullOrWhiteSpace($configuredConversationTitle) -and
                [string]::IsNullOrWhiteSpace([string]$env:MC_ANTIGRAVITY_CONVERSATION_TITLE)) {
                $env:MC_ANTIGRAVITY_CONVERSATION_TITLE = $configuredConversationTitle
            }
        }
    }
    if ($EnableLiveFixtures) {
        $env:MC_ENABLE_LIVE_FIXTURES = "1"
    } else {
        Remove-Item Env:MC_ENABLE_LIVE_FIXTURES -ErrorAction SilentlyContinue
    }
    if ($OpenDashboard) {
        Start-Process "http://127.0.0.1:$Port"
    }
    if ($Background) {
        try {
            $existing = Invoke-RestMethod `
                -Uri "http://127.0.0.1:$Port/api/health" `
                -TimeoutSec 2
        } catch {
            $existing = $null
        }
        if ($null -ne $existing -and $existing.ok -and
            $existing.service -eq "minecraft-codex-companion") {
            [PSCustomObject]@{
                Started = $false
                AlreadyRunning = $true
                Port = $Port
                Companions = [int]$existing.companions
                LiveFixturesRequested = [bool]$EnableLiveFixtures
            }
            return
        }

        $node = (Get-Command node.exe -ErrorAction Stop).Source
        $process = Start-Process `
            -FilePath $node `
            -ArgumentList 'apps\control-plane\dist\server.js' `
            -WorkingDirectory $projectRoot `
            -WindowStyle Hidden `
            -PassThru
        if ($null -eq $process) {
            throw "Control service process did not start"
        }

        $deadline = (Get-Date).AddSeconds(30)
        $health = $null
        do {
            Start-Sleep -Milliseconds 400
            try {
                $health = Invoke-RestMethod `
                    -Uri "http://127.0.0.1:$Port/api/health" `
                    -TimeoutSec 2
            } catch {
                $health = $null
            }
        } while ($null -eq $health -and (Get-Date) -lt $deadline)
        if ($null -eq $health -or -not $health.ok -or
            $health.service -ne "minecraft-codex-companion") {
            if (-not $process.HasExited) {
                $process.Kill()
            }
            throw "Control service did not become healthy before timeout"
        }

        [PSCustomObject]@{
            Started = $true
            AlreadyRunning = $false
            ProcessId = $process.Id
            Port = $Port
            Companions = [int]$health.companions
            LiveFixturesRequested = [bool]$EnableLiveFixtures
        }
        return
    }
    & node.exe "apps\control-plane\dist\server.js"
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
