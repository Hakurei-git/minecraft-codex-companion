package cn.codex.minecraftbridge.forge;

/** Pure reachability and retry rules for task-owned crafting workstations. */
final class WorkstationNavigationPolicy {
    static final int MAX_STALLED_TICKS = 200;

    private WorkstationNavigationPolicy() {
    }

    static boolean pathGetsWithinInteractionReach(
        boolean pathCanReach,
        boolean hasEndNode,
        double endX,
        double endY,
        double endZ,
        double targetX,
        double targetY,
        double targetZ,
        double reach
    ) {
        if (pathCanReach) return true;
        if (!hasEndNode || !Double.isFinite(reach) || reach < 0.0D) return false;
        double dx = endX - targetX;
        double dy = endY - targetY;
        double dz = endZ - targetZ;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    static boolean shouldTryAnotherWorkstation(int stalledTicks) {
        return stalledTicks > MAX_STALLED_TICKS;
    }
}
