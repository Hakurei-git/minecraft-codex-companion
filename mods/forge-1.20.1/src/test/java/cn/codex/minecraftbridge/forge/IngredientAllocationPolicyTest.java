package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientAllocationPolicyTest {
    @Test
    void allocatesRepeatedIngredientsAcrossStacks() {
        int[] counts = {1, 2};
        IngredientAllocationPolicy.Result result = IngredientAllocationPolicy.allocate(
            3,
            counts.length,
            ignored -> true,
            (ingredient, slot) -> true,
            slot -> counts[slot]
        );

        assertTrue(result.complete());
        assertEquals(java.util.List.of(0, 1, 1), result.slots());
    }

    @Test
    void reportsTheFirstIngredientWhoseRequiredCountIsMissing() {
        int[] counts = {2, 0};
        IngredientAllocationPolicy.Result result = IngredientAllocationPolicy.allocate(
            3,
            counts.length,
            ignored -> true,
            (ingredient, slot) -> ingredient < 2 && slot == 0,
            slot -> counts[slot]
        );

        assertEquals(2, result.missingIngredientIndex());
        assertEquals(java.util.List.of(0, 0), result.slots());
    }

    @Test
    void consumesTheSmallestMatchingStackSoCraftingCanFreeItsSlot() {
        int[] counts = {4, 2, 64};
        IngredientAllocationPolicy.Result result = IngredientAllocationPolicy.allocate(
            2,
            counts.length,
            ignored -> true,
            (ingredient, slot) -> slot < 2,
            slot -> counts[slot]
        );

        assertTrue(result.complete());
        assertEquals(java.util.List.of(1, 1), result.slots());
    }
}
