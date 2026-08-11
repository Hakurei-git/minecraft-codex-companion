[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "SpawnBook", "SpawnSaints", "MoveBookFar", "MoveSaintsFar",
        "RaiseBook", "RaiseSaints", "SetBookWander", "SetSaintsWander", "SpawnCombatTarget", "ArmCombatTarget",
        "PrepareBookFeed", "InspectBookNeeds", "InspectBookTame", "DropBookFood", "CleanupCombat", "Cleanup",
        "CoRideBook", "CoRideSaints", "DismountAll", "InspectBook", "InspectSaints",
        "StageObstacleBook", "StageObstacleSaints", "ClearObstacle", "SetCreative", "SetSurvival"
    )]
    [string]$Mode
)

$modeMap = @{
    SpawnBook = "spawn-book"
    SpawnSaints = "spawn-saints"
    MoveBookFar = "move-book-far"
    MoveSaintsFar = "move-saints-far"
    RaiseBook = "raise-book"
    RaiseSaints = "raise-saints"
    SetBookWander = "set-book-wander"
    SetSaintsWander = "set-saints-wander"
    SpawnCombatTarget = "spawn-combat-target"
    ArmCombatTarget = "arm-combat-target"
    PrepareBookFeed = "prepare-book-feed"
    InspectBookNeeds = "inspect-book-needs"
    InspectBookTame = "inspect-book-tame"
    DropBookFood = "drop-book-food"
    CoRideBook = "co-ride-book"
    CoRideSaints = "co-ride-saints"
    DismountAll = "dismount-all"
    InspectBook = "inspect-book"
    InspectSaints = "inspect-saints"
    StageObstacleBook = "stage-obstacle-book"
    StageObstacleSaints = "stage-obstacle-saints"
    ClearObstacle = "clear-obstacle"
    CleanupCombat = "cleanup-combat"
    Cleanup = "cleanup"
    SetCreative = "set-creative"
    SetSurvival = "set-survival"
}

& (Join-Path $PSScriptRoot "invoke-live-fixture.ps1") -Suite "dragon" -Mode $modeMap[$Mode]
