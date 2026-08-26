package cn.codex.minecraftbridge.client;

public final class MovementTargetPolicy {
    private static final double ANY_DIRECTION_Z_RATIO = 0.6D;
    private static final double MIN_NAVIGATION_ARRIVAL_TOLERANCE = 0.5D;
    private static final double MIN_PROGRESS = 0.15D;

    private MovementTargetPolicy() {
    }

    public record HorizontalOffset(double x, double z) {
    }

    public static HorizontalOffset exploreOffset(double requestedRadius, String requestedDirection) {
        double radius = Math.max(0.0D, requestedRadius);
        String direction = requestedDirection == null ? "any" : requestedDirection;
        return switch (direction) {
            case "west" -> new HorizontalOffset(-radius, 0.0D);
            case "east" -> new HorizontalOffset(radius, 0.0D);
            case "north" -> new HorizontalOffset(0.0D, -radius);
            case "south" -> new HorizontalOffset(0.0D, radius);
            default -> {
                double x = radius / Math.sqrt(1.0D + ANY_DIRECTION_Z_RATIO * ANY_DIRECTION_Z_RATIO);
                yield new HorizontalOffset(x, x * ANY_DIRECTION_Z_RATIO);
            }
        };
    }

    public static double arrivalTolerance(double configured) {
        if (!Double.isFinite(configured)) return 1.6D;
        return Math.max(MIN_NAVIGATION_ARRIVAL_TOLERANCE, Math.min(2.0D, configured));
    }

    public static double distance(double deltaX, double deltaY, double deltaZ, boolean horizontal) {
        return horizontal
            ? Math.hypot(deltaX, deltaZ)
            : Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }

    public static boolean madeProgress(double bestDistance, double currentDistance) {
        return !Double.isFinite(bestDistance) || currentDistance < bestDistance - MIN_PROGRESS;
    }

    public static boolean shouldUseUnstuckControls(int stalledSamples, boolean collision, boolean insideLeaves) {
        return collision || insideLeaves || stalledSamples >= 2;
    }

    public static boolean strafeLeft(long ticks) {
        return (ticks / 20L & 1L) == 0L;
    }
}
