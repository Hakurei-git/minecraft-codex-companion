package cn.codex.minecraftbridge.forge;

import java.util.Set;

final class MiningAccessPolicy {
    private static final Set<String> SUPPORTED_TAG_SELECTORS = Set.of(
        "minecraft:stone_tool_materials",
        "minecraft:stone_crafting_materials",
        "forge:cobblestone"
    );
    private static final Set<String> SUPPORTED_ITEM_SELECTORS = Set.of(
        "minecraft:cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:blackstone",
        "minecraft:coal",
        "minecraft:raw_iron",
        "minecraft:raw_copper",
        "minecraft:raw_gold",
        "minecraft:lapis_lazuli",
        "minecraft:redstone",
        "minecraft:diamond",
        "minecraft:emerald"
    );
    private static final Set<String> SAFE_ACCESS_BLOCKS = Set.of(
        "minecraft:stone",
        "minecraft:deepslate",
        "minecraft:granite",
        "minecraft:diorite",
        "minecraft:andesite",
        "minecraft:tuff",
        "minecraft:calcite",
        "minecraft:dripstone_block",
        "minecraft:cobblestone",
        "minecraft:mossy_cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:blackstone",
        "minecraft:dirt",
        "minecraft:grass_block",
        "minecraft:coarse_dirt",
        "minecraft:rooted_dirt",
        "minecraft:podzol",
        "minecraft:mycelium",
        "minecraft:mud",
        "minecraft:clay",
        "minecraft:gravel",
        "minecraft:sand",
        "minecraft:red_sand"
    );

    private MiningAccessPolicy() {
    }

    static boolean supportsSelector(ResourceSelectorPolicy.Parsed selector) {
        return selector.tag()
            ? SUPPORTED_TAG_SELECTORS.contains(selector.resourceId())
            : SUPPORTED_ITEM_SELECTORS.contains(selector.resourceId());
    }

    static boolean mayBreakAsAccess(String blockId, boolean air, boolean fluid, float destroySpeed) {
        return !air && !fluid && destroySpeed >= 0.0F && SAFE_ACCESS_BLOCKS.contains(blockId);
    }
}
