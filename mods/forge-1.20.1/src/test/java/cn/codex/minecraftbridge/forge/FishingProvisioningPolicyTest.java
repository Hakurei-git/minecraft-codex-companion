package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FishingProvisioningPolicyTest {
    @Test
    void keepsOnlyAcceptedSafeFoodFromReserveFishing() {
        assertTrue(FishingProvisioningPolicy.keepLoot(true, true));
        assertFalse(FishingProvisioningPolicy.keepLoot(false, true));
        assertFalse(FishingProvisioningPolicy.keepLoot(true, false));
    }

    @Test
    void recognizesOnlyLowValueFishingJunkForEmergencyCleanup() {
        assertTrue(FishingProvisioningPolicy.disposableJunk("minecraft:bowl"));
        assertTrue(FishingProvisioningPolicy.disposableJunk(" MINECRAFT:TRIPWIRE_HOOK "));
        assertFalse(FishingProvisioningPolicy.disposableJunk("minecraft:enchanted_book"));
        assertFalse(FishingProvisioningPolicy.disposableJunk("minecraft:cod"));
    }
}
