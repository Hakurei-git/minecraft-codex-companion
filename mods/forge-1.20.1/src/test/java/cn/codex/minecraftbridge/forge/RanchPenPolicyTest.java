package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RanchPenPolicyTest {
    @Test
    void countsAnimalsOnlyInsideTheFenceBlocks() {
        assertTrue(RanchPenPolicy.insideBoundary(0.5D, 0.5D, -2, 2, -2, 2));
        assertTrue(RanchPenPolicy.insideBoundary(-1.0D, -1.0D, -2, 2, -2, 2));
        assertTrue(RanchPenPolicy.insideBoundary(-1.49D, 2.49D, -2, 2, -2, 2));

        assertFalse(RanchPenPolicy.insideBoundary(0.5D, -1.51D, -2, 2, -2, 2));
        assertFalse(RanchPenPolicy.insideBoundary(2.5D, 0.5D, -2, 2, -2, 2));
        assertFalse(RanchPenPolicy.insideBoundary(0.5D, -2.5D, -2, 2, -2, 2));
    }

    @Test
    void rejectsDegenerateFenceBounds() {
        assertFalse(RanchPenPolicy.insideBoundary(0.5D, 0.5D, 0, 1, 0, 1));
    }

    @Test
    void toleratesTransientMovingTargetPathFailures() {
        assertFalse(RanchPenPolicy.targetPathUnavailable(1, 0));
        assertFalse(RanchPenPolicy.targetPathUnavailable(
            RanchPenPolicy.MAX_TARGET_PATH_START_FAILURES - 1,
            RanchPenPolicy.MAX_TARGET_STALLED_TICKS
        ));
        assertTrue(RanchPenPolicy.targetPathUnavailable(
            RanchPenPolicy.MAX_TARGET_PATH_START_FAILURES,
            0
        ));
        assertTrue(RanchPenPolicy.targetPathUnavailable(
            0,
            RanchPenPolicy.MAX_TARGET_STALLED_TICKS + 1
        ));
    }
}
