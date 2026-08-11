package cn.codex.minecraftbridge.forge;

/** Pure accounting for one build-material smelting goal. */
final class BuildSmeltingLedgerPolicy {
    record Ledger(int missingOutput, int requiredInput, int availableInput, int missingInput) {}

    private BuildSmeltingLedgerPolicy() {}

    static Ledger calculate(
        int targetOutput,
        int inventoryOutput,
        int bufferedOutput,
        int outputPerBatch,
        int inventoryInput,
        int bufferedInput
    ) {
        int normalizedBatch = Math.max(1, outputPerBatch);
        int missingOutput = Math.max(0,
            Math.max(0, targetOutput) - Math.max(0, inventoryOutput) - Math.max(0, bufferedOutput));
        int requiredInput = missingOutput == 0
            ? 0
            : (missingOutput + normalizedBatch - 1) / normalizedBatch;
        int availableInput = Math.max(0, inventoryInput) + Math.max(0, bufferedInput);
        return new Ledger(
            missingOutput,
            requiredInput,
            availableInput,
            Math.max(0, requiredInput - availableInput)
        );
    }
}
