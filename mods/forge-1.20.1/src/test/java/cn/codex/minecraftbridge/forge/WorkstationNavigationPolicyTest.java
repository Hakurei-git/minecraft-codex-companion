package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkstationNavigationPolicyTest {
    @Test
    void acceptsAPathThatMinecraftReportsAsReachable() {
        assertTrue(WorkstationNavigationPolicy.pathGetsWithinInteractionReach(
            true, false, 0, 0, 0, 20, 70, 20, 3.5
        ));
    }

    @Test
    void acceptsAnAdjacentPathEndpointForASolidWorkstationBlock() {
        assertTrue(WorkstationNavigationPolicy.pathGetsWithinInteractionReach(
            false, true, 9.5, 70, 10.5, 10.5, 70.5, 10.5, 3.5
        ));
    }

    @Test
    void rejectsAPathEndingTooFarFromTheWorkstation() {
        assertFalse(WorkstationNavigationPolicy.pathGetsWithinInteractionReach(
            false, true, 5.5, 70, 10.5, 10.5, 70.5, 10.5, 3.5
        ));
        assertFalse(WorkstationNavigationPolicy.pathGetsWithinInteractionReach(
            false, false, 0, 0, 0, 0, 0, 0, 3.5
        ));
    }

    @Test
    void retriesAnotherWorkstationOnlyAfterTheBoundedStallWindow() {
        assertFalse(WorkstationNavigationPolicy.shouldTryAnotherWorkstation(
            WorkstationNavigationPolicy.MAX_STALLED_TICKS
        ));
        assertTrue(WorkstationNavigationPolicy.shouldTryAnotherWorkstation(
            WorkstationNavigationPolicy.MAX_STALLED_TICKS + 1
        ));
    }

    @Test
    void reusesAnOwnerAreaWorkstationOnlyWhenItCanBeReachedOrRecoveredLocally() {
        assertTrue(WorkstationNavigationPolicy.canSelectKnownWorkstation(true, true, false));
        assertTrue(WorkstationNavigationPolicy.canSelectKnownWorkstation(false, true, true));
        assertFalse(WorkstationNavigationPolicy.canSelectKnownWorkstation(false, true, false));
        assertFalse(WorkstationNavigationPolicy.canSelectKnownWorkstation(false, false, true));
    }
}
