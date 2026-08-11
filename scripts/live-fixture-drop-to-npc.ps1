[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9_.-]+:[a-z0-9/._-]+$')]
    [string]$ItemId,

    [ValidateRange(1, 64)]
    [int]$Count = 1
)

& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") `
    -Suite "drop-to-npc" `
    -Mode "drop" `
    -Arguments @{ itemId = $ItemId; count = $Count }
