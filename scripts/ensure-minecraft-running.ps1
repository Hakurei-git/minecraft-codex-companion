[CmdletBinding()]
param(
    [switch]$ForcePauseMenu,

    [ValidateLength(1, 256)]
    [string]$ControlBaseUri = "http://127.0.0.1:8765"
)

$ErrorActionPreference = "Stop"
if ($ForcePauseMenu) {
    throw "MANUAL_UI_REQUIRED: press Escape yourself; automatic Minecraft pause-menu control is disabled"
}

$baseUri = [Uri]$ControlBaseUri
if ($baseUri.Scheme -ne "http" -or
    $baseUri.Host.ToLowerInvariant() -notin @("127.0.0.1", "localhost", "::1", "[::1]")) {
    throw "The live check must use the loopback control service"
}
$apiRoot = [Uri]($baseUri.GetLeftPart([UriPartial]::Authority) + "/")

function Get-SnapshotSequence {
    $response = Invoke-RestMethod -Uri ([Uri]::new($apiRoot, "api/companions")) -TimeoutSec 5
    $companion = @($response.companions) |
        Where-Object {
            $_.connected -eq $true -and
            $_.backend -in @("forge-1.20.1", "neoforge-1.21.1")
        } |
        Select-Object -First 1
    if ($null -eq $companion) { throw "No connected Minecraft companion was found" }
    return [long]$companion.snapshot.sequence
}

$before = Get-SnapshotSequence
Start-Sleep -Milliseconds 1400
$after = Get-SnapshotSequence
if ($after -le $before) {
    throw "MANUAL_UI_REQUIRED: return Minecraft to active gameplay yourself; the snapshot did not advance"
}

[PSCustomObject]@{
    Running = $true
    SnapshotAdvanced = $true
    MouseOrKeyboardInputUsed = $false
    WindowMutationUsed = $false
    CursorMutationUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
