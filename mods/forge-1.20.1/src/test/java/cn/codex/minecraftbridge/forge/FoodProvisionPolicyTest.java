package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FoodProvisionPolicyTest {
    @Test
    void preservesProtectedAnimalsAndAutomaticBreedingPairs() {
        assertFalse(FoodProvisionPolicy.mayHunt(false, false, false, false, false, 8));
        assertFalse(FoodProvisionPolicy.mayHunt(true, true, false, false, false, 8));
        assertFalse(FoodProvisionPolicy.mayHunt(true, false, true, false, false, 8));
        assertFalse(FoodProvisionPolicy.mayHunt(true, false, false, true, false, 8));
        assertFalse(FoodProvisionPolicy.mayHunt(true, false, false, false, true, 8));
        assertFalse(FoodProvisionPolicy.mayHunt(true, false, false, false, false, 2));
        assertTrue(FoodProvisionPolicy.mayHunt(true, false, false, false, false, 3));
        assertFalse(FoodProvisionPolicy.mayHunt(true, false, false, false, false, 1));
    }

    @Test
    void permitsOnlyUnprotectedAdultsForTheRemoteSurvivalFallback() {
        assertTrue(FoodProvisionPolicy.mayHunt(
            true, false, false, false, false, 1, true
        ));
        assertFalse(FoodProvisionPolicy.mayHunt(
            true, false, false, false, true, 4, true
        ));
        assertFalse(FoodProvisionPolicy.mayHunt(
            true, true, false, false, false, 4, true
        ));
    }

    @Test
    void boundsReserveAndSearchProgress() {
        assertFalse(FoodProvisionPolicy.shouldComplete(7, 8));
        assertTrue(FoodProvisionPolicy.shouldComplete(8, 8));
        assertTrue(FoodProvisionPolicy.shouldComplete(1, 0));
        assertTrue(FoodProvisionPolicy.nextSearchRadius(48) == 48);
    }

    @Test
    void preservesRequestedFoodCategoryFromAcquisitionThroughDelivery() {
        assertTrue(FoodProvisionPolicy.acceptsSourceItem("hunt", "minecraft:beef"));
        assertTrue(FoodProvisionPolicy.acceptsSourceItem("hunt", "minecraft:cooked_porkchop"));
        assertFalse(FoodProvisionPolicy.acceptsSourceItem("hunt", "minecraft:melon_slice"));
        assertFalse(FoodProvisionPolicy.acceptsSourceItem("hunt", "minecraft:bread"));

        assertTrue(FoodProvisionPolicy.acceptsSourceItem("forage", "minecraft:melon_slice"));
        assertFalse(FoodProvisionPolicy.acceptsSourceItem("forage", "minecraft:cooked_beef"));
        assertTrue(FoodProvisionPolicy.acceptsSourceItem("auto", "minecraft:cooked_beef"));
        assertTrue(FoodProvisionPolicy.acceptsSourceItem("auto", "minecraft:melon_slice"));
    }

    @Test
    void classifiesRawAndCookedMeatWithoutTreatingRottenFleshAsRequestedMeat() {
        assertTrue(FoodProvisionPolicy.isRawMeat("minecraft:beef"));
        assertTrue(FoodProvisionPolicy.isMeat("minecraft:cooked_mutton"));
        assertFalse(FoodProvisionPolicy.isRawMeat("minecraft:cooked_beef"));
        assertFalse(FoodProvisionPolicy.isMeat("minecraft:rotten_flesh"));
        assertFalse(FoodProvisionPolicy.isMeat("minecraft:melon_slice"));
    }

    @Test
    void keepsTheAutomaticFoodRouteInTheRequestedPriorityOrder() {
        assertTrue(FoodProvisionPolicy.initialPhase("auto") == FoodProvisionPolicy.PHASE_HOME_STORAGE);
        assertTrue(FoodProvisionPolicy.phaseAfterHomeStorage("auto") == FoodProvisionPolicy.PHASE_FISHING);
        assertTrue(FoodProvisionPolicy.phaseAfterFishing(true) == FoodProvisionPolicy.PHASE_REMEMBERED_FARM);
        assertTrue(FoodProvisionPolicy.phaseAfterFishing(false) == FoodProvisionPolicy.PHASE_LOCAL_THEN_REMOTE);

        assertTrue(FoodProvisionPolicy.initialPhase("hunt") == FoodProvisionPolicy.PHASE_LOCAL_THEN_REMOTE);
        assertTrue(FoodProvisionPolicy.phaseAfterHomeStorage("forage") == FoodProvisionPolicy.PHASE_LOCAL_THEN_REMOTE);
    }
}
