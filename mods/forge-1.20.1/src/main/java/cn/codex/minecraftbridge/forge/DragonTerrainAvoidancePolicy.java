package cn.codex.minecraftbridge.forge;

/** Pure calculations used by the runtime terrain-aware dragon navigator. */
final class DragonTerrainAvoidancePolicy {
    static final int STUCK_TELEPORT_TICKS = 120;
    static final int MOUNT_APPROACH_RECOVERY_STALL_SAMPLES = 4;
    static final int MOUNTED_TERRAIN_RECOVERY_STALL_TICKS = 20 * 4;
    static final int LANDING_RECOVERY_STALL_TICKS = 20 * 4;
    static final int LANDING_SEARCH_RINGS = 12;
    static final int LANDING_SEARCH_DIRECTIONS = 24;
    static final double LANDING_SUPPORT_STEP = 1.0D / 16.0D;
    static final int LANDING_SUPPORT_SEARCH_STEPS = 24;

    private DragonTerrainAvoidancePolicy() {
    }

    static double clearance(double width, double height) {
        double bodyClearance = Math.max(width * 0.65D, height * 0.35D) + 2.0D;
        return Math.max(4.0D, Math.min(32.0D, Math.ceil(bodyClearance)));
    }

    static int routeSamples(double horizontalDistance, double width) {
        double step = Math.max(2.0D, Math.min(6.0D, Math.max(1.0D, width * 0.75D)));
        return Math.max(4, Math.min(32, (int) Math.ceil(horizontalDistance / step)));
    }

    static boolean blocksAerialRoute(
        double obstacleTop,
        double plannedBottomY,
        double clearance,
        boolean collisionAhead
    ) {
        return collisionAhead || obstacleTop + clearance > plannedBottomY;
    }

    static double safeAltitude(
        double requestedTargetY,
        double highestObstacleTop,
        double clearance,
        double maximumBottomY
    ) {
        double requested = Math.max(requestedTargetY, highestObstacleTop + clearance);
        return Math.min(maximumBottomY, requested);
    }

    static boolean shouldUseDetour(
        boolean ownerAerial,
        boolean dragonFlying,
        boolean routeBlocked,
        boolean landingSpaceClear,
        double horizontalDistance,
        double dragonWidth
    ) {
        if (ownerAerial || routeBlocked) return true;
        if (dragonFlying && (!landingSpaceClear || horizontalDistance > landingRadius(dragonWidth))) return true;
        return horizontalDistance > 12.0D;
    }

    static double landingRadius(double dragonWidth) {
        return Math.max(7.0D, Math.min(18.0D, dragonWidth + 5.0D));
    }

    static double landingSearchRadius(double dragonWidth, int ring) {
        double width = Math.max(1.0D, dragonWidth);
        double minimumRadius = Math.max(6.0D, width + 3.0D);
        double ringStep = Math.max(6.0D, width + 2.0D);
        return minimumRadius + Math.max(0, ring) * ringStep;
    }

    static double landingApproachReach(double dragonWidth) {
        return Math.max(1.5D, Math.min(6.0D, Math.max(0.0D, dragonWidth) * 0.35D));
    }

    static boolean shouldAllowCanopyEscape(
        boolean landingApproachPhase,
        double landingY,
        double currentY
    ) {
        return landingApproachPhase || landingY > currentY + 0.25D;
    }

    static int landingCorridorSamples(double verticalDistance, double dragonHeight) {
        double step = Math.max(0.5D, Math.min(1.5D, Math.max(1.0D, dragonHeight) * 0.20D));
        return Math.max(1, Math.min(96, (int) Math.ceil(Math.max(0.0D, verticalDistance) / step)));
    }

    static double mountedLiftStep(double dragonHeight) {
        return Math.max(0.55D, Math.min(1.25D, Math.max(0.0D, dragonHeight) * 0.12D));
    }

    static double mountedEscapeSearchHeight(double dragonWidth, double dragonHeight) {
        double requested = clearance(dragonWidth, dragonHeight) + Math.max(1.0D, dragonHeight);
        return Math.max(6.0D, Math.min(24.0D, requested));
    }

    static boolean shouldUseMountApproachRecovery(boolean cheatsAllowed, int stalledSamples) {
        return cheatsAllowed && stalledSamples >= MOUNT_APPROACH_RECOVERY_STALL_SAMPLES;
    }

    static boolean shouldUseMountedTerrainRecovery(boolean cheatsAllowed, int stalledTicks) {
        return cheatsAllowed && stalledTicks >= MOUNTED_TERRAIN_RECOVERY_STALL_TICKS;
    }

    static boolean shouldRecoverStalledLanding(int stalledTicks) {
        return stalledTicks >= LANDING_RECOVERY_STALL_TICKS;
    }

    static boolean shouldTeleportStalledDragon(
        boolean cheatsAllowed,
        boolean routeBlocked,
        double distance,
        int stuckTicks
    ) {
        return cheatsAllowed
            && routeBlocked
            && distance > 12.0D
            && stuckTicks >= STUCK_TELEPORT_TICKS;
    }
}
