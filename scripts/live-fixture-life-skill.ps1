[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("Fishing", "Sleep", "BedChain", "BedCleanup")]
    [string]$Mode
)

$modeMap = @{ Fishing = "fishing"; Sleep = "sleep"; BedChain = "bed-chain"; BedCleanup = "bed-cleanup" }
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "life-skill" -Mode $modeMap[$Mode]
