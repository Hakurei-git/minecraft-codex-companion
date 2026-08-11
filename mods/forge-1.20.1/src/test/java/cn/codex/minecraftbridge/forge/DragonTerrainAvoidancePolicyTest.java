package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonTerrainAvoidancePolicyTest {
    @Test
    void giantDragonsReceiveMoreClearanceThanSmallDragons() {
        double small = DragonTerrainAvoidancePolicy.clearance(2.5D, 2.0D);
        double giant = DragonTerrainAvoidancePolicy.clearance(14.0D, 8.0D);
        assertEquals(4.0D, small);
        assertTrue(giant > small);
    }

    @Test
    void treeCanopyOrCollisionBlocksAnAerialRoute() {
        assertTrue(DragonTerrainAvoidancePolicy.blocksAerialRoute(80.0D, 78.0D, 5.0D, false));
        assertTrue(DragonTerrainAvoidancePolicy.blocksAerialRoute(60.0D, 78.0D, 5.0D, true));
        assertFalse(DragonTerrainAvoidancePolicy.blocksAerialRoute(60.0D, 78.0D, 5.0D, false));
    }

    @Test
    void safeAltitudeClearsTerrainButNeverExceedsBuildLimit() {
        assertEquals(87.0D, DragonTerrainAvoidancePolicy.safeAltitude(70.0D, 80.0D, 7.0D, 300.0D));
        assertEquals(100.0D, DragonTerrainAvoidancePolicy.safeAltitude(70.0D, 98.0D, 7.0D, 100.0D));
    }

    @Test
    void flyingDragonKeepsAnAerialDetourUntilLandingSpaceIsSafeAndNear() {
        assertTrue(DragonTerrainAvoidancePolicy.shouldUseDetour(false, true, false, false, 5.0D, 3.0D));
        assertTrue(DragonTerrainAvoidancePolicy.shouldUseDetour(false, true, false, true, 20.0D, 3.0D));
        assertFalse(DragonTerrainAvoidancePolicy.shouldUseDetour(false, true, false, true, 6.0D, 3.0D));
    }

    @Test
    void teleportFallbackRequiresCheatsObstructionDistanceAndSustainedStall() {
        int threshold = DragonTerrainAvoidancePolicy.STUCK_TELEPORT_TICKS;
        assertTrue(DragonTerrainAvoidancePolicy.shouldTeleportStalledDragon(true, true, 40.0D, threshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldTeleportStalledDragon(false, true, 40.0D, threshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldTeleportStalledDragon(true, false, 40.0D, threshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldTeleportStalledDragon(true, true, 8.0D, threshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldTeleportStalledDragon(true, true, 40.0D, threshold - 1));
    }

    @Test
    void landingSearchExpandsFarEnoughForLargeDragons() {
        double first = DragonTerrainAvoidancePolicy.landingSearchRadius(8.0D, 0);
        double last = DragonTerrainAvoidancePolicy.landingSearchRadius(
            8.0D, DragonTerrainAvoidancePolicy.LANDING_SEARCH_RINGS - 1
        );
        assertTrue(first >= 11.0D);
        assertTrue(last > 100.0D);
        assertTrue(DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS >= 24);
    }

    @Test
    void landingApproachKeepsLargeBodiesCenteredBeforeDescent() {
        assertEquals(1.5D, DragonTerrainAvoidancePolicy.landingApproachReach(2.0D));
        assertTrue(DragonTerrainAvoidancePolicy.landingApproachReach(12.0D) > 4.0D);
        assertEquals(6.0D, DragonTerrainAvoidancePolicy.landingApproachReach(40.0D));
    }

    @Test
    void canopyEscapeRemainsEnabledWhenTheSafePerchIsAboveTheDragon() {
        assertTrue(DragonTerrainAvoidancePolicy.shouldAllowCanopyEscape(true, 70.0D, 80.0D));
        assertTrue(DragonTerrainAvoidancePolicy.shouldAllowCanopyEscape(false, 82.0D, 80.0D));
        assertFalse(DragonTerrainAvoidancePolicy.shouldAllowCanopyEscape(false, 80.2D, 80.0D));
        assertFalse(DragonTerrainAvoidancePolicy.shouldAllowCanopyEscape(false, 64.0D, 80.0D));
    }

    @Test
    void landingCorridorSamplingScalesWithHeightAndRemainsBounded() {
        assertEquals(1, DragonTerrainAvoidancePolicy.landingCorridorSamples(0.0D, 4.0D));
        assertTrue(DragonTerrainAvoidancePolicy.landingCorridorSamples(24.0D, 4.0D) >= 24);
        assertTrue(DragonTerrainAvoidancePolicy.landingCorridorSamples(24.0D, 12.0D) < 24);
        assertEquals(96, DragonTerrainAvoidancePolicy.landingCorridorSamples(10_000.0D, 1.0D));
    }

    @Test
    void mountedTakeoffScalesLiftAndBoundsLocalRecovery() {
        assertEquals(0.55D, DragonTerrainAvoidancePolicy.mountedLiftStep(2.0D));
        assertEquals(1.25D, DragonTerrainAvoidancePolicy.mountedLiftStep(20.0D));
        assertTrue(DragonTerrainAvoidancePolicy.mountedEscapeSearchHeight(8.0D, 6.0D) >= 10.0D);
        assertTrue(DragonTerrainAvoidancePolicy.mountedEscapeSearchHeight(100.0D, 100.0D) <= 24.0D);

        int mountThreshold = DragonTerrainAvoidancePolicy.MOUNT_APPROACH_RECOVERY_STALL_SAMPLES;
        assertTrue(DragonTerrainAvoidancePolicy.shouldUseMountApproachRecovery(true, mountThreshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldUseMountApproachRecovery(false, mountThreshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldUseMountApproachRecovery(true, mountThreshold - 1));

        int terrainThreshold = DragonTerrainAvoidancePolicy.MOUNTED_TERRAIN_RECOVERY_STALL_TICKS;
        assertTrue(DragonTerrainAvoidancePolicy.shouldUseMountedTerrainRecovery(true, terrainThreshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldUseMountedTerrainRecovery(false, terrainThreshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldUseMountedTerrainRecovery(true, terrainThreshold - 1));

        int landingThreshold = DragonTerrainAvoidancePolicy.LANDING_RECOVERY_STALL_TICKS;
        assertTrue(DragonTerrainAvoidancePolicy.shouldRecoverStalledLanding(landingThreshold));
        assertFalse(DragonTerrainAvoidancePolicy.shouldRecoverStalledLanding(landingThreshold - 1));
        assertEquals(1.5D, DragonTerrainAvoidancePolicy.LANDING_SUPPORT_STEP
            * DragonTerrainAvoidancePolicy.LANDING_SUPPORT_SEARCH_STEPS);
    }
}
