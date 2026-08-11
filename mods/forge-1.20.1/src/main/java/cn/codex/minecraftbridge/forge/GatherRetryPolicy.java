package cn.codex.minecraftbridge.forge;

final class GatherRetryPolicy {
    static final int MAX_SKIPPED_TARGETS = 24;
    static final int MAX_PATH_FAILURES = 4;
    static final int MAX_STALLED_TICKS = 80;
    static final double MAX_USEFUL_TELEPORT_DISTANCE = 8.0;
    static final int REPATH_INTERVAL_TICKS = 10;
    static final int MAX_STAND_PATH_CANDIDATES_PER_ATTEMPT = 24;

    private GatherRetryPolicy() {
    }

    enum Decision {
        RETRY_ANOTHER_TARGET,
        FAIL_TASK
    }

    static Decision afterSkipping(int uniqueSkippedTargets) {
        return uniqueSkippedTargets <= MAX_SKIPPED_TARGETS
            ? Decision.RETRY_ANOTHER_TARGET
            : Decision.FAIL_TASK;
    }

    static boolean targetIsUnreachable(int consecutivePathFailures, int stalledTicks) {
        return consecutivePathFailures >= MAX_PATH_FAILURES || stalledTicks > MAX_STALLED_TICKS;
    }

    static boolean teleportDestinationIsUseful(double distanceToTarget) {
        return Double.isFinite(distanceToTarget) && distanceToTarget <= MAX_USEFUL_TELEPORT_DISTANCE;
    }

    static boolean allowsRemoteRecovery(String movement) {
        return movement == null || !"walk".equalsIgnoreCase(movement.trim());
    }

    static int nextPathFailureCount(
        int currentFailures,
        boolean pathStarted,
        boolean navigationInProgress,
        boolean sampleFailure
    ) {
        if (pathStarted || navigationInProgress) return 0;
        return sampleFailure ? currentFailures + 1 : currentFailures;
    }

    static boolean shouldAttemptPath(int currentTick, int lastAttemptTick) {
        return lastAttemptTick < 0 || currentTick - lastAttemptTick >= REPATH_INTERVAL_TICKS;
    }

    static boolean shouldRepathDestination(
        boolean navigationInProgress,
        int stalledTicks,
        int currentTick
    ) {
        if (!navigationInProgress) return true;
        return stalledTicks > 0
            && currentTick >= 0
            && currentTick % REPATH_INTERVAL_TICKS == 0;
    }

    static boolean queuedTargetMayBeAttempted(
        boolean matchesResource,
        boolean protectedResource,
        boolean safeStandKnown,
        boolean reachedNaturalTreeCluster
    ) {
        return matchesResource
            && !protectedResource
            && (safeStandKnown || reachedNaturalTreeCluster);
    }

    /**
     * Remote recovery is only allowed after the current local target and the
     * complete local queue have both been exhausted. A few awkward blocks in
     * an otherwise reachable ore vein must never make the NPC abandon the
     * remaining nearby resources.
     */
    static boolean shouldStartRemoteExcursion(
        boolean localQueueEmpty,
        boolean activeTarget,
        int noWorkTicks,
        int searchRadius,
        int localSearchRadius
    ) {
        return localQueueEmpty
            && !activeTarget
            && noWorkTicks > 40
            && searchRadius >= localSearchRadius;
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
