package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeepMiningPolicyTest {
    @Test
    void choosesConservativeVanillaMiningLayers() {
        assertEquals(-58, DeepMiningPolicy.targetY("minecraft:diamond"));
        assertEquals(-58, DeepMiningPolicy.targetY("minecraft:redstone"));
        assertEquals(0, DeepMiningPolicy.targetY("minecraft:lapis_lazuli"));
        assertEquals(-16, DeepMiningPolicy.targetY("minecraft:raw_gold"));
        assertEquals(16, DeepMiningPolicy.targetY("minecraft:raw_iron"));
        assertEquals(48, DeepMiningPolicy.targetY("minecraft:raw_copper"));
        assertEquals(48, DeepMiningPolicy.targetY("#minecraft:coals"));
        assertTrue(DeepMiningPolicy.supports("minecraft:coal"));
        assertFalse(DeepMiningPolicy.supports("minecraft:emerald"));
        assertEquals("minecraft:iron_pickaxe", DeepMiningPolicy.requiredPickaxe("minecraft:diamond"));
        assertEquals("minecraft:stone_pickaxe", DeepMiningPolicy.requiredPickaxe("minecraft:raw_iron"));
        assertEquals("", DeepMiningPolicy.requiredPickaxe("#minecraft:coals"));
    }

    @Test
    void coalCanBootstrapTorchMaterialsWithoutARecursiveTorchRequirement() {
        assertEquals(0, DeepMiningPolicy.requiredTorches("#minecraft:coals"));
        assertEquals(0, DeepMiningPolicy.requiredLadders("#minecraft:coals", 55));
        assertEquals(DeepMiningPolicy.REQUIRED_LADDERS,
            DeepMiningPolicy.requiredLadders("#minecraft:coals", 120));
        assertEquals(DeepMiningPolicy.REQUIRED_TORCHES,
            DeepMiningPolicy.requiredTorches("minecraft:diamond"));
        assertEquals(DeepMiningPolicy.REQUIRED_LADDERS,
            DeepMiningPolicy.requiredLadders("minecraft:diamond", 55));
        assertFalse(DeepMiningPolicy.suppliesNeedRefresh(false, Integer.MAX_VALUE, 0, false));
        assertTrue(DeepMiningPolicy.needsHigherEntry("#minecraft:coals", -58));
        assertFalse(DeepMiningPolicy.needsHigherEntry("#minecraft:coals", 40));
        assertFalse(DeepMiningPolicy.needsHigherEntry("minecraft:diamond", -58));
    }

    @Test
    void buildsAOneDownPerStepSurvivalStaircase() {
        BlockPos entry = new BlockPos(10, 64, 20);
        assertEquals(new BlockPos(11, 63, 20),
            DeepMiningPolicy.staircaseStand(entry, Direction.EAST, 1));
        assertEquals(new BlockPos(14, 60, 20),
            DeepMiningPolicy.staircaseStand(entry, Direction.EAST, 4));
    }

    @Test
    void alternatesThirtyTwoBlockBranchesAcrossAThreeBlockSpine() {
        BlockPos landing = new BlockPos(0, -58, 0);
        assertEquals(Direction.EAST, DeepMiningPolicy.branchDirection(Direction.NORTH, 0));
        assertEquals(Direction.WEST, DeepMiningPolicy.branchDirection(Direction.NORTH, 1));
        assertEquals(new BlockPos(0, -58, -3),
            DeepMiningPolicy.branchOrigin(landing, Direction.NORTH, 2, 0));
        assertEquals(new BlockPos(32, -58, 0),
            DeepMiningPolicy.branchStand(landing, Direction.NORTH, 0, 0, 32));
        assertEquals(new BlockPos(0, -58, -128),
            DeepMiningPolicy.branchOrigin(landing, Direction.NORTH, 0, 1));
        assertTrue(DeepMiningPolicy.branchComplete(32));
        assertTrue(DeepMiningPolicy.regionComplete(8));
        assertTrue(DeepMiningPolicy.shouldApproachBranchOrigin(0, false));
        assertFalse(DeepMiningPolicy.shouldApproachBranchOrigin(0, true));
        assertFalse(DeepMiningPolicy.shouldApproachBranchOrigin(1, false));
        assertFalse(DeepMiningPolicy.shouldApproachBranchOrigin(16, false));
    }

    @Test
    void resetsPersistedBranchProgressWhenRecoveryFallsBackToTheMainTunnel() {
        BlockPos landing = new BlockPos(11, -58, -31);
        BlockPos expected = DeepMiningPolicy.branchStand(
            landing,
            Direction.NORTH,
            1,
            0,
            16
        );

        assertEquals(16, DeepMiningPolicy.retainedBranchProgress(
            landing,
            Direction.NORTH,
            1,
            0,
            16,
            expected
        ));
        assertEquals(0, DeepMiningPolicy.retainedBranchProgress(
            landing,
            Direction.NORTH,
            1,
            0,
            16,
            landing
        ));
        assertEquals(0, DeepMiningPolicy.retainedBranchProgress(
            landing,
            Direction.NORTH,
            1,
            0,
            16,
            null
        ));
    }

    @Test
    void advancesReturnCheckpointsOnlyAlongTheOpenedBranchTowardTheOrigin() {
        BlockPos origin = new BlockPos(11, -58, -31);
        BlockPos end = new BlockPos(11, -58, -63);

        assertTrue(DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin, end, new BlockPos(11, -58, -62), end
        ));
        assertTrue(DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin, end, new BlockPos(11, -58, -40), new BlockPos(11, -58, -41)
        ));
        assertTrue(DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin, end, new BlockPos(11, -57, -40), new BlockPos(11, -58, -41)
        ));
        assertEquals(
            new BlockPos(11, -58, -40),
            DeepMiningPolicy.canonicalReturnCheckpoint(origin, new BlockPos(11, -57, -40))
        );
        assertTrue(DeepMiningPolicy.reachedReturnOrigin(
            new Vec3(11.5D, -57.0D, -30.5D), origin
        ));
        assertFalse(DeepMiningPolicy.reachedReturnOrigin(
            new Vec3(12.5D, -57.0D, -30.5D), origin
        ));
        assertFalse(DeepMiningPolicy.reachedReturnOrigin(
            new Vec3(11.5D, -56.0D, -30.5D), origin
        ));
        assertTrue(DeepMiningPolicy.isOnReturnCorridor(
            origin, end, new BlockPos(11, -57, -47)
        ));
        assertTrue(DeepMiningPolicy.isOnReturnCorridor(
            origin, end, origin.above()
        ));
        assertFalse(DeepMiningPolicy.isOnReturnCorridor(
            origin, end, new BlockPos(12, -57, -47)
        ));
        assertFalse(DeepMiningPolicy.isOnReturnCorridor(
            origin, end, new BlockPos(11, -55, -47)
        ));
        assertFalse(DeepMiningPolicy.isOnReturnCorridor(
            origin, end, new BlockPos(11, -57, -64)
        ));
        assertTrue(DeepMiningPolicy.shouldRelocateRegionAfterSpineReroute(0, false));
        assertFalse(DeepMiningPolicy.shouldRelocateRegionAfterSpineReroute(0, true));
        assertFalse(DeepMiningPolicy.shouldRelocateRegionAfterSpineReroute(1, false));
        assertFalse(DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin, end, new BlockPos(12, -58, -40), end
        ));
        assertFalse(DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin, end, new BlockPos(11, -58, -64), end
        ));
        assertFalse(DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin, end, new BlockPos(11, -58, -50), new BlockPos(11, -58, -40)
        ));
        assertEquals(
            new BlockPos(11, -58, -49),
            DeepMiningPolicy.nextReturnStand(new BlockPos(11, -58, -50), origin)
        );
        assertEquals(origin, DeepMiningPolicy.nextReturnStand(origin, origin));
    }

    @Test
    void entersOneBlockDescentsBeforeApplyingDownwardMotion() {
        Vec3 target = new Vec3(40.5D, -55.0D, 52.5D);
        Vec3 approach = DeepMiningPolicy.closeRangeStep(
            new Vec3(40.5D, -54.0D, 51.7D),
            target
        );
        assertEquals(0.0D, approach.y, 0.000001D);
        assertTrue(approach.z > 0.0D);

        Vec3 descent = DeepMiningPolicy.closeRangeStep(
            new Vec3(40.5D, -54.0D, 52.5D),
            target
        );
        assertTrue(descent.y < 0.0D);
        assertEquals(0.0D, descent.x, 0.000001D);
        assertEquals(0.0D, descent.z, 0.000001D);

        BlockPos from = new BlockPos(40, -54, 51);
        BlockPos targetStand = new BlockPos(40, -55, 52);
        assertEquals(
            List.of(targetStand.above(2), targetStand.above(), targetStand),
            DeepMiningPolicy.corridorExcavations(from, targetStand)
        );
        assertEquals(
            List.of(targetStand.east().above(), targetStand.east()),
            DeepMiningPolicy.corridorExcavations(targetStand, targetStand.east())
        );
        assertFalse(DeepMiningPolicy.reachedStand(
            new Vec3(40.5D, -54.0D, 51.98D),
            targetStand
        ));
        assertTrue(DeepMiningPolicy.reachedStand(
            new Vec3(40.5D, -55.0D, 52.02D),
            targetStand
        ));
        assertFalse(DeepMiningPolicy.reachedStand(
            new Vec3(40.5D, -54.4D, 52.5D),
            targetStand
        ));
    }

    @Test
    void requiresSuppliesAndRejectsFluidsGravityBlocksAndUnbreakableBlocks() {
        assertEquals(32, DeepMiningPolicy.REQUIRED_LADDERS);
        assertEquals(32, DeepMiningPolicy.REQUIRED_TORCHES);
        assertEquals(2, DeepMiningPolicy.REQUIRED_IRON_PICKAXES);
        assertEquals(200, DeepMiningPolicy.MIN_PICKAXE_REMAINING_DURABILITY);
        assertTrue(DeepMiningPolicy.isUnsafeExcavation("minecraft:stone", true, 1.5F));
        assertFalse(DeepMiningPolicy.isUnsafeExcavation("minecraft:gravel", false, 0.6F));
        assertFalse(DeepMiningPolicy.isUnsafeExcavation("minecraft:sand", false, 0.5F));
        assertTrue(DeepMiningPolicy.isUnsafeExcavation("minecraft:bedrock", false, -1.0F));
        assertFalse(DeepMiningPolicy.isUnsafeExcavation("minecraft:deepslate", false, 3.0F));
        assertTrue(DeepMiningPolicy.mayBreakCorridorObstacle(false, false, 2.0F, false, false));
        assertTrue(DeepMiningPolicy.mayBreakCorridorObstacle(false, false, 0.1F, false, false));
        assertFalse(DeepMiningPolicy.mayBreakCorridorObstacle(true, false, 0.0F, false, false));
        assertFalse(DeepMiningPolicy.mayBreakCorridorObstacle(false, true, 100.0F, false, false));
        assertFalse(DeepMiningPolicy.mayBreakCorridorObstacle(false, false, -1.0F, false, false));
        assertFalse(DeepMiningPolicy.mayBreakCorridorObstacle(false, false, 2.0F, true, false));
        assertFalse(DeepMiningPolicy.mayBreakCorridorObstacle(false, false, 2.0F, false, true));
        assertEquals(List.of(
            "#forge:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:moss_block",
            "#minecraft:planks"
        ), DeepMiningPolicy.floorMaterialSelectors());
    }

    @Test
    void retriesTorchPlacementAfterAnUnconfirmedAttemptEvenWhenInventoryIsAvailable() {
        int lastConfirmedTorchProgress = 0;

        assertTrue(DeepMiningPolicy.shouldPlaceTorch(8, lastConfirmedTorchProgress));
        // A failed placement leaves the confirmed progress unchanged, so the
        // next tunnel block must retry instead of waiting for another multiple.
        assertTrue(DeepMiningPolicy.shouldPlaceTorch(9, lastConfirmedTorchProgress));

        lastConfirmedTorchProgress = 9;
        assertFalse(DeepMiningPolicy.shouldPlaceTorch(16, lastConfirmedTorchProgress));
        assertTrue(DeepMiningPolicy.shouldPlaceTorch(17, lastConfirmedTorchProgress));
    }

    @Test
    void relocatesFromAnUnsafeEntryOnlyToAUsableNearbyStand() {
        assertFalse(DeepMiningPolicy.isUsableNearbyEntry(true, true, true));
        assertFalse(DeepMiningPolicy.isUsableNearbyEntry(false, false, true));
        assertFalse(DeepMiningPolicy.isUsableNearbyEntry(false, true, false));
        assertTrue(DeepMiningPolicy.isUsableNearbyEntry(false, true, true));
    }

    @Test
    void fallsBackToDirectStaircaseMiningWhenNoNaturalCaveIsAvailable() {
        assertTrue(DeepMiningPolicy.canStartDirectDescent(true, true, false));
        assertFalse(DeepMiningPolicy.canStartDirectDescent(false, true, false));
        assertFalse(DeepMiningPolicy.canStartDirectDescent(true, false, false));
        assertFalse(DeepMiningPolicy.canStartDirectDescent(true, true, true));
    }

    @Test
    void retainsANonNullHorizontalDirectionWhileWaitingForAnEntry() {
        assertEquals(Direction.EAST, DeepMiningPolicy.retainedDirection(Direction.EAST, Direction.SOUTH));
        assertEquals(Direction.SOUTH, DeepMiningPolicy.retainedDirection(null, Direction.SOUTH));
        assertEquals(Direction.NORTH, DeepMiningPolicy.retainedDirection(null, Direction.UP));
        assertEquals(Direction.NORTH, DeepMiningPolicy.retainedDirection(null, null));
    }

    @Test
    void expeditionProbesExpandDeterministicallyAwayFromTheBlockedEntry() {
        BlockPos origin = new BlockPos(10, 64, -20);
        BlockPos first = DeepMiningPolicy.entryExpeditionProbe(origin, 0);
        BlockPos later = DeepMiningPolicy.entryExpeditionProbe(origin, 15);

        assertEquals(origin.getY(), first.getY());
        assertEquals(origin.getY(), later.getY());
        assertTrue(first.distSqr(origin) >= 90L * 90L);
        assertTrue(later.distSqr(origin) > first.distSqr(origin));
        assertEquals(first, DeepMiningPolicy.entryExpeditionProbe(origin, 0));
    }

    @Test
    void entryTeleportRequiresCheatsAndEitherDistanceOrAStalledWalk() {
        assertFalse(DeepMiningPolicy.shouldTeleportToEntry(false, 128.0D, 500));
        assertTrue(DeepMiningPolicy.shouldTeleportToEntry(true, 64.0D, 0));
        assertFalse(DeepMiningPolicy.shouldTeleportToEntry(true, 8.0D, 20));
        assertTrue(DeepMiningPolicy.shouldTeleportToEntry(
            true,
            8.0D,
            DeepMiningPolicy.ENTRY_TELEPORT_STALLED_TICKS
        ));
    }

    @Test
    void entryTargetTimeoutRequiresDistanceAndFullAge() {
        assertFalse(DeepMiningPolicy.entryTargetTimedOut(1.5D, 20 * 30));
        assertFalse(DeepMiningPolicy.entryTargetTimedOut(8.0D, 20 * 11));
        assertTrue(DeepMiningPolicy.entryTargetTimedOut(8.0D, 20 * 12));
        assertFalse(DeepMiningPolicy.entryTargetTimedOut(Double.NaN, 20 * 30));
    }

    @Test
    void refreshesDeepMiningSuppliesBeforeToolsOrTorchesRunOut() {
        assertFalse(DeepMiningPolicy.suppliesNeedRefresh(true, 0, 0));
        // The reported failure happened with 28 torches in the backpack. That
        // inventory is healthy; a missing world placement is a separate bug.
        assertFalse(DeepMiningPolicy.suppliesNeedRefresh(false, 400, 28));
        assertTrue(DeepMiningPolicy.suppliesNeedRefresh(false, 199, 16));
        assertTrue(DeepMiningPolicy.suppliesNeedRefresh(false, 400, 3));
    }

    @Test
    void scalesTheUsableDurabilityFloorForLowerTierPickaxes() {
        assertEquals(105, DeepMiningPolicy.minimumUsablePickaxeDurability(131, 200));
        assertEquals(200, DeepMiningPolicy.minimumUsablePickaxeDurability(250, 200));
        assertEquals(200, DeepMiningPolicy.minimumUsablePickaxeDurability(1561, 200));
        assertEquals(1, DeepMiningPolicy.minimumUsablePickaxeDurability(0, 0));
    }

    @Test
    void carriesFoodBeforeDepartureAndReplenishesAWorkingReserve() {
        assertFalse(DeepMiningPolicy.foodReserveNeedsRefresh(true, false, 0));
        assertTrue(DeepMiningPolicy.foodReserveNeedsRefresh(false, false, 7));
        assertFalse(DeepMiningPolicy.foodReserveNeedsRefresh(false, false, 8));
        assertTrue(DeepMiningPolicy.foodReserveNeedsRefresh(false, true, 1));
        assertFalse(DeepMiningPolicy.foodReserveNeedsRefresh(false, true, 2));
    }

    @Test
    void carriesAFullStackOfLogsBeforeDeepMining() {
        assertEquals(64, DeepMiningPolicy.REQUIRED_LOG_RESERVE);
        assertFalse(DeepMiningPolicy.logReserveNeedsRefresh(true, 0));
        assertTrue(DeepMiningPolicy.logReserveNeedsRefresh(false, 63));
        assertFalse(DeepMiningPolicy.logReserveNeedsRefresh(false, 64));
        assertFalse(DeepMiningPolicy.logReserveNeedsRefresh(false, 96));
    }

    @Test
    void restoresADeepMinerOnlyWithCheatsAndARealCheckpointDisplacement() {
        assertFalse(DeepMiningPolicy.shouldRestoreCheckpoint(false, 128.0D, 120));
        assertFalse(DeepMiningPolicy.shouldRestoreCheckpoint(true, 8.0D, 1));
        assertTrue(DeepMiningPolicy.shouldRestoreCheckpoint(
            true,
            DeepMiningPolicy.CHECKPOINT_RESTORE_DISTANCE,
            0
        ));
        assertTrue(DeepMiningPolicy.shouldRestoreCheckpoint(
            true,
            2.0D,
            DeepMiningPolicy.CHECKPOINT_RESTORE_VERTICAL_DELTA
        ));
        assertFalse(DeepMiningPolicy.shouldRestoreCheckpoint(true, Double.NaN, 100));
    }

    @Test
    void rejectsSurfaceCheckpointsThatRetainCompletedDeepMiningProgress() {
        BlockPos entrance = new BlockPos(-215, 71, -177);
        BlockPos expectedLanding = DeepMiningPolicy.staircaseStand(
            entrance,
            Direction.NORTH,
            129
        );
        BlockPos corruptSurfaceCheckpoint = new BlockPos(-215, 71, -423);

        assertEquals(129, DeepMiningPolicy.staircaseProgress(
            entrance,
            Direction.NORTH,
            129,
            expectedLanding
        ));
        assertEquals(-1, DeepMiningPolicy.staircaseProgress(
            entrance,
            Direction.NORTH,
            129,
            corruptSurfaceCheckpoint
        ));
        assertTrue(DeepMiningPolicy.isValidMiningLayer(-58, expectedLanding));
        assertFalse(DeepMiningPolicy.isValidMiningLayer(-58, corruptSurfaceCheckpoint));
        assertTrue(DeepMiningPolicy.isConsistentBranchCheckpoint(
            -58,
            expectedLanding,
            expectedLanding.east(12)
        ));
        assertFalse(DeepMiningPolicy.isConsistentBranchCheckpoint(
            -58,
            expectedLanding,
            corruptSurfaceCheckpoint
        ));
    }

    @Test
    void skipsOneOscillatingVisibleOreAfterARealTimeBound() {
        assertFalse(DeepMiningPolicy.resourceTargetTimedOut(
            DeepMiningPolicy.RESOURCE_TARGET_TIMEOUT_TICKS - 1
        ));
        assertTrue(DeepMiningPolicy.resourceTargetTimedOut(
            DeepMiningPolicy.RESOURCE_TARGET_TIMEOUT_TICKS
        ));
        assertFalse(DeepMiningPolicy.resourceChaseOwnsMovement(false, 1));
        assertFalse(DeepMiningPolicy.resourceChaseOwnsMovement(true, 0));
        assertTrue(DeepMiningPolicy.resourceChaseOwnsMovement(true, 1));
    }
}
