package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskDimensionPolicyTest {
    @Test
    void bindsNewMoveTasksToTheirStartingDimension() {
        assertEquals(
            "minecraft:overworld",
            TaskDimensionPolicy.bind("move", "", "minecraft:overworld")
        );
    }

    @Test
    void pausesCoordinatesInAnotherDimensionAndResumesAtTheirOrigin() {
        assertTrue(TaskDimensionPolicy.shouldPause(
            "move", "minecraft:overworld", "minecraft:the_nether"
        ));
        assertFalse(TaskDimensionPolicy.shouldPause(
            "move", "minecraft:overworld", "minecraft:overworld"
        ));
        assertFalse(TaskDimensionPolicy.shouldPause(
            "gather", "minecraft:overworld", "minecraft:the_nether"
        ));
    }
}
