package cn.codex.minecraftbridge.forge;

final class DragonFollowPolicy {
    enum Decision { CRUISE, TAKE_OFF, LAND }

    private DragonFollowPolicy() {
    }

    static Decision decide(
        boolean dragonFlying,
        boolean ownerCreativeFlying,
        boolean ownerFallFlying,
        boolean ownerMountedDragonFlying,
        boolean ownerOnGround,
        double ownerHeightAboveDragon,
        double distance
    ) {
        return decide(
            dragonFlying,
            ownerCreativeFlying,
            ownerFallFlying,
            ownerMountedDragonFlying,
            ownerOnGround,
            ownerHeightAboveDragon,
            distance,
            false,
            true
        );
    }

    static Decision decide(
        boolean dragonFlying,
        boolean ownerCreativeFlying,
        boolean ownerFallFlying,
        boolean ownerMountedDragonFlying,
        boolean ownerOnGround,
        double ownerHeightAboveDragon,
        double distance,
        boolean routeObstructed,
        boolean safeToLand
    ) {
        boolean ownerAerial = ownerCreativeFlying
            || ownerFallFlying
            || ownerMountedDragonFlying
            || ownerHeightAboveDragon > 4.0D;
        if (!dragonFlying && (ownerAerial || routeObstructed || distance > 12.0D)) return Decision.TAKE_OFF;
        if (dragonFlying && ownerOnGround && !ownerAerial && safeToLand && !routeObstructed && distance <= 24.0D) {
            return Decision.LAND;
        }
        return Decision.CRUISE;
    }

    static boolean useAerialTarget(
        boolean ownerCreativeFlying,
        boolean ownerFallFlying,
        boolean ownerMountedDragonFlying,
        double ownerHeightAboveDragon
    ) {
        return ownerCreativeFlying || ownerFallFlying || ownerMountedDragonFlying || ownerHeightAboveDragon > 4.0D;
    }
}
