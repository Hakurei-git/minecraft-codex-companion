package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MiningAccessPolicyTest {
    @Test
    void stoneAndKnownVanillaMineralsUseTheBoundedMiningFallback() {
        assertTrue(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("minecraft:cobblestone")));
        assertTrue(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("minecraft:cobbled_deepslate")));
        assertTrue(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("#minecraft:stone_tool_materials")));
        assertTrue(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("minecraft:raw_iron")));
        assertTrue(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("minecraft:diamond")));
        assertFalse(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("#minecraft:logs")));
        assertFalse(MiningAccessPolicy.supportsSelector(ResourceSelectorPolicy.parse("minecraft:oak_log")));
    }

    @Test
    void accessMiningOnlyBreaksNaturalReplaceableGround() {
        assertTrue(MiningAccessPolicy.mayBreakAsAccess("minecraft:dirt", false, false, 0.5F));
        assertTrue(MiningAccessPolicy.mayBreakAsAccess("minecraft:stone", false, false, 1.5F));
        assertFalse(MiningAccessPolicy.mayBreakAsAccess("minecraft:oak_planks", false, false, 2.0F));
        assertFalse(MiningAccessPolicy.mayBreakAsAccess("minecraft:bedrock", false, false, -1.0F));
        assertFalse(MiningAccessPolicy.mayBreakAsAccess("minecraft:stone", false, true, 1.5F));
        assertFalse(MiningAccessPolicy.mayBreakAsAccess("minecraft:air", true, false, 0.0F));
    }
}
