package cn.codex.minecraftbridge.forge;

import java.util.Map;
import java.util.Set;

/**
 * Cheap pre-filter for common vanilla drops. Loot-table evaluation remains
 * authoritative, but obviously unrelated blocks are rejected before invoking
 * it during a wide resource scan.
 */
final class GatherCandidatePolicy {
    private static final Map<String, Set<String>> KNOWN_DROP_BLOCKS = Map.ofEntries(
        Map.entry("minecraft:raw_iron", Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")),
        Map.entry("minecraft:raw_copper", Set.of("minecraft:copper_ore", "minecraft:deepslate_copper_ore")),
        Map.entry("minecraft:raw_gold", Set.of("minecraft:gold_ore", "minecraft:deepslate_gold_ore")),
        Map.entry("minecraft:coal", Set.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore")),
        Map.entry("minecraft:diamond", Set.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")),
        Map.entry("minecraft:emerald", Set.of("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore")),
        Map.entry("minecraft:lapis_lazuli", Set.of("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore")),
        Map.entry("minecraft:redstone", Set.of("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore")),
        Map.entry("minecraft:nether_quartz", Set.of("minecraft:nether_quartz_ore")),
        Map.entry("minecraft:clay_ball", Set.of("minecraft:clay")),
        Map.entry("minecraft:string", Set.of("minecraft:cobweb")),
        Map.entry("minecraft:flint", Set.of("minecraft:gravel")),
        // 1.20.1 still registers the ordinary plant as minecraft:grass. Newer
        // versions renamed it to minecraft:short_grass, so keep both names in
        // the source table instead of making seed gathering depend on the much
        // rarer two-block tall-grass variant.
        Map.entry("minecraft:wheat_seeds", Set.of(
            "minecraft:grass",
            "minecraft:short_grass",
            "minecraft:tall_grass",
            "minecraft:fern",
            "minecraft:large_fern"
        )),
        Map.entry("minecraft:amethyst_shard", Set.of("minecraft:amethyst_cluster"))
    );

    private GatherCandidatePolicy() {
    }

    static boolean mayProduce(String requestedSelector, String blockId, boolean blockIsLog) {
        if ("#minecraft:logs".equals(requestedSelector)) return blockIsLog;
        if (requestedSelector.startsWith("#")) return true;
        if (requestedSelector.equals(blockId)) return true;
        Set<String> known = KNOWN_DROP_BLOCKS.get(requestedSelector);
        return known == null || known.contains(blockId);
    }

    /** A random dry loot-table roll must not make the scanner reject gravel. */
    static boolean isProbabilisticKnownSource(String requestedSelector, String blockId) {
        return ("minecraft:flint".equals(requestedSelector) && "minecraft:gravel".equals(blockId))
            || ("minecraft:wheat_seeds".equals(requestedSelector)
                && Set.of(
                    "minecraft:grass",
                    "minecraft:short_grass",
                    "minecraft:tall_grass",
                    "minecraft:fern",
                    "minecraft:large_fern"
                ).contains(blockId));
    }

    /**
     * Two-block plants must be harvested from their lower half. Breaking the
     * upper half first can remove the plant without producing the probabilistic
     * seed drop, which made dense jungle searches look active but yield zero.
     */
    static boolean harvestablePlantHalf(
        String requestedSelector,
        String blockId,
        boolean upperHalf
    ) {
        if (!"minecraft:wheat_seeds".equals(requestedSelector) || !upperHalf) return true;
        return !Set.of("minecraft:tall_grass", "minecraft:large_fern").contains(blockId);
    }

    static boolean protectedHomeResource(
        boolean sameDimension,
        double distanceSquared,
        int protectionRadius,
        boolean protectedResourceType
    ) {
        if (!sameDimension || !protectedResourceType || protectionRadius < 0) return false;
        double radiusSquared = (double) protectionRadius * protectionRadius;
        return distanceSquared <= radiusSquared;
    }
}
