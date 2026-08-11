package cn.codex.minecraftbridge.forge;

/** Pure geometry used to keep the companion on a distinct rear dragon seat. */
final class DragonSeatSharingPolicy {
    private static final double MIN_REAR_DISTANCE = 1.05D;
    private static final double MAX_REAR_DISTANCE = 4.25D;
    private static final double REAR_SEAT_RISE = 0.08D;

    private DragonSeatSharingPolicy() {
    }

    static SeatOffset rearSeatOffset(float yawDegrees, double dragonWidth, double dragonHeight) {
        double distance = rearSeatDistance(dragonWidth, dragonHeight);
        double yaw = Math.toRadians(yawDegrees);
        return new SeatOffset(
            Math.sin(yaw) * distance,
            REAR_SEAT_RISE,
            -Math.cos(yaw) * distance
        );
    }

    static double rearSeatDistance(double dragonWidth, double dragonHeight) {
        double width = Math.max(0.0D, dragonWidth);
        double height = Math.max(0.0D, dragonHeight);
        double scaled = width * 0.55D + height * 0.10D;
        return Math.max(MIN_REAR_DISTANCE, Math.min(MAX_REAR_DISTANCE, scaled));
    }

    record SeatOffset(double x, double y, double z) {
    }
}
