package cn.codex.minecraftbridge.forge;

/** Pure one-second navigation progress sampling used by stateful task movement. */
final class NavigationProgressPolicy {
    static final double MIN_PROGRESS_PER_SAMPLE = 0.15D;
    static final int SAMPLE_TICKS = 20;

    private NavigationProgressPolicy() {
    }

    record Sample(int stalledTicks, double lastDistance) {
    }

    static Sample sample(int stalledTicks, double lastDistance, double currentDistance) {
        if (!Double.isFinite(currentDistance) || currentDistance < 0.0D) {
            return new Sample(stalledTicks, lastDistance);
        }
        int nextStalled = lastDistance >= 0.0D
            && currentDistance >= lastDistance - MIN_PROGRESS_PER_SAMPLE
            ? stalledTicks + SAMPLE_TICKS
            : Math.max(0, stalledTicks - SAMPLE_TICKS);
        return new Sample(nextStalled, currentDistance);
    }
}
