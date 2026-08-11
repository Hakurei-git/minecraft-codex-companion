package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedSleepLiveFixtureTest {
    @Test
    void setupAcceptsOnlyIdleAwakeSameDimensionSurvivalActors() {
        assertEquals("", refusal(true, false, "", 0, "idle", false, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires living owner and NPC actors",
            refusal(false, false, "", 0, "idle", false, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires a recovered NPC",
            refusal(true, true, "", 0, "idle", false, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires no active task",
            refusal(true, false, "task-1", 0, "running", false, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires no paused task",
            refusal(true, false, "", 1, "idle", false, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires an idle scheduler",
            refusal(true, false, "", 0, "running", false, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires NPC eating to finish",
            refusal(true, false, "", 0, "idle", true, false, true, true, false, false));
        assertEquals("Bed sleep fixture requires awake owner and NPC actors",
            refusal(true, false, "", 0, "idle", false, true, true, true, false, false));
        assertEquals("Bed sleep fixture requires the owner and NPC in the same dimension",
            refusal(true, false, "", 0, "idle", false, false, false, true, false, false));
        assertEquals("Bed sleep fixture requires a natural sleeping dimension",
            refusal(true, false, "", 0, "idle", false, false, true, false, false, false));
        assertEquals("Bed sleep fixture requires survival material mode",
            refusal(true, false, "", 0, "idle", false, false, true, true, true, false));
        assertEquals("Bed sleep fixture requires dismounted owner and NPC actors",
            refusal(true, false, "", 0, "idle", false, false, true, true, false, true));
    }

    @Test
    void cleanupRefusesCrossDimensionRestoration() {
        assertEquals("", BedSleepLiveFixture.cleanupDimensionRefusalReason(
            "minecraft:overworld", "minecraft:overworld", "minecraft:overworld"
        ));
        assertEquals("Bed sleep fixture cleanup requires the owner and NPC in the fixture dimension",
            BedSleepLiveFixture.cleanupDimensionRefusalReason(
                "minecraft:overworld", "minecraft:the_nether", "minecraft:overworld"
            ));
        assertEquals("Bed sleep fixture dimension snapshot is missing",
            BedSleepLiveFixture.cleanupDimensionRefusalReason("", "minecraft:overworld", "minecraft:overworld"));
    }

    @Test
    void failuresExposeOnlyStableDiagnosticCodes() {
        assertEquals("npc-not-idle", BedSleepLiveFixture.failureCode(
            new IllegalStateException("Bed sleep fixture requires no active task")
        ));
        assertEquals("dimension-mismatch", BedSleepLiveFixture.failureCode(
            new IllegalStateException("Bed sleep fixture requires the owner and NPC in the same dimension")
        ));
        assertEquals("survival-required", BedSleepLiveFixture.failureCode(
            new IllegalStateException("Bed sleep fixture requires survival material mode")
        ));
        assertEquals("fixture-failed", BedSleepLiveFixture.failureCode(
            new IllegalStateException("private implementation detail")
        ));
    }

    private static String refusal(
        boolean alive,
        boolean downed,
        String activeTaskId,
        int pausedTaskCount,
        String schedulerLifecycle,
        boolean managedEating,
        boolean actorSleeping,
        boolean sameDimension,
        boolean naturalDimension,
        boolean creativeResources,
        boolean mounted
    ) {
        return BedSleepLiveFixture.setupRefusalReason(
            alive,
            downed,
            activeTaskId,
            pausedTaskCount,
            schedulerLifecycle,
            managedEating,
            actorSleeping,
            sameDimension,
            naturalDimension,
            creativeResources,
            mounted
        );
    }
}
