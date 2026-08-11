package cn.codex.minecraftbridge.forge;

final class GatherSearchPolicy {
    private static final int NEAR_LEVEL_VERTICAL_RADIUS = 4;

    private GatherSearchPolicy() {}

    static int preferredVerticalRadius(int requestedVerticalRadius) {
        return Math.max(0, Math.min(NEAR_LEVEL_VERTICAL_RADIUS, requestedVerticalRadius));
    }
}
