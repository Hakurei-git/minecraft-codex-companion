package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonSeatSharingPolicyTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void placesRearSeatBehindMinecraftYaw() {
        var northFacing = DragonSeatSharingPolicy.rearSeatOffset(0.0F, 2.0D, 2.0D);
        assertEquals(0.0D, northFacing.x(), EPSILON);
        assertTrue(northFacing.z() < 0.0D);

        var westFacing = DragonSeatSharingPolicy.rearSeatOffset(90.0F, 2.0D, 2.0D);
        assertTrue(westFacing.x() > 0.0D);
        assertEquals(0.0D, westFacing.z(), EPSILON);
        assertTrue(westFacing.y() > 0.0D);
    }

    @Test
    void clampsSeatDistanceForTinyAndHugeDragons() {
        assertEquals(1.05D, DragonSeatSharingPolicy.rearSeatDistance(0.1D, 0.1D), EPSILON);
        assertEquals(4.25D, DragonSeatSharingPolicy.rearSeatDistance(100.0D, 100.0D), EPSILON);
        assertTrue(DragonSeatSharingPolicy.rearSeatDistance(5.0D, 4.0D) > 2.5D);
    }

}
