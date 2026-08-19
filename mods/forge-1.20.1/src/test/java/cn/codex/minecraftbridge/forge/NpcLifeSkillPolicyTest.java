package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NpcLifeSkillPolicyTest {
    @Test
    void fishingWaitIsDeterministicAndBounded() {
        int value = NpcLifeSkillPolicy.fishingWaitTicks(123456789L, 3);
        assertEquals(value, NpcLifeSkillPolicy.fishingWaitTicks(123456789L, 3));
        assertTrue(value >= NpcLifeSkillPolicy.MIN_FISHING_WAIT_TICKS);
        assertTrue(value <= NpcLifeSkillPolicy.MAX_FISHING_WAIT_TICKS);
    }

    @Test
    void clampsFishingCatchCount() {
        assertEquals(1, NpcLifeSkillPolicy.clampFishingCatches(0));
        assertEquals(12, NpcLifeSkillPolicy.clampFishingCatches(12));
        assertEquals(64, NpcLifeSkillPolicy.clampFishingCatches(500));
    }

    @Test
    void mapsVanillaCropToPlantingItem() {
        assertEquals("minecraft:wheat_seeds", NpcLifeSkillPolicy.seedItemId("minecraft:wheat"));
        assertEquals("minecraft:carrot", NpcLifeSkillPolicy.seedItemId("minecraft:carrots"));
        assertEquals("minecraft:nether_wart", NpcLifeSkillPolicy.seedItemId("minecraft:nether_wart"));
        assertEquals("example:mod_crop", NpcLifeSkillPolicy.seedItemId("example:mod_crop"));
    }

    @Test
    void normalizesSeedAndItemAliasesToPlacedCropBlocks() {
        assertEquals("minecraft:wheat", NpcLifeSkillPolicy.cropBlockId("minecraft:wheat_seeds"));
        assertEquals("minecraft:carrots", NpcLifeSkillPolicy.cropBlockId("minecraft:carrot"));
        assertEquals("minecraft:potatoes", NpcLifeSkillPolicy.cropBlockId("minecraft:potatoes"));
    }

    @Test
    void limitsTillingToVanillaHoeCompatibleGround() {
        assertTrue(NpcLifeSkillPolicy.isTillableGround("minecraft:dirt"));
        assertTrue(NpcLifeSkillPolicy.isTillableGround("minecraft:grass_block"));
        assertFalse(NpcLifeSkillPolicy.isTillableGround("minecraft:stone"));
    }

    @Test
    void expandsPreparedFarmLandOnlyForExplicitPlantingTasks() {
        assertTrue(NpcLifeSkillPolicy.mayTillNewGround("plant"));
        assertFalse(NpcLifeSkillPolicy.mayTillNewGround("cycle"));
        assertFalse(NpcLifeSkillPolicy.mayTillNewGround("harvest"));
        assertTrue(NpcLifeSkillPolicy.isPreparedFarmGround("minecraft:dirt"));
        assertTrue(NpcLifeSkillPolicy.isPreparedFarmGround("minecraft:coarse_dirt"));
        assertFalse(NpcLifeSkillPolicy.isPreparedFarmGround("minecraft:grass_block"));
    }

    @Test
    void farmCannotReportSuccessWithoutDoingPhysicalWork() {
        assertFalse(NpcLifeSkillPolicy.farmMayReportSuccess(0));
        assertTrue(NpcLifeSkillPolicy.farmMayReportSuccess(1));
    }

    @Test
    void farmTimeoutIsStrictAndBoundedForBuildAndSeedSearch() {
        assertFalse(NpcLifeSkillPolicy.farmTimedOut(NpcLifeSkillPolicy.MAX_FARM_TICKS));
        assertTrue(NpcLifeSkillPolicy.farmTimedOut(NpcLifeSkillPolicy.MAX_FARM_TICKS + 1));
        assertEquals(20 * 60 * 20, NpcLifeSkillPolicy.MAX_FARM_TICKS);
    }

    @Test
    void farmDoesNotRecallNpcDuringSeedExcursionAndBatchesSeeds() {
        assertFalse(NpcLifeSkillPolicy.shouldReturnToFarmAnchor(true, 80.0D, 32));
        assertTrue(NpcLifeSkillPolicy.shouldReturnToFarmAnchor(false, 48.1D, 32));
        assertFalse(NpcLifeSkillPolicy.shouldReturnToFarmAnchor(false, 48.0D, 32));
        assertFalse(NpcLifeSkillPolicy.shouldReturnToFarmAnchor(false, 20.0D, 32));
        assertEquals(8, NpcLifeSkillPolicy.farmActionTarget(32));
        assertEquals(1, NpcLifeSkillPolicy.farmActionTarget(0));
        assertEquals(8, NpcLifeSkillPolicy.farmSeedBatchSize(32, 0));
        assertEquals(7, NpcLifeSkillPolicy.farmSeedBatchSize(32, 25));
        assertEquals(1, NpcLifeSkillPolicy.farmSeedBatchSize(32, 32));
        assertEquals(8, NpcLifeSkillPolicy.farmSeedBatchSize(128, 0));
        assertTrue(NpcLifeSkillPolicy.isWheatSeedSurfaceGather("minecraft:wheat_seeds"));
        assertFalse(NpcLifeSkillPolicy.isWheatSeedSurfaceGather("minecraft:beetroot_seeds"));
        assertTrue(NpcLifeSkillPolicy.isSurfacePlantSource(70, 71));
        assertFalse(NpcLifeSkillPolicy.isSurfacePlantSource(42, 71));
        assertTrue(NpcLifeSkillPolicy.needsSurfaceRecovery(42, 71));
        assertFalse(NpcLifeSkillPolicy.needsSurfaceRecovery(66, 71));
        assertTrue(NpcLifeSkillPolicy.mayTeleportToSurface(true, 42, 71));
        assertFalse(NpcLifeSkillPolicy.mayTeleportToSurface(false, 42, 71));
        assertFalse(NpcLifeSkillPolicy.mayTeleportToSurface(true, 66, 71));
        assertFalse(NpcLifeSkillPolicy.farmPlantRejectionsExhausted(15));
        assertTrue(NpcLifeSkillPolicy.farmPlantRejectionsExhausted(16));
    }

    @Test
    void expandsFarmSearchOutsideTheCurrentRoomWithoutScanningEveryTick() {
        assertEquals(8, NpcLifeSkillPolicy.FARM_VERTICAL_SEARCH_RADIUS);
        assertEquals(12, NpcLifeSkillPolicy.farmSearchRadius(12, 0));
        assertEquals(32, NpcLifeSkillPolicy.farmSearchRadius(12, 20));
        assertEquals(48, NpcLifeSkillPolicy.farmSearchRadius(12, 40));
        assertEquals(16, NpcLifeSkillPolicy.farmSearchRadius(96, 0));
        assertEquals(48, NpcLifeSkillPolicy.farmSearchRadius(96, 40));
        assertEquals(64, NpcLifeSkillPolicy.farmSearchRadius(96, 60));
        assertEquals(80, NpcLifeSkillPolicy.farmSearchRadius(96, 80));
        assertEquals(96, NpcLifeSkillPolicy.farmSearchRadius(96, 100));
        assertTrue(NpcLifeSkillPolicy.shouldScanFarmNow(0));
        assertTrue(NpcLifeSkillPolicy.shouldScanFarmNow(20));
        assertFalse(NpcLifeSkillPolicy.shouldScanFarmNow(21));
        assertFalse(NpcLifeSkillPolicy.farmLocalSearchExhausted(119));
        assertTrue(NpcLifeSkillPolicy.farmLocalSearchExhausted(120));
    }

    @Test
    void sleepRefusesDaylightAndDanger() {
        assertEquals(NpcLifeSkillPolicy.SleepDecision.ALREADY_DAY,
            NpcLifeSkillPolicy.sleepDecision(true, true, false));
        assertEquals(NpcLifeSkillPolicy.SleepDecision.BED_MISSING,
            NpcLifeSkillPolicy.sleepDecision(false, false, false));
        assertEquals(NpcLifeSkillPolicy.SleepDecision.DANGER_NEARBY,
            NpcLifeSkillPolicy.sleepDecision(false, true, true));
        assertEquals(NpcLifeSkillPolicy.SleepDecision.SLEEP,
            NpcLifeSkillPolicy.sleepDecision(false, true, false));
    }

    @Test
    void singlePlayerSleepSkipsOnlyAfterARealRestInNaturalDimensions() {
        assertFalse(NpcLifeSkillPolicy.shouldSkipSinglePlayerNight(1, true,
            NpcLifeSkillPolicy.MIN_SINGLE_PLAYER_REST_TICKS - 1));
        assertTrue(NpcLifeSkillPolicy.shouldSkipSinglePlayerNight(1, true,
            NpcLifeSkillPolicy.MIN_SINGLE_PLAYER_REST_TICKS));
        assertFalse(NpcLifeSkillPolicy.shouldSkipSinglePlayerNight(2, true,
            NpcLifeSkillPolicy.MIN_SINGLE_PLAYER_REST_TICKS));
        assertFalse(NpcLifeSkillPolicy.shouldSkipSinglePlayerNight(1, false,
            NpcLifeSkillPolicy.MIN_SINGLE_PLAYER_REST_TICKS));
    }

    @Test
    void sleepReportsProgressAndHasABoundedWait() {
        assertTrue(NpcLifeSkillPolicy.shouldReportSleepProgress(
            NpcLifeSkillPolicy.SLEEP_PROGRESS_INTERVAL_TICKS));
        assertFalse(NpcLifeSkillPolicy.shouldReportSleepProgress(
            NpcLifeSkillPolicy.SLEEP_PROGRESS_INTERVAL_TICKS + 1));
        assertFalse(NpcLifeSkillPolicy.sleepTimedOut(NpcLifeSkillPolicy.MAX_SLEEP_TICKS - 1));
        assertTrue(NpcLifeSkillPolicy.sleepTimedOut(NpcLifeSkillPolicy.MAX_SLEEP_TICKS));
    }

    @Test
    void calculatesPhysicalDropEntityCount() {
        assertEquals(0, NpcLifeSkillPolicy.stackCountForDrop(0, 64));
        assertEquals(1, NpcLifeSkillPolicy.stackCountForDrop(64, 64));
        assertEquals(2, NpcLifeSkillPolicy.stackCountForDrop(65, 64));
    }
}
