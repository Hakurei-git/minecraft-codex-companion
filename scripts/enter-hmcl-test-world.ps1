[CmdletBinding()]
param(
    [ValidateRange(30, 600)]
    [int]$WaitSeconds = 300,

    [switch]$AnyWorld,

    [ValidateLength(1, 128)]
    [string]$WorldId,

    [switch]$SkipLogInspection,

    [ValidateRange(0, 180)]
    [int]$NoLogMenuGraceSeconds = 75,

    [ValidateLength(1, 256)]
    [string]$ControlBaseUri = "http://127.0.0.1:8765",

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0,

    [switch]$CaptureBeforeSelection
)

$ErrorActionPreference = "Stop"

if ($CaptureBeforeSelection) {
    throw "MANUAL_UI_REQUIRED: capture the Minecraft world-selection screen yourself"
}
if ($AnyWorld -and -not [string]::IsNullOrWhiteSpace($WorldId)) {
    throw "AnyWorld and WorldId cannot be combined"
}

$configuredBaseUri = if ($PSBoundParameters.ContainsKey("ControlBaseUri")) {
    $ControlBaseUri
} elseif (-not [string]::IsNullOrWhiteSpace($env:MC_COMPANION_URL)) {
    [string]$env:MC_COMPANION_URL
} else {
    "http://127.0.0.1:8765"
}
$baseUri = [Uri]$configuredBaseUri
if ($baseUri.Scheme -ne "http" -or
    $baseUri.Host.ToLowerInvariant() -notin @("127.0.0.1", "localhost", "::1", "[::1]")) {
    throw "ControlBaseUri must be an HTTP loopback URL"
}
$apiRoot = [Uri]($baseUri.GetLeftPart([UriPartial]::Authority) + "/")

function Get-ConnectedTarget {
    try {
        $response = Invoke-RestMethod -Uri ([Uri]::new($apiRoot, "api/companions")) -TimeoutSec 2
    } catch {
        return $null
    }
    $matches = @()
    foreach ($companion in @($response.companions)) {
        if ($companion.connected -ne $true -or
            [string]$companion.backend -notin @("forge-1.20.1", "neoforge-1.21.1") -or
            [string]$companion.embodiment -notin @("in-world-npc", "remote-player")) {
            continue
        }
        $id = [Uri]::EscapeDataString([string]$companion.id)
        try {
            $snapshot = Invoke-RestMethod `
                -Uri ([Uri]::new($apiRoot, "api/companions/$id/snapshot")) `
                -TimeoutSec 2
        } catch {
            continue
        }
        if (-not $AnyWorld -and
            -not [string]::IsNullOrWhiteSpace($WorldId) -and
            -not [string]::Equals([string]$snapshot.worldId, $WorldId, [StringComparison]::Ordinal)) {
            continue
        }
        $matches += [PSCustomObject]@{
            Companion = $companion
            Snapshot = $snapshot
        }
    }
    if ($matches.Count -gt 1) {
        throw "More than one Minecraft companion matched the requested world"
    }
    return $matches | Select-Object -First 1
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$target = $null
do {
    $target = Get-ConnectedTarget
    if ($null -ne $target) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)

if ($null -eq $target) {
    $expected = if ($AnyWorld -or [string]::IsNullOrWhiteSpace($WorldId)) {
        "the intended world"
    } else {
        "world '$WorldId'"
    }
    throw "MANUAL_UI_REQUIRED: enter $expected yourself; no matching bridge connected before timeout"
}

[PSCustomObject]@{
    Connected = $true
    CompanionId = [string]$target.Companion.id
    Backend = [string]$target.Companion.backend
    Embodiment = [string]$target.Companion.embodiment
    WorldId = [string]$target.Snapshot.worldId
    Dimension = [string]$target.Snapshot.dimension
    ManualWorldEntryObserved = $true
    WorldSelectionPosted = $false
    ScreenshotUsed = $false
    LogInspectionUsed = $false
    MouseOrKeyboardInputUsed = $false
    WindowMutationUsed = $false
    CursorMutationUsed = $false
    MinecraftProcessIdHint = $MinecraftProcessId
    NativeWindowHandleHint = $NativeWindowHandle
}
