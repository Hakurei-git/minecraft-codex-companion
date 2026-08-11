package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EquipmentPolicyTest {
    @Test
    void armorAndToughnessDominateMinorDurabilityDifference() {
        double iron = EquipmentPolicy.score(6, 0, 0, 1.0);
        double damagedDiamond = EquipmentPolicy.score(8, 2, 0, 0.5);
        assertTrue(damagedDiamond > iron);
    }

    @Test
    void cursesCanMakeOtherwiseEqualEquipmentWorse() {
        double safe = EquipmentPolicy.score(6, 0, 2, 0.8);
        double cursed = EquipmentPolicy.score(6, 0, -8, 1.0);
        assertTrue(safe > cursed);
    }

    @Test
    void healthyNpcPrefersAUsableShield() {
        double shield = EquipmentPolicy.offhandScore(false, true, 1.0, 0.8);
        double totem = EquipmentPolicy.offhandScore(true, false, 1.0, 1.0);
        assertTrue(shield > totem);
    }

    @Test
    void lowHealthNpcPrefersATotem() {
        double shield = EquipmentPolicy.offhandScore(false, true, 0.25, 1.0);
        double totem = EquipmentPolicy.offhandScore(true, false, 0.25, 1.0);
        assertTrue(totem > shield);
    }

    @Test
    void nearlyBrokenShieldLosesToATotem() {
        double shield = EquipmentPolicy.offhandScore(false, true, 1.0, 0.01);
        double totem = EquipmentPolicy.offhandScore(true, false, 1.0, 1.0);
        assertTrue(totem > shield);
    }
}
