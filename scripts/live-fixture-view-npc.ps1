[CmdletBinding()]
param(
    [ValidateSet("Npc", "Fishing", "Sleep")]
    [string]$Mode = "Npc"
)

$modeMap = @{ Npc = "npc"; Fishing = "fishing"; Sleep = "sleep" }
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "view-npc" -Mode $modeMap[$Mode]
