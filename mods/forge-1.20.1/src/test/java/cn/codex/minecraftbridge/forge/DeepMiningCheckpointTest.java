package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class DeepMiningCheckpointTest {
    @Test
    void roundTripsEveryRouteAndTimedTargetField() {
        DeepMiningCheckpoint original = checkpoint(
            "minecraft:diamond",
            -58,
            87,
            new BlockPos(-107, -51, -2)
        );

        DeepMiningCheckpoint restored = DeepMiningCheckpoint.fromJson(original.toJson());

        assertNotNull(restored);
        assertEquals(original, restored);
        assertEquals("branching", restored.phase());
        assertEquals(Direction.WEST, restored.direction());
        assertEquals(341, restored.brokenBlocks());
        assertEquals(new BlockPos(-105, -51, -2), restored.resourceTimedTarget());
    }

    @Test
    void independentStackFramesKeepParentAndChildMineCheckpoints() {
        DeepMiningCheckpoint diamond = checkpoint(
            "minecraft:diamond",
            -58,
            87,
            new BlockPos(-107, -51, -2)
        );
        DeepMiningCheckpoint iron = checkpoint(
            "minecraft:raw_iron",
            16,
            19,
            new BlockPos(-168, 40, -8)
        );
        Deque<JsonObject> materialFrames = new ArrayDeque<>();
        materialFrames.addFirst(diamond.toJson());
        materialFrames.addFirst(iron.toJson());

        assertEquals("minecraft:raw_iron", DeepMiningCheckpoint.fromJson(materialFrames.removeFirst()).itemId());
        DeepMiningCheckpoint resumedParent = DeepMiningCheckpoint.fromJson(materialFrames.removeFirst());
        assertEquals("minecraft:diamond", resumedParent.itemId());
        assertEquals(87, resumedParent.staircaseStep());
        assertEquals(new BlockPos(-107, -51, -2), resumedParent.lastSafeStand());
    }

    @Test
    void rejectsAbsentOrIncompleteCheckpoints() {
        assertNull(DeepMiningCheckpoint.fromJson(null));
        JsonObject incomplete = new JsonObject();
        incomplete.addProperty("phase", "branching");
        assertNull(DeepMiningCheckpoint.fromJson(incomplete));
    }

    private static DeepMiningCheckpoint checkpoint(
        String itemId,
        int targetY,
        int staircaseStep,
        BlockPos lastSafeStand
    ) {
        return new DeepMiningCheckpoint(
            "branching",
            itemId,
            targetY,
            Direction.WEST,
            1_000,
            staircaseStep,
            3,
            17,
            1,
            16,
            341,
            10,
            2,
            3,
            4,
            900,
            true,
            false,
            1_040,
            1_020,
            new BlockPos(-20, 36, -2),
            new BlockPos(-100, -51, -2),
            lastSafeStand,
            new BlockPos(-19, 35, -2),
            new BlockPos(-106, -51, -2),
            new BlockPos(-105, -51, -2)
        );
    }
}
