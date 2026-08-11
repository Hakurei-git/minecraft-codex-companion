package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CombatDefensePolicyTest {
    @Test
    void raisesShieldWhileApproachingOrWaitingForAttackCooldown() {
        assertTrue(CombatDefensePolicy.shouldRaiseShield(true, true, 6.0D, 0));
        assertTrue(CombatDefensePolicy.shouldRaiseShield(true, true, 2.0D, 8));
    }

    @Test
    void lowersShieldForAnImmediateAttackOrAfterCombat() {
        assertFalse(CombatDefensePolicy.shouldRaiseShield(true, true, 2.0D, 0));
        assertFalse(CombatDefensePolicy.shouldRaiseShield(true, false, 6.0D, 8));
        assertFalse(CombatDefensePolicy.shouldRaiseShield(false, true, 6.0D, 8));
    }
}
