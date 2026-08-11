package cn.codex.minecraftbridge.forge;

import java.util.Locale;

final class HomeStoragePolicy {
    static final int DEFAULT_RADIUS = 24;
    static final int MIN_RADIUS = 8;
    static final int MAX_RADIUS = 64;
    static final double AUTO_STORE_FILL_RATIO = 0.80;
    static final int AUTO_STORE_MIN_FREE_SLOTS = 6;
    static final int CRAFTING_TABLE_PLANKS = 4;
    static final int CHEST_PLANKS = 8;
    static final int MAX_STALLED_PATH_TICKS = 200;
    static final int MAX_RETRIEVE_BATCH_CONTAINERS = 12;

    enum Category {
        FOOD, WEAPONS, ARMOR, TOOLS, WOOD, BUILDING, MINERALS, REDSTONE,
        FARMING, MOB_DROPS, POTIONS, VALUABLES, MISC
    }

    enum RetrievalDecision { READY, ITEMS_MISSING, INVENTORY_FULL }

    private HomeStoragePolicy() {}

    static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }

    static boolean shouldAutoStore(int occupiedSlots, int totalSlots) {
        if (totalSlots <= 0) return false;
        int free = Math.max(0, totalSlots - occupiedSlots);
        return occupiedSlots / (double) totalSlots >= AUTO_STORE_FILL_RATIO || free < AUTO_STORE_MIN_FREE_SLOTS;
    }

    /**
     * Whether the NPC has enough local resources to create another storage
     * container.  A pre-existing chest needs no planks; otherwise a crafting
     * table costs four planks and the chest itself costs eight, just like a
     * player crafting both vanilla recipes.
     */
    static boolean canExpandStorage(int chestCount, int plankCount, boolean hasCraftingTable, boolean creative) {
        if (creative || chestCount > 0) return true;
        int required = CHEST_PLANKS + (hasCraftingTable ? 0 : CRAFTING_TABLE_PLANKS);
        return plankCount >= required;
    }

    static RetrievalDecision retrievalDecision(int available, int requested, boolean inventoryFits) {
        if (requested <= 0 || available < requested) return RetrievalDecision.ITEMS_MISSING;
        return inventoryFits ? RetrievalDecision.READY : RetrievalDecision.INVENTORY_FULL;
    }

    static int usableExpansionMaterial(int total, int requestedOverlap, int pendingRequested) {
        int reserved = Math.min(Math.max(0, requestedOverlap), Math.max(0, pendingRequested));
        return Math.max(0, total - reserved);
    }

    static boolean shouldRecoverStoragePath(int stalledTicks) {
        return stalledTicks > MAX_STALLED_PATH_TICKS;
    }

    static boolean mayUseCheatPathRecovery(boolean cheatsEnabled, boolean alreadyTeleportedToTarget) {
        return cheatsEnabled && !alreadyTeleportedToTarget;
    }

    static boolean mayBatchRetrieve(double distance, double reach, int containersAlreadyProcessed) {
        return Double.isFinite(distance)
            && Double.isFinite(reach)
            && reach > 0.0D
            && distance <= reach
            && containersAlreadyProcessed >= 0
            && containersAlreadyProcessed < MAX_RETRIEVE_BATCH_CONTAINERS;
    }

    static boolean shouldRetain(
        boolean equipped,
        boolean locked,
        boolean customNamed,
        boolean taskRequired,
        boolean bestGear,
        boolean rareOrEnchanted
    ) {
        return equipped || locked || customNamed || taskRequired || bestGear || rareOrEnchanted;
    }

    static boolean shouldRetainBackpackGear(boolean bestWorkingGear, boolean rareOrEnchanted) {
        return bestWorkingGear || rareOrEnchanted;
    }

    static boolean isRareCarryItem(String itemId) {
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        return containsAny(id, "netherite", "diamond", "totem_of_undying", "elytra", "enchanted_book");
    }

    static Category category(String itemId, boolean food, boolean enchanted) {
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        if (enchanted || isRareCarryItem(id)) return Category.VALUABLES;
        if (food) return Category.FOOD;
        if (containsAny(id, "_sword", "_bow", "crossbow", "trident", "mace", "shield")) return Category.WEAPONS;
        if (containsAny(id, "_helmet", "_chestplate", "_leggings", "_boots")) return Category.ARMOR;
        if (containsAny(id, "_pickaxe", "_axe", "_shovel", "_hoe", "shears", "fishing_rod", "flint_and_steel")) return Category.TOOLS;
        if (containsAny(id, "_log", "_wood", "_planks", "_stem", "_hyphae", "bamboo")) return Category.WOOD;
        if (containsAny(id, "ore", "raw_", "_ingot", "_nugget", "coal", "lapis", "quartz", "amethyst")) return Category.MINERALS;
        if (containsAny(id, "redstone", "repeater", "comparator", "observer", "piston", "hopper", "dispenser", "dropper")) return Category.REDSTONE;
        if (containsAny(id, "seed", "sapling", "crop", "bone_meal", "wheat", "carrot", "potato")) return Category.FARMING;
        if (containsAny(id, "rotten_flesh", "bone", "string", "spider_eye", "gunpowder", "slime_ball", "ender_pearl")) return Category.MOB_DROPS;
        if (containsAny(id, "potion", "glass_bottle", "blaze_powder", "nether_wart")) return Category.POTIONS;
        if (containsAny(id, "stone", "brick", "concrete", "terracotta", "glass", "dirt", "sand", "gravel")) return Category.BUILDING;
        return Category.MISC;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
