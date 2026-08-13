package cn.codex.minecraftbridge.forge;

final class WorkstationPolicy {
    record MaterialCost(String selector, int count) {}

    private WorkstationPolicy() {}

    static MaterialCost fallbackMaterialCost(String workstationId) {
        return switch (workstationId) {
            case "minecraft:crafting_table" -> new MaterialCost("#minecraft:planks", 4);
            case "minecraft:furnace" -> new MaterialCost("#minecraft:stone_crafting_materials", 8);
            default -> null;
        };
    }

    static boolean canSupply(boolean hasWorkstationItem, int materialCount, boolean creative, MaterialCost fallback) {
        return creative || hasWorkstationItem || fallback != null && materialCount >= fallback.count();
    }

    /**
     * An interaction can report SUCCESS because the support block opened its
     * menu while the requested workstation was never placed.  The block state,
     * not the interaction result, is authoritative.
     */
    static boolean shouldAttemptDirectPlacement(boolean requestedBlockPresent) {
        return !requestedBlockPresent;
    }
}
