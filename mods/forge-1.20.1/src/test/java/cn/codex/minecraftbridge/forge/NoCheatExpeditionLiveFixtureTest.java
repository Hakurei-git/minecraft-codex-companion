package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoCheatExpeditionLiveFixtureTest {
    @Test
    void setupRequiresAnIdleUnprivilegedSurvivalPair() {
        assertEquals("", refusal(true, false, "", 0, "idle", false, false,
            true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires living owner and NPC actors",
            refusal(false, false, "", 0, "idle", false, false,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires a recovered NPC",
            refusal(true, true, "", 0, "idle", false, false,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires no active task",
            refusal(true, false, "task-1", 0, "running", false, false,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires no paused task",
            refusal(true, false, "", 1, "idle", false, false,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires an idle scheduler",
            refusal(true, false, "", 0, "running", false, false,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires NPC eating to finish",
            refusal(true, false, "", 0, "idle", true, false,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires awake owner and NPC actors",
            refusal(true, false, "", 0, "idle", false, true,
                true, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires the owner and NPC in the same dimension",
            refusal(true, false, "", 0, "idle", false, false,
                false, true, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires a natural dimension",
            refusal(true, false, "", 0, "idle", false, false,
                true, false, false, true, false, false));
        assertEquals("No-cheat expedition fixture requires cheats to be disabled",
            refusal(true, false, "", 0, "idle", false, false,
                true, true, true, true, false, false));
        assertEquals("No-cheat expedition fixture requires player survival mode",
            refusal(true, false, "", 0, "idle", false, false,
                true, true, false, false, false, false));
        assertEquals("No-cheat expedition fixture requires survival material mode",
            refusal(true, false, "", 0, "idle", false, false,
                true, true, false, true, true, false));
        assertEquals("No-cheat expedition fixture requires dismounted owner and NPC actors",
            refusal(true, false, "", 0, "idle", false, false,
                true, true, false, true, false, true));
    }

    @Test
    void firstTreeIsOutsideLocalSearchButInsideTheFirstExpeditionArea() {
        BlockPos origin = new BlockPos(100, 200, -40);
        BlockPos search = NoCheatExpeditionLiveFixture.firstSearchCenter(origin);
        BlockPos tree = NoCheatExpeditionLiveFixture.remoteTreeRoot(origin, search);

        assertTrue(origin.distSqr(search) >= 70.0D * 70.0D);
        assertTrue(origin.distSqr(tree) > 48.0D * 48.0D);
        assertTrue(search.distSqr(tree) <= 16.0D * 16.0D);
        assertEquals(origin.getY(), search.getY());
        assertEquals(origin.getY(), tree.getY());
    }

    @Test
    void acceptanceRequiresWalkingAndExactPhysicalConservation() {
        assertTrue(complete(false, false, true, true, true, 70_000, 900,
            4, 4, 4, 0, 0, 2_500, 0, true, 0));
        assertFalse(complete(true, false, true, true, true, 70_000, 900,
            4, 4, 4, 0, 0, 2_500, 0, true, 0));
        assertFalse(complete(false, false, true, true, true, 70_000, 4_001,
            4, 4, 4, 0, 0, 2_500, 0, true, 0));
        assertFalse(complete(false, false, true, true, true, 54_999, 900,
            4, 4, 4, 0, 0, 2_500, 0, true, 0));
        assertFalse(complete(false, false, true, true, true, 70_000, 900,
            4, 4, 3, 1, 0, 2_500, 0, true, 0));
        assertFalse(complete(false, false, true, true, true, 70_000, 900,
            4, 4, 4, 0, 0, 3_201, 0, true, 0));
        assertFalse(complete(false, false, true, true, true, 70_000, 900,
            4, 4, 4, 0, 0, 2_500, 1_501, true, 0));
        assertFalse(complete(false, false, true, true, true, 70_000, 900,
            4, 4, 4, 0, 0, 2_500, 0, false, 0));
    }

    @Test
    void cleanupRefusesCrossDimensionRestoration() {
        assertEquals("", NoCheatExpeditionLiveFixture.cleanupDimensionRefusalReason(
            "minecraft:overworld", "minecraft:overworld", "minecraft:overworld"
        ));
        assertEquals("No-cheat expedition fixture cleanup requires the owner and NPC in the fixture dimension",
            NoCheatExpeditionLiveFixture.cleanupDimensionRefusalReason(
                "minecraft:overworld", "minecraft:the_nether", "minecraft:overworld"
            ));
        assertEquals("No-cheat expedition fixture dimension snapshot is missing",
            NoCheatExpeditionLiveFixture.cleanupDimensionRefusalReason(
                "", "minecraft:overworld", "minecraft:overworld"
            ));
    }

    @Test
    void failuresExposeOnlyStableDiagnosticCodes() {
        assertEquals("npc-not-idle", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture requires no active task")
        ));
        assertEquals("cheats-enabled", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture requires cheats to be disabled")
        ));
        assertEquals("dimension-mismatch", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture requires the owner and NPC in the same dimension")
        ));
        assertEquals("actor-not-ready", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture requires a recovered NPC")
        ));
        assertEquals("actors-sleeping", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture requires awake owner and NPC actors")
        ));
        assertEquals("passenger-active", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture requires dismounted owner and NPC actors")
        ));
        assertEquals("site-unavailable", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No isolated no-cheat expedition fixture site was found")
        ));
        assertEquals("block-change-rejected", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("No-cheat expedition fixture block could not be changed")
        ));
        assertEquals("inventory-rejected", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("NPC inventory rejected expedition fixture items")
        ));
        assertEquals("fixture-failed", NoCheatExpeditionLiveFixture.failureCode(
            new IllegalStateException("private implementation detail")
        ));
    }

    @Test
    void restorationChecksOnlyExplicitStableFields() {
        CompoundTag expected = new CompoundTag();
        expected.putInt("CodexFood", 14);
        expected.putInt("Age", 10);
        CompoundTag actual = expected.copy();
        actual.putInt("Age", 11);

        assertTrue(NoCheatExpeditionLiveFixture.stableFieldsMatch(
            expected, actual, "CodexFood"
        ));
        actual.putInt("CodexFood", 13);
        assertFalse(NoCheatExpeditionLiveFixture.stableFieldsMatch(
            expected, actual, "CodexFood"
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
        boolean hasCheats,
        boolean playerSurvival,
        boolean creativeResources,
        boolean mounted
    ) {
        return NoCheatExpeditionLiveFixture.setupRefusalReason(
            alive, downed, activeTaskId, pausedTaskCount, schedulerLifecycle, managedEating,
            actorSleeping, sameDimension, naturalDimension, hasCheats, playerSurvival,
            creativeResources, mounted
        );
    }

    private static boolean complete(
        boolean cheatsObserved,
        boolean creativeObserved,
        boolean sawGather,
        boolean sawDeliver,
        boolean sawExcursion,
        int maxDistanceMilli,
        int maxStepMilli,
        int breaks,
        int deliveryItems,
        int playerLogs,
        int npcLogs,
        int worldLogs,
        int returnDistanceMilli,
        int ownerDriftMilli,
        boolean taskIdStable,
        int errors
    ) {
        return NoCheatExpeditionLiveFixture.acceptanceComplete(
            cheatsObserved, creativeObserved, sawGather, sawDeliver, sawExcursion,
            maxDistanceMilli, maxStepMilli, breaks, deliveryItems, playerLogs, npcLogs,
            worldLogs, returnDistanceMilli, ownerDriftMilli, taskIdStable, errors
        );
    }
}
