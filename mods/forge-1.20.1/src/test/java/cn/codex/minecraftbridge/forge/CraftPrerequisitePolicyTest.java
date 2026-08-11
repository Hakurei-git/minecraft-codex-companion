package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CraftPrerequisitePolicyTest {
    @Test
    void recognizesWoodBackedCraftingIngredients() {
        assertTrue(CraftPrerequisitePolicy.isWoodCraftingIngredient("minecraft:oak_planks"));
        assertTrue(CraftPrerequisitePolicy.isWoodCraftingIngredient("minecraft:stick"));
        assertTrue(CraftPrerequisitePolicy.isWoodCraftingIngredient("minecraft:birch_log"));
        assertTrue(CraftPrerequisitePolicy.isWoodCraftingIngredient("minecraft:warped_stem"));
        assertTrue(CraftPrerequisitePolicy.isWoodCraftingIngredient("minecraft:crimson_hyphae"));
        assertFalse(CraftPrerequisitePolicy.isWoodCraftingIngredient("minecraft:iron_ingot"));
    }

    @Test
    void recognizesStoneBackedCraftingIngredients() {
        assertTrue(CraftPrerequisitePolicy.isStoneCraftingIngredient("minecraft:cobblestone"));
        assertTrue(CraftPrerequisitePolicy.isStoneCraftingIngredient("minecraft:cobbled_deepslate"));
        assertTrue(CraftPrerequisitePolicy.isStoneCraftingIngredient("minecraft:blackstone"));
        assertFalse(CraftPrerequisitePolicy.isStoneCraftingIngredient("minecraft:stone"));
    }

    @Test
    void estimatesRawGatherCountsForPlayerLikeChains() {
        assertEquals(1, CraftPrerequisitePolicy.logsNeededForWoodUnits(1));
        assertEquals(1, CraftPrerequisitePolicy.logsNeededForWoodUnits(4));
        assertEquals(2, CraftPrerequisitePolicy.logsNeededForWoodUnits(8));
        assertEquals(8, CraftPrerequisitePolicy.stoneNeededForStoneUnits(8));
        assertEquals(0, CraftPrerequisitePolicy.logsNeededForWoodUnits(0));
        assertEquals(0, CraftPrerequisitePolicy.logsNeededForWoodUnits(-4));
        assertEquals(0, CraftPrerequisitePolicy.stoneNeededForStoneUnits(0));
    }
}
