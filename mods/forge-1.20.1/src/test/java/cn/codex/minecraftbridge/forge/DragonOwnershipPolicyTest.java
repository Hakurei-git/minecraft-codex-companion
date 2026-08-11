package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonOwnershipPolicyTest {
    @Test
    void ownershipUsesThePersistentUuidInsteadOfEntityResolution() {
        UUID owner = UUID.randomUUID();
        assertTrue(DragonOwnershipPolicy.isOwnedBy(true, owner, owner));
        assertFalse(DragonOwnershipPolicy.isOwnedBy(false, owner, owner));
        assertFalse(DragonOwnershipPolicy.isOwnedBy(true, owner, UUID.randomUUID()));
        assertFalse(DragonOwnershipPolicy.isOwnedBy(true, null, owner));
    }
}
