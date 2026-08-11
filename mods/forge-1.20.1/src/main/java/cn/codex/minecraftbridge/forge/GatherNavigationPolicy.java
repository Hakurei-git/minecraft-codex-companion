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

    static boolean allowsStandOffset(int dx, int dy, int dz) {
        // A cleared trunk column is the only unobstructed way to reach upper
        // logs through a canopy. The caller still requires solid footing,
        // empty feet/head space, interaction reach, and line of sight.
        return dx != 0 || dz != 0 || dy != 0;
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
