package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CraftBatchPolicyTest {
    @Test
    void sixtyFourTorchesPlanSixteenCompleteRecipeBatches() {
        assertEquals(16, CraftBatchPolicy.remainingBatches(64, 0, 4));
        assertEquals(16, CraftBatchPolicy.ingredientTarget(0, 1, 16));
        assertEquals(16, CraftBatchPolicy.ingredientDeficit(0, 1, 16));
    }

    @Test
    void completedOutputsReduceAndEliminateTheCurrentBatch() {
        assertEquals(15, CraftBatchPolicy.remainingBatches(64, 4, 4));
        assertEquals(0, CraftBatchPolicy.remainingBatches(1, 1, 1));
        assertEquals(0, CraftBatchPolicy.remainingBatches(64, 80, 4));
        assertEquals(5, CraftBatchPolicy.ingredientTarget(5, 1, 1));
        assertEquals(0, CraftBatchPolicy.ingredientDeficit(5, 1, 0));
    }

    @Test
    void roundsEveryMultiOutputVanillaBatchWithoutOvercrafting() {
        assertEquals(16, CraftBatchPolicy.remainingBatches(64, 0, 4), "torches");
        assertEquals(16, CraftBatchPolicy.remainingBatches(64, 0, 4), "arrows");
        assertEquals(2, CraftBatchPolicy.remainingBatches(4, 0, 3), "doors/ladder");
        assertEquals(1, CraftBatchPolicy.remainingBatches(2, 0, 3), "doors/ladder");
        assertEquals(2, CraftBatchPolicy.remainingBatches(18, 1, 16), "glass panes");
        assertEquals(1, CraftBatchPolicy.remainingBatches(2, 0, 2), "lead");

        assertEquals(48, CraftBatchPolicy.ingredientTarget(2, 3, 16));
        assertEquals(46, CraftBatchPolicy.ingredientDeficit(2, 3, 16));
        assertEquals(7, CraftBatchPolicy.ingredientTarget(7, 1, 0));
    }

    @Test
    void saturatesLargeIngredientProductsInsteadOfWrappingNegative() {
        assertEquals(Integer.MAX_VALUE, CraftBatchPolicy.ingredientTarget(
            0,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE
        ));
        assertEquals(Integer.MAX_VALUE, CraftBatchPolicy.ingredientDeficit(
            0,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE
        ));
    }

    @Test
    void rejectsInvalidCountsRatherThanSilentlyInventingOneBatch() {
        assertThrows(IllegalArgumentException.class,
            () -> CraftBatchPolicy.remainingBatches(0, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> CraftBatchPolicy.remainingBatches(1, -1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> CraftBatchPolicy.remainingBatches(1, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> CraftBatchPolicy.ingredientTarget(-1, 1, 1));
        assertThrows(IllegalArgumentException.class,
            () -> CraftBatchPolicy.ingredientTarget(0, 0, 1));
        assertThrows(IllegalArgumentException.class,
            () -> CraftBatchPolicy.ingredientTarget(0, 1, -1));
    }
}
