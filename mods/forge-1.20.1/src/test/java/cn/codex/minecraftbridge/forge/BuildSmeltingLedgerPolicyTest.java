package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BuildSmeltingLedgerPolicyTest {
    @Test
    void countsInputAlreadyLoadedInTheOwnedFurnace() {
        var ledger = BuildSmeltingLedgerPolicy.calculate(8, 0, 0, 1, 0, 8);

        assertEquals(8, ledger.missingOutput());
        assertEquals(8, ledger.requiredInput());
        assertEquals(8, ledger.availableInput());
        assertEquals(0, ledger.missingInput());
    }

    @Test
    void countsBufferedOutputBeforeRequestingMoreInput() {
        var ledger = BuildSmeltingLedgerPolicy.calculate(8, 0, 2, 1, 0, 6);

        assertEquals(6, ledger.missingOutput());
        assertEquals(6, ledger.requiredInput());
        assertEquals(0, ledger.missingInput());
    }

    @Test
    void recomputesAfterOutputWasCollectedInsteadOfUsingAStaleDeficit() {
        var ledger = BuildSmeltingLedgerPolicy.calculate(8, 2, 0, 1, 6, 0);

        assertEquals(6, ledger.missingOutput());
        assertEquals(6, ledger.requiredInput());
        assertEquals(0, ledger.missingInput());
    }

    @Test
    void respectsRecipesThatProduceMultipleItemsPerInput() {
        var ledger = BuildSmeltingLedgerPolicy.calculate(7, 1, 2, 2, 1, 1);

        assertEquals(4, ledger.missingOutput());
        assertEquals(2, ledger.requiredInput());
        assertEquals(0, ledger.missingInput());
    }
}
