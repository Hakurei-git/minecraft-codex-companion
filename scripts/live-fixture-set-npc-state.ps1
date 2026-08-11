[CmdletBinding()]
param(
    [ValidateRange(0, 20)]
    [int]$Food = 20,

    [ValidateRange(0, 20)]
    [single]$Saturation = 5,

    [ValidateRange(1, 20)]
    [single]$Health = 20
)

if ($Saturation -gt $Food) { throw "Saturation cannot exceed the food level" }
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") `
    -Suite "npc-state" `
    -Mode "set" `
    -Arguments @{ food = $Food; saturation = $Saturation; health = $Health }
