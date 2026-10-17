package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NpcFoodReservePolicyTest {
    @Test
    void replenishesOrdinaryWorkAndIdleBackpacksToTheConfiguredTarget() {
        assertTrue(NpcFoodReservePolicy.shouldProvision(false, 8, 7, "craft", false, false, false));
        assertTrue(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 8, "craft", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(true, 8, 0, "craft", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 0, 0, "craft", false, false, false));
    }

    @Test
    void freezesNewTargetsAndMigratesLegacyMealBufferedTargetsOnce() {
        String automaticTask = "local:auto-food:npc:1";
        assertEquals(8, NpcFoodReservePolicy.taskTarget(automaticTask, 8, 14, false));
        assertEquals(8, NpcFoodReservePolicy.taskTarget(automaticTask, 8, 8, true));
        assertEquals(12, NpcFoodReservePolicy.taskTarget(automaticTask, 8, 12, true));
        assertEquals(0, NpcFoodReservePolicy.taskTarget(automaticTask, 0, 16, false));
        assertEquals(64, NpcFoodReservePolicy.taskTarget(automaticTask, 80, 80, false));
    }

    @Test
    void disablingStandingReservesDoesNotSkipMiningFoodProvision() {
        int required = DeepMiningPolicy.REQUIRED_FOOD_RESERVE;
        int target = NpcFoodReservePolicy.taskTarget("local:deep-mining-food:mine", 0, required, false);

        assertEquals(required, target);
        assertTrue(DeepMiningPolicy.foodReserveNeedsRefresh(false, false, 0));
        assertFalse(FoodProvisionPolicy.shouldComplete(0, target));
        assertTrue(FoodProvisionPolicy.shouldComplete(required, target));
        assertFalse(DeepMiningPolicy.foodReserveNeedsRefresh(false, false, required));
    }

    @Test
    void lowStandingReserveCannotResumeAnUnderProvisionedMiner() {
        int required = DeepMiningPolicy.REQUIRED_FOOD_RESERVE;
        for (int configured = 0; configured < required; configured++) {
            int target = NpcFoodReservePolicy.taskTarget("local:deep-mining-food:mine", configured, required, false);
            for (int available = 0; available < required; available++) {
                assertTrue(DeepMiningPolicy.foodReserveNeedsRefresh(false, false, available));
                assertFalse(FoodProvisionPolicy.shouldComplete(available, target));
            }
        }
    }

    @Test
    void explicitFoodOrdersKeepTheirCountRegardlessOfAutomaticSettings() {
        for (int configured : new int[] {0, 4, 8, 32, 64}) {
            assertEquals(16, NpcFoodReservePolicy.taskTarget("player-food-order", configured, 16, false));
            assertEquals(16, NpcFoodReservePolicy.taskTarget("player-food-order", configured, 16, true));
        }
    }

    @Test
    void restoredMiningProvisionRetainsItsFrozenTarget() {
        assertEquals(8, NpcFoodReservePolicy.taskTarget("local:deep-mining-food:mine", 0, 8, true));
        assertEquals(16, NpcFoodReservePolicy.taskTarget("local:deep-mining-food:mine", 4, 16, true));
        assertEquals(16, NpcFoodReservePolicy.taskTarget("local:deep-mining-food:mine", 64, 16, true));
    }

    @Test
    void neverInterruptsCombatEatingOrExplicitFoodTransfers() {
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "combat", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "eat", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "provision-food", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "deliver", false, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "craft", true, false, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "craft", false, true, false));
        assertFalse(NpcFoodReservePolicy.shouldProvision(false, 8, 0, "craft", false, false, true));
    }
}
