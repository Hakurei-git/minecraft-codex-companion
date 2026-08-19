package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

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
        assertTrue(NpcFoodReservePolicy.targetWithMealBuffer(8, 20, 20) == 8);
        assertTrue(NpcFoodReservePolicy.targetWithMealBuffer(8, 18, 20) == 9);
        assertTrue(NpcFoodReservePolicy.targetWithMealBuffer(8, 14, 20) == 11);
        assertTrue(NpcFoodReservePolicy.targetWithMealBuffer(64, 0, 20) == 64);
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
