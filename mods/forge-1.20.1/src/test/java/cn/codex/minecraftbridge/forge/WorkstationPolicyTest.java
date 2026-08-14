package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;

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

    @Test
    void relocatesInsteadOfRecursingWhenAnAlcoveNeedsTheToolBeingCrafted() {
        assertTrue(WorkstationPolicy.shouldRelocateBlockedMiningPlacement(true, false));
        assertFalse(WorkstationPolicy.shouldRelocateBlockedMiningPlacement(true, true));
        assertFalse(WorkstationPolicy.shouldRelocateBlockedMiningPlacement(false, false));
    }

    @Test
    void rejectsEveryCellThatCanCloseAOneBlockMiningTunnel() {
        BlockPos feet = new BlockPos(10, -54, 10);
        BlockPos next = feet.north();
        Set<BlockPos> corridor = Set.of(
            feet,
            feet.above(),
            next,
            next.above(),
            feet.south(),
            feet.south().above()
        );

        assertFalse(WorkstationPolicy.allowsTemporaryPlacement(feet, feet, next, corridor));
        assertFalse(WorkstationPolicy.allowsTemporaryPlacement(feet.above(), feet, next, corridor));
        assertFalse(WorkstationPolicy.allowsTemporaryPlacement(next, feet, next, corridor));
        assertFalse(WorkstationPolicy.allowsTemporaryPlacement(feet.south(), feet, next, corridor));
    }

    @Test
    void prefersASideAlcoveWithoutClosingTheTunnel() {
        BlockPos feet = new BlockPos(10, -54, 10);
        BlockPos side = feet.east();
        BlockPos sideRear = feet.south().east();
        Set<BlockPos> corridor = Set.of(feet, feet.north(), feet.south());

        assertTrue(WorkstationPolicy.allowsTemporaryPlacement(side, feet, feet.north(), corridor));
        assertEquals(0, WorkstationPolicy.miningPlacementRank(side, feet, Direction.NORTH));
        assertEquals(1, WorkstationPolicy.miningPlacementRank(sideRear, feet, Direction.NORTH));
        assertEquals(Integer.MAX_VALUE,
            WorkstationPolicy.miningPlacementRank(feet.north(), feet, Direction.NORTH));
    }

    @Test
    void onlyRecoversOwnedObstructionsAndRelocatesWhenStillNeeded() {
        assertEquals(WorkstationPolicy.BlockingAction.KEEP,
            WorkstationPolicy.blockingAction(false, true, true, true));
        assertEquals(WorkstationPolicy.BlockingAction.KEEP,
            WorkstationPolicy.blockingAction(true, false, true, true));
        assertEquals(WorkstationPolicy.BlockingAction.KEEP,
            WorkstationPolicy.blockingAction(true, true, false, true));
        assertEquals(WorkstationPolicy.BlockingAction.RECOVER,
            WorkstationPolicy.blockingAction(true, true, true, false));
        assertEquals(WorkstationPolicy.BlockingAction.RECOVER_AND_RELOCATE,
            WorkstationPolicy.blockingAction(true, true, true, true));
    }
}
