package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherRetryPolicyTest {
    @Test
    void retriesAfterAnUnreachableTarget() {
        assertEquals(
            GatherRetryPolicy.Decision.RETRY_ANOTHER_TARGET,
            GatherRetryPolicy.afterSkipping(1)
        );
    }

    @Test
    void permitsTheBoundedNumberOfUniqueSkips() {
        assertEquals(
            GatherRetryPolicy.Decision.RETRY_ANOTHER_TARGET,
            GatherRetryPolicy.afterSkipping(GatherRetryPolicy.MAX_SKIPPED_TARGETS)
        );
    }

    @Test
    void failsAfterTheSkipBoundIsExceeded() {
        assertEquals(
            GatherRetryPolicy.Decision.FAIL_TASK,
            GatherRetryPolicy.afterSkipping(GatherRetryPolicy.MAX_SKIPPED_TARGETS + 1)
        );
    }

    @Test
    void marksTargetsUnreachableAfterRepeatedPathFailuresOrStalling() {
        assertFalse(GatherRetryPolicy.targetIsUnreachable(1, 20));
        assertTrue(GatherRetryPolicy.targetIsUnreachable(GatherRetryPolicy.MAX_PATH_FAILURES, 0));
        assertTrue(GatherRetryPolicy.targetIsUnreachable(0, GatherRetryPolicy.MAX_STALLED_TICKS + 1));
    }

    @Test
    void rejectsARepeatedTeleportWhenTheSafePositionIsStillFarAway() {
        assertTrue(GatherRetryPolicy.teleportDestinationIsUseful(8.0));
        assertFalse(GatherRetryPolicy.teleportDestinationIsUseful(8.01));
        assertFalse(GatherRetryPolicy.teleportDestinationIsUseful(Double.NaN));
    }

    @Test
    void walkOnlyGatherDisablesRemoteRecovery() {
        assertTrue(GatherRetryPolicy.allowsRemoteRecovery(null));
        assertTrue(GatherRetryPolicy.allowsRemoteRecovery(""));
        assertTrue(GatherRetryPolicy.allowsRemoteRecovery("auto"));
        assertFalse(GatherRetryPolicy.allowsRemoteRecovery("walk"));
        assertFalse(GatherRetryPolicy.allowsRemoteRecovery(" WALK "));
    }

    @Test
    void samplesFailedPathStartsInsteadOfCountingEveryTick() {
        assertEquals(3, GatherRetryPolicy.nextPathFailureCount(3, false, false, false));
        assertEquals(4, GatherRetryPolicy.nextPathFailureCount(3, false, false, true));
    }

    @Test
    void clearsFailuresWhenNavigationStartsOrIsAlreadyRunning() {
        assertEquals(0, GatherRetryPolicy.nextPathFailureCount(3, true, false, true));
        assertEquals(0, GatherRetryPolicy.nextPathFailureCount(3, false, true, true));
    }

    @Test
    void expensiveStandPathSearchIsThrottled() {
        assertTrue(GatherRetryPolicy.shouldAttemptPath(1, -1));
        assertFalse(GatherRetryPolicy.shouldAttemptPath(9, 1));
        assertTrue(GatherRetryPolicy.shouldAttemptPath(11, 1));
    }

    @Test
    void longDistanceNavigationKeepsAHealthyPathInsteadOfReplacingItEveryTick() {
        assertTrue(GatherRetryPolicy.shouldRepathDestination(false, 0, 1));
        assertFalse(GatherRetryPolicy.shouldRepathDestination(true, 0, 10));
        assertFalse(GatherRetryPolicy.shouldRepathDestination(true, 1, 9));
        assertTrue(GatherRetryPolicy.shouldRepathDestination(true, 1, 10));
    }

    @Test
    void reachedNaturalTreeClustersDoNotDiscardQueuedUpperLogsDuringPreflight() {
        assertFalse(GatherRetryPolicy.queuedTargetMayBeAttempted(true, false, false, false));
        assertTrue(GatherRetryPolicy.queuedTargetMayBeAttempted(true, false, false, true));
        assertFalse(GatherRetryPolicy.queuedTargetMayBeAttempted(true, true, true, true));
        assertFalse(GatherRetryPolicy.queuedTargetMayBeAttempted(false, false, true, true));
    }

    @Test
    void remoteExcursionWaitsUntilEveryLocalTargetAndQueueEntryAreExhausted() {
        assertFalse(GatherRetryPolicy.shouldStartRemoteExcursion(false, false, 100, 48, 48));
        assertFalse(GatherRetryPolicy.shouldStartRemoteExcursion(true, true, 100, 48, 48));
        assertFalse(GatherRetryPolicy.shouldStartRemoteExcursion(true, false, 40, 48, 48));
        assertFalse(GatherRetryPolicy.shouldStartRemoteExcursion(true, false, 100, 32, 48));
        assertTrue(GatherRetryPolicy.shouldStartRemoteExcursion(true, false, 41, 48, 48));
    }

    @Test
    void standCandidateBatchesRotateInsteadOfRetryingTheSameCandidates() {
        int total = GatherRetryPolicy.MAX_STAND_PATH_CANDIDATES_PER_ATTEMPT + 7;
        int attempted = GatherRetryPolicy.candidateAttemptCount(total);
        int next = GatherRetryPolicy.nextCandidateCursor(0, attempted, total);

        assertTrue(attempted < total);
        assertEquals(attempted, GatherRetryPolicy.normalizedCandidateCursor(next, total));
    }

    @Test
    void doesNotSkipATargetAfterFourConsecutiveGameTicks() {
        int failures = 0;
        for (int tick = 1; tick <= GatherRetryPolicy.MAX_PATH_FAILURES; tick++) {
            failures = GatherRetryPolicy.nextPathFailureCount(
                failures,
                false,
                false,
                tick % 20 == 0
            );
        }

        assertEquals(0, failures);
        assertFalse(GatherRetryPolicy.targetIsUnreachable(failures, 0));
    }

    @Test
    void requiresFourFailedOneSecondSamplesBeforeSkipping() {
        int failures = 0;
        int finalTick = GatherRetryPolicy.MAX_PATH_FAILURES * 20;
        for (int tick = 1; tick <= finalTick; tick++) {
            failures = GatherRetryPolicy.nextPathFailureCount(
                failures,
                false,
                false,
                tick % 20 == 0
            );
            if (tick < finalTick) {
                assertFalse(GatherRetryPolicy.targetIsUnreachable(failures, 0));
            }
        }

        assertEquals(GatherRetryPolicy.MAX_PATH_FAILURES, failures);
        assertTrue(GatherRetryPolicy.targetIsUnreachable(failures, 0));
    }
}
