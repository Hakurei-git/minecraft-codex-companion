package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OwnerDamagePolicyTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PROJECTILE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void blocksDirectOwnerDamage() {
        assertTrue(OwnerDamagePolicy.isOwnerDamage(OWNER, OWNER, OWNER));
    }

    @Test
    void blocksOwnerProjectileDamage() {
        assertTrue(OwnerDamagePolicy.isOwnerDamage(OWNER, PROJECTILE, OWNER));
    }

    @Test
    void keepsEnvironmentalAndNonOwnerDamage() {
        assertFalse(OwnerDamagePolicy.isOwnerDamage(OWNER, null, null));
        assertFalse(OwnerDamagePolicy.isOwnerDamage(OWNER, OTHER, OTHER));
        assertFalse(OwnerDamagePolicy.isOwnerDamage(null, OWNER, OWNER));
    }

    @Test
    void blocksDirectAndIndirectDamageFromTheOwnersDragon() {
        assertTrue(OwnerDamagePolicy.isOwnerOrOwnedDragonDamage(
            OWNER, OTHER, OTHER, true, false
        ));
        assertTrue(OwnerDamagePolicy.isOwnerOrOwnedDragonDamage(
            OWNER, PROJECTILE, OTHER, false, true
        ));
        assertFalse(OwnerDamagePolicy.isOwnerOrOwnedDragonDamage(
            OWNER, PROJECTILE, OTHER, false, false
        ));
        assertFalse(OwnerDamagePolicy.isOwnerOrOwnedDragonDamage(
            null, PROJECTILE, OTHER, true, true
        ));
    }
}
