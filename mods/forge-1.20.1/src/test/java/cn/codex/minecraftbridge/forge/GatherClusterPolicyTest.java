package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherClusterPolicyTest {
    @Test
    void treatsFacesEdgesAndCornersAsOneOreVein() {
        assertEquals(26, GatherClusterPolicy.connectedNeighbors().size());
        assertTrue(GatherClusterPolicy.connectedNeighbors().contains(new GatherClusterPolicy.Offset(1, 0, 0)));
        assertTrue(GatherClusterPolicy.connectedNeighbors().contains(new GatherClusterPolicy.Offset(1, 1, 0)));
        assertTrue(GatherClusterPolicy.connectedNeighbors().contains(new GatherClusterPolicy.Offset(1, 1, 1)));
        assertFalse(GatherClusterPolicy.connectedNeighbors().contains(new GatherClusterPolicy.Offset(0, 0, 0)));
    }

    @Test
    void retriesOnlyNearbySkippedBlocksWhenGeometryChanges() {
        assertTrue(GatherClusterPolicy.reconsiderSkippedAfterBreak(64.0D));
        assertFalse(GatherClusterPolicy.reconsiderSkippedAfterBreak(64.01D));
        assertFalse(GatherClusterPolicy.reconsiderSkippedAfterBreak(Double.NaN));
    }
}
