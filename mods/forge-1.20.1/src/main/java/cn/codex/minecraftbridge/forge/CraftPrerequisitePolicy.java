package cn.codex.minecraftbridge.forge;

final class CraftPrerequisitePolicy {
    private CraftPrerequisitePolicy() {
    }

    static boolean isWoodCraftingIngredient(String itemId) {
        if (itemId == null) return false;
        String normalized = itemId.trim();
        return normalized.equals("minecraft:stick")
            || normalized.endsWith("_planks")
            || normalized.endsWith("_log")
            || normalized.endsWith("_wood")
            || normalized.endsWith("_stem")
            || normalized.endsWith("_hyphae")
            || normalized.equals("minecraft:bamboo");
    }

    static boolean isStoneCraftingIngredient(String itemId) {
        if (itemId == null) return false;
        String normalized = itemId.trim();
        return normalized.equals("minecraft:cobblestone")
            || normalized.equals("minecraft:cobbled_deepslate")
            || normalized.equals("minecraft:blackstone");
    }

    static int logsNeededForWoodUnits(int missingWoodIngredientSlots) {
        if (missingWoodIngredientSlots <= 0) return 0;
        return (int) Math.ceil(missingWoodIngredientSlots / 4.0D);
    }

    static int stoneNeededForStoneUnits(int missingStoneIngredientSlots) {
        return Math.max(0, missingStoneIngredientSlots);
    }
}
