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
        assertEquals(8, NpcFoodReservePolicy.taskTarget(8, 14, false));
        assertEquals(8, NpcFoodReservePolicy.taskTarget(8, 8, true));
        assertEquals(12, NpcFoodReservePolicy.taskTarget(8, 12, true));
        assertEquals(0, NpcFoodReservePolicy.taskTarget(0, 16, false));
        assertEquals(64, NpcFoodReservePolicy.taskTarget(80, 80, false));
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
