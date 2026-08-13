package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NpcTaskPersistenceTest {
    @Test
    void roundTripsActiveAndPausedWorkInResumeOrder() {
        NpcTaskPersistence.WorkState gather = work("gather-1", "gather", 50, "被战斗打断", 7);
        NpcTaskPersistence.WorkState queuedGather = work("gather-2", "gather", 50, "等待当前任务", 2);
        NpcTaskPersistence.WorkState deliver = work("deliver-1", "deliver", 60, "等待当前任务", 3);
        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "downed", gather, List.of(queuedGather, deliver))
        ));

        assertEquals("downed", restored.lifecycle());
        assertEquals("gather-1", restored.active().id());
        assertEquals(7, restored.active().checkpoint().get("completed").getAsInt());
        assertEquals("gather-2", restored.paused().get(0).id());
        assertEquals("deliver-1", restored.paused().get(1).id());
        assertEquals(60, restored.paused().get(1).priority());
        assertEquals(
            "deliver-1",
            TaskPriorityPolicy.highestPriorityFirst(restored.paused(), NpcTaskPersistence.WorkState::priority).id()
        );
    }

    @Test
    void rejectsCorruptOrMismatchedState() {
        assertThrows(IllegalArgumentException.class, () -> NpcTaskPersistence.decode("not-json"));
        NpcTaskPersistence.WorkState invalid = work("x", "gather", 1, "", 0);
        invalid.spec().addProperty("kind", "combat");
        assertThrows(IllegalArgumentException.class, () -> NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", invalid, List.of())
        ));
    }

    @Test
    void readsVersionOneCheckpointsWrittenBeforeRecoverableBuildWasAdded() {
        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(
            "{\"version\":1,\"lifecycle\":\"ready\",\"active\":null,\"paused\":[]}"
        );
        assertEquals(0, restored.paused().size());
        assertTrue(restored.recoverableBuild() == null);
    }

    @Test
    void roundTripsDeepMiningRouteAndSupplyCheckpoint() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("deepMiningPhase", "branching");
        checkpoint.addProperty("deepMiningItemId", "minecraft:diamond");
        checkpoint.addProperty("deepMiningTargetY", -58);
        checkpoint.addProperty("deepMiningDirection", "north");
        checkpoint.addProperty("deepMiningPreflightComplete", true);
        checkpoint.addProperty("deepMiningStaircaseStep", 122);
        checkpoint.addProperty("deepMiningBranchIndex", 3);
        checkpoint.addProperty("deepMiningBranchProgress", 17);
        checkpoint.addProperty("deepMiningRegionIndex", 1);
        checkpoint.addProperty("deepMiningBrokenBlocks", 401);
        checkpoint.addProperty("deepMiningPlacedTorches", 26);
        JsonObject lastSafe = new JsonObject();
        lastSafe.addProperty("x", 91);
        lastSafe.addProperty("y", -58);
        lastSafe.addProperty("z", -13);
        checkpoint.add("deepMiningLastSafeStand", lastSafe);

        NpcTaskPersistence.WorkState mining = new NpcTaskPersistence.WorkState(
            "deep-mine-1",
            "gather",
            spec("gather"),
            new JsonObject(),
            "FOLLOW",
            50,
            "",
            checkpoint
        );
        mining.spec().addProperty("itemId", "minecraft:diamond");
        mining.spec().addProperty("count", 3);
        JsonObject restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", mining, List.of())
        )).active().checkpoint();

        assertEquals("branching", restored.get("deepMiningPhase").getAsString());
        assertEquals(-58, restored.get("deepMiningTargetY").getAsInt());
        assertEquals(17, restored.get("deepMiningBranchProgress").getAsInt());
        assertEquals(401, restored.get("deepMiningBrokenBlocks").getAsInt());
        assertEquals(-58, restored.getAsJsonObject("deepMiningLastSafeStand").get("y").getAsInt());
    }

    @Test
    void boundsQueueAndPriority() {
        List<NpcTaskPersistence.WorkState> tooMany = new ArrayList<>();
        for (int i = 0; i <= NpcTaskPersistence.MAX_QUEUED_TASKS; i++) tooMany.add(work("t" + i, "move", 50, "", 0));
        assertThrows(IllegalArgumentException.class, () -> NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", null, tooMany)
        ));

        NpcTaskPersistence.SchedulerState state = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", work("x", "move", 5000, "", 0), List.of())
        ));
        assertEquals(1000, state.active().priority());
    }

    @Test
    void compressedFormatRestoresBlueprintLargerThanNbtStringLimit() {
        JsonObject plan = new JsonObject();
        JsonArray blocks = new JsonArray();
        for (int index = 0; index < 5_000; index++) {
            JsonObject block = new JsonObject();
            block.addProperty("blockId", "minecraft:cobblestone");
            block.addProperty("x", index);
            blocks.add(block);
        }
        plan.add("blocks", blocks);
        NpcTaskPersistence.WorkState build = new NpcTaskPersistence.WorkState(
            "build-large",
            "build",
            spec("build"),
            plan,
            "FOLLOW",
            50,
            "",
            new JsonObject()
        );

        byte[] encoded = NpcTaskPersistence.encodeCompressed(
            new NpcTaskPersistence.SchedulerState(1, "ready", build, List.of())
        );
        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decodeCompressed(encoded);

        assertTrue(NpcTaskPersistence.encode(restored).length() > 65_535);
        assertEquals(5_000, restored.active().plan().getAsJsonArray("blocks").size());
    }

    @Test
    void rejectsCorruptCompressedState() {
        assertThrows(IllegalArgumentException.class, () -> NpcTaskPersistence.decodeCompressed(new byte[] {1, 2, 3}));
    }

    @Test
    void preservesLockedDragonTargetsAndObservationProgress() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("stableTicks", 4);
        checkpoint.addProperty("dragonTargetId", "11111111-1111-1111-1111-111111111111");
        checkpoint.addProperty("dragonCombatTargetId", "22222222-2222-2222-2222-222222222222");
        NpcTaskPersistence.WorkState dragon = new NpcTaskPersistence.WorkState(
            "dragon-assist",
            "dragon",
            spec("dragon"),
            new JsonObject(),
            "FOLLOW",
            80,
            "被玩家防御打断",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", dragon, List.of())
        ));

        assertEquals(4, restored.active().checkpoint().get("stableTicks").getAsInt());
        assertEquals(
            "11111111-1111-1111-1111-111111111111",
            restored.active().checkpoint().get("dragonTargetId").getAsString()
        );
        assertEquals(
            "22222222-2222-2222-2222-222222222222",
            restored.active().checkpoint().get("dragonCombatTargetId").getAsString()
        );
    }

    @Test
    void preservesNestedBuildMaterialGoalsAndRouteProgress() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("buildIndex", 17);
        checkpoint.addProperty("completed", 17);
        checkpoint.addProperty("buildPhase", "gather");
        checkpoint.addProperty("buildPhaseStartedTick", 240);
        checkpoint.addProperty("buildLastProgressTick", 300);
        JsonArray goals = new JsonArray();
        JsonObject parent = new JsonObject();
        parent.addProperty("itemId", "minecraft:glass_pane");
        parent.addProperty("materialContextId", "minecraft:dark_oak_fence");
        parent.addProperty("targetCount", 16);
        parent.addProperty("lastInventoryCount", 0);
        parent.addProperty("startedTick", 200);
        parent.addProperty("stalledTicks", 10);
        parent.addProperty("attemptedRoutes", 1);
        parent.add("suspendedDeepMining", suspendedDeepMiningCheckpoint());
        goals.add(parent);
        JsonObject child = new JsonObject();
        child.addProperty("itemId", "minecraft:sand");
        child.addProperty("materialContextId", "minecraft:dark_oak_fence");
        child.addProperty("selector", "#minecraft:sand");
        child.addProperty("targetCount", 6);
        child.addProperty("lastInventoryCount", 2);
        child.addProperty("startedTick", 240);
        child.addProperty("stalledTicks", 4);
        child.addProperty("attemptedRoutes", 1);
        goals.add(child);
        checkpoint.add("buildMaterialGoals", goals);
        NpcTaskPersistence.WorkState build = new NpcTaskPersistence.WorkState(
            "build-material-resume",
            "build",
            spec("build"),
            new JsonObject(),
            "FOLLOW",
            50,
            "战斗暂停",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", build, List.of())
        ));

        JsonObject restoredCheckpoint = restored.active().checkpoint();
        assertEquals(17, restoredCheckpoint.get("completed").getAsInt());
        assertEquals("gather", restoredCheckpoint.get("buildPhase").getAsString());
        assertEquals(2, restoredCheckpoint.getAsJsonArray("buildMaterialGoals").size());
        assertEquals(
            "minecraft:sand",
            restoredCheckpoint.getAsJsonArray("buildMaterialGoals").get(1).getAsJsonObject().get("itemId").getAsString()
        );
        assertEquals(
            "#minecraft:sand",
            restoredCheckpoint.getAsJsonArray("buildMaterialGoals").get(1).getAsJsonObject().get("selector").getAsString()
        );
        assertEquals(
            "minecraft:dark_oak_fence",
            restoredCheckpoint.getAsJsonArray("buildMaterialGoals").get(1).getAsJsonObject()
                .get("materialContextId").getAsString()
        );
        assertEquals(
            1,
            restoredCheckpoint.getAsJsonArray("buildMaterialGoals").get(0).getAsJsonObject().get("attemptedRoutes").getAsInt()
        );
        JsonObject mining = restoredCheckpoint.getAsJsonArray("buildMaterialGoals").get(0).getAsJsonObject()
            .getAsJsonObject("suspendedDeepMining");
        assertEquals("minecraft:diamond", mining.get("itemId").getAsString());
        assertEquals(87, mining.get("staircaseStep").getAsInt());
        assertEquals(-51, mining.getAsJsonObject("lastSafeStand").get("y").getAsInt());
    }

    private static JsonObject suspendedDeepMiningCheckpoint() {
        JsonObject mining = new JsonObject();
        mining.addProperty("phase", "branching");
        mining.addProperty("itemId", "minecraft:diamond");
        mining.addProperty("targetY", -58);
        mining.addProperty("direction", "west");
        mining.addProperty("staircaseStep", 87);
        JsonObject lastSafe = new JsonObject();
        lastSafe.addProperty("x", -107);
        lastSafe.addProperty("y", -51);
        lastSafe.addProperty("z", -2);
        mining.add("lastSafeStand", lastSafe);
        return mining;
    }

    @Test
    void preservesCraftGatherPhaseStartSeparatelyFromWholeTaskAge() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("ticks", 200_000);
        checkpoint.addProperty("craftGatherItemId", "minecraft:cobblestone");
        checkpoint.addProperty("craftGatherCount", 16);
        checkpoint.addProperty("craftGatherStartedTick", 199_980);
        NpcTaskPersistence.WorkState build = new NpcTaskPersistence.WorkState(
            "build-gather-timing",
            "build",
            spec("build"),
            new JsonObject(),
            "FOLLOW",
            50,
            "",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", build, List.of())
        ));

        assertEquals(200_000, restored.active().checkpoint().get("ticks").getAsInt());
        assertEquals(199_980, restored.active().checkpoint().get("craftGatherStartedTick").getAsInt());
    }

    @Test
    void preservesRecoverableBuildFailureSeparatelyFromTheRunnableQueue() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("buildIndex", 23);
        checkpoint.addProperty("failureCode", "BLOCK_BREAK_DENIED");
        checkpoint.addProperty("failureMessage", "施工位受保护");
        JsonObject target = new JsonObject();
        target.addProperty("x", -229);
        target.addProperty("y", 70);
        target.addProperty("z", -174);
        checkpoint.add("targetBlock", target);
        NpcTaskPersistence.WorkState failedBuild = new NpcTaskPersistence.WorkState(
            "failed-build",
            "build",
            spec("build"),
            new JsonObject(),
            "FOLLOW",
            50,
            "失败点 23",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", null, List.of(), failedBuild)
        ));

        assertEquals(0, restored.paused().size());
        assertEquals("failed-build", restored.recoverableBuild().id());
        assertEquals(23, restored.recoverableBuild().checkpoint().get("buildIndex").getAsInt());
        assertEquals(
            "BLOCK_BREAK_DENIED",
            restored.recoverableBuild().checkpoint().get("failureCode").getAsString()
        );
    }

    @Test
    void preservesCrossContainerStorageProgressAcrossRestart() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("initialized", true);
        checkpoint.addProperty("requestedCount", 8);
        checkpoint.addProperty("completed", 4);
        checkpoint.addProperty("storageExpanded", true);
        JsonObject workstation = new JsonObject();
        workstation.addProperty("x", 120);
        workstation.addProperty("y", 65);
        workstation.addProperty("z", -42);
        checkpoint.add("workstation", workstation);
        JsonArray skipped = new JsonArray();
        skipped.add(workstation.deepCopy());
        checkpoint.add("skippedStorageTargets", skipped);

        JsonObject retrieveSpec = spec("retrieve");
        retrieveSpec.addProperty("itemId", "minecraft:cobblestone");
        retrieveSpec.addProperty("count", 8);
        NpcTaskPersistence.WorkState retrieve = new NpcTaskPersistence.WorkState(
            "retrieve-resume",
            "retrieve",
            retrieveSpec,
            new JsonObject(),
            "FOLLOW",
            60,
            "保存退出",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", retrieve, List.of())
        ));

        JsonObject restoredCheckpoint = restored.active().checkpoint();
        assertEquals(4, restoredCheckpoint.get("completed").getAsInt());
        assertEquals(8, restoredCheckpoint.get("requestedCount").getAsInt());
        assertTrue(restoredCheckpoint.get("storageExpanded").getAsBoolean());
        assertEquals(120, restoredCheckpoint.getAsJsonObject("workstation").get("x").getAsInt());
        assertEquals(1, restoredCheckpoint.getAsJsonArray("skippedStorageTargets").size());
    }

    @Test
    void preservesGatherNavigationRetryAndTeleportGuardsAcrossRestart() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("gatherPathFailures", 3);
        checkpoint.addProperty("lastGatherPathAttemptTick", 418);
        checkpoint.addProperty("gatherStandPathCursor", 7);
        JsonObject lastTeleportTarget = new JsonObject();
        lastTeleportTarget.addProperty("x", -214);
        lastTeleportTarget.addProperty("y", 71);
        lastTeleportTarget.addProperty("z", 96);
        checkpoint.add("lastTeleportTarget", lastTeleportTarget);
        NpcTaskPersistence.WorkState gather = new NpcTaskPersistence.WorkState(
            "gather-navigation-resume",
            "gather",
            spec("gather"),
            new JsonObject(),
            "FOLLOW",
            50,
            "保存退出",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", gather, List.of())
        ));

        JsonObject restoredCheckpoint = restored.active().checkpoint();
        assertEquals(3, restoredCheckpoint.get("gatherPathFailures").getAsInt());
        assertEquals(418, restoredCheckpoint.get("lastGatherPathAttemptTick").getAsInt());
        assertEquals(7, restoredCheckpoint.get("gatherStandPathCursor").getAsInt());
        assertEquals(-214, restoredCheckpoint.getAsJsonObject("lastTeleportTarget").get("x").getAsInt());
    }

    @Test
    void preservesFishingRodStorageDependencyPhaseAcrossRestart() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("initialized", true);
        checkpoint.addProperty("requestedCount", 3);
        checkpoint.addProperty("fishingStoragePhase", 2);
        NpcTaskPersistence.WorkState fish = new NpcTaskPersistence.WorkState(
            "fish-dependency-resume",
            "fish",
            spec("fish"),
            new JsonObject(),
            "FOLLOW",
            50,
            "保存退出",
            checkpoint
        );

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decode(NpcTaskPersistence.encode(
            new NpcTaskPersistence.SchedulerState(1, "ready", fish, List.of())
        ));

        JsonObject restoredCheckpoint = restored.active().checkpoint();
        assertEquals(2, restoredCheckpoint.get("fishingStoragePhase").getAsInt());
        assertEquals(3, restoredCheckpoint.get("requestedCount").getAsInt());
    }

    @Test
    void preservesProvisionFoodHuntCookingAndDeliveryCheckpoint() {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("initialized", true);
        checkpoint.addProperty("requestedCount", 4);
        checkpoint.addProperty("completed", 5);
        checkpoint.addProperty("loaded", 5);
        checkpoint.addProperty("foodPhase", 1);
        checkpoint.addProperty("foodCookingInputId", "minecraft:beef");
        checkpoint.addProperty("foodCookingOutputId", "minecraft:cooked_beef");
        checkpoint.addProperty("foodCookingTargetCount", 5);
        checkpoint.addProperty("foodCookedCount", 1);
        checkpoint.addProperty("foodTransferredCount", 0);
        checkpoint.addProperty("foodAnimalTargetId", "33333333-3333-3333-3333-333333333333");
        JsonObject workstation = new JsonObject();
        workstation.addProperty("x", 12);
        workstation.addProperty("y", 96);
        workstation.addProperty("z", -8);
        checkpoint.add("workstation", workstation);
        JsonArray skipped = new JsonArray();
        skipped.add("44444444-4444-4444-4444-444444444444");
        checkpoint.add("skippedFoodAnimalTargets", skipped);

        NpcTaskPersistence.WorkState food = new NpcTaskPersistence.WorkState(
            "food-survival-restart",
            "provision-food",
            spec("provision-food"),
            new JsonObject(),
            "STAY",
            70,
            "",
            checkpoint
        );
        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decodeCompressed(
            NpcTaskPersistence.encodeCompressed(
                new NpcTaskPersistence.SchedulerState(1, "ready", food, List.of())
            )
        );

        JsonObject value = restored.active().checkpoint();
        assertEquals("food-survival-restart", restored.active().id());
        assertEquals("minecraft:beef", value.get("foodCookingInputId").getAsString());
        assertEquals("minecraft:cooked_beef", value.get("foodCookingOutputId").getAsString());
        assertEquals(5, value.get("loaded").getAsInt());
        assertEquals(1, value.get("foodCookedCount").getAsInt());
        assertEquals(12, value.getAsJsonObject("workstation").get("x").getAsInt());
        assertEquals(1, value.getAsJsonArray("skippedFoodAnimalTargets").size());
    }

    private static NpcTaskPersistence.WorkState work(String id, String kind, int priority, String reason, int completed) {
        JsonObject spec = spec(kind);
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("completed", completed);
        return new NpcTaskPersistence.WorkState(id, kind, spec, new JsonObject(), "FOLLOW", priority, reason, checkpoint);
    }

    private static JsonObject spec(String kind) {
        JsonObject spec = new JsonObject();
        spec.addProperty("kind", kind);
        return spec;
    }
}
