package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildFailureRecoveryPolicyTest {
    @Test
    void retainsEnvironmentalFailuresForExactRetry() {
        assertTrue(BuildFailureRecoveryPolicy.isRecoverable("PATH_NOT_FOUND"));
        assertTrue(BuildFailureRecoveryPolicy.isRecoverable("BUILD_SITE_BLOCKED"));
        assertTrue(BuildFailureRecoveryPolicy.isRecoverable("BUILD_SITE_PROTECTED"));
        assertTrue(BuildFailureRecoveryPolicy.isRecoverable("BLOCK_BREAK_DENIED"));
        assertTrue(BuildFailureRecoveryPolicy.isRecoverable("BLOCK_PLACE_DENIED"));
        assertTrue(BuildFailureRecoveryPolicy.isRecoverable("BUILD_MATERIAL_STALLED"));
    }

    @Test
    void rejectsInvalidPlansAndExplicitCancellation() {
        assertFalse(BuildFailureRecoveryPolicy.isRecoverable("BUILD_PLAN_INVALID"));
        assertFalse(BuildFailureRecoveryPolicy.isRecoverable("BUILD_CHECKPOINT_INVALID"));
        assertFalse(BuildFailureRecoveryPolicy.isRecoverable("CANCELLED"));
        assertFalse(BuildFailureRecoveryPolicy.isRecoverable("EMERGENCY_STOP"));
        assertFalse(BuildFailureRecoveryPolicy.isRecoverable(""));
    }

    @Test
    void discardsOnlyTheExplicitlyCancelledRecoverableCheckpoint() {
        assertTrue(BuildFailureRecoveryPolicy.shouldDiscardOnCancellation("build-1", "build-1"));
        assertFalse(BuildFailureRecoveryPolicy.shouldDiscardOnCancellation("build-1", "build-2"));
        assertFalse(BuildFailureRecoveryPolicy.shouldDiscardOnCancellation("", "build-1"));
        assertFalse(BuildFailureRecoveryPolicy.shouldDiscardOnCancellation(null, "build-1"));
    }
}
