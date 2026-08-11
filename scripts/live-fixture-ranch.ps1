[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("SetupEstablish", "SupplyBreed", "SetupCull", "Inspect", "Cleanup")]
    [string]$Mode
)

$modeMap = @{
    SetupEstablish = "setup-establish"
    SupplyBreed = "supply-breed"
    SetupCull = "setup-cull"
    Inspect = "inspect"
    Cleanup = "cleanup"
}

& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "ranch" -Mode $modeMap[$Mode]
