package cn.codex.minecraftbridge.forge;

import java.util.List;

/** Ordered home-storage lookups before gathering or crafting a fishing rod. */
final class FishingRodPrerequisitePolicy {
    static final int REQUIRED_STRING = 2;
    static final int REQUIRED_STICKS = 3;

    private static final List<Supply> STORAGE_SUPPLIES = List.of(
        new Supply("minecraft:fishing_rod", 1, "钓鱼竿"),
        new Supply("minecraft:string", REQUIRED_STRING, "线"),
        new Supply("minecraft:stick", REQUIRED_STICKS, "木棍"),
        new Supply("#minecraft:planks", 2, "木板"),
        new Supply("#minecraft:logs", 1, "原木")
    );

    private FishingRodPrerequisitePolicy() {
    }

    static Supply storageSupply(int phase) {
        return phase < 0 || phase >= STORAGE_SUPPLIES.size() ? null : STORAGE_SUPPLIES.get(phase);
    }

    static int storagePhaseCount() {
        return STORAGE_SUPPLIES.size();
    }

    static int missingString(int available) {
        return missingString(available, 1);
    }

    static int missingString(int available, int rods) {
        if (rods <= 0) return 0;
        long required = (long) REQUIRED_STRING * rods;
        return (int) Math.min(
            Integer.MAX_VALUE,
            Math.max(0L, required - Math.max(0, available))
        );
    }

    static boolean directIngredientsReady(int sticks, int string, int rods) {
        if (rods <= 0) return true;
        return Math.max(0L, sticks) >= (long) REQUIRED_STICKS * rods
            && Math.max(0L, string) >= (long) REQUIRED_STRING * rods;
    }

    record Supply(String selector, int required, String label) {
    }
}
