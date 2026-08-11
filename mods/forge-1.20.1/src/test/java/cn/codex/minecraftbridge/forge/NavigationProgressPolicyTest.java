package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class NavigationProgressPolicyTest {
    @Test
    void changingStandCandidatesCannotHideNoProgressTowardTheFixedBlock() {
        // These values model a newly selected stand node changing each second.
        // Progress must instead be sampled against the fixed resource block.
        double[] changingStandDistances = { 13, 18, 12, 17, 14, 18 };
        NavigationProgressPolicy.Sample sample = new NavigationProgressPolicy.Sample(0, -1);
        for (double ignoredStandDistance : changingStandDistances) {
            sample = NavigationProgressPolicy.sample(sample.stalledTicks(), sample.lastDistance(), 15.0D);
        }

        assertTrue(GatherRetryPolicy.targetIsUnreachable(0, sample.stalledTicks()));
    }

    @Test
    void realMovementTowardTheFixedTargetClearsAccumulatedStallTime() {
        NavigationProgressPolicy.Sample sample = new NavigationProgressPolicy.Sample(60, 15.0D);
        sample = NavigationProgressPolicy.sample(sample.stalledTicks(), sample.lastDistance(), 14.0D);
        sample = NavigationProgressPolicy.sample(sample.stalledTicks(), sample.lastDistance(), 13.0D);

        assertTrue(sample.stalledTicks() < 60);
    }
}
