[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("SetupRetrieve", "InspectRetrieve", "SetupOrganize", "InspectOrganize", "SetupExpand", "InspectExpand", "SetupRestart", "InspectRestart", "Cleanup")]
    [string]$Mode
)

$modeMap = @{
    SetupRetrieve = "setup-retrieve"
    InspectRetrieve = "inspect-retrieve"
    SetupOrganize = "setup-organize"
    InspectOrganize = "inspect-organize"
    SetupExpand = "setup-expand"
    InspectExpand = "inspect-expand"
    SetupRestart = "setup-restart"
    InspectRestart = "inspect-restart"
    Cleanup = "cleanup"
}

& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "storage" -Mode $modeMap[$Mode]
