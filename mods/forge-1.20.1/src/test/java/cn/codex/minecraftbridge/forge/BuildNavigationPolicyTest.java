package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildNavigationPolicyTest {
    @Test
    void reroutesAStalledBuildBeforeFailingTheTask() {
        assertFalse(BuildNavigationPolicy.shouldTryAlternativeStand(39));
        assertTrue(BuildNavigationPolicy.shouldTryAlternativeStand(40));
        assertFalse(BuildNavigationPolicy.exhaustedAlternativeStands(5));
        assertTrue(BuildNavigationPolicy.exhaustedAlternativeStands(6));
    }

    @Test
    void cheatRecoveryRequiresPermissionDistanceAndOneShotUse() {
        assertTrue(BuildNavigationPolicy.mayUseCheatRecovery(true, false, 17.0D));
        assertFalse(BuildNavigationPolicy.mayUseCheatRecovery(false, false, 17.0D));
        assertFalse(BuildNavigationPolicy.mayUseCheatRecovery(true, true, 17.0D));
        assertFalse(BuildNavigationPolicy.mayUseCheatRecovery(true, false, 8.0D));
    }

    @Test
    void rotatesBoundedCandidateBatches() {
        assertEquals(24, BuildNavigationPolicy.candidateAttemptCount(100));
        assertEquals(3, BuildNavigationPolicy.candidateAttemptCount(3));
        assertEquals(24, BuildNavigationPolicy.nextCandidateCursor(0, 24, 100));
        assertEquals(4, BuildNavigationPolicy.nextCandidateCursor(96, 8, 100));
    }
}
