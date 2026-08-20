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
        assertEquals(0, BuildPlacementPolicy.supportScore(false, true));
        assertEquals(1, BuildPlacementPolicy.supportScore(true, false));
        assertEquals(2, BuildPlacementPolicy.supportScore(true, true));
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
        BlockPos home = new BlockPos(100, 70, 100);
        assertFalse(BuildPlacementPolicy.clearsHome(new BlockPos(120, 90, 100), home, 24));
        assertTrue(BuildPlacementPolicy.clearsHome(new BlockPos(124, 70, 100), home, 24));
        assertTrue(BuildPlacementPolicy.clearsHome(new BlockPos(100, 70, 124), home, 24));
        assertTrue(BuildPlacementPolicy.shouldResolveOutdoorSite("outdoor", 0));
        assertFalse(BuildPlacementPolicy.shouldResolveOutdoorSite("outdoor", 1));
        assertFalse(BuildPlacementPolicy.shouldResolveOutdoorSite("default", 0));
        assertTrue(BuildPlacementPolicy.shouldResolveHomeCompoundSite("home-compound", 0, false));
        assertFalse(BuildPlacementPolicy.shouldResolveHomeCompoundSite("home-compound", 1, false));
        assertFalse(BuildPlacementPolicy.shouldResolveHomeCompoundSite("home-compound", 0, true));
        assertTrue(BuildPlacementPolicy.terrainFits(72, 75, 3));
        assertFalse(BuildPlacementPolicy.terrainFits(72, 76, 3));
    }

    @Test
    void measuresTheWholeBlueprintAgainstTheHouseAndOtherFacilities() {
        NpcHomeStorage.Bounds home = new NpcHomeStorage.Bounds(
            new BlockPos(0, 60, 0),
            new BlockPos(6, 75, 6)
        );
        NpcHomeStorage.Bounds farm = new NpcHomeStorage.Bounds(
            new BlockPos(22, 64, 2),
            new BlockPos(30, 65, 10)
        );
        NpcHomeStorage.Bounds nearbyPen = new NpcHomeStorage.Bounds(
            new BlockPos(42, 64, 0),
            new BlockPos(50, 68, 8)
        );

        assertEquals(16.0D, BuildPlacementPolicy.horizontalGap(farm, home), 0.0001D);
        assertTrue(BuildPlacementPolicy.insideCompoundRing(farm, home, 16, 40));
        assertFalse(BuildPlacementPolicy.insideCompoundRing(farm, home, 17, 40));
        assertTrue(BuildPlacementPolicy.overlapsWithMargin(farm, nearbyPen, 12));
        assertFalse(BuildPlacementPolicy.overlapsWithMargin(farm, nearbyPen, 11));
    }

    @Test
    void lightPreparationAllowsOnlySmallVegetationOrLeafObstacles() {
        assertEquals(0, BuildPlacementPolicy.maximumTerrainDelta("none"));
        assertEquals(2, BuildPlacementPolicy.maximumTerrainDelta("light"));
        assertTrue(BuildPlacementPolicy.mayUseCompoundVolumeCell(
            true, true, false, false, false, false, 0.0F
        ));
        assertTrue(BuildPlacementPolicy.mayUseCompoundVolumeCell(
            false, false, true, false, false, false, 0.2F
        ));
        assertFalse(BuildPlacementPolicy.mayUseCompoundVolumeCell(
            false, false, false, false, false, false, 2.0F
        ));
        assertFalse(BuildPlacementPolicy.mayUseCompoundVolumeCell(
            false, true, false, false, true, false, 2.0F
        ));
        assertTrue(BuildPlacementPolicy.inclusiveSpanAtMost(0, 63, 64));
        assertFalse(BuildPlacementPolicy.inclusiveSpanAtMost(0, 64, 64));
        assertTrue(BuildPlacementPolicy.compoundLockMatches(
            "minecraft:overworld",
            new BlockPos(12, 70, -8),
            "minecraft:overworld",
            new BlockPos(12, 70, -8)
        ));
        assertFalse(BuildPlacementPolicy.compoundLockMatches(
            "minecraft:the_nether",
            new BlockPos(12, 70, -8),
            "minecraft:overworld",
            new BlockPos(12, 70, -8)
        ));
        assertFalse(BuildPlacementPolicy.mayModifyCompoundTarget(
            "minecraft:crafting_table", "minecraft:crafting_table", true
        ));
        assertTrue(BuildPlacementPolicy.mayModifyCompoundTarget(
            "minecraft:tall_grass", "minecraft:dirt", false
        ));
        assertTrue(BuildPlacementPolicy.isProtectedCompoundInfrastructureId("minecraft:crafting_table"));
        assertTrue(BuildPlacementPolicy.isProtectedCompoundInfrastructureId("minecraft:oak_fence"));
        assertTrue(BuildPlacementPolicy.isProtectedCompoundInfrastructureId("minecraft:redstone_wire"));
        assertFalse(BuildPlacementPolicy.isProtectedCompoundInfrastructureId("minecraft:tall_grass"));
    }
}
