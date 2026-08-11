package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatherProgressPolicyTest {
    @Test
    void ignoresExistingInventoryAndLooseItemsPickedUpBeforeTheBreak() {
        int completed = 0;

        // The NPC starts with 601 logs and vacuums 236 unrelated loose logs.
        // Only the one log obtained after the actual break advances the task.
        completed = GatherProgressPolicy.afterBreak(completed, 837, 838);

        assertEquals(1, completed);
    }

    @Test
    void accumulatesDropsAcrossBreaksAndIgnoresNonMatchingAccessBlocks() {
        int completed = GatherProgressPolicy.afterBreak(2, 40, 43);
        completed = GatherProgressPolicy.afterBreak(completed, 43, 43);

        assertEquals(5, completed);
    }

    @Test
    void rawBreakCounterDoesNotMoveBackwardsInsideOneBreakObservation() {
        assertEquals(7, GatherProgressPolicy.afterBreak(7, 50, 45));
    }

    @Test
    void retainedProgressFallsWhenTaskOutputsLeaveTheInventory() {
        assertEquals(7, GatherProgressPolicy.retained(7, 10, 17));
        assertEquals(4, GatherProgressPolicy.retained(7, 10, 14));
        assertEquals(0, GatherProgressPolicy.retained(7, 10, 8));
        assertEquals(2, GatherProgressPolicy.retained(2, 10, 40));
    }

    @Test
    void externalSupplyAfterThePhaseStartsMustStillBeRetained() {
        assertEquals(4, GatherProgressPolicy.includingExternalSupply(1, 12, 16));
        assertEquals(0, GatherProgressPolicy.includingExternalSupply(3, 12, 10));
    }
}
