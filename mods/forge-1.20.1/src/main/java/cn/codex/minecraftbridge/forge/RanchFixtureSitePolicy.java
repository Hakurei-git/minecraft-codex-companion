package cn.codex.minecraftbridge.forge;

/** Candidate coverage for the reversible live ranch fixture. */
final class RanchFixtureSitePolicy {
    static final int MIN_RADIUS = 12;
    static final int MAX_RADIUS = 128;
    static final int RADIUS_STEP = 4;
    static final double MAX_ARC_STEP = 4.0D;

    private RanchFixtureSitePolicy() {
    }

    static int angularSamples(int radius) {
        int required = (int) Math.ceil(Math.PI * 2.0D * Math.max(MIN_RADIUS, radius) / MAX_ARC_STEP);
        return Math.max(16, ((required + 7) / 8) * 8);
    }
}
