package cn.codex.minecraftbridge.client;

public final class MovementTargetPolicy {
    private static final double ANY_DIRECTION_Z_RATIO = 0.6D;
    private static final double MIN_NAVIGATION_ARRIVAL_TOLERANCE = 0.5D;
    private static final double MIN_PROGRESS = 0.15D;
    private static final double MAX_EXACT_MOVE_FINAL_APPROACH_DISTANCE = 1.5D;
    private static final int CLIENT_ONLY_ARRIVAL_CONFIRMATION_TICKS = 40;
    private static final int EXACT_MOVE_PATH_REACH_RANGE = 0;
    private static final int EXACT_MOVE_PATH_RETRY_TICKS = 20;
    private static final int EXACT_MOVE_STALL_REPATH_TICKS = 60;
    private static final int EXACT_MOVE_STALL_FAILURE_TICKS = 200;
    private static final int EXACT_MOVE_PATH_FAILURE_LIMIT = 3;

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

    public static int exactMovePathReachRange() {
        return EXACT_MOVE_PATH_REACH_RANGE;
    }

    public static boolean sameNavigationCell(
        double currentX,
        double currentY,
        double currentZ,
        double targetX,
        double targetY,
        double targetZ,
        boolean horizontal
    ) {
        return blockCoordinate(currentX) == blockCoordinate(targetX)
            && blockCoordinate(currentZ) == blockCoordinate(targetZ)
            && (horizontal || blockCoordinate(currentY) == blockCoordinate(targetY));
    }

    public static boolean shouldUseExactMoveFinalApproach(
        boolean navigationDone,
        boolean sameNavigationCell,
        double distance,
        boolean destinationSafe
    ) {
        if (!destinationSafe) return false;
        if (sameNavigationCell) return true;
        return navigationDone
            && Double.isFinite(distance)
            && distance <= MAX_EXACT_MOVE_FINAL_APPROACH_DISTANCE;
    }

    public static boolean shouldAttemptExactMovePath(
        int ticks,
        int lastAttemptTick,
        int stalledTicks,
        boolean navigationDone
    ) {
        if (lastAttemptTick <= 0) return true;
        if (ticks - lastAttemptTick < EXACT_MOVE_PATH_RETRY_TICKS) return false;
        return navigationDone || stalledTicks >= EXACT_MOVE_STALL_REPATH_TICKS;
    }

    public static boolean exactMovePathAttemptsExhausted(int failedAttempts) {
        return failedAttempts >= EXACT_MOVE_PATH_FAILURE_LIMIT;
    }

    public static boolean exactMoveStalled(int stalledTicks) {
        return stalledTicks >= EXACT_MOVE_STALL_FAILURE_TICKS;
    }

    /**
     * Client movement is predicted before the server accepts it. A single
     * client-side threshold hit therefore cannot finish a task: the server may
     * still correct the player several blocks backwards. Singleplayer uses a
     * fresh integrated-server sample; remote multiplayer requires two seconds
     * of uninterrupted client stability after all movement controls stop.
     */
    static boolean arrivalConfirmed(
        boolean authoritativeServerAvailable,
        boolean freshAuthoritativeSample,
        double authoritativeDistance,
        double tolerance,
        int consecutiveClientTicks
    ) {
        if (authoritativeServerAvailable) {
            return freshAuthoritativeSample
                && Double.isFinite(authoritativeDistance)
                && authoritativeDistance <= tolerance;
        }
        return consecutiveClientTicks >= CLIENT_ONLY_ARRIVAL_CONFIRMATION_TICKS;
    }

    static int clientOnlyArrivalConfirmationTicks() {
        return CLIENT_ONLY_ARRIVAL_CONFIRMATION_TICKS;
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

    private static int blockCoordinate(double value) {
        return (int) Math.floor(value);
    }
}
