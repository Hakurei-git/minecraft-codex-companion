package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class WeaponSelectionPolicyTest {
    @Test
    void prefersIronSwordDpsOverStoneAxeBurstDamage() {
        int ironSword = WeaponSelectionPolicy.score(6.0D, -2.4D, 0, 1.0D);
        int stoneAxe = WeaponSelectionPolicy.score(8.0D, -3.2D, 0, 1.0D);
        assertTrue(ironSword > stoneAxe);
    }

    @Test
    void accountsForEnchantmentsAndRemainingDurability() {
        int plain = WeaponSelectionPolicy.score(6.0D, -2.4D, 0, 0.2D);
        int enchanted = WeaponSelectionPolicy.score(6.0D, -2.4D, 4, 1.0D);
        assertTrue(enchanted > plain);
    }
}
