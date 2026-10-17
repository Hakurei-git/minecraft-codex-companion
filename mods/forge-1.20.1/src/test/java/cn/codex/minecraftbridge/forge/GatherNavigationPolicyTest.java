package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherNavigationPolicyTest {
    @Test
    void usesTheRealInteractionReachForGatherStands() {
        assertEquals(4.5, GatherNavigationPolicy.effectiveReach(2.8, 4.5));
        assertEquals(5.0, GatherNavigationPolicy.effectiveReach(5.0, 4.5));
    }

    @Test
    void expandsCandidateRadiusToCoverVanillaReach() {
        assertEquals(5, GatherNavigationPolicy.horizontalCandidateRadius(4.5));
        assertEquals(1, GatherNavigationPolicy.horizontalCandidateRadius(0));
    }

    @Test
    void allowsStandingAboveOrBelowButNotInsideTheTargetBlock() {
        assertTrue(GatherNavigationPolicy.allowsStandOffset(0, 1, 0));
        assertTrue(GatherNavigationPolicy.allowsStandOffset(0, -2, 0));
        assertTrue(GatherNavigationPolicy.allowsStandOffset(1, 0, 0));
        assertTrue(GatherNavigationPolicy.allowsStandOffset(0, -1, 1));
        assertFalse(GatherNavigationPolicy.allowsStandOffset(0, 0, 0));
    }

    @Test
    void measuresReachToTheBlockSurfaceInsteadOfOnlyTheCenter() {
        assertEquals(0.62, GatherNavigationPolicy.blockTouchDistance(0.5, 1.62, 0.5, 0, 0, 0), 0.0001);
        assertEquals(4.2, GatherNavigationPolicy.blockTouchDistance(5.2, 0.5, 0.5, 0, 0, 0), 0.0001);
    }

    @Test
    void aReachedNaturalTreeDoesNotLoseUpperLogsToItsOwnCanopy() {
        assertTrue(GatherNavigationPolicy.canInteractWithTarget(true, true, false, false));
        assertTrue(GatherNavigationPolicy.canInteractWithTarget(true, false, true, true));
        assertFalse(GatherNavigationPolicy.canInteractWithTarget(true, false, true, false));
        assertFalse(GatherNavigationPolicy.canInteractWithTarget(true, false, false, true));
        assertFalse(GatherNavigationPolicy.canInteractWithTarget(false, true, true, true));
    }

    @Test
    void includesGroundUnderUpperTrunksWithoutExtendingInteractionReach() {
        double reach = 4.5;
        double eyeHeight = 1.62;
        int minY = GatherNavigationPolicy.minimumStandOffset(reach, eyeHeight);
        assertEquals(-6, minY);
        assertTrue(GatherNavigationPolicy.blockTouchDistance(0.5, minY + eyeHeight, 0.5, 0, 0, 0) <= reach);
        assertTrue(GatherNavigationPolicy.blockTouchDistance(0.5, minY - 1 + eyeHeight, 0.5, 0, 0, 0) > reach);
        assertFalse(GatherNavigationPolicy.canInteractWithTarget(false, true, true, true));
    }

    @Test
    void includesStandsAboveTheTargetOnlyWithinSurfaceReach() {
        double reach = 4.5;
        double eyeHeight = 1.62;
        int maxY = GatherNavigationPolicy.maximumStandOffset(reach, eyeHeight);
        assertEquals(3, maxY);
        assertTrue(GatherNavigationPolicy.blockTouchDistance(0.5, maxY + eyeHeight, 0.5, 0, 0, 0) <= reach);
        assertTrue(GatherNavigationPolicy.blockTouchDistance(0.5, maxY + 1 + eyeHeight, 0.5, 0, 0, 0) > reach);
    }

    @Test
    void verticalSearchCoversExactlyTheReachableOffsetsForDifferentEyeHeights() {
        for (double eyeHeight : new double[] { 0, 0.5, 1.62, 2.0 }) {
            for (double reach : new double[] { 2.8, 4.5, 5.0 }) {
                int minY = GatherNavigationPolicy.minimumStandOffset(reach, eyeHeight);
                int maxY = GatherNavigationPolicy.maximumStandOffset(reach, eyeHeight);
                for (int dy = -10; dy <= 10; dy++) {
                    double distance = GatherNavigationPolicy.blockTouchDistance(0.5, dy + eyeHeight, 0.5, 0, 0, 0);
                    assertEquals(distance <= reach, dy >= minY && dy <= maxY,
                        "stand offset " + dy + ", eye height " + eyeHeight + ", reach " + reach);
                }
            }
        }
    }
}
