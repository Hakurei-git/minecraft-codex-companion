[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("SpawnHusk", "HitOwner", "Cleanup", "SetNormal", "SetPeaceful")]
    [string]$Mode
)

$modeMap = @{
    SpawnHusk = "spawn-husk"
    HitOwner = "hit-owner"
    Cleanup = "cleanup"
    SetNormal = "set-normal"
    SetPeaceful = "set-peaceful"
}
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "combat" -Mode $modeMap[$Mode]
