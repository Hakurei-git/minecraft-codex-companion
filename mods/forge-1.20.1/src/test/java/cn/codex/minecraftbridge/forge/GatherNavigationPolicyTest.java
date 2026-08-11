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
}
