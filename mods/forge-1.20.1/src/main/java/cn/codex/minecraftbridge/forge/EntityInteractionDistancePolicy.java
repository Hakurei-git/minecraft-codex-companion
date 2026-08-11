package cn.codex.minecraftbridge.forge;

/** Measures entity interaction reach against the target's selectable surface. */
final class EntityInteractionDistancePolicy {
    static final double TARGETING_MARGIN = 0.1D;

    private EntityInteractionDistancePolicy() {
    }

    static double distanceToExpandedBounds(
        double pointX,
        double pointY,
        double pointZ,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        double expansion
    ) {
        double margin = Double.isFinite(expansion) && expansion > 0.0D ? expansion : 0.0D;
        double dx = outsideDistance(pointX, minX - margin, maxX + margin);
        double dy = outsideDistance(pointY, minY - margin, maxY + margin);
        double dz = outsideDistance(pointZ, minZ - margin, maxZ + margin);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double outsideDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0D;
    }
}
