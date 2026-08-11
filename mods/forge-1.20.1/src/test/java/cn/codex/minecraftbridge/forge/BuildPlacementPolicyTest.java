package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildPlacementPolicyTest {
    @Test
    void mapsFluidSourcesToFilledBuckets() {
        assertEquals("minecraft:water_bucket", BuildPlacementPolicy.materialItemId("minecraft:water"));
        assertEquals("minecraft:lava_bucket", BuildPlacementPolicy.materialItemId("minecraft:lava"));
        assertEquals("minecraft:cobblestone", BuildPlacementPolicy.materialItemId("minecraft:cobblestone"));
        assertTrue(BuildPlacementPolicy.isFluidSource("minecraft:water"));
        assertTrue(BuildPlacementPolicy.isFluidSource("minecraft:lava"));
        assertFalse(BuildPlacementPolicy.isFluidSource("minecraft:cobblestone"));
    }

    @Test
    void acceptsPartialCollisionBlocksAsClickableSupports() {
        assertTrue(BuildPlacementPolicy.isClickableSupport(false, false, false));
        assertFalse(BuildPlacementPolicy.isClickableSupport(true, true, true));
        assertFalse(BuildPlacementPolicy.isClickableSupport(false, true, false));
        assertFalse(BuildPlacementPolicy.isClickableSupport(false, false, true));
    }

    @Test
    void snapsOnlyCompanionPlacementToTheTargetSurface() {
        assertEquals(65, BuildPlacementPolicy.originY("companion", 80, 64, 1));
        assertEquals(80, BuildPlacementPolicy.originY("plan-origin", 80, 64, 1));
        assertEquals(80, BuildPlacementPolicy.originY("", 80, 64, 1));
    }

    @Test
    void companionRetriesProbeTheLockedAnchorInsteadOfThePartialBuild() {
        BlockPos plannedOrigin = new BlockPos(105, 79, 103);
        BlockPos placementAnchor = new BlockPos(102, 79, 100);

        assertEquals(
            placementAnchor,
            BuildPlacementPolicy.surfaceProbe("companion", plannedOrigin, placementAnchor)
        );
        assertEquals(
            plannedOrigin,
            BuildPlacementPolicy.surfaceProbe("plan-origin", plannedOrigin, placementAnchor)
        );
        assertEquals(
            plannedOrigin,
            BuildPlacementPolicy.surfaceProbe("companion", plannedOrigin, null)
        );
    }
}
