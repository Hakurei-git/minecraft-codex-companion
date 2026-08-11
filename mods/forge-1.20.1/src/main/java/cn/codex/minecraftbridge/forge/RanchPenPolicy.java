package cn.codex.minecraftbridge.forge;

final class RanchPenPolicy {
    static final int MAX_TARGET_PATH_START_FAILURES = 20;
    static final int MAX_TARGET_STALLED_TICKS = 200;

    private RanchPenPolicy() {
    }

    static boolean insideBoundary(
        double x,
        double z,
        int minFenceX,
        int maxFenceX,
        int minFenceZ,
        int maxFenceZ
    ) {
        if (maxFenceX - minFenceX < 2 || maxFenceZ - minFenceZ < 2) return false;
        return x > minFenceX + 0.5D && x < maxFenceX + 0.5D
            && z > minFenceZ + 0.5D && z < maxFenceZ + 0.5D;
    }

    static boolean targetPathUnavailable(int consecutiveStartFailures, int stalledTicks) {
        return consecutiveStartFailures >= MAX_TARGET_PATH_START_FAILURES
            || stalledTicks > MAX_TARGET_STALLED_TICKS;
    }
}
