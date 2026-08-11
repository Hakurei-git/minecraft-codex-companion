package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageLiveFixtureTest {
    @Test
    void acceptsOnlyAnIdleActiveSameDimensionDismountedNpc() {
        assertEquals("", refusal(false, "", 0, 0, "idle", true, false));
        assertEquals("Storage fixture requires an active NPC",
            refusal(true, "", 0, 0, "idle", true, false));
        assertEquals("Storage fixture requires no active task",
            refusal(false, "task-1", 0, 0, "idle", true, false));
        assertEquals("Storage fixture requires no paused task",
            refusal(false, "", 1, 0, "idle", true, false));
        assertEquals("Storage fixture requires an empty task queue",
            refusal(false, "", 0, 1, "idle", true, false));
        assertEquals("Storage fixture requires an idle scheduler",
            refusal(false, "", 0, 0, "running", true, false));
        assertEquals("Storage fixture requires the owner and NPC in the same dimension",
            refusal(false, "", 0, 0, "idle", false, false));
        assertEquals("Storage fixture requires a dismounted NPC",
            refusal(false, "", 0, 0, "idle", true, true));
    }

    @Test
    void loadsSavedNpcCoordinatesOnlyInTheFixtureDimension() {
        assertEquals("", StorageLiveFixture.cleanupDimensionRefusalReason(
            "minecraft:overworld", "minecraft:overworld", "minecraft:overworld"
        ));
        assertEquals("Storage fixture cleanup requires the owner and NPC in the fixture dimension",
            StorageLiveFixture.cleanupDimensionRefusalReason(
                "minecraft:overworld", "minecraft:the_nether", "minecraft:the_nether"
            ));
        assertEquals("Storage fixture cleanup requires the owner and NPC in the fixture dimension",
            StorageLiveFixture.cleanupDimensionRefusalReason(
                "minecraft:overworld", "minecraft:overworld", "minecraft:the_nether"
            ));
        assertEquals("Storage fixture dimension snapshot is missing",
            StorageLiveFixture.cleanupDimensionRefusalReason("", "minecraft:overworld", "minecraft:overworld"));
    }

    private static String refusal(
        boolean downed,
        String activeTaskId,
        int pausedTaskCount,
        int queuedTaskCount,
        String schedulerLifecycle,
        boolean sameDimension,
        boolean mounted
    ) {
        return StorageLiveFixture.setupRefusalReason(
            downed,
            activeTaskId,
            pausedTaskCount,
            queuedTaskCount,
            schedulerLifecycle,
            sameDimension,
            mounted
        );
    }
}
