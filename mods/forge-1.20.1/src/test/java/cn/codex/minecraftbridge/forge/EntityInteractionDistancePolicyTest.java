package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EntityInteractionDistancePolicyTest {
    @Test
    void treatsTheSelectableSurfaceAsReachableWhenCentersAreFiveBlocksApart() {
        double distance = EntityInteractionDistancePolicy.distanceToExpandedBounds(
            0.0D, 1.62D, 0.0D,
            4.55D, 0.0D, -0.45D,
            5.45D, 1.3D, 0.45D,
            EntityInteractionDistancePolicy.TARGETING_MARGIN
        );

        assertTrue(distance <= 4.5D);
    }

    @Test
    void measuresFromTheEyeToTheClosestExpandedBoundsPoint() {
        assertEquals(4.455435D, EntityInteractionDistancePolicy.distanceToExpandedBounds(
            0.0D, 1.62D, 0.0D,
            4.55D, 0.0D, -0.45D,
            5.45D, 1.3D, 0.45D,
            0.1D
        ), 0.000001D);
        assertEquals(0.0D, EntityInteractionDistancePolicy.distanceToExpandedBounds(
            5.0D, 1.0D, 0.0D,
            4.55D, 0.0D, -0.45D,
            5.45D, 1.3D, 0.45D,
            0.1D
        ));
    }

    @Test
    void ignoresInvalidExpansionValues() {
        assertEquals(4.0D, EntityInteractionDistancePolicy.distanceToExpandedBounds(
            0.0D, 0.5D, 0.5D,
            4.0D, 0.0D, 0.0D,
            5.0D, 1.0D, 1.0D,
            Double.NaN
        ));
    }
}
