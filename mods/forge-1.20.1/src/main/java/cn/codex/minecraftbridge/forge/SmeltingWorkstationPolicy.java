package cn.codex.minecraftbridge.forge;

/**
 * Pure ownership and slot-safety rules for furnaces used by an NPC task.
 *
 * <p>An existing furnace may only be claimed while all three slots are empty.
 * After that, the task may continue using the claimed block only while every
 * occupied slot is compatible with the task.  This keeps player-owned furnace
 * contents out of NPC accounting. On release, a separate bounded transaction
 * ledger may recover only the task's recorded contribution; unknown contents
 * remain untouched.</p>
 */
final class SmeltingWorkstationPolicy {
    enum Validation {
        USABLE,
        UNCLAIMED,
        BLOCK_MISSING,
        INPUT_CONFLICT,
        FUEL_CONFLICT,
        OUTPUT_CONFLICT
    }

    private SmeltingWorkstationPolicy() {}

    static boolean canClaim(boolean inputEmpty, boolean fuelEmpty, boolean outputEmpty) {
        return inputEmpty && fuelEmpty && outputEmpty;
    }

    static Validation validate(
        boolean claimed,
        boolean blockMatches,
        boolean inputCompatible,
        boolean fuelCompatible,
        boolean outputCompatible
    ) {
        if (!claimed) return Validation.UNCLAIMED;
        if (!blockMatches) return Validation.BLOCK_MISSING;
        if (!inputCompatible) return Validation.INPUT_CONFLICT;
        if (!fuelCompatible) return Validation.FUEL_CONFLICT;
        if (!outputCompatible) return Validation.OUTPUT_CONFLICT;
        return Validation.USABLE;
    }

    static boolean canReuse(boolean recipeMatches, Validation validation) {
        return recipeMatches && validation == Validation.USABLE;
    }

    /**
     * {@code loaded} counts inputs committed to the old furnace.  Once that
     * furnace is released, only already collected outputs remain credited.
     */
    static int loadedAfterRelease(int completed) {
        return Math.max(0, completed);
    }
}
