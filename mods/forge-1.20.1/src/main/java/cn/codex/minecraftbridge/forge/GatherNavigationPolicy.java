package cn.codex.minecraftbridge.forge;

final class GatherNavigationPolicy {
    static final int MIN_VERTICAL_OFFSET = -3;
    static final int MAX_VERTICAL_OFFSET = 2;

    private GatherNavigationPolicy() {
    }

    static double effectiveReach(double requestedReach, double interactionReach) {
        if (!Double.isFinite(requestedReach) || requestedReach <= 0) return interactionReach;
        if (!Double.isFinite(interactionReach) || interactionReach <= 0) return requestedReach;
        return Math.max(requestedReach, interactionReach);
    }

    static int horizontalCandidateRadius(double effectiveReach) {
        if (!Double.isFinite(effectiveReach) || effectiveReach <= 0) return 1;
        return Math.max(1, (int) Math.ceil(effectiveReach));
    }

    static int minimumStandOffset(double reach, double eyeHeight) {
        if (!Double.isFinite(reach) || reach <= 0 || !Double.isFinite(eyeHeight) || eyeHeight < 0) {
            return MIN_VERTICAL_OFFSET;
        }
        // Stand Y is measured at the feet; interaction reach starts at the
        // eyes and ends at the block surface. A log six blocks above the feet
        // is still reachable with 4.5-block reach and a 1.62-block eye height.
        return (int) Math.ceil(-reach - eyeHeight);
    }

    static int maximumStandOffset(double reach, double eyeHeight) {
        if (!Double.isFinite(reach) || reach <= 0 || !Double.isFinite(eyeHeight) || eyeHeight < 0) {
            return MAX_VERTICAL_OFFSET;
        }
        return (int) Math.floor(1.0D + reach - eyeHeight);
    }

    static boolean allowsStandOffset(int dx, int dy, int dz) {
        // A cleared trunk column is the only unobstructed way to reach upper
        // logs through a canopy. The caller still requires solid footing,
        // empty feet/head space, interaction reach, and line of sight.
        return dx != 0 || dz != 0 || dy != 0;
    }

    static boolean canInteractWithTarget(
        boolean withinReach,
        boolean hasLineOfSight,
        boolean naturalTreeCluster,
        boolean clusterReached
    ) {
        // Once one log in a scanner-verified natural tree has been reached,
        // neighbouring trunk logs may be hidden only by that tree's own logs
        // or canopy. Keep the physical reach requirement, but do not abandon
        // the rest of the same tree merely because a ray clips its foliage.
        return withinReach && (hasLineOfSight || naturalTreeCluster && clusterReached);
    }

    static double blockTouchDistance(
        double pointX,
        double pointY,
        double pointZ,
        int blockX,
        int blockY,
        int blockZ
    ) {
        double dx = outsideDistance(pointX, blockX, blockX + 1.0);
        double dy = outsideDistance(pointY, blockY, blockY + 1.0);
        double dz = outsideDistance(pointZ, blockZ, blockZ + 1.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double outsideDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }
}
