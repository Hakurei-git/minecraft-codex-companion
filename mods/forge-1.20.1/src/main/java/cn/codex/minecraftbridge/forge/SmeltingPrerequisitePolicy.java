package cn.codex.minecraftbridge.forge;

final class SmeltingPrerequisitePolicy {
    static final int FURNACE_STONE_COUNT = 8;
    static final int MAX_FALLBACK_FUEL_LOG_BATCH = 16;

    private SmeltingPrerequisitePolicy() {}

    static int missingInput(int requested, int loaded, int carried) {
        return Math.max(0, requested - Math.max(0, loaded) - Math.max(0, carried));
    }

    static int missingFurnaceStone(int available) {
        return Math.max(0, FURNACE_STONE_COUNT - Math.max(0, available));
    }

    static int fallbackFuelLogs(int remainingSmelts) {
        if (remainingSmelts <= 0) return 0;
        int logs = (remainingSmelts * 2 + 2) / 3;
        return Math.min(MAX_FALLBACK_FUEL_LOG_BATCH, Math.max(1, logs));
    }

    static int preferredCoalFuelItems(int remainingSmelts) {
        if (remainingSmelts <= 0) return 0;
        return Math.max(1, (remainingSmelts + 7) / 8);
    }

    static boolean shouldSupplyFuel(boolean fuelSlotEmpty, boolean furnaceLit) {
        return fuelSlotEmpty && !furnaceLit;
    }

    static String requiredPickaxe(String inputId) {
        return GatherToolPolicy.requiredPickaxe(inputId);
    }

    static int safeFuelPriority(String itemId, boolean fuel, boolean damageable, boolean log, boolean plank) {
        if (!fuel || damageable) return Integer.MAX_VALUE;
        if (itemId.equals("minecraft:coal") || itemId.equals("minecraft:charcoal")) return 0;
        if (log) return 10;
        if (plank) return 20;
        if (itemId.equals("minecraft:stick") || itemId.equals("minecraft:bamboo")) return 30;
        return 100;
    }
}
