package cn.codex.minecraftbridge.forge;

final class GatherToolPolicy {
    private GatherToolPolicy() {}

    static String requiredPickaxe(String itemId) {
        return switch (itemId) {
            case "minecraft:cobblestone", "minecraft:stone", "minecraft:coal", "minecraft:coal_ore",
                 "minecraft:deepslate_coal_ore", "minecraft:nether_quartz", "minecraft:nether_quartz_ore" ->
                "minecraft:wooden_pickaxe";
            case "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                 "minecraft:raw_copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore",
                 "minecraft:lapis_lazuli", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore" ->
                "minecraft:stone_pickaxe";
            case "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
                 "minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                 "minecraft:emerald", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
                 "minecraft:redstone", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
                 "minecraft:amethyst_shard", "minecraft:amethyst_cluster" ->
                "minecraft:iron_pickaxe";
            case "minecraft:obsidian", "minecraft:ancient_debris" -> "minecraft:diamond_pickaxe";
            default -> "";
        };
    }
}
