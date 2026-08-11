package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildClearancePolicyTest {
    @Test
    void clearsOrdinaryBreakableConstructionObstacles() {
        assertTrue(BuildClearancePolicy.mayClear(
            "minecraft:oak_planks", false, false, false, false, 2.0F
        ));
        assertTrue(BuildClearancePolicy.mayClear(
            "minecraft:stone", false, false, false, false, 1.5F
        ));
    }

    @Test
    void preservesContainersInfrastructureAndUnbreakableBlocks() {
        assertFalse(BuildClearancePolicy.mayClear(
            "minecraft:chest", false, false, false, true, 2.5F
        ));
        assertFalse(BuildClearancePolicy.mayClear(
            "minecraft:spawner", false, false, false, true, 5.0F
        ));
        assertFalse(BuildClearancePolicy.mayClear(
            "minecraft:bedrock", false, false, false, false, -1.0F
        ));
        assertFalse(BuildClearancePolicy.mayClear(
            "minecraft:nether_portal", false, false, true, false, -1.0F
        ));
    }

    @Test
    void doesNotTreatAirOrReplaceableBlocksAsClearanceTargets() {
        assertFalse(BuildClearancePolicy.mayClear(
            "minecraft:air", true, true, false, false, 0.0F
        ));
        assertFalse(BuildClearancePolicy.mayClear(
            "minecraft:tall_grass", false, true, false, false, 0.0F
        ));
    }
}
