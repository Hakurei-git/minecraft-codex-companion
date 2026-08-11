package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SmeltingWorkstationPolicyTest {
    @Test
    void claimsOnlyACompletelyEmptyExistingFurnace() {
        assertTrue(SmeltingWorkstationPolicy.canClaim(true, true, true));
        assertFalse(SmeltingWorkstationPolicy.canClaim(false, true, true));
        assertFalse(SmeltingWorkstationPolicy.canClaim(true, false, true));
        assertFalse(SmeltingWorkstationPolicy.canClaim(true, true, false));
    }

    @Test
    void keepsAClaimOnlyWhileEverySlotRemainsCompatible() {
        assertEquals(SmeltingWorkstationPolicy.Validation.USABLE,
            SmeltingWorkstationPolicy.validate(true, true, true, true, true));
        assertEquals(SmeltingWorkstationPolicy.Validation.UNCLAIMED,
            SmeltingWorkstationPolicy.validate(false, true, true, true, true));
        assertEquals(SmeltingWorkstationPolicy.Validation.BLOCK_MISSING,
            SmeltingWorkstationPolicy.validate(true, false, true, true, true));
        assertEquals(SmeltingWorkstationPolicy.Validation.INPUT_CONFLICT,
            SmeltingWorkstationPolicy.validate(true, true, false, true, true));
        assertEquals(SmeltingWorkstationPolicy.Validation.FUEL_CONFLICT,
            SmeltingWorkstationPolicy.validate(true, true, true, false, true));
        assertEquals(SmeltingWorkstationPolicy.Validation.OUTPUT_CONFLICT,
            SmeltingWorkstationPolicy.validate(true, true, true, true, false));
    }

    @Test
    void reusesOnlyACompatibleClaimForTheSameRecipe() {
        assertTrue(SmeltingWorkstationPolicy.canReuse(
            true,
            SmeltingWorkstationPolicy.Validation.USABLE
        ));
        assertFalse(SmeltingWorkstationPolicy.canReuse(
            false,
            SmeltingWorkstationPolicy.Validation.USABLE
        ));
        assertFalse(SmeltingWorkstationPolicy.canReuse(
            true,
            SmeltingWorkstationPolicy.Validation.INPUT_CONFLICT
        ));
    }

    @Test
    void reacquiresOnlyInputsWhoseOutputsAreNotAlreadyCollected() {
        assertEquals(0, SmeltingWorkstationPolicy.loadedAfterRelease(-1));
        assertEquals(0, SmeltingWorkstationPolicy.loadedAfterRelease(0));
        assertEquals(2, SmeltingWorkstationPolicy.loadedAfterRelease(2));
    }
}
