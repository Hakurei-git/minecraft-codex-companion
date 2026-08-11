package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HungerPolicyTest {
    @Test
    void autoEatingStartsOnlyBelowHalfAndWhenIdle() {
        assertTrue(HungerPolicy.shouldAutoEat(9, false));
        assertFalse(HungerPolicy.shouldAutoEat(10, false));
        assertFalse(HungerPolicy.shouldAutoEat(5, true));
    }

    @Test
    void naturalRegenerationRequiresEnoughFoodAndMissingHealth() {
        assertTrue(HungerPolicy.canNaturallyRegenerate(18, 3.0F, 20.0F));
        assertFalse(HungerPolicy.canNaturallyRegenerate(17, 3.0F, 20.0F));
        assertFalse(HungerPolicy.canNaturallyRegenerate(20, 20.0F, 20.0F));
    }

    @Test
    void damagedNpcEatsUntilItCanRegenerate() {
        assertTrue(HungerPolicy.shouldEatToRegenerate(11, 10.0F, 20.0F));
        assertFalse(HungerPolicy.shouldEatToRegenerate(18, 10.0F, 20.0F));
        assertFalse(HungerPolicy.shouldEatToRegenerate(11, 20.0F, 20.0F));
    }

    @Test
    void explicitEatingStopsWhenFullOrRequestedCountIsMet() {
        assertTrue(HungerPolicy.explicitEatingShouldStop(20, 0, 64));
        assertTrue(HungerPolicy.explicitEatingShouldStop(12, 3, 3));
        assertFalse(HungerPolicy.explicitEatingShouldStop(12, 2, 3));
    }
}
