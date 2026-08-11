package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DragonDismountPolicyTest {
    @Test
    void keepsRidersOutsideSmallAndLargeDragonCollisionFootprints() {
        assertEquals(3, DragonDismountPolicy.standRadius(1.0D));
        assertEquals(5, DragonDismountPolicy.standRadius(5.2D));
        assertEquals(12, DragonDismountPolicy.standRadius(100.0D));
        assertEquals(3, DragonDismountPolicy.standRadius(Double.NaN));
    }

    @Test
    void placesTheTwoRidersOnOppositeSides() {
        DragonDismountPolicy.Offset right = DragonDismountPolicy.sideOffset(0.0F, 2.0D, 1);
        DragonDismountPolicy.Offset left = DragonDismountPolicy.sideOffset(0.0F, 2.0D, -1);
        assertEquals(3.0D, right.x(), 0.0001D);
        assertEquals(0.0D, right.z(), 0.0001D);
        assertEquals(-3.0D, left.x(), 0.0001D);
        assertEquals(0.0D, left.z(), 0.0001D);
    }
}
