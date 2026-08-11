package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HomeStoragePolicyTest {
    @Test
    void clampsStorageRadiusToSupportedRange() {
        assertEquals(8, HomeStoragePolicy.clampRadius(1));
        assertEquals(24, HomeStoragePolicy.clampRadius(24));
        assertEquals(64, HomeStoragePolicy.clampRadius(200));
    }

    @Test
    void triggersAutoStorageAtEightyPercentOrLowFreeSlots() {
        assertFalse(HomeStoragePolicy.shouldAutoStore(20, 27));
        assertTrue(HomeStoragePolicy.shouldAutoStore(22, 27));
        assertTrue(HomeStoragePolicy.shouldAutoStore(8, 10));
    }

    @Test
    void retainsProtectedAndHighValueInventory() {
        assertTrue(HomeStoragePolicy.shouldRetain(false, true, false, false, false, false));
        assertTrue(HomeStoragePolicy.shouldRetain(false, false, false, false, false, true));
        assertFalse(HomeStoragePolicy.shouldRetain(false, false, false, false, false, false));
    }

    @Test
    void classifiesRepresentativeItems() {
        assertEquals(HomeStoragePolicy.Category.FOOD, HomeStoragePolicy.category("minecraft:bread", true, false));
        assertEquals(HomeStoragePolicy.Category.WOOD, HomeStoragePolicy.category("minecraft:oak_log", false, false));
        assertEquals(HomeStoragePolicy.Category.WEAPONS, HomeStoragePolicy.category("minecraft:iron_sword", false, false));
        assertEquals(HomeStoragePolicy.Category.VALUABLES, HomeStoragePolicy.category("minecraft:enchanted_book", false, true));
    }

    @Test
    void requiresPlayerEquivalentMaterialsForAutomaticStorageExpansion() {
        assertTrue(HomeStoragePolicy.canExpandStorage(1, 0, false, false));
        assertTrue(HomeStoragePolicy.canExpandStorage(0, 8, true, false));
        assertFalse(HomeStoragePolicy.canExpandStorage(0, 11, false, false));
        assertTrue(HomeStoragePolicy.canExpandStorage(0, 12, false, false));
        assertTrue(HomeStoragePolicy.canExpandStorage(0, 0, false, true));
    }

    @Test
    void keepsOnlyUsefulOrRareBackpackGear() {
        assertTrue(HomeStoragePolicy.shouldRetainBackpackGear(true, false));
        assertTrue(HomeStoragePolicy.shouldRetainBackpackGear(false, true));
        assertFalse(HomeStoragePolicy.shouldRetainBackpackGear(false, false));
    }

    @Test
    void keepsRareEmergencyItemsInTheNpcBackpack() {
        assertTrue(HomeStoragePolicy.isRareCarryItem("minecraft:totem_of_undying"));
        assertTrue(HomeStoragePolicy.isRareCarryItem("minecraft:diamond"));
        assertFalse(HomeStoragePolicy.isRareCarryItem("minecraft:cobblestone"));
    }

    @Test
    void retrievalPreflightRejectsMissingItemsBeforeCapacity() {
        assertEquals(
            HomeStoragePolicy.RetrievalDecision.ITEMS_MISSING,
            HomeStoragePolicy.retrievalDecision(7, 8, false)
        );
        assertEquals(
            HomeStoragePolicy.RetrievalDecision.INVENTORY_FULL,
            HomeStoragePolicy.retrievalDecision(8, 8, false)
        );
        assertEquals(
            HomeStoragePolicy.RetrievalDecision.READY,
            HomeStoragePolicy.retrievalDecision(12, 8, true)
        );
    }

    @Test
    void storageExpansionDoesNotConsumeExplicitlyRequestedMaterials() {
        assertEquals(8, HomeStoragePolicy.usableExpansionMaterial(16, 8, 8));
        assertEquals(4, HomeStoragePolicy.usableExpansionMaterial(12, 12, 8));
        assertEquals(12, HomeStoragePolicy.usableExpansionMaterial(12, 0, 8));
        assertEquals(0, HomeStoragePolicy.usableExpansionMaterial(8, 8, 8));
    }

    @Test
    void stalledStoragePathsRecoverOnlyAfterTheBoundedWalkingAttempt() {
        assertFalse(HomeStoragePolicy.shouldRecoverStoragePath(HomeStoragePolicy.MAX_STALLED_PATH_TICKS));
        assertTrue(HomeStoragePolicy.shouldRecoverStoragePath(HomeStoragePolicy.MAX_STALLED_PATH_TICKS + 1));
    }

    @Test
    void cheatRecoveryTeleportsAtMostOncePerStorageTarget() {
        assertFalse(HomeStoragePolicy.mayUseCheatPathRecovery(false, false));
        assertTrue(HomeStoragePolicy.mayUseCheatPathRecovery(true, false));
        assertFalse(HomeStoragePolicy.mayUseCheatPathRecovery(true, true));
    }

    @Test
    void batchesOnlyContainersInsidePlayerInteractionReachAndTheTickLimit() {
        assertTrue(HomeStoragePolicy.mayBatchRetrieve(3.5, 3.5, 0));
        assertFalse(HomeStoragePolicy.mayBatchRetrieve(3.5001, 3.5, 0));
        assertFalse(HomeStoragePolicy.mayBatchRetrieve(
            1.0,
            3.5,
            HomeStoragePolicy.MAX_RETRIEVE_BATCH_CONTAINERS
        ));
        assertFalse(HomeStoragePolicy.mayBatchRetrieve(Double.NaN, 3.5, 0));
    }
}
