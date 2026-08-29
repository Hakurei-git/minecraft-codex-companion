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

    @Test
    void completesOnlyAfterThreeStableUnmountedTicks() {
        assertEquals(
            DragonDismountPolicy.Decision.CONTINUE,
            DragonDismountPolicy.verify(false, 2, 2)
        );
        assertEquals(
            DragonDismountPolicy.Decision.COMPLETE,
            DragonDismountPolicy.verify(false, 3, 3)
        );
        assertEquals(
            DragonDismountPolicy.Decision.CONTINUE,
            DragonDismountPolicy.verify(true, 0, 39)
        );
        assertEquals(
            DragonDismountPolicy.Decision.TIMED_OUT,
            DragonDismountPolicy.verify(true, 0, 40)
        );
    }

    @Test
    void retriesARejectedDismountAtAShortBoundedInterval() {
        assertEquals(true, DragonDismountPolicy.shouldAttempt(false, 1, 0));
        assertEquals(false, DragonDismountPolicy.shouldAttempt(true, 4, 1));
        assertEquals(true, DragonDismountPolicy.shouldAttempt(true, 6, 1));
    }
}
