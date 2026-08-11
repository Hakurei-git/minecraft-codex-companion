package cn.codex.minecraftbridge.forge;

/** Pure retry limits for walking to a reachable placement stance. */
final class BuildNavigationPolicy {
    static final int STALLED_TICKS_BEFORE_ALTERNATIVE = 40;
    static final int MAX_ALTERNATIVE_STANDS = 6;
    static final int REPATH_INTERVAL_TICKS = 10;
    static final int MAX_STAND_PATH_CANDIDATES_PER_ATTEMPT = 24;
    static final double MIN_TELEPORT_RECOVERY_DISTANCE = 12.0D;

    private BuildNavigationPolicy() {}

    static boolean shouldTryAlternativeStand(int stalledTicks) {
        return stalledTicks >= STALLED_TICKS_BEFORE_ALTERNATIVE;
    }

    static boolean shouldAttemptPath(int currentTick, int lastAttemptTick) {
        return lastAttemptTick < 0 || currentTick - lastAttemptTick >= REPATH_INTERVAL_TICKS;
    }

    static boolean exhaustedAlternativeStands(int failedAlternatives) {
        return failedAlternatives >= MAX_ALTERNATIVE_STANDS;
    }

    static boolean mayUseCheatRecovery(
        boolean ownerCanUseCommands,
        boolean alreadyTeleportedToTarget,
        double distance
    ) {
        return ownerCanUseCommands
            && !alreadyTeleportedToTarget
            && Double.isFinite(distance)
            && distance >= MIN_TELEPORT_RECOVERY_DISTANCE;
    }

    static int candidateAttemptCount(int candidateCount) {
        return Math.min(Math.max(0, candidateCount), MAX_STAND_PATH_CANDIDATES_PER_ATTEMPT);
    }

    static int normalizedCandidateCursor(int cursor, int candidateCount) {
        if (candidateCount <= 0) return 0;
        return Math.floorMod(cursor, candidateCount);
    }

    static int nextCandidateCursor(int cursor, int attempted, int candidateCount) {
        if (candidateCount <= 0) return 0;
        return Math.floorMod(
            normalizedCandidateCursor(cursor, candidateCount) + Math.max(0, attempted),
            candidateCount
        );
    }
}
