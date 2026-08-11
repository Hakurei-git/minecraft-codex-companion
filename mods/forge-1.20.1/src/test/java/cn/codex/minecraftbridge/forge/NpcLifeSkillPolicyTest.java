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
    void expandsPreparedFarmLandForPlantingAndCycleTasks() {
        assertTrue(NpcLifeSkillPolicy.mayTillNewGround("plant"));
        assertTrue(NpcLifeSkillPolicy.mayTillNewGround("cycle"));
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
    void farmTimeoutIsStrictAndIndependentOfCompletedWork() {
        assertFalse(NpcLifeSkillPolicy.farmTimedOut(NpcLifeSkillPolicy.MAX_FARM_TICKS));
        assertTrue(NpcLifeSkillPolicy.farmTimedOut(NpcLifeSkillPolicy.MAX_FARM_TICKS + 1));
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
