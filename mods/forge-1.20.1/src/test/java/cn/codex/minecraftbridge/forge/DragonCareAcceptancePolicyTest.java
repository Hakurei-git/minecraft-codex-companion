package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonCareAcceptancePolicyTest {
    private static DragonCareAcceptancePolicy.State state(
        String identity,
        boolean present,
        int items,
        double health,
        double food,
        double happiness,
        boolean owned,
        double eggProgress
    ) {
        return new DragonCareAcceptancePolicy.State(
            identity, present, items, health, food, happiness, owned, eggProgress
        );
    }

    @Test
    void feedRequiresConsumptionAndARealNeedChange() {
        var before = state("dragon", true, 3, 20, 20, 10, true, Double.NaN);
        assertTrue(DragonCareAcceptancePolicy.fed(
            before,
            state("dragon", true, 2, 20, 30, 10, true, Double.NaN)
        ));
        assertTrue(DragonCareAcceptancePolicy.fed(
            before,
            state("dragon", true, 2, 20, 20, 14, true, Double.NaN)
        ));
        assertFalse(DragonCareAcceptancePolicy.fed(
            before,
            state("dragon", true, 3, 20, 30, 10, true, Double.NaN)
        ));
        assertFalse(DragonCareAcceptancePolicy.fed(
            before,
            state("dragon", true, 2, 20, 20, 10, true, Double.NaN)
        ));
    }

    @Test
    void healAndTameRejectTextOnlyOrOwnershipOnlySuccess() {
        var hurt = state("dragon", true, 3, 8, 20, 10, true, Double.NaN);
        assertTrue(DragonCareAcceptancePolicy.healed(
            hurt,
            state("dragon", true, 2, 12, 20, 10, true, Double.NaN)
        ));
        assertFalse(DragonCareAcceptancePolicy.healed(
            hurt,
            state("dragon", true, 2, 8, 30, 14, true, Double.NaN)
        ));

        var wild = state("wild", true, 3, 20, 20, 10, false, Double.NaN);
        assertTrue(DragonCareAcceptancePolicy.tamed(
            wild,
            state("wild", true, 2, 20, 20, 10, true, Double.NaN)
        ));
        assertFalse(DragonCareAcceptancePolicy.tamed(
            wild,
            state("wild", true, 3, 20, 20, 10, true, Double.NaN)
        ));
    }

    @Test
    void creativeCareStillRequiresRealStateChangesButNotSyntheticConsumption() {
        var hungry = state("dragon", true, 0, 20, 20, 10, true, Double.NaN);
        assertTrue(DragonCareAcceptancePolicy.fed(
            hungry,
            state("dragon", true, 0, 20, 30, 10, true, Double.NaN),
            false
        ));
        assertFalse(DragonCareAcceptancePolicy.fed(hungry, hungry, false));

        var hurt = state("dragon", true, 0, 8, 20, 10, true, Double.NaN);
        assertTrue(DragonCareAcceptancePolicy.healed(
            hurt,
            state("dragon", true, 0, 12, 20, 10, true, Double.NaN),
            false
        ));
        assertFalse(DragonCareAcceptancePolicy.healed(hurt, hurt, false));

        var wild = state("wild", true, 0, 20, 20, 10, false, Double.NaN);
        assertTrue(DragonCareAcceptancePolicy.tamed(
            wild,
            state("wild", true, 0, 20, 20, 10, true, Double.NaN),
            false
        ));
        assertFalse(DragonCareAcceptancePolicy.tamed(wild, wild, false));

        // Survival acceptance remains strict even when a normal state happens
        // to use zero item counts on both sides.
        assertFalse(DragonCareAcceptancePolicy.fed(
            hungry,
            state("dragon", true, 0, 20, 30, 10, true, Double.NaN),
            true
        ));
    }

    @Test
    void eggCareRequiresTheSamePresentEggAndForwardProgress() {
        var before = state("egg", true, 0, Double.NaN, Double.NaN, Double.NaN, false, 0.25);
        assertTrue(DragonCareAcceptancePolicy.eggAdvanced(
            before,
            state("egg", true, 0, Double.NaN, Double.NaN, Double.NaN, false, 0.251)
        ));
        assertFalse(DragonCareAcceptancePolicy.eggAdvanced(before, before));
        assertFalse(DragonCareAcceptancePolicy.eggAdvanced(
            before,
            state("other", true, 0, Double.NaN, Double.NaN, Double.NaN, false, 0.30)
        ));
        assertFalse(DragonCareAcceptancePolicy.eggAdvanced(
            before,
            state("egg", false, 0, Double.NaN, Double.NaN, Double.NaN, false, 0.30)
        ));
        assertFalse(DragonCareAcceptancePolicy.eggAdvanced(
            before,
            state("egg", true, 0, Double.NaN, Double.NaN, Double.NaN, false, 0.20)
        ));
    }

    @Test
    void bookHealingRefillsToTheNaturalRegenerationThresholdAndWaitsForRegen() {
        assertTrue(DragonCareAcceptancePolicy.shouldRefillForHealing(
            "bookofdragons", 65, true, 8
        ));
        assertFalse(DragonCareAcceptancePolicy.shouldRefillForHealing(
            "bookofdragons", 75, true, 8
        ));
        assertFalse(DragonCareAcceptancePolicy.shouldRefillForHealing(
            "bookofdragons", 65, false, 8
        ));
        assertFalse(DragonCareAcceptancePolicy.shouldRefillForHealing(
            "saintsdragons", 20, true, 8
        ));
        assertTrue(DragonCareAcceptancePolicy.healingConfirmationTicks("bookofdragons") > 40);
        assertTrue(DragonCareAcceptancePolicy.healingConfirmationTicks("saintsdragons") == 40);
    }
}
