package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class FishingRodPrerequisitePolicyTest {
    @Test
    void checksFinishedRodAndIngredientsInPlayerLikeOrder() {
        assertEquals("minecraft:fishing_rod", FishingRodPrerequisitePolicy.storageSupply(0).selector());
        assertEquals("minecraft:string", FishingRodPrerequisitePolicy.storageSupply(1).selector());
        assertEquals(2, FishingRodPrerequisitePolicy.storageSupply(1).required());
        assertEquals("minecraft:stick", FishingRodPrerequisitePolicy.storageSupply(2).selector());
        assertEquals("#minecraft:planks", FishingRodPrerequisitePolicy.storageSupply(3).selector());
        assertEquals("#minecraft:logs", FishingRodPrerequisitePolicy.storageSupply(4).selector());
        assertNull(FishingRodPrerequisitePolicy.storageSupply(
            FishingRodPrerequisitePolicy.storagePhaseCount()
        ));
    }

    @Test
    void requestsOnlyTheMissingString() {
        assertEquals(2, FishingRodPrerequisitePolicy.missingString(0));
        assertEquals(1, FishingRodPrerequisitePolicy.missingString(1));
        assertEquals(0, FishingRodPrerequisitePolicy.missingString(2));
        assertEquals(0, FishingRodPrerequisitePolicy.missingString(20));
        assertEquals(6, FishingRodPrerequisitePolicy.missingString(0, 3));
        assertEquals(1, FishingRodPrerequisitePolicy.missingString(5, 3));
        assertEquals(0, FishingRodPrerequisitePolicy.missingString(0, 0));
        assertEquals(0, FishingRodPrerequisitePolicy.missingString(0, -1));
    }

    @Test
    void skipsStorageFallbacksWhenEveryDirectRecipeIngredientIsReady() {
        assertEquals(true, FishingRodPrerequisitePolicy.directIngredientsReady(3, 2, 1));
        assertEquals(true, FishingRodPrerequisitePolicy.directIngredientsReady(6, 4, 2));
        assertEquals(false, FishingRodPrerequisitePolicy.directIngredientsReady(2, 2, 1));
        assertEquals(false, FishingRodPrerequisitePolicy.directIngredientsReady(3, 1, 1));
        assertEquals(true, FishingRodPrerequisitePolicy.directIngredientsReady(0, 0, 0));
    }
}
