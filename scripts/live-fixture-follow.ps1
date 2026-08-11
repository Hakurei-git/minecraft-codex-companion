[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "Setup", "MoveGround", "InspectGround", "TakeOff", "InspectAir",
        "Land", "InspectLand", "FarRecall", "InspectRecall", "Cleanup", "ResetSurvival"
    )]
    [string]$Mode
)

$modeMap = @{
    Setup = "setup"
    MoveGround = "move-ground"
    InspectGround = "inspect-ground"
    TakeOff = "take-off"
    InspectAir = "inspect-air"
    Land = "land"
    InspectLand = "inspect-land"
    FarRecall = "far-recall"
    InspectRecall = "inspect-recall"
    Cleanup = "cleanup"
    ResetSurvival = "reset-survival"
}
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "follow" -Mode $modeMap[$Mode]
