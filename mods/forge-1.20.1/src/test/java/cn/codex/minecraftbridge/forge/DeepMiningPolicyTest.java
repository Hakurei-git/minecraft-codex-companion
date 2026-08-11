package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeepMiningPolicyTest {
    @Test
    void choosesConservativeVanillaMiningLayers() {
        assertEquals(-58, DeepMiningPolicy.targetY("minecraft:diamond"));
        assertEquals(-58, DeepMiningPolicy.targetY("minecraft:redstone"));
        assertEquals(0, DeepMiningPolicy.targetY("minecraft:lapis_lazuli"));
        assertEquals(-16, DeepMiningPolicy.targetY("minecraft:raw_gold"));
        assertEquals(16, DeepMiningPolicy.targetY("minecraft:raw_iron"));
        assertEquals(48, DeepMiningPolicy.targetY("minecraft:raw_copper"));
        assertFalse(DeepMiningPolicy.supports("minecraft:coal"));
        assertFalse(DeepMiningPolicy.supports("minecraft:emerald"));
        assertEquals("minecraft:iron_pickaxe", DeepMiningPolicy.requiredPickaxe("minecraft:diamond"));
        assertEquals("minecraft:stone_pickaxe", DeepMiningPolicy.requiredPickaxe("minecraft:raw_iron"));
    }

    @Test
    void buildsAOneDownPerStepSurvivalStaircase() {
        BlockPos entry = new BlockPos(10, 64, 20);
        assertEquals(new BlockPos(11, 63, 20),
            DeepMiningPolicy.staircaseStand(entry, Direction.EAST, 1));
        assertEquals(new BlockPos(14, 60, 20),
            DeepMiningPolicy.staircaseStand(entry, Direction.EAST, 4));
    }

    @Test
    void alternatesThirtyTwoBlockBranchesAcrossAThreeBlockSpine() {
        BlockPos landing = new BlockPos(0, -58, 0);
        assertEquals(Direction.EAST, DeepMiningPolicy.branchDirection(Direction.NORTH, 0));
        assertEquals(Direction.WEST, DeepMiningPolicy.branchDirection(Direction.NORTH, 1));
        assertEquals(new BlockPos(0, -58, -3),
            DeepMiningPolicy.branchOrigin(landing, Direction.NORTH, 2, 0));
        assertEquals(new BlockPos(32, -58, 0),
            DeepMiningPolicy.branchStand(landing, Direction.NORTH, 0, 0, 32));
        assertEquals(new BlockPos(0, -58, -128),
            DeepMiningPolicy.branchOrigin(landing, Direction.NORTH, 0, 1));
        assertTrue(DeepMiningPolicy.branchComplete(32));
        assertTrue(DeepMiningPolicy.regionComplete(8));
    }

    @Test
    void entersOneBlockDescentsBeforeApplyingDownwardMotion() {
        Vec3 target = new Vec3(40.5D, -55.0D, 52.5D);
        Vec3 approach = DeepMiningPolicy.closeRangeStep(
            new Vec3(40.5D, -54.0D, 51.7D),
            target
        );
        assertEquals(0.0D, approach.y, 0.000001D);
        assertTrue(approach.z > 0.0D);

        Vec3 descent = DeepMiningPolicy.closeRangeStep(
            new Vec3(40.5D, -54.0D, 52.5D),
            target
        );
        assertTrue(descent.y < 0.0D);
        assertEquals(0.0D, descent.x, 0.000001D);
        assertEquals(0.0D, descent.z, 0.000001D);

        BlockPos from = new BlockPos(40, -54, 51);
        BlockPos targetStand = new BlockPos(40, -55, 52);
        assertEquals(
            List.of(targetStand.above(2), targetStand.above(), targetStand),
            DeepMiningPolicy.corridorExcavations(from, targetStand)
        );
        assertEquals(
            List.of(targetStand.east().above(), targetStand.east()),
            DeepMiningPolicy.corridorExcavations(targetStand, targetStand.east())
        );
        assertFalse(DeepMiningPolicy.reachedStand(
            new Vec3(40.5D, -54.0D, 51.98D),
            targetStand
        ));
        assertTrue(DeepMiningPolicy.reachedStand(
            new Vec3(40.5D, -55.0D, 52.02D),
            targetStand
        ));
        assertFalse(DeepMiningPolicy.reachedStand(
            new Vec3(40.5D, -54.4D, 52.5D),
            targetStand
        ));
    }

    @Test
    void requiresSuppliesAndRejectsFluidsGravityBlocksAndUnbreakableBlocks() {
        assertEquals(32, DeepMiningPolicy.REQUIRED_LADDERS);
        assertEquals(32, DeepMiningPolicy.REQUIRED_TORCHES);
        assertEquals(2, DeepMiningPolicy.REQUIRED_IRON_PICKAXES);
        assertEquals(200, DeepMiningPolicy.MIN_PICKAXE_REMAINING_DURABILITY);
        assertTrue(DeepMiningPolicy.shouldPlaceTorch(8, 0));
        assertFalse(DeepMiningPolicy.shouldPlaceTorch(8, 8));
        assertTrue(DeepMiningPolicy.isUnsafeExcavation("minecraft:stone", true, 1.5F));
        assertTrue(DeepMiningPolicy.isUnsafeExcavation("minecraft:gravel", false, 0.6F));
        assertTrue(DeepMiningPolicy.isUnsafeExcavation("minecraft:bedrock", false, -1.0F));
        assertFalse(DeepMiningPolicy.isUnsafeExcavation("minecraft:deepslate", false, 3.0F));
    }
}
