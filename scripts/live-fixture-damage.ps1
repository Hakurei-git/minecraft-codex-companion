[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("OwnerMelee", "OwnerProjectile", "Environment", "Cleanup")]
    [string]$Mode
)

$modeMap = @{
    OwnerMelee = "owner-melee"
    OwnerProjectile = "owner-projectile"
    Environment = "environment"
    Cleanup = "cleanup"
}
& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "damage" -Mode $modeMap[$Mode]
