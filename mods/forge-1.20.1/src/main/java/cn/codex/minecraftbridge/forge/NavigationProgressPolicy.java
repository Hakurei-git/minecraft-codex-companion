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
        boolean hasBaseline = lastDistance >= 0.0D;
        boolean madeProgress = !hasBaseline
            || currentDistance < lastDistance - MIN_PROGRESS_PER_SAMPLE;
        int nextStalled = hasBaseline && !madeProgress
            ? stalledTicks + SAMPLE_TICKS
            : Math.max(0, stalledTicks - SAMPLE_TICKS);
        // Keep the best observed distance as the baseline. Sampling only the
        // immediately previous distance lets a two-position collision wobble
        // alternate between +0.2/-0.2 forever and falsely erase every stalled
        // tick, so remote food/resource searches never choose another region.
        double bestDistance = !hasBaseline || currentDistance < lastDistance
            ? currentDistance
            : lastDistance;
        return new Sample(nextStalled, bestDistance);
    }
}
