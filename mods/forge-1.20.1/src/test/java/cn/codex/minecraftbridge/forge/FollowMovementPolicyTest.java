package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FollowMovementPolicyTest {
    @Test
    void stayRequestPreventsIdleDistanceRecall() {
        assertFalse(FollowMovementPolicy.shouldAutoRecall(false, true, true, 10_000, 32));
    }

    @Test
    void recallsOnlyIdleNonStayingCompanionsBeyondTheThreshold() {
        assertTrue(FollowMovementPolicy.shouldAutoRecall(false, false, true, 10_000, 32));
        assertFalse(FollowMovementPolicy.shouldAutoRecall(true, false, true, 10_000, 32));
        assertFalse(FollowMovementPolicy.shouldAutoRecall(false, false, false, 10_000, 32));
        assertFalse(FollowMovementPolicy.shouldAutoRecall(false, false, true, 1_024, 32));
    }

    @Test
    void onlyResetsTheStallWindowAfterRealDistanceProgress() {
        assertTrue(FollowMovementPolicy.madeMeaningfulDistanceProgress(Double.POSITIVE_INFINITY, 12));
        assertFalse(FollowMovementPolicy.madeMeaningfulDistanceProgress(12, 11.8));
        assertFalse(FollowMovementPolicy.madeMeaningfulDistanceProgress(12, 13));
        assertTrue(FollowMovementPolicy.madeMeaningfulDistanceProgress(12, 11.7));
    }

    @Test
    void aerialRecoveryRequiresPermissionDistanceAndACompletedStallWindow() {
        assertTrue(FollowMovementPolicy.shouldRecoverAerialFollow(true, 12, 100, 6, 100));
        assertFalse(FollowMovementPolicy.shouldRecoverAerialFollow(false, 12, 100, 6, 100));
        assertFalse(FollowMovementPolicy.shouldRecoverAerialFollow(true, 6, 100, 6, 100));
        assertFalse(FollowMovementPolicy.shouldRecoverAerialFollow(true, 12, 99, 6, 100));
    }

    @Test
    void walkingDescentRequiresANearbyBoundedDropWithoutTeleportPermission() {
        assertTrue(FollowMovementPolicy.shouldUseWalkingDescent(false, 40, 8, 3));
        assertFalse(FollowMovementPolicy.shouldUseWalkingDescent(true, 40, 8, 3));
        assertFalse(FollowMovementPolicy.shouldUseWalkingDescent(false, 39, 8, 3));
        assertFalse(FollowMovementPolicy.shouldUseWalkingDescent(false, 40, 11, 3));
        assertFalse(FollowMovementPolicy.shouldUseWalkingDescent(false, 40, 8, 9));
    }

    @Test
    void startsForAnAirborneCreativeOwnerWhoIsFlying() {
        assertEquals(
            FollowMovementPolicy.Mode.AERIAL,
            FollowMovementPolicy.nextMode(FollowMovementPolicy.Mode.GROUND, true, true, false, -4)
        );
    }

    @Test
    void doesNotStartForSurvivalOrGroundMovement() {
        assertEquals(
            FollowMovementPolicy.Mode.GROUND,
            FollowMovementPolicy.nextMode(FollowMovementPolicy.Mode.GROUND, false, true, false, -4)
        );
        assertEquals(
            FollowMovementPolicy.Mode.GROUND,
            FollowMovementPolicy.nextMode(FollowMovementPolicy.Mode.GROUND, true, false, true, 0)
        );
    }

    @Test
    void continuesAControlledLandingUntilNearTheOwner() {
        FollowMovementPolicy.Mode landing = FollowMovementPolicy.nextMode(
            FollowMovementPolicy.Mode.AERIAL,
            true,
            false,
            true,
            5
        );
        assertEquals(FollowMovementPolicy.Mode.LANDING, landing);
        assertEquals(
            FollowMovementPolicy.Mode.GROUND,
            FollowMovementPolicy.nextMode(landing, true, false, true, 1)
        );
    }
}
