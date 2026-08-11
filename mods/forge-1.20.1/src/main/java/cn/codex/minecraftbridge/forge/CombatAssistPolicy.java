package cn.codex.minecraftbridge.forge;

final class CombatAssistPolicy {
    private static final double DISTANCE_PROGRESS_EPSILON = 0.5D;
    private static final float HEALTH_PROGRESS_EPSILON = 0.01F;

    private CombatAssistPolicy() {
    }

    static boolean shouldAssist(
        boolean targetAlive,
        boolean targetIsOwner,
        boolean targetIsCompanion,
        boolean alliedToOwner,
        boolean alliedToCompanion,
        boolean targetIsPlayer,
        boolean allowPvp
    ) {
        return targetAlive
            && !targetIsOwner
            && !targetIsCompanion
            && !alliedToOwner
             && !alliedToCompanion
             && (!targetIsPlayer || allowPvp);
    }

    static int updateDeadline(
        int currentTick,
        int currentDeadline,
        int timeoutTicks,
        boolean changedTarget,
        boolean madeProgress
    ) {
        if (!changedTarget && !madeProgress && currentDeadline > 0) return currentDeadline;
        return currentTick + Math.max(1, timeoutTicks);
    }

    static boolean madeProgress(
        double currentDistance,
        double bestDistance,
        float currentHealth,
        float lowestHealth
    ) {
        return currentDistance + DISTANCE_PROGRESS_EPSILON < bestDistance
            || currentHealth + HEALTH_PROGRESS_EPSILON < lowestHealth;
    }

    static boolean leaseExpired(int currentTick, int deadline) {
        return deadline > 0 && currentTick > deadline;
    }

    static boolean retrySuppressed(boolean sameTarget, int currentTick, int retryAfterTick) {
        return sameTarget && currentTick < retryAfterTick;
    }
}
