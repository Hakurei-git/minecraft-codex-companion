[CmdletBinding()]
param([switch]$MatureExisting)

$mode = if ($MatureExisting) { "mature-existing-wheat" } else { "create-3x3" }
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "farm-patch" -Mode $mode
