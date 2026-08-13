package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CraftingIngredientAllocatorTest {
    @Test
    void acceptsAnOutputWhenIngredientConsumptionFreesTheOnlySlot() {
        List<CraftingOutputSpacePolicy.Slot> full = List.of(
            new CraftingOutputSpacePolicy.Slot(0, "cobbled_deepslate", 64, 64),
            new CraftingOutputSpacePolicy.Slot(1, "stick", 2, 64)
        );

        assertTrue(CraftingOutputSpacePolicy.canInsertAfterConsumption(
            2,
            full,
            List.of(0, 0, 0, 1, 1),
            "stone_pickaxe",
            1,
            1
        ));
    }

    @Test
    void rejectsAnOutputWhenConsumptionLeavesEverySlotOccupied() {
        List<CraftingOutputSpacePolicy.Slot> full = List.of(
            new CraftingOutputSpacePolicy.Slot(0, "cobbled_deepslate", 64, 64),
            new CraftingOutputSpacePolicy.Slot(1, "stick", 64, 64)
        );

        assertFalse(CraftingOutputSpacePolicy.canInsertAfterConsumption(
            2,
            full,
            List.of(0, 0, 0, 1, 1),
            "stone_pickaxe",
            1,
            1
        ));
    }

    @Test
    void mergesCraftOutputIntoACompatiblePartiallyFilledStack() {
        List<CraftingOutputSpacePolicy.Slot> full = List.of(
            new CraftingOutputSpacePolicy.Slot(0, "planks", 64, 64),
            new CraftingOutputSpacePolicy.Slot(1, "stick", 60, 64)
        );

        assertTrue(CraftingOutputSpacePolicy.canInsertAfterConsumption(
            2,
            full,
            List.of(0),
            "stick",
            4,
            64
        ));
    }
}
