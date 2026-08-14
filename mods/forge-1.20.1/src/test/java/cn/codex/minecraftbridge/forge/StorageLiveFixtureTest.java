package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void craftExpandInspectionPreservesAllEvidenceWithinTheStatusLimit() {
        String initial = StorageLiveFixture.craftExpandInspectionStatus(
            1728, 0, 7, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0
        );
        String complete = StorageLiveFixture.craftExpandInspectionStatus(
            1728, 4, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0
        );

        assertEquals(
            "storage-fixture:craft-expand|hf=1728,hs=0,nf=7,nl=3,np=0,nt=0,nc=0,e=0,t=0,tp=0,cp=0,d=0,u=0",
            initial
        );
        assertEquals(
            "storage-fixture:craft-expand|hf=1728,hs=4,nf=0,nl=0,np=0,nt=0,nc=0,e=1,t=1,tp=1,cp=1,d=0,u=0",
            complete
        );
        assertTrue(initial.length() <= 120);
        assertTrue(complete.length() <= 120);
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
