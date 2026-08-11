package cn.codex.minecraftbridge.forge;

/** Pure geometry for placing riders outside a dragon's collision footprint. */
final class DragonDismountPolicy {
    private static final int MIN_RADIUS = 3;
    private static final int MAX_RADIUS = 12;

    private DragonDismountPolicy() {
    }

    static int standRadius(double dragonWidth) {
        double width = Double.isFinite(dragonWidth) ? Math.max(0.0D, dragonWidth) : 0.0D;
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, (int) Math.ceil(width * 0.5D) + 2));
    }

    static Offset sideOffset(float yawDegrees, double dragonWidth, int side) {
        int direction = side < 0 ? -1 : 1;
        double radians = Math.toRadians(yawDegrees);
        double radius = standRadius(dragonWidth);
        return new Offset(Math.cos(radians) * radius * direction, Math.sin(radians) * radius * direction);
    }

    record Offset(double x, double z) {
    }
}
