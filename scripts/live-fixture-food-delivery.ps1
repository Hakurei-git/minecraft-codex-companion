[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("SetupPlayer", "InspectPlayer", "Cleanup")]
    [string]$Mode
)

$modeMap = @{
    SetupPlayer = "setup-player"
    InspectPlayer = "inspect-player"
    Cleanup = "cleanup"
}

& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "food-delivery" -Mode $modeMap[$Mode]
