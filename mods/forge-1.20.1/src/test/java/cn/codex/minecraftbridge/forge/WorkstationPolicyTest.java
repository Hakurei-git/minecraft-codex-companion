package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkstationPolicyTest {
    @Test
    void usesPlayerEquivalentFallbackMaterialsForBasicStations() {
        WorkstationPolicy.MaterialCost table = WorkstationPolicy.fallbackMaterialCost("minecraft:crafting_table");
        WorkstationPolicy.MaterialCost furnace = WorkstationPolicy.fallbackMaterialCost("minecraft:furnace");
        assertEquals("#minecraft:planks", table.selector());
        assertEquals(4, table.count());
        assertEquals("#minecraft:stone_crafting_materials", furnace.selector());
        assertEquals(8, furnace.count());
        assertNull(WorkstationPolicy.fallbackMaterialCost("minecraft:blast_furnace"));
    }

    @Test
    void neverSynthesizesAComplexStationWithoutItsItem() {
        assertTrue(WorkstationPolicy.canSupply(true, 0, false, null));
        assertTrue(WorkstationPolicy.canSupply(false, 0, true, null));
        assertFalse(WorkstationPolicy.canSupply(false, 64, false, null));
        assertFalse(WorkstationPolicy.canSupply(false, 3, false, WorkstationPolicy.fallbackMaterialCost("minecraft:crafting_table")));
        assertTrue(WorkstationPolicy.canSupply(false, 4, false, WorkstationPolicy.fallbackMaterialCost("minecraft:crafting_table")));
    }

    @Test
    void retriesDirectPlacementWheneverTheRequestedBlockIsStillAbsent() {
        assertTrue(WorkstationPolicy.shouldAttemptDirectPlacement(false));
        assertFalse(WorkstationPolicy.shouldAttemptDirectPlacement(true));
    }
}
