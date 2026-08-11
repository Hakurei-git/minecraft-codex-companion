package cn.codex.minecraftbridge.forge;

/** Count arithmetic for gathering a complete multi-output crafting request. */
final class CraftBatchPolicy {
    private CraftBatchPolicy() {
    }

    static int remainingBatches(int requestedOutputs, int completedOutputs, int outputPerBatch) {
        if (requestedOutputs <= 0) throw new IllegalArgumentException("requestedOutputs must be positive");
        if (completedOutputs < 0) throw new IllegalArgumentException("completedOutputs must not be negative");
        if (outputPerBatch <= 0) throw new IllegalArgumentException("outputPerBatch must be positive");
        int remaining = Math.max(0, requestedOutputs - completedOutputs);
        return remaining == 0 ? 0 : ceilDiv(remaining, outputPerBatch);
    }

    static int ingredientTarget(int available, int ingredientSlotsPerBatch, int batches) {
        if (available < 0) throw new IllegalArgumentException("available must not be negative");
        if (ingredientSlotsPerBatch <= 0) {
            throw new IllegalArgumentException("ingredientSlotsPerBatch must be positive");
        }
        if (batches < 0) throw new IllegalArgumentException("batches must not be negative");
        int required = saturatedMultiply(ingredientSlotsPerBatch, batches);
        return Math.max(available, required);
    }

    static int ingredientDeficit(int available, int ingredientSlotsPerBatch, int batches) {
        int target = ingredientTarget(available, ingredientSlotsPerBatch, batches);
        return Math.max(0, target - available);
    }

    private static int ceilDiv(int value, int divisor) {
        return (int) (((long) value + divisor - 1L) / divisor);
    }

    private static int saturatedMultiply(int left, int right) {
        long product = (long) left * right;
        return product >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }
}
