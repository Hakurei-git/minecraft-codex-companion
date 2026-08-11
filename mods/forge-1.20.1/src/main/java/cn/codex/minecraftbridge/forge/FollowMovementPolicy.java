package cn.codex.minecraftbridge.forge;

final class FollowMovementPolicy {
    private static final double START_VERTICAL_GAP = 1.5;
    private static final double LANDING_VERTICAL_GAP = 1.25;
    private static final double DISTANCE_PROGRESS_EPSILON = 0.25;

    private FollowMovementPolicy() {
    }

    enum Mode {
        GROUND,
        AERIAL,
        LANDING
    }

    static boolean shouldAutoRecall(
        boolean hasActiveWork,
        boolean stayRequested,
        boolean ownerCanTeleport,
        double distanceSquared,
        double recallDistance
    ) {
        return !hasActiveWork
            && !stayRequested
            && ownerCanTeleport
            && distanceSquared > recallDistance * recallDistance;
    }

    static boolean madeMeaningfulDistanceProgress(double bestDistance, double currentDistance) {
        return !Double.isFinite(bestDistance)
            || currentDistance + DISTANCE_PROGRESS_EPSILON < bestDistance;
    }

    static boolean shouldRecoverAerialFollow(
        boolean ownerCanTeleport,
        double distance,
        int stalledTicks,
        double recoveryDistance,
        int maxStalledTicks
    ) {
        return ownerCanTeleport
            && distance > recoveryDistance
            && stalledTicks >= maxStalledTicks;
    }

    static boolean shouldUseWalkingDescent(
        boolean ownerCanTeleport,
        int stalledTicks,
        double verticalGap,
        double horizontalDistance
    ) {
        return !ownerCanTeleport
            && stalledTicks >= 40
            && verticalGap > 3.0
            && verticalGap <= 10.0
            && horizontalDistance > 0.25
            && horizontalDistance <= 8.0;
    }

    static Mode nextMode(
        Mode currentMode,
        boolean ownerCreative,
        boolean ownerFlying,
        boolean ownerOnGround,
        double verticalGap
    ) {
        boolean ownerNeedsAerialFollow = ownerCreative
            && ownerFlying
            && (!ownerOnGround || Math.abs(verticalGap) >= START_VERTICAL_GAP);
        if (ownerNeedsAerialFollow) return Mode.AERIAL;
        if (currentMode != Mode.GROUND && verticalGap > LANDING_VERTICAL_GAP) return Mode.LANDING;
        return Mode.GROUND;
    }
}
