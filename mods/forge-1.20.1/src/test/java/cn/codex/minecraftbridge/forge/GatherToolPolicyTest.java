package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GatherToolPolicyTest {
    @Test
    void selectsTheMinimumVanillaPickaxeTierBeforeDropBasedSearch() {
        assertEquals("minecraft:wooden_pickaxe", GatherToolPolicy.requiredPickaxe("minecraft:coal"));
        assertEquals("minecraft:wooden_pickaxe", GatherToolPolicy.requiredPickaxe("minecraft:cobblestone"));
        assertEquals("minecraft:stone_pickaxe", GatherToolPolicy.requiredPickaxe("minecraft:raw_iron"));
        assertEquals("minecraft:iron_pickaxe", GatherToolPolicy.requiredPickaxe("minecraft:diamond"));
        assertEquals("minecraft:iron_pickaxe", GatherToolPolicy.requiredPickaxe("minecraft:amethyst_shard"));
        assertEquals("minecraft:diamond_pickaxe", GatherToolPolicy.requiredPickaxe("minecraft:obsidian"));
        assertEquals("", GatherToolPolicy.requiredPickaxe("#minecraft:logs"));
    }
}
