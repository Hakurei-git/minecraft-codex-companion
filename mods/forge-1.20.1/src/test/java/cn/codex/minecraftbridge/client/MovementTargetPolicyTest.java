package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MovementTargetPolicyTest {
    @Test
    void anyDirectionNeverExceedsTheRequestedExploreRadius() {
        MovementTargetPolicy.HorizontalOffset offset = MovementTargetPolicy.exploreOffset(8.0D, "any");
        assertEquals(8.0D, Math.hypot(offset.x(), offset.z()), 0.000001D);
    }

    @Test
    void preservesCardinalExploreDirections() {
        assertEquals(new MovementTargetPolicy.HorizontalOffset(-5.0D, 0.0D),
            MovementTargetPolicy.exploreOffset(5.0D, "west"));
        assertEquals(new MovementTargetPolicy.HorizontalOffset(0.0D, 5.0D),
            MovementTargetPolicy.exploreOffset(5.0D, "south"));
    }

    @Test
    void clampsArrivalToleranceAndDetectsStalls() {
        assertEquals(0.5D, MovementTargetPolicy.arrivalTolerance(0.01D));
        assertEquals(0.5D, MovementTargetPolicy.arrivalTolerance(0.35D));
        assertEquals(2.0D, MovementTargetPolicy.arrivalTolerance(5.0D));
        assertEquals(1.6D, MovementTargetPolicy.arrivalTolerance(Double.NaN));
        assertTrue(MovementTargetPolicy.madeProgress(Double.POSITIVE_INFINITY, 5.0D));
        assertTrue(MovementTargetPolicy.madeProgress(5.0D, 4.8D));
        assertFalse(MovementTargetPolicy.madeProgress(5.0D, 4.9D));
        assertTrue(MovementTargetPolicy.shouldUseUnstuckControls(0, true, false));
        assertTrue(MovementTargetPolicy.shouldUseUnstuckControls(2, false, false));
        assertFalse(MovementTargetPolicy.shouldUseUnstuckControls(1, false, false));
    }

    @Test
    void horizontalArrivalIgnoresTerrainHeightWithoutChangingOrdinaryMoves() {
        assertEquals(5.0D, MovementTargetPolicy.distance(3.0D, 12.0D, 4.0D, true), 0.000001D);
        assertEquals(13.0D, MovementTargetPolicy.distance(3.0D, 12.0D, 4.0D, false), 0.000001D);
    }

    @Test
    void exactMoveNavigationCannotStopInAnAdjacentBlock() {
        assertEquals(0, MovementTargetPolicy.exactMovePathReachRange());
        assertFalse(MovementTargetPolicy.sameNavigationCell(
            262.816D, 111.0D, -5.499D,
            263.596D, 111.0D, -5.596D,
            true
        ));
        assertTrue(MovementTargetPolicy.sameNavigationCell(
            263.051D, 112.0D, -5.949D,
            263.596D, 111.0D, -5.596D,
            true
        ));
        assertFalse(MovementTargetPolicy.sameNavigationCell(
            263.051D, 112.0D, -5.949D,
            263.596D, 111.0D, -5.596D,
            false
        ));

        assertTrue(MovementTargetPolicy.shouldUseExactMoveFinalApproach(
            true, false, 1.008D, true
        ));
        assertTrue(MovementTargetPolicy.shouldUseExactMoveFinalApproach(
            false, true, 0.8D, true
        ));
        assertFalse(MovementTargetPolicy.shouldUseExactMoveFinalApproach(
            false, false, 1.008D, true
        ));
        assertFalse(MovementTargetPolicy.shouldUseExactMoveFinalApproach(
            true, false, 1.501D, true
        ));
        assertFalse(MovementTargetPolicy.shouldUseExactMoveFinalApproach(
            true, false, 1.008D, false
        ));
    }

    @Test
    void exactMoveReplansAndFailsWithinABoundedStallWindow() {
        assertTrue(MovementTargetPolicy.shouldAttemptExactMovePath(1, 0, 0, true));
        assertFalse(MovementTargetPolicy.shouldAttemptExactMovePath(10, 1, 0, true));
        assertTrue(MovementTargetPolicy.shouldAttemptExactMovePath(21, 1, 0, true));
        assertFalse(MovementTargetPolicy.shouldAttemptExactMovePath(80, 40, 40, false));
        assertTrue(MovementTargetPolicy.shouldAttemptExactMovePath(80, 40, 60, false));

        assertFalse(MovementTargetPolicy.exactMovePathAttemptsExhausted(2));
        assertTrue(MovementTargetPolicy.exactMovePathAttemptsExhausted(3));
        assertFalse(MovementTargetPolicy.exactMoveStalled(199));
        assertTrue(MovementTargetPolicy.exactMoveStalled(200));
    }

    @Test
    void predictedClientArrivalRequiresFreshAuthoritativeConfirmation() {
        assertFalse(MovementTargetPolicy.arrivalConfirmed(true, false, 0.1D, 0.5D, 200));
        assertFalse(MovementTargetPolicy.arrivalConfirmed(true, true, 3.394D, 0.5D, 200));
        assertTrue(MovementTargetPolicy.arrivalConfirmed(true, true, 0.49D, 0.5D, 1));
    }

    @Test
    void multiplayerFallbackRequiresTwoContinuousSecondsAtTheTarget() {
        int required = MovementTargetPolicy.clientOnlyArrivalConfirmationTicks();
        assertEquals(40, required);
        assertFalse(MovementTargetPolicy.arrivalConfirmed(false, false, Double.NaN, 0.5D, 1));
        assertFalse(MovementTargetPolicy.arrivalConfirmed(false, false, Double.NaN, 0.5D, required - 1));
        assertTrue(MovementTargetPolicy.arrivalConfirmed(false, false, Double.NaN, 0.5D, required));
    }
}
