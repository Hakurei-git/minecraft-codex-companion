package cn.codex.minecraftbridge.forge;

import java.util.Map;

/**
 * Pure provenance limits for recovering the contents of a task-claimed furnace.
 *
 * <p>The runtime creates a ledger only after observing a completely empty
 * furnace.  Recovery is therefore limited to item ids and quantities that the
 * task subsequently committed.  Unknown contents, and same-kind surplus beyond
 * the recorded contribution, always remain in the furnace.</p>
 */
final class FurnaceRecoveryPolicy {
    static final int INPUT_SLOT = 0;
    static final int FUEL_SLOT = 1;
    static final int OUTPUT_SLOT = 2;

    private FurnaceRecoveryPolicy() {}

    static int recoverableCount(
        int slot,
        String actualItemId,
        int actualCount,
        String inputItemId,
        int inputDeposited,
        String outputItemId,
        int outputPerInput,
        int outputWithdrawn,
        Map<String, Integer> fuelDeposited
    ) {
        if (actualItemId == null || actualItemId.isBlank() || actualCount <= 0) return 0;
        int budget = switch (slot) {
            case INPUT_SLOT -> actualItemId.equals(inputItemId) ? Math.max(0, inputDeposited) : 0;
            case FUEL_SLOT -> fuelBudget(actualItemId, fuelDeposited);
            case OUTPUT_SLOT -> actualItemId.equals(outputItemId)
                ? remainingOutputBudget(inputDeposited, outputPerInput, outputWithdrawn)
                : 0;
            default -> 0;
        };
        return Math.min(actualCount, budget);
    }

    static int remainingOutputBudget(int inputDeposited, int outputPerInput, int outputWithdrawn) {
        long producedLimit = (long) Math.max(0, inputDeposited) * Math.max(1, outputPerInput);
        long remaining = Math.max(0L, producedLimit - Math.max(0, outputWithdrawn));
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static int fuelBudget(String actualItemId, Map<String, Integer> fuelDeposited) {
        if (fuelDeposited == null || fuelDeposited.isEmpty()) return 0;
        int direct = Math.max(0, fuelDeposited.getOrDefault(actualItemId, 0));
        if (direct > 0) return direct;
        // A lava bucket is consumed as fuel but leaves its empty bucket in the
        // same slot.  That residue is task-owned up to the deposited quantity.
        if (actualItemId.equals("minecraft:bucket")) {
            return Math.max(0, fuelDeposited.getOrDefault("minecraft:lava_bucket", 0));
        }
        return 0;
    }
}
