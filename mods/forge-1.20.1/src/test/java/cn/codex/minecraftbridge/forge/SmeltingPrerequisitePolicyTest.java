package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SmeltingPrerequisitePolicyTest {
    @Test
    void countsOnlyInputsThatStillNeedToBeAcquired() {
        assertEquals(5, SmeltingPrerequisitePolicy.missingInput(8, 2, 1));
        assertEquals(0, SmeltingPrerequisitePolicy.missingInput(8, 8, 0));
        assertEquals(0, SmeltingPrerequisitePolicy.missingInput(8, 2, 12));
    }

    @Test
    void gathersOnlyTheMissingFurnaceStone() {
        assertEquals(8, SmeltingPrerequisitePolicy.missingFurnaceStone(0));
        assertEquals(3, SmeltingPrerequisitePolicy.missingFurnaceStone(5));
        assertEquals(0, SmeltingPrerequisitePolicy.missingFurnaceStone(12));
    }

    @Test
    void boundsFallbackLogFuelBatches() {
        assertEquals(0, SmeltingPrerequisitePolicy.fallbackFuelLogs(0));
        assertEquals(1, SmeltingPrerequisitePolicy.fallbackFuelLogs(1));
        assertEquals(2, SmeltingPrerequisitePolicy.fallbackFuelLogs(3));
        assertEquals(16, SmeltingPrerequisitePolicy.fallbackFuelLogs(256));
    }

    @Test
    void requestsOnlyEnoughPreferredCoalForTheRemainingSmelts() {
        assertEquals(0, SmeltingPrerequisitePolicy.preferredCoalFuelItems(0));
        assertEquals(1, SmeltingPrerequisitePolicy.preferredCoalFuelItems(1));
        assertEquals(1, SmeltingPrerequisitePolicy.preferredCoalFuelItems(8));
        assertEquals(2, SmeltingPrerequisitePolicy.preferredCoalFuelItems(9));
    }

    @Test
    void selectsVanillaMiningTierForSmeltableOreInputs() {
        assertEquals("minecraft:stone_pickaxe", SmeltingPrerequisitePolicy.requiredPickaxe("minecraft:raw_iron"));
        assertEquals("minecraft:stone_pickaxe", SmeltingPrerequisitePolicy.requiredPickaxe("minecraft:raw_copper"));
        assertEquals("minecraft:iron_pickaxe", SmeltingPrerequisitePolicy.requiredPickaxe("minecraft:raw_gold"));
        assertEquals("minecraft:diamond_pickaxe", SmeltingPrerequisitePolicy.requiredPickaxe("minecraft:ancient_debris"));
        assertEquals("", SmeltingPrerequisitePolicy.requiredPickaxe("minecraft:sand"));
    }

    @Test
    void neverBurnsDamageableToolsAndPrefersOrdinaryFuel() {
        assertEquals(Integer.MAX_VALUE, SmeltingPrerequisitePolicy.safeFuelPriority(
            "minecraft:wooden_pickaxe", true, true, false, false
        ));
        assertEquals(0, SmeltingPrerequisitePolicy.safeFuelPriority(
            "minecraft:coal", true, false, false, false
        ));
        assertEquals(10, SmeltingPrerequisitePolicy.safeFuelPriority(
            "minecraft:oak_log", true, false, true, false
        ));
        assertEquals(20, SmeltingPrerequisitePolicy.safeFuelPriority(
            "minecraft:oak_planks", true, false, false, true
        ));
    }

    @Test
    void refuelsOnlyWhenTheSlotIsEmptyAndTheFurnaceIsNotAlreadyBurning() {
        assertTrue(SmeltingPrerequisitePolicy.shouldSupplyFuel(true, false));
        assertFalse(SmeltingPrerequisitePolicy.shouldSupplyFuel(true, true));
        assertFalse(SmeltingPrerequisitePolicy.shouldSupplyFuel(false, false));
        assertFalse(SmeltingPrerequisitePolicy.shouldSupplyFuel(false, true));
    }
}
