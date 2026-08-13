package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.client.BridgeConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.ToolActions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Predicate;

@SuppressWarnings("deprecation")
public final class NpcTaskEngine {
    private static final double FOLLOW_STOP_DISTANCE = 3.0;
    private static final double FOLLOW_RECOVERY_DISTANCE = 6.0;
    private static final int FOLLOW_REPATH_TICKS = 10;
    private static final int FOLLOW_MAX_PATH_FAILURES = 4;
    private static final int FOLLOW_MAX_STALLED_TICKS = 100;
    private static final int COMBAT_ASSIST_TIMEOUT_TICKS = 20 * 30;
    private static final int COMBAT_ASSIST_RETRY_COOLDOWN_TICKS = 20 * 30;
    private static final int GATHER_LOCAL_SEARCH_RADIUS = 48;
    private static final int GATHER_MAX_EXCURSIONS = 64;
    private static final double GATHER_EXCURSION_DISTANCE = 72.0;
    private static final int GATHER_MAX_CONNECTED_TARGETS = 512;
    private static final double GATHER_INTERACTION_REACH = 4.5;
    private static final int GATHER_HOME_PROTECTION_RADIUS = 16;
    private static final int MAX_CRAFT_DEPENDENCY_DEPTH = 6;
    private static final int CRAFT_WORKSTATION_SEARCH_RADIUS = 64;
    private static final int CRAFT_PREREQUISITE_TIMEOUT_TICKS = 20 * 60 * 30;
    private static final int BUILD_MATERIAL_BATCH_LIMIT = 64;
    private static final int BUILD_MATERIAL_MAX_DEPTH = 8;
    private static final int MAX_TASK_FURNACE_TRANSACTIONS = BUILD_MATERIAL_MAX_DEPTH + 4;
    private static final int BUILD_MATERIAL_STALL_TIMEOUT_TICKS = 20 * 60 * 30;
    private static final int BUILD_ROUTE_STORAGE = 1;
    private static final double DRAGON_MOUNTED_FLIGHT_STEP = 0.55D;
    private static final TicketType<UUID> TASK_CHUNK_TICKET = TicketType.create(
        "minecraft_codex_task",
        Comparator.comparing(UUID::toString),
        20 * 30
    );
    private static final String FURNACE_RECOVERY_TAG = "CodexFurnaceRecovery";
    private static final String FURNACE_RECOVERY_TASK_TAG = "CodexFurnaceRecoveryTask";
    private static final String FURNACE_RECOVERY_DIMENSION_TAG = "CodexFurnaceRecoveryDimension";
    private static final String FURNACE_RECOVERY_POSITION_TAG = "CodexFurnaceRecoveryPosition";
    private static final String FURNACE_RECOVERY_SLOT_TAG = "CodexFurnaceRecoverySlot";
    private static final String FURNACE_RECOVERY_REASON_TAG = "CodexFurnaceRecoveryReason";
    private static final String TASK_FURNACE_CLAIM_TAG = "CodexTaskFurnaceClaim";

    public enum Stance {
        FOLLOW((byte) 0), STAY((byte) 1), GUARD((byte) 2), WORK((byte) 3);

        private final byte id;

        Stance(byte id) {
            this.id = id;
        }

        public byte id() {
            return id;
        }

        public static Stance fromId(byte id) {
            for (Stance stance : values()) if (stance.id == id) return stance;
            return FOLLOW;
        }
    }

    record GatherDiagnostics(
        int queuedTargets,
        int skippedTargets,
        int excursions,
        boolean treeCluster,
        boolean clusterReached,
        boolean targetSelected
    ) {
    }

    record DeepMiningDiagnostics(
        String phase,
        String itemId,
        int targetY,
        int staircaseStep,
        int branchIndex,
        int branchProgress,
        int regionIndex,
        int brokenBlocks,
        int placedTorches,
        BlockPos entrance,
        BlockPos lastSafeStand
    ) {
        static DeepMiningDiagnostics empty() {
            return new DeepMiningDiagnostics("", "", 0, 0, 0, 0, 0, 0, 0, null, null);
        }
    }

    record ProvisionFoodDiagnostics(
        String taskId,
        boolean paused,
        int foodPhase,
        int requestedCount,
        int completed,
        int loaded,
        int cookingTargetCount,
        int cookedCount,
        int transferredCount,
        String inputId,
        String outputId,
        BlockPos workstation
    ) {
        static ProvisionFoodDiagnostics empty() {
            return new ProvisionFoodDiagnostics("", false, 0, 0, 0, 0, 0, 0, 0, "", "", null);
        }
    }

    private final CodexNpcEntity npc;
    private final BridgeConfig config;
    private final NpcInteractionProxy proxy;
    private ActiveWork active;
    private final Deque<ActiveWork> pausedWorks = new ArrayDeque<>();
    private ServerLevel forcedTaskLevel;
    private ChunkPos forcedTaskChunk;
    private double guardRadius = 12;
    private int attackCooldown;
    private int followRepathCooldown;
    private int followPathFailures;
    private int followStalledTicks;
    private double bestFollowDistance = Double.POSITIVE_INFINITY;
    private FollowMovementPolicy.Mode followMode = FollowMovementPolicy.Mode.GROUND;
    private LivingEntity assistTarget;
    private int assistTargetExpiresAt;
    private double assistTargetBestDistance = Double.POSITIVE_INFINITY;
    private float assistTargetLowestHealth = Float.POSITIVE_INFINITY;
    private UUID suppressedAssistTargetId;
    private int suppressedAssistTargetUntil;
    private boolean suspendedForDowned;
    /** The most recent environmental build failure, retained for explicit retry. */
    private ActiveWork recoverableBuild;

    public NpcTaskEngine(CodexNpcEntity npc, BridgeConfig config) {
        this.npc = npc;
        this.config = config;
        this.proxy = new NpcInteractionProxy(npc, config);
    }

    public void setStance(Stance stance) {
        npc.setStance(stance);
        stopAerialFollow();
        resetFollowTracking();
        if (stance == Stance.STAY) npc.getNavigation().stop();
        npc.setStatus(switch (stance) {
            case FOLLOW -> "正在跟随";
            case STAY -> "原地等待";
            case GUARD -> "护卫待命";
            case WORK -> "正在工作";
        });
    }

    public void followOwner() {
        cancelAllWork("主人要求立即跟随", "STANCE_CHANGED");
        clearAssistTarget(false);
        setStance(Stance.FOLLOW);
        ServerPlayer owner = npc.owner();
        if (owner != null && owner.level() == npc.level()) tickFollow(owner);
    }

    public void stay() {
        cancelAllWork("主人要求原地等待", "STANCE_CHANGED");
        clearAssistTarget(false);
        setStance(Stance.STAY);
    }

    public void assistOwnerAgainst(ServerPlayer owner, LivingEntity target) {
        if (!npc.isOwnedBy(owner) || owner.level() != npc.level() || target.level() != npc.level()) return;
        if (!isValidAssistTarget(owner, target)) return;
        UUID targetId = target.getUUID();
        boolean suppressedTarget = targetId.equals(suppressedAssistTargetId);
        if (CombatAssistPolicy.retrySuppressed(
            suppressedTarget,
            npc.tickCount,
            suppressedAssistTargetUntil
        )) return;
        if (suppressedTarget) {
            suppressedAssistTargetId = null;
            suppressedAssistTargetUntil = 0;
        }
        boolean changedTarget = assistTarget == null || !targetId.equals(assistTarget.getUUID());
        assistTarget = target;
        assistTargetExpiresAt = CombatAssistPolicy.updateDeadline(
            npc.tickCount,
            assistTargetExpiresAt,
            COMBAT_ASSIST_TIMEOUT_TICKS,
            changedTarget,
            false
        );
        if (changedTarget) {
            assistTargetBestDistance = npc.distanceTo(target);
            assistTargetLowestHealth = target.getHealth();
        }
        suspendActiveLifeInteraction();
        npc.cancelManagedEating();
        if (changedTarget && active != null) {
            active.pauseReason = "正在优先保护主人";
            if (!active.id.startsWith("local:")) {
                sendProgressUpdate(active, activeProgress(active), "任务已暂停：正在优先保护主人", "paused");
            }
        }
        npc.setTarget(target);
        npc.setStatus("正在协助 " + owner.getGameProfile().getName() + " 战斗");
    }

    public void start(JsonObject task, JsonObject plan) {
        JsonObject spec = task.getAsJsonObject("spec");
        Stance resumeStance = npc.stance() == Stance.WORK ? Stance.FOLLOW : npc.stance();
        ActiveWork next = new ActiveWork(task.get("id").getAsString(), spec, plan, resumeStance);
        if ("build".equals(next.kind) && recoverableBuild != null
            && recoverableBuild.id.equals(next.id)) {
            next = recoverableBuild;
            recoverableBuild = null;
            next.pauseReason = "";
            next.failedActions = 0;
            next.stalledTicks = 0;
            next.buildPathFailures = 0;
            next.lastBuildPathAttemptTick = -1;
            next.buildStandPathCursor = 0;
            next.lastDistance = -1;
            next.lastTeleportTarget = null;
            next.buildLastProgressTick = next.ticks;
            npc.setStatus("正在从上次失败点恢复建造");
        }
        if (active != null) {
            if (!TaskPriorityPolicy.shouldPreempt(next.priority, active.priority)) {
                next.pauseReason = "等待更高优先级任务 " + active.id;
                pausedWorks.addLast(next);
                if (!next.id.startsWith("local:")) {
                    sendProgressUpdate(next, 0, "任务已排队：" + next.pauseReason, "paused");
                }
                return;
            }
            pauseActive("被优先级 " + next.priority + " 的任务打断");
        }
        switch (next.kind) {
            case "follow" -> {
                cancelPausedWorks("主人切换为跟随模式", "STANCE_CHANGED");
                clearAssistTarget(false);
                setStance(Stance.FOLLOW);
                completePersistentStance(next, "已开始跟随主人");
            }
            case "guard" -> {
                cancelPausedWorks("主人切换为护卫模式", "STANCE_CHANGED");
                clearAssistTarget(false);
                guardRadius = number(spec, "radius", 12);
                setStance(Stance.GUARD);
                completePersistentStance(next, "已进入护卫模式");
            }
            default -> {
                active = next;
                setStance(Stance.WORK);
                npc.setStatus("正在执行 " + next.kind);
            }
        }
    }

    public void cancel(String taskId, String reason) {
        if (active != null && active.id.equals(taskId)) {
            fail(active, reason, "CANCELLED");
            return;
        }
        if (recoverableBuild != null && BuildFailureRecoveryPolicy.shouldDiscardOnCancellation(
            recoverableBuild.id,
            taskId
        )) {
            recoverAllTaskFurnaces(recoverableBuild, "recoverable-build-cancel");
            suspendLifeInteraction(recoverableBuild);
            recoverableBuild = null;
            npc.getNavigation().stop();
            npc.setTarget(null);
            npc.setStatus(reason);
            return;
        }
        ActiveWork paused = pausedWorks.stream().filter(work -> work.id.equals(taskId)).findFirst().orElse(null);
        if (paused == null) return;
        pausedWorks.remove(paused);
        FurnaceRecoverySummary recovery = recoverAllTaskFurnaces(paused, "paused-task-cancel");
        suspendLifeInteraction(paused);
        if (!paused.id.startsWith("local:")) {
            CodexNetwork.sendResult(npc, paused.id, false, reason + recovery.detail(), "CANCELLED");
        }
    }

    public void emergencyStop(String reason) {
        cancelPausedWorks(reason, "EMERGENCY_STOP");
        if (active != null) fail(active, reason, "EMERGENCY_STOP");
        if (recoverableBuild != null) {
            recoverAllTaskFurnaces(recoverableBuild, "recoverable-build-emergency-stop");
            suspendLifeInteraction(recoverableBuild);
            recoverableBuild = null;
        }
        npc.cancelManagedEating();
        npc.getNavigation().stop();
        npc.setTarget(null);
        clearAssistTarget();
        setStance(Stance.STAY);
    }

    public boolean isExplicitEating() {
        return active != null && "eat".equals(active.kind);
    }

    public boolean canStartAutomaticEating() {
        if (assistTarget != null || active != null && "combat".equals(active.kind)) return false;
        if (active != null && "provision-food".equals(active.kind) && active.foodCookingInputId != null) return false;
        if (active != null && isSurvivalReserveProvision(active)) return false;
        return nearestHostile(npc, 8.0D, "hostile") == null;
    }

    public String activeTaskId() {
        if (assistTarget != null) return "local:combat-assist";
        return active == null ? "" : active.id;
    }

    public String activeTaskKind() {
        if (assistTarget != null) return "combat";
        return active == null ? "" : active.kind;
    }

    public int pausedTaskCount() {
        return pausedWorks.size()
            + (assistTarget != null && active != null ? 1 : 0)
            + (recoverableBuild != null ? 1 : 0);
    }

    public String primaryPauseReason() {
        if (assistTarget != null && active != null) return "护主战斗中，原任务稍后继续";
        ActiveWork paused = pausedWorks.peekFirst();
        if (paused != null) return paused.pauseReason;
        return recoverableBuild == null ? "" : recoverableBuild.pauseReason;
    }

    public int activeTaskPriority() {
        if (assistTarget != null) return TaskPriorityPolicy.COMBAT_ASSIST;
        return active == null ? 0 : active.priority;
    }

    public String schedulerLifecycle() {
        return suspendedForDowned ? "downed" : active == null && assistTarget == null ? "idle" : "running";
    }

    GatherDiagnostics gatherDiagnosticsForFixture() {
        ActiveWork work = active;
        if (work == null || !"gather".equals(work.kind)) {
            return new GatherDiagnostics(0, 0, 0, false, false, false);
        }
        return new GatherDiagnostics(
            work.gatherTargets.size(),
            work.skippedGatherTargets.size(),
            work.gatherExcursions,
            work.gatherTreeCluster,
            work.gatherClusterReached,
            work.targetBlock != null
        );
    }

    ProvisionFoodDiagnostics provisionFoodDiagnosticsForFixture() {
        ActiveWork work = active;
        if (work == null || !"provision-food".equals(work.kind)) {
            return ProvisionFoodDiagnostics.empty();
        }
        return new ProvisionFoodDiagnostics(
            work.id,
            assistTarget != null,
            work.foodPhase,
            work.requestedCount,
            work.completed,
            work.loaded,
            work.foodCookingTargetCount,
            work.foodCookedCount,
            work.foodTransferredCount,
            work.foodCookingInputId == null ? "" : work.foodCookingInputId,
            work.foodCookingOutputId == null ? "" : work.foodCookingOutputId,
            work.workstation == null ? null : work.workstation.immutable()
        );
    }

    public JsonArray observableTaskQueue() {
        JsonArray result = new JsonArray();
        if (assistTarget != null) {
            JsonObject combat = new JsonObject();
            combat.addProperty("id", "local:combat-assist");
            combat.addProperty("kind", "combat");
            combat.addProperty("phase", "active");
            combat.addProperty("priority", 100);
            combat.addProperty("progress", 0.0D);
            result.add(combat);
        }
        if (active != null) result.add(observableTask(
            active,
            assistTarget == null ? "active" : "paused",
            assistTarget == null ? "" : "正在优先保护主人"
        ));
        for (ActiveWork work : pausedWorks) result.add(observableTask(work, "paused", work.pauseReason));
        if (recoverableBuild != null) {
            result.add(observableTask(
                recoverableBuild,
                "paused",
                "上次建造失败，可用“继续建造”恢复：" + recoverableBuild.failureCode
            ));
        }
        return result;
    }

    DeepMiningDiagnostics deepMiningDiagnostics() {
        ActiveWork work = active;
        if (work == null || work.deepMiningPhase.isBlank()) {
            work = pausedWorks.stream()
                .filter(candidate -> !candidate.deepMiningPhase.isBlank())
                .findFirst()
                .orElse(null);
        }
        if (work == null) return DeepMiningDiagnostics.empty();
        return new DeepMiningDiagnostics(
            work.deepMiningPhase,
            work.deepMiningItemId,
            work.deepMiningTargetY,
            work.deepMiningStaircaseStep,
            work.deepMiningBranchIndex,
            work.deepMiningBranchProgress,
            work.deepMiningRegionIndex,
            work.deepMiningBrokenBlocks,
            work.deepMiningPlacedTorches,
            work.deepMiningEntrance == null ? null : work.deepMiningEntrance.immutable(),
            work.deepMiningLastSafeStand == null ? null : work.deepMiningLastSafeStand.immutable()
        );
    }

    String recoverableBuildTaskIdForFixture() {
        return recoverableBuild == null ? "" : recoverableBuild.id;
    }

    int recoverableBuildIndexForFixture() {
        return recoverableBuild == null ? -1 : recoverableBuild.buildIndex;
    }

    int recoverableBuildTotalForFixture() {
        return recoverableBuild == null ? 0 : recoverableBuild.requestedCount;
    }

    String recoverableBuildFailureCodeForFixture() {
        return recoverableBuild == null || recoverableBuild.failureCode == null
            ? ""
            : recoverableBuild.failureCode;
    }

    String activeBuildDiagnosticForFixture() {
        ActiveWork work = active;
        if (work == null || !"build".equals(work.kind)) return "";
        return "ticks=" + work.ticks
            + ",initialized=" + (work.initialized ? 1 : 0)
            + ",passenger=" + (npc.isPassenger() ? 1 : 0)
            + ",index=" + work.buildIndex
            + ",requested=" + work.requestedCount
            + ",completed=" + work.completed
            + ",phase=" + work.buildPhase
            + ",goals=" + work.buildMaterialGoals.size()
            + ",gather=" + (work.craftGatherItemId == null ? 0 : 1)
            + ",target=" + (work.targetBlock == null ? 0 : 1);
    }

    BlockPos recoverableBuildTargetForFixture() {
        return recoverableBuild == null ? null : recoverableBuild.targetBlock;
    }

    boolean discardRecoverableBuildCheckpointForFixture(BlockPos expectedTarget) {
        if (recoverableBuild == null || expectedTarget == null
            || !expectedTarget.equals(recoverableBuild.targetBlock)) return false;
        recoverableBuild = null;
        return true;
    }

    public double activeTaskProgress() {
        if (assistTarget != null) return 0.0D;
        if (active == null) return 0.0D;
        if (active.requestedCount > 0) return Math.min(1.0D, active.completed / (double) active.requestedCount);
        return 0.0D;
    }

    private void pauseActive(String reason) {
        ActiveWork work = active;
        if (work == null) return;
        suspendLifeInteraction(work);
        active = null;
        npc.cancelManagedEating();
        npc.getNavigation().stop();
        npc.setTarget(null);
        pausedWorks.push(work);
        work.pauseReason = reason;
        sendProgressUpdate(work, activeProgress(work), "任务已暂停：" + reason, "paused");
    }

    private boolean resumePausedWork() {
        ActiveWork resumed = TaskPriorityPolicy.highestPriorityFirst(pausedWorks, work -> work.priority);
        if (resumed == null) return false;
        pausedWorks.remove(resumed);
        active = resumed;
        resumed.pauseReason = "";
        npc.setStance(Stance.WORK);
        npc.setStatus("正在恢复 " + resumed.kind + " 任务");
        sendProgressUpdate(resumed, activeProgress(resumed), "已恢复暂停的任务", "active");
        return true;
    }

    private double activeProgress(ActiveWork work) {
        return work.requestedCount <= 0 ? 0.0D : Math.min(1.0D, work.completed / (double) work.requestedCount);
    }

    private void maybeStartAutonomousStorage() {
        int occupied = 0;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            if (!npc.inventory().getStackInSlot(slot).isEmpty()) occupied++;
        }
        if (!HomeStoragePolicy.shouldAutoStore(occupied, CodexNpcEntity.BACKPACK_SIZE)) return;
        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "organize-storage");
        spec.addProperty("radius", HomeStoragePolicy.DEFAULT_RADIUS);
        spec.addProperty("requestedBy", "npc-autonomy");
        Stance resume = npc.stance() == Stance.WORK ? Stance.FOLLOW : npc.stance();
        active = new ActiveWork("local:organize:" + npc.tickCount, spec, new JsonObject(), resume);
        npc.setStance(Stance.WORK);
        npc.setStatus("背包空间不足，正在返回家中整理");
    }

    private void maybeStartAutonomousFoodReserve() {
        String activeKind = active == null ? "" : active.kind;
        int reserve = safeProvisioningFoodCount();
        if (!NpcFoodReservePolicy.shouldProvision(
            npc.creativeResources(),
            config.npcFoodReserveCount,
            reserve,
            activeKind,
            assistTarget != null,
            nearestHostile(npc, 8.0D, "hostile") != null,
            suspendedForDowned || npc.isDowned()
        )) return;

        Stance resume = npc.stance() == Stance.WORK ? Stance.FOLLOW : npc.stance();
        int priority = 70;
        if (active != null) {
            ActiveWork interrupted = active;
            priority = Math.min(1000, interrupted.priority + 1);
            progress(interrupted, activeProgress(interrupted),
                "常备口粮不足（" + reserve + "/" + foodReserveTarget() + "），先补足口粮再继续原任务");
            pauseActive("补充 NPC 常备安全口粮");
        }

        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "provision-food");
        spec.addProperty("source", "auto");
        spec.addProperty("destination", "backpack");
        spec.addProperty("count", foodReserveTarget());
        spec.addProperty("requestedBy", "npc-food-reserve");
        spec.addProperty("priority", priority);
        active = new ActiveWork(
            "local:auto-food:" + npc.getUUID() + ":" + npc.tickCount,
            spec,
            new JsonObject(),
            resume
        );
        npc.setStance(Stance.WORK);
        npc.setStatus("正在补充常备安全口粮 " + reserve + "/" + foodReserveTarget());
    }

    private int foodReserveTarget() {
        return Math.max(
            DeepMiningPolicy.REQUIRED_FOOD_RESERVE,
            NpcFoodReservePolicy.target(config.npcFoodReserveCount)
        );
    }

    /** Preserve work while the companion is incapacitated. */
    public void suspendForDowned() {
        suspendedForDowned = true;
        suspendActiveLifeInteraction();
        npc.cancelManagedEating();
        npc.getNavigation().stop();
        npc.setTarget(null);
        clearAssistTarget(false);
        if (active != null) {
            active.pauseReason = "NPC 倒地恢复中";
            if (!active.id.startsWith("local:")) {
                sendProgressUpdate(active, activeProgress(active), "任务因 NPC 倒地而暂停，恢复后继续", "paused");
            }
        }
    }

    public void resumeAfterRecovery() {
        suspendedForDowned = false;
        if (active == null) resumePausedWork();
        if (active != null) {
            active.pauseReason = "";
            npc.setStance(Stance.WORK);
            npc.setStatus("恢复后继续 " + active.kind + " 任务");
            if (!active.id.startsWith("local:")) {
                sendProgressUpdate(active, activeProgress(active), "NPC 已恢复，任务继续执行", "active");
            }
        }
    }

    /**
     * A downed deep miner may resume at the last confirmed two-block-high
     * stand instead of being recalled to the owner and losing the open tunnel.
     * Starvation recovery deliberately remains on the surface when no ration
     * exists; the task will provision food first and then restore the checkpoint.
     */
    public boolean recoverAtActiveTaskCheckpoint() {
        ActiveWork work = active;
        if (work == null || work.deepMiningPhase.isBlank()
            || work.deepMiningLastSafeStand == null) return false;
        if (!npc.creativeResources() && npc.foodLevel() <= HungerPolicy.AUTO_EAT_THRESHOLD
            && safeProvisioningFoodCount() <= 0) return false;
        BlockPos checkpoint = safeDeepMiningCheckpoint(work);
        if (checkpoint == null) return false;
        reconcileDeepMiningCheckpoint(work, checkpoint);
        restoreDeepMiningCheckpoint(work, checkpoint);
        return true;
    }

    public void onLivingEntityDefeated(UUID targetId) {
        if (targetId == null) return;
        if (active != null && targetId.equals(active.dragonCombatTargetId)) {
            active.dragonCombatTargetDefeated = true;
        }
        for (ActiveWork work : pausedWorks) {
            if (targetId.equals(work.dragonCombatTargetId)) work.dragonCombatTargetDefeated = true;
        }
    }

    public void onOwnerOffline() {
        suspendActiveLifeInteraction();
        npc.cancelManagedEating();
        npc.getNavigation().stop();
        npc.setTarget(null);
        clearAssistTarget(false);
        if (active != null) {
            active.pauseReason = "等待主人上线";
            if (!active.id.startsWith("local:")) {
                sendProgressUpdate(active, activeProgress(active), "主人已离线，任务将在上线后继续", "paused");
            }
        }
        npc.setStatus("等待主人上线");
    }

    public void onOwnerOnline() {
        if (active != null && !suspendedForDowned) {
            // Region tickets are intentionally not persisted by Minecraft.
            // Re-arm the active NPC's chunk as soon as its owner rejoins so a
            // far-away expedition continues ticking after a world restart.
            maintainTaskChunkTicket(npc.blockPosition());
            active.pauseReason = "";
            npc.setStance(Stance.WORK);
            npc.setStatus("主人上线，继续 " + active.kind + " 任务");
            if (!active.id.startsWith("local:")) {
                sendProgressUpdate(active, activeProgress(active), "主人已上线，任务继续执行", "active");
            }
            return;
        }
        // Logout status must never survive after the owner has rejoined.  Keep
        // an intentional stay/guard stance, but repair an orphaned WORK stance
        // and restore the matching live idle status immediately.
        Stance restored = npc.stance() == Stance.WORK ? Stance.FOLLOW : npc.stance();
        setStance(restored);
    }

    /** Recall transports the NPC without cancelling work or losing its stance. */
    public void onRecalled() {
        resetFollowTracking();
        if (active != null) {
            npc.setStance(Stance.WORK);
            npc.setStatus("召回后继续 " + active.kind + " 任务");
        } else {
            setStance(Stance.FOLLOW);
            npc.setStatus("已召回");
        }
    }

    public String savePersistentState() {
        return NpcTaskPersistence.encode(persistentState());
    }

    public byte[] savePersistentStateBytes() {
        return NpcTaskPersistence.encodeCompressed(persistentState());
    }

    private NpcTaskPersistence.SchedulerState persistentState() {
        List<NpcTaskPersistence.WorkState> paused = new ArrayList<>();
        for (ActiveWork work : pausedWorks) paused.add(toPersistentWork(work));
        return new NpcTaskPersistence.SchedulerState(
            NpcTaskPersistence.VERSION,
            suspendedForDowned ? "downed" : "ready",
            active == null ? null : toPersistentWork(active),
            paused,
            recoverableBuild == null ? null : toPersistentWork(recoverableBuild)
        );
    }

    public void loadPersistentState(String encoded) {
        loadPersistentState(NpcTaskPersistence.decode(encoded));
    }

    public void loadPersistentState(byte[] encoded) {
        loadPersistentState(NpcTaskPersistence.decodeCompressed(encoded));
    }

    private void loadPersistentState(NpcTaskPersistence.SchedulerState state) {
        active = null;
        pausedWorks.clear();
        recoverableBuild = null;
        suspendedForDowned = false;
        suspendedForDowned = "downed".equals(state.lifecycle()) || "recovering".equals(state.lifecycle());
        if (state.active() != null) active = fromPersistentWork(state.active());
        for (NpcTaskPersistence.WorkState work : state.paused()) pausedWorks.addLast(fromPersistentWork(work));
        if (state.recoverableBuild() != null) recoverableBuild = fromPersistentWork(state.recoverableBuild());
        if (active != null && !suspendedForDowned) {
            npc.setStance(Stance.WORK);
            npc.setStatus("已从存档恢复 " + active.kind + " 任务");
        }
    }

    public void tick() {
        if (attackCooldown > 0) attackCooldown--;
        if (active == null && pausedWorks.isEmpty() && assistTarget == null) {
            releaseForcedTaskChunk();
        }
        ServerPlayer owner = npc.owner();
        if (owner == null) {
            npc.getNavigation().stop();
            npc.setStatus("等待主人上线");
            return;
        }
        if (!NpcManager.isCanonical(owner, npc)) {
            npc.getNavigation().stop();
            npc.discard();
            return;
        }
        if (owner.level() != npc.level()) {
            if (owner.hasPermissions(2)) {
                npc.setStatus(active == null ? "正在跨维度跟随主人" : "正在跨维度转移，任务将继续");
                NpcManager.recall(owner, npc);
            } else {
                npc.setStatus("主人位于其他维度；未开启作弊，正在等待主人返回");
            }
            return;
        }
        restoreStatusAfterAutomaticEating();
        if (active != null || assistTarget != null || npc.stance() == Stance.FOLLOW || npc.stance() == Stance.GUARD) {
            maintainTaskChunkTicket(npc.blockPosition());
        }
        if (FollowMovementPolicy.shouldAutoRecall(
            active != null,
            npc.stance() == Stance.STAY,
            owner.hasPermissions(2),
            npc.distanceToSqr(owner),
            config.npcRecallDistance
        )) {
            Entity dragon = ridingDragon();
            DragonAdapter adapter = dragon == null ? null : DragonAdapters.forEntity(dragon);
            if (adapter != null && adapter.recall(dragon, owner, owner.hasPermissions(2))) {
                npc.setStatus("正在骑龙返回主人身边");
            } else {
                NpcManager.recall(owner, npc);
            }
            return;
        }
        if (npc.isManagedEating() && !isExplicitEating()) {
            npc.getNavigation().stop();
            npc.setStatus("正在自动进食，随后继续原任务");
            if (npc.tickCount % Math.max(2, config.snapshotIntervalTicks) == 0) {
                CodexNetwork.sendSnapshot(owner, npc);
            }
            return;
        }
        maybeStartAutonomousFoodReserve();
        if (active == null && assistTarget == null && npc.stance() != Stance.STAY && npc.tickCount % 200 == 0) {
            maybeStartAutonomousStorage();
        }

        ActiveWork work = active;
        if (tickCombatAssist(owner)) {
            // Owner-directed combat temporarily takes priority; queued work resumes afterwards.
        } else if (work != null) {
            work.ticks++;
            try {
                if (compactBackpackIfNeeded(work)) {
                    // Continue the original task on the next tick with the
                    // newly released slots; no item counts were changed.
                } else if (!work.kind.equals("build") && !work.buildMaterialGoals.isEmpty()) {
                    tickBuildMaterialPrerequisite(work);
                } else switch (work.kind) {
                    case "move" -> tickMove(work, target(work.spec.getAsJsonObject("target")));
                    case "explore" -> tickExplore(work);
                    case "gather" -> tickGather(work);
                    case "craft" -> tickCraft(work);
                    case "smelt" -> tickSmelt(work);
                    case "farm" -> tickFarm(work);
                    case "store" -> tickStore(work);
                    case "retrieve" -> tickRetrieve(work);
                    case "organize-storage" -> tickOrganizeStorage(work);
                    case "deliver" -> tickDeliver(work);
                    case "drop" -> tickDrop(work);
                    case "eat" -> tickEat(work);
                    case "provision-food" -> tickProvisionFood(work);
                    case "ranch" -> tickRanch(work);
                    case "fish" -> tickFish(work);
                    case "sleep" -> tickSleep(work);
                    case "combat" -> tickCombat(work);
                    case "build" -> tickBuild(work);
                    case "dragon" -> tickDragon(work);
                    case "macro" -> fail(work, "声明式技能应由控制服务展开", "MACRO_NOT_EXPANDED");
                    default -> fail(work, "不支持的 NPC 任务：" + work.kind, "UNSUPPORTED_TASK");
                }
            } catch (RuntimeException error) {
                fail(work, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), "TASK_EXCEPTION");
            }
        } else if (npc.stance() == Stance.FOLLOW) {
            if (!tickRidingFollow(owner)) tickFollow(owner);
        } else if (npc.stance() == Stance.GUARD) {
            LivingEntity hostile = nearestHostile(owner, guardRadius, "hostile");
            if (hostile != null) {
                if (!tickRidingAssist(owner, hostile)) attack(hostile);
            } else if (!tickRidingFollow(owner)) tickFollow(owner);
        }

        if (npc.tickCount % Math.max(2, config.snapshotIntervalTicks) == 0) CodexNetwork.sendSnapshot(owner, npc);
    }

    private boolean compactBackpackIfNeeded(ActiveWork work) {
        List<InventoryCompactionPolicy.Slot> slots = new ArrayList<>();
        int occupied = 0;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            occupied++;
            slots.add(new InventoryCompactionPolicy.Slot(
                slot,
                CraftingIngredientAllocator.stackKey(stack),
                stack.getCount(),
                stack.getMaxStackSize()
            ));
        }
        if (CodexNpcEntity.BACKPACK_SIZE - occupied > 1) return false;

        List<InventoryCompactionPolicy.Transfer> transfers = InventoryCompactionPolicy.plan(
            CodexNpcEntity.BACKPACK_SIZE,
            slots
        );
        if (transfers.isEmpty()) return false;

        int beforeOccupied = occupied;
        for (InventoryCompactionPolicy.Transfer transfer : transfers) {
            ItemStack source = npc.inventory().getStackInSlot(transfer.fromSlot());
            ItemStack target = npc.inventory().getStackInSlot(transfer.toSlot());
            if (source.isEmpty() || target.isEmpty()
                || !ItemStack.isSameItemSameTags(source, target)) continue;
            int moved = Math.min(
                transfer.count(),
                Math.min(source.getCount(), target.getMaxStackSize() - target.getCount())
            );
            if (moved <= 0) continue;
            target.grow(moved);
            source.shrink(moved);
            npc.inventory().setStackInSlot(transfer.toSlot(), target);
            npc.inventory().setStackInSlot(transfer.fromSlot(), source);
        }

        int afterOccupied = 0;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            if (!npc.inventory().getStackInSlot(slot).isEmpty()) afterOccupied++;
        }
        int released = Math.max(0, beforeOccupied - afterOccupied);
        if (released <= 0) return false;
        progress(work, activeProgress(work), "已合并同类物品并释放 " + released + " 个背包格，继续原任务");
        return true;
    }

    private boolean tickCombatAssist(ServerPlayer owner) {
        LivingEntity target = assistTarget;
        if (target == null) return false;
        double maxDistance = Math.max(8, config.observeRadius);
        if (target.level() != npc.level()
            || !isValidAssistTarget(owner, target)
            || owner.distanceToSqr(target) > maxDistance * maxDistance
                && npc.distanceToSqr(target) > maxDistance * maxDistance) {
            clearAssistTarget();
            return false;
        }
        double distance = npc.distanceTo(target);
        float health = target.getHealth();
        boolean madeProgress = CombatAssistPolicy.madeProgress(
            distance,
            assistTargetBestDistance,
            health,
            assistTargetLowestHealth
        );
        assistTargetExpiresAt = CombatAssistPolicy.updateDeadline(
            npc.tickCount,
            assistTargetExpiresAt,
            COMBAT_ASSIST_TIMEOUT_TICKS,
            false,
            madeProgress
        );
        if (madeProgress) {
            assistTargetBestDistance = Math.min(assistTargetBestDistance, distance);
            assistTargetLowestHealth = Math.min(assistTargetLowestHealth, health);
        }
        if (CombatAssistPolicy.leaseExpired(npc.tickCount, assistTargetExpiresAt)) {
            suppressedAssistTargetId = target.getUUID();
            suppressedAssistTargetUntil = npc.tickCount + COMBAT_ASSIST_RETRY_COOLDOWN_TICKS;
            clearAssistTarget();
            return false;
        }
        equipBestWeapon();
        npc.setTarget(target);
        npc.setStatus("正在协助主人攻击 " + target.getDisplayName().getString());
        if (!tickRidingAssist(owner, target)) attack(target);
        return true;
    }

    private void restoreStatusAfterAutomaticEating() {
        if (npc.isManagedEating() || !"正在自动进食，随后继续原任务".equals(npc.status())) return;
        npc.setStatus(active != null ? "正在工作" : switch (npc.stance()) {
            case FOLLOW -> "正在跟随";
            case STAY -> "原地等待";
            case GUARD -> "护卫待命";
            case WORK -> "空闲待命";
        });
    }

    private Entity ridingDragon() {
        Entity vehicle = npc.getVehicle();
        return vehicle != null && isDragon(vehicle) ? vehicle : null;
    }

    private boolean tickRidingFollow(ServerPlayer owner) {
        Entity dragon = ridingDragon();
        DragonAdapter adapter = dragon == null ? null : DragonAdapters.forEntity(dragon);
        if (adapter == null) return false;
        rememberDragon(dragon);
        if (owner.getVehicle() == dragon) {
            npc.getNavigation().stop();
            npc.fallDistance = 0.0F;
            owner.fallDistance = 0.0F;
            dragon.fallDistance = 0.0F;
            npc.setStatus("正在与主人同骑（主人主驾）");
            return true;
        }
        npc.setStatus("正在骑龙跟随主人");
        return adapter.tickMountedFollow(dragon, owner);
    }

    private boolean tickRidingAssist(ServerPlayer owner, LivingEntity target) {
        Entity dragon = ridingDragon();
        DragonAdapter adapter = dragon == null ? null : DragonAdapters.forEntity(dragon);
        if (adapter != null) rememberDragon(dragon);
        return adapter != null && adapter.assistCombat(dragon, target, owner);
    }

    private boolean isValidAssistTarget(ServerPlayer owner, LivingEntity target) {
        boolean playerUnavailable = target instanceof Player player && player.isSpectator();
        return CombatAssistPolicy.shouldAssist(
            target.isAlive() && !playerUnavailable,
            target == owner,
            target instanceof CodexNpcEntity,
            owner.isAlliedTo(target) || target.isAlliedTo(owner),
            npc.isAlliedTo(target) || target.isAlliedTo(npc),
            target instanceof Player,
            config.allowPvp
        );
    }

    private void clearAssistTarget() {
        clearAssistTarget(true);
    }

    private void clearAssistTarget(boolean resumeWork) {
        boolean wasAssisting = assistTarget != null;
        if (assistTarget != null) {
            if (npc.getTarget() == assistTarget) npc.setTarget(null);
            npc.getNavigation().stop();
        }
        assistTarget = null;
        assistTargetExpiresAt = 0;
        assistTargetBestDistance = Double.POSITIVE_INFINITY;
        assistTargetLowestHealth = Float.POSITIVE_INFINITY;
        lowerShield();
        if (wasAssisting) {
            if (active != null) {
                active.pauseReason = "";
                if (resumeWork && !active.id.startsWith("local:")) {
                    sendProgressUpdate(active, activeProgress(active), "护主战斗结束，已恢复原任务", "active");
                }
            }
            npc.setStatus(active != null ? "正在工作" : switch (npc.stance()) {
                case FOLLOW -> "正在跟随";
                case STAY -> "原地等待";
                case GUARD -> "护卫待命";
                case WORK -> "正在工作";
            });
        }
    }

    private void cancelAllWork(String reason, String code) {
        suspendActiveLifeInteraction();
        npc.cancelManagedEating();
        npc.getNavigation().stop();
        npc.setTarget(null);
        ActiveWork current = active;
        active = null;
        if (current != null && !current.id.startsWith("local:")) {
            FurnaceRecoverySummary recovery = recoverAllTaskFurnaces(current, "cancel-all-work");
            CodexNetwork.sendResult(npc, current.id, false, reason + recovery.detail(), code);
        } else if (current != null) {
            recoverAllTaskFurnaces(current, "cancel-all-work");
        }
        cancelPausedWorks(reason, code);
    }

    private void cancelPausedWorks(String reason, String code) {
        for (ActiveWork work : pausedWorks) {
            FurnaceRecoverySummary recovery = recoverAllTaskFurnaces(work, "cancel-paused-work");
            if (!work.id.startsWith("local:")) {
                CodexNetwork.sendResult(npc, work.id, false, reason + recovery.detail(), code);
            }
        }
        pausedWorks.clear();
    }

    private void suspendActiveLifeInteraction() {
        if (active != null) suspendLifeInteraction(active);
    }

    private void suspendLifeInteraction(ActiveWork work) {
        releaseDragonAutopilot(work);
        if ("fish".equals(work.kind) && work.fishingCast) {
            proxy.cancelFishing();
            work.fishingCast = false;
            work.fishingReadyTick = 0;
        }
        if ("sleep".equals(work.kind) && npc.isSleeping()) npc.stopSleeping();
        if ("eat".equals(work.kind)) {
            npc.cancelManagedEating();
            work.sourceSlot = -1;
        }
        if ("ranch".equals(work.kind) && work.ranchAnimalTargetId != null
            && npc.level() instanceof ServerLevel level) {
            Entity entity = level.getEntity(work.ranchAnimalTargetId);
            if (entity instanceof Mob mob && mob.isLeashed()
                && (mob.getLeashHolder() == npc || isTemporaryRanchKnot(work, mob.getLeashHolder()))) {
                mob.dropLeash(true, true);
                discardTemporaryRanchKnot(work);
                npc.absorbNearbyItemsAt(mob.position(), 6.0D);
                work.ranchPhase = 1;
                work.ranchAnimalTargetId = null;
                work.ranchExitStaged = false;
            }
            discardTemporaryRanchKnot(work);
        }
        if ("combat".equals(work.kind)) lowerShield();
    }

    private void releaseDragonAutopilot(ActiveWork work) {
        if (!"dragon".equals(work.kind)) return;
        ServerPlayer owner = npc.owner();
        if (owner == null) return;
        Entity rootVehicle = owner.getRootVehicle();
        Entity dragon = rootVehicle != owner && isDragon(rootVehicle) ? rootVehicle : npc.getVehicle();
        if (dragon == null && work.dragonTargetId != null) {
            dragon = findDragonByUuid(work.dragonTargetId, false);
        }
        if (dragon != null) {
            DragonAdapter adapter = DragonAdapters.forEntity(dragon);
            String action = string(work.spec, "action", "");
            if (adapter != null && DragonActionPolicy.shouldHaltTravel(action)) {
                if ("fly-to".equals(action)) adapter.haltAirborneTravel(dragon, owner);
                else adapter.haltTravel(dragon, owner);
            }
            DragonAutopilotControl.sync(dragon, owner);
        }
        // A task owns at most one player control lease. Ending with a live
        // vehicle starts the stale-packet drain handshake; a missing target
        // removes the owner lease immediately.
        DragonAutopilotControl.end(dragon, owner);
    }

    private void tickFollow(ServerPlayer owner) {
        if (tickBoatFollow(owner)) return;
        double verticalGap = npc.getY() - owner.getY();
        FollowMovementPolicy.Mode nextMode = FollowMovementPolicy.nextMode(
            followMode,
            owner.isCreative(),
            owner.getAbilities().flying,
            owner.onGround(),
            verticalGap
        );
        if (nextMode != FollowMovementPolicy.Mode.GROUND) {
            tickAerialFollow(owner, nextMode);
            return;
        }
        stopAerialFollow();

        double distance = npc.distanceTo(owner);
        if (distance > FOLLOW_STOP_DISTANCE) {
            double horizontalDistance = Math.sqrt(Math.max(0.0D, npc.distanceToSqr(owner)
                - Math.pow(npc.getY() - owner.getY(), 2)));
            if (FollowMovementPolicy.shouldUseWalkingDescent(
                owner.hasPermissions(2),
                followStalledTicks,
                npc.getY() - owner.getY(),
                horizontalDistance
            )) {
                tickWalkingDescent(owner);
                return;
            }
            if (FollowMovementPolicy.madeMeaningfulDistanceProgress(bestFollowDistance, distance)) {
                bestFollowDistance = distance;
                followStalledTicks = 0;
            } else {
                followStalledTicks++;
            }

            if (followRepathCooldown-- <= 0) {
                followRepathCooldown = FOLLOW_REPATH_TICKS;
                if (navigateTowardPlayer(owner, 1.15)) followPathFailures = 0;
                else followPathFailures++;
            }

            if (followPathFailures >= FOLLOW_MAX_PATH_FAILURES
                || (distance > FOLLOW_RECOVERY_DISTANCE && followStalledTicks >= FOLLOW_MAX_STALLED_TICKS)) {
                if (owner.hasPermissions(2)) {
                    NpcManager.recall(owner, npc);
                } else {
                    npc.getNavigation().stop();
                    npc.setStatus("未开启作弊，正在重新规划步行路线");
                }
                resetFollowTracking();
                return;
            }
            npc.setStatus(npc.stance() == Stance.GUARD ? "护卫移动中" : "正在跟随 " + owner.getGameProfile().getName());
            npc.addExhaustion(0.002F);
        } else {
            npc.getNavigation().stop();
            resetFollowTracking();
            npc.getLookControl().setLookAt(owner, 30.0F, 30.0F);
            npc.setStatus(npc.stance() == Stance.GUARD ? "护卫待命" : "跟随待命");
        }
    }

    private boolean tickBoatFollow(ServerPlayer owner) {
        Entity ownerVehicle = owner.getVehicle();
        if (!(ownerVehicle instanceof Boat boat)) {
            if (npc.getVehicle() instanceof Boat) npc.stopRiding();
            return false;
        }
        if (npc.getVehicle() == boat) {
            npc.getNavigation().stop();
            npc.setStatus("正在与 " + owner.getGameProfile().getName() + " 同船前行");
            return true;
        }
        if (npc.isPassenger() || boat.getPassengers().size() >= 2 || npc.distanceToSqr(boat) > 4.5 * 4.5) return false;
        if (!npc.startRiding(boat, true)) return false;
        npc.getNavigation().stop();
        npc.setStatus("已坐进主人的船");
        return true;
    }

    private boolean navigateTowardPlayer(ServerPlayer owner, double speed) {
        double distance = npc.distanceTo(owner);
        if (distance <= 32.0 || !(npc.level() instanceof ServerLevel level)) {
            return npc.getNavigation().moveTo(owner, speed);
        }
        Vec3 direction = owner.position().subtract(npc.position());
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontal < 0.01) return npc.getNavigation().moveTo(owner, speed);
        double step = Math.min(24.0, horizontal);
        int x = (int) Math.floor(npc.getX() + direction.x / horizontal * step);
        int z = (int) Math.floor(npc.getZ() + direction.z / horizontal * step);
        ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
        level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
        level.getChunk(chunk.x, chunk.z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return npc.getNavigation().moveTo(x + 0.5, y, z + 0.5, speed);
    }

    private void tickAerialFollow(ServerPlayer owner, FollowMovementPolicy.Mode mode) {
        boolean modeChanged = followMode != mode;
        followMode = mode;
        boolean ownerFlying = mode == FollowMovementPolicy.Mode.AERIAL;
        if (modeChanged) resetFollowTracking();
        npc.getNavigation().stop();
        npc.setNoGravity(true);
        npc.fallDistance = 0;

        double ownerDistance = npc.distanceTo(owner);
        if (ownerDistance > FOLLOW_RECOVERY_DISTANCE) {
            if (FollowMovementPolicy.madeMeaningfulDistanceProgress(bestFollowDistance, ownerDistance)) {
                bestFollowDistance = ownerDistance;
                followStalledTicks = 0;
            } else {
                followStalledTicks++;
            }
            if (FollowMovementPolicy.shouldRecoverAerialFollow(
                owner.hasPermissions(2),
                ownerDistance,
                followStalledTicks,
                FOLLOW_RECOVERY_DISTANCE,
                FOLLOW_MAX_STALLED_TICKS
            )) {
                npc.setStatus("飞行路线受阻，正在召回");
                NpcManager.recall(owner, npc);
                return;
            }
        } else {
            resetFollowTracking();
        }

        Vec3 horizontalLook = owner.getLookAngle().multiply(1, 0, 1);
        if (horizontalLook.lengthSqr() > 1.0E-4) horizontalLook = horizontalLook.normalize();
        Vec3 target = owner.position()
            .subtract(horizontalLook.scale(2.0))
            .add(0, ownerFlying ? -0.8 : 0.1, 0);
        Vec3 offset = target.subtract(npc.position());
        double distance = offset.length();
        Vec3 ownerMotion = ownerFlying ? owner.getDeltaMovement() : Vec3.ZERO;
        Vec3 pursuit = distance < 0.05
            ? Vec3.ZERO
            : offset.scale(Math.min(0.75, 0.08 + distance * 0.065) / distance);
        Vec3 desired = clampAerialVelocity(pursuit.add(ownerMotion.scale(0.65)));
        Vec3 velocity = clampAerialVelocity(npc.getDeltaMovement().scale(0.35).add(desired.scale(0.65)));
        if (distance < 0.3 && ownerMotion.lengthSqr() < 0.0025) velocity = Vec3.ZERO;

        npc.setDeltaMovement(velocity);
        npc.hasImpulse = true;
        npc.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        npc.setStatus(ownerFlying ? "飞行跟随 " + owner.getGameProfile().getName() : "正在降落");
    }

    private Vec3 clampAerialVelocity(Vec3 velocity) {
        double horizontalLength = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double horizontalScale = horizontalLength > 0.75 ? 0.75 / horizontalLength : 1.0;
        return new Vec3(
            velocity.x * horizontalScale,
            Math.max(-0.45, Math.min(0.45, velocity.y)),
            velocity.z * horizontalScale
        );
    }

    private void stopAerialFollow() {
        // noGravity is persisted by the entity, while followMode is not. Always
        // clear it so a save/reload or interrupted flight cannot strand the NPC.
        npc.setNoGravity(false);
        if (followMode == FollowMovementPolicy.Mode.GROUND) return;
        followMode = FollowMovementPolicy.Mode.GROUND;
        Vec3 velocity = npc.getDeltaMovement();
        npc.setDeltaMovement(velocity.x * 0.5, Math.min(0, velocity.y), velocity.z * 0.5);
    }

    void resetFollowMovementForFixture() {
        stopAerialFollow();
        resetFollowTracking();
    }

    int followStalledTicksForFixture() {
        return followStalledTicks;
    }

    int followModeForFixture() {
        return followMode.ordinal();
    }

    int recallDistanceForFixture() {
        return config.npcRecallDistance;
    }

    boolean hasActiveWorkForFixture() {
        return active != null;
    }

    private void resetFollowTracking() {
        followRepathCooldown = 0;
        followPathFailures = 0;
        followStalledTicks = 0;
        bestFollowDistance = Double.POSITIVE_INFINITY;
    }

    private void tickMove(ActiveWork work, Vec3 destination) {
        double distance = npc.position().distanceTo(destination);
        if (work.startDistance < 0) work.startDistance = Math.max(1, distance);
        if (distance <= 1.6) {
            complete(work, "已到达目标位置");
            return;
        }
        npc.getNavigation().moveTo(destination.x, destination.y, destination.z, 1.1);
        npc.addExhaustion(0.003F);
        trackNavigation(work, distance);
        if (work.ticks % 10 == 0) progress(work, 1 - Math.min(1, distance / work.startDistance), "移动中，距离 " + Math.round(distance) + " 格");
        if (work.ticks > 20 * 60 * 5) fail(work, "移动超时", "NAVIGATION_TIMEOUT");
    }

    private void tickExplore(ActiveWork work) {
        if (work.destination == null) {
            double radius = number(work.spec, "radius", 64);
            String direction = string(work.spec, "direction", "any");
            double dx = direction.equals("west") ? -radius : direction.equals("east") || direction.equals("any") ? radius : 0;
            double dz = direction.equals("north") ? -radius : direction.equals("south") ? radius : direction.equals("any") ? radius * 0.6 : 0;
            work.destination = npc.position().add(dx, 0, dz);
        }
        tickMove(work, work.destination);
    }

    private void tickGather(ActiveWork work) {
        String itemId = string(work.spec, "itemId", "");
        ResourceSelector selector = ResourceSelector.parse(itemId);
        int requested = integer(work.spec, "count", 1);
        if (!work.initialized) {
            work.initialized = true;
            work.initialCount = inventoryCount(itemId);
            work.requestedCount = requested;
        }
        if (hasCraftGatherPrerequisite(work) && tickCraftGatherPrerequisite(work)) return;
        npc.absorbNearbyItems(2.5);
        int gathered = GatherProgressPolicy.retained(
            work.completed,
            work.initialCount,
            inventoryCount(itemId)
        );
        work.completed = gathered;
        if (gathered >= requested) {
            clearDeepMining(work);
            work.targetBlock = null;
            work.destination = null;
            work.gatherTargets.clear();
            complete(work, "已采集 " + gathered + " 个 " + itemId);
            return;
        }
        if (deepMiningActiveFor(work, itemId)) {
            tickDeepMining(work, itemId, selector, requested, false);
            return;
        }
        if (!npc.creativeResources() && prepareKnownGatherTool(work, itemId)) return;
        if (work.destination != null) {
            BlockPos searchArea = BlockPos.containing(work.destination);
            maintainTaskChunkTicket(searchArea);
            if (npc.position().distanceTo(work.destination) <= 3.5) {
                npc.getNavigation().stop();
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
            } else {
                if (!approachGatherDestination(
                    work,
                    searchArea,
                    3.5,
                    1.15,
                    "远程搜索区不可达，无法继续寻找 " + itemId,
                    "RESOURCE_NOT_REACHABLE"
                )) {
                    if (active == work && work.ticks % 40 == 0) {
                        progress(work, Math.min(0.95, gathered / (double) requested),
                            "正在前往第 " + work.gatherExcursions + " 个远程搜索区");
                    }
                    return;
                }
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
            }
        }
        if (!isCurrentGatherTargetValid(work, selector)) {
            work.gatherAccessTarget = false;
            work.targetBlock = pollGatherTarget(work, selector);
            if (work.targetBlock == null && work.ticks - work.lastSearchTick >= 10) {
                work.lastSearchTick = work.ticks;
                BlockPos seed = DeepMiningPolicy.supports(itemId)
                    ? findExposedGatherBlock(selector, work.gatherSearchRadius, 24, work.skippedGatherTargets)
                    : findGatherBlock(selector, work.gatherSearchRadius, 24, work.skippedGatherTargets);
                if (seed != null) {
                    enqueueConnectedResources(work, seed, selector);
                    work.targetBlock = pollGatherTarget(work, selector);
                } else if (work.gatherSearchRadius < GATHER_LOCAL_SEARCH_RADIUS) {
                    work.gatherSearchRadius += 16;
                }
            }
            if (work.targetBlock == null && work.gatherSearchRadius >= GATHER_LOCAL_SEARCH_RADIUS) {
                if (DeepMiningPolicy.supports(itemId)) {
                    startDeepMining(work, itemId);
                    tickDeepMining(work, itemId, selector, requested, false);
                    return;
                }
                work.targetBlock = findMiningAccessBlock(work, selector);
                if (work.targetBlock != null) {
                    work.gatherAccessTarget = !matchesGatherBlock(work.targetBlock, selector, bestToolStack());
                    npc.setStatus(work.gatherAccessTarget
                        ? "找不到裸露目标，正在开安全矿道"
                        : "找到可安全采集的目标资源");
                }
            }
            if (work.targetBlock == null) {
                work.noWorkTicks++;
                if (GatherRetryPolicy.shouldStartRemoteExcursion(
                    work.gatherTargets.isEmpty(),
                    work.targetBlock != null,
                    work.noWorkTicks,
                    work.gatherSearchRadius,
                    GATHER_LOCAL_SEARCH_RADIUS
                )) {
                    if (!gatherAllowsRemoteRecovery(work)) {
                        fail(work, "走路采集模式下附近没有可走路抵达的 " + itemId, "LOCAL_RESOURCE_NOT_REACHABLE");
                        return;
                    }
                    if (work.gatherExcursions >= GATHER_MAX_EXCURSIONS) {
                        boolean skippedTargets = !work.skippedGatherTargets.isEmpty();
                        fail(
                            work,
                            skippedTargets ? "远程搜索后其余 " + itemId + " 仍不可达" : "远程搜索后仍没有找到可采集的 " + itemId,
                            skippedTargets ? "RESOURCE_NOT_REACHABLE" : "RESOURCE_NOT_FOUND"
                        );
                        return;
                    }
                    work.gatherExcursions++;
                    work.destination = nextGatherSearchDestination(work.gatherExcursions);
                    work.gatherSearchRadius = 16;
                    work.noWorkTicks = 0;
                    work.lastSearchTick = -10;
                    progress(work, Math.min(0.95, gathered / (double) requested),
                        "附近资源不足，正在前往第 " + work.gatherExcursions + " 个远程搜索区");
                }
                return;
            }
            work.stalledTicks = 0;
            work.gatherPathFailures = 0;
            work.lastDistance = -1;
            work.lastGatherPathAttemptTick = -1;
            work.gatherStandPathCursor = 0;
            work.noWorkTicks = 0;
        }
        if (!approachGatherTarget(work, work.targetBlock, 2.8, 1.15)) return;
        if (work.gatherTreeCluster) work.gatherClusterReached = true;
        BlockPos brokenTarget = work.targetBlock.immutable();
        BlockState targetState = npc.level().getBlockState(work.targetBlock);
        if (!npc.creativeResources()
            && targetState.requiresCorrectToolForDrops()
            && !hasUsableToolFor(targetState)
            && prepareGatherTool(work, targetState, itemId)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        boolean accessTarget = work.gatherAccessTarget;
        int toolSlot = bestGatherToolSlot(targetState, selector);
        if (toolSlot >= 0) equipMainHand(toolSlot);
        ItemStack toolStack = toolSlot >= 0
            ? npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT)
            : ItemStack.EMPTY;
        ItemStack creativeDrop = npc.creativeResources() && !accessTarget
            ? creativeGatherStack(work.targetBlock, targetState, toolStack, selector)
            : ItemStack.EMPTY;
        int inventoryBeforeBreak = inventoryCount(itemId);
        npc.swing(InteractionHand.MAIN_HAND);
        boolean broken = proxy.breakBlock(work.targetBlock, toolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1);
        if (!broken) {
            if (++work.failedActions >= 3) fail(work, "无法破坏目标方块", "BLOCK_BREAK_DENIED");
            return;
        }
        if (npc.creativeResources() && !creativeDrop.isEmpty()) npc.insert(creativeDrop);
        else npc.absorbNearbyItemsAt(Vec3.atCenterOf(brokenTarget), 3.0);
        recordInventoryAction(work, "gather-pickup");
        refreshConnectedResourcesAfterBreak(work, brokenTarget, selector, targetState.is(BlockTags.LOGS));
        npc.addExhaustion(0.08F);
        work.targetBlock = null;
        work.gatherAccessTarget = false;
        int acquiredByTask = GatherProgressPolicy.afterBreak(
            work.completed,
            inventoryBeforeBreak,
            inventoryCount(itemId)
        );
        work.completed = GatherProgressPolicy.retained(
            acquiredByTask,
            work.initialCount,
            inventoryCount(itemId)
        );
        gathered = work.completed;
        progress(
            work,
            Math.min(0.99, gathered / (double) requested),
            accessTarget ? "正在开安全矿道，继续寻找 " + itemId : "已采集 " + gathered + "/" + requested
        );
        if (work.ticks > 20 * 60 * 30) fail(work, "远程采集超时", "GATHER_TIMEOUT");
    }

    private void tickCraft(ActiveWork work) {
        String requestedItemId = string(work.spec, "itemId", "");
        String itemId = work.outputItemId != null ? work.outputItemId : resolveCraftItemId(requestedItemId);
        int requested = integer(work.spec, "count", 1);
        if (work.bedPlacementPending) {
            tickBedPlacement(work);
            return;
        }
        if (work.craftDeliveryPending) {
            if (work.outputItemId == null) work.outputItemId = itemId;
            tickCraftDelivery(work);
            return;
        }
        if (!work.initialized || work.recipe == null) {
            work.recipe = isCraftAndPlaceBed(work, itemId)
                ? findBasicBedCraftRecipe(itemId)
                : findCraftRecipe(itemId);
            if (work.recipe == null) {
                fail(work, "没有找到可制作 " + itemId + " 的配方", "RECIPE_NOT_FOUND");
                return;
            }
            work.initialized = true;
            work.outputItemId = itemId;
            work.requestedCount = requested;
            work.requiresTable = !work.recipe.canCraftInDimensions(2, 2);
        }
        if (!npc.creativeResources() && tickMiningInventoryCleanup(work, work.recipe, itemId)) return;
        if (isCraftAndPlaceBed(work, itemId) && tickBedPrerequisites(work)) return;
        if (hasCraftGatherPrerequisite(work) && tickCraftGatherPrerequisite(work)) return;
        if (itemId.equals("minecraft:fishing_rod") && tickDirectFishingRodPrerequisites(work)) return;
        ItemStack output = work.recipe.getResultItem(npc.level().registryAccess()).copy();
        if (output.isEmpty()) {
            fail(work, "配方没有产物", "EMPTY_RECIPE_RESULT");
            return;
        }
        List<Integer> ingredients = npc.creativeResources() ? List.of() : allocateIngredients(work.recipe.getIngredients());
        if (!npc.creativeResources() && ingredients == null) {
            if (craftMissingIngredient(work, work.recipe, 0, new HashSet<>())) return;
            if (prepareCraftPrerequisite(work, itemId, work.recipe)) return;
            if (prepareRecipeMaterialAcquisition(work, itemId, work.recipe)) return;
            fail(work, "NPC 背包中的材料不足", "MISSING_INGREDIENTS");
            return;
        }
        // Resolve the table only after all ingredients are ready. Gathering may
        // move the NPC far away from a table selected at task start.
        if (!prepareCraftingWorkstation(work, itemId, work.requiresTable)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        if (!CraftingIngredientAllocator.canInsertAfterConsumption(
            ingredients,
            CodexNpcEntity.BACKPACK_SIZE,
            slot -> npc.inventory().getStackInSlot(slot),
            output
        )) {
            fail(work, "NPC 背包没有空间接收制作结果", "INVENTORY_FULL");
            return;
        }
        if (!npc.creativeResources()) consumeIngredients(ingredients);
        npc.insert(output);
        recordInventoryAction(work, "craft-output");
        npc.swing(InteractionHand.MAIN_HAND);
        work.completed += output.getCount();
        npc.addExhaustion(0.03F);
        if (work.completed >= requested) {
            if (isCraftAndPlaceBed(work, itemId)) {
                work.bedPlacementPending = true;
                work.workstation = null;
                work.skippedWorkstationTargets.clear();
                work.stalledTicks = 0;
                work.lastDistance = -1;
                progress(work, 0.99, "床已制作完成，正在返回家附近寻找安全位置");
                return;
            }
            String deliverTo = string(work.spec, "deliverTo", "");
            if (!deliverTo.isBlank()) {
                work.craftDeliveryPending = true;
                progress(work, 0.99, "已制作 " + work.completed + " 个 " + work.outputItemId + "，正在交付给 " + deliverTo);
                return;
            }
            complete(work, "已制作 " + work.completed + " 个 " + work.outputItemId);
            return;
        }
        progress(work, Math.min(0.99, work.completed / (double) requested), "已制作 " + work.completed + "/" + requested);
        if (work.ticks > CRAFT_PREREQUISITE_TIMEOUT_TICKS) fail(work, "制作超时", "CRAFT_TIMEOUT");
    }

    private void tickCraftDelivery(ActiveWork work) {
        String itemId = work.outputItemId == null ? string(work.spec, "itemId", "") : work.outputItemId;
        String playerName = string(work.spec, "deliverTo", "");
        int requested = Math.max(1, integer(work.spec, "count", work.completed));
        requested = Math.min(requested, Math.max(1, work.completed));
        if (playerName.isBlank()) {
            complete(work, "已制作 " + work.completed + " 个 " + itemId);
            return;
        }
        ServerPlayer recipient = findRecipientPlayer(playerName);
        if (recipient == null || !recipient.isAlive()) {
            if (++work.noWorkTicks >= 100) fail(work, "已制作完成，但没有找到在线玩家 " + playerName, "PLAYER_NOT_FOUND");
            return;
        }
        if (recipient.level() != npc.level()) {
            fail(work, "已制作完成，但玩家 " + playerName + " 位于其他维度", "PLAYER_NOT_REACHABLE");
            return;
        }
        work.noWorkTicks = 0;
        if (!approach(work, recipient, 3.2, 1.15)) return;
        if (work.ticks - work.lastActionTick < 4) return;
        work.lastActionTick = work.ticks;
        int moved = throwItems(itemId, requested - work.craftDelivered, recipient);
        work.craftDelivered += moved;
        if (work.craftDelivered >= requested) {
            complete(work, "已制作并把 " + work.craftDelivered + " 个 " + itemId + " 丢给 " + recipient.getGameProfile().getName());
            return;
        }
        if (moved <= 0) {
            fail(work, "制作完成但无法从 NPC 背包取出 " + itemId, "DELIVERY_FAILED");
            return;
        }
        progress(work, 0.99, "已交付 " + work.craftDelivered + "/" + requested + " 个 " + itemId);
    }

    private boolean isCraftAndPlaceBed(ActiveWork work, String itemId) {
        return itemId.endsWith("_bed")
            && work.spec.has("placeAtHome")
            && work.spec.get("placeAtHome").getAsBoolean();
    }

    private boolean tickBedPrerequisites(ActiveWork work) {
        if (npc.creativeResources()) return false;
        if (hasCraftGatherPrerequisite(work)) return tickCraftGatherPrerequisite(work);
        if (work.bedStoragePhase < 5) {
            tickBedHomeMaterialLookup(work);
            return true;
        }
        if (inventoryCount("minecraft:white_wool") >= 3) {
            clearBedSheepSearch(work);
            return false;
        }

        int shearsSlot = findUsableShearsSlot();
        if (shearsSlot < 0) {
            if (inventoryCount("minecraft:iron_ingot") < 2) {
                tickBedIronSupply(work);
                return true;
            }
            if (!craftOnePrerequisite(work, "minecraft:shears", "已用铁锭制作剪刀")) {
                fail(work, "有铁锭但无法按当前配方制作剪刀", "BED_SHEARS_RECIPE_MISSING");
            }
            return true;
        }
        tickBedSheepWool(work, shearsSlot);
        return true;
    }

    private void tickBedHomeMaterialLookup(ActiveWork work) {
        if (inventoryCount("minecraft:white_wool") >= 3 && work.bedStoragePhase >= 2) {
            finishBedHomeMaterialLookup(work);
            return;
        }
        if (findUsableShearsSlot() >= 0 && work.bedStoragePhase >= 2) {
            finishBedHomeMaterialLookup(work);
            return;
        }
        String selector;
        int required;
        String label;
        switch (work.bedStoragePhase) {
            case 0 -> {
                selector = "minecraft:white_wool";
                required = 3;
                label = "白色羊毛";
            }
            case 1 -> {
                selector = "#minecraft:planks";
                required = 3;
                label = "木板";
            }
            case 2 -> {
                selector = "minecraft:shears";
                required = 1;
                label = "剪刀";
            }
            case 3 -> {
                if (inventoryCount("minecraft:iron_ingot") >= 2) {
                    finishBedHomeMaterialLookup(work);
                    return;
                }
                selector = "minecraft:iron_ingot";
                required = 2;
                label = "铁锭";
            }
            case 4 -> {
                if (inventoryCount("minecraft:iron_ingot") >= 2) {
                    finishBedHomeMaterialLookup(work);
                    return;
                }
                selector = "minecraft:raw_iron";
                required = 2;
                label = "粗铁";
            }
            default -> {
                finishBedHomeMaterialLookup(work);
                return;
            }
        }

        int missing = Math.max(0, required - inventoryCount(selector));
        if (missing <= 0) {
            advanceBedStoragePhase(work);
            return;
        }
        if (work.workstation == null) {
            work.workstation = findHomeStorage(ResourceSelector.parse(selector), true, work, HomeStoragePolicy.DEFAULT_RADIUS);
            if (work.workstation == null) {
                advanceBedStoragePhase(work);
                return;
            }
            progress(work, activeProgress(work), "家中箱子找到" + label + "，正在前往取出");
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
            return;
        }
        int moved = withdraw(container, selector, missing);
        container.setChanged();
        work.skippedStorageTargets.add(work.workstation);
        work.workstation = null;
        if (moved > 0) {
            progress(work, activeProgress(work), "已从家中箱子取出 " + moved + " 个" + label);
        }
        if (inventoryCount(selector) >= required) advanceBedStoragePhase(work);
    }

    private void advanceBedStoragePhase(ActiveWork work) {
        work.bedStoragePhase++;
        work.workstation = null;
        work.skippedStorageTargets.clear();
        work.stalledTicks = 0;
        work.lastDistance = -1;
        if (work.bedStoragePhase >= 5) finishBedHomeMaterialLookup(work);
    }

    private void finishBedHomeMaterialLookup(ActiveWork work) {
        work.bedStoragePhase = 5;
        work.workstation = null;
        work.skippedStorageTargets.clear();
        work.stalledTicks = 0;
        work.lastDistance = -1;
        progress(work, activeProgress(work), "已检查背包和家中箱子，正在补齐床的缺少材料");
    }

    private void tickBedIronSupply(ActiveWork work) {
        int ironNeeded = Math.max(0, 2 - inventoryCount("minecraft:iron_ingot"));
        if (ironNeeded <= 0) return;
        int rawNeeded = Math.max(0, ironNeeded - work.bedSmeltLoaded - inventoryCount("minecraft:raw_iron"));
        if (rawNeeded > 0) {
            if (prepareSmeltingInputTool(work, "minecraft:raw_iron")) return;
            beginCraftGather(work, "minecraft:raw_iron", rawNeeded,
                "制作剪刀缺少铁料，先远程寻找并采集 " + rawNeeded + " 个粗铁");
            return;
        }

        Recipe<?> cooking = findCookingRecipe("minecraft:raw_iron");
        if (cooking == null) {
            fail(work, "没有找到粗铁烧炼配方", "BED_IRON_RECIPE_MISSING");
            return;
        }
        String workstationId = cookingWorkstation(cooking.getType());
        if (work.workstation != null) {
            SmeltingWorkstationPolicy.Validation validation = validateSmeltingWorkstation(
                work, workstationId, "minecraft:raw_iron", "minecraft:iron_ingot"
            );
            if (validation != SmeltingWorkstationPolicy.Validation.USABLE) {
                releaseBedSmeltingWorkstation(work);
                return;
            }
        }
        if (work.workstation == null) {
            work.workstation = findClaimableSmeltingWorkstation(work, workstationId);
            if (work.workstation != null) {
                int outputPerInput = Math.max(1, cooking.getResultItem(npc.level().registryAccess()).getCount());
                work.smeltingWorkstationClaimed = claimTaskFurnace(
                    work,
                    work.workstation,
                    "minecraft:raw_iron",
                    "minecraft:iron_ingot",
                    outputPerInput
                );
                if (!work.smeltingWorkstationClaimed) {
                    work.skippedWorkstationTargets.add(work.workstation.immutable());
                    work.workstation = null;
                }
            }
        }
        if (work.workstation == null) {
            if (prepareSmeltingWorkstation(work, workstationId)) return;
            work.workstation = ensureWorkstation(work, workstationId);
            if (work.workstation == null) return;
            int outputPerInput = Math.max(1, cooking.getResultItem(npc.level().registryAccess()).getCount());
            work.smeltingWorkstationClaimed = claimTaskFurnace(
                work,
                work.workstation,
                "minecraft:raw_iron",
                "minecraft:iron_ingot",
                outputPerInput
            );
            if (!work.smeltingWorkstationClaimed) {
                work.skippedWorkstationTargets.add(work.workstation.immutable());
                work.workstation = null;
                return;
            }
        }
        if (!approach(work, work.workstation, 3.5, 1.05)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            releaseBedSmeltingWorkstation(work);
            return;
        }

        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty()) {
            if (!itemId(output).equals("minecraft:iron_ingot") || !canInsert(output)) {
                releaseBedSmeltingWorkstation(work);
                return;
            }
            int moved = output.getCount();
            npc.insert(output.copy());
            recordInventoryAction(work, "furnace-output");
            recordTaskFurnaceOutputWithdrawal(work, work.workstation, output);
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
            work.bedSmeltLoaded = Math.max(0, work.bedSmeltLoaded - moved);
            work.lastProgressTick = work.ticks;
        }
        if (inventoryCount("minecraft:iron_ingot") >= 2) {
            FurnaceRecoverySummary recovery = recoverTaskFurnace(work, work.workstation, "bed-iron-complete");
            work.workstation = null;
            work.smeltingWorkstationClaimed = false;
            work.bedSmeltLoaded = 0;
            work.smeltStartedTick = -1;
            work.skippedWorkstationTargets.clear();
            progress(work, activeProgress(work), "已烧炼足够铁锭，准备制作剪刀" + recovery.detail());
            return;
        }

        if (furnace.getItem(0).isEmpty()) {
            int batch = Math.max(1, 2 - inventoryCount("minecraft:iron_ingot") - work.bedSmeltLoaded);
            ItemStack input = extract("minecraft:raw_iron", batch);
            if (input.isEmpty()) {
                releaseBedSmeltingWorkstation(work);
                return;
            }
            recordInventoryAction(work, "furnace-input");
            work.bedSmeltLoaded += input.getCount();
            recordTaskFurnaceInput(work, work.workstation, input);
            furnace.setItem(0, input);
            furnace.setChanged();
            work.lastProgressTick = work.ticks;
        }
        ItemStack fuel = furnace.getItem(1);
        if (!isCompatibleClaimedFurnaceFuel(fuel)) {
            releaseBedSmeltingWorkstation(work);
            return;
        }
        if (shouldSupplyFurnaceFuel(work.workstation, fuel)) {
            if (!hasSafeFurnaceFuel() && beginPreferredCoalFuelAcquisition(
                work,
                2 - inventoryCount("minecraft:iron_ingot"),
                "剪刀铁料烧炼"
            )) return;
            if (!hasSafeFurnaceFuel()) {
                beginCraftGather(work, "#minecraft:logs", 1, "烧炼剪刀铁料缺少燃料，先砍 1 个原木");
                return;
            }
            ItemStack suppliedFuel = extractFuel();
            if (suppliedFuel.isEmpty()) return;
            CraftChainLiveFixture.recordFurnaceFuelSupply(npc, work.workstation, suppliedFuel);
            recordTaskFurnaceFuel(work, work.workstation, suppliedFuel);
            furnace.setItem(1, suppliedFuel);
            furnace.setChanged();
            work.lastProgressTick = work.ticks;
        }
        if (work.smeltStartedTick < 0) work.smeltStartedTick = work.ticks;
        if (work.ticks % 20 == 0) progress(work, activeProgress(work), "正在烧炼铁锭制作剪刀");
        if (work.ticks - work.lastProgressTick > 20 * 40) {
            fail(work, "剪刀铁料烧炼长时间没有产物", "BED_IRON_SMELT_STALLED");
        }
    }

    private void tickWalkingDescent(ServerPlayer owner) {
        Vec3 target = walkingDescentWaypoint(owner);
        Vec3 horizontal = target.subtract(npc.position()).multiply(1.0D, 0.0D, 1.0D);
        if (horizontal.lengthSqr() <= 1.0E-4D) return;
        Vec3 direction = horizontal.normalize().scale(0.18D);
        Vec3 velocity = npc.getDeltaMovement();
        double vertical = npc.horizontalCollision
            ? Math.max(0.25D, velocity.y)
            : velocity.y;
        npc.getNavigation().stop();
        npc.move(MoverType.SELF, new Vec3(direction.x, 0.0D, direction.z));
        npc.setDeltaMovement(direction.x, vertical, direction.z);
        npc.hasImpulse = true;
        npc.addExhaustion(0.002F);
        npc.setStatus("正在沿地形下降接近主人");
    }

    private Vec3 walkingDescentWaypoint(ServerPlayer owner) {
        if (!(npc.level() instanceof ServerLevel level)) return owner.position();
        Vec3 best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int radius = 1; radius <= 8; radius++) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = direction * Math.PI / 8.0D;
                int x = (int) Math.floor(npc.getX() + Math.cos(angle) * radius);
                int z = (int) Math.floor(npc.getZ() + Math.sin(angle) * radius);
                BlockPos column = new BlockPos(x, npc.blockPosition().getY(), z);
                if (!level.hasChunkAt(column)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                double drop = npc.getY() - y;
                if (drop <= 2.0D || drop > 10.0D) continue;
                BlockPos feet = new BlockPos(x, y, z);
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    || !level.getFluidState(feet).isEmpty()) continue;
                double ownerDistance = Math.hypot(x + 0.5D - owner.getX(), z + 0.5D - owner.getZ());
                double score = radius + ownerDistance * 0.05D;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Vec3(x + 0.5D, y, z + 0.5D);
                }
            }
            if (best != null) break;
        }
        return best == null ? owner.position() : best;
    }

    private void releaseBedSmeltingWorkstation(ActiveWork work) {
        BlockPos released = work.workstation == null ? null : work.workstation.immutable();
        FurnaceRecoverySummary recovery = recoverTaskFurnace(work, released, "bed-iron-release");
        if (released != null) work.skippedWorkstationTargets.add(released);
        npc.getNavigation().stop();
        work.workstation = null;
        work.smeltingWorkstationClaimed = false;
        work.bedSmeltLoaded = 0;
        work.smeltStartedTick = -1;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        progress(work, activeProgress(work), "原烧炼设备不可继续使用，未知内容保持原样，正在换用空炉或新炉"
            + recovery.detail());
    }

    private void tickBedSheepWool(ActiveWork work, int shearsSlot) {
        int wool = inventoryCount("minecraft:white_wool");
        if (wool >= 3) {
            clearBedSheepSearch(work);
            return;
        }
        if (work.destination != null) {
            BlockPos searchArea = BlockPos.containing(work.destination);
            maintainTaskChunkTicket(searchArea);
            if (npc.position().distanceTo(work.destination) <= 3.5) {
                npc.getNavigation().stop();
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
            } else {
                approachGatherDestination(
                    work, searchArea, 3.5, 1.15,
                    "远程寻找羊的搜索区不可达", "BED_SHEEP_SEARCH_UNREACHABLE"
                );
                return;
            }
        }

        Sheep sheep = resolveBedSheepTarget(work);
        if (sheep == null && work.ticks - work.lastSearchTick >= 10) {
            work.lastSearchTick = work.ticks;
            sheep = findBedSheep(work, work.gatherSearchRadius);
            if (sheep != null) {
                work.bedSheepTargetId = sheep.getUUID();
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
                progress(work, activeProgress(work), "找到可安全剪毛的成年白羊，正在接近");
            } else if (work.gatherSearchRadius < GATHER_LOCAL_SEARCH_RADIUS) {
                work.gatherSearchRadius += 16;
            }
        }
        if (sheep == null) {
            if (++work.noWorkTicks > 40 && work.gatherSearchRadius >= GATHER_LOCAL_SEARCH_RADIUS) {
                if (work.gatherExcursions >= GATHER_MAX_EXCURSIONS) {
                    fail(work, "扩大搜索范围后仍没有找到可剪毛的成年白羊", "BED_SHEEP_NOT_FOUND");
                    return;
                }
                work.gatherExcursions++;
                work.destination = nextGatherSearchDestination(work.gatherExcursions);
                work.gatherSearchRadius = 16;
                work.noWorkTicks = 0;
                work.lastSearchTick = -10;
                work.skippedBedSheepTargets.clear();
                progress(work, activeProgress(work), "附近没有合适的羊，正在前往第 " + work.gatherExcursions + " 个搜索区");
            }
            return;
        }
        if (!approachBedSheep(work, sheep, GATHER_INTERACTION_REACH, 1.15)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        int before = inventoryCount("minecraft:white_wool");
        ItemStack shears = npc.inventory().getStackInSlot(shearsSlot);
        npc.getLookControl().setLookAt(sheep, 30.0F, 30.0F);
        npc.swing(InteractionHand.MAIN_HAND);
        InteractionResult result = proxy.interact(sheep, shears, shearsSlot);
        npc.absorbNearbyItemsAt(sheep.position(), 4.0);
        int after = inventoryCount("minecraft:white_wool");
        boolean sheared = sheep.isSheared();
        if (!result.consumesAction() && !sheared) {
            if (++work.failedActions >= 3) {
                work.skippedBedSheepTargets.add(sheep.getUUID());
                work.bedSheepTargetId = null;
                work.failedActions = 0;
            }
            return;
        }
        work.failedActions = 0;
        work.bedSheepTargetId = null;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        progress(work, activeProgress(work), "已剪羊毛，当前白色羊毛 " + after + "/3"
            + (after == before ? "，正在拾取掉落物" : ""));
    }

    private Sheep resolveBedSheepTarget(ActiveWork work) {
        if (work.bedSheepTargetId == null || !(npc.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(work.bedSheepTargetId);
        if (entity instanceof Sheep sheep && isSafeBedSheep(work, sheep)) return sheep;
        work.bedSheepTargetId = null;
        return null;
    }

    private Sheep findBedSheep(ActiveWork work, int requestedRadius) {
        int radius = Math.max(8, Math.min(GATHER_LOCAL_SEARCH_RADIUS, requestedRadius));
        return npc.level().getEntitiesOfClass(
            Sheep.class,
            npc.getBoundingBox().inflate(radius, 16, radius),
            sheep -> isSafeBedSheep(work, sheep)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private boolean isSafeBedSheep(ActiveWork work, Sheep sheep) {
        return sheep.isAlive()
            && !sheep.isBaby()
            && !sheep.hasCustomName()
            && !sheep.isSheared()
            && sheep.getColor() == DyeColor.WHITE
            && !work.skippedBedSheepTargets.contains(sheep.getUUID());
    }

    private boolean approachBedSheep(ActiveWork work, Sheep sheep, double reach, double speed) {
        Vec3 eye = npc.getEyePosition();
        AABB bounds = sheep.getBoundingBox();
        double interactionDistance = EntityInteractionDistancePolicy.distanceToExpandedBounds(
            eye.x, eye.y, eye.z,
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
            EntityInteractionDistancePolicy.TARGETING_MARGIN
        );
        if (interactionDistance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(sheep, 30.0F, 30.0F);
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        double centerDistance = npc.distanceTo(sheep);
        ServerPlayer owner = npc.owner();
        if (centerDistance > config.npcRecallDistance && owner != null && owner.hasPermissions(2)
            && npc.level() instanceof ServerLevel level) {
            BlockPos destination = safeTaskPositionNear(level, sheep.blockPosition());
            npc.getNavigation().stop();
            npc.teleportTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0;
            work.stalledTicks = 0;
            work.lastDistance = -1;
            npc.setStatus("已传送到远处羊群附近");
            return false;
        }
        if (npc.getNavigation().isDone()) {
            BlockPos target = sheep.blockPosition();
            Path path = npc.getNavigation().createPath(target, 2);
            if (path != null) npc.getNavigation().moveTo(path, speed);
            else navigateTowardBlock(target, speed);
        }
        npc.addExhaustion(0.002F);
        taskStatus(work, "正在走向羊群，距离 " + Math.round(centerDistance) + " 格");
        trackNavigation(work, interactionDistance);
        if (work.stalledTicks > 200) {
            work.skippedBedSheepTargets.add(sheep.getUUID());
            work.bedSheepTargetId = null;
            work.stalledTicks = 0;
            work.lastDistance = -1;
            npc.getNavigation().stop();
        }
        return false;
    }

    private int findUsableShearsSlot() {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!stack.is(Items.SHEARS)) continue;
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) return slot;
        }
        return -1;
    }

    private void clearBedSheepSearch(ActiveWork work) {
        work.bedSheepTargetId = null;
        work.destination = null;
        work.skippedBedSheepTargets.clear();
        work.gatherSearchRadius = 16;
        work.gatherExcursions = 0;
        work.noWorkTicks = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1;
    }

    private void tickBedPlacement(ActiveWork work) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) {
            fail(work, "无法确认玩家家园位置", "BED_HOME_UNAVAILABLE");
            return;
        }
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        if (!home.dimension().equals(level.dimension())) {
            fail(work, "玩家家园位于其他维度，当前无法返回安放床", "BED_HOME_DIMENSION_UNREACHABLE");
            return;
        }
        maintainTaskChunkTicket(home.position());
        level.getChunkAt(home.position());
        if (work.bedPlacementFoot == null || work.bedPlacementFacing == null) {
            NpcHomeStorage.BedPlacement placement = NpcHomeStorage.findSafeBedPlacement(
                level,
                home,
                home.position(),
                10,
                foot -> !work.skippedBedPlacements.contains(foot)
            );
            if (placement == null) {
                fail(work, "家附近没有找到有实体支撑的安全双格床位", "BED_PLACEMENT_NOT_FOUND");
                return;
            }
            work.bedPlacementFoot = placement.foot();
            work.bedPlacementFacing = placement.facing();
            progress(work, 0.99, "已选择家附近的安全床位，正在前往放置");
        }
        BlockPos foot = work.bedPlacementFoot;
        Direction facing = work.bedPlacementFacing;
        BlockPos head = foot.relative(facing);
        if (isBedPairAt(level, foot, facing)) {
            complete(work, "已在家附近完成床的制作与放置");
            return;
        }
        if (!level.getBlockState(foot).canBeReplaced() || !level.getBlockState(head).canBeReplaced()) {
            retryBedPlacement(work);
            return;
        }
        if (!approach(work, foot, 3.5, 1.08)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        int bedSlot = npc.creativeResources() ? -1 : findItemSlot(work.outputItemId == null ? "minecraft:white_bed" : work.outputItemId);
        if (!npc.creativeResources() && bedSlot < 0) {
            fail(work, "床已制作但背包中找不到待放置的床", "BED_ITEM_MISSING");
            return;
        }
        ItemStack bed = npc.creativeResources()
            ? new ItemStack(item(work.outputItemId == null ? "minecraft:white_bed" : work.outputItemId))
            : npc.inventory().getStackInSlot(bedSlot);
        npc.setYRot(facing.toYRot());
        npc.setYHeadRot(facing.toYRot());
        npc.getLookControl().setLookAt(Vec3.atCenterOf(foot));
        npc.swing(InteractionHand.MAIN_HAND);
        work.lastActionTick = work.ticks;
        proxy.useItemOn(foot.below(), Direction.UP, bed, bedSlot);
        if (isBedPairAt(level, foot, facing)) {
            work.bedPlacementPending = false;
            complete(work, "已在家附近完成床的制作与放置（" + foot.getX() + ", " + foot.getY() + ", " + foot.getZ() + "）");
            return;
        }
        retryBedPlacement(work);
    }

    private boolean isBedPairAt(ServerLevel level, BlockPos foot, Direction facing) {
        BlockState footState = level.getBlockState(foot);
        BlockState headState = level.getBlockState(foot.relative(facing));
        return footState.getBlock() instanceof BedBlock
            && headState.getBlock() == footState.getBlock()
            && footState.getValue(BedBlock.PART) == BedPart.FOOT
            && headState.getValue(BedBlock.PART) == BedPart.HEAD
            && footState.getValue(BedBlock.FACING) == facing
            && headState.getValue(BedBlock.FACING) == facing;
    }

    private void retryBedPlacement(ActiveWork work) {
        if (work.bedPlacementFoot != null) work.skippedBedPlacements.add(work.bedPlacementFoot.immutable());
        work.bedPlacementFoot = null;
        work.bedPlacementFacing = null;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        if (++work.failedActions >= 8) {
            fail(work, "连续多个家附近床位被占用或被保护规则拒绝", "BED_PLACEMENT_DENIED");
        }
    }

    private boolean prepareCraftPrerequisite(ActiveWork work, String itemId, Recipe<?> recipe) {
        if (npc.creativeResources()) return false;
        String materialContextId = materialContextItemId(work, itemId);
        int headCount = vanillaToolHeadCount(itemId);
        int stickCount = vanillaToolStickCount(itemId);
        String tier = vanillaToolTier(itemId);

        if (itemId.equals("minecraft:crafting_table") && inventoryCount("#minecraft:planks") < 4 && inventoryCount("#minecraft:logs") <= 0) {
            beginCraftGather(work, "#minecraft:logs", 1, "缺少工作台材料，先去砍 1 个原木");
            return true;
        }

        if (headCount > 0 && ("wooden".equals(tier) || stickCount > 0)
            && !hasWoodSupplyForTool(itemId)
            && inventoryCount("#minecraft:logs") <= 0) {
            beginCraftGather(work, "#minecraft:logs", 1, "缺少木材前置，先去砍树");
            return true;
        }

        if ("stone".equals(tier) && stoneToolMaterialCount() < headCount) {
            if (!hasUsablePickaxeForStone()) {
                if (!hasWoodSupplyForTool("minecraft:wooden_pickaxe") && inventoryCount("#minecraft:logs") <= 0) {
                    beginCraftGather(work, "#minecraft:logs", 1, "要挖圆石需要先做木镐，先去砍树");
                    return true;
                }
                if (craftOnePrerequisite(work, "minecraft:wooden_pickaxe", "先制作木镐用于挖圆石")) return true;
            }
            int missing = Math.max(1, headCount - stoneToolMaterialCount());
            beginCraftGather(work, "minecraft:cobblestone", missing, "缺少圆石，先用镐子挖 " + missing + " 个圆石");
            return true;
        }

        if (stickCount > 0 && !hasStickSupply(stickCount) && inventoryCount("#minecraft:logs") <= 0) {
            beginCraftGather(work, "#minecraft:logs", 1, "缺少木棍材料，先去砍树");
            return true;
        }
        if (recipe != null && prepareGenericCraftGatherPrerequisite(
            work,
            recipe,
            itemId,
            materialContextId
        )) return true;
        return false;
    }

    private boolean prepareGenericCraftGatherPrerequisite(
        ActiveWork work,
        Recipe<?> recipe,
        String itemId,
        String materialContextId
    ) {
        Ingredient missing = firstMissingIngredient(recipe.getIngredients());
        if (missing == null) return false;
        if (ingredientMatches(missing, CraftPrerequisitePolicy::isWoodCraftingIngredient)) {
            int required = countRecipeIngredientSlots(recipe, CraftPrerequisitePolicy::isWoodCraftingIngredient);
            int availableUnits = inventoryCount("#minecraft:planks") + inventoryCount("#minecraft:logs") * 4;
            int missingWoodUnits = Math.max(0, required - availableUnits);
            int logs = CraftPrerequisitePolicy.logsNeededForWoodUnits(missingWoodUnits);
            if (logs <= 0) return false;
            String familySelector = BuildMaterialPrerequisitePolicy.preferredWoodGatherSelector(materialContextId);
            String gatherSelector = familySelector.isBlank() ? "#minecraft:logs" : familySelector;
            beginCraftGather(work, gatherSelector, logs, "制作 " + itemId + " 缺少木材，先去砍 " + logs + " 个原木");
            return true;
        }
        if (ingredientMatches(missing, CraftPrerequisitePolicy::isStoneCraftingIngredient)) {
            int required = countRecipeIngredientSlots(recipe, CraftPrerequisitePolicy::isStoneCraftingIngredient);
            int missingStone = CraftPrerequisitePolicy.stoneNeededForStoneUnits(required - stoneToolMaterialCount());
            if (missingStone <= 0) return false;
            if (!hasUsablePickaxeForStone()) {
                if (!hasWoodSupplyForTool("minecraft:wooden_pickaxe") && inventoryCount("#minecraft:logs") <= 0) {
                    beginCraftGather(work, "#minecraft:logs", 1, "制作 " + itemId + " 需要先做木镐，先去砍树");
                    return true;
                }
                if (craftOnePrerequisite(work, "minecraft:wooden_pickaxe", "先制作木镐用于挖石材")) return true;
            }
            beginCraftGather(work, "minecraft:cobblestone", missingStone, "制作 " + itemId + " 缺少石材，先挖 " + missingStone + " 个圆石");
            return true;
        }
        return false;
    }

    private boolean prepareMissingCraftingTableMaterials(ActiveWork work, String itemId) {
        if (npc.creativeResources()) return false;
        if (inventoryCount("minecraft:crafting_table") > 0) return false;
        if (inventoryCount("#minecraft:planks") + inventoryCount("#minecraft:logs") * 4 >= 4) return false;
        beginCraftGather(work, "#minecraft:logs", 1, "制作 " + itemId + " 需要工作台，先去砍树");
        return true;
    }

    private boolean hasCraftGatherPrerequisite(ActiveWork work) {
        return work.craftGatherItemId != null && !work.craftGatherItemId.isBlank();
    }

    private void beginCraftGather(ActiveWork work, String itemId, int count, String message) {
        work.craftGatherItemId = itemId;
        work.craftGatherCount = Math.max(1, count);
        work.craftGatherInitialCount = inventoryCount(itemId);
        work.craftGatherCompleted = 0;
        work.craftGatherStartedTick = work.ticks;
        work.targetBlock = null;
        work.destination = null;
        work.gatherTargets.clear();
        work.skippedGatherTargets.clear();
        work.gatherSearchRadius = 16;
        work.gatherExcursions = 0;
        work.gatherPathFailures = 0;
        work.gatherTreeCluster = false;
        work.gatherClusterReached = false;
        work.gatherAccessTarget = false;
        work.noWorkTicks = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.lastGatherPathAttemptTick = -1;
        work.gatherStandPathCursor = 0;
        work.lastSearchTick = -10;
        progress(work, activeProgress(work), message);
    }

    private boolean tickCraftGatherPrerequisite(ActiveWork work) {
        String itemId = work.craftGatherItemId;
        if (work.craftGatherStartedTick < 0) work.craftGatherStartedTick = work.ticks;
        ResourceSelector selector = ResourceSelector.parse(itemId);
        int requested = Math.max(1, work.craftGatherCount);
        npc.absorbNearbyItems(2.5);
        int gathered = GatherProgressPolicy.includingExternalSupply(
            work.craftGatherCompleted,
            work.craftGatherInitialCount,
            inventoryCount(itemId)
        );
        work.craftGatherCompleted = gathered;
        if (!work.buildMaterialGoals.isEmpty()) {
            BuildMaterialGoal buildGoal = work.buildMaterialGoals.peekFirst();
            int buildCount = inventoryCount(buildGoal.selector);
            if (buildCount > buildGoal.lastInventoryCount) {
                buildGoal.lastInventoryCount = buildCount;
                buildGoal.stalledTicks = 0;
                work.buildLastProgressTick = work.ticks;
            }
        }
        if (gathered >= requested) {
            if (deepMiningActiveFor(work, itemId)) clearDeepMining(work);
            clearCraftGatherPrerequisite(work);
            String next = switch (work.kind) {
                case "gather" -> "继续采集 " + string(work.spec, "itemId", "目标资源");
                case "build" -> "返回原建筑索引继续建造";
                case "smelt" -> "继续烧炼 " + string(work.spec, "itemId", "目标材料");
                case "farm" -> "返回农田继续播种";
                case "fish" -> "继续补齐钓鱼竿依赖";
                case "ranch" -> "继续准备畜牧前置材料";
                case "provision-food" -> "继续烹饪并补足口粮";
                default -> "继续制作 " + work.outputItemId;
            };
            progress(work, activeProgress(work), "前置材料已补齐，" + next);
            return true;
        }
        if (deepMiningActiveFor(work, itemId)) {
            tickDeepMining(work, itemId, selector, requested, true);
            return true;
        }
        if (work.kind.equals("build") && !npc.creativeResources()) {
            String requiredTool = GatherToolPolicy.requiredPickaxe(itemId);
            BlockState probe = requiredTool.isBlank() ? null : gatherProbeState(itemId);
            if (probe != null && !hasUsablePickaxeFor(probe)) {
                clearCraftGatherPrerequisite(work);
                return prepareCraftedToolPrerequisite(
                    work,
                    requiredTool,
                    "继续采集任务材料前先补充 " + requiredTool,
                    "任务材料采集工具损坏且无法重新制作",
                    "BUILD_GATHER_TOOL_RECIPE_MISSING",
                    "BUILD_GATHER_TOOL_MATERIALS_MISSING"
                );
            }
        }
        if (work.destination != null) {
            BlockPos searchArea = BlockPos.containing(work.destination);
            maintainTaskChunkTicket(searchArea);
            if (npc.position().distanceTo(work.destination) <= 3.5) {
                npc.getNavigation().stop();
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
            } else {
                if (!approachGatherDestination(
                    work,
                    searchArea,
                    3.5,
                    1.15,
                    "制作前置材料不足，远程搜索区不可达",
                    "CRAFT_PREREQUISITE_NOT_FOUND"
                )) {
                    if (work.ticks % 40 == 0) {
                        progress(work, activeProgress(work),
                            "正在为制作寻找前置材料 " + itemId + "，已获得 " + gathered + "/" + requested);
                    }
                    return true;
                }
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
            }
        }
        if (!isCurrentGatherTargetValid(work, selector)) {
            work.gatherAccessTarget = false;
            work.targetBlock = pollGatherTarget(work, selector);
            if (work.targetBlock == null && work.ticks - work.lastSearchTick >= 10) {
                work.lastSearchTick = work.ticks;
                BlockPos seed = DeepMiningPolicy.supports(itemId)
                    ? findExposedGatherBlock(selector, work.gatherSearchRadius, 24, work.skippedGatherTargets)
                    : findGatherBlock(selector, work.gatherSearchRadius, 24, work.skippedGatherTargets);
                if (seed != null) {
                    enqueueConnectedResources(work, seed, selector);
                    work.targetBlock = pollGatherTarget(work, selector);
                } else if (work.gatherSearchRadius < GATHER_LOCAL_SEARCH_RADIUS) {
                    work.gatherSearchRadius += 16;
                }
            }
            if (work.targetBlock == null && work.gatherSearchRadius >= GATHER_LOCAL_SEARCH_RADIUS) {
                if (DeepMiningPolicy.supports(itemId)) {
                    startDeepMining(work, itemId);
                    tickDeepMining(work, itemId, selector, requested, true);
                    return true;
                }
                work.targetBlock = findMiningAccessBlock(work, selector);
                if (work.targetBlock != null) {
                    work.gatherAccessTarget = !matchesGatherBlock(work.targetBlock, selector, bestToolStack());
                    npc.setStatus(work.gatherAccessTarget
                        ? "缺少裸露目标，正在为制作开安全矿道"
                        : "找到可安全采集的制作前置材料");
                }
            }
            if (work.targetBlock == null) {
                work.noWorkTicks++;
                if (GatherRetryPolicy.shouldStartRemoteExcursion(
                    work.gatherTargets.isEmpty(),
                    work.targetBlock != null,
                    work.noWorkTicks,
                    work.gatherSearchRadius,
                    GATHER_LOCAL_SEARCH_RADIUS
                )) {
                    if (work.gatherExcursions >= GATHER_MAX_EXCURSIONS) {
                        fail(work, "制作前置材料不足，远程搜索后仍没有找到可采集的 " + itemId, "CRAFT_PREREQUISITE_NOT_FOUND");
                        return true;
                    }
                    work.gatherExcursions++;
                    work.destination = nextGatherSearchDestination(work.gatherExcursions);
                    work.gatherSearchRadius = 16;
                    work.noWorkTicks = 0;
                    work.lastSearchTick = -10;
                    progress(work, activeProgress(work),
                        "附近缺少制作前置材料，正在前往第 " + work.gatherExcursions + " 个搜索区");
                }
                return true;
            }
            work.stalledTicks = 0;
            work.gatherPathFailures = 0;
            work.lastDistance = -1;
            work.lastGatherPathAttemptTick = -1;
            work.gatherStandPathCursor = 0;
            work.noWorkTicks = 0;
        }
        if (!approachGatherTarget(work, work.targetBlock, 2.8, 1.15)) return true;
        if (work.ticks - work.lastActionTick < 8) return true;
        work.lastActionTick = work.ticks;
        BlockPos brokenTarget = work.targetBlock.immutable();
        BlockState targetState = npc.level().getBlockState(work.targetBlock);
        if (work.kind.equals("build") && !npc.creativeResources()
            && targetState.requiresCorrectToolForDrops() && !hasUsableToolFor(targetState)) {
            clearCraftGatherPrerequisite(work);
            return prepareGatherTool(work, targetState, itemId);
        }
        boolean accessTarget = work.gatherAccessTarget;
        int inventoryBeforeBreak = inventoryCount(itemId);
        int toolSlot = bestGatherToolSlot(targetState, selector);
        if (toolSlot >= 0) equipMainHand(toolSlot);
        npc.swing(InteractionHand.MAIN_HAND);
        boolean broken = proxy.breakBlock(work.targetBlock, toolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1);
        if (!broken) {
            if (++work.failedActions >= 3) {
                fail(work, "无法采集制作前置材料 " + itemId, "CRAFT_PREREQUISITE_BREAK_DENIED");
            }
            return true;
        }
        npc.absorbNearbyItemsAt(Vec3.atCenterOf(brokenTarget), 3.0);
        recordInventoryAction(work, "gather-pickup");
        refreshConnectedResourcesAfterBreak(work, brokenTarget, selector, targetState.is(BlockTags.LOGS));
        npc.addExhaustion(0.08F);
        work.targetBlock = null;
        work.gatherAccessTarget = false;
        int acquiredForPrerequisite = GatherProgressPolicy.afterBreak(
            work.craftGatherCompleted,
            inventoryBeforeBreak,
            inventoryCount(itemId)
        );
        work.craftGatherCompleted = GatherProgressPolicy.retained(
            acquiredForPrerequisite,
            work.craftGatherInitialCount,
            inventoryCount(itemId)
        );
        gathered = work.craftGatherCompleted;
        progress(
            work,
            activeProgress(work),
            accessTarget
                ? "正在为制作开安全矿道，继续寻找 " + itemId
                : "制作前置采集中 " + gathered + "/" + requested + " " + itemId
        );
        if (work.ticks - work.craftGatherStartedTick > CRAFT_PREREQUISITE_TIMEOUT_TICKS) {
            fail(work, "制作前置采集超时", "CRAFT_PREREQUISITE_TIMEOUT");
        }
        return true;
    }

    private void clearCraftGatherPrerequisite(ActiveWork work) {
        work.craftGatherItemId = null;
        work.craftGatherCount = 0;
        work.craftGatherInitialCount = 0;
        work.craftGatherCompleted = 0;
        work.craftGatherStartedTick = -1;
        work.targetBlock = null;
        work.destination = null;
        work.gatherTargets.clear();
        work.gatherTreeCluster = false;
        work.gatherClusterReached = false;
        work.gatherAccessTarget = false;
        work.gatherPathFailures = 0;
        work.gatherSearchRadius = 16;
        work.gatherExcursions = 0;
        work.skippedGatherTargets.clear();
        work.noWorkTicks = 0;
        work.lastSearchTick = -10;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.lastGatherPathAttemptTick = -1;
        work.gatherStandPathCursor = 0;
    }

    private boolean craftOnePrerequisite(ActiveWork work, String outputId, String message) {
        return craftOnePrerequisite(work, outputId, message, materialContextItemId(work, outputId));
    }

    private boolean craftOnePrerequisite(
        ActiveWork work,
        String outputId,
        String message,
        String materialContextId
    ) {
        Recipe<?> recipe = findCraftRecipe(outputId, materialContextId);
        if (recipe == null) return false;
        boolean requiresTable = !recipe.canCraftInDimensions(2, 2);
        List<Integer> ingredients = allocateIngredients(recipe.getIngredients());
        if (ingredients == null) {
            if (craftMissingIngredient(work, recipe, materialContextId, 0, new HashSet<>())) return true;
            return false;
        }
        if (requiresTable && !prepareCraftingWorkstation(work, outputId)) return true;
        if (work.ticks - work.lastActionTick < 8) return true;
        ItemStack output = recipe.getResultItem(npc.level().registryAccess()).copy();
        if (output.isEmpty() || !CraftingIngredientAllocator.canInsertAfterConsumption(
            ingredients,
            CodexNpcEntity.BACKPACK_SIZE,
            slot -> npc.inventory().getStackInSlot(slot),
            output
        )) return false;
        work.lastActionTick = work.ticks;
        consumeIngredients(ingredients);
        npc.insert(output);
        recordInventoryAction(work, "craft-output");
        npc.swing(InteractionHand.MAIN_HAND);
        npc.addExhaustion(0.03F);
        progress(work, activeProgress(work), message + "：" + outputId);
        return true;
    }

    private void tickSmelt(ActiveWork work) {
        String inputId = string(work.spec, "itemId", "");
        int requested = integer(work.spec, "count", 1);
        if (!work.initialized) {
            work.recipe = findCookingRecipe(inputId);
            if (work.recipe == null) {
                fail(work, "没有找到 " + inputId + " 的烧炼配方", "RECIPE_NOT_FOUND");
                return;
            }
            work.initialized = true;
            work.requestedCount = requested;
            work.outputItemId = itemId(work.recipe.getResultItem(npc.level().registryAccess()));
            // A checkpoint from an older build has no ownership marker.  Do
            // not adopt its arbitrary furnace reference: the slots may belong
            // to the player.  A marked checkpoint is validated below.
            if (!work.smeltingWorkstationClaimed || !hasTaskFurnaceClaim(work, work.workstation)) {
                work.workstation = null;
                work.smeltingWorkstationClaimed = false;
            }
        }
        if (hasCraftGatherPrerequisite(work) && tickCraftGatherPrerequisite(work)) return;

        String workstationId = cookingWorkstation(work.recipe.getType());
        if (work.workstation != null) {
            SmeltingWorkstationPolicy.Validation validation = validateSmeltingWorkstation(
                work,
                workstationId,
                inputId,
                work.outputItemId
            );
            if (validation != SmeltingWorkstationPolicy.Validation.USABLE) {
                releaseSmeltingWorkstation(work, validation);
                return;
            }
        }
        if (!npc.creativeResources()) {
            int missingInput = SmeltingPrerequisitePolicy.missingInput(requested, work.loaded, inventoryCount(inputId));
            if (missingInput > 0) {
                if (prepareSmeltingInputTool(work, inputId)) return;
                beginCraftGather(work, inputId, missingInput,
                    "烧炼缺少原料，先去采集 " + missingInput + " 个 " + inputId);
                return;
            }
        }
        if (work.workstation == null) {
            work.workstation = findClaimableSmeltingWorkstation(work, workstationId);
            if (work.workstation != null) {
                int outputPerInput = Math.max(1, work.recipe.getResultItem(npc.level().registryAccess()).getCount());
                work.smeltingWorkstationClaimed = claimTaskFurnace(
                    work, work.workstation, inputId, work.outputItemId, outputPerInput
                );
                if (work.smeltingWorkstationClaimed) {
                    progress(work, activeProgress(work), "已确认并占用一座完全空闲的 " + workstationId);
                } else {
                    work.skippedWorkstationTargets.add(work.workstation.immutable());
                    work.workstation = null;
                }
            }
        }
        if (work.workstation == null) {
            if (prepareSmeltingWorkstation(work, workstationId)) return;
            work.workstation = ensureWorkstation(work, workstationId);
            if (work.workstation == null) return;
            int outputPerInput = Math.max(1, work.recipe.getResultItem(npc.level().registryAccess()).getCount());
            work.smeltingWorkstationClaimed = claimTaskFurnace(
                work, work.workstation, inputId, work.outputItemId, outputPerInput
            );
            if (!work.smeltingWorkstationClaimed) {
                work.skippedWorkstationTargets.add(work.workstation.immutable());
                work.workstation = null;
                return;
            }
        }
        if (!approach(work, work.workstation, 3.5, 1.05)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            releaseSmeltingWorkstation(work, SmeltingWorkstationPolicy.Validation.BLOCK_MISSING);
            return;
        }

        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty()) {
            if (!itemId(output).equals(work.outputItemId)) {
                releaseSmeltingWorkstation(work, SmeltingWorkstationPolicy.Validation.OUTPUT_CONFLICT);
                return;
            }
            if (!canInsert(output)) {
                fail(work, "NPC 背包没有空间接收烧炼产物", "INVENTORY_FULL");
                return;
            }
            int moved = output.getCount();
            npc.insert(output.copy());
            recordInventoryAction(work, "furnace-output");
            recordTaskFurnaceOutputWithdrawal(work, work.workstation, output);
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
            work.completed += moved;
            work.lastProgressTick = work.ticks;
        }
        if (work.completed >= requested) {
            complete(work, "已获得 " + work.completed + " 个 " + work.outputItemId);
            return;
        }

        ItemStack furnaceInput = furnace.getItem(0);
        if (!furnaceInput.isEmpty() && !itemId(furnaceInput).equals(inputId)) {
            releaseSmeltingWorkstation(work, SmeltingWorkstationPolicy.Validation.INPUT_CONFLICT);
            return;
        }
        if (furnaceInput.isEmpty() && work.loaded < requested) {
            int batch = Math.min(64, requested - work.loaded);
            ItemStack input;
            if (npc.creativeResources()) input = new ItemStack(item(inputId), batch);
            else input = extract(inputId, batch);
            if (input.isEmpty()) {
                fail(work, "无法取得烧炼材料", "MISSING_INPUT");
                return;
            }
            if (!npc.creativeResources()) recordInventoryAction(work, "furnace-input");
            work.loaded += input.getCount();
            recordTaskFurnaceInput(work, work.workstation, input);
            furnace.setItem(0, input);
            furnace.setChanged();
            work.lastProgressTick = work.ticks;
        }
        ItemStack furnaceFuel = furnace.getItem(1);
        if (!isCompatibleClaimedFurnaceFuel(furnaceFuel)) {
            releaseSmeltingWorkstation(work, SmeltingWorkstationPolicy.Validation.FUEL_CONFLICT);
            return;
        }
        if (shouldSupplyFurnaceFuel(work.workstation, furnaceFuel)) {
            if (!npc.creativeResources() && !hasSafeFurnaceFuel()
                && beginPreferredCoalFuelAcquisition(
                    work,
                    requested - work.completed,
                    "烧炼"
                )) return;
            if (!npc.creativeResources() && !hasSafeFurnaceFuel()) {
                int fuelLogs = SmeltingPrerequisitePolicy.fallbackFuelLogs(requested - work.completed);
                beginCraftGather(work, "#minecraft:logs", fuelLogs,
                    "烧炼缺少安全燃料，先去采集 " + fuelLogs + " 个原木");
                return;
            }
            ItemStack fuel = npc.creativeResources() ? new ItemStack(Items.COAL) : extractFuel();
            if (fuel.isEmpty()) {
                fail(work, "NPC 背包中没有熔炉燃料", "MISSING_FUEL");
                return;
            }
            CraftChainLiveFixture.recordFurnaceFuelSupply(npc, work.workstation, fuel);
            recordTaskFurnaceFuel(work, work.workstation, fuel);
            furnace.setItem(1, fuel);
            furnace.setChanged();
            work.lastProgressTick = work.ticks;
        }
        if (work.smeltStartedTick < 0) work.smeltStartedTick = work.ticks;
        if (work.ticks % 20 == 0) progress(work, Math.min(0.99, work.completed / (double) requested), "已烧炼 " + work.completed + "/" + requested);
        if (work.ticks - work.lastProgressTick > 20 * 40) fail(work, "熔炉长时间没有产生结果", "SMELT_STALLED");
        if (work.smeltStartedTick >= 0
            && work.ticks - work.smeltStartedTick > 20L * (90 + requested * 12L)) {
            fail(work, "烧炼超时", "SMELT_TIMEOUT");
        }
    }

    private BlockPos findClaimableSmeltingWorkstation(ActiveWork work, String workstationId) {
        return findBlockAt(position -> {
            if (work.skippedWorkstationTargets.contains(position)
                || !id(npc.level().getBlockState(position).getBlock()).equals(workstationId)) return false;
            BlockEntity blockEntity = npc.level().getBlockEntity(position);
            if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) return false;
            return SmeltingWorkstationPolicy.canClaim(
                furnace.getItem(0).isEmpty(),
                furnace.getItem(1).isEmpty(),
                furnace.getItem(2).isEmpty()
            );
        }, 16, 5);
    }

    private SmeltingWorkstationPolicy.Validation validateSmeltingWorkstation(
        ActiveWork work,
        String workstationId,
        String inputId,
        String outputId
    ) {
        if (work.workstation == null) return SmeltingWorkstationPolicy.Validation.UNCLAIMED;
        boolean blockMatches = id(npc.level().getBlockState(work.workstation).getBlock()).equals(workstationId);
        BlockEntity blockEntity = blockMatches ? npc.level().getBlockEntity(work.workstation) : null;
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return SmeltingWorkstationPolicy.validate(
                work.smeltingWorkstationClaimed && hasTaskFurnaceClaim(work, work.workstation),
                false,
                true,
                true,
                true
            );
        }
        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        ItemStack output = furnace.getItem(2);
        return SmeltingWorkstationPolicy.validate(
            work.smeltingWorkstationClaimed && hasTaskFurnaceClaim(work, work.workstation),
            true,
            input.isEmpty() || itemId(input).equals(inputId),
            isCompatibleClaimedFurnaceFuel(fuel),
            output.isEmpty() || itemId(output).equals(outputId)
        );
    }

    private boolean isCompatibleClaimedFurnaceFuel(ItemStack fuel) {
        // A lava bucket legitimately leaves an empty bucket in the fuel slot.
        return fuel.isEmpty() || AbstractFurnaceBlockEntity.isFuel(fuel) || fuel.is(Items.BUCKET);
    }

    private void releaseSmeltingWorkstation(
        ActiveWork work,
        SmeltingWorkstationPolicy.Validation validation
    ) {
        BlockPos released = work.workstation == null ? null : work.workstation.immutable();
        FurnaceRecoverySummary recovery = recoverTaskFurnace(work, released, "workstation-release");
        if (released != null) work.skippedWorkstationTargets.add(released);
        npc.getNavigation().stop();
        work.workstation = null;
        work.smeltingWorkstationClaimed = false;
        work.targetBlock = null;
        work.loaded = SmeltingWorkstationPolicy.loadedAfterRelease(work.completed);
        work.smeltStartedTick = -1;
        work.lastProgressTick = work.ticks;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        String detail = switch (validation) {
            case INPUT_CONFLICT -> "原料槽出现了其他物品";
            case FUEL_CONFLICT -> "燃料槽出现了不兼容物品";
            case OUTPUT_CONFLICT -> "产物槽出现了其他物品";
            case BLOCK_MISSING -> "原烧炼设备已不存在";
            case UNCLAIMED -> "原烧炼设备没有任务所有权记录";
            case USABLE -> "原烧炼设备不再可用";
        };
        progress(work, activeProgress(work), detail + "；未知内容保持原样，任务投入已按账本回收并改用其他空炉或新炉"
            + recovery.detail());
    }

    private boolean claimTaskFurnace(
        ActiveWork work,
        BlockPos position,
        String inputItemId,
        String outputItemId,
        int outputPerInput
    ) {
        if (position == null || inputItemId == null || inputItemId.isBlank()
            || outputItemId == null || outputItemId.isBlank()) return false;
        String dimensionId = currentDimensionId();
        FurnaceTransaction existing = taskFurnace(work, dimensionId, position);
        if (existing != null) {
            BlockEntity existingBlockEntity = npc.level().getBlockEntity(position);
            if (existingBlockEntity instanceof AbstractFurnaceBlockEntity existingFurnace
                && furnaceClaimMatches(existingFurnace, existing)) {
                return existing.matches(dimensionId, position, inputItemId, outputItemId, outputPerInput);
            }
            work.furnaceTransactions.remove(existing);
        }
        if (work.furnaceTransactions.size() >= MAX_TASK_FURNACE_TRANSACTIONS) return false;
        BlockEntity blockEntity = npc.level().getBlockEntity(position);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)
            || furnace.getPersistentData().contains(TASK_FURNACE_CLAIM_TAG)
            || !SmeltingWorkstationPolicy.canClaim(
                furnace.getItem(FurnaceRecoveryPolicy.INPUT_SLOT).isEmpty(),
                furnace.getItem(FurnaceRecoveryPolicy.FUEL_SLOT).isEmpty(),
                furnace.getItem(FurnaceRecoveryPolicy.OUTPUT_SLOT).isEmpty()
            )) return false;
        UUID claimId = UUID.randomUUID();
        furnace.getPersistentData().putUUID(TASK_FURNACE_CLAIM_TAG, claimId);
        furnace.setChanged();
        work.furnaceTransactions.add(new FurnaceTransaction(
            claimId,
            dimensionId,
            position,
            inputItemId,
            outputItemId,
            outputPerInput
        ));
        return true;
    }

    private boolean hasTaskFurnaceClaim(ActiveWork work, BlockPos position) {
        if (position == null) return false;
        FurnaceTransaction transaction = taskFurnace(work, currentDimensionId(), position);
        if (transaction == null) return false;
        BlockEntity blockEntity = npc.level().getBlockEntity(position);
        return blockEntity instanceof AbstractFurnaceBlockEntity furnace
            && furnaceClaimMatches(furnace, transaction);
    }

    private BlockPos findReusableBuildMaterialFurnace(
        ActiveWork work,
        String inputItemId,
        String outputItemId,
        int outputPerInput
    ) {
        String dimensionId = currentDimensionId();
        for (FurnaceTransaction transaction : work.furnaceTransactions) {
            boolean recipeMatches = transaction.matches(
                dimensionId,
                transaction.position,
                inputItemId,
                outputItemId,
                outputPerInput
            );
            if (work.skippedWorkstationTargets.contains(transaction.position)
                || !isReusableTaskFurnace(transaction, recipeMatches)) continue;
            return transaction.position.immutable();
        }
        return null;
    }

    private boolean isReusableTaskFurnace(ActiveWork work, BlockPos position) {
        if (position == null) return false;
        FurnaceTransaction transaction = taskFurnace(work, currentDimensionId(), position);
        return transaction != null && isReusableTaskFurnace(transaction, true);
    }

    private boolean isReusableTaskFurnace(FurnaceTransaction transaction, boolean recipeMatches) {
        if (transaction == null || !transaction.dimensionId.equals(currentDimensionId())) return false;
        BlockState state = npc.level().getBlockState(transaction.position);
        BlockEntity blockEntity = npc.level().getBlockEntity(transaction.position);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) return false;
        SmeltingWorkstationPolicy.Validation validation = SmeltingWorkstationPolicy.validate(
            furnaceClaimMatches(furnace, transaction),
            id(state.getBlock()).equals("minecraft:furnace"),
            furnace.getItem(0).isEmpty()
                || itemId(furnace.getItem(0)).equals(transaction.inputItemId),
            isCompatibleClaimedFurnaceFuel(furnace.getItem(1)),
            furnace.getItem(2).isEmpty()
                || itemId(furnace.getItem(2)).equals(transaction.outputItemId)
        );
        return SmeltingWorkstationPolicy.canReuse(recipeMatches, validation);
    }

    private boolean furnaceClaimMatches(
        AbstractFurnaceBlockEntity furnace,
        FurnaceTransaction transaction
    ) {
        return furnace.getPersistentData().hasUUID(TASK_FURNACE_CLAIM_TAG)
            && furnace.getPersistentData().getUUID(TASK_FURNACE_CLAIM_TAG).equals(transaction.claimId);
    }

    private FurnaceTransaction taskFurnace(ActiveWork work, String dimensionId, BlockPos position) {
        if (work == null || dimensionId == null || position == null) return null;
        for (FurnaceTransaction transaction : work.furnaceTransactions) {
            if (transaction.dimensionId.equals(dimensionId) && transaction.position.equals(position)) {
                return transaction;
            }
        }
        return null;
    }

    private void recordTaskFurnaceInput(ActiveWork work, BlockPos position, ItemStack stack) {
        FurnaceTransaction transaction = taskFurnace(work, currentDimensionId(), position);
        if (transaction == null || stack.isEmpty() || !transaction.inputItemId.equals(itemId(stack))) return;
        transaction.inputDeposited = saturatingAdd(transaction.inputDeposited, stack.getCount());
    }

    private void recordTaskFurnaceFuel(ActiveWork work, BlockPos position, ItemStack stack) {
        FurnaceTransaction transaction = taskFurnace(work, currentDimensionId(), position);
        if (transaction == null || stack.isEmpty()) return;
        String fuelItemId = itemId(stack);
        transaction.fuelDeposited.merge(fuelItemId, stack.getCount(), NpcTaskEngine::saturatingAdd);
    }

    private void recordTaskFurnaceOutputWithdrawal(ActiveWork work, BlockPos position, ItemStack stack) {
        FurnaceTransaction transaction = taskFurnace(work, currentDimensionId(), position);
        if (transaction == null || stack.isEmpty() || !transaction.outputItemId.equals(itemId(stack))) return;
        transaction.outputWithdrawn = saturatingAdd(transaction.outputWithdrawn, stack.getCount());
    }

    private FurnaceRecoverySummary recoverAllTaskFurnaces(ActiveWork work, String reason) {
        FurnaceRecoverySummary result = FurnaceRecoverySummary.empty();
        List<FurnaceTransaction> pending = new ArrayList<>(work.furnaceTransactions);
        for (FurnaceTransaction transaction : pending) {
            result = result.plus(recoverTaskFurnace(work, transaction, reason));
        }
        return result;
    }

    private FurnaceRecoverySummary recoverTaskFurnace(ActiveWork work, BlockPos position, String reason) {
        if (position == null) return FurnaceRecoverySummary.empty();
        FurnaceTransaction transaction = taskFurnace(work, currentDimensionId(), position);
        if (transaction == null) return FurnaceRecoverySummary.empty();
        return recoverTaskFurnace(work, transaction, reason);
    }

    private FurnaceRecoverySummary recoverTaskFurnace(
        ActiveWork work,
        FurnaceTransaction transaction,
        String reason
    ) {
        ServerLevel sourceLevel = furnaceLevel(transaction.dimensionId);
        if (sourceLevel == null) return FurnaceRecoverySummary.empty();
        BlockEntity blockEntity = sourceLevel.getBlockEntity(transaction.position);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            work.furnaceTransactions.remove(transaction);
            return FurnaceRecoverySummary.empty();
        }
        CompoundTag persistentData = furnace.getPersistentData();
        if (!persistentData.hasUUID(TASK_FURNACE_CLAIM_TAG)
            || !persistentData.getUUID(TASK_FURNACE_CLAIM_TAG).equals(transaction.claimId)) {
            // The block was replaced or claimed by someone else. Its current
            // contents are not attributable to this task and remain untouched.
            work.furnaceTransactions.remove(transaction);
            return FurnaceRecoverySummary.empty();
        }

        FurnaceRecoverySummary result = FurnaceRecoverySummary.empty();
        for (int slot = FurnaceRecoveryPolicy.INPUT_SLOT; slot <= FurnaceRecoveryPolicy.OUTPUT_SLOT; slot++) {
            ItemStack current = furnace.getItem(slot);
            int recoverable = FurnaceRecoveryPolicy.recoverableCount(
                slot,
                itemId(current),
                current.getCount(),
                transaction.inputItemId,
                transaction.inputDeposited,
                transaction.outputItemId,
                transaction.outputPerInput,
                transaction.outputWithdrawn,
                transaction.fuelDeposited
            );
            if (recoverable <= 0) continue;

            ItemStack transfer = current.copyWithCount(recoverable);
            ItemStack remainder = npc.insert(transfer.copy());
            int inserted = recoverable - remainder.getCount();
            if (inserted > 0) {
                npc.recordInventoryTransaction(work.id, "furnace-recovery");
                shrinkFurnaceSlot(furnace, slot, inserted);
            }

            int escrowed = 0;
            int retained = 0;
            if (!remainder.isEmpty()) {
                if (spawnFurnaceRecoveryEscrow(remainder.copy(), work, transaction, slot, reason)) {
                    escrowed = remainder.getCount();
                    shrinkFurnaceSlot(furnace, slot, escrowed);
                } else {
                    retained = remainder.getCount();
                }
            }
            result = result.plus(new FurnaceRecoverySummary(inserted, escrowed, retained));
        }
        furnace.setChanged();
        if (!hasRecoverableTaskFurnaceContents(furnace, transaction)) {
            persistentData.remove(TASK_FURNACE_CLAIM_TAG);
            furnace.setChanged();
            work.furnaceTransactions.remove(transaction);
        }
        return result;
    }

    private boolean hasRecoverableTaskFurnaceContents(
        AbstractFurnaceBlockEntity furnace,
        FurnaceTransaction transaction
    ) {
        for (int slot = FurnaceRecoveryPolicy.INPUT_SLOT; slot <= FurnaceRecoveryPolicy.OUTPUT_SLOT; slot++) {
            ItemStack stack = furnace.getItem(slot);
            if (FurnaceRecoveryPolicy.recoverableCount(
                slot,
                itemId(stack),
                stack.getCount(),
                transaction.inputItemId,
                transaction.inputDeposited,
                transaction.outputItemId,
                transaction.outputPerInput,
                transaction.outputWithdrawn,
                transaction.fuelDeposited
            ) > 0) return true;
        }
        return false;
    }

    private void shrinkFurnaceSlot(AbstractFurnaceBlockEntity furnace, int slot, int count) {
        if (count <= 0) return;
        ItemStack current = furnace.getItem(slot);
        current.shrink(Math.min(count, current.getCount()));
        furnace.setItem(slot, current.isEmpty() ? ItemStack.EMPTY : current);
    }

    private boolean spawnFurnaceRecoveryEscrow(
        ItemStack stack,
        ActiveWork work,
        FurnaceTransaction transaction,
        int slot,
        String reason
    ) {
        if (stack.isEmpty() || !(npc.level() instanceof ServerLevel level)) return false;
        Vec3 spawn = npc.position().add(0.0D, 0.35D, 0.0D);
        ItemEntity escrow = new ItemEntity(level, spawn.x, spawn.y, spawn.z, stack);
        escrow.setThrower(npc.getUUID());
        escrow.setTarget(npc.getUUID());
        escrow.setPickUpDelay(0);
        escrow.setUnlimitedLifetime();
        escrow.setInvulnerable(true);
        escrow.getPersistentData().putBoolean(FURNACE_RECOVERY_TAG, true);
        escrow.getPersistentData().putUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG, npc.getUUID());
        escrow.getPersistentData().putString(FURNACE_RECOVERY_TASK_TAG, boundedRecoveryText(work.id, 160));
        escrow.getPersistentData().putString(
            FURNACE_RECOVERY_DIMENSION_TAG,
            boundedRecoveryText(transaction.dimensionId, 160)
        );
        escrow.getPersistentData().putLong(FURNACE_RECOVERY_POSITION_TAG, transaction.position.asLong());
        escrow.getPersistentData().putInt(FURNACE_RECOVERY_SLOT_TAG, slot);
        escrow.getPersistentData().putString(FURNACE_RECOVERY_REASON_TAG, boundedRecoveryText(reason, 80));
        return level.addFreshEntity(escrow);
    }

    private ServerLevel furnaceLevel(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank() || npc.level().getServer() == null) return null;
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) return null;
        return npc.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private String currentDimensionId() {
        return npc.level().dimension().location().toString();
    }

    private static int saturatingAdd(int left, int right) {
        long value = (long) Math.max(0, left) + Math.max(0, right);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static String boundedRecoveryText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private boolean prepareSmeltingInputTool(ActiveWork work, String inputId) {
        String requiredTool = SmeltingPrerequisitePolicy.requiredPickaxe(inputId);
        if (requiredTool.isBlank()) return false;
        BlockState probe = smeltingInputProbeState(inputId);
        if (probe != null && hasUsablePickaxeFor(probe)) return false;
        return prepareCraftedToolPrerequisite(
            work,
            requiredTool,
            "采集 " + inputId + " 前先准备 " + requiredTool,
            "烧炼原料需要 " + requiredTool + "，但缺少可取得的制作材料"
        );
    }

    private boolean prepareSmeltingWorkstation(ActiveWork work, String workstationId) {
        if (!workstationId.equals("minecraft:furnace") || npc.creativeResources()
            || inventoryCount(workstationId) > 0) return false;
        int availableStone = inventoryCount("#minecraft:stone_crafting_materials");
        int missingStone = SmeltingPrerequisitePolicy.missingFurnaceStone(availableStone);
        if (missingStone <= 0) return false;
        if (!hasUsablePickaxeForStone() && prepareCraftedToolPrerequisite(
            work,
            "minecraft:wooden_pickaxe",
            "挖圆石制作熔炉前先准备木镐",
            "制作熔炉需要先准备木镐，但缺少可取得的制作材料"
        )) return true;
        // Gather the furnace recipe tag rather than only surface cobblestone.
        // At deep-mining height ordinary deepslate drops cobbled deepslate,
        // which is equally valid and usually the nearest available material.
        beginCraftGather(work, "#minecraft:stone_crafting_materials", missingStone,
            "缺少熔炉，先挖 " + missingStone + " 个圆石");
        return true;
    }

    private boolean prepareCraftedToolPrerequisite(
        ActiveWork work,
        String toolId,
        String progressMessage,
        String failureMessage
    ) {
        return prepareCraftedToolPrerequisite(
            work,
            toolId,
            progressMessage,
            failureMessage,
            "SMELT_TOOL_RECIPE_MISSING",
            "SMELT_TOOL_MATERIALS_MISSING"
        );
    }

    private boolean prepareCraftedToolPrerequisite(
        ActiveWork work,
        String toolId,
        String progressMessage,
        String failureMessage,
        String recipeMissingCode,
        String materialsMissingCode
    ) {
        Recipe<?> recipe = findCraftRecipe(toolId);
        if (recipe == null) {
            fail(work, "没有找到 " + toolId + " 的制作配方", recipeMissingCode);
            return true;
        }
        if (toolId.endsWith("_pickaxe") && tickMiningInventoryCleanup(work, recipe, toolId)) return true;
        if (prepareCraftPrerequisite(work, toolId, recipe)) return true;
        if (craftOnePrerequisite(work, toolId, progressMessage)) return true;
        if (prepareRecipeMaterialAcquisition(work, toolId, recipe)) return true;
        fail(work, failureMessage, materialsMissingCode);
        return true;
    }

    /**
     * Starts the persistent material-goal stack for a recipe ingredient that
     * cannot be produced from the NPC's current inventory. Craft and build
     * tasks share this stack so storage lookup, smelting, gathering,
     * interruption and reload recovery use one execution path.
     */
    private boolean prepareRecipeMaterialAcquisition(ActiveWork work, String outputId, Recipe<?> recipe) {
        Ingredient missing = firstMissingIngredient(recipe.getIngredients());
        if (missing == null) return false;
        String materialContextId = materialContextItemId(work, outputId);
        String ingredientId = selectBuildIngredientItem(materialContextId, missing, List.of());
        if (ingredientId.isBlank()) return false;

        ItemStack candidate = new ItemStack(item(ingredientId));
        int requiredForBatch = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty() && ingredient.test(candidate)) requiredForBatch++;
        }
        int available = inventoryCount(ingredientId);
        int batches = work.kind.equals("craft") && outputId.equals(work.outputItemId)
            ? CraftBatchPolicy.remainingBatches(
                work.requestedCount,
                work.completed,
                recipe.getResultItem(npc.level().registryAccess()).getCount()
            )
            : 1;
        int target = CraftBatchPolicy.ingredientTarget(available, requiredForBatch, batches);
        return beginBuildMaterialGoal(
            work,
            ingredientId,
            ingredientId,
            target,
            "制作 " + outputId + " 前先取得配方材料 " + ingredientId,
            materialContextId
        );
    }

    private boolean prepareGatherTool(ActiveWork work, BlockState targetState, String itemId) {
        String toolId = requiredGatherTool(targetState);
        if (toolId.isBlank()) {
            fail(work, "采集 " + itemId + " 需要正确工具，但没有找到可用的原版工具", "GATHER_TOOL_UNSUPPORTED");
            return true;
        }
        return prepareCraftedToolPrerequisite(
            work,
            toolId,
            "采集 " + itemId + " 前先准备 " + toolId,
            "采集 " + itemId + " 需要 " + toolId + "，但缺少可取得的制作材料",
            "GATHER_TOOL_RECIPE_MISSING",
            "GATHER_TOOL_MATERIALS_MISSING"
        );
    }

    private boolean prepareKnownGatherTool(ActiveWork work, String itemId) {
        String toolId = GatherToolPolicy.requiredPickaxe(itemId);
        if (toolId.isBlank()) return false;
        BlockState probe = gatherProbeState(itemId);
        if (probe != null && hasUsablePickaxeFor(probe)) return false;
        return prepareCraftedToolPrerequisite(
            work,
            toolId,
            "搜索 " + itemId + " 前先准备 " + toolId,
            "采集 " + itemId + " 需要 " + toolId + "，但缺少可取得的制作材料",
            "GATHER_TOOL_RECIPE_MISSING",
            "GATHER_TOOL_MATERIALS_MISSING"
        );
    }

    private BlockState gatherProbeState(String itemId) {
        return switch (itemId) {
            case "minecraft:cobblestone", "minecraft:stone" -> Blocks.STONE.defaultBlockState();
            case "minecraft:coal", "minecraft:coal_ore", "minecraft:deepslate_coal_ore" ->
                Blocks.COAL_ORE.defaultBlockState();
            case "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore" ->
                Blocks.IRON_ORE.defaultBlockState();
            case "minecraft:raw_copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore" ->
                Blocks.COPPER_ORE.defaultBlockState();
            case "minecraft:lapis_lazuli", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore" ->
                Blocks.LAPIS_ORE.defaultBlockState();
            case "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore" ->
                Blocks.GOLD_ORE.defaultBlockState();
            case "minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore" ->
                Blocks.DIAMOND_ORE.defaultBlockState();
            case "minecraft:emerald", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore" ->
                Blocks.EMERALD_ORE.defaultBlockState();
            case "minecraft:redstone", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore" ->
                Blocks.REDSTONE_ORE.defaultBlockState();
            case "minecraft:nether_quartz", "minecraft:nether_quartz_ore" ->
                Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
            case "minecraft:obsidian" -> Blocks.OBSIDIAN.defaultBlockState();
            case "minecraft:ancient_debris" -> Blocks.ANCIENT_DEBRIS.defaultBlockState();
            default -> null;
        };
    }

    private String requiredGatherTool(BlockState targetState) {
        List<String> kinds = targetState.is(BlockTags.MINEABLE_WITH_PICKAXE) ? List.of("pickaxe")
            : targetState.is(BlockTags.MINEABLE_WITH_AXE) ? List.of("axe")
            : targetState.is(BlockTags.MINEABLE_WITH_SHOVEL) ? List.of("shovel")
            : targetState.is(BlockTags.MINEABLE_WITH_HOE) ? List.of("hoe")
            : List.of("pickaxe", "axe", "shovel", "hoe", "sword");
        for (String tier : List.of("wooden", "stone", "iron", "diamond")) {
            for (String kind : kinds) {
                String itemId = "minecraft:" + tier + "_" + kind;
                Item candidate = item(itemId);
                if (candidate != Items.AIR && new ItemStack(candidate).isCorrectToolForDrops(targetState)) return itemId;
            }
        }
        return "";
    }

    private BlockState smeltingInputProbeState(String inputId) {
        return switch (inputId) {
            case "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore" ->
                Blocks.IRON_ORE.defaultBlockState();
            case "minecraft:raw_copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore" ->
                Blocks.COPPER_ORE.defaultBlockState();
            case "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore" ->
                Blocks.GOLD_ORE.defaultBlockState();
            case "minecraft:ancient_debris" -> Blocks.ANCIENT_DEBRIS.defaultBlockState();
            default -> null;
        };
    }

    private void tickFarm(ActiveWork work) {
        String cropId = NpcLifeSkillPolicy.cropBlockId(string(work.spec, "cropId", "minecraft:wheat"));
        String action = string(work.spec, "action", "cycle");
        int radius = (int) number(work.spec, "radius", 12);
        if (!work.initialized) {
            work.initialized = true;
            work.requestedCount = Math.max(1, radius);
        }
        if (NpcLifeSkillPolicy.farmTimedOut(work.ticks)) {
            fail(work, "农务任务执行超时，已处理 " + work.completed + " 处", "FARM_TIMEOUT");
            return;
        }
        if (hasCraftGatherPrerequisite(work)) {
            tickCraftGatherPrerequisite(work);
            return;
        }
        if (NpcLifeSkillPolicy.mayTillNewGround(action)
            && !npc.creativeResources()
            && findHoeSlot() < 0
            && !prepareFarmHoe(work)) return;
        if (work.targetBlock == null) {
            work.targetBlock = findFarmTarget(cropId, action, radius);
            if (work.targetBlock == null) {
                if (++work.noWorkTicks >= 40) {
                    if (NpcLifeSkillPolicy.farmMayReportSuccess(work.completed)) {
                        complete(work, "本轮农务已完成，共处理 " + work.completed + " 处");
                    } else {
                        fail(work, "指定范围内没有可执行的农务目标", "FARM_TARGET_NOT_FOUND");
                    }
                }
                return;
            }
            work.noWorkTicks = 0;
        }
        if (!approach(work, work.targetBlock, 3.2, 1.05)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        BlockState state = npc.level().getBlockState(work.targetBlock);
        boolean handled = false;
        if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state) && !action.equals("plant")) {
            int tool = bestToolSlot(state);
            handled = proxy.breakBlock(work.targetBlock, tool >= 0 ? tool : -1);
            npc.absorbNearbyItems(3.0);
            if (handled && action.equals("cycle") && !plantCrop(work.targetBlock, cropId)) {
                handleFarmPlantFailure(work, cropId);
                return;
            }
        } else if (state.isAir() && !action.equals("harvest")) {
            BlockPos ground = work.targetBlock.below();
            BlockState groundState = npc.level().getBlockState(ground);
            if (!(groundState.getBlock() instanceof FarmBlock)) {
                int hoeSlot = findHoeSlot();
                if (hoeSlot < 0 && !npc.creativeResources()) {
                    fail(work, "NPC 背包中没有可用的锄头，无法耕地", "HOE_MISSING");
                    return;
                }
                ItemStack hoe = hoeSlot >= 0
                    ? npc.inventory().getStackInSlot(hoeSlot)
                    : new ItemStack(Items.WOODEN_HOE);
                npc.getLookControl().setLookAt(Vec3.atCenterOf(ground));
                InteractionResult result = proxy.useItemOn(ground, Direction.UP, hoe, hoeSlot);
                handled = result.consumesAction() && npc.level().getBlockState(ground).getBlock() instanceof FarmBlock;
            } else {
                handled = true;
            }
            if (handled && !plantCrop(work.targetBlock, cropId)) {
                handleFarmPlantFailure(work, cropId);
                return;
            }
        }
        if (!handled) work.failedActions++;
        else {
            work.completed++;
            npc.swing(InteractionHand.MAIN_HAND);
            npc.addExhaustion(0.04F);
        }
        work.targetBlock = null;
        progress(work, Math.min(0.95, work.completed / (double) Math.max(1, radius)), "已处理 " + work.completed + " 处农作物");
    }

    private void handleFarmPlantFailure(ActiveWork work, String cropId) {
        String seedId = NpcLifeSkillPolicy.seedItemId(cropId);
        if (npc.creativeResources() || inventoryCount(seedId) > 0) {
            fail(work, "播种交互被世界保护或其他模组拒绝：" + seedId, "CROP_PLANT_DENIED");
            return;
        }
        prepareFarmSeedPrerequisite(work, seedId);
    }

    /** Replenishes seeds inside the original farm task instead of reporting a false success. */
    private void prepareFarmSeedPrerequisite(ActiveWork work, String seedId) {
        if (seedId == null || seedId.isBlank() || item(seedId) == Items.AIR) {
            fail(work, "无法解析需要补充的种子：" + seedId, "SEEDS_UNAVAILABLE");
            return;
        }
        BuildMaterialPrerequisitePolicy.MaterialPlan plan = BuildMaterialPrerequisitePolicy.plan(seedId);
        if (plan.action() == BuildMaterialPrerequisitePolicy.Action.GATHER) {
            beginCraftGather(work, plan.gatherSelector(), 1, "种子用完，正在补充 " + seedId);
            return;
        }
        if (plan.action() == BuildMaterialPrerequisitePolicy.Action.CRAFT) {
            if (craftOnePrerequisite(work, seedId, "已制作播种所需种子")) return;
            if (active != work) return;
            Recipe<?> recipe = findCraftRecipe(seedId);
            Ingredient missing = recipe == null ? null : firstMissingIngredient(recipe.getIngredients());
            if (missing != null) {
                String upstreamId = selectBuildIngredientItem(
                    seedId,
                    missing,
                    plan.upstreamRequirements()
                );
                BuildMaterialPrerequisitePolicy.MaterialPlan upstream = upstreamId.isBlank()
                    ? null
                    : BuildMaterialPrerequisitePolicy.plan(upstreamId);
                if (upstream != null && upstream.action() == BuildMaterialPrerequisitePolicy.Action.GATHER) {
                    beginCraftGather(work, upstream.gatherSelector(), 1,
                        "制作 " + seedId + " 前先采集 " + upstream.gatherSelector());
                    return;
                }
            }
        }
        fail(work, "无法安全自动补充播种所需的 " + seedId, "SEEDS_UNAVAILABLE");
    }

    private boolean prepareFarmHoe(ActiveWork work) {
        if (findHoeSlot() >= 0) return true;
        if (work.recipe == null) {
            Recipe<?> fallback = findCraftRecipe("minecraft:wooden_hoe");
            for (String candidateId : List.of("minecraft:iron_hoe", "minecraft:stone_hoe", "minecraft:wooden_hoe")) {
                Recipe<?> candidate = findCraftRecipe(candidateId);
                if (candidate != null && allocateIngredients(candidate.getIngredients()) != null) {
                    work.recipe = candidate;
                    break;
                }
            }
            if (work.recipe == null) work.recipe = fallback;
            if (work.recipe == null) {
                fail(work, "没有找到可制作锄头的配方", "HOE_RECIPE_MISSING");
                return false;
            }
            work.requiresTable = !work.recipe.canCraftInDimensions(2, 2);
        }
        List<Integer> ingredients = allocateIngredients(work.recipe.getIngredients());
        if (ingredients == null) {
            if (craftMissingIngredient(work, work.recipe, 0, new HashSet<>())) return false;
            fail(work, "缺少制作锄头所需的真实材料", "HOE_MATERIALS_MISSING");
            return false;
        }
        if (work.requiresTable && !prepareCraftingWorkstation(
            work,
            itemId(work.recipe.getResultItem(npc.level().registryAccess()))
        )) return false;
        if (work.ticks - work.lastActionTick < 8) return false;
        ItemStack output = work.recipe.getResultItem(npc.level().registryAccess()).copy();
        if (output.isEmpty() || !itemId(output).endsWith("_hoe")) {
            fail(work, "锄头配方没有生成有效工具", "HOE_RECIPE_INVALID");
            return false;
        }
        if (!canInsert(output)) {
            fail(work, "NPC 背包没有空间接收新锄头", "INVENTORY_FULL");
            return false;
        }
        consumeIngredients(ingredients);
        npc.insert(output);
        recordInventoryAction(work, "craft-output");
        npc.swing(InteractionHand.MAIN_HAND);
        npc.addExhaustion(0.03F);
        work.lastActionTick = work.ticks;
        work.recipe = null;
        work.requiresTable = false;
        progress(work, activeProgress(work), "已制作农耕所需的 " + itemId(output));
        return findHoeSlot() >= 0;
    }

    private void tickStore(ActiveWork work) {
        String requestedId = work.spec.has("itemId") && !work.spec.get("itemId").isJsonNull()
            ? work.spec.get("itemId").getAsString()
            : null;
        int radius = integer(work.spec, "radius", HomeStoragePolicy.DEFAULT_RADIUS);
        if (!work.initialized || work.recipe == null) {
            work.initialized = true;
            int available = requestedId == null ? organizableInventoryCount() : inventoryCount(requestedId);
            work.requestedCount = work.spec.has("count") && !work.spec.get("count").isJsonNull()
                ? work.spec.get("count").getAsInt()
                : available;
            if (work.requestedCount <= 0 || available < work.requestedCount) {
                fail(work, "NPC 背包中没有足够的待整理物品", "MISSING_ITEMS");
                return;
            }
            work.workstation = findHomeStorage(null, false, work, radius);
        }
        if (work.workstation == null) {
            work.workstation = findHomeStorage(null, false, work, radius);
        }
        if (work.workstation == null) {
            work.workstation = createHomeStorageIfPossible(work, radius);
            if (work.workstation == null) return;
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.05)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = findHomeStorage(null, false, work, radius);
            if (work.workstation == null) work.workstation = createHomeStorageIfPossible(work, radius);
            return;
        }
        int moved = requestedId == null
            ? depositOrganizable(container, work.requestedCount - work.completed)
            : deposit(container, requestedId, work.requestedCount - work.completed);
        work.completed += moved;
        if (moved > 0) container.setChanged();
        if (work.completed >= work.requestedCount) {
            complete(work, "已把 " + work.completed + " 个物品整理回家中仓库");
            return;
        }
        work.skippedStorageTargets.add(work.workstation);
        work.workstation = findHomeStorage(null, false, work, radius);
        if (work.workstation == null) work.workstation = createHomeStorageIfPossible(work, radius);
        progress(work, work.completed / (double) work.requestedCount, "已存放 " + work.completed + "/" + work.requestedCount);
    }

    private void tickRetrieve(ActiveWork work) {
        String requestedId = string(work.spec, "itemId", "");
        int radius = integer(work.spec, "radius", HomeStoragePolicy.DEFAULT_RADIUS);
        if (!work.initialized) {
            ResourceSelector selector = ResourceSelector.parse(requestedId);
            work.initialized = true;
            work.requestedCount = integer(work.spec, "count", 1);
            HomeRetrieveInspection inspection = inspectHomeRetrieve(selector, work.requestedCount, radius);
            HomeStoragePolicy.RetrievalDecision decision = HomeStoragePolicy.retrievalDecision(
                inspection.available(), work.requestedCount, inspection.inventoryFits()
            );
            if (decision == HomeStoragePolicy.RetrievalDecision.ITEMS_MISSING) {
                String message = inspection.available() <= 0
                    ? "家中仓库没有找到 " + requestedId
                    : "家中仓库只有 " + inspection.available() + " 个 " + requestedId
                        + "，不足任务要求的 " + work.requestedCount + " 个";
                fail(work, message, inspection.available() <= 0 ? "STORAGE_ITEM_NOT_FOUND" : "STORAGE_ITEM_INSUFFICIENT");
                return;
            }
            if (decision == HomeStoragePolicy.RetrievalDecision.INVENTORY_FULL) {
                fail(work, "NPC 背包没有足够空间一次取出 " + work.requestedCount + " 个 " + requestedId, "INVENTORY_FULL");
                return;
            }
            work.workstation = findHomeStorage(selector, true, work, radius);
        }
        if (work.workstation == null) {
            work.workstation = findHomeStorage(ResourceSelector.parse(requestedId), true, work, radius);
            if (work.workstation == null) {
                boolean unreachable = !work.skippedStorageTargets.isEmpty();
                fail(work, unreachable
                    ? "家中含目标物品的容器均不可达"
                    : "家中仓库内容在执行期间发生变化",
                    unreachable ? "STORAGE_NOT_REACHABLE" : "STORAGE_CONTENT_CHANGED");
                return;
            }
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = findHomeStorage(ResourceSelector.parse(requestedId), true, work, radius);
            return;
        }
        int moved = withdrawReachableHomeStorage(
            work,
            ResourceSelector.parse(requestedId),
            requestedId,
            work.requestedCount - work.completed,
            radius,
            work.workstation,
            3.5D
        );
        work.completed += moved;
        if (work.completed >= work.requestedCount) {
            complete(work, "已从家中取出 " + work.completed + " 个 " + requestedId);
            return;
        }
        work.workstation = findHomeStorage(ResourceSelector.parse(requestedId), true, work, radius);
        if (work.workstation == null) {
            fail(work, "剩余目标物品所在的家中容器均不可达", "STORAGE_NOT_REACHABLE");
            return;
        }
        progress(work, work.completed / (double) work.requestedCount, "已取出 " + work.completed + "/" + work.requestedCount);
    }

    private int withdrawReachableHomeStorage(
        ActiveWork work,
        ResourceSelector selector,
        String requestedId,
        int requested,
        int radius,
        BlockPos primary,
        double reach
    ) {
        ServerPlayer owner = npc.owner();
        if (requested <= 0 || owner == null || !(npc.level() instanceof ServerLevel level)) return 0;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        List<BlockPos> candidates = new ArrayList<>(NpcHomeStorage.findContainers(level, home, radius));
        candidates.sort(Comparator
            .comparingInt((BlockPos position) -> position.equals(primary) ? 0 : 1)
            .thenComparingDouble(position -> npc.position().distanceToSqr(Vec3.atCenterOf(position))));

        int moved = 0;
        int processed = 0;
        for (BlockPos position : candidates) {
            if (moved >= requested || work.skippedStorageTargets.contains(position)) continue;
            double distance = npc.position().distanceTo(Vec3.atCenterOf(position));
            if (!HomeStoragePolicy.mayBatchRetrieve(distance, reach, processed)) continue;
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container candidate)
                || blockEntity instanceof AbstractFurnaceBlockEntity
                || !containerContains(candidate, selector)) continue;

            int accepted = withdraw(candidate, requestedId, requested - moved);
            work.skippedStorageTargets.add(position.immutable());
            processed++;
            if (accepted <= 0) continue;
            candidate.setChanged();
            moved += accepted;
        }
        if (processed > 0) npc.swing(InteractionHand.MAIN_HAND);
        return moved;
    }

    private void tickOrganizeStorage(ActiveWork work) {
        int radius = integer(work.spec, "radius", HomeStoragePolicy.DEFAULT_RADIUS);
        if (!work.initialized) {
            work.initialized = true;
            work.requestedCount = organizableInventoryCount();
            if (work.requestedCount <= 0) {
                complete(work, "背包中没有需要自动入库的物品");
                return;
            }
            work.workstation = findHomeStorage(null, false, work, radius);
        }
        if (work.workstation == null) {
            work.workstation = findHomeStorage(null, false, work, radius);
        }
        if (work.workstation == null) {
            work.workstation = createHomeStorageIfPossible(work, radius);
            if (work.workstation == null) return;
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = findHomeStorage(null, false, work, radius);
            if (work.workstation == null) fail(work, "家中仓库容器已不存在", "CONTAINER_MISSING");
            return;
        }
        StorageSortResult sorted = organizeHomeStorageTransactional(radius);
        if (sorted == StorageSortResult.SUCCEEDED) {
            work.completed = work.requestedCount;
            complete(work, "已把 " + work.completed + " 个多余物品按类别整理回家中仓库");
            return;
        }
        if (sorted == StorageSortResult.UNAVAILABLE) {
            fail(work, "家中仓库容器已不可用", "CONTAINER_MISSING");
            return;
        }
        if (npc.level() instanceof ServerLevel level && npc.owner() != null) {
            NpcHomeStorage.Home home = NpcHomeStorage.resolve(npc.owner());
            work.skippedStorageTargets.addAll(NpcHomeStorage.findContainers(level, home, radius));
        }
        work.workstation = createHomeStorageIfPossible(work, radius);
    }

    private void tickDeliver(ActiveWork work) {
        String requestedId = string(work.spec, "itemId", "");
        int requested = integer(work.spec, "count", 1);
        String playerName = string(work.spec, "player", "");
        if (!work.initialized) {
            ResourceSelector.parse(requestedId);
            work.initialized = true;
            work.requestedCount = requested;
            if (inventoryCount(requestedId) < requested) {
                fail(work, "NPC 背包中没有足够的 " + requestedId, "MISSING_ITEMS");
                return;
            }
        }

        ServerPlayer recipient = findRecipientPlayer(playerName);
        if (recipient == null || !recipient.isAlive()) {
            if (++work.noWorkTicks >= 100) fail(work, "没有找到在线玩家 " + playerName, "PLAYER_NOT_FOUND");
            return;
        }
        if (recipient.level() != npc.level()) {
            fail(work, "玩家 " + playerName + " 位于其他维度", "PLAYER_NOT_REACHABLE");
            return;
        }
        work.noWorkTicks = 0;
        if (!approach(work, recipient, 3.2, 1.15)) return;
        if (work.ticks - work.lastActionTick < 4) return;
        work.lastActionTick = work.ticks;

        int moved = throwItems(requestedId, requested - work.completed, recipient);
        work.completed += moved;
        if (work.completed >= requested) {
            complete(work, "已把 " + work.completed + " 个 " + requestedId + " 丢给 " + recipient.getGameProfile().getName());
            return;
        }
        if (moved <= 0) {
            fail(work, "无法从 NPC 背包取出待交付物品", "DELIVERY_FAILED");
            return;
        }
        progress(work, work.completed / (double) requested, "已交付 " + work.completed + "/" + requested);
    }

    private void tickDrop(ActiveWork work) {
        String requestedId = string(work.spec, "itemId", "");
        int requested = integer(work.spec, "count", 1);
        String playerName = string(work.spec, "player", "");
        if (!work.initialized) {
            ResourceSelector.parse(requestedId);
            work.initialized = true;
            work.requestedCount = requested;
            if (inventoryCount(requestedId) < requested) {
                fail(work, "NPC 背包中没有足够的 " + requestedId, "MISSING_ITEMS");
                return;
            }
        }

        ServerPlayer recipient = playerName.isBlank() ? null : findRecipientPlayer(playerName);
        if (!playerName.isBlank() && (recipient == null || !recipient.isAlive())) {
            if (++work.noWorkTicks >= 100) fail(work, "没有找到在线玩家 " + playerName, "PLAYER_NOT_FOUND");
            return;
        }
        if (recipient != null) {
            if (recipient.level() != npc.level()) {
                fail(work, "玩家 " + playerName + " 位于其他维度", "PLAYER_NOT_REACHABLE");
                return;
            }
            if (!approach(work, recipient, 3.2, 1.15)) return;
        }
        if (work.ticks - work.lastActionTick < 4) return;
        work.lastActionTick = work.ticks;
        int moved = throwItems(requestedId, requested - work.completed, recipient);
        work.completed += moved;
        if (work.completed >= requested) {
            complete(work, recipient == null
                ? "已丢出 " + work.completed + " 个 " + requestedId
                : "已把 " + work.completed + " 个 " + requestedId + " 丢给 " + recipient.getGameProfile().getName());
        } else if (moved <= 0) {
            fail(work, "无法从 NPC 背包取出待丢弃物品", "DROP_FAILED");
        } else {
            progress(work, work.completed / (double) requested, "已丢出 " + work.completed + "/" + requested);
        }
    }

    private void tickFish(ActiveWork work) {
        if (!(npc.level() instanceof ServerLevel level)) {
            fail(work, "钓鱼任务只能在服务端世界执行", "WORLD_UNAVAILABLE");
            return;
        }
        if (!work.initialized) {
            work.initialized = true;
            work.requestedCount = NpcLifeSkillPolicy.clampFishingCatches(integer(work.spec, "count", 1));
        }
        if (hasCraftGatherPrerequisite(work)) {
            tickCraftGatherPrerequisite(work);
            return;
        }
        int rodSlot = findFishingRodSlot();
        if (rodSlot < 0 && npc.creativeResources() && canInsert(new ItemStack(Items.FISHING_ROD))) {
            npc.insert(new ItemStack(Items.FISHING_ROD));
            rodSlot = findFishingRodSlot();
        }
        if (rodSlot < 0) {
            tickFishingRodSupply(work);
            return;
        }
        finishFishingHomeSupply(work);
        if (!isFishableWater(work.workstation)) {
            work.workstation = findFishingWater(integer(work.spec, "radius", 24));
            if (work.workstation == null) {
                fail(work, "附近没有可抛竿的水面", "FISHING_WATER_NOT_FOUND");
                return;
            }
        }
        if (!approach(work, work.workstation, 4.5, 1.0)) return;

        if (rodSlot != CodexNpcEntity.MAIN_HAND_SLOT) equipMainHand(rodSlot);
        rodSlot = CodexNpcEntity.MAIN_HAND_SLOT;
        ItemStack rod = npc.inventory().getStackInSlot(rodSlot);

        if (!work.fishingCast) {
            npc.getLookControl().setLookAt(Vec3.atCenterOf(work.workstation).add(0, 0.4, 0));
            npc.swing(InteractionHand.MAIN_HAND);
            if (!proxy.castFishing(rodSlot)) {
                if (++work.failedActions >= 3) fail(work, "无法在当前水面抛竿", "FISHING_CAST_FAILED");
                return;
            }
            work.fishingCast = true;
            long seed = level.getSeed() ^ npc.getUUID().getLeastSignificantBits();
            work.fishingReadyTick = work.ticks + NpcLifeSkillPolicy.fishingWaitTicks(seed, work.completed);
            progress(work, work.completed / (double) work.requestedCount, "已抛竿，正在等待鱼儿上钩");
            return;
        }

        if (work.ticks < work.fishingReadyTick) {
            if (work.ticks % 100 == 0) {
                progress(work, work.completed / (double) work.requestedCount,
                    "钓鱼中 " + work.completed + "/" + work.requestedCount);
            }
            return;
        }

        proxy.cancelFishing();
        work.fishingCast = false;
        work.fishingReadyTick = 0;
        rod.hurtAndBreak(1, npc, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        npc.inventory().setStackInSlot(rodSlot, rod);
        List<ItemStack> loot = rollFishingLoot(level, work.workstation, rod);
        StringJoiner caught = new StringJoiner("、");
        for (ItemStack stack : loot) {
            if (stack.isEmpty()) continue;
            caught.add(stack.getHoverName().getString() + "×" + stack.getCount());
            ItemStack remainder = npc.insert(stack.copy());
            if (!remainder.isEmpty()) spawnThrownStack(remainder, null);
        }
        work.completed++;
        npc.addExhaustion(0.04F);
        String catchMessage = caught.length() == 0 ? "这次没有钓到物品" : "钓到了 " + caught;
        if (work.completed >= work.requestedCount) {
            complete(work, catchMessage + "；共完成 " + work.completed + " 次垂钓");
        } else {
            progress(work, work.completed / (double) work.requestedCount,
                catchMessage + "，准备下一竿 " + work.completed + "/" + work.requestedCount);
        }
    }

    private void tickFishingRodSupply(ActiveWork work) {
        if (work.fishingStoragePhase < FishingRodPrerequisitePolicy.storagePhaseCount()) {
            tickFishingHomeSupply(work);
            return;
        }
        int missingString = FishingRodPrerequisitePolicy.missingString(inventoryCount("minecraft:string"));
        if (missingString > 0) {
            if (findUsableSwordSlot() < 0) {
                prepareCraftedToolPrerequisite(
                    work,
                    "minecraft:wooden_sword",
                    "取得鱼竿用线前先制作木剑",
                    "制作鱼竿需要线，但无法制作采集蜘蛛网所需的木剑",
                    "FISHING_STRING_TOOL_RECIPE_MISSING",
                    "FISHING_STRING_TOOL_MATERIALS_MISSING"
                );
                return;
            }
            beginCraftGather(
                work,
                "minecraft:string",
                missingString,
                "制作鱼竿缺少 " + missingString + " 根线，正在扩大范围寻找蜘蛛网"
            );
            return;
        }
        Recipe<?> recipe = findCraftRecipe("minecraft:fishing_rod");
        if (recipe == null) {
            fail(work, "没有找到原版钓鱼竿制作配方", "FISHING_ROD_RECIPE_MISSING");
            return;
        }
        if (prepareCraftPrerequisite(work, "minecraft:fishing_rod", recipe)) return;
        if (craftOnePrerequisite(work, "minecraft:fishing_rod", "已制作钓鱼竿，准备寻找水面")) return;
        fail(work, "钓鱼竿依赖链无法补齐", "FISHING_ROD_MATERIALS_MISSING");
    }

    private boolean tickDirectFishingRodPrerequisites(ActiveWork work) {
        if (npc.creativeResources()) return false;
        int rods = CraftBatchPolicy.remainingBatches(
            Math.max(1, work.requestedCount),
            work.completed,
            1
        );
        if (FishingRodPrerequisitePolicy.directIngredientsReady(
            inventoryCount("minecraft:stick"),
            inventoryCount("minecraft:string"),
            rods
        )) {
            finishFishingHomeSupply(work);
            return false;
        }
        // Phase 0 belongs to the fish action, where an already finished rod is
        // acceptable. A direct craft request must actually complete its own
        // recipe, so it starts by looking for ingredients instead.
        if (work.fishingStoragePhase < 1) work.fishingStoragePhase = 1;
        if (work.fishingStoragePhase < FishingRodPrerequisitePolicy.storagePhaseCount()) {
            tickFishingHomeSupply(work);
            return true;
        }
        int missingString = FishingRodPrerequisitePolicy.missingString(
            inventoryCount("minecraft:string"),
            rods
        );
        if (missingString <= 0) return false;
        if (findUsableSwordSlot() < 0) {
            prepareCraftedToolPrerequisite(
                work,
                "minecraft:wooden_sword",
                "取得鱼竿用线前先制作木剑",
                "制作鱼竿需要线，但无法制作采集蜘蛛网所需的木剑",
                "FISHING_STRING_TOOL_RECIPE_MISSING",
                "FISHING_STRING_TOOL_MATERIALS_MISSING"
            );
            return true;
        }
        beginCraftGather(
            work,
            "minecraft:string",
            missingString,
            "制作钓鱼竿缺少 " + missingString + " 根线，正在扩大范围寻找蜘蛛网"
        );
        return true;
    }

    private void tickFishingHomeSupply(ActiveWork work) {
        FishingRodPrerequisitePolicy.Supply supply =
            FishingRodPrerequisitePolicy.storageSupply(work.fishingStoragePhase);
        if (supply == null) {
            finishFishingHomeSupply(work);
            return;
        }
        if (inventoryCount(supply.selector()) >= supply.required()) {
            advanceFishingHomeSupply(work);
            return;
        }
        if (work.workstation == null) {
            work.workstation = findHomeStorage(
                ResourceSelector.parse(supply.selector()),
                true,
                work,
                HomeStoragePolicy.DEFAULT_RADIUS
            );
            if (work.workstation == null) {
                advanceFishingHomeSupply(work);
                return;
            }
            progress(work, activeProgress(work), "家中箱子找到" + supply.label() + "，正在前往取出");
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
            return;
        }
        int moved = withdraw(
            container,
            supply.selector(),
            supply.required() - inventoryCount(supply.selector())
        );
        container.setChanged();
        work.skippedStorageTargets.add(work.workstation);
        work.workstation = null;
        if (moved > 0) {
            npc.swing(InteractionHand.MAIN_HAND);
            progress(work, activeProgress(work), "已从家中箱子取出 " + moved + " 个" + supply.label());
        }
        if (inventoryCount(supply.selector()) >= supply.required()) advanceFishingHomeSupply(work);
    }

    private void advanceFishingHomeSupply(ActiveWork work) {
        work.fishingStoragePhase++;
        work.workstation = null;
        work.skippedStorageTargets.clear();
        work.stalledTicks = 0;
        work.lastDistance = -1;
    }

    private void finishFishingHomeSupply(ActiveWork work) {
        if (work.fishingStoragePhase >= FishingRodPrerequisitePolicy.storagePhaseCount()) return;
        work.fishingStoragePhase = FishingRodPrerequisitePolicy.storagePhaseCount();
        work.workstation = null;
        work.skippedStorageTargets.clear();
        work.stalledTicks = 0;
        work.lastDistance = -1;
    }

    private void tickSleep(ActiveWork work) {
        if (!(npc.level() instanceof ServerLevel level)) {
            fail(work, "睡觉任务只能在服务端世界执行", "WORLD_UNAVAILABLE");
            return;
        }
        if (!work.initialized) {
            work.initialized = true;
            work.requestedCount = 1;
            if (level.isDay()) {
                complete(work, "现在已经是白天，无需睡觉");
                return;
            }
            work.workstation = findSleepBed(integer(work.spec, "radius", 32));
        }

        boolean danger = nearestHostile(npc, 8.0, "hostile") != null;
        NpcLifeSkillPolicy.SleepDecision decision = NpcLifeSkillPolicy.sleepDecision(
            level.isDay(), work.workstation != null && level.getBlockState(work.workstation).is(BlockTags.BEDS), danger
        );
        if (decision == NpcLifeSkillPolicy.SleepDecision.ALREADY_DAY) {
            work.completed = 1;
            complete(work, "天亮了，已经起床");
            return;
        }
        if (decision == NpcLifeSkillPolicy.SleepDecision.BED_MISSING) {
            fail(work, "家或附近没有可用的床", "BED_NOT_FOUND");
            return;
        }
        if (decision == NpcLifeSkillPolicy.SleepDecision.DANGER_NEARBY) {
            fail(work, "附近有怪物，暂时无法安全入睡", "BED_NOT_SAFE");
            return;
        }
        if (!approach(work, work.workstation, 2.2, 1.0)) return;
        if (!npc.isSleeping()) {
            npc.startSleeping(work.workstation);
            work.lastActionTick = work.ticks;
            npc.setStatus("正在床上睡觉，等待天亮");
            progress(work, 0.5, "已经躺下，正在等待天亮");
        }
        int restedTicks = Math.max(0, work.ticks - work.lastActionTick);
        if (NpcLifeSkillPolicy.shouldSkipSinglePlayerNight(
            level.getServer().getPlayerCount(), level.dimensionType().natural(), restedTicks
        )) {
            long dayTime = level.getDayTime();
            level.setDayTime(dayTime + (24_000L - Math.floorMod(dayTime, 24_000L)));
            return;
        }
        if (NpcLifeSkillPolicy.shouldReportSleepProgress(restedTicks)) {
            double fraction = Math.min(0.9D, 0.5D + restedTicks / (double) (NpcLifeSkillPolicy.MAX_SLEEP_TICKS * 2));
            progress(work, fraction, "仍在休息；多人世界需要其他玩家也入睡才能跳过夜晚");
        }
        if (NpcLifeSkillPolicy.sleepTimedOut(restedTicks)) {
            fail(work, "等待其他玩家入睡超时，已结束本次睡眠", "SLEEP_TIMEOUT");
        }
    }

    private void tickRanch(ActiveWork work) {
        String action = string(work.spec, "action", "establish");
        String animalType = string(work.spec, "animalType", "any");
        if (!work.initialized) {
            work.initialized = true;
            work.requestedCount = Math.max(2, integer(work.spec, "count", 2));
            work.gatherSearchRadius = 16;
            work.lastSearchTick = -10;
        }
        if (hasCraftGatherPrerequisite(work)) {
            tickCraftGatherPrerequisite(work);
            return;
        }

        if (work.ranchPhase == 0 && action.equals("establish") && !prepareRanchLead(work)) return;
        if (!ensureRanchPen(work)) return;
        List<Animal> housed = ranchAnimals(work, work.ranchPenCenter, animalType);
        for (Animal resident : housed) {
            if (!resident.getUUID().equals(work.ranchAnimalTargetId)) {
                work.securedRanchAnimalIds.add(resident.getUUID());
            }
        }
        if (action.equals("establish")) work.completed = Math.min(work.requestedCount, housed.size());

        if (action.equals("breed")) {
            tickRanchBreed(work, housed);
            return;
        }
        if (action.equals("cull")) {
            tickRanchCull(work, housed);
            return;
        }
        if (housed.size() >= work.requestedCount && work.ranchPhase != 2 && work.ranchPhase != 3) {
            if (!ensureRanchGateState(work, false)) return;
            complete(work, "围栏已关闭，已安置 " + housed.size() + " 只牲畜；幼崽和受保护动物均未移动");
            return;
        }

        if (work.ranchPhase == 2) {
            tickRanchReturnWithAnimal(work);
            return;
        }
        if (work.ranchPhase == 3) {
            tickRanchExitAndClose(work, animalType);
            return;
        }
        tickRanchFindAndLeash(work, animalType);
    }

    private boolean prepareRanchLead(ActiveWork work) {
        if (findItemSlot("minecraft:lead") >= 0) {
            work.ranchPhase = 1;
            work.workstation = null;
            work.skippedStorageTargets.clear();
            return true;
        }
        if (!work.ranchLeadStorageChecked) {
            if (work.workstation == null) {
                work.workstation = findHomeStorage(
                    ResourceSelector.parse("minecraft:lead"), true, work, HomeStoragePolicy.DEFAULT_RADIUS
                );
                if (work.workstation == null) {
                    work.ranchLeadStorageChecked = true;
                    work.skippedStorageTargets.clear();
                }
            }
            if (work.workstation != null) {
                if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return false;
                BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
                if (blockEntity instanceof Container container && !(blockEntity instanceof AbstractFurnaceBlockEntity)) {
                    int moved = withdraw(container, "minecraft:lead", 1);
                    container.setChanged();
                    if (moved > 0) {
                        work.ranchPhase = 1;
                        work.workstation = null;
                        work.skippedStorageTargets.clear();
                        progress(work, activeProgress(work), "已从家中箱子取出拴绳，准备寻找牲畜");
                        return true;
                    }
                }
                work.skippedStorageTargets.add(work.workstation);
                work.workstation = null;
                return false;
            }
        }
        if (craftOnePrerequisite(work, "minecraft:lead", "已按真实配方制作拴绳")) return false;
        if (active == work) {
            fail(work, "背包和家中箱子没有拴绳，且缺少制作拴绳所需的线或黏液球", "RANCH_LEAD_MISSING");
        }
        return false;
    }

    private boolean ensureRanchPen(ActiveWork work) {
        if (work.workstation != null && work.ranchPenCenter != null
            && npc.level().getBlockState(work.workstation).getBlock() instanceof FenceGateBlock) return true;
        work.workstation = findBlockAt(
            position -> npc.level().getBlockState(position).getBlock() instanceof FenceGateBlock,
            48,
            12
        );
        if (work.workstation == null) {
            fail(work, "附近没有找到已建成的栅栏门；围栏建造步骤可能尚未完成", "RANCH_PEN_NOT_FOUND");
            return false;
        }
        work.ranchPenCenter = resolveRanchPenCenter(work.workstation);
        if (work.ranchPenCenter == null) {
            work.skippedWorkstationTargets.add(work.workstation);
            fail(work, "栅栏门附近没有形成可识别的封闭围栏", "RANCH_PEN_INVALID");
            return false;
        }
        return true;
    }

    private BlockPos resolveRanchPenCenter(BlockPos gate) {
        List<BlockPos> boundary = ranchFenceBoundary(gate);
        if (boundary.size() < 12) return null;
        int x = (int) Math.round(boundary.stream().mapToInt(BlockPos::getX).average().orElse(gate.getX()));
        int y = (int) Math.round(boundary.stream().mapToInt(BlockPos::getY).average().orElse(gate.getY()));
        int z = (int) Math.round(boundary.stream().mapToInt(BlockPos::getZ).average().orElse(gate.getZ()));
        BlockPos center = new BlockPos(x, y, z);
        return npc.level().getBlockState(center).getCollisionShape(npc.level(), center).isEmpty()
            ? center.immutable()
            : center.above().immutable();
    }

    private List<BlockPos> ranchFenceBoundary(BlockPos gate) {
        List<BlockPos> boundary = new ArrayList<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.add(gate.immutable());
        while (!frontier.isEmpty() && boundary.size() < 256) {
            BlockPos position = frontier.removeFirst();
            if (!visited.add(position) || position.distSqr(gate) > 12 * 12) continue;
            Block block = npc.level().getBlockState(position).getBlock();
            if (!(block instanceof FenceBlock) && !(block instanceof FenceGateBlock)) continue;
            boundary.add(position.immutable());
            for (Direction direction : Direction.Plane.HORIZONTAL) frontier.addLast(position.relative(direction));
            frontier.addLast(position.above());
            frontier.addLast(position.below());
        }
        return boundary;
    }

    private void tickRanchFindAndLeash(ActiveWork work, String animalType) {
        Animal target = resolveRanchAnimalTarget(work, false);
        if (target == null) {
            if (!tickRanchRemoteSearch(work, animalType)) return;
            target = resolveRanchAnimalTarget(work, false);
            if (target == null) return;
        }
        if (!prepareNpcOutsideClosedPen(work)) return;
        if (!approachRanchAnimal(work, target, 2.8D, 1.15D)) return;
        int leadSlot = findItemSlot("minecraft:lead");
        if (leadSlot < 0) {
            npc.absorbNearbyItemsAt(npc.position(), 6.0D);
            leadSlot = findItemSlot("minecraft:lead");
        }
        if (leadSlot < 0) {
            fail(work, "牵引前找不到可用拴绳", "RANCH_LEAD_LOST");
            return;
        }
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        if (!proxy.leashToNpc(target, leadSlot)) {
            if (++work.failedActions >= 3) {
                work.skippedRanchAnimalTargets.add(target.getUUID());
                work.ranchAnimalTargetId = null;
                work.failedActions = 0;
            }
            return;
        }
        work.failedActions = 0;
        work.ranchPhase = 2;
        work.ranchReachedOutsideGate = false;
        work.ranchExitStaged = false;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        progress(work, activeProgress(work), "已用拴绳牵住成年" + target.getDisplayName().getString() + "，正在返回围栏");
    }

    private void tickRanchReturnWithAnimal(ActiveWork work) {
        Animal target = resolveRanchAnimalTarget(work, true);
        if (target == null) {
            npc.absorbNearbyItemsAt(npc.position(), 8.0D);
            work.ranchPhase = 1;
            work.ranchReachedOutsideGate = false;
            progress(work, activeProgress(work), "拴绳中断，已保留围栏进度并重新寻找目标");
            return;
        }
        keepSecuredRanchAnimalsInside(work);
        if (!work.ranchReachedOutsideGate) {
            BlockPos outside = ranchOutsidePoint(work);
            if (!approachRanchLeashedPair(work, target, outside, 2.8D, 1.08D)) return;
            work.ranchReachedOutsideGate = true;
        }
        if (!ensureRanchGateState(work, true)) return;
        BlockPos pullPoint = ranchInteriorPullPoint(work);
        if (!approachRanchLeashedPair(work, target, pullPoint, 1.0D, 0.95D)) return;
        if (!isInsideRanchPen(work, target)) {
            pullLeashedAnimalIntoPen(work, target);
            taskStatus(work, "正在把牲畜完全牵入围栏");
            return;
        }
        if (!tieRanchAnimalForGateExit(work, target)) return;
        target.getNavigation().stop();
        target.setDeltaMovement(Vec3.ZERO);
        work.ranchPhase = 3;
        work.ranchReachedOutsideGate = false;
        work.ranchExitStaged = false;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        progress(work, activeProgress(work), "牲畜已进入围栏并临时拴好，正在退出并关门");
    }

    private void tickRanchExitAndClose(ActiveWork work, String animalType) {
        Animal target = resolveRanchAnimalTarget(work, true);
        if (target == null) {
            discardTemporaryRanchKnot(work);
            work.ranchPhase = 1;
            work.ranchAnimalTargetId = null;
            work.ranchExitStaged = false;
            progress(work, activeProgress(work), "临时绳结已释放，正在重新寻找牲畜");
            return;
        }
        keepRanchAnimalAtTiePoint(work, target);
        if (!exitRanchPenThroughGate(work)) return;
        if (!ensureRanchGateState(work, false)) return;
        work.ranchExitStaged = false;
        if (!isInsideRanchPen(work, target)) {
            releaseTemporaryRanchLead(work, target);
            work.ranchPhase = 1;
            work.ranchAnimalTargetId = null;
            work.ranchExitStaged = false;
            progress(work, activeProgress(work), "牲畜在关门前离开围栏，已回收拴绳并保留当前进度");
            return;
        }
        releaseTemporaryRanchLead(work, target);
        work.securedRanchAnimalIds.add(target.getUUID());
        npc.absorbNearbyItemsAt(Vec3.atCenterOf(work.workstation), 8.0D);
        work.ranchAnimalTargetId = null;
        work.ranchPhase = 1;
        work.completed = Math.min(work.requestedCount, ranchAnimals(work, work.ranchPenCenter, animalType).size());
        progress(work, activeProgress(work), "围栏门已关闭，当前已安置 " + work.completed + "/" + work.requestedCount + " 只牲畜");
    }

    private void keepSecuredRanchAnimalsInside(ActiveWork work) {
        if (work.workstation == null || work.ranchPenCenter == null
            || work.securedRanchAnimalIds.isEmpty()
            || !(npc.level() instanceof ServerLevel level)) return;
        BlockPos gate = work.workstation;
        BlockPos center = work.ranchPenCenter;
        int dx = Integer.compare(center.getX(), gate.getX());
        int dz = Integer.compare(center.getZ(), gate.getZ());
        if (Math.abs(center.getX() - gate.getX()) >= Math.abs(center.getZ() - gate.getZ())) dz = 0;
        else dx = 0;

        var iterator = work.securedRanchAnimalIds.iterator();
        while (iterator.hasNext()) {
            UUID residentId = iterator.next();
            Entity entity = level.getEntity(residentId);
            if (!(entity instanceof Animal resident) || !resident.isAlive()) {
                iterator.remove();
                continue;
            }
            int lane = Math.floorMod(residentId.hashCode(), 3) - 1;
            BlockPos holding = center.offset(dx - dz * lane, 0, dz + dx * lane);
            Vec3 offset = Vec3.atBottomCenterOf(holding).subtract(resident.position());
            double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
            if (horizontal <= 0.35D) {
                resident.getNavigation().stop();
                resident.setDeltaMovement(0.0D, resident.getDeltaMovement().y, 0.0D);
                continue;
            }
            resident.getNavigation().moveTo(
                holding.getX() + 0.5D,
                holding.getY(),
                holding.getZ() + 0.5D,
                0.9D
            );
            double step = Math.min(horizontal, 0.07D);
            resident.move(MoverType.SELF, new Vec3(
                offset.x / horizontal * step,
                0.0D,
                offset.z / horizontal * step
            ));
            resident.setDeltaMovement(0.0D, resident.getDeltaMovement().y, 0.0D);
        }
    }

    private boolean prepareNpcOutsideClosedPen(ActiveWork work) {
        if (isNpcOutsideRanch(work)) return ensureRanchGateState(work, false);
        if (!exitRanchPenThroughGate(work)) return false;
        if (!ensureRanchGateState(work, false)) return false;
        work.ranchExitStaged = false;
        return true;
    }

    private boolean ensureRanchGateState(ActiveWork work, boolean open) {
        if (work.workstation == null) return false;
        BlockState state = npc.level().getBlockState(work.workstation);
        if (!(state.getBlock() instanceof FenceGateBlock) || !state.hasProperty(FenceGateBlock.OPEN)) {
            fail(work, "牲畜围栏门已不存在", "RANCH_GATE_MISSING");
            return false;
        }
        if (state.getValue(FenceGateBlock.OPEN) == open) return true;
        if (!approachRanchPoint(work, work.workstation, 3.8D, 1.05D)) return false;
        if (work.ticks - work.lastActionTick < 8) return false;
        work.lastActionTick = work.ticks;
        InteractionResult result = proxy.useItemOn(work.workstation, Direction.UP, ItemStack.EMPTY, -1);
        boolean changed = npc.level().getBlockState(work.workstation).getValue(FenceGateBlock.OPEN) == open;
        if (!result.consumesAction() || !changed) {
            if (++work.failedActions >= 3) fail(work, "围栏门交互被世界保护或其他模组拒绝", "RANCH_GATE_DENIED");
            return false;
        }
        work.failedActions = 0;
        npc.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private BlockPos ranchOutsidePoint(ActiveWork work) {
        BlockPos gate = work.workstation;
        BlockPos center = work.ranchPenCenter;
        int dx = Integer.compare(center.getX(), gate.getX());
        int dz = Integer.compare(center.getZ(), gate.getZ());
        if (Math.abs(center.getX() - gate.getX()) >= Math.abs(center.getZ() - gate.getZ())) dz = 0;
        else dx = 0;
        return gate.offset(-dx * 2, 0, -dz * 2).immutable();
    }

    private BlockPos ranchInteriorPullPoint(ActiveWork work) {
        BlockPos gate = work.workstation;
        BlockPos center = work.ranchPenCenter;
        int dx = Integer.compare(center.getX(), gate.getX());
        int dz = Integer.compare(center.getZ(), gate.getZ());
        if (Math.abs(center.getX() - gate.getX()) >= Math.abs(center.getZ() - gate.getZ())) dz = 0;
        else dx = 0;
        return center.offset(dx, 0, dz).immutable();
    }

    private BlockPos ranchInteriorGatePoint(ActiveWork work) {
        BlockPos gate = work.workstation;
        BlockPos center = work.ranchPenCenter;
        int dx = Integer.compare(center.getX(), gate.getX());
        int dz = Integer.compare(center.getZ(), gate.getZ());
        if (Math.abs(center.getX() - gate.getX()) >= Math.abs(center.getZ() - gate.getZ())) dz = 0;
        else dx = 0;
        return gate.offset(dx, 0, dz).immutable();
    }

    private boolean exitRanchPenThroughGate(ActiveWork work) {
        if (!ensureRanchGateState(work, true)) return false;
        if (!work.ranchExitStaged) {
            if (!approachRanchPoint(work, ranchInteriorGatePoint(work), 0.55D, 1.0D)) return false;
            work.ranchExitStaged = true;
            work.stalledTicks = 0;
            work.lastDistance = -1;
        }
        return approachRanchPoint(work, ranchOutsidePoint(work), 1.4D, 1.05D);
    }

    private boolean tieRanchAnimalForGateExit(ActiveWork work, Animal animal) {
        BlockPos fence = ranchTemporaryTieFence(work);
        if (fence == null) {
            fail(work, "围栏内没有可安全使用的临时拴绳点", "RANCH_TIE_POINT_MISSING");
            return false;
        }
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(npc.level(), fence);
        work.ranchTemporaryKnotId = knot.getUUID();
        animal.setLeashedTo(knot, true);
        if (animal.getLeashHolder() != knot) {
            discardTemporaryRanchKnot(work);
            fail(work, "无法把牲畜临时拴在围栏内侧", "RANCH_TIE_DENIED");
            return false;
        }
        npc.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private BlockPos ranchTemporaryTieFence(ActiveWork work) {
        if (work.workstation == null) return null;
        return ranchFenceBoundary(work.workstation).stream()
            .filter(position -> npc.level().getBlockState(position).getBlock() instanceof FenceBlock)
            .filter(position -> npc.level().getEntitiesOfClass(
                LeashFenceKnotEntity.class,
                new AABB(position).inflate(0.75D),
                knot -> knot.blockPosition().equals(position)
            ).isEmpty())
            .max(Comparator.comparingDouble(position -> position.distSqr(work.workstation)))
            .map(BlockPos::immutable)
            .orElse(null);
    }

    private void keepRanchAnimalAtTiePoint(ActiveWork work, Animal animal) {
        Entity holder = animal.getLeashHolder();
        if (!isTemporaryRanchKnot(work, holder)) return;
        BlockPos fence = holder.blockPosition();
        BlockPos center = work.ranchPenCenter;
        int dx = Integer.compare(center.getX(), fence.getX());
        int dz = Integer.compare(center.getZ(), fence.getZ());
        BlockPos holding = fence.offset(dx, 0, dz);
        animal.getNavigation().moveTo(
            holding.getX() + 0.5D,
            holding.getY(),
            holding.getZ() + 0.5D,
            0.8D
        );
        Vec3 offset = Vec3.atBottomCenterOf(holding).subtract(animal.position());
        double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (horizontal > 0.05D) {
            double step = Math.min(horizontal, 0.08D);
            animal.move(MoverType.SELF, new Vec3(
                offset.x / horizontal * step,
                0.0D,
                offset.z / horizontal * step
            ));
            animal.setDeltaMovement(0.0D, animal.getDeltaMovement().y, 0.0D);
        }
    }

    private void releaseTemporaryRanchLead(ActiveWork work, Animal animal) {
        if (animal.isLeashed() && isTemporaryRanchKnot(work, animal.getLeashHolder())) {
            animal.dropLeash(true, true);
            npc.absorbNearbyItemsAt(animal.position(), 8.0D);
        }
        discardTemporaryRanchKnot(work);
    }

    private boolean isTemporaryRanchKnot(ActiveWork work, Entity holder) {
        return work.ranchTemporaryKnotId != null
            && holder instanceof LeashFenceKnotEntity
            && work.ranchTemporaryKnotId.equals(holder.getUUID());
    }

    private void discardTemporaryRanchKnot(ActiveWork work) {
        if (work.ranchTemporaryKnotId == null || !(npc.level() instanceof ServerLevel level)) return;
        Entity knot = level.getEntity(work.ranchTemporaryKnotId);
        if (knot instanceof LeashFenceKnotEntity) knot.discard();
        work.ranchTemporaryKnotId = null;
    }

    private void pullLeashedAnimalIntoPen(ActiveWork work, Animal animal) {
        BlockPos gate = work.workstation;
        BlockPos center = work.ranchPenCenter;
        int dx = Integer.compare(center.getX(), gate.getX());
        int dz = Integer.compare(center.getZ(), gate.getZ());
        if (Math.abs(center.getX() - gate.getX()) >= Math.abs(center.getZ() - gate.getZ())) dz = 0;
        else dx = 0;

        Vec3 movement;
        if (!isInsideRanchPen(work, animal)) {
            double lateral = dx == 0
                ? gate.getX() + 0.5D - animal.getX()
                : gate.getZ() + 0.5D - animal.getZ();
            if (Math.abs(lateral) > 0.015D) {
                double correction = Mth.clamp(lateral, -0.04D, 0.04D);
                movement = dx == 0
                    ? new Vec3(correction, 0.0D, 0.0D)
                    : new Vec3(0.0D, 0.0D, correction);
            } else {
                movement = new Vec3(dx * 0.05D, 0.0D, dz * 0.05D);
            }
        } else {
            Vec3 offset = Vec3.atBottomCenterOf(center).subtract(animal.position());
            double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
            if (horizontal <= 1.0E-4D) return;
            double step = Math.min(horizontal, 0.05D);
            movement = new Vec3(offset.x / horizontal * step, 0.0D, offset.z / horizontal * step);
        }
        animal.getNavigation().stop();
        animal.move(MoverType.SELF, movement);
        animal.setDeltaMovement(0.0D, animal.getDeltaMovement().y, 0.0D);
    }

    private boolean approachRanchPoint(ActiveWork work, BlockPos destination, double reach, double speed) {
        double distance = npc.position().distanceTo(Vec3.atBottomCenterOf(destination));
        if (distance <= reach) {
            npc.getNavigation().stop();
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        maintainTaskChunkTicket(destination);
        if (distance <= 8.0D) {
            npc.getNavigation().stop();
            Vec3 offset = Vec3.atBottomCenterOf(destination).subtract(npc.position());
            double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
            if (horizontal > 1.0E-4D) {
                double step = Math.min(horizontal, 0.10D * speed);
                npc.move(MoverType.SELF, new Vec3(
                    offset.x / horizontal * step,
                    0.0D,
                    offset.z / horizontal * step
                ));
                npc.setDeltaMovement(0.0D, npc.getDeltaMovement().y, 0.0D);
            }
        } else navigateTowardBlock(destination, speed);
        npc.getLookControl().setLookAt(Vec3.atCenterOf(destination));
        npc.addExhaustion(0.002F);
        trackNavigation(work, distance);
        taskStatus(work, "正在穿过围栏门，距离 " + Math.round(distance) + " 格");
        if (work.stalledTicks > 300) {
            fail(work, "无法通过围栏门到达目标位置", "RANCH_PEN_PATH_NOT_FOUND");
        }
        return false;
    }

    private boolean isNpcOutsideRanch(ActiveWork work) {
        BlockPos gate = work.workstation;
        BlockPos center = work.ranchPenCenter;
        Vec3 inward = Vec3.atCenterOf(center).subtract(Vec3.atCenterOf(gate));
        Vec3 fromGate = npc.position().subtract(Vec3.atCenterOf(gate));
        return inward.dot(fromGate) <= 0.0D;
    }

    private boolean tickRanchRemoteSearch(ActiveWork work, String animalType) {
        int requestedRadius = Math.max(16, integer(work.spec, "radius", 128));
        if (work.destination != null) {
            BlockPos searchArea = BlockPos.containing(work.destination);
            if (npc.position().distanceTo(work.destination) > 3.5D) {
                if (!approachGatherDestination(
                    work, searchArea, 3.5D, 1.15D,
                    "远程牲畜搜索区不可达", "RANCH_SEARCH_NOT_REACHABLE"
                )) return false;
            }
            work.destination = null;
            work.gatherSearchRadius = 16;
            work.lastSearchTick = -10;
            work.noWorkTicks = 0;
            work.stalledTicks = 0;
            work.lastDistance = -1;
        }
        if (work.ticks - work.lastSearchTick >= 10) {
            work.lastSearchTick = work.ticks;
            Animal candidate = findRanchAnimal(work, animalType, work.gatherSearchRadius);
            if (candidate != null) {
                work.ranchAnimalTargetId = candidate.getUUID();
                work.noWorkTicks = 0;
                work.gatherPathFailures = 0;
                return true;
            }
            if (work.gatherSearchRadius < FoodProvisionPolicy.LOCAL_SEARCH_RADIUS) {
                work.gatherSearchRadius = FoodProvisionPolicy.nextSearchRadius(work.gatherSearchRadius);
            }
        }
        if (++work.noWorkTicks <= 40 || work.gatherSearchRadius < FoodProvisionPolicy.LOCAL_SEARCH_RADIUS) return false;
        int maxExcursions = Math.min(64, Math.max(1, (int) Math.ceil(requestedRadius / GATHER_EXCURSION_DISTANCE) * 8));
        if (work.gatherExcursions >= maxExcursions) {
            fail(work, "扩大范围后仍没有找到可牵引的成年牲畜", "RANCH_ANIMAL_NOT_FOUND");
            return false;
        }
        work.gatherExcursions++;
        work.destination = nextGatherSearchDestination(work.gatherExcursions);
        work.gatherSearchRadius = 16;
        work.noWorkTicks = 0;
        work.lastSearchTick = -10;
        progress(work, activeProgress(work), "附近没有合适牲畜，正在前往第 " + work.gatherExcursions + " 个搜索区");
        return false;
    }

    private Animal findRanchAnimal(ActiveWork work, String animalType, double radius) {
        Map<String, Integer> housedByType = new HashMap<>();
        for (Animal housed : ranchAnimals(work, work.ranchPenCenter, "any")) {
            housedByType.merge(id(housed), 1, Integer::sum);
        }
        return npc.level().getEntitiesOfClass(
            Animal.class,
            npc.getBoundingBox().inflate(radius, 16.0D, radius),
            animal -> isEligibleRanchAnimal(work, animal, animalType)
        ).stream()
            .filter(animal -> npc.getNavigation().createPath(animal, 0) != null)
            .min(Comparator
                .comparingInt((Animal animal) -> housedByType.getOrDefault(id(animal), 0))
                .thenComparingDouble(npc::distanceToSqr))
            .orElse(null);
    }

    private Animal resolveRanchAnimalTarget(ActiveWork work, boolean allowNpcLeash) {
        if (work.ranchAnimalTargetId == null || !(npc.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(work.ranchAnimalTargetId);
        if (!(entity instanceof Animal animal) || !animal.isAlive() || animal.isBaby() || animal.hasCustomName()) {
            work.ranchAnimalTargetId = null;
            return null;
        }
        if (animal.isLeashed() && (!allowNpcLeash
            || (animal.getLeashHolder() != npc && !isTemporaryRanchKnot(work, animal.getLeashHolder())))) {
            work.ranchAnimalTargetId = null;
            return null;
        }
        return animal;
    }

    private boolean isEligibleRanchAnimal(ActiveWork work, Animal animal, String animalType) {
        if (!isSupportedFoodAnimal(animal) || animal.isBaby() || animal.hasCustomName() || animal.isLeashed()) return false;
        if (animal instanceof TamableAnimal tamable && tamable.isTame()) return false;
        if (!animalType.equals("any") && !id(animal).equals(animalType)) return false;
        String fixtureTag = string(work.spec, "fixtureTag", "");
        if (!fixtureTag.isBlank() && !animal.getTags().contains(fixtureTag)) return false;
        if (work.skippedRanchAnimalTargets.contains(animal.getUUID())) return false;
        return !isInsideRanchPen(work, animal);
    }

    private boolean isInsideRanchPen(ActiveWork work, Animal animal) {
        if (work.workstation == null) return false;
        List<BlockPos> boundary = ranchFenceBoundary(work.workstation);
        if (boundary.size() < 12) return false;
        int minX = boundary.stream().mapToInt(BlockPos::getX).min().orElse(work.workstation.getX());
        int maxX = boundary.stream().mapToInt(BlockPos::getX).max().orElse(work.workstation.getX());
        int minZ = boundary.stream().mapToInt(BlockPos::getZ).min().orElse(work.workstation.getZ());
        int maxZ = boundary.stream().mapToInt(BlockPos::getZ).max().orElse(work.workstation.getZ());
        return RanchPenPolicy.insideBoundary(animal.getX(), animal.getZ(), minX, maxX, minZ, maxZ);
    }

    private boolean approachRanchAnimal(ActiveWork work, Animal animal, double reach, double speed) {
        maintainTaskChunkTicket(animal.blockPosition());
        double distance = npc.distanceTo(animal);
        if (distance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(animal, 30.0F, 30.0F);
            work.gatherPathFailures = 0;
            return true;
        }
        boolean moving = npc.getNavigation().moveTo(animal, speed);
        npc.getLookControl().setLookAt(animal, 30.0F, 30.0F);
        work.gatherPathFailures = moving || npc.getNavigation().isInProgress()
            ? 0
            : work.gatherPathFailures + 1;
        trackNavigation(work, distance);
        if (RanchPenPolicy.targetPathUnavailable(work.gatherPathFailures, work.stalledTicks)) {
            work.skippedRanchAnimalTargets.add(animal.getUUID());
            work.ranchAnimalTargetId = null;
            work.gatherPathFailures = 0;
            work.stalledTicks = 0;
            work.lastDistance = -1;
            npc.getNavigation().stop();
            return false;
        }
        String movement = moving || npc.getNavigation().isInProgress() ? "正在走向" : "正在重新计算到";
        taskStatus(work, movement + "成年" + animal.getDisplayName().getString()
            + "的路线，距离 " + Math.round(distance) + " 格");
        return false;
    }

    private boolean approachRanchLeashedPair(
        ActiveWork work,
        Animal animal,
        BlockPos destination,
        double reach,
        double speed
    ) {
        double distance = npc.position().distanceTo(Vec3.atBottomCenterOf(destination));
        if (distance <= reach) {
            npc.getNavigation().stop();
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        maintainTaskChunkTicket(destination);
        if (distance <= 8.0D) {
            npc.getNavigation().stop();
            npc.getMoveControl().setWantedPosition(
                destination.getX() + 0.5D,
                destination.getY(),
                destination.getZ() + 0.5D,
                speed
            );
            Vec3 offset = Vec3.atBottomCenterOf(destination).subtract(npc.position());
            double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
            if (horizontal > 1.0E-4D) {
                double step = Math.min(horizontal, 0.10D * speed);
                npc.move(MoverType.SELF, new Vec3(
                    offset.x / horizontal * step,
                    0.0D,
                    offset.z / horizontal * step
                ));
                npc.setDeltaMovement(0.0D, npc.getDeltaMovement().y, 0.0D);
            }
        } else navigateTowardBlock(destination, speed);
        npc.getLookControl().setLookAt(Vec3.atCenterOf(destination));
        npc.addExhaustion(0.002F);
        trackNavigation(work, distance);
        taskStatus(work, "正在步行牵引" + animal.getDisplayName().getString()
            + "返回围栏，距离 " + Math.round(distance) + " 格");
        if (work.stalledTicks > 300) {
            animal.dropLeash(true, true);
            npc.absorbNearbyItemsAt(animal.position(), 6.0D);
            work.ranchAnimalTargetId = null;
            work.ranchPhase = 1;
            work.ranchReachedOutsideGate = false;
            work.stalledTicks = 0;
            work.lastDistance = -1;
            progress(work, activeProgress(work), "牵引路线暂时不可达，已安全释放牲畜并保留围栏进度");
        }
        return false;
    }

    private List<Animal> ranchAnimals(ActiveWork work, BlockPos center, String animalType) {
        if (center == null || work.workstation == null) return List.of();
        String fixtureTag = string(work.spec, "fixtureTag", "");
        List<BlockPos> boundary = ranchFenceBoundary(work.workstation);
        if (boundary.size() < 12) return List.of();
        int minX = boundary.stream().mapToInt(BlockPos::getX).min().orElse(center.getX());
        int maxX = boundary.stream().mapToInt(BlockPos::getX).max().orElse(center.getX());
        int minY = boundary.stream().mapToInt(BlockPos::getY).min().orElse(center.getY());
        int maxY = boundary.stream().mapToInt(BlockPos::getY).max().orElse(center.getY());
        int minZ = boundary.stream().mapToInt(BlockPos::getZ).min().orElse(center.getZ());
        int maxZ = boundary.stream().mapToInt(BlockPos::getZ).max().orElse(center.getZ());
        AABB searchBounds = new AABB(minX, minY - 2.0D, minZ, maxX + 1.0D, maxY + 4.0D, maxZ + 1.0D);
        return npc.level().getEntitiesOfClass(
            Animal.class,
            searchBounds,
            animal -> isSupportedFoodAnimal(animal)
                && (animalType.equals("any") || id(animal).equals(animalType))
                && (fixtureTag.isBlank() || animal.getTags().contains(fixtureTag))
                && RanchPenPolicy.insideBoundary(animal.getX(), animal.getZ(), minX, maxX, minZ, maxZ)
        );
    }

    private void tickRanchBreed(ActiveWork work, List<Animal> housed) {
        if (housed.size() < 2) {
            fail(work, "围栏内没有两只当前可繁殖的成年牲畜", "RANCH_BREEDING_PAIR_MISSING");
            return;
        }
        if (work.completed >= 2) {
            if ((!isNpcOutsideRanch(work) || work.ranchExitStaged) && !exitRanchPenThroughGate(work)) return;
            if (!ensureRanchGateState(work, false)) return;
            work.ranchExitStaged = false;
            complete(work, "已给两只成年牲畜喂食并进入繁殖状态");
            return;
        }
        if (isNpcOutsideRanch(work)) {
            if (!ensureRanchGateState(work, true)) return;
            if (!approachRanchPoint(work, work.ranchPenCenter, 2.0D, 1.0D)) return;
        }
        Animal ready = housed.stream()
            .filter(animal -> !animal.isBaby() && animal.getAge() == 0 && !animal.isInLove())
            .findFirst()
            .orElse(null);
        if (ready == null) {
            fail(work, "围栏内没有当前可继续喂食的成年牲畜", "RANCH_BREEDING_PAIR_MISSING");
            return;
        }
        int foodSlot = findRanchBreedingFoodSlot(ready);
        if (foodSlot < 0) {
            fail(work, "背包中没有足够的对应繁殖饲料", "RANCH_BREEDING_FOOD_MISSING");
            return;
        }
        if (!approachRanchAnimal(work, ready, 2.8D, 1.0D)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        if (!proxy.interact(ready, npc.inventory().getStackInSlot(foodSlot), foodSlot).consumesAction()) {
            if (++work.failedActions >= 3) fail(work, "繁殖喂食交互被世界保护或其他模组拒绝", "RANCH_BREEDING_DENIED");
            return;
        }
        work.failedActions = 0;
        work.completed++;
        progress(work, work.completed / 2.0D, "已给 " + work.completed + "/2 只成年牲畜喂食");
    }

    private int findRanchBreedingFoodSlot(Animal animal) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!stack.isEmpty() && animal.isFood(stack)) return slot;
        }
        return -1;
    }

    private void tickRanchCull(ActiveWork work, List<Animal> housed) {
        if (housed.size() <= work.requestedCount) {
            if ((!isNpcOutsideRanch(work) || work.ranchExitStaged) && !exitRanchPenThroughGate(work)) return;
            if (!ensureRanchGateState(work, false)) return;
            work.ranchExitStaged = false;
            complete(work, "围栏内牲畜数量已不超过保留目标 " + work.requestedCount + " 只");
            return;
        }
        if (isNpcOutsideRanch(work)) {
            if (!ensureRanchGateState(work, true)) return;
            if (!approachRanchPoint(work, work.ranchPenCenter, 1.8D, 1.05D)) return;
            return;
        }
        Animal target = housed.stream()
            .filter(animal -> !animal.isBaby() && !animal.hasCustomName() && !animal.isLeashed())
            .findFirst()
            .orElse(null);
        if (target == null) {
            fail(work, "超额牲畜均处于受保护状态，未执行屠宰", "RANCH_CULL_PROTECTED");
            return;
        }
        equipBestWeapon();
        npc.setTarget(target);
        attack(target);
        if (!target.isAlive()) {
            npc.absorbNearbyItemsAt(target.position(), 5.0D);
            npc.setTarget(null);
            progress(work, activeProgress(work), "已处理一只超额成年牲畜并收集掉落");
        }
    }

    private void tickProvisionFood(ActiveWork work) {
        String source = string(work.spec, "source", "auto");
        String destination = string(work.spec, "destination", "backpack");
        if (!work.initialized) {
            work.initialized = true;
            work.requestedCount = Math.max(1, integer(work.spec, "count", FoodProvisionPolicy.DEFAULT_RESERVE_COUNT));
            work.foodPhase = source.equals("auto") && destination.equals("backpack") ? 0 : 1;
            work.gatherSearchRadius = 16;
            work.lastSearchTick = -10;
            work.completed = Math.min(work.requestedCount, provisioningFoodCount(work));
        }
        if (hasCraftGatherPrerequisite(work)) {
            tickCraftGatherPrerequisite(work);
            return;
        }
        if (work.foodCookingInputId != null) {
            tickProvisionFoodCooking(work);
            return;
        }
        if (work.foodPhase == 3) {
            tickProvisionFoodFishing(work);
            return;
        }
        if (work.foodPhase == 2) {
            tickProvisionFoodDestination(work, destination);
            return;
        }

        int foodItems = provisioningFoodCount(work);
        work.completed = Math.min(work.requestedCount, foodItems);
        if (FoodProvisionPolicy.shouldComplete(foodItems, work.requestedCount)) {
            String rawFood = findRawProvisioningFood(work);
            if (rawFood != null) {
                beginProvisionFoodCooking(work, rawFood);
                tickProvisionFoodCooking(work);
                return;
            }
            work.targetBlock = null;
            work.destination = null;
            work.foodAnimalTargetId = null;
            npc.setTarget(null);
            if (!destination.equals("backpack")) {
                work.foodPhase = 2;
                work.workstation = null;
                work.skippedStorageTargets.clear();
                progress(work, 0.9D, destination.equals("home-storage")
                    ? "口粮已经备齐，正在返回家中仓库存放"
                    : "口粮已经备齐，正在返回并交付给玩家");
                tickProvisionFoodDestination(work, destination);
                return;
            }
            complete(work, "已备好 " + foodItems + " 份" + FoodProvisionPolicy.goalLabel(source)
                + "，饱食度 " + npc.foodLevel() + "/20");
            return;
        }

        if (work.foodPhase == 0 && tickFoodStorageLookup(work)) return;
        tickFoodExpedition(work, source);
    }

    private void tickProvisionFoodDestination(ActiveWork work, String destination) {
        int remaining = Math.max(0, work.requestedCount - work.foodTransferredCount);
        if (remaining <= 0) {
            complete(work, destination.equals("home-storage")
                ? "已将 " + work.foodTransferredCount + " 份安全口粮存回家中箱子"
                : "已将 " + work.foodTransferredCount + " 份安全口粮交给玩家");
            return;
        }
        if (destination.equals("home-storage")) {
            tickProvisionFoodStorageDestination(work, remaining);
            return;
        }
        if (!destination.equals("player")) {
            fail(work, "寻食任务包含未知的口粮去向", "FOOD_DESTINATION_INVALID");
            return;
        }
        String playerName = string(work.spec, "player", "");
        ServerPlayer recipient = findRecipientPlayer(playerName);
        if (recipient == null || !recipient.isAlive()) {
            if (++work.noWorkTicks >= 100) fail(work, "没有找到要接收口粮的在线玩家", "PLAYER_NOT_FOUND");
            return;
        }
        if (recipient.level() != npc.level()) {
            fail(work, "接收口粮的玩家位于其他维度", "PLAYER_NOT_REACHABLE");
            return;
        }
        work.noWorkTicks = 0;
        if (!approach(work, recipient, 3.2D, 1.15D)) return;
        if (work.ticks - work.lastActionTick < 4) return;
        work.lastActionTick = work.ticks;
        int moved = throwProvisioningFood(work, remaining, recipient);
        work.foodTransferredCount += moved;
        if (work.foodTransferredCount >= work.requestedCount) {
            complete(work, "已将 " + work.foodTransferredCount + " 份"
                + FoodProvisionPolicy.goalLabel(string(work.spec, "source", "auto")) + "交给 "
                + recipient.getGameProfile().getName());
        } else if (moved <= 0) {
            fail(work, "准备好的安全口粮已不在 NPC 背包中", "FOOD_DELIVERY_MISSING");
        } else {
            progress(work, 0.9D + 0.1D * work.foodTransferredCount / work.requestedCount,
                "已交付口粮 " + work.foodTransferredCount + "/" + work.requestedCount);
        }
    }

    private void tickProvisionFoodStorageDestination(ActiveWork work, int remaining) {
        if (work.workstation == null) {
            work.workstation = findHomeStorage(null, false, work, HomeStoragePolicy.DEFAULT_RADIUS);
            if (work.workstation == null) {
                work.workstation = createHomeStorageIfPossible(work, HomeStoragePolicy.DEFAULT_RADIUS);
                if (work.workstation == null) return;
            }
        }
        if (!approachStorageTarget(work, work.workstation, 3.5D, 1.08D)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
            return;
        }
        int moved = depositProvisioningFood(work, container, remaining);
        work.foodTransferredCount += moved;
        container.setChanged();
        if (work.foodTransferredCount >= work.requestedCount) {
            complete(work, "已将 " + work.foodTransferredCount + " 份安全口粮存回家中箱子");
            return;
        }
        if (moved <= 0 || !containerHasSpace(container)) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
        }
        progress(work, 0.9D + 0.1D * work.foodTransferredCount / work.requestedCount,
            "已存入口粮 " + work.foodTransferredCount + "/" + work.requestedCount);
    }

    private String findRawProvisioningFood(ActiveWork work) {
        for (String inputId : List.of(
            "minecraft:beef",
            "minecraft:porkchop",
            "minecraft:mutton",
            "minecraft:chicken",
            "minecraft:rabbit",
            "minecraft:cod",
            "minecraft:salmon",
            "minecraft:potato"
        )) {
            if (FoodProvisionPolicy.acceptsSourceItem(provisioningSource(work), inputId)
                && inventoryCount(inputId) > 0) return inputId;
        }
        return null;
    }

    private void beginProvisionFoodCooking(ActiveWork work, String inputId) {
        Recipe<?> recipe = findCookingRecipe(inputId);
        if (recipe == null) return;
        work.foodCookingInputId = inputId;
        work.foodCookingOutputId = itemId(recipe.getResultItem(npc.level().registryAccess()));
        work.foodCookingTargetCount = inventoryCount(inputId);
        work.foodCookedCount = 0;
        work.loaded = 0;
        work.workstation = null;
        work.smeltingWorkstationClaimed = false;
        work.smeltStartedTick = -1;
        work.skippedWorkstationTargets.clear();
        progress(work, activeProgress(work), "已取得生食，准备烹饪 " + work.foodCookingTargetCount + " 份 " + inputId);
    }

    private void tickProvisionFoodCooking(ActiveWork work) {
        String inputId = work.foodCookingInputId;
        String outputId = work.foodCookingOutputId;
        if (inputId == null || outputId == null || work.foodCookingTargetCount <= 0) {
            clearProvisionFoodCooking(work);
            return;
        }
        Recipe<?> recipe = findCookingRecipe(inputId);
        if (recipe == null || !itemId(recipe.getResultItem(npc.level().registryAccess())).equals(outputId)) {
            fail(work, "烹饪配方在任务执行期间发生变化", "FOOD_COOK_RECIPE_CHANGED");
            return;
        }
        String workstationId = cookingWorkstation(recipe.getType());
        if (!workstationId.equals("minecraft:furnace")) workstationId = "minecraft:furnace";
        if (work.workstation != null && !hasTaskFurnaceClaim(work, work.workstation)) {
            work.workstation = null;
            work.smeltingWorkstationClaimed = false;
        }
        if (work.workstation == null) {
            work.workstation = findClaimableSmeltingWorkstation(work, workstationId);
            if (work.workstation != null) {
                int outputPerInput = Math.max(1, recipe.getResultItem(npc.level().registryAccess()).getCount());
                work.smeltingWorkstationClaimed = claimTaskFurnace(
                    work, work.workstation, inputId, outputId, outputPerInput
                );
                if (!work.smeltingWorkstationClaimed) {
                    work.skippedWorkstationTargets.add(work.workstation.immutable());
                    work.workstation = null;
                }
            }
        }
        if (work.workstation == null) {
            if (prepareSmeltingWorkstation(work, workstationId)) return;
            work.workstation = ensureWorkstation(work, workstationId);
            if (work.workstation == null) return;
            int outputPerInput = Math.max(1, recipe.getResultItem(npc.level().registryAccess()).getCount());
            work.smeltingWorkstationClaimed = claimTaskFurnace(
                work, work.workstation, inputId, outputId, outputPerInput
            );
            if (!work.smeltingWorkstationClaimed) {
                work.skippedWorkstationTargets.add(work.workstation.immutable());
                work.workstation = null;
                return;
            }
        }
        if (!approach(work, work.workstation, 3.5D, 1.05D)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            fail(work, "寻食任务占用的熔炉已不存在", "FOOD_COOK_FURNACE_MISSING");
            return;
        }
        ItemStack furnaceInput = furnace.getItem(0);
        ItemStack furnaceOutput = furnace.getItem(2);
        if (!furnaceInput.isEmpty() && !itemId(furnaceInput).equals(inputId)) {
            fail(work, "熔炉原料槽被其他物品占用；已有物品保持原样", "FOOD_COOK_INPUT_CONFLICT");
            return;
        }
        if (!furnaceOutput.isEmpty() && !itemId(furnaceOutput).equals(outputId)) {
            fail(work, "熔炉产物槽被其他物品占用；已有物品保持原样", "FOOD_COOK_OUTPUT_CONFLICT");
            return;
        }
        FoodSurvivalLiveFixture.observeFurnace(npc, work.workstation, furnace, inputId, outputId);
        if (!furnaceOutput.isEmpty()) {
            if (!canInsert(furnaceOutput)) {
                fail(work, "NPC 背包没有空间接收熟食", "INVENTORY_FULL");
                return;
            }
            int moved = furnaceOutput.getCount();
            npc.insert(furnaceOutput.copy());
            recordInventoryAction(work, "furnace-output");
            recordTaskFurnaceOutputWithdrawal(work, work.workstation, furnaceOutput);
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
            work.foodCookedCount += moved;
            work.lastProgressTick = work.ticks;
            FoodSurvivalLiveFixture.recordCookedWithdrawal(npc, work.workstation, moved);
        }
        if (work.foodCookedCount >= work.foodCookingTargetCount) {
            String cooked = work.foodCookingOutputId;
            int cookedCount = work.foodCookedCount;
            FurnaceRecoverySummary recovery = clearProvisionFoodCooking(work);
            work.completed = Math.min(work.requestedCount, provisioningFoodCount(work));
            progress(work, activeProgress(work), "已烹饪 " + cookedCount + " 份 " + cooked + "，继续检查口粮目标"
                + recovery.detail());
            return;
        }
        furnaceInput = furnace.getItem(0);
        if (furnaceInput.isEmpty() && work.loaded < work.foodCookingTargetCount) {
            int batch = Math.min(64, work.foodCookingTargetCount - work.loaded);
            ItemStack input = extract(inputId, batch);
            if (input.isEmpty()) {
                fail(work, "准备烹饪的生食已不在 NPC 背包中", "FOOD_COOK_INPUT_MISSING");
                return;
            }
            recordInventoryAction(work, "furnace-input");
            work.loaded += input.getCount();
            recordTaskFurnaceInput(work, work.workstation, input);
            furnace.setItem(0, input);
            furnace.setChanged();
            work.lastProgressTick = work.ticks;
        }
        ItemStack fuel = furnace.getItem(1);
        if (!isCompatibleClaimedFurnaceFuel(fuel)) {
            fail(work, "熔炉燃料槽出现不兼容物品；已有物品保持原样", "FOOD_COOK_FUEL_CONFLICT");
            return;
        }
        if (shouldSupplyFurnaceFuel(work.workstation, fuel)) {
            if (!npc.creativeResources() && !hasSafeFurnaceFuel()
                && beginPreferredCoalFuelAcquisition(
                    work,
                    work.foodCookingTargetCount - work.foodCookedCount,
                    "烹饪食物"
                )) return;
            if (!npc.creativeResources() && !hasSafeFurnaceFuel()) {
                int logs = SmeltingPrerequisitePolicy.fallbackFuelLogs(
                    work.foodCookingTargetCount - work.foodCookedCount
                );
                beginCraftGather(work, "#minecraft:logs", logs, "烹饪食物缺少安全燃料，先采集 " + logs + " 个原木");
                return;
            }
            ItemStack supplied = npc.creativeResources() ? new ItemStack(Items.COAL) : extractFuel();
            if (supplied.isEmpty()) {
                fail(work, "NPC 背包中没有可用的安全燃料", "FOOD_COOK_FUEL_MISSING");
                return;
            }
            CraftChainLiveFixture.recordFurnaceFuelSupply(npc, work.workstation, supplied);
            recordTaskFurnaceFuel(work, work.workstation, supplied);
            furnace.setItem(1, supplied);
            furnace.setChanged();
            work.lastProgressTick = work.ticks;
        }
        if (work.smeltStartedTick < 0) work.smeltStartedTick = work.ticks;
        if (work.ticks % 20 == 0) {
            taskStatus(work, "正在烹饪食物 " + work.foodCookedCount + "/" + work.foodCookingTargetCount);
        }
        if (work.ticks - work.lastProgressTick > 20 * 45) {
            fail(work, "烹饪长时间没有产生熟食", "FOOD_COOK_STALLED");
        }
    }

    private FurnaceRecoverySummary clearProvisionFoodCooking(ActiveWork work) {
        FurnaceRecoverySummary recovery = recoverTaskFurnace(work, work.workstation, "food-cooking-complete");
        work.foodCookingInputId = null;
        work.foodCookingOutputId = null;
        work.foodCookingTargetCount = 0;
        work.foodCookedCount = 0;
        work.loaded = 0;
        work.workstation = null;
        work.smeltingWorkstationClaimed = false;
        work.smeltStartedTick = -1;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        return recovery;
    }

    /** Returns true while storage lookup owns this tick. */
    private boolean tickFoodStorageLookup(ActiveWork work) {
        if (work.workstation == null) {
            work.workstation = findHomeFoodStorage(work, HomeStoragePolicy.DEFAULT_RADIUS);
            if (work.workstation == null) {
                work.foodPhase = 1;
                work.skippedStorageTargets.clear();
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
                progress(work, activeProgress(work), "背包和家中箱子口粮不足，开始寻找成熟作物和野外成年牲畜");
                return false;
            }
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return true;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
            return true;
        }
        int wanted = Math.max(1, work.requestedCount - provisioningFoodCount(work));
        int moved = withdrawProvisioningFood(work, container, wanted);
        container.setChanged();
        if (moved <= 0) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
            return true;
        }
        work.workstation = null;
        work.completed = Math.min(work.requestedCount, provisioningFoodCount(work));
        progress(work, activeProgress(work), "已从家中箱子取出 " + moved + " 份"
            + FoodProvisionPolicy.goalLabel(provisioningSource(work)) + "，当前数量 "
            + provisioningFoodCount(work) + "/" + work.requestedCount);
        return true;
    }

    private void tickFoodExpedition(ActiveWork work, String source) {
        if (maybeBeginDeepMiningRationFishing(work)) return;
        if (work.destination != null) {
            BlockPos searchArea = BlockPos.containing(work.destination);
            maintainTaskChunkTicket(searchArea);
            if (npc.position().distanceTo(work.destination) <= 3.5) {
                npc.getNavigation().stop();
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
            } else {
                if (!approachGatherDestination(
                    work,
                    searchArea,
                    3.5,
                    1.15,
                    "远程寻食区不可达",
                    "FOOD_SEARCH_NOT_REACHABLE"
                )) {
                    if (active == work && work.ticks % 40 == 0) {
                        progress(work, activeProgress(work), "正在前往第 " + work.gatherExcursions + " 个远程寻食区");
                    }
                    return;
                }
                work.destination = null;
                work.gatherSearchRadius = 16;
                work.lastSearchTick = -10;
                work.noWorkTicks = 0;
            }
        }

        Animal animal = resolveFoodAnimalTarget(work);
        if (animal != null) {
            tickFoodHunt(work, animal);
            return;
        }
        if (work.targetBlock != null && isHarvestableFoodSource(work.targetBlock)) {
            tickFoodHarvest(work);
            return;
        }
        work.targetBlock = null;

        if (work.ticks - work.lastSearchTick >= 10) {
            work.lastSearchTick = work.ticks;
            if (!source.equals("hunt")) {
                work.targetBlock = findFoodSourceBlock(work.gatherSearchRadius, work.skippedGatherTargets);
            }
            if (work.targetBlock == null && !source.equals("forage")) {
                Animal candidate = findFoodAnimal(work, work.gatherSearchRadius);
                if (candidate != null) work.foodAnimalTargetId = candidate.getUUID();
            }
            if (work.targetBlock != null || work.foodAnimalTargetId != null) {
                work.noWorkTicks = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1;
                work.failedActions = 0;
            } else if (work.gatherSearchRadius < FoodProvisionPolicy.LOCAL_SEARCH_RADIUS) {
                work.gatherSearchRadius = FoodProvisionPolicy.nextSearchRadius(work.gatherSearchRadius);
            }
        }

        if (work.targetBlock != null || work.foodAnimalTargetId != null) return;
        if (++work.noWorkTicks <= 40 || work.gatherSearchRadius < FoodProvisionPolicy.LOCAL_SEARCH_RADIUS) return;
        if (work.gatherExcursions >= FoodProvisionPolicy.MAX_EXCURSIONS) {
            fail(work, "远程搜索后仍没有足够的安全食物来源", "FOOD_SOURCE_NOT_FOUND");
            return;
        }
        work.gatherExcursions++;
        work.destination = nextGatherSearchDestination(work.gatherExcursions);
        work.gatherSearchRadius = 16;
        work.noWorkTicks = 0;
        work.lastSearchTick = -10;
        progress(work, activeProgress(work), "附近安全食物不足，正在前往第 " + work.gatherExcursions + " 个远程寻食区");
    }

    private boolean isSurvivalReserveProvision(ActiveWork work) {
        return work != null
            && "provision-food".equals(work.kind)
            && Set.of("deep-mining-survival", "npc-food-reserve")
                .contains(string(work.spec, "requestedBy", ""));
    }

    /**
     * A mining expedition must not depend on finding a large unprotected herd.
     * After two unsuccessful surface search regions, use a real fishing rod and
     * nearby water as the renewable, non-destructive ration fallback.
     */
    private boolean maybeBeginDeepMiningRationFishing(ActiveWork work) {
        if (!isSurvivalReserveProvision(work)
            || work.gatherExcursions < 2
            || work.ticks % 100 != 0
            || findFishingRodSlot() < 0) return false;
        BlockPos water = findFishingWater(64);
        if (water == null) return false;
        npc.getNavigation().stop();
        work.destination = null;
        work.targetBlock = null;
        work.foodAnimalTargetId = null;
        work.workstation = water.immutable();
        work.foodPhase = 3;
        work.fishingCast = false;
        work.fishingReadyTick = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        work.lastTeleportTarget = null;
        progress(work, activeProgress(work), "野外口粮来源稀少，改用钓鱼补足深挖口粮");
        return true;
    }

    private void tickProvisionFoodFishing(ActiveWork work) {
        if (!(npc.level() instanceof ServerLevel level)) {
            fail(work, "钓鱼补给只能在服务端世界执行", "WORLD_UNAVAILABLE");
            return;
        }
        int foodItems = provisioningFoodCount(work);
        if (FoodProvisionPolicy.shouldComplete(foodItems, work.requestedCount)) {
            proxy.cancelFishing();
            work.fishingCast = false;
            work.fishingReadyTick = 0;
            work.foodPhase = 1;
            work.workstation = null;
            progress(work, activeProgress(work), "钓鱼口粮已备齐，继续整理并烹饪安全口粮");
            return;
        }
        if (discardLowValueFishingJunk(work)) return;
        if (tickMiningInventoryCleanup(work, null, "minecraft:cod")) return;
        int rodSlot = findFishingRodSlot();
        if (rodSlot < 0) {
            proxy.cancelFishing();
            work.fishingCast = false;
            work.fishingReadyTick = 0;
            work.foodPhase = 1;
            work.workstation = null;
            progress(work, activeProgress(work), "钓鱼竿已无法使用，继续寻找其他安全食物来源");
            return;
        }
        if (!isFishableWater(work.workstation)) {
            work.workstation = findFishingWater(64);
            if (work.workstation == null) {
                work.foodPhase = 1;
                progress(work, activeProgress(work), "附近水面不可用，继续寻找其他安全食物来源");
                return;
            }
        }
        if (!approach(work, work.workstation, 4.5D, 1.0D)) return;
        if (rodSlot != CodexNpcEntity.MAIN_HAND_SLOT) equipMainHand(rodSlot);
        rodSlot = CodexNpcEntity.MAIN_HAND_SLOT;
        ItemStack rod = npc.inventory().getStackInSlot(rodSlot);

        if (!work.fishingCast) {
            npc.getLookControl().setLookAt(Vec3.atCenterOf(work.workstation).add(0, 0.4D, 0));
            npc.swing(InteractionHand.MAIN_HAND);
            if (!proxy.castFishing(rodSlot)) {
                if (++work.failedActions >= 3) {
                    work.foodPhase = 1;
                    work.workstation = null;
                    work.failedActions = 0;
                    progress(work, activeProgress(work), "当前水面无法抛竿，继续寻找其他口粮来源");
                }
                return;
            }
            work.failedActions = 0;
            work.fishingCast = true;
            long seed = level.getSeed() ^ npc.getUUID().getLeastSignificantBits();
            work.fishingReadyTick = work.ticks
                + NpcLifeSkillPolicy.fishingWaitTicks(seed, work.completed + foodItems);
            progress(work, activeProgress(work), "已抛竿，正在为深挖任务钓取安全口粮");
            return;
        }
        if (work.ticks < work.fishingReadyTick) {
            if (work.ticks % 100 == 0) {
                taskStatus(work, "正在钓取深挖口粮 " + foodItems + "/" + work.requestedCount);
            }
            return;
        }

        proxy.cancelFishing();
        work.fishingCast = false;
        work.fishingReadyTick = 0;
        rod.hurtAndBreak(1, npc, entity -> entity.broadcastBreakEvent(InteractionHand.MAIN_HAND));
        npc.inventory().setStackInSlot(rodSlot, rod);
        List<ItemStack> loot = rollFishingLoot(level, work.workstation, rod);
        for (ItemStack stack : loot) {
            if (stack.isEmpty()) continue;
            if (!FishingProvisioningPolicy.keepLoot(
                isSafeProvisioningFood(stack),
                FoodProvisionPolicy.acceptsSourceItem(provisioningSource(work), itemId(stack))
            )) {
                spawnThrownStack(stack.copy(), null);
                continue;
            }
            ItemStack remainder = npc.insert(stack.copy());
            if (!remainder.isEmpty()) spawnThrownStack(remainder, null);
        }
        recordInventoryAction(work, "fish-catch");
        npc.addExhaustion(0.04F);
        int updated = provisioningFoodCount(work);
        work.completed = Math.min(work.requestedCount, updated);
        progress(work, activeProgress(work), "已完成一竿，当前安全口粮 " + updated + "/" + work.requestedCount);
    }

    private boolean discardLowValueFishingJunk(ActiveWork work) {
        boolean backpackFull = true;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            if (npc.inventory().getStackInSlot(slot).isEmpty()) {
                backpackFull = false;
                break;
            }
        }
        if (!backpackFull) return false;

        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            ItemStack current = npc.inventory().getStackInSlot(slot);
            if (current.isEmpty() || !FishingProvisioningPolicy.disposableJunk(itemId(current))) continue;
            ItemStack discarded = npc.inventory().extractItem(slot, current.getCount(), false);
            if (discarded.isEmpty()) continue;
            spawnThrownStack(discarded, null);
            recordInventoryAction(work, "drop");
            npc.swing(InteractionHand.MAIN_HAND);
            progress(work, activeProgress(work), "背包已满，清出钓鱼杂物并继续补充安全口粮");
            return true;
        }
        return false;
    }

    private void tickFoodHarvest(ActiveWork work) {
        BlockPos target = work.targetBlock;
        if (!approachGatherTarget(work, target, 3.2, 1.1)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        BlockState state = npc.level().getBlockState(target);
        int before = provisioningFoodCount(work);
        boolean handled;
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            InteractionResult result = proxy.useItemOn(target, Direction.UP, ItemStack.EMPTY, -1);
            handled = result.consumesAction();
        } else {
            int toolSlot = bestToolSlot(state);
            npc.swing(InteractionHand.MAIN_HAND);
            handled = proxy.breakBlock(target, toolSlot >= 0 ? toolSlot : -1);
        }
        if (!handled) {
            if (++work.failedActions >= 3) {
                work.skippedGatherTargets.add(target.immutable());
                work.targetBlock = null;
                work.failedActions = 0;
            }
            return;
        }
        npc.absorbNearbyItemsAt(Vec3.atCenterOf(target), 4.0D);
        if (state.getBlock() instanceof CropBlock) plantCrop(target, id(state.getBlock()));
        npc.swing(InteractionHand.MAIN_HAND);
        npc.addExhaustion(0.05F);
        work.targetBlock = null;
        work.failedActions = 0;
        int gained = Math.max(0, provisioningFoodCount(work) - before);
        work.completed = Math.min(work.requestedCount, provisioningFoodCount(work));
        progress(work, activeProgress(work), "已采集 " + gained + " 份食物，当前安全口粮 "
            + provisioningFoodCount(work) + "/" + work.requestedCount);
    }

    private void tickFoodHunt(ActiveWork work, Animal target) {
        if (!target.isAlive()) {
            Vec3 drops = target.position();
            npc.absorbNearbyItemsAt(drops, 5.0D);
            work.foodAnimalTargetId = null;
            npc.setTarget(null);
            work.completed = Math.min(work.requestedCount, provisioningFoodCount(work));
            progress(work, activeProgress(work), "已收集猎物掉落，当前"
                + FoodProvisionPolicy.goalLabel(provisioningSource(work)) + " "
                + provisioningFoodCount(work) + "/" + work.requestedCount);
            return;
        }
        maintainTaskChunkTicket(target.blockPosition());
        double distance = npc.distanceTo(target);
        if (distance > 2.8D) {
            boolean moving = npc.getNavigation().moveTo(target, 1.2D);
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
            trackNavigation(work, distance);
            if (!moving || work.stalledTicks > 100) {
                if (++work.failedActions >= 3) {
                    work.skippedFoodAnimalTargets.add(target.getUUID());
                    work.foodAnimalTargetId = null;
                    work.failedActions = 0;
                    work.stalledTicks = 0;
                    work.lastDistance = -1;
                }
            } else {
                work.failedActions = 0;
            }
            taskStatus(work, "正在接近成年" + target.getDisplayName().getString() + "，距离 " + Math.round(distance) + " 格");
            return;
        }
        work.failedActions = 0;
        equipBestWeapon();
        npc.setTarget(target);
        attack(target);
        taskStatus(work, "正在安全猎取成年" + target.getDisplayName().getString());
        if (!target.isAlive()) {
            npc.absorbNearbyItemsAt(target.position(), 5.0D);
            work.foodAnimalTargetId = null;
            npc.setTarget(null);
        }
    }

    private BlockPos findFoodSourceBlock(int radius, Set<BlockPos> skippedTargets) {
        Predicate<BlockPos> candidate = position -> npc.level().hasChunkAt(position)
            && !skippedTargets.contains(position)
            && isHarvestableFoodSource(position)
            && hasSafeGatherStand(position, GATHER_INTERACTION_REACH);
        BlockPos nearLevel = findBlockAt(candidate, radius, 8);
        return nearLevel != null ? nearLevel : findBlockAt(candidate, radius, 24);
    }

    private boolean isHarvestableFoodSource(BlockPos position) {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        BlockState state = level.getBlockState(position);
        if (state.is(Blocks.MELON)) return true;
        if (state.getBlock() instanceof SweetBerryBushBlock) {
            return state.getValue(SweetBerryBushBlock.AGE) >= 2;
        }
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return false;
        try {
            return Block.getDrops(state, level, position, null, npc, bestToolForState(state)).stream()
                .anyMatch(this::isSafeProvisioningFood);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Animal findFoodAnimal(ActiveWork work, double radius) {
        List<Animal> candidates = npc.level().getEntitiesOfClass(
            Animal.class,
            npc.getBoundingBox().inflate(radius, 16.0D, radius),
            animal -> isSupportedFoodAnimal(animal)
                && !work.skippedFoodAnimalTargets.contains(animal.getUUID())
        );
        return candidates.stream()
            .filter(animal -> maySelectFoodAnimal(animal, candidates))
            .filter(animal -> npc.getNavigation().createPath(animal, 0) != null)
            .min(Comparator.comparingDouble(npc::distanceToSqr))
            .orElse(null);
    }

    private Animal resolveFoodAnimalTarget(ActiveWork work) {
        if (work.foodAnimalTargetId == null || !(npc.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(work.foodAnimalTargetId);
        if (entity instanceof Animal animal && isSupportedFoodAnimal(animal)
            && !isProtectedFoodAnimal(animal)) return animal;
        work.foodAnimalTargetId = null;
        return null;
    }

    private boolean maySelectFoodAnimal(Animal animal, List<Animal> candidates) {
        int nearbyAdults = (int) candidates.stream()
            .filter(other -> other.getType() == animal.getType() && !other.isBaby())
            .count();
        return FoodProvisionPolicy.mayHunt(
            !animal.isBaby(),
            animal.hasCustomName(),
            animal instanceof TamableAnimal tamable && tamable.isTame(),
            animal.isLeashed(),
            isNearOwnerHome(animal.blockPosition(), 32),
            nearbyAdults
        );
    }

    private boolean isProtectedFoodAnimal(Animal animal) {
        return animal.isBaby()
            || animal.hasCustomName()
            || animal instanceof TamableAnimal tamable && tamable.isTame()
            || animal.isLeashed()
            || isNearOwnerHome(animal.blockPosition(), 32);
    }

    private boolean isSupportedFoodAnimal(Animal animal) {
        return animal.isAlive() && (animal instanceof Pig || animal instanceof Cow || animal instanceof Sheep);
    }

    private boolean isNearOwnerHome(BlockPos position, int radius) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) return false;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        return home.dimension().equals(level.dimension()) && home.position().distSqr(position) <= radius * radius;
    }

    private BlockPos findHomeFoodStorage(ActiveWork work, int radius) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) return null;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        if (home.dimension().equals(level.dimension())) {
            int clampedRadius = HomeStoragePolicy.clampRadius(radius);
            ChunkPos homeChunk = new ChunkPos(home.position());
            int chunkRadius = Math.max(0, (clampedRadius + 15) / 16);
            for (int x = homeChunk.x - chunkRadius; x <= homeChunk.x + chunkRadius; x++) {
                for (int z = homeChunk.z - chunkRadius; z <= homeChunk.z + chunkRadius; z++) {
                    level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, new ChunkPos(x, z), 2, npc.getUUID());
                    level.getChunk(x, z);
                }
            }
        }
        for (BlockPos position : NpcHomeStorage.findContainers(level, home, radius)) {
            if (work.skippedStorageTargets.contains(position)) continue;
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (blockEntity instanceof Container container
                && !(blockEntity instanceof AbstractFurnaceBlockEntity)
                && containerProvisioningFoodCount(work, container) > 0) return position;
        }
        return null;
    }

    private int provisioningFoodCount(ActiveWork work) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (isProvisioningFoodFor(work, stack)) count += stack.getCount();
        }
        return count;
    }

    private int containerProvisioningFoodCount(ActiveWork work, Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isProvisioningFoodFor(work, stack)) count += stack.getCount();
        }
        return count;
    }

    private int withdrawProvisioningFood(ActiveWork work, Container container, int requested) {
        List<Integer> sourceSlots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (isProvisioningFoodFor(work, container.getItem(slot))) sourceSlots.add(slot);
        }
        sourceSlots.sort(Comparator.comparingInt((Integer slot) -> provisioningFoodScore(container.getItem(slot))).reversed());
        int moved = 0;
        for (int source : sourceSlots) {
            if (moved >= requested) break;
            ItemStack stored = container.getItem(source);
            int wanted = Math.min(stored.getCount(), requested - moved);
            ItemStack remainder = stored.copyWithCount(wanted);
            for (int target = 0; target < CodexNpcEntity.BACKPACK_SIZE && !remainder.isEmpty(); target++) {
                remainder = npc.inventory().insertItem(target, remainder, false);
            }
            int accepted = wanted - remainder.getCount();
            if (accepted <= 0) continue;
            stored.shrink(accepted);
            container.setItem(source, stored);
            moved += accepted;
        }
        return moved;
    }

    private int depositProvisioningFood(ActiveWork work, Container container, int requested) {
        List<Integer> sourceSlots = new ArrayList<>();
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            if (isProvisioningFoodFor(work, npc.inventory().getStackInSlot(slot))) sourceSlots.add(slot);
        }
        sourceSlots.sort(Comparator.comparingInt(
            (Integer slot) -> provisioningFoodScore(npc.inventory().getStackInSlot(slot))
        ).reversed());
        int moved = 0;
        for (int source : sourceSlots) {
            if (moved >= requested) break;
            ItemStack stack = npc.inventory().getStackInSlot(source);
            int remaining = Math.min(stack.getCount(), requested - moved);
            for (int target = 0; target < container.getContainerSize() && remaining > 0; target++) {
                if (!container.canPlaceItem(target, stack)) continue;
                ItemStack existing = container.getItem(target);
                if (existing.isEmpty()) {
                    int count = Math.min(remaining, stack.getMaxStackSize());
                    container.setItem(target, stack.copyWithCount(count));
                    stack.shrink(count);
                    moved += count;
                    remaining -= count;
                } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                    int count = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                    if (count <= 0) continue;
                    existing.grow(count);
                    stack.shrink(count);
                    moved += count;
                    remaining -= count;
                }
            }
            npc.inventory().setStackInSlot(source, stack);
        }
        return moved;
    }

    private int throwProvisioningFood(ActiveWork work, int requested, ServerPlayer recipient) {
        List<Integer> sourceSlots = new ArrayList<>();
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            if (isProvisioningFoodFor(work, npc.inventory().getStackInSlot(slot))) sourceSlots.add(slot);
        }
        sourceSlots.sort(Comparator.comparingInt(
            (Integer slot) -> provisioningFoodScore(npc.inventory().getStackInSlot(slot))
        ).reversed());
        int moved = 0;
        for (int source : sourceSlots) {
            if (moved >= requested) break;
            ItemStack stack = npc.inventory().getStackInSlot(source);
            int count = Math.min(stack.getCount(), requested - moved);
            ItemStack transfer = stack.copyWithCount(count);
            stack.shrink(count);
            npc.inventory().setStackInSlot(source, stack);
            spawnThrownStack(transfer, recipient);
            FoodSurvivalLiveFixture.recordDelivery(npc, recipient, transfer);
            moved += count;
        }
        if (moved > 0) npc.swing(InteractionHand.MAIN_HAND);
        return moved;
    }

    private boolean isSafeProvisioningFood(ItemStack stack) {
        if (stack.isEmpty() || stack.hasCustomHoverName() || stack.hasFoil()) return false;
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.is(Items.CHORUS_FRUIT) || stack.is(Items.PUFFERFISH)
            || stack.is(Items.SPIDER_EYE) || stack.is(Items.POISONOUS_POTATO)
            || stack.is(Items.ROTTEN_FLESH)) return false;
        FoodProperties food = stack.getFoodProperties(npc);
        return food != null && food.getEffects().isEmpty();
    }

    private boolean isProvisioningFoodFor(ActiveWork work, ItemStack stack) {
        return isSafeProvisioningFood(stack)
            && FoodProvisionPolicy.acceptsSourceItem(provisioningSource(work), itemId(stack));
    }

    private String provisioningSource(ActiveWork work) {
        String category = string(work.spec, "foodCategory", "any");
        if ("meat".equals(category)) return "hunt";
        if ("plant".equals(category)) return "forage";
        return string(work.spec, "source", "auto");
    }

    private int provisioningFoodScore(ItemStack stack) {
        FoodProperties food = stack.getFoodProperties(npc);
        if (food == null) return Integer.MIN_VALUE;
        int score = food.getNutrition() * 10 + Math.round(food.getSaturationModifier() * 20.0F);
        String value = itemId(stack);
        if (value.contains("cooked_") || value.contains("baked_") || value.equals("minecraft:bread")) score += 30;
        return score;
    }

    private void tickEat(ActiveWork work) {
        String requestedId = string(work.spec, "itemId", "");
        if (!work.initialized) {
            if (!requestedId.isBlank()) ResourceSelector.parse(requestedId);
            npc.cancelManagedEating();
            work.initialized = true;
            work.requestedCount = Math.max(1, integer(work.spec, "count", 1));
            work.failedActions = 0;
        }

        if (work.sourceSlot < 0 && HungerPolicy.explicitEatingShouldStop(npc.foodLevel(), work.completed, work.requestedCount)) {
            complete(work, work.completed == 0 ? "饱食度已满，无需继续进食" : "已经吃饱，共吃下 " + work.completed + " 份食物");
            return;
        }

        if (work.sourceSlot < 0) {
            work.sourceSlot = findFoodSlot(requestedId);
            if (work.sourceSlot < 0) {
                fail(work, requestedId.isBlank()
                    ? "NPC 背包中没有可食用的食物"
                    : "NPC 背包中没有可食用的 " + requestedId, "FOOD_MISSING");
                return;
            }
            ItemStack held = npc.inventory().getStackInSlot(work.sourceSlot);
            work.eatingSequence = npc.eatingCompletionSequence();
            if (!npc.startManagedEating(work.sourceSlot)) {
                taskStatus(work, "正在准备进食，饱食度 " + npc.foodLevel() + "/20");
                return;
            }
            progress(work, work.completed / (double) work.requestedCount,
                "正在吃 " + held.getHoverName().getString() + "，饱食度 " + npc.foodLevel() + "/20");
            return;
        }

        if (npc.isManagedEating()) {
            taskStatus(work, "正在吃东西，饱食度 " + npc.foodLevel() + "/20");
            return;
        }
        if (npc.eatingCompletionSequence() <= work.eatingSequence) {
            work.sourceSlot = -1;
            if (++work.failedActions >= 3) {
                fail(work, "进食动作没有完成，已停止本次进食", "EAT_ACTION_STALLED");
            }
            return;
        }
        String eatenName = npc.lastEatenName();
        work.completed++;
        work.failedActions = 0;
        work.sourceSlot = -1;
        if (HungerPolicy.explicitEatingShouldStop(npc.foodLevel(), work.completed, work.requestedCount)) {
            complete(work, "已吃下 " + work.completed + " 份 " + eatenName + "，饱食度 " + npc.foodLevel() + "/20");
            return;
        }
        progress(work, work.completed / (double) work.requestedCount,
            "已吃 " + work.completed + "/" + work.requestedCount + "，饱食度 " + npc.foodLevel() + "/20");
    }

    private void tickCombat(ActiveWork work) {
        String targetType = string(work.spec, "targetType", "hostile");
        double radius = number(work.spec, "maxDistance", 24);
        LivingEntity target = nearestHostile(npc, radius, targetType);
        if (target == null) {
            if (++work.noWorkTicks >= 30) complete(work, "附近威胁已清除");
            return;
        }
        work.noWorkTicks = 0;
        equipBestWeapon();
        attack(target);
        if (work.ticks % 10 == 0) progress(work, Math.min(0.9, work.ticks / 200.0), "正在应对 " + target.getDisplayName().getString());
        if (work.ticks > 20 * 60 * 3) fail(work, "战斗超时，已停止追击", "COMBAT_TIMEOUT");
    }

    private void tickBuild(ActiveWork work) {
        if (work.plan == null) {
            fail(work, "缺少已确认的建筑计划", "BUILD_PLAN_MISSING");
            return;
        }
        if (!work.initialized) {
            if (!work.plan.has("blocks") || !work.plan.get("blocks").isJsonArray()
                || !work.plan.has("origin") || !work.plan.get("origin").isJsonObject()) {
                fail(work, "建筑计划缺少方块或原点", "BUILD_PLAN_INVALID");
                return;
            }
            work.initialized = true;
            BlockPos restoredOrigin = work.buildOrigin;
            work.buildOrigin = restoredOrigin == null
                ? block(work.plan.getAsJsonObject("origin"))
                : restoredOrigin;
            if (restoredOrigin == null && npc.level() instanceof ServerLevel level) {
                String placement = string(work.spec, "placement", "plan-origin");
                BlockPos placementAnchor = work.spec.has("placementAnchor")
                    && work.spec.get("placementAnchor").isJsonObject()
                    ? block(work.spec.getAsJsonObject("placementAnchor"))
                    : null;
                BlockPos surfaceProbe = BuildPlacementPolicy.surfaceProbe(
                    placement,
                    work.buildOrigin,
                    placementAnchor
                );
                int surfaceY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    surfaceProbe.getX(),
                    surfaceProbe.getZ()
                );
                int offsetY = work.spec.has("offset") && work.spec.get("offset").isJsonObject()
                    ? integer(work.spec.getAsJsonObject("offset"), "y", 0)
                    : 0;
                int originY = BuildPlacementPolicy.originY(
                    placement,
                    work.buildOrigin.getY(),
                    surfaceY,
                    offsetY
                );
                work.buildOrigin = new BlockPos(work.buildOrigin.getX(), originY, work.buildOrigin.getZ());
            }
            BuildMaterialPaletteResolver.CachedResult cachedPalette =
                BuildMaterialPaletteResolver.validateCachedMetadata((ServerLevel) npc.level(), work.plan);
            if (!cachedPalette.error().isBlank()) {
                fail(work, cachedPalette.error(), "BUILD_PALETTE_CHECKPOINT_INVALID");
                return;
            }
            JsonObject paletteMetadata = cachedPalette.metadata();
            if (paletteMetadata == null) {
                BuildMaterialPaletteResolver.Result palette = BuildMaterialPaletteResolver.resolve(
                    npc,
                    work.spec,
                    work.plan.getAsJsonArray("blocks"),
                    work.buildOrigin
                );
                if (!palette.error().isBlank()) {
                    fail(work, palette.error(), "BUILD_PALETTE_UNAVAILABLE");
                    return;
                }
                work.plan.add("blocks", palette.blocks());
                work.plan.add("_codexMaterialPalette", palette.metadata());
                paletteMetadata = palette.metadata();
            }
            work.buildBlocks = work.plan.getAsJsonArray("blocks");
            work.requestedCount = work.buildBlocks.size();
            if (work.buildIndex < 0 || work.buildIndex > work.requestedCount) {
                fail(work, "建筑检查点索引越界，已拒绝继续执行", "BUILD_CHECKPOINT_INVALID");
                return;
            }
            if (work.buildCheckpointInvalid) {
                fail(work, "建筑材料检查点无效，已拒绝重放可能重复消耗材料的动作", "BUILD_CHECKPOINT_INVALID");
                return;
            }
            work.completed = work.buildIndex;
            if (work.buildLastProgressTick <= 0) work.buildLastProgressTick = work.ticks;
            if (work.buildIndex == 0 && paletteMetadata.has("summary")) {
                progress(work, 0.0D, paletteMetadata.get("summary").getAsString());
            }
        }
        if (work.ticks - work.buildLastProgressTick > BUILD_MATERIAL_STALL_TIMEOUT_TICKS) {
            fail(work, "建造阶段长时间没有进展", "BUILD_STALLED");
            return;
        }
        if (hasCraftGatherPrerequisite(work)) {
            tickCraftGatherPrerequisite(work);
            return;
        }
        if (!work.buildMaterialGoals.isEmpty()) {
            tickBuildMaterialPrerequisite(work);
            return;
        }
        int scanStart = work.buildIndex;
        while (work.buildIndex < work.buildBlocks.size()) {
            JsonObject entry = work.buildBlocks.get(work.buildIndex).getAsJsonObject();
            String blockId = string(entry, "blockId", "minecraft:air");
            BlockPos relative = block(entry.getAsJsonObject("position"));
            BlockPos target = work.buildOrigin.offset(relative);
            BlockState currentState = npc.level().getBlockState(target);
            String current = id(currentState.getBlock());
            if (blockId.equals("minecraft:air")) {
                work.buildIndex++;
                continue;
            }
            if (current.equals(blockId)) {
                try {
                    if (BuildStatePolicy.matches(currentState, buildProperties(entry))) {
                        work.buildIndex++;
                        continue;
                    }
                } catch (IllegalArgumentException error) {
                    fail(work, "建筑方块状态无效：" + error.getMessage(), "INVALID_BUILD_STATE");
                    return;
                }
            }
            work.targetBlock = target;
            break;
        }
        if (work.buildIndex > scanStart && work.buildIndex < work.buildBlocks.size()) {
            work.completed = work.buildIndex;
            work.buildLastProgressTick = work.ticks;
            progress(work, work.buildIndex / (double) work.requestedCount,
                "已确认 " + work.buildIndex + "/" + work.requestedCount + " 个方块");
        }
        if (work.buildIndex >= work.buildBlocks.size()) {
            work.completed = work.buildIndex;
            complete(work, "建筑已完成，共处理 " + work.buildIndex + " 个方块");
            return;
        }
        JsonObject entry = work.buildBlocks.get(work.buildIndex).getAsJsonObject();
        String blockId = string(entry, "blockId", "minecraft:air");
        BlockState currentState = npc.level().getBlockState(work.targetBlock);
        boolean correctingState = id(currentState.getBlock()).equals(blockId)
            && !BuildPlacementPolicy.isFluidSource(blockId);
        if (correctingState) {
            if (!approachBuildTarget(work, work.targetBlock, 1.08)) return;
            if (work.ticks - work.lastActionTick < 5) return;
            work.lastActionTick = work.ticks;
            npc.swing(InteractionHand.MAIN_HAND, true);
            if (!applyExactBuildState(work, work.targetBlock, entry)) return;
            finishBuildBlock(work, 0.005F);
            return;
        }
        BuildMaterialPrerequisitePolicy.MaterialPlan materialPlan =
            BuildMaterialPrerequisitePolicy.plan(BuildPlacementPolicy.materialItemId(blockId));
        if (materialPlan.action() == BuildMaterialPrerequisitePolicy.Action.TILL) {
            tickBuildTilledBlock(work, entry, currentState, materialPlan);
            return;
        }
        if (!currentState.canBeReplaced()) {
            tickBuildClearance(work, currentState);
            return;
        }
        String materialId = BuildPlacementPolicy.materialItemId(blockId);
        Item item = item(materialId);
        if (!BuildPlacementPolicy.isFluidSource(blockId) && !(item instanceof BlockItem)) {
            fail(work, "建筑材料不是可放置方块：" + blockId, "INVALID_BUILD_BLOCK");
            return;
        }
        int sourceSlot = npc.creativeResources() ? -1 : findItemSlot(materialId);
        if (!npc.creativeResources() && sourceSlot < 0) {
            int remaining = remainingBuildMaterialCount(work, materialId);
            int stackLimit = item == Items.AIR ? BUILD_MATERIAL_BATCH_LIMIT : Math.max(1, item.getMaxStackSize());
            int batch = Math.max(1, Math.min(remaining, Math.min(BUILD_MATERIAL_BATCH_LIMIT, stackLimit)));
            if (!beginBuildMaterialGoal(work, materialId, batch, "建筑缺少 " + materialId + "，开始补齐材料链")) return;
            tickBuildMaterialPrerequisite(work);
            return;
        }
        if (!approachBuildTarget(work, work.targetBlock, 1.08)) return;
        if (work.ticks - work.lastActionTick < 5) return;
        work.lastActionTick = work.ticks;
        Direction supportDirection = findSupport(work.targetBlock);
        if (supportDirection == null) {
            fail(work, "方块没有可点击的支撑面：" + work.targetBlock.toShortString(), "NO_BUILD_SUPPORT");
            return;
        }
        BlockPos support = work.targetBlock.relative(supportDirection);
        Direction face = supportDirection.getOpposite();
        // Swing before every placement attempt so retries are visible in-world.
        npc.swing(InteractionHand.MAIN_HAND, true);
        ItemStack material = npc.creativeResources() ? new ItemStack(item) : npc.inventory().getStackInSlot(sourceSlot);
        InteractionResult result = BuildPlacementPolicy.isFluidSource(blockId)
            ? proxy.useItemToward(support, face, material, sourceSlot)
            : proxy.useItemOn(support, face, material, sourceSlot);
        boolean placed = id(npc.level().getBlockState(work.targetBlock).getBlock()).equals(blockId);
        // Some Forge FakePlayer interactions report PASS even when the target is safe to place.
        // Use a guarded direct placement fallback only for ordinary BlockItems and only when the
        // normal interaction did not consume an action. World-border and owner permission checks
        // keep this fallback from bypassing protection or placing outside the loaded world.
        if (!placed && !result.consumesAction() && !BuildPlacementPolicy.isFluidSource(blockId)) {
            placed = placeBuildBlockDirectly(work.targetBlock, blockId, item, sourceSlot);
        }
        if (!placed || !id(npc.level().getBlockState(work.targetBlock).getBlock()).equals(blockId)) {
            if (++work.failedActions >= 3) fail(work, "BLOCK_PLACE_DENIED: " + blockId, "BLOCK_PLACE_DENIED");
            return;
        }
        if (!applyExactBuildState(work, work.targetBlock, entry)) return;
        finishBuildBlock(work, 0.025F);
    }

    /** Clears an ordinary obstacle through Forge's normal break hooks, then retries the same blueprint index. */
    private void tickBuildClearance(ActiveWork work, BlockState occupiedState) {
        BlockPos target = work.targetBlock;
        String occupiedId = id(occupiedState.getBlock());
        BlockEntity blockEntity = npc.level().getBlockEntity(target);
        boolean fluid = !npc.level().getFluidState(target).isEmpty();
        float destroySpeed = occupiedState.getDestroySpeed(npc.level(), target);
        if (!BuildClearancePolicy.mayClear(
            occupiedId,
            occupiedState.isAir(),
            occupiedState.canBeReplaced(),
            fluid,
            blockEntity != null,
            destroySpeed
        )) {
            fail(
                work,
                "施工位置包含受保护方块，已保留失败点：" + occupiedId + " @ " + target.toShortString(),
                "BUILD_SITE_PROTECTED"
            );
            return;
        }
        if (!approachBuildTarget(work, target, 1.08)) return;
        if (work.ticks - work.lastActionTick < 8) return;
        work.lastActionTick = work.ticks;
        int toolSlot = bestToolSlot(occupiedState);
        if (toolSlot >= 0) equipMainHand(toolSlot);
        npc.swing(InteractionHand.MAIN_HAND, true);
        boolean broken = proxy.breakBlock(
            target,
            toolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1
        );
        BlockState after = npc.level().getBlockState(target);
        boolean cleared = after.isAir() || after.canBeReplaced();
        if (!broken && !cleared) {
            taskStatus(work, "正在重试清理施工位 " + target.toShortString());
            if (++work.failedActions >= 3) {
                fail(
                    work,
                    "施工位方块无法破坏，已保留失败点：" + occupiedId + " @ " + target.toShortString(),
                    "BLOCK_BREAK_DENIED"
                );
            }
            return;
        }
        if (!npc.creativeResources()) npc.absorbNearbyItemsAt(Vec3.atCenterOf(target), 3.0D);
        work.failedActions = 0;
        work.stalledTicks = 0;
        work.buildPathFailures = 0;
        work.lastBuildPathAttemptTick = -1;
        work.lastDistance = -1;
        work.buildLastProgressTick = work.ticks;
        npc.addExhaustion(0.04F);
        progress(
            work,
            activeProgress(work),
            "已清理占位方块 " + occupiedId + "，继续建造 " + (work.buildIndex + 1) + "/" + work.requestedCount
        );
    }

    /**
     * Runs material acquisition as a child phase of the original craft or
     * build task. Keeping the phase on the same ActiveWork preserves
     * cancellation, priority, combat interruption and save/reload semantics.
     */
    private void tickBuildMaterialPrerequisite(ActiveWork work) {
        if (hasCraftGatherPrerequisite(work)) {
            tickCraftGatherPrerequisite(work);
            return;
        }
        BuildMaterialGoal goal = work.buildMaterialGoals.peekFirst();
        if (goal == null) {
            work.buildPhase = "scan";
            return;
        }
        if (work.buildCheckpointInvalid) {
            fail(work, "材料依赖链检查点无效，已停止以避免重复消耗材料", "MATERIAL_CHECKPOINT_INVALID");
            return;
        }

        int available = inventoryCount(goal.selector);
        if (available > goal.lastInventoryCount) {
            goal.lastInventoryCount = available;
            goal.stalledTicks = 0;
            work.buildLastProgressTick = work.ticks;
        } else {
            goal.stalledTicks++;
        }
        if (available >= goal.targetCount) {
            finishBuildMaterialGoal(work, goal);
            return;
        }
        if (work.ticks - work.buildLastProgressTick > BUILD_MATERIAL_STALL_TIMEOUT_TICKS) {
            fail(work, "补充任务材料长时间没有进展：" + goal.itemId, "MATERIAL_STALLED");
            return;
        }

        if ((goal.attemptedRoutes & BUILD_ROUTE_STORAGE) == 0 && tickBuildMaterialStorage(work, goal)) return;
        if (active != work) return;

        BuildMaterialPrerequisitePolicy.MaterialPlan plan = BuildMaterialPrerequisitePolicy.plan(goal.itemId);
        if (plan.action() == BuildMaterialPrerequisitePolicy.Action.REJECT
            && isSafeRuntimePaletteGatherItem(goal.itemId)) {
            work.buildPhase = "gather";
            beginCraftGather(
                work,
                goal.selector,
                Math.max(1, goal.targetCount - inventoryCount(goal.selector)),
                "任务缺少安全标签资源，正在采集 " + goal.selector
            );
            return;
        }
        switch (plan.action()) {
            case GATHER -> {
                work.buildPhase = "gather";
                String gatherSelector = goal.selector.startsWith("#") ? goal.selector : plan.gatherSelector();
                if ("minecraft:string".equals(gatherSelector) && findUsableSwordSlot() < 0) {
                    prepareCraftedToolPrerequisite(
                        work,
                        "minecraft:wooden_sword",
                        "取得配方用线前先制作木剑",
                        "蜘蛛网采线需要剑，但木剑依赖链无法补齐",
                        "STRING_TOOL_RECIPE_MISSING",
                        "STRING_TOOL_MATERIALS_MISSING"
                    );
                    return;
                }
                if (prepareKnownGatherTool(work, gatherSelector)) return;
                if (active != work) return;
                beginCraftGather(
                    work,
                    gatherSelector,
                    Math.max(1, goal.targetCount - inventoryCount(goal.selector)),
                    "任务材料不足，正在采集 " + gatherSelector
                );
            }
            case CRAFT -> tickBuildCraftMaterial(work, goal, plan);
            case SMELT -> tickBuildSmeltMaterial(work, goal, plan);
            case TILL -> fail(work, "耕地方块只能在建筑目标位置现场生成", "BUILD_TILL_TARGET_REQUIRED");
            case REJECT -> {
                EntityMaterialAcquisitionPolicy.Route entityRoute =
                    EntityMaterialAcquisitionPolicy.routeFor(goal.itemId);
                if (entityRoute != EntityMaterialAcquisitionPolicy.Route.UNSUPPORTED) {
                    tickBuildEntityMaterial(work, goal, entityRoute);
                } else if (BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe(goal.itemId)
                    && findCraftRecipe(goal.itemId) != null) {
                    tickBuildCraftMaterial(work, goal, null);
                } else if (BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe(goal.itemId)
                    && findSmeltingRecipeByOutput(goal.itemId) != null) {
                    tickBuildSmeltMaterial(work, goal, null);
                } else {
                    fail(
                        work,
                        "无法安全自动取得任务材料 " + goal.itemId + "：" + plan.refusalReason(),
                        "MATERIAL_UNAVAILABLE"
                    );
                }
            }
        }
    }

    private boolean tickBuildMaterialStorage(ActiveWork work, BuildMaterialGoal goal) {
        work.buildPhase = "storage";
        ResourceSelector selector = ResourceSelector.parse(goal.selector);
        if (work.workstation == null) {
            work.workstation = findHomeStorage(selector, true, work, HomeStoragePolicy.DEFAULT_RADIUS);
            if (work.workstation == null) {
                goal.attemptedRoutes |= BUILD_ROUTE_STORAGE;
                work.skippedStorageTargets.clear();
                work.buildLastProgressTick = work.ticks;
                return false;
            }
        }
        if (!approachStorageTarget(work, work.workstation, 3.5, 1.08)) return true;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) {
            work.skippedStorageTargets.add(work.workstation);
            work.workstation = null;
            return true;
        }
        int moved = withdraw(container, goal.selector, goal.targetCount - inventoryCount(goal.selector));
        container.setChanged();
        if (moved > 0) {
            npc.swing(InteractionHand.MAIN_HAND);
            goal.lastInventoryCount = inventoryCount(goal.selector);
            goal.stalledTicks = 0;
            work.buildLastProgressTick = work.ticks;
            progress(work, activeProgress(work), "已从家中仓库取出 " + moved + " 个 " + goal.selector);
        }
        if (inventoryCount(goal.selector) >= goal.targetCount) return true;
        work.skippedStorageTargets.add(work.workstation);
        work.workstation = findHomeStorage(selector, true, work, HomeStoragePolicy.DEFAULT_RADIUS);
        if (work.workstation == null) {
            goal.attemptedRoutes |= BUILD_ROUTE_STORAGE;
            work.skippedStorageTargets.clear();
            work.buildLastProgressTick = work.ticks;
        }
        return true;
    }

    /**
     * Obtains the small, explicitly supported set of recipe ingredients that
     * come from living entities.  This is a child phase of the original craft
     * task, so interruption, persistence and cancellation keep the same task
     * identity instead of silently launching an unrelated hunt.
     */
    private void tickBuildEntityMaterial(
        ActiveWork work,
        BuildMaterialGoal goal,
        EntityMaterialAcquisitionPolicy.Route route
    ) {
        if (!"entity".equals(work.buildPhase)) initializeBuildEntitySearch(work);
        npc.absorbNearbyItems(3.0D);
        recordInventoryAction(work, "entity-material-pickup");
        int available = inventoryCount(goal.selector);
        if (available >= goal.targetCount) {
            clearBuildEntitySearch(work);
            finishBuildMaterialGoal(work, goal);
            return;
        }

        if (route == EntityMaterialAcquisitionPolicy.Route.SHEAR_WHITE_SHEEP) {
            int shearsSlot = findUsableShearsSlot();
            if (shearsSlot < 0) {
                prepareCraftedToolPrerequisite(
                    work,
                    "minecraft:shears",
                    "取得白色羊毛前先制作剪刀",
                    "取得白色羊毛需要剪刀，但剪刀依赖链无法补齐",
                    "ENTITY_SHEARS_RECIPE_MISSING",
                    "ENTITY_SHEARS_MATERIALS_MISSING"
                );
                return;
            }
            tickBuildShearMaterial(work, goal, shearsSlot);
            return;
        }

        tickBuildHuntMaterial(work, goal, route);
    }

    private void initializeBuildEntitySearch(ActiveWork work) {
        work.buildPhase = "entity";
        work.destination = null;
        work.targetBlock = null;
        work.bedSheepTargetId = null;
        work.foodAnimalTargetId = null;
        work.skippedBedSheepTargets.clear();
        work.skippedFoodAnimalTargets.clear();
        work.gatherSearchRadius = 16;
        work.gatherExcursions = 0;
        work.noWorkTicks = 0;
        work.lastSearchTick = -10;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.failedActions = 0;
    }

    private boolean tickBuildEntityExpedition(ActiveWork work, String label) {
        if (work.destination == null) return false;
        BlockPos searchArea = BlockPos.containing(work.destination);
        maintainTaskChunkTicket(searchArea);
        if (npc.position().distanceTo(work.destination) <= 3.5D) {
            npc.getNavigation().stop();
            work.destination = null;
            work.gatherSearchRadius = 16;
            work.lastSearchTick = -10;
            work.noWorkTicks = 0;
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return false;
        }
        approachGatherDestination(
            work,
            searchArea,
            3.5D,
            1.15D,
            "远程寻找" + label + "的搜索区不可达",
            "ENTITY_MATERIAL_SEARCH_UNREACHABLE"
        );
        return true;
    }

    private void tickBuildShearMaterial(ActiveWork work, BuildMaterialGoal goal, int shearsSlot) {
        if (tickBuildEntityExpedition(work, "白羊")) return;
        Sheep sheep = resolveBuildSheepTarget(work);
        if (sheep == null && work.ticks - work.lastSearchTick >= 10) {
            work.lastSearchTick = work.ticks;
            sheep = findBuildSheep(work, work.gatherSearchRadius);
            if (sheep != null) {
                work.bedSheepTargetId = sheep.getUUID();
                resetEntityMaterialNavigation(work);
                progress(work, activeProgress(work), "找到可安全剪毛的成年白羊，正在接近");
            } else if (work.gatherSearchRadius < GATHER_LOCAL_SEARCH_RADIUS) {
                work.gatherSearchRadius += 16;
            }
        }
        if (sheep == null) {
            advanceBuildEntityExpeditionOrFail(work, goal, "可安全剪毛的成年白羊");
            return;
        }
        if (!approachEntityMaterialTarget(work, sheep, "白羊")) return;
        if (work.ticks - work.lastActionTick < 8) return;

        work.lastActionTick = work.ticks;
        int before = inventoryCount(goal.selector);
        ItemStack shears = npc.inventory().getStackInSlot(shearsSlot);
        npc.getLookControl().setLookAt(sheep, 30.0F, 30.0F);
        npc.swing(InteractionHand.MAIN_HAND);
        InteractionResult result = proxy.interact(sheep, shears, shearsSlot);
        npc.absorbNearbyItemsAt(sheep.position(), 4.0D);
        int after = inventoryCount(goal.selector);
        if (!result.consumesAction() && !sheep.isSheared()) {
            if (++work.failedActions >= 3) {
                work.skippedBedSheepTargets.add(sheep.getUUID());
                work.bedSheepTargetId = null;
                work.failedActions = 0;
            }
            return;
        }
        work.bedSheepTargetId = null;
        work.failedActions = 0;
        resetEntityMaterialNavigation(work);
        recordBuildEntityMaterialProgress(work, goal, before, after, "白色羊毛");
    }

    private void tickBuildHuntMaterial(
        ActiveWork work,
        BuildMaterialGoal goal,
        EntityMaterialAcquisitionPolicy.Route route
    ) {
        String label = switch (route) {
            case HUNT_COW -> "成年牛";
            case HUNT_CHICKEN -> "成年鸡";
            case HUNT_SLIME -> "史莱姆";
            default -> "目标生物";
        };
        if (tickBuildEntityExpedition(work, label)) return;
        LivingEntity target = resolveBuildEntityMaterialTarget(work, route);
        if (target == null && work.ticks - work.lastSearchTick >= 10) {
            work.lastSearchTick = work.ticks;
            target = findBuildEntityMaterialTarget(work, route, work.gatherSearchRadius);
            if (target != null) {
                work.foodAnimalTargetId = target.getUUID();
                resetEntityMaterialNavigation(work);
                progress(work, activeProgress(work), "找到可安全取得材料的" + label + "，正在接近");
            } else if (work.gatherSearchRadius < GATHER_LOCAL_SEARCH_RADIUS) {
                work.gatherSearchRadius += 16;
            }
        }
        if (target == null) {
            advanceBuildEntityExpeditionOrFail(work, goal, label);
            return;
        }

        if (!target.isAlive()) {
            int before = inventoryCount(goal.selector);
            npc.absorbNearbyItemsAt(target.position(), 5.0D);
            int after = inventoryCount(goal.selector);
            work.foodAnimalTargetId = null;
            npc.setTarget(null);
            resetEntityMaterialNavigation(work);
            recordBuildEntityMaterialProgress(work, goal, before, after, goal.itemId);
            return;
        }
        if (!approachEntityMaterialTarget(work, target, label)) return;
        equipBestWeapon();
        npc.setTarget(target);
        attack(target);
        taskStatus(work, "正在安全取得 " + goal.itemId + "：" + label);
        if (!target.isAlive()) {
            int before = inventoryCount(goal.selector);
            npc.absorbNearbyItemsAt(target.position(), 5.0D);
            int after = inventoryCount(goal.selector);
            work.foodAnimalTargetId = null;
            npc.setTarget(null);
            resetEntityMaterialNavigation(work);
            recordBuildEntityMaterialProgress(work, goal, before, after, goal.itemId);
        }
    }

    private Sheep resolveBuildSheepTarget(ActiveWork work) {
        if (work.bedSheepTargetId == null || !(npc.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(work.bedSheepTargetId);
        if (entity instanceof Sheep sheep && isSafeBuildSheep(work, sheep)) return sheep;
        work.bedSheepTargetId = null;
        return null;
    }

    private Sheep findBuildSheep(ActiveWork work, int requestedRadius) {
        int radius = Math.max(8, Math.min(GATHER_LOCAL_SEARCH_RADIUS, requestedRadius));
        return npc.level().getEntitiesOfClass(
            Sheep.class,
            npc.getBoundingBox().inflate(radius, 16, radius),
            sheep -> isSafeBuildSheep(work, sheep)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private boolean isSafeBuildSheep(ActiveWork work, Sheep sheep) {
        return sheep.getColor() == DyeColor.WHITE
            && !sheep.isSheared()
            && !work.skippedBedSheepTargets.contains(sheep.getUUID())
            && EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
                sheep.isAlive(),
                !sheep.isBaby(),
                sheep.hasCustomName(),
                false,
                sheep.isLeashed(),
                isNearOwnerHome(sheep.blockPosition(), 32),
                1,
                true
            );
    }

    private LivingEntity resolveBuildEntityMaterialTarget(
        ActiveWork work,
        EntityMaterialAcquisitionPolicy.Route route
    ) {
        if (work.foodAnimalTargetId == null || !(npc.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(work.foodAnimalTargetId);
        if (entity instanceof LivingEntity living && isSafeBuildEntityMaterialTarget(work, living, route)) {
            return living;
        }
        work.foodAnimalTargetId = null;
        return null;
    }

    private LivingEntity findBuildEntityMaterialTarget(
        ActiveWork work,
        EntityMaterialAcquisitionPolicy.Route route,
        int requestedRadius
    ) {
        int radius = Math.max(8, Math.min(GATHER_LOCAL_SEARCH_RADIUS, requestedRadius));
        return npc.level().getEntitiesOfClass(
            LivingEntity.class,
            npc.getBoundingBox().inflate(radius, 16, radius),
            entity -> isSafeBuildEntityMaterialTarget(work, entity, route)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private boolean isSafeBuildEntityMaterialTarget(
        ActiveWork work,
        LivingEntity entity,
        EntityMaterialAcquisitionPolicy.Route route
    ) {
        if (work.skippedFoodAnimalTargets.contains(entity.getUUID())) return false;
        if (route == EntityMaterialAcquisitionPolicy.Route.HUNT_SLIME) {
            return entity instanceof Slime
                && EntityMaterialAcquisitionPolicy.mayUseSlime(
                    entity.isAlive(), entity.hasCustomName(), isNearOwnerHome(entity.blockPosition(), 32)
                );
        }
        boolean correctType = route == EntityMaterialAcquisitionPolicy.Route.HUNT_COW
            ? entity instanceof Cow
            : route == EntityMaterialAcquisitionPolicy.Route.HUNT_CHICKEN && entity instanceof Chicken;
        if (!correctType || !(entity instanceof Animal animal)) return false;
        int nearbyAdults = (int) npc.level().getEntitiesOfClass(
            animal.getClass(),
            animal.getBoundingBox().inflate(32.0D, 16.0D, 32.0D),
            other -> other.isAlive() && !other.isBaby()
        ).size();
        return EntityMaterialAcquisitionPolicy.mayUsePassiveAnimal(
            animal.isAlive(),
            !animal.isBaby(),
            animal.hasCustomName(),
            animal instanceof TamableAnimal tamable && tamable.isTame(),
            animal.isLeashed(),
            isNearOwnerHome(animal.blockPosition(), 32),
            nearbyAdults,
            false
        );
    }

    private boolean approachEntityMaterialTarget(ActiveWork work, LivingEntity target, String label) {
        Vec3 eye = npc.getEyePosition();
        AABB bounds = target.getBoundingBox();
        double interactionDistance = EntityInteractionDistancePolicy.distanceToExpandedBounds(
            eye.x, eye.y, eye.z,
            bounds.minX, bounds.minY, bounds.minZ,
            bounds.maxX, bounds.maxY, bounds.maxZ,
            EntityInteractionDistancePolicy.TARGETING_MARGIN
        );
        if (interactionDistance <= GATHER_INTERACTION_REACH) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        double centerDistance = npc.distanceTo(target);
        ServerPlayer owner = npc.owner();
        if (centerDistance > config.npcRecallDistance && owner != null && owner.hasPermissions(2)
            && npc.level() instanceof ServerLevel level) {
            BlockPos destination = safeTaskPositionNear(level, target.blockPosition());
            npc.getNavigation().stop();
            npc.teleportTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D);
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            resetEntityMaterialNavigation(work);
            taskStatus(work, "已传送到远处" + label + "附近");
            return false;
        }
        if (npc.getNavigation().isDone()) {
            Path path = npc.getNavigation().createPath(target.blockPosition(), 2);
            if (path != null) npc.getNavigation().moveTo(path, 1.15D);
            else navigateTowardBlock(target.blockPosition(), 1.15D);
        }
        npc.addExhaustion(0.002F);
        taskStatus(work, "正在走向" + label + "，距离 " + Math.round(centerDistance) + " 格");
        trackNavigation(work, interactionDistance);
        if (work.stalledTicks > 200) {
            work.skippedFoodAnimalTargets.add(target.getUUID());
            work.foodAnimalTargetId = null;
            if (target instanceof Sheep) {
                work.skippedBedSheepTargets.add(target.getUUID());
                work.bedSheepTargetId = null;
            }
            npc.setTarget(null);
            npc.getNavigation().stop();
            resetEntityMaterialNavigation(work);
        }
        return false;
    }

    private void advanceBuildEntityExpeditionOrFail(ActiveWork work, BuildMaterialGoal goal, String label) {
        if (++work.noWorkTicks <= 40 || work.gatherSearchRadius < GATHER_LOCAL_SEARCH_RADIUS) return;
        if (work.gatherExcursions >= GATHER_MAX_EXCURSIONS) {
            fail(work, "扩大搜索范围后仍没有找到" + label + "，无法取得 " + goal.itemId,
                "ENTITY_MATERIAL_SOURCE_NOT_FOUND");
            return;
        }
        work.gatherExcursions++;
        work.destination = nextGatherSearchDestination(work.gatherExcursions);
        work.gatherSearchRadius = 16;
        work.noWorkTicks = 0;
        work.lastSearchTick = -10;
        work.skippedBedSheepTargets.clear();
        work.skippedFoodAnimalTargets.clear();
        progress(work, activeProgress(work), "附近没有" + label + "，正在前往第 "
            + work.gatherExcursions + " 个搜索区");
    }

    private void recordBuildEntityMaterialProgress(
        ActiveWork work,
        BuildMaterialGoal goal,
        int before,
        int after,
        String label
    ) {
        recordInventoryAction(work, "entity-material-pickup");
        if (after > before) {
            goal.lastInventoryCount = after;
            goal.stalledTicks = 0;
            work.buildLastProgressTick = work.ticks;
        }
        progress(work, activeProgress(work), "已取得" + label + "，当前 " + after + "/" + goal.targetCount
            + (after == before ? "，正在拾取掉落物" : ""));
        if (after >= goal.targetCount) {
            clearBuildEntitySearch(work);
            finishBuildMaterialGoal(work, goal);
        }
    }

    private void resetEntityMaterialNavigation(ActiveWork work) {
        work.noWorkTicks = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.failedActions = 0;
    }

    private void clearBuildEntitySearch(ActiveWork work) {
        boolean hadHuntTarget = work.foodAnimalTargetId != null;
        work.bedSheepTargetId = null;
        work.foodAnimalTargetId = null;
        work.destination = null;
        work.skippedBedSheepTargets.clear();
        work.skippedFoodAnimalTargets.clear();
        if (hadHuntTarget) npc.setTarget(null);
        npc.getNavigation().stop();
        resetEntityMaterialNavigation(work);
    }

    private void tickBuildCraftMaterial(
        ActiveWork work,
        BuildMaterialGoal goal,
        BuildMaterialPrerequisitePolicy.MaterialPlan plan
    ) {
        work.buildPhase = "craft";
        String materialContextId = goal.materialContextId;
        if (plan != null && ensureBuildMaterialUpstream(work, goal, plan)) return;
        if (active != work) return;
        Recipe<?> recipe = findCraftRecipe(goal.itemId, materialContextId);
        if (recipe == null) {
            fail(work, "没有找到任务材料 " + goal.itemId + " 的真实制作配方", "BUILD_MATERIAL_RECIPE_MISSING");
            return;
        }
        if (hasSuspendedDeepMining(work) && tickMiningInventoryCleanup(work, recipe, goal.itemId)) {
            work.buildLastProgressTick = work.ticks;
            return;
        }
        if (craftOnePrerequisite(work, goal.itemId, "正在制作任务材料", materialContextId)) {
            work.buildLastProgressTick = work.ticks;
            return;
        }
        if (active != work) return;
        if (prepareCraftPrerequisite(work, goal.itemId, recipe)) return;
        if (active != work) return;
        Ingredient missing = firstMissingIngredient(recipe.getIngredients());
        if (missing != null) {
            List<BuildMaterialPrerequisitePolicy.Requirement> requirements = plan == null
                ? List.of()
                : plan.upstreamRequirements();
            String upstreamId = selectBuildIngredientItem(materialContextId, missing, requirements);
            if (!upstreamId.isBlank()) {
                beginBuildMaterialGoal(
                    work,
                    upstreamId,
                    inventoryCount(upstreamId) + 1,
                    "制作 " + goal.itemId + " 前先补充配方原料 " + upstreamId
                );
                return;
            }
        }
        ItemStack output = recipe.getResultItem(npc.level().registryAccess()).copy();
        if (!output.isEmpty() && !canInsert(output)) {
            fail(work, "NPC 背包没有空间接收任务材料 " + goal.itemId, "INVENTORY_FULL");
            return;
        }
        fail(work, "任务材料 " + goal.itemId + " 的上游材料无法取得", "BUILD_MATERIAL_UPSTREAM_UNAVAILABLE");
    }

    private void tickBuildSmeltMaterial(
        ActiveWork work,
        BuildMaterialGoal goal,
        BuildMaterialPrerequisitePolicy.MaterialPlan plan
    ) {
        work.buildPhase = "smelt";
        List<BuildMaterialPrerequisitePolicy.Requirement> policyRequirements = plan == null
            ? List.of()
            : plan.upstreamRequirements();
        Recipe<?> recipe = findSmeltingRecipeByOutput(goal.itemId, policyRequirements);
        if (recipe == null || recipe.getIngredients().isEmpty()) {
            fail(work, "没有找到任务材料 " + goal.itemId + " 的真实熔炼配方", "BUILD_MATERIAL_SMELT_RECIPE_MISSING");
            return;
        }
        // Smelting can be reached after a long mining run has filled every
        // backpack slot. Free bounded low-value terrain slots before loading
        // or withdrawing from the task-owned furnace so its inputs and output
        // never force a retryable INVENTORY_FULL failure.
        if (tickMiningInventoryCleanup(work, recipe, goal.itemId)) {
            work.buildLastProgressTick = work.ticks;
            return;
        }
        Ingredient inputIngredient = recipe.getIngredients().get(0);
        String inputId = selectBuildIngredientItem(
            goal.materialContextId,
            inputIngredient,
            policyRequirements
        );
        if (inputId.isBlank()) {
            fail(work, "无法确定 " + goal.itemId + " 的安全熔炼原料", "BUILD_MATERIAL_INPUT_UNAVAILABLE");
            return;
        }
        int outputPerBatch = Math.max(1, recipe.getResultItem(npc.level().registryAccess()).getCount());
        String furnaceId = "minecraft:furnace";
        if (goal.ownedFurnace == null) {
            goal.ownedFurnace = findReusableBuildMaterialFurnace(
                work,
                inputId,
                goal.itemId,
                outputPerBatch
            );
        }
        AbstractFurnaceBlockEntity trackedFurnace = null;
        if (goal.ownedFurnace != null) {
            BlockPos owned = goal.ownedFurnace;
            BlockEntity blockEntity = npc.level().getBlockEntity(owned);
            if (!hasTaskFurnaceClaim(work, owned)) {
                releaseBuildSmeltingWorkstation(
                    work,
                    goal,
                    SmeltingWorkstationPolicy.Validation.UNCLAIMED
                );
                return;
            } else if (id(npc.level().getBlockState(owned).getBlock()).equals(furnaceId)
                && blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
                trackedFurnace = furnace;
                work.workstation = owned;
            } else {
                releaseBuildSmeltingWorkstation(
                    work,
                    goal,
                    SmeltingWorkstationPolicy.Validation.BLOCK_MISSING
                );
                return;
            }
        } else {
            // Never adopt an arbitrary nearby furnace: its input, fuel and
            // output may belong to the player or another automation task.
            work.workstation = null;
        }

        int bufferedInput = 0;
        int bufferedOutput = 0;
        if (trackedFurnace != null) {
            ItemStack input = trackedFurnace.getItem(0);
            ItemStack fuel = trackedFurnace.getItem(1);
            ItemStack output = trackedFurnace.getItem(2);
            SmeltingWorkstationPolicy.Validation validation = SmeltingWorkstationPolicy.validate(
                true,
                true,
                input.isEmpty() || inputIngredient.test(input),
                isCompatibleClaimedFurnaceFuel(fuel),
                output.isEmpty() || itemId(output).equals(goal.itemId)
            );
            if (validation != SmeltingWorkstationPolicy.Validation.USABLE) {
                releaseBuildSmeltingWorkstation(work, goal, validation);
                return;
            }
            bufferedInput = input.getCount();
            bufferedOutput = output.getCount();
        }
        BuildSmeltingLedgerPolicy.Ledger ledger = BuildSmeltingLedgerPolicy.calculate(
            goal.targetCount,
            inventoryCount(goal.selector),
            bufferedOutput,
            outputPerBatch,
            inventoryCount(inputId),
            bufferedInput
        );
        int missingOutput = ledger.missingOutput();
        if (ledger.missingInput() > 0) {
            beginBuildMaterialGoal(
                work,
                inputId,
                inventoryCount(inputId) + ledger.missingInput(),
                "熔炼 " + goal.itemId + " 前先补充 " + inputId
            );
            return;
        }

        if (goal.ownedFurnace == null) {
            if (prepareSmeltingWorkstation(work, furnaceId)) return;
            if (active != work) return;
            work.workstation = ensureWorkstation(work, furnaceId);
            if (work.workstation == null) return;
            if (!claimTaskFurnace(work, work.workstation, inputId, goal.itemId, outputPerBatch)) {
                work.skippedWorkstationTargets.add(work.workstation.immutable());
                work.workstation = null;
                return;
            }
            goal.ownedFurnace = work.workstation.immutable();
            work.buildLastProgressTick = work.ticks;
        }
        if (!approach(work, work.workstation, 3.5, 1.05)) return;
        BlockEntity blockEntity = npc.level().getBlockEntity(work.workstation);
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            releaseBuildSmeltingWorkstation(
                work,
                goal,
                SmeltingWorkstationPolicy.Validation.BLOCK_MISSING
            );
            return;
        }

        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty()) {
            if (!itemId(output).equals(goal.itemId)) {
                releaseBuildSmeltingWorkstation(work, goal, SmeltingWorkstationPolicy.Validation.OUTPUT_CONFLICT);
                return;
            }
            if (!canInsert(output)) {
                fail(work, "NPC 背包没有空间接收熔炼材料", "INVENTORY_FULL");
                return;
            }
            int moved = output.getCount();
            npc.insert(output.copy());
            recordInventoryAction(work, "furnace-output");
            recordTaskFurnaceOutputWithdrawal(work, work.workstation, output);
            furnace.setItem(2, ItemStack.EMPTY);
            furnace.setChanged();
            npc.swing(InteractionHand.MAIN_HAND);
            goal.lastInventoryCount = inventoryCount(goal.selector);
            work.buildLastProgressTick = work.ticks;
            progress(work, activeProgress(work), "已熔炼 " + moved + " 个 " + goal.itemId);
            if (goal.lastInventoryCount >= goal.targetCount) {
                finishBuildMaterialGoal(work, goal);
                return;
            }
            // Recompute the deficit and buffered input on the next tick. Using
            // the pre-collection value here would load or gather excess input.
            return;
        }

        missingOutput = Math.max(0, goal.targetCount - inventoryCount(goal.selector));
        if (missingOutput <= 0) {
            finishBuildMaterialGoal(work, goal);
            return;
        }
        ItemStack furnaceInput = furnace.getItem(0);
        if (!furnaceInput.isEmpty() && !inputIngredient.test(furnaceInput)) {
            releaseBuildSmeltingWorkstation(work, goal, SmeltingWorkstationPolicy.Validation.INPUT_CONFLICT);
            return;
        }
        if (furnaceInput.isEmpty()) {
            int batches = Math.max(1, (missingOutput + outputPerBatch - 1) / outputPerBatch);
            ItemStack input = extract(inputId, Math.min(64, batches));
            if (input.isEmpty()) {
                fail(work, "已经准备的熔炼原料不可用：" + inputId, "BUILD_MATERIAL_INPUT_MISSING");
                return;
            }
            recordInventoryAction(work, "furnace-input");
            recordTaskFurnaceInput(work, work.workstation, input);
            furnace.setItem(0, input);
            furnace.setChanged();
            npc.swing(InteractionHand.MAIN_HAND);
            work.buildLastProgressTick = work.ticks;
        }
        ItemStack furnaceFuel = furnace.getItem(1);
        if (!isCompatibleClaimedFurnaceFuel(furnaceFuel)) {
            releaseBuildSmeltingWorkstation(work, goal, SmeltingWorkstationPolicy.Validation.FUEL_CONFLICT);
            return;
        }
        if (shouldSupplyFurnaceFuel(work.workstation, furnaceFuel)) {
            if (!hasSafeFurnaceFuel() && beginPreferredCoalFuelAcquisition(
                work,
                goal.targetCount - inventoryCount(goal.selector),
                "熔炼任务材料"
            )) return;
            if (!hasSafeFurnaceFuel()) {
                int fuelLogs = SmeltingPrerequisitePolicy.fallbackFuelLogs(
                    goal.targetCount - inventoryCount(goal.selector)
                );
                beginCraftGather(work, "#minecraft:logs", fuelLogs,
                    "熔炼任务材料缺少安全燃料，先去采集 " + fuelLogs + " 个原木");
                return;
            }
            ItemStack fuel = extractFuel();
            if (fuel.isEmpty()) {
                fail(work, "无法取得熔炼任务材料所需燃料", "BUILD_MATERIAL_FUEL_MISSING");
                return;
            }
            CraftChainLiveFixture.recordFurnaceFuelSupply(npc, work.workstation, fuel);
            BuildGatherLiveFixture.recordMaterialFurnaceFuelSupply(npc, work.workstation, fuel);
            recordTaskFurnaceFuel(work, work.workstation, fuel);
            furnace.setItem(1, fuel);
            furnace.setChanged();
            npc.swing(InteractionHand.MAIN_HAND);
            work.buildLastProgressTick = work.ticks;
        }
        taskStatus(work, "正在熔炼任务材料 " + goal.itemId + "（"
            + inventoryCount(goal.selector) + "/" + goal.targetCount + "）");
    }

    private void releaseBuildSmeltingWorkstation(
        ActiveWork work,
        BuildMaterialGoal goal,
        SmeltingWorkstationPolicy.Validation validation
    ) {
        BlockPos released = goal.ownedFurnace != null ? goal.ownedFurnace : work.workstation;
        FurnaceRecoverySummary recovery = recoverTaskFurnace(work, released, "build-material-release");
        if (released != null) work.skippedWorkstationTargets.add(released.immutable());
        npc.getNavigation().stop();
        goal.ownedFurnace = null;
        work.workstation = null;
        work.targetBlock = null;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.buildLastProgressTick = work.ticks;
        String detail = switch (validation) {
            case INPUT_CONFLICT -> "原料槽出现了其他物品";
            case FUEL_CONFLICT -> "燃料槽出现了不兼容物品";
            case OUTPUT_CONFLICT -> "产物槽出现了其他物品";
            case BLOCK_MISSING -> "原任务熔炉已不存在";
            case UNCLAIMED -> "原任务熔炉没有所有权记录";
            case USABLE -> "原任务熔炉不再可用";
        };
        progress(work, activeProgress(work), detail + "；未知内容保持原样，任务投入已按账本回收并另行放置任务熔炉"
            + recovery.detail());
    }

    private boolean ensureBuildMaterialUpstream(
        ActiveWork work,
        BuildMaterialGoal goal,
        BuildMaterialPrerequisitePolicy.MaterialPlan plan
    ) {
        int missingOutput = Math.max(1, goal.targetCount - inventoryCount(goal.selector));
        int batches = Math.max(1, (missingOutput + plan.outputPerBatch() - 1) / plan.outputPerBatch());
        for (BuildMaterialPrerequisitePolicy.Requirement requirement : plan.upstreamRequirements()) {
            int required = Math.max(1, requirement.count() * batches);
            int available = inventoryCount(requirement.selector());
            if (available >= required) continue;
            int deficit = required - available;
            String concreteId = acquisitionBuildMaterialId(
                requirement.selector(),
                deficit,
                goal.materialContextId
            );
            if (concreteId.isBlank()) {
                fail(work, "无法解析任务材料上游选择器 " + requirement.selector(), "BUILD_MATERIAL_SELECTOR_UNSUPPORTED");
                return true;
            }
            int targetCount = available + deficit;
            return beginBuildMaterialGoal(
                work,
                concreteId,
                requirement.selector(),
                targetCount,
                "制作 " + goal.itemId + " 前先补充 " + requirement.selector()
            );
        }
        return false;
    }

    private boolean beginBuildMaterialGoal(ActiveWork work, String itemId, int targetCount, String message) {
        return beginBuildMaterialGoal(
            work,
            itemId,
            itemId,
            targetCount,
            message,
            materialContextItemId(work, itemId)
        );
    }

    private boolean beginBuildMaterialGoal(
        ActiveWork work,
        String itemId,
        String selector,
        int targetCount,
        String message
    ) {
        return beginBuildMaterialGoal(
            work,
            itemId,
            selector,
            targetCount,
            message,
            materialContextItemId(work, itemId)
        );
    }

    private boolean beginBuildMaterialGoal(
        ActiveWork work,
        String itemId,
        String selector,
        int targetCount,
        String message,
        String materialContextId
    ) {
        if (itemId == null || itemId.isBlank() || item(itemId) == Items.AIR) {
            fail(work, "无效的任务材料目标：" + itemId, "BUILD_MATERIAL_INVALID");
            return false;
        }
        if (selector == null || selector.isBlank()) {
            fail(work, "无效的任务材料选择器：" + selector, "BUILD_MATERIAL_SELECTOR_UNSUPPORTED");
            return false;
        }
        for (BuildMaterialGoal existing : work.buildMaterialGoals) {
            if (existing.selector.equals(selector)) {
                fail(work, "任务材料配方出现循环依赖：" + selector, "BUILD_MATERIAL_DEPENDENCY_CYCLE");
                return false;
            }
        }
        if (work.buildMaterialGoals.size() >= BUILD_MATERIAL_MAX_DEPTH) {
            fail(work, "任务材料依赖超过安全深度", "BUILD_MATERIAL_DEPENDENCY_DEPTH");
            return false;
        }
        int normalizedTarget = Math.max(inventoryCount(selector) + 1, targetCount);
        String suspendedGatherItemId = work.craftGatherItemId;
        int suspendedGatherCount = work.craftGatherCount;
        int suspendedGatherInitialCount = work.craftGatherInitialCount;
        int suspendedGatherCompleted = work.craftGatherCompleted;
        int suspendedGatherStartedTick = work.craftGatherStartedTick;
        DeepMiningCheckpoint suspendedDeepMining = captureDeepMiningCheckpoint(work);
        if (hasCraftGatherPrerequisite(work)) clearCraftGatherPrerequisite(work);
        if (suspendedDeepMining != null) clearDeepMining(work);
        BuildMaterialGoal goal = new BuildMaterialGoal(
            itemId,
            selector,
            materialContextId == null || materialContextId.isBlank() ? itemId : materialContextId,
            normalizedTarget,
            inventoryCount(selector),
            work.ticks,
            suspendedGatherItemId,
            suspendedGatherCount,
            suspendedGatherInitialCount,
            suspendedGatherCompleted,
            suspendedGatherStartedTick,
            suspendedDeepMining
        );
        work.buildMaterialGoals.addFirst(goal);
        resetBuildMaterialTransient(work);
        work.buildPhase = "storage";
        work.buildPhaseStartedTick = work.ticks;
        work.buildLastProgressTick = work.ticks;
        progress(work, activeProgress(work), message + "（目标 " + normalizedTarget + "）");
        return true;
    }

    private String materialContextItemId(ActiveWork work, String fallbackItemId) {
        BuildMaterialGoal current = work.buildMaterialGoals.peekFirst();
        return current == null || current.materialContextId.isBlank()
            ? fallbackItemId
            : current.materialContextId;
    }

    private void finishBuildMaterialGoal(ActiveWork work, BuildMaterialGoal goal) {
        if (work.buildMaterialGoals.peekFirst() != goal) return;
        FurnaceRecoverySummary recovery = isReusableTaskFurnace(work, goal.ownedFurnace)
            ? FurnaceRecoverySummary.empty()
            : recoverTaskFurnace(work, goal.ownedFurnace, "build-material-complete");
        work.buildMaterialGoals.removeFirst();
        resetBuildMaterialTransient(work);
        restoreSuspendedGather(work, goal);
        restoreSuspendedDeepMining(work, goal);
        work.buildPhase = work.buildMaterialGoals.isEmpty() ? "scan" : "resume-parent";
        work.buildPhaseStartedTick = work.ticks;
        work.buildLastProgressTick = work.ticks;
        String next = work.buildMaterialGoals.isEmpty()
            ? switch (work.kind) {
                case "craft" -> "返回原配方继续制作";
                case "smelt" -> "返回原烧炼任务继续加工";
                case "gather" -> "返回原采集目标继续工作";
                case "farm" -> "返回农务任务继续工作";
                case "fish" -> "返回钓鱼任务继续准备";
                default -> "返回建筑位置继续放置";
            }
            : "继续准备 " + work.buildMaterialGoals.peekFirst().itemId;
        progress(work, activeProgress(work), "已备齐 " + goal.itemId + "，" + next + recovery.detail());
    }

    private void restoreSuspendedGather(ActiveWork work, BuildMaterialGoal goal) {
        if (goal.suspendedGatherItemId == null || goal.suspendedGatherItemId.isBlank()) return;
        work.craftGatherItemId = goal.suspendedGatherItemId;
        work.craftGatherCount = Math.max(1, goal.suspendedGatherCount);
        work.craftGatherInitialCount = Math.max(0, goal.suspendedGatherInitialCount);
        work.craftGatherCompleted = Math.max(0, goal.suspendedGatherCompleted);
        int pausedTicks = Math.max(0, work.ticks - goal.startedTick);
        work.craftGatherStartedTick = goal.suspendedGatherStartedTick < 0
            ? work.ticks
            : goal.suspendedGatherStartedTick + pausedTicks;
    }

    private boolean hasSuspendedDeepMining(ActiveWork work) {
        if (work != null && !work.deepMiningPhase.isBlank()) return true;
        return work != null && work.buildMaterialGoals.stream()
            .anyMatch(goal -> goal.suspendedDeepMining != null);
    }

    private DeepMiningCheckpoint captureDeepMiningCheckpoint(ActiveWork work) {
        if (work == null || work.deepMiningPhase.isBlank() || work.deepMiningItemId.isBlank()) return null;
        return new DeepMiningCheckpoint(
            work.deepMiningPhase,
            work.deepMiningItemId,
            work.deepMiningTargetY,
            DeepMiningPolicy.retainedDirection(work.deepMiningDirection, Direction.NORTH),
            work.deepMiningPhaseStartedTick,
            work.deepMiningStaircaseStep,
            work.deepMiningBranchIndex,
            work.deepMiningBranchProgress,
            work.deepMiningRegionIndex,
            work.deepMiningLastTorchProgress,
            work.deepMiningBrokenBlocks,
            work.deepMiningPlacedTorches,
            work.deepMiningBlockedTurns,
            work.deepMiningMarkerStage,
            work.deepMiningEntrySearchIndex,
            work.deepMiningEntryTargetStartedTick,
            work.deepMiningPreflightComplete,
            work.deepMiningExcavationTarget,
            work.deepMiningResourceTargetStartedTick,
            work.deepMiningResourceChaseStartedTick,
            immutable(work.deepMiningEntrance),
            immutable(work.deepMiningLanding),
            immutable(work.deepMiningLastSafeStand),
            immutable(work.deepMiningCaveTarget),
            immutable(work.targetBlock),
            immutable(work.deepMiningResourceTimedTarget)
        );
    }

    private void restoreSuspendedDeepMining(ActiveWork work, BuildMaterialGoal goal) {
        DeepMiningCheckpoint checkpoint = goal.suspendedDeepMining;
        if (checkpoint == null) return;
        clearDeepMining(work);
        int pausedTicks = Math.max(0, work.ticks - goal.startedTick);
        work.deepMiningPhase = checkpoint.phase();
        work.deepMiningItemId = checkpoint.itemId();
        work.deepMiningTargetY = checkpoint.targetY();
        work.deepMiningDirection = DeepMiningPolicy.retainedDirection(checkpoint.direction(), Direction.NORTH);
        work.deepMiningPhaseStartedTick = shiftedTick(checkpoint.phaseStartedTick(), pausedTicks);
        work.deepMiningStaircaseStep = checkpoint.staircaseStep();
        work.deepMiningBranchIndex = checkpoint.branchIndex();
        work.deepMiningBranchProgress = checkpoint.branchProgress();
        work.deepMiningRegionIndex = checkpoint.regionIndex();
        work.deepMiningLastTorchProgress = checkpoint.lastTorchProgress();
        work.deepMiningBrokenBlocks = checkpoint.brokenBlocks();
        work.deepMiningPlacedTorches = checkpoint.placedTorches();
        work.deepMiningBlockedTurns = checkpoint.blockedTurns();
        work.deepMiningMarkerStage = checkpoint.markerStage();
        work.deepMiningEntrySearchIndex = checkpoint.entrySearchIndex();
        work.deepMiningEntryTargetStartedTick = shiftedTick(checkpoint.entryTargetStartedTick(), pausedTicks);
        work.deepMiningPreflightComplete = checkpoint.preflightComplete();
        work.deepMiningExcavationTarget = checkpoint.excavationTarget();
        work.deepMiningResourceTargetStartedTick = shiftedTick(checkpoint.resourceTargetStartedTick(), pausedTicks);
        work.deepMiningResourceChaseStartedTick = shiftedTick(checkpoint.resourceChaseStartedTick(), pausedTicks);
        work.deepMiningEntrance = immutable(checkpoint.entrance());
        work.deepMiningLanding = immutable(checkpoint.landing());
        work.deepMiningLastSafeStand = immutable(checkpoint.lastSafeStand());
        work.deepMiningCaveTarget = immutable(checkpoint.caveTarget());
        work.targetBlock = immutable(checkpoint.targetBlock());
        work.deepMiningResourceTimedTarget = immutable(checkpoint.resourceTimedTarget());
        work.destination = null;
        work.gatherTargets.clear();
        work.skippedGatherTargets.clear();
        work.gatherPathFailures = 0;
        work.lastGatherPathAttemptTick = -1;
        work.gatherStandPathCursor = 0;
        work.lastDistance = -1.0D;
        npc.getNavigation().stop();
    }

    private static int shiftedTick(int savedTick, int pausedTicks) {
        return savedTick <= 0 ? savedTick : savedTick + Math.max(0, pausedTicks);
    }

    private static BlockPos immutable(BlockPos position) {
        return position == null ? null : position.immutable();
    }

    private void resetBuildMaterialTransient(ActiveWork work) {
        boolean hadEntityMaterialTarget = work.foodAnimalTargetId != null;
        work.bedSheepTargetId = null;
        work.foodAnimalTargetId = null;
        work.skippedBedSheepTargets.clear();
        work.skippedFoodAnimalTargets.clear();
        if (hadEntityMaterialTarget) npc.setTarget(null);
        work.workstation = null;
        work.targetBlock = null;
        work.destination = null;
        work.gatherTargets.clear();
        work.skippedGatherTargets.clear();
        work.skippedStorageTargets.clear();
        work.gatherSearchRadius = 16;
        work.gatherExcursions = 0;
        work.gatherPathFailures = 0;
        work.gatherTreeCluster = false;
        work.gatherClusterReached = false;
        work.gatherAccessTarget = false;
        work.noWorkTicks = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.lastGatherPathAttemptTick = -1;
        work.gatherStandPathCursor = 0;
        work.lastSearchTick = -10;
        work.recipe = null;
        work.requiresTable = false;
    }

    private int remainingBuildMaterialCount(ActiveWork work, String materialId) {
        int remaining = 0;
        for (int index = work.buildIndex; index < work.buildBlocks.size(); index++) {
            JsonObject entry = work.buildBlocks.get(index).getAsJsonObject();
            String blockId = string(entry, "blockId", "minecraft:air");
            if (blockId.equals("minecraft:air")
                || !BuildMaterialPalettePolicy.consumesPlacementItem(blockId, buildProperties(entry))
                || !BuildPlacementPolicy.materialItemId(blockId).equals(materialId)) continue;
            BlockPos target = work.buildOrigin.offset(block(entry.getAsJsonObject("position")));
            if (!id(npc.level().getBlockState(target).getBlock()).equals(blockId)) remaining++;
        }
        return Math.max(1, remaining);
    }

    private String concreteBuildMaterialId(String selector) {
        if (selector == null || selector.isBlank()) return "";
        if (!selector.startsWith("#")) return selector;
        ResourceSelector parsed = ResourceSelector.parse(selector);
        String inventory = bestInventoryMaterialId(parsed);
        if (!inventory.isBlank()) return inventory;
        String stored = bestStoredMaterialId(parsed, 1);
        if (!stored.isBlank()) return stored;
        return fallbackBuildMaterialId(selector, parsed);
    }

    private String acquisitionBuildMaterialId(String selector, int deficit, String materialContextId) {
        if (selector == null || selector.isBlank()) return "";
        if (!selector.startsWith("#")) return selector;
        ResourceSelector parsed = ResourceSelector.parse(selector);
        String contextual = contextualBuildMaterialId(materialContextId, parsed);
        if (!contextual.isBlank()) return contextual;
        String stored = bestStoredMaterialId(parsed, Math.max(1, deficit));
        if (!stored.isBlank()) return stored;
        return fallbackBuildMaterialId(selector, parsed);
    }

    private String contextualBuildMaterialId(String materialContextId, ResourceSelector selector) {
        List<String> acceptedItemIds = new ArrayList<>();
        for (Item candidate : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(candidate);
            if (selector.matches(stack)) acceptedItemIds.add(itemId(stack));
        }
        String preferred = BuildMaterialPrerequisitePolicy.preferredWoodFamilyCandidate(
            materialContextId,
            acceptedItemIds
        );
        return !preferred.isBlank() && buildMaterialCandidateRank(preferred) < 4 ? preferred : "";
    }

    private String fallbackBuildMaterialId(String selector, ResourceSelector parsed) {
        String canonical = canonicalBuildMaterialId(selector);
        if (!canonical.isBlank()) {
            Item candidate = item(canonical);
            if (candidate != Items.AIR && parsed.matches(new ItemStack(candidate))) return canonical;
        }
        List<String> candidates = new ArrayList<>();
        for (Item candidate : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(candidate);
            if (parsed.matches(stack)) candidates.add(itemId(stack));
        }
        candidates.sort(Comparator
            .comparingInt(this::buildMaterialCandidateRank)
            .thenComparing(id -> id));
        return candidates.stream().filter(id -> buildMaterialCandidateRank(id) < 4).findFirst().orElse("");
    }

    private String canonicalBuildMaterialId(String selector) {
        if (selector.equals("#minecraft:planks")) return "minecraft:oak_planks";
        if (selector.equals("#minecraft:wooden_slabs")) return "minecraft:oak_slab";
        if (selector.equals("#minecraft:stone_crafting_materials")
            || selector.equals("#minecraft:stone_tool_materials")) return "minecraft:cobblestone";
        if (selector.equals("#minecraft:logs")) return "minecraft:oak_log";
        String prefix = "#minecraft:";
        if (selector.startsWith(prefix) && selector.endsWith("_logs")) {
            String family = selector.substring(prefix.length(), selector.length() - "_logs".length());
            return "minecraft:" + family + "_log";
        }
        if (selector.equals("#minecraft:crimson_stems")) return "minecraft:crimson_stem";
        if (selector.equals("#minecraft:warped_stems")) return "minecraft:warped_stem";
        return "";
    }

    private int buildMaterialCandidateRank(String candidateId) {
        if (isSafeRuntimePaletteGatherItem(candidateId)) return 0;
        if (BuildMaterialPrerequisitePolicy.plan(candidateId).action()
            != BuildMaterialPrerequisitePolicy.Action.REJECT) return 1;
        if (!BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe(candidateId)) return 4;
        if (findCraftRecipe(candidateId) != null) return 2;
        return findSmeltingRecipeByOutput(candidateId) != null ? 3 : 4;
    }

    private String bestInventoryMaterialId(ResourceSelector selector) {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < npc.inventory().getSlots(); slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (selector.matches(stack)) counts.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        return highestCountId(counts, 1);
    }

    private String bestStoredMaterialId(ResourceSelector selector, int minimumCount) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) return "";
        Map<String, Integer> counts = new HashMap<>();
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        for (BlockPos position : NpcHomeStorage.findContainers(level, home, HomeStoragePolicy.DEFAULT_RADIUS)) {
            BlockEntity entity = level.getBlockEntity(position);
            if (!(entity instanceof Container container) || entity instanceof AbstractFurnaceBlockEntity) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (selector.matches(stack)) counts.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        return highestCountId(counts, minimumCount);
    }

    private String highestCountId(Map<String, Integer> counts, int minimumCount) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= minimumCount)
            .max(Comparator
                .comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue())
                .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .orElse("");
    }

    private String selectBuildIngredientItem(
        String materialItemId,
        Ingredient ingredient,
        List<BuildMaterialPrerequisitePolicy.Requirement> policyRequirements
    ) {
        List<String> acceptedItemIds = Arrays.stream(ingredient.getItems())
            .map(this::itemId)
            .filter(candidateId -> !candidateId.equals("minecraft:air"))
            .distinct()
            .toList();
        for (ItemStack candidate : ingredient.getItems()) {
            String candidateId = itemId(candidate);
            if (BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
                materialItemId,
                candidateId,
                acceptedItemIds
            ) && inventoryCount(candidateId) > 0) return candidateId;
        }
        for (BuildMaterialPrerequisitePolicy.Requirement requirement : policyRequirements) {
            String candidateId = concreteBuildMaterialId(requirement.selector());
            if (!candidateId.isBlank()
                && BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
                    materialItemId,
                    candidateId,
                    acceptedItemIds
                )
                && ingredient.test(new ItemStack(item(candidateId)))) return candidateId;
        }
        for (ItemStack candidate : ingredient.getItems()) {
            String candidateId = itemId(candidate);
            if (!BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
                materialItemId,
                candidateId,
                acceptedItemIds
            )) continue;
            if (BuildMaterialPrerequisitePolicy.plan(candidateId).action()
                != BuildMaterialPrerequisitePolicy.Action.REJECT
                || isSafeRuntimePaletteGatherItem(candidateId)
                || BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe(candidateId)
                    && (findCraftRecipe(candidateId) != null || findSmeltingRecipeByOutput(candidateId) != null)) {
                return candidateId;
            }
        }
        return "";
    }

    private boolean isSafeRuntimePaletteGatherItem(String candidateId) {
        Item candidate = item(candidateId);
        if (candidate == Items.AIR || !(candidate instanceof BlockItem blockItem)) return false;
        ItemStack stack = new ItemStack(candidate);
        BlockState state = blockItem.getBlock().defaultBlockState();
        boolean naturalSource = candidate == Items.BAMBOO
            || BuildMaterialPalettePolicy.naturalTrunkId(candidateId)
                && (stack.is(ItemTags.LOGS) || state.is(BlockTags.LOGS));
        return naturalSource
            && !(blockItem.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)
            && state.getFluidState().isEmpty()
            && state.getDestroySpeed(npc.level(), npc.blockPosition()) >= 0.0F
            && !BuildMaterialPalettePolicy.unsafeStructuralId(candidateId)
            && BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe(candidateId);
    }

    private void tickBuildTilledBlock(
        ActiveWork work,
        JsonObject entry,
        BlockState currentState,
        BuildMaterialPrerequisitePolicy.MaterialPlan plan
    ) {
        BlockPos target = work.targetBlock;
        if (currentState.canBeReplaced()) {
            String dirtId = plan.upstreamRequirements().isEmpty()
                ? "minecraft:dirt"
                : concreteBuildMaterialId(plan.upstreamRequirements().get(0).selector());
            int dirtSlot = npc.creativeResources() ? -1 : findItemSlot(dirtId);
            if (!npc.creativeResources() && dirtSlot < 0) {
                beginBuildMaterialGoal(work, dirtId, 1, "耕地前先准备泥土");
                return;
            }
            if (!approachBuildTarget(work, target, 1.08)) return;
            if (work.ticks - work.lastActionTick < 5) return;
            Direction supportDirection = findSupport(target);
            if (supportDirection == null) {
                fail(work, "耕地位置没有可点击的支撑面：" + target.toShortString(), "NO_BUILD_SUPPORT");
                return;
            }
            Item dirtItem = item(dirtId);
            ItemStack dirt = npc.creativeResources() ? new ItemStack(dirtItem) : npc.inventory().getStackInSlot(dirtSlot);
            Direction face = supportDirection.getOpposite();
            BlockPos support = target.relative(supportDirection);
            npc.swing(InteractionHand.MAIN_HAND, true);
            work.lastActionTick = work.ticks;
            InteractionResult result = proxy.useItemOn(support, face, dirt, dirtSlot);
            boolean placed = npc.level().getBlockState(target).is(Blocks.DIRT);
            if (!placed && !result.consumesAction()) {
                placed = placeBuildBlockDirectly(target, dirtId, dirtItem, dirtSlot);
            }
            if (!placed) {
                if (++work.failedActions >= 3) fail(work, "无法放置耕地基底", "BLOCK_PLACE_DENIED");
            }
            return;
        }
        if (!(currentState.is(Blocks.DIRT) || currentState.is(Blocks.GRASS_BLOCK)
            || currentState.is(Blocks.DIRT_PATH) || currentState.is(Blocks.COARSE_DIRT))) {
            fail(work, "耕地目标位置已有不可耕作方块：" + target.toShortString(), "BUILD_SITE_BLOCKED");
            return;
        }
        int hoeSlot = findHoeSlot();
        if (hoeSlot < 0 && !npc.creativeResources()) {
            work.targetBlock = null;
            prepareCraftedToolPrerequisite(
                work,
                "minecraft:wooden_hoe",
                "耕地前先制作木锄",
                "缺少可取得的木锄材料",
                "BUILD_HOE_RECIPE_MISSING",
                "BUILD_HOE_MATERIALS_MISSING"
            );
            return;
        }
        if (!approachBuildTarget(work, target, 1.08)) return;
        if (work.ticks - work.lastActionTick < 5) return;
        ItemStack hoe = npc.creativeResources()
            ? new ItemStack(Items.WOODEN_HOE)
            : npc.inventory().getStackInSlot(hoeSlot);
        npc.getLookControl().setLookAt(Vec3.atCenterOf(target));
        npc.swing(InteractionHand.MAIN_HAND, true);
        work.lastActionTick = work.ticks;
        InteractionResult result = proxy.useItemOn(target, Direction.UP, hoe, hoeSlot);
        BlockState tilled = npc.level().getBlockState(target);
        if (tilled.getBlock() instanceof FarmBlock) {
            if (!applyExactBuildState(work, target, entry)) return;
            work.workstation = null;
            finishBuildBlock(work, 0.03F);
            return;
        }
        if (!result.consumesAction() && ++work.failedActions >= 3) {
            fail(work, "无法把目标地块耕作为农田", "BUILD_TILL_DENIED");
        }
    }

    private boolean placeBuildBlockDirectly(BlockPos target, String blockId, Item item, int sourceSlot) {
        if (!(item instanceof BlockItem blockItem)) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;
        if (!level.hasChunkAt(target) || !level.getWorldBorder().isWithinBounds(target)) return false;
        BlockState current = level.getBlockState(target);
        if (!current.canBeReplaced() || !level.getFluidState(target).isEmpty()) return false;
        ServerPlayer owner = npc.owner();
        if (owner == null || !level.mayInteract(owner, target)) return false;
        Direction supportDirection = findSupport(target);
        if (supportDirection == null) return false;
        BlockPos support = target.relative(supportDirection);
        if (!level.getBlockState(support).isFaceSturdy(level, support, supportDirection.getOpposite())) return false;
        if (!npc.creativeResources()) {
            if (sourceSlot < 0) return false;
            ItemStack source = npc.inventory().getStackInSlot(sourceSlot);
            if (source.isEmpty() || source.getItem() != item) return false;
        }
        if (!level.setBlock(target, blockItem.getBlock().defaultBlockState(), Block.UPDATE_ALL)) return false;
        if (!id(level.getBlockState(target).getBlock()).equals(blockId)) return false;
        if (!npc.creativeResources()) {
            ItemStack source = npc.inventory().getStackInSlot(sourceSlot);
            source.shrink(1);
            npc.inventory().setStackInSlot(sourceSlot, source);
        }
        return true;
    }

    private boolean applyExactBuildState(ActiveWork work, BlockPos target, JsonObject entry) {
        BlockState current = npc.level().getBlockState(target);
        BlockState desired;
        try {
            desired = BuildStatePolicy.apply(current, buildProperties(entry));
        } catch (IllegalArgumentException error) {
            fail(work, "建筑方块状态无效：" + error.getMessage(), "INVALID_BUILD_STATE");
            return false;
        }
        if (!desired.equals(current) && !npc.level().setBlock(target, desired, Block.UPDATE_ALL)) {
            fail(work, "无法应用建筑方块状态：" + target.toShortString(), "BLOCK_STATE_UPDATE_DENIED");
            return false;
        }
        BlockState applied = npc.level().getBlockState(target);
        if (!id(applied.getBlock()).equals(string(entry, "blockId", "minecraft:air"))
            || !BuildStatePolicy.matches(applied, buildProperties(entry))) {
            fail(work, "建筑方块状态未正确应用：" + target.toShortString(), "BLOCK_STATE_MISMATCH");
            return false;
        }
        return true;
    }

    private Map<String, String> buildProperties(JsonObject entry) {
        if (!entry.has("properties") || !entry.get("properties").isJsonObject()) return Map.of();
        Map<String, String> result = new HashMap<>();
        entry.getAsJsonObject("properties").entrySet().forEach(property -> {
            if (!property.getValue().isJsonPrimitive()) {
                throw new IllegalArgumentException("属性 " + property.getKey() + " 不是文本值");
            }
            result.put(property.getKey(), property.getValue().getAsString());
        });
        return result;
    }

    private void finishBuildBlock(ActiveWork work, float exhaustion) {
        work.failedActions = 0;
        work.buildIndex++;
        work.completed = work.buildIndex;
        work.buildLastProgressTick = work.ticks;
        work.buildPhase = "scan";
        npc.addExhaustion(exhaustion);
        progress(work, work.buildIndex / (double) work.requestedCount, "建造中 " + work.buildIndex + "/" + work.requestedCount);
    }

    private boolean approachBuildTarget(ActiveWork work, BlockPos target, double speed) {
        if (!npc.creativeResources()) return approachGroundBuildTarget(work, target, 4.2, speed);

        // A creative player can fly above a structure while placing blocks. Keep
        // the NPC above the active block so completed walls cannot trap it and
        // bucket ray casts retain a clear path to their support face.
        Vec3 targetCenter = Vec3.atCenterOf(target);
        Vec3 hover = targetCenter.add(0, 2.0, 0);
        Vec3 offset = hover.subtract(npc.position());
        double distance = offset.length();
        if (distance <= 0.35) {
            npc.getNavigation().stop();
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0;
            npc.getLookControl().setLookAt(targetCenter);
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }

        npc.getNavigation().stop();
        npc.setNoGravity(true);
        npc.fallDistance = 0;
        double velocity = Math.min(0.55, 0.10 + distance * 0.08);
        npc.setDeltaMovement(offset.scale(velocity / distance));
        npc.hasImpulse = true;
        npc.getLookControl().setLookAt(targetCenter);
        trackNavigation(work, distance);
        if (work.stalledTicks > 200) {
            fail(work, "无法飞到建造目标 " + target.toShortString(), "PATH_NOT_FOUND");
        }
        return false;
    }

    private void tickDragon(ActiveWork work) {
        String action = string(work.spec, "action", "observe");
        if (action.equals("dismount") && npc.isPassenger()) {
            Entity vehicle = npc.getVehicle();
            DragonAdapter mountedAdapter = vehicle == null ? null : DragonAdapters.forEntity(vehicle);
            if (mountedAdapter != null) mountedAdapter.dismount(npc);
            else npc.stopRiding();
            complete(work, "Codex 已下龙");
            return;
        }
        if (action.equals("care-for-egg")) {
            tickDragonEggCare(work);
            return;
        }
        Entity target = resolveDragonTarget(work);
        if (target == null) {
            fail(work, action.equals("care-for-egg") ? "附近没有找到龙蛋" : "附近没有找到龙", "DRAGON_NOT_FOUND");
            return;
        }
        if (action.equals("observe")) {
            String health = target instanceof LivingEntity living ? "，生命 " + Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth()) : "";
            Object command = invokeNoArg(target, "getCommand");
            DragonAdapter adapter = DragonAdapters.forEntity(target);
            String mod = adapter == null ? "" : "，模组 " + adapter.modId();
            Object needs = invokeNoArg(target, "getNeedsSystem");
            Object hunger = needs == null ? null : invokeNoArg(needs, "getFoodLevel");
            ServerPlayer owner = npc.owner();
            boolean tamed = !(target instanceof TamableAnimal tameable) || tameable.isTame();
            boolean owned = adapter != null && owner != null && adapter.isOwnedBy(target, owner);
            complete(work, "已观察 " + target.getDisplayName().getString() + health + mod
                + "，驯服=" + tamed + "，属于主人=" + owned
                + (hunger instanceof Number n ? "，饱食 " + n.intValue() : "")
                + (command instanceof Number n ? "，指令 " + n.intValue() : ""));
            return;
        }
        boolean needsApproach = DragonActionPolicy.requiresApproach(action);
        if (needsApproach && !approach(work, target.blockPosition(), action.equals("mount") ? 3.5 : 3.0, 1.08)) return;
        DragonAdapter adapter = DragonAdapters.forEntity(target);
        if (adapter == null && !action.equals("care-for-egg")) {
            fail(work, "只支持 bookofdragons 与 saintsdragons 的龙", "DRAGON_MOD_UNSUPPORTED");
            return;
        }
        ServerPlayer owner = npc.owner();
        if (owner == null) {
            fail(work, "主人当前不在线", "OWNER_OFFLINE");
            return;
        }
        switch (action) {
            case "follow" -> tickDragonCommand(work, target, adapter, owner, true);
            case "stay" -> tickDragonCommand(work, target, adapter, owner, false);
            case "mount" -> tickDragonMount(work, target, adapter, owner);
            case "recall" -> {
                tickDragonRecall(work, target, adapter, owner);
            }
            case "assist-combat" -> tickDragonAssistCombat(work, target, adapter, owner);
            case "land" -> {
                tickDragonLand(work, target, adapter, owner);
            }
            case "fly-to" -> {
                tickDragonFlyTo(work, target, adapter, owner);
            }
            case "tame" -> tickDragonTame(work, target, adapter, owner);
            case "feed", "heal" -> interactWithDragon(work, target, action);
            default -> fail(work, "不支持的养龙动作 " + action, "DRAGON_ACTION_UNSUPPORTED");
        }
    }

    private void tickDragonCommand(
        ActiveWork work,
        Entity dragon,
        DragonAdapter adapter,
        ServerPlayer owner,
        boolean follow
    ) {
        int desiredCommand = follow ? adapter.followCommand() : adapter.stayCommand();
        Object observed = invokeNoArg(dragon, "getCommand");
        boolean matches = observed instanceof Number number && number.intValue() == desiredCommand;
        work.stableTicks = matches ? work.stableTicks + 1 : 0;
        DragonActionPolicy.Decision decision = DragonActionPolicy.command(
            matches, work.stableTicks, dragonActionElapsedTicks(work)
        );
        if (decision == DragonActionPolicy.Decision.COMPLETE) {
            complete(work, follow ? "龙已开始跟随" : "龙已在原地停留");
            return;
        }
        if (decision == DragonActionPolicy.Decision.TIMED_OUT) {
            fail(work, "龙没有进入请求的指令状态", "DRAGON_COMMAND_TIMEOUT");
            return;
        }
        if (!matches && DragonActionPolicy.shouldIssueCommand(work.initialized, work.ticks, work.lastActionTick)) {
            work.initialized = true;
            work.lastActionTick = work.ticks;
            boolean accepted = follow
                ? adapter.setFollow(dragon, owner)
                : adapter.setStay(dragon, owner);
            if (accepted) work.failedActions = 0;
            else if (DragonActionPolicy.commandFailed(++work.failedActions)) {
                fail(work, "这只龙不属于主人或不支持该指令", "DRAGON_COMMAND_UNSUPPORTED");
                return;
            }
        }
        if (work.ticks % 20 == 0) {
            progress(work, matches ? 0.9D : 0.4D, follow
                ? "已发送跟随指令，正在确认龙的状态"
                : "已发送停留指令，正在确认龙的状态");
        }
    }

    private void tickDragonMount(ActiveWork work, Entity dragon, DragonAdapter adapter, ServerPlayer owner) {
        DragonActionPolicy.Decision decision = DragonActionPolicy.mounting(
            npc.getVehicle() == dragon, dragonActionElapsedTicks(work)
        );
        if (decision == DragonActionPolicy.Decision.COMPLETE) {
            complete(work, "Codex 已骑乘 " + dragon.getDisplayName().getString());
            return;
        }
        if (decision == DragonActionPolicy.Decision.TIMED_OUT) {
            fail(work, "骑乘指令已超时，NPC 没有实际骑上这只龙", "DRAGON_MOUNT_TIMEOUT");
            return;
        }
        double distance = entityBodyGap(npc, dragon);
        if (work.startDistance < 0.0D) {
            work.startDistance = Math.max(distance, DragonActionPolicy.MOUNT_REACH + 1.0D);
        }
        if (distance > DragonActionPolicy.MOUNT_REACH) {
            if (dragon.level() != owner.level() || dragon.level() != npc.level()) {
                fail(work, "龙位于其他维度，无法召回后骑乘", "DRAGON_DIMENSION_UNREACHABLE");
                return;
            }
            if (DragonActionPolicy.shouldRepathMountApproach(
                npc.getNavigation().isDone(), work.ticks
            )) {
                navigateTowardBlock(dragon.blockPosition(), 1.15D);
            }
            npc.getLookControl().setLookAt(dragon, 30.0F, 30.0F);
            npc.addExhaustion(0.002F);
            trackDragonMountApproach(work, distance);
            if (DragonTerrainAvoidancePolicy.shouldUseMountApproachRecovery(
                owner.hasPermissions(2), work.stalledTicks
            ) && adapter instanceof ReflectiveDragonAdapter reflective
                && reflective.recoverStalledRecall(dragon, owner)) {
                work.stalledTicks = 0;
                work.lastDistance = -1.0D;
                work.initialized = false;
                npc.setStatus("龙召回持续受阻，已移到主人附近的安全落点");
                return;
            }
            if (DragonActionPolicy.shouldIssueCommand(work.initialized, work.ticks, work.lastActionTick)) {
                work.initialized = true;
                work.lastActionTick = work.ticks;
                boolean accepted = adapter.recall(dragon, owner, owner.hasPermissions(2));
                if (distance > 12.0D) {
                    accepted = adapter.flyTo(dragon, npc.getEyePosition(), owner) || accepted;
                }
                if (accepted) work.failedActions = 0;
                else if (DragonActionPolicy.commandFailed(++work.failedActions)) {
                    fail(work, "无法先把龙召回到可骑乘距离", "DRAGON_MOUNT_RECALL_FAILED");
                    return;
                }
            }
            if (work.ticks % 20 == 0) {
                progress(work, DragonActionPolicy.progress(
                    work.startDistance, distance, DragonActionPolicy.MOUNT_REACH
                ), "NPC 正在走向龙，同时召回龙，距离 " + Math.round(distance) + " 格");
            }
            return;
        }
        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(dragon, 30.0F, 30.0F);
        adapter.haltTravel(dragon, owner);
        if (DragonActionPolicy.shouldIssueCommand(work.initialized, work.ticks, work.lastActionTick)) {
            work.initialized = true;
            work.lastActionTick = work.ticks;
            if (adapter.mount(npc, dragon, owner)) work.failedActions = 0;
            else if (DragonActionPolicy.commandFailed(++work.failedActions)) {
                fail(work, "无法骑乘这只龙", "DRAGON_MOUNT_FAILED");
                return;
            }
        }
        if (work.ticks % 20 == 0) progress(work, 0.5D, "正在确认 NPC 已实际骑上龙");
    }

    private void trackDragonMountApproach(ActiveWork work, double distance) {
        if (work.ticks % 20 != 0) return;
        DragonActionPolicy.MountApproachSample sample = DragonActionPolicy.sampleMountApproach(
            work.stalledTicks, work.lastDistance, distance
        );
        work.stalledTicks = sample.stalledSamples();
        work.lastDistance = sample.bestDistance();
    }

    private static double entityBodyGap(Entity first, Entity second) {
        AABB a = first.getBoundingBox();
        AABB b = second.getBoundingBox();
        double x = axisGap(a.minX, a.maxX, b.minX, b.maxX);
        double y = axisGap(a.minY, a.maxY, b.minY, b.maxY);
        double z = axisGap(a.minZ, a.maxZ, b.minZ, b.maxZ);
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double axisGap(double firstMin, double firstMax, double secondMin, double secondMax) {
        if (firstMax < secondMin) return secondMin - firstMax;
        if (secondMax < firstMin) return firstMin - secondMax;
        return 0.0D;
    }

    private void tickDragonAssistCombat(
        ActiveWork work,
        Entity dragon,
        DragonAdapter adapter,
        ServerPlayer owner
    ) {
        LivingEntity combatTarget = resolveDragonCombatTarget(work, owner);
        if (DragonActionPolicy.combatComplete(
            work.dragonCombatTargetDefeated,
            combatTarget != null,
            combatTarget != null && combatTarget.isAlive()
        )) {
            complete(work, "龙已协助击败目标");
            return;
        }
        if (combatTarget == null) {
            fail(work, work.dragonCombatTargetId == null
                ? "没有可协助攻击的有效目标"
                : "协战目标已丢失，无法确认目标是否被击败",
                work.dragonCombatTargetId == null ? "DRAGON_COMBAT_TARGET_MISSING" : "DRAGON_COMBAT_TARGET_LOST");
            return;
        }
        if (work.dragonCombatTargetId == null) work.dragonCombatTargetId = combatTarget.getUUID();
        DragonActionPolicy.Decision decision = DragonActionPolicy.combat(
            combatTarget.isAlive(), dragonActionElapsedTicks(work)
        );
        if (decision == DragonActionPolicy.Decision.COMPLETE) {
            complete(work, "龙已协助击败 " + combatTarget.getDisplayName().getString());
            return;
        }
        if (decision == DragonActionPolicy.Decision.TIMED_OUT) {
            fail(work, "龙协助战斗超时，目标仍然存活", "DRAGON_COMBAT_TIMEOUT");
            return;
        }
        if (!isValidAssistTarget(owner, combatTarget) || combatTarget.level() != dragon.level()) {
            fail(work, "协战目标不在龙所在的维度或不是有效敌人", "DRAGON_COMBAT_TARGET_INVALID");
            return;
        }
        double distance = dragon.distanceTo(combatTarget);
        double reach = DragonActionPolicy.combatReach(
            dragon.getBbWidth(), dragon.getBbHeight(), combatTarget.getBbWidth()
        );
        boolean companionRiding = npc.getVehicle() == dragon || owner.getVehicle() == dragon;
        if (distance > reach) trackNavigation(work, distance);
        else {
            work.stalledTicks = 0;
            work.lastDistance = distance;
        }
        if (DragonActionPolicy.shouldSteerRiddenCombat(companionRiding, distance, reach)) {
            steerMountedDragonToward(work, dragon, combatTarget.getEyePosition(), true);
        } else if (!companionRiding && adapter.isFlying(dragon) && distance > reach
            && work.ticks % DragonActionPolicy.COMBAT_COMMAND_INTERVAL_TICKS == 0) {
            adapter.flyTo(dragon, combatTarget.getEyePosition(), owner);
        }
        if (DragonActionPolicy.shouldIssueCombatCommand(work.initialized, work.ticks, work.lastActionTick)) {
            work.initialized = true;
            work.lastActionTick = work.ticks;
            if (adapter.assistCombat(dragon, combatTarget, owner)) work.failedActions = 0;
            else if (DragonActionPolicy.commandFailed(++work.failedActions)) {
                fail(work, "这只龙没有接受协战指令", "DRAGON_COMBAT_COMMAND_FAILED");
                return;
            }
        }
        if (work.ticks % 20 == 0) {
            progress(work, 0.5D, "龙正在协助攻击 " + combatTarget.getDisplayName().getString()
                + "，目标生命 " + Math.round(combatTarget.getHealth()) + "/" + Math.round(combatTarget.getMaxHealth()));
        }
    }

    private boolean steerMountedDragonToward(
        ActiveWork work,
        Entity dragon,
        Vec3 destination,
        boolean allowCanopyEscape
    ) {
        ServerPlayer owner = npc.owner();
        if (npc.getVehicle() != dragon && (owner == null || owner.getVehicle() != dragon)) return false;
        if (owner != null && owner.getVehicle() == dragon
            && !DragonAutopilotControl.begin(dragon, owner)) return false;
        DragonAdapter mountedAdapter = DragonAdapters.forEntity(dragon);
        if (allowCanopyEscape && mountedAdapter != null && owner != null) {
            mountedAdapter.maintainMountedAirborneState(dragon, owner);
        }
        Vec3 offset = destination.subtract(dragon.position());
        double distance = offset.length();
        if (distance < 0.01D) {
            if (owner != null) DragonAutopilotControl.sync(dragon, owner);
            return true;
        }
        Vec3 step = offset.scale(Math.min(DRAGON_MOUNTED_FLIGHT_STEP, distance) / distance);
        if (!dragon.level().noCollision(dragon, dragon.getBoundingBox().move(step))) {
            step = mountedCollisionRecoveryStep(dragon, destination, step, allowCanopyEscape);
            if (step == null && allowCanopyEscape && work != null && owner != null
                && DragonTerrainAvoidancePolicy.shouldUseMountedTerrainRecovery(
                    owner.hasPermissions(2), work.stalledTicks
                )) {
                Vec3 recovered = findMountedVerticalRecovery(dragon);
                if (recovered != null) {
                    dragon.setPos(recovered.x, recovered.y, recovered.z);
                    dragon.setDeltaMovement(Vec3.ZERO);
                    dragon.hasImpulse = true;
                    resetMountedFallDistance(dragon);
                    DragonAutopilotControl.sync(dragon, owner);
                    work.stalledTicks = 0;
                    work.lastDistance = -1.0D;
                    npc.setStatus("骑龙路线受阻，已使用作弊权限垂直脱离地形");
                    return true;
                }
            }
            if (step == null) return false;
        }
        Vec3 next = dragon.position().add(step);
        double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(offset.z, offset.x)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(offset.y, horizontal));
        dragon.setPos(next.x, next.y, next.z);
        dragon.setDeltaMovement(step);
        dragon.setYRot(yaw);
        dragon.setXRot(pitch);
        dragon.hasImpulse = true;
        resetMountedFallDistance(dragon);
        if (dragon instanceof LivingEntity living) {
            living.yBodyRot = yaw;
            living.setYHeadRot(yaw);
        }
        if (owner != null) DragonAutopilotControl.sync(dragon, owner);
        return true;
    }

    private Vec3 mountedCollisionRecoveryStep(
        Entity dragon,
        Vec3 destination,
        Vec3 requestedStep,
        boolean allowCanopyEscape
    ) {
        double liftAmount = DragonTerrainAvoidancePolicy.mountedLiftStep(dragon.getBbHeight());
        if (allowCanopyEscape) {
            Vec3 lift = new Vec3(
                requestedStep.x * 0.18D,
                Math.max(liftAmount, Math.max(0.0D, requestedStep.y)),
                requestedStep.z * 0.18D
            );
            AABB liftedBox = dragon.getBoundingBox().move(lift);
            if (dragon.level().noCollision(dragon, liftedBox) || collisionIsOnlyLeaves(dragon, liftedBox)) {
                return lift;
            }
        }

        double horizontal = Math.hypot(destination.x - dragon.getX(), destination.z - dragon.getZ());
        double baseAngle = horizontal < 0.01D
            ? Math.toRadians(dragon.getYRot() + 90.0F)
            : Math.atan2(destination.z - dragon.getZ(), destination.x - dragon.getX());
        double detourLength = Math.max(0.30D, Math.min(DRAGON_MOUNTED_FLIGHT_STEP, requestedStep.length()));
        double detourY = allowCanopyEscape ? Math.max(0.18D, Math.max(0.0D, requestedStep.y * 0.35D)) : requestedStep.y;
        for (int degrees : new int[] {30, -30, 60, -60, 90, -90, 120, -120, 180}) {
            double angle = baseAngle + Math.toRadians(degrees);
            Vec3 detour = new Vec3(
                Math.cos(angle) * detourLength,
                detourY,
                Math.sin(angle) * detourLength
            );
            if (dragon.level().noCollision(dragon, dragon.getBoundingBox().move(detour))) return detour;
        }
        return null;
    }

    private boolean collisionIsOnlyLeaves(Entity dragon, AABB candidateBox) {
        int minX = Mth.floor(candidateBox.minX + 1.0E-7D);
        int minY = Mth.floor(candidateBox.minY + 1.0E-7D);
        int minZ = Mth.floor(candidateBox.minZ + 1.0E-7D);
        int maxX = Mth.floor(candidateBox.maxX - 1.0E-7D);
        int maxY = Mth.floor(candidateBox.maxY - 1.0E-7D);
        int maxZ = Mth.floor(candidateBox.maxZ - 1.0E-7D);
        boolean foundLeaves = false;
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    position.set(x, y, z);
                    BlockState state = dragon.level().getBlockState(position);
                    if (state.getCollisionShape(dragon.level(), position).isEmpty()) continue;
                    boolean intersects = false;
                    for (AABB blockBox : state.getCollisionShape(dragon.level(), position).toAabbs()) {
                        if (candidateBox.intersects(blockBox.move(x, y, z))) {
                            intersects = true;
                            break;
                        }
                    }
                    if (!intersects) continue;
                    if (!state.is(BlockTags.LEAVES)) return false;
                    foundLeaves = true;
                }
            }
        }
        return foundLeaves;
    }

    private Vec3 findMountedVerticalRecovery(Entity dragon) {
        double maximumLift = DragonTerrainAvoidancePolicy.mountedEscapeSearchHeight(
            dragon.getBbWidth(), dragon.getBbHeight()
        );
        double increment = Math.max(0.5D, Math.min(1.5D, dragon.getBbHeight() * 0.20D));
        for (double lift = increment; lift <= maximumLift + 1.0E-7D; lift += increment) {
            if (dragon.level().noCollision(dragon, dragon.getBoundingBox().move(0.0D, lift, 0.0D))) {
                return dragon.position().add(0.0D, lift, 0.0D);
            }
        }
        return null;
    }

    private void resetMountedFallDistance(Entity dragon) {
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
    }

    private void tickDragonRecall(ActiveWork work, Entity dragon, DragonAdapter adapter, ServerPlayer owner) {
        if (dragon.level() != owner.level()) {
            fail(work, "龙位于其他维度，当前无法安全召回", "DRAGON_DIMENSION_UNREACHABLE");
            return;
        }
        double distance = dragon.distanceTo(owner);
        double physicalRecallReach = DragonActionPolicy.recallReach(dragon.getBbWidth());
        if (work.startDistance < 0) {
            work.startDistance = Math.max(distance, physicalRecallReach + 1.0D);
        }
        boolean longRangeTeleportStaged = DragonActionPolicy.shouldTeleportRecall(
            owner.hasPermissions(2), work.startDistance
        );
        double recallReach = DragonActionPolicy.recallReach(
            dragon.getBbWidth(), longRangeTeleportStaged
        );
        boolean withinReach = !DragonActionPolicy.shouldSteerMovement(distance, recallReach);
        work.stableTicks = withinReach ? work.stableTicks + 1 : 0;
        if (withinReach) {
            adapter.haltTravel(dragon, owner);
            settleMountedDragonAt(dragon, work.destination, owner);
            DragonAutopilotControl.sync(dragon, owner);
        }
        trackNavigation(work, distance);
        DragonActionPolicy.Decision decision = DragonActionPolicy.movement(
            distance,
            recallReach,
            work.stableTicks,
            work.stalledTicks,
            dragonActionElapsedTicks(work),
            DragonActionPolicy.RECALL_TIMEOUT_TICKS
        );
        if (decision == DragonActionPolicy.Decision.COMPLETE) {
            complete(work, "已召回 " + dragon.getDisplayName().getString() + " 到主人身边");
            return;
        }
        if (decision == DragonActionPolicy.Decision.STALLED) {
            fail(work, "龙的召回移动长时间没有进展", "DRAGON_RECALL_STALLED");
            return;
        }
        if (decision == DragonActionPolicy.Decision.TIMED_OUT) {
            fail(work, "召回龙超时", "DRAGON_RECALL_TIMEOUT");
            return;
        }
        if (withinReach) return;
        if (DragonActionPolicy.shouldIssueCommand(work.initialized, work.ticks, work.lastActionTick)) {
            work.initialized = true;
            work.lastActionTick = work.ticks;
            if (adapter.recall(dragon, owner, owner.hasPermissions(2))) work.failedActions = 0;
            else if (DragonActionPolicy.commandFailed(++work.failedActions)) {
                fail(work, "这只龙没有接受召回指令", "DRAGON_RECALL_FAILED");
                return;
            }
        }
        steerMountedDragonToward(work, dragon, owner.position().add(0.0D, 2.0D, 0.0D), true);
        if (work.ticks % 20 == 0) {
            progress(work, DragonActionPolicy.progress(work.startDistance, distance, recallReach),
                "正在召回龙，距离主人 " + Math.round(distance) + " 格");
        }
    }

    private void tickDragonFlyTo(ActiveWork work, Entity dragon, DragonAdapter adapter, ServerPlayer owner) {
        if (!work.spec.has("target")) {
            fail(work, "飞行任务缺少目标坐标", "DRAGON_TARGET_MISSING");
            return;
        }
        if (dragon.level() != owner.level()) {
            fail(work, "龙位于其他维度，无法飞向当前目标", "DRAGON_DIMENSION_UNREACHABLE");
            return;
        }
        if (work.destination == null) work.destination = target(work.spec.getAsJsonObject("target"));
        double distance = dragon.position().distanceTo(work.destination);
        if (work.startDistance < 0) work.startDistance = Math.max(distance, DragonActionPolicy.FLIGHT_REACH + 1.0D);
        boolean withinReach = !DragonActionPolicy.shouldSteerMovement(
            distance, DragonActionPolicy.FLIGHT_REACH
        );
        work.stableTicks = withinReach ? work.stableTicks + 1 : 0;
        if (withinReach) {
            adapter.haltTravel(dragon, owner);
            DragonAutopilotControl.sync(dragon, owner);
        }
        trackNavigation(work, distance);
        DragonActionPolicy.Decision decision = DragonActionPolicy.movement(
            distance,
            DragonActionPolicy.FLIGHT_REACH,
            work.stableTicks,
            work.stalledTicks,
            dragonActionElapsedTicks(work),
            DragonActionPolicy.FLIGHT_TIMEOUT_TICKS
        );
        if (decision == DragonActionPolicy.Decision.COMPLETE) {
            complete(work, "龙已到达目标位置");
            return;
        }
        if (decision == DragonActionPolicy.Decision.STALLED) {
            fail(work, "龙飞行长时间没有接近目标", "DRAGON_FLIGHT_STALLED");
            return;
        }
        if (decision == DragonActionPolicy.Decision.TIMED_OUT) {
            fail(work, "龙飞向目标超时", "DRAGON_FLIGHT_TIMEOUT");
            return;
        }
        if (withinReach) return;
        if (DragonActionPolicy.shouldIssueCommand(work.initialized, work.ticks, work.lastActionTick)) {
            work.initialized = true;
            work.lastActionTick = work.ticks;
            if (adapter.flyTo(dragon, work.destination, owner)) work.failedActions = 0;
            else if (DragonActionPolicy.commandFailed(++work.failedActions)) {
                fail(work, "这只龙没有接受飞行导航指令", "DRAGON_FLIGHT_FAILED");
                return;
            }
        }
        steerMountedDragonToward(work, dragon, work.destination, true);
        if (work.ticks % 20 == 0) {
            progress(work, DragonActionPolicy.progress(work.startDistance, distance, DragonActionPolicy.FLIGHT_REACH),
                "龙正在飞向目标，剩余 " + Math.round(distance) + " 格");
        }
    }

    private void settleMountedDragonAt(Entity dragon, Vec3 destination, ServerPlayer owner) {
        boolean mounted = npc.getVehicle() == dragon || owner.getVehicle() == dragon;
        if (!mounted) return;
        Vec3 offset = destination.subtract(dragon.position());
        if (offset.lengthSqr() > DragonActionPolicy.FLIGHT_REACH * DragonActionPolicy.FLIGHT_REACH) return;
        if (!dragonCanOccupy(dragon, destination)) return;
        dragon.setPos(destination.x, destination.y, destination.z);
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.hasImpulse = true;
        resetMountedFallDistance(dragon);
    }

    private boolean dragonCanOccupy(Entity dragon, Vec3 destination) {
        Vec3 offset = destination.subtract(dragon.position());
        return dragon.level().noCollision(dragon, dragon.getBoundingBox().move(offset));
    }

    private void tickDragonLand(ActiveWork work, Entity dragon, DragonAdapter adapter, ServerPlayer owner) {
        if (dragon.level() != owner.level()) {
            fail(work, "龙位于其他维度，无法在主人附近降落", "DRAGON_DIMENSION_UNREACHABLE");
            return;
        }
        boolean companionRiding = npc.getVehicle() == dragon || owner.getVehicle() == dragon;
        boolean landingApproachPhase = false;
        boolean canopyEscapePhase = false;
        boolean finalizingLanding = work.dragonLandingCommitted;
        Vec3 landingNavigationTarget = null;
        double landingDistance = Double.NaN;
        double landingHorizontalDistance = Double.NaN;
        double landingVerticalDistance = Double.NaN;
        if (companionRiding) {
            if (work.destination == null) work.destination = adapter.safeLandingTarget(dragon, owner);
            if (work.destination == null) {
                if (work.ticks % 20 == 0) {
                    progress(work, 0.25D, "正在扩大范围寻找可容纳巨龙的安全落地点");
                }
            } else {
                landingHorizontalDistance = Math.hypot(
                    dragon.getX() - work.destination.x,
                    dragon.getZ() - work.destination.z
                );
                landingVerticalDistance = work.destination.y - dragon.getY();
                landingApproachPhase = landingHorizontalDistance
                    > DragonTerrainAvoidancePolicy.landingApproachReach(dragon.getBbWidth());
                landingNavigationTarget = landingApproachPhase
                    ? dragonLandingApproach(dragon, work.destination)
                    : work.destination;
                landingDistance = dragon.position().distanceTo(landingNavigationTarget);
                // A dragon can begin a landing while its body is already
                // embedded in a tree canopy. Once horizontally centred, the
                // perch is above the current body position, so the same
                // leaves-only upward escape used by the approach phase must
                // remain enabled until the body clears the canopy.
                canopyEscapePhase = DragonTerrainAvoidancePolicy.shouldAllowCanopyEscape(
                    landingApproachPhase, work.destination.y, dragon.getY()
                );
                if (work.dragonLandingCommitted) {
                    finalizingLanding = true;
                } else if (landingDistance > 0.75D) {
                    steerMountedDragonToward(
                        work, dragon, landingNavigationTarget,
                        landingApproachPhase || canopyEscapePhase
                    );
                    trackNavigation(work, landingDistance);
                    if (DragonTerrainAvoidancePolicy.shouldRecoverStalledLanding(work.stalledTicks)) {
                        boolean recovered = owner.hasPermissions(2)
                            && forceDragonGrounded(dragon, work.destination, owner);
                        if (recovered) {
                            work.dragonLandingCommitted = true;
                            finalizingLanding = true;
                            work.stalledTicks = 0;
                            work.lastDistance = -1.0D;
                            npc.setStatus("降落路线持续受阻，已安全落到验证过的落点");
                        } else {
                            work.destination = null;
                            work.initialized = false;
                            work.stalledTicks = 0;
                            work.lastDistance = -1.0D;
                            npc.setStatus("降落路线持续受阻，正在重新寻找可达落点");
                        }
                    }
                }
                else {
                    work.dragonLandingCommitted = forceDragonGrounded(dragon, work.destination, owner);
                    finalizingLanding = work.dragonLandingCommitted;
                    if (!work.dragonLandingCommitted) {
                        trackNavigation(work, landingDistance);
                        if (DragonTerrainAvoidancePolicy.shouldRecoverStalledLanding(work.stalledTicks)) {
                            work.destination = null;
                            work.initialized = false;
                            work.stalledTicks = 0;
                            work.lastDistance = -1.0D;
                            npc.setStatus("贴近的落点无法形成真实支撑，正在重新寻找安全落点");
                        }
                    }
                }
            }
        }
        boolean flying = adapter.isFlying(dragon);
        boolean onGround = dragon.onGround();
        boolean landed = !flying && onGround;
        work.stableTicks = landed ? work.stableTicks + 1 : 0;
        DragonActionPolicy.Decision decision = DragonActionPolicy.landing(
            flying, onGround, work.stableTicks, dragonActionElapsedTicks(work)
        );
        if (decision == DragonActionPolicy.Decision.COMPLETE) {
            complete(work, "龙已安全落地");
            return;
        }
        if (decision == DragonActionPolicy.Decision.TIMED_OUT) {
            fail(work, "龙降落超时", "DRAGON_LAND_TIMEOUT");
            return;
        }
        boolean landingSiteReady = !companionRiding || work.destination != null;
        if (landingSiteReady
            && DragonActionPolicy.shouldIssueLandingCommand(
                finalizingLanding, work.initialized, work.ticks, work.lastActionTick
            )) {
            work.initialized = true;
            work.lastActionTick = work.ticks;
            boolean accepted = landingApproachPhase
                ? adapter.flyTo(dragon, landingNavigationTarget, owner)
                : adapter.land(dragon, owner);
            if (accepted) work.failedActions = 0;
            else work.failedActions++;
        }
        if (work.ticks % 20 == 0) {
            String phase = finalizingLanding
                ? "正在确认落地"
                : landingApproachPhase
                    ? "正在飞向安全落点上方"
                    : canopyEscapePhase
                        ? "正在向上脱离树冠后落稳"
                        : "正在垂直降落";
            String distance = Double.isFinite(landingDistance)
                ? "，距导航点 " + String.format(Locale.ROOT, "%.1f", landingDistance)
                    + " 格（水平 " + String.format(Locale.ROOT, "%.1f", landingHorizontalDistance)
                    + "，高差 " + String.format(Locale.ROOT, "%.1f", landingVerticalDistance) + "）"
                : "";
            progress(work, finalizingLanding ? 0.9D : 0.5D, "龙" + phase + distance);
        }
    }

    private Vec3 dragonLandingTarget(ServerPlayer owner) {
        Vec3 look = owner.getLookAngle();
        double horizontalLength = Math.sqrt(look.x * look.x + look.z * look.z);
        double offsetX = horizontalLength > 0.01D ? -look.x / horizontalLength * 4.0D : 0.0D;
        double offsetZ = horizontalLength > 0.01D ? -look.z / horizontalLength * 4.0D : 4.0D;
        double x = owner.getX() + offsetX;
        double z = owner.getZ() + offsetZ;
        double y = owner.onGround()
            ? owner.getY()
            : owner.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(x), (int) Math.floor(z));
        return new Vec3(x, y, z);
    }

    private Vec3 dragonLandingApproach(Entity dragon, Vec3 landingTarget) {
        if (!(dragon.level() instanceof ServerLevel level)) {
            return new Vec3(landingTarget.x, Math.max(dragon.getY(), landingTarget.y + 4.0D), landingTarget.z);
        }
        double horizontalDistance = Math.hypot(
            landingTarget.x - dragon.getX(),
            landingTarget.z - dragon.getZ()
        );
        int samples = DragonTerrainAvoidancePolicy.routeSamples(horizontalDistance, dragon.getBbWidth());
        double highestTerrain = landingTarget.y;
        for (int sample = 0; sample <= samples; sample++) {
            double alpha = sample / (double) samples;
            double x = dragon.getX() + (landingTarget.x - dragon.getX()) * alpha;
            double z = dragon.getZ() + (landingTarget.z - dragon.getZ()) * alpha;
            highestTerrain = Math.max(highestTerrain, level.getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(x),
                (int) Math.floor(z)
            ));
        }
        double clearance = DragonTerrainAvoidancePolicy.clearance(
            dragon.getBbWidth(), dragon.getBbHeight()
        );
        double maximumBottomY = level.getMaxBuildHeight() - dragon.getBbHeight() - 2.0D;
        double approachY = DragonTerrainAvoidancePolicy.safeAltitude(
            landingTarget.y + clearance,
            highestTerrain,
            clearance,
            maximumBottomY
        );
        return new Vec3(landingTarget.x, Math.max(dragon.getY(), approachY), landingTarget.z);
    }

    private boolean forceDragonGrounded(Entity dragon, Vec3 destination, ServerPlayer owner) {
        if (owner != null && owner.getVehicle() == dragon
            && !DragonAutopilotControl.begin(dragon, owner)) return false;
        if (dragon instanceof Mob mob) mob.getNavigation().stop();
        Object aiMovement = invokeNoArg(dragon, "getAIMovement");
        if (aiMovement != null) invokeNoArg(aiMovement, "clearAllWaypoints");
        Vec3 supported = supportedLandingPosition(dragon, destination);
        if (supported == null) return false;
        dragon.setPos(supported.x, supported.y, supported.z);
        dragon.setNoGravity(false);
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.hasImpulse = true;
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
        boolean collisionBelow = !dragon.level().noCollision(
            dragon, dragon.getBoundingBox().move(0.0D, -0.125D, 0.0D)
        );
        BlockPos feet = dragon.blockPosition();
        boolean feetInFluid = !dragon.level().getFluidState(feet).isEmpty()
            || !dragon.level().getFluidState(feet.below()).isEmpty();
        boolean grounded = DragonActionPolicy.shouldCommitGroundContact(collisionBelow, feetInFluid);
        if (!grounded) {
            dragon.setOnGround(false);
            if (owner != null) DragonAutopilotControl.sync(dragon, owner);
            return false;
        }
        // Saints rejects a request to clear flying while a ridden dragon is
        // still marked airborne. Commit the verified support first, then let
        // each mod transition its public flight state to grounded.
        dragon.setOnGround(true);
        invokeOneArg(dragon, "setGoingUp", false);
        invokeOneArg(dragon, "setGoingDown", false);
        invokeOneArg(dragon, "setThrottleLevel", 0.0F);
        invokeOneArg(dragon, "setAccelerating", false);
        invokeOneArg(dragon, "setDecelerating", false);
        invokeOneArg(dragon, "setLanding", false);
        invokeOneArg(dragon, "setFlying", false);
        invokeOneArg(dragon, "setIsOnSolidGround", true);
        invokeEnumOneArg(dragon, "setTransportMode", "GROUNDED");
        invokeEnumOneArg(dragon, "setGroundStance", "IDLE");
        Object stateContext = invokeNoArg(dragon, "getStateContext");
        if (stateContext != null) {
            invokeEnumOneArg(stateContext, "setTransportMode", "GROUNDED");
            invokeEnumOneArg(stateContext, "setGroundStance", "IDLE");
        }
        dragon.setOnGround(true);
        if (owner != null) DragonAutopilotControl.sync(dragon, owner);
        return true;
    }

    private Vec3 supportedLandingPosition(Entity dragon, Vec3 destination) {
        AABB destinationBox = dragon.getBoundingBox().move(
            destination.x - dragon.getX(),
            destination.y - dragon.getY(),
            destination.z - dragon.getZ()
        );

        // A heightmap landing target can sit a fraction inside carpet, slabs,
        // leaves or a modded collision shape. Search upward for the closest
        // body-clear box, then probe down to the first real support surface.
        // This still requires physical collision and never commits a floating
        // position merely because the mod reports an on-ground state.
        for (int liftStep = 0;
             liftStep <= DragonTerrainAvoidancePolicy.LANDING_SUPPORT_SEARCH_STEPS;
             liftStep++) {
            double lift = liftStep * DragonTerrainAvoidancePolicy.LANDING_SUPPORT_STEP;
            AABB clearBox = destinationBox.move(0.0D, lift, 0.0D);
            if (!dragon.level().noCollision(dragon, clearBox)) continue;
            double lastClearDrop = 0.0D;
            for (int dropStep = 1;
                 dropStep <= DragonTerrainAvoidancePolicy.LANDING_SUPPORT_SEARCH_STEPS;
                 dropStep++) {
                double drop = dropStep * DragonTerrainAvoidancePolicy.LANDING_SUPPORT_STEP;
                if (!dragon.level().noCollision(dragon, clearBox.move(0.0D, -drop, 0.0D))) {
                    return destination.add(0.0D, lift - lastClearDrop, 0.0D);
                }
                lastClearDrop = drop;
            }
        }
        return null;
    }

    private void interactWithDragon(ActiveWork work, Entity target, String action) {
        if (!work.initialized && work.ticks - work.lastActionTick < 8) return;
        int slot = work.initialized ? work.sourceSlot : findDragonFoodSlot(target, action.equals("heal"));
        ItemStack stack = slot >= 0 ? npc.inventory().getStackInSlot(slot) : ItemStack.EMPTY;
        if (slot < 0 && !npc.creativeResources()) {
            fail(work, "NPC 背包中没有适合这只龙的食物", "DRAGON_FOOD_MISSING");
            return;
        }
        if (npc.creativeResources() && stack.isEmpty()) stack = new ItemStack(Items.SALMON);

        ServerPlayer owner = npc.owner();
        if (!work.initialized) {
            DragonCareAcceptancePolicy.State before = dragonCareState(target, slot, owner);
            work.sourceSlot = slot;
            work.dragonCareItemCount = before.itemCount();
            work.dragonCareHealth = before.health();
            work.dragonCareFood = before.food();
            work.dragonCareHappiness = before.happiness();
            work.dragonCareOwned = before.owned();
            work.dragonCareIdentity = before.identity();
            InteractionResult result = proxy.interact(target, stack, slot);
            npc.swing(InteractionHand.MAIN_HAND);
            work.initialized = true;
            work.lastActionTick = work.ticks;
            work.failedActions = result.consumesAction() ? 0 : 1;
            return;
        }

        DragonCareAcceptancePolicy.State before = dragonCareBefore(work);
        DragonCareAcceptancePolicy.State after = dragonCareState(target, slot, owner);
        boolean requireItemConsumption = !npc.creativeResources();
        boolean accepted = action.equals("heal")
            ? DragonCareAcceptancePolicy.healed(before, after, requireItemConsumption)
            : DragonCareAcceptancePolicy.fed(before, after, requireItemConsumption);
        if (accepted) {
            complete(work, action.equals("heal")
                ? "已为龙喂食并确认生命恢复"
                : "已喂养龙并确认饱食状态改善");
            return;
        }
        DragonAdapter adapter = DragonAdapters.forEntity(target);
        String modId = adapter == null ? "" : adapter.modId();
        int ticksSinceLastAction = work.ticks - work.lastActionTick;
        if (action.equals("heal") && DragonCareAcceptancePolicy.shouldRefillForHealing(
            modId,
            rawDragonFoodLevel(target),
            !stack.isEmpty(),
            ticksSinceLastAction
        )) {
            InteractionResult result = proxy.interact(target, stack, slot);
            npc.swing(InteractionHand.MAIN_HAND);
            work.lastActionTick = work.ticks;
            if (result.consumesAction()) work.failedActions = 0;
            else work.failedActions++;
            return;
        }
        int confirmationTicks = action.equals("heal")
            ? DragonCareAcceptancePolicy.healingConfirmationTicks(modId)
            : 40;
        if (ticksSinceLastAction >= confirmationTicks) {
            fail(work, "龙互动未产生可验证的物品消耗和状态变化（"
                + dragonInteractionDiagnostic(target, stack, InteractionResult.PASS) + "）",
                "DRAGON_INTERACTION_NOT_CONFIRMED");
        }
    }

    private void tickDragonEggCare(ActiveWork work) {
        Entity egg = findDragonEggEntity(work);
        if (egg != null) {
            if (!approach(work, egg.blockPosition(), 3.0D, 1.08D)) return;
            tickDragonEggProgress(work, egg, egg.getUUID().toString(), dragonEggProgress(egg), () -> {
                proxy.interact(egg, ItemStack.EMPTY, -1);
                npc.swing(InteractionHand.MAIN_HAND);
            });
            return;
        }

        BlockPos eggPosition = findDragonEggBlock(work);
        if (eggPosition == null) {
            fail(work, "附近没有找到龙蛋实体或龙蛋方块", "DRAGON_EGG_NOT_FOUND");
            return;
        }
        if (!approach(work, eggPosition, 3.0D, 1.08D)) return;
        BlockState state = npc.level().getBlockState(eggPosition);
        BlockEntity blockEntity = npc.level().getBlockEntity(eggPosition);
        String identity = id(state.getBlock()) + "@" + eggPosition.asLong();
        tickDragonEggProgress(work, blockEntity, identity, dragonEggProgress(blockEntity), () -> {
            proxy.useItemOn(eggPosition, Direction.UP, ItemStack.EMPTY, -1);
            npc.swing(InteractionHand.MAIN_HAND);
        });
    }

    private void tickDragonEggProgress(
        ActiveWork work,
        Object target,
        String identity,
        double progressValue,
        Runnable inspectAction
    ) {
        boolean present = target != null && Double.isFinite(progressValue) && progressValue >= 0.0D;
        if (!work.initialized) {
            if (!present) {
                fail(work, "龙蛋没有可验证的孵化状态", "DRAGON_EGG_STATE_UNAVAILABLE");
                return;
            }
            work.initialized = true;
            work.startDistance = progressValue;
            work.dragonCareIdentity = identity;
            work.lastActionTick = work.ticks;
            inspectAction.run();
            return;
        }
        DragonCareAcceptancePolicy.State before = eggCareState(
            work.dragonCareIdentity, true, work.startDistance
        );
        DragonCareAcceptancePolicy.State after = eggCareState(identity, present, progressValue);
        if (DragonCareAcceptancePolicy.eggAdvanced(before, after)) {
            complete(work, "已照料龙蛋并确认孵化进度向前");
            return;
        }
        if (work.ticks - work.lastActionTick >= 200) {
            fail(work, "龙蛋孵化进度未向前，请检查孵化条件", "DRAGON_EGG_PROGRESS_STALLED");
        }
    }

    private void tickDragonTame(
        ActiveWork work,
        Entity target,
        DragonAdapter adapter,
        ServerPlayer owner
    ) {
        if (!(target instanceof TamableAnimal tameable)) {
            fail(work, "这只龙没有可用的驯服接口", "DRAGON_TAME_UNSUPPORTED");
            return;
        }
        if (tameable.isTame()) {
            if (adapter.isOwnedBy(target, owner)) complete(work, "龙已经属于主人，无需重复驯服");
            else fail(work, "这只龙已经属于其他主人", "DRAGON_ALREADY_OWNED");
            return;
        }
        int slot = findDragonFoodSlot(target, true);
        ItemStack food = slot >= 0 ? npc.inventory().getStackInSlot(slot) : ItemStack.EMPTY;
        if (slot < 0 && !npc.creativeResources()) {
            fail(work, "NPC 背包中没有可用于驯服的龙食", "DRAGON_FOOD_MISSING");
            return;
        }
        if (food.isEmpty()) food = new ItemStack(Items.SALMON);
        DragonCareAcceptancePolicy.State before = dragonCareState(target, slot, owner);

        // Both supported mods extend TamableAnimal. Use their real ownership
        // state, then finish Book of Dragons' crouch ritual flags so later
        // follow, feeding, saddle and riding checks observe a coherent tame.
        tameable.tame(owner);
        tameable.setOrderedToSit(false);
        invokeOneArg(target, "setAwaitingTamingRitual", false);
        invokeOneArg(target, "setTamingRitualCompleted", true);
        invokeOneArg(target, "setTamingRitualTimer", 0);
        invokeOneArg(target, "setCommand", adapter.followCommand());
        if (!npc.creativeResources() && slot >= 0) {
            food.shrink(1);
            npc.inventory().setStackInSlot(slot, food);
        }
        npc.swing(InteractionHand.MAIN_HAND);
        DragonCareAcceptancePolicy.State after = dragonCareState(target, slot, owner);
        if (!DragonCareAcceptancePolicy.tamed(before, after, !npc.creativeResources())) {
            fail(work, "驯服后没有同时确认龙的所有权与驯服物品消耗", "DRAGON_TAME_NOT_CONFIRMED");
            return;
        }
        complete(work, "龙已完成驯服并绑定给主人");
    }

    private String dragonInteractionDiagnostic(Entity target, ItemStack stack, InteractionResult result) {
        Object needs = invokeNoArg(target, "getNeedsSystem");
        Object hunger = needs == null ? null : invokeNoArg(needs, "getFoodLevel");
        ServerPlayer owner = npc.owner();
        DragonAdapter adapter = DragonAdapters.forEntity(target);
        boolean owned = owner != null && adapter != null && adapter.isOwnedBy(target, owner);
        return "结果=" + result
            + "，物品=" + itemId(stack)
            + "，已驯服=" + Boolean.TRUE.equals(invokeNoArg(target, "isTame"))
            + "，属于主人=" + owned
            + "，可食用=" + invokeBoolean(target, "isFood", stack)
            + "，偏爱=" + invokeBoolean(target, "isFavoriteFood", stack)
            + "，特殊=" + invokeBoolean(target, "isSpecialFood", stack)
            + "，普通=" + invokeBoolean(target, "isGeneralFood", stack)
            + (hunger instanceof Number number ? "，饱食=" + number.intValue() : "");
    }

    private boolean approach(ActiveWork work, BlockPos target, double reach, double speed) {
        double distance = npc.position().distanceTo(Vec3.atCenterOf(target));
        if (distance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(Vec3.atCenterOf(target));
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        if (teleportNearTaskTargetWhenAllowed(work, target, distance, "距离过远，已传送到任务目标附近")) return false;
        Path currentPath = npc.getNavigation().getPath();
        boolean navigationInProgress = !npc.getNavigation().isDone() && currentPath != null;
        if (GatherRetryPolicy.shouldRepathDestination(
            navigationInProgress,
            work.stalledTicks,
            work.ticks
        )) {
            if (work.stalledTicks > 0) npc.getNavigation().stop();
            navigateTowardBlock(target, speed);
        }
        npc.addExhaustion(0.002F);
        taskStatus(work, "正在接近任务目标，距离 " + Math.round(distance) + " 格");
        trackNavigation(work, distance);
        if (work.stalledTicks > 200) {
            fail(work, "无法寻路到目标", "PATH_NOT_FOUND");
        }
        return false;
    }

    private boolean approachGatherDestination(
        ActiveWork work,
        BlockPos target,
        double reach,
        double speed,
        String exhaustedMessage,
        String exhaustedCode
    ) {
        double distance = npc.position().distanceTo(Vec3.atCenterOf(target));
        if (distance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(Vec3.atCenterOf(target));
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        if (teleportNearTaskTargetWhenAllowed(work, target, distance, "距离过远，已传送到任务目标附近")) return false;
        navigateTowardBlock(target, speed);
        npc.addExhaustion(0.002F);
        taskStatus(work, "正在前往资源搜索区，距离 " + Math.round(distance) + " 格");
        trackNavigation(work, distance);
        if (work.stalledTicks > 200) {
            npc.getNavigation().stop();
            if (work.gatherExcursions >= GATHER_MAX_EXCURSIONS) {
                fail(work, exhaustedMessage, exhaustedCode);
            } else {
                work.gatherExcursions++;
                work.destination = nextGatherSearchDestination(work.gatherExcursions);
                work.gatherSearchRadius = 16;
                work.noWorkTicks = 0;
                work.lastSearchTick = -10;
                work.stalledTicks = 0;
                work.gatherPathFailures = 0;
                work.lastDistance = -1;
                npc.setStatus("远程搜索区不可达，改去第 " + work.gatherExcursions + " 个搜索区");
            }
        }
        return false;
    }

    private boolean approachGatherTarget(ActiveWork work, BlockPos target, double reach, double speed) {
        Vec3 targetCenter = Vec3.atCenterOf(target);
        double effectiveReach = GatherNavigationPolicy.effectiveReach(reach, GATHER_INTERACTION_REACH);
        double interactionDistance = gatherInteractionDistance(target);
        if (interactionDistance <= effectiveReach && hasGatherLineOfSight(npc.getEyePosition(), target)) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(targetCenter);
            work.stalledTicks = 0;
            work.gatherPathFailures = 0;
            work.lastDistance = -1;
            work.lastGatherPathAttemptTick = -1;
            work.gatherStandPathCursor = 0;
            return true;
        }
        double distance = npc.position().distanceTo(targetCenter);
        if (teleportNearTaskTargetWhenAllowed(work, target, distance, "资源距离过远，已传送到采集点附近")) return false;
        Path path = npc.getNavigation().getPath();
        boolean pathStarted = false;
        boolean pathAttempted = false;
        if ((path == null || npc.getNavigation().isDone())
            && GatherRetryPolicy.shouldAttemptPath(work.ticks, work.lastGatherPathAttemptTick)) {
            work.lastGatherPathAttemptTick = work.ticks;
            pathAttempted = true;
            path = findGatherStandPath(work, target, reach);
            if (path != null) pathStarted = npc.getNavigation().moveTo(path, speed);
        }
        taskStatus(work, "正在走向采集点，距离 " + Math.round(distance) + " 格");
        work.gatherPathFailures = GatherRetryPolicy.nextPathFailureCount(
            work.gatherPathFailures,
            pathStarted,
            !npc.getNavigation().isDone(),
            pathAttempted && !pathStarted
        );
        npc.addExhaustion(0.002F);
        // Stand candidates may change on every repath. Measuring progress
        // against the fixed resource block prevents that oscillation from
        // repeatedly clearing a genuinely stalled walk-only gather.
        trackNavigation(work, distance);
        if (GatherRetryPolicy.targetIsUnreachable(work.gatherPathFailures, work.stalledTicks)) {
            skipGatherTarget(work, target);
        }
        return false;
    }

    private Path findGatherStandPath(ActiveWork work, BlockPos target, double reach) {
        List<BlockPos> candidates = new ArrayList<>();
        double effectiveReach = GatherNavigationPolicy.effectiveReach(reach, GATHER_INTERACTION_REACH);
        int horizontalRadius = GatherNavigationPolicy.horizontalCandidateRadius(effectiveReach);
        for (int dy = GatherNavigationPolicy.MIN_VERTICAL_OFFSET; dy <= GatherNavigationPolicy.MAX_VERTICAL_OFFSET; dy++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    if (!GatherNavigationPolicy.allowsStandOffset(dx, dy, dz)) continue;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (gatherInteractionDistanceFromStand(candidate, target) > effectiveReach) continue;
                    if (!isSafeGatherStand(candidate)
                        || !hasGatherLineOfSight(gatherEyePositionFromStand(candidate), target)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
            candidate -> npc.position().distanceToSqr(Vec3.atBottomCenterOf(candidate))
        ));
        int candidateCount = candidates.size();
        int attemptCount = GatherRetryPolicy.candidateAttemptCount(candidateCount);
        int cursor = GatherRetryPolicy.normalizedCandidateCursor(work.gatherStandPathCursor, candidateCount);
        for (int attempt = 0; attempt < attemptCount; attempt++) {
            BlockPos candidate = candidates.get((cursor + attempt) % candidateCount);
            Path path = npc.getNavigation().createPath(candidate, 0);
            if (path != null && pathGetsWithinGatherReach(path, target, effectiveReach)) {
                work.gatherStandPathCursor = 0;
                return path;
            }
        }
        work.gatherStandPathCursor = GatherRetryPolicy.nextCandidateCursor(cursor, attemptCount, candidateCount);
        return null;
    }

    private int dragonActionElapsedTicks(ActiveWork work) {
        if (work.dragonActionStartedTick < 0) work.dragonActionStartedTick = work.ticks;
        return Math.max(0, work.ticks - work.dragonActionStartedTick);
    }

    private boolean pathGetsWithinGatherReach(Path path, BlockPos target, double reach) {
        if (path.canReach()) return true;
        if (path.getNodeCount() <= 0 || path.getEndNode() == null) return false;
        Vec3 end = path.getEndNode().asVec3();
        double eyeHeight = npcEyeHeight();
        double directNodeDistance = GatherNavigationPolicy.blockTouchDistance(
            end.x,
            end.y + eyeHeight,
            end.z,
            target.getX(),
            target.getY(),
            target.getZ()
        );
        double centeredNodeDistance = GatherNavigationPolicy.blockTouchDistance(
            end.x + 0.5,
            end.y + eyeHeight,
            end.z + 0.5,
            target.getX(),
            target.getY(),
            target.getZ()
        );
        return Math.min(directNodeDistance, centeredNodeDistance) <= reach;
    }

    private boolean teleportNearTaskTargetWhenAllowed(ActiveWork work, BlockPos target, double distance, String status) {
        ServerPlayer owner = npc.owner();
        if (!gatherAllowsRemoteRecovery(work)) return false;
        double verticalDistance = Math.abs(npc.getY() - (target.getY() + 0.5D));
        if (!GatherRetryPolicy.shouldAttemptTeleport(
                distance,
                config.npcRecallDistance,
                verticalDistance,
                work.stalledTicks
            ) || owner == null || !owner.hasPermissions(2)
            || !(npc.level() instanceof ServerLevel level)) return false;
        boolean skippableResourceTarget = work.kind.equals("gather") || hasCraftGatherPrerequisite(work);
        if (target.equals(work.lastTeleportTarget)) {
            // Never loop a teleport against the same bad target. Resource work
            // can switch targets; workstation, delivery and other task targets
            // fall back to ordinary pathfinding instead of failing immediately.
            if (GatherRetryPolicy.shouldSkipAfterUnusableTeleport(skippableResourceTarget)) {
                skipGatherTarget(work, target);
            }
            return false;
        }
        BlockPos destination = safeTaskPositionNear(level, target);
        double destinationDistance = Vec3.atCenterOf(destination).distanceTo(Vec3.atCenterOf(target));
        if (!GatherRetryPolicy.teleportDestinationIsUseful(destinationDistance)) {
            if (GatherRetryPolicy.shouldSkipAfterUnusableTeleport(skippableResourceTarget)) {
                skipGatherTarget(work, target);
            } else {
                work.lastTeleportTarget = target.immutable();
                npc.setStatus("未找到安全传送落点，已改为步行前往任务目标");
            }
            return false;
        }
        npc.getNavigation().stop();
        ChunkPos currentChunk = new ChunkPos(npc.blockPosition());
        ChunkPos destinationChunk = new ChunkPos(destination);
        if (GatherRetryPolicy.teleportChangesChunk(
            currentChunk.x,
            currentChunk.z,
            destinationChunk.x,
            destinationChunk.z
        )) {
            // Entity ticks stop immediately when a remote teleport lands outside
            // every player's simulation distance. Move the persistent task ticket
            // before teleporting so the next gather tick can actually run.
            maintainTaskChunkTicket(destination);
        }
        npc.teleportTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0;
        maintainTaskChunkTicket(npc.blockPosition());
        work.lastTeleportTarget = target.immutable();
        work.stalledTicks = 0;
        work.gatherPathFailures = 0;
        work.lastDistance = -1;
        npc.setStatus(status);
        return true;
    }

    private boolean isSafeGatherStand(BlockPos candidate) {
        BlockState floor = npc.level().getBlockState(candidate.below());
        BlockState feet = npc.level().getBlockState(candidate);
        BlockState head = npc.level().getBlockState(candidate.above());
        return floor.isSolidRender(npc.level(), candidate.below())
            && feet.getCollisionShape(npc.level(), candidate).isEmpty()
            && head.getCollisionShape(npc.level(), candidate.above()).isEmpty()
            && npc.level().getFluidState(candidate).isEmpty()
            && npc.level().getFluidState(candidate.above()).isEmpty();
    }

    private boolean hasSafeGatherStand(BlockPos target, double reach) {
        double effectiveReach = GatherNavigationPolicy.effectiveReach(reach, GATHER_INTERACTION_REACH);
        int horizontalRadius = GatherNavigationPolicy.horizontalCandidateRadius(effectiveReach);
        for (int dy = GatherNavigationPolicy.MIN_VERTICAL_OFFSET; dy <= GatherNavigationPolicy.MAX_VERTICAL_OFFSET; dy++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    if (!GatherNavigationPolicy.allowsStandOffset(dx, dy, dz)) continue;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (gatherInteractionDistanceFromStand(candidate, target) <= effectiveReach
                        && isSafeGatherStand(candidate)
                        && hasGatherLineOfSight(gatherEyePositionFromStand(candidate), target)) return true;
                }
            }
        }
        return false;
    }

    private BlockPos safeTaskPositionNear(ServerLevel level, BlockPos target) {
        int[] verticalOffsets = { 0, 1, -1, 2, -2, 3, -3, 4, -4 };
        // Prefer a stand on the target's own underground level. Heightmap-only
        // probing places an NPC on the surface above an underground workstation
        // and incorrectly declares that otherwise reachable target unsafe.
        for (int radius = 0; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    for (int dy : verticalOffsets) {
                        BlockPos candidate = target.offset(dx, dy, dz);
                        if (isDryStandingSpot(level, candidate)) return candidate.immutable();
                    }
                }
            }
        }
        for (int radius = 0; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = target.getX() + dx;
                    int z = target.getZ() + dz;
                    int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    for (int dy = 2; dy >= -4; dy--) {
                        BlockPos candidate = new BlockPos(x, surfaceY + dy, z);
                        if (isDryStandingSpot(level, candidate)) return candidate;
                    }
                }
            }
        }
        return NpcManager.safePosition(level, target);
    }

    private static boolean isDryStandingSpot(ServerLevel level, BlockPos candidate) {
        BlockState floor = level.getBlockState(candidate.below());
        BlockState feet = level.getBlockState(candidate);
        BlockState head = level.getBlockState(candidate.above());
        return floor.isSolidRender(level, candidate.below())
            && feet.getCollisionShape(level, candidate).isEmpty()
            && head.getCollisionShape(level, candidate.above()).isEmpty()
            && level.getFluidState(candidate).isEmpty()
            && level.getFluidState(candidate.above()).isEmpty();
    }

    private void skipGatherTarget(ActiveWork work, BlockPos target) {
        npc.getNavigation().stop();
        work.skippedGatherTargets.add(target.immutable());
        work.targetBlock = null;
        work.lastTeleportTarget = null;
        work.stalledTicks = 0;
        work.gatherPathFailures = 0;
        work.lastDistance = -1;
        work.lastGatherPathAttemptTick = -1;
        work.gatherStandPathCursor = 0;
        work.noWorkTicks = 0;
        boolean remoteRecoveryAllowed = gatherAllowsRemoteRecovery(work);
        if (!remoteRecoveryAllowed) {
            if (GatherRetryPolicy.afterSkipping(work.skippedGatherTargets.size()) == GatherRetryPolicy.Decision.FAIL_TASK) {
                fail(work, "走路采集模式下连续遇到过多不可达目标", "LOCAL_RESOURCE_NOT_REACHABLE");
                return;
            }
            npc.setStatus("走路采集模式：当前资源不可达，正在寻找附近其他目标");
            return;
        }
        GatherRetryPolicy.Decision decision = GatherRetryPolicy.afterSkipping(
            work.skippedGatherTargets.size(),
            hasCraftGatherPrerequisite(work),
            hasCraftGatherPrerequisite(work) && DeepMiningPolicy.supports(work.craftGatherItemId),
            remoteRecoveryAllowed,
            work.gatherExcursions,
            GATHER_MAX_EXCURSIONS
        );
        if (decision == GatherRetryPolicy.Decision.START_DEEP_MINING) {
            String itemId = work.craftGatherItemId;
            startDeepMining(work, itemId);
            progress(work, activeProgress(work),
                "附近目标连续不可达，改为开掘安全矿道寻找制作前置材料 " + itemId);
            return;
        }
        if (decision == GatherRetryPolicy.Decision.START_REMOTE_EXCURSION) {
            work.gatherExcursions++;
            work.destination = nextGatherSearchDestination(work.gatherExcursions);
            work.gatherTargets.clear();
            work.skippedGatherTargets.clear();
            work.gatherSearchRadius = 16;
            work.noWorkTicks = 0;
            work.lastSearchTick = -10;
            work.lastTeleportTarget = null;
            work.gatherTreeCluster = false;
            work.gatherClusterReached = false;
            progress(work, activeProgress(work),
                "本区制作材料均不可达，正在前往第 " + work.gatherExcursions + " 个搜索区继续寻找");
            return;
        }
        if (decision == GatherRetryPolicy.Decision.FAIL_TASK) {
            fail(work, "连续遇到过多不可达的采集目标", "RESOURCE_NOT_REACHABLE");
            return;
        }
        npc.setStatus("当前资源不可达，继续清空附近矿脉与本地目标后再考虑远征");
    }

    private boolean gatherAllowsRemoteRecovery(ActiveWork work) {
        return GatherRetryPolicy.allowsRemoteRecovery(string(work.spec, "movement", "auto"));
    }

    private boolean approach(ActiveWork work, ServerPlayer target, double reach, double speed) {
        double distance = npc.distanceTo(target);
        if (distance <= reach) {
            stopAerialFollow();
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }

        if (distance > config.npcRecallDistance && target.hasPermissions(2)) {
            NpcManager.recall(target, npc);
            npc.setStatus("距离过远，已传送回来交付物品");
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return false;
        }

        FollowMovementPolicy.Mode mode = FollowMovementPolicy.nextMode(
            followMode,
            target.isCreative(),
            target.getAbilities().flying,
            target.onGround(),
            npc.getY() - target.getY()
        );
        if (mode != FollowMovementPolicy.Mode.GROUND) {
            tickAerialFollow(target, mode);
            npc.setStatus("正在飞向 " + target.getGameProfile().getName() + " 交付物品");
            trackNavigation(work, distance);
            return false;
        }

        stopAerialFollow();
        navigateTowardPlayer(target, speed);
        npc.addExhaustion(0.002F);
        taskStatus(work, "正在接近 " + target.getGameProfile().getName() + " 交付物品，距离 " + Math.round(distance) + " 格");
        trackNavigation(work, distance);
        if (work.stalledTicks > 200) fail(work, "无法寻路到目标玩家", "PATH_NOT_FOUND");
        return false;
    }

    private Vec3 gatherEyePositionFromStand(BlockPos stand) {
        return new Vec3(stand.getX() + 0.5D, stand.getY() + npcEyeHeight(), stand.getZ() + 0.5D);
    }

    private boolean hasGatherLineOfSight(Vec3 eye, BlockPos target) {
        BlockHitResult hit = npc.level().clip(new ClipContext(
            eye,
            Vec3.atCenterOf(target),
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            npc
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    /** An entrance is an air stand, so an unobstructed MISS is valid too. */
    private boolean hasDeepMiningStandLineOfSight(Vec3 eye, BlockPos stand) {
        BlockHitResult hit = npc.level().clip(new ClipContext(
            eye,
            Vec3.atCenterOf(stand),
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            npc
        ));
        return hit.getType() == HitResult.Type.MISS
            || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(stand));
    }

    private boolean approachStorageTarget(ActiveWork work, BlockPos target, double reach, double speed) {
        double distance = npc.position().distanceTo(Vec3.atCenterOf(target));
        if (distance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(Vec3.atCenterOf(target));
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }

        maintainTaskChunkTicket(target);
        ServerPlayer owner = npc.owner();
        if (distance > config.npcRecallDistance && recoverStorageTargetWithCheatTeleport(work, target, reach, owner)) {
            return false;
        }

        if (distance > 32.0D) {
            navigateTowardBlock(target, speed);
        } else {
            Path path = npc.getNavigation().getPath();
            if (path == null || npc.getNavigation().isDone() || work.lastDistance < 0.0D) {
                Path candidate = npc.getNavigation().createPath(target, 2);
                if (pathGetsWithinWorkstationReach(candidate, target, reach)) {
                    npc.getNavigation().moveTo(candidate, speed);
                }
            }
        }
        npc.addExhaustion(0.002F);
        taskStatus(work, "正在接近家中容器，距离 " + Math.round(distance) + " 格");
        trackNavigation(work, distance);
        if (!HomeStoragePolicy.shouldRecoverStoragePath(work.stalledTicks)) return false;

        if (recoverStorageTargetWithCheatTeleport(work, target, reach, owner)) return false;
        skipStorageTarget(work, target, "当前家中容器不可达，正在寻找其他容器");
        return false;
    }

    private boolean recoverStorageTargetWithCheatTeleport(
        ActiveWork work,
        BlockPos target,
        double reach,
        ServerPlayer owner
    ) {
        boolean alreadyTeleported = target.equals(work.lastTeleportTarget);
        if (owner == null || !(npc.level() instanceof ServerLevel level)
            || !HomeStoragePolicy.mayUseCheatPathRecovery(owner.hasPermissions(2), alreadyTeleported)) return false;
        BlockPos destination = safeTaskPositionNear(level, target);
        if (Vec3.atCenterOf(destination).distanceTo(Vec3.atCenterOf(target)) > reach) return false;
        npc.getNavigation().stop();
        npc.teleportTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        work.lastTeleportTarget = target.immutable();
        work.stalledTicks = 0;
        work.lastDistance = -1;
        progress(work, activeProgress(work), "容器路径不可达，已使用作弊权限传送到安全交互位置");
        return true;
    }

    private void skipStorageTarget(ActiveWork work, BlockPos target, String status) {
        npc.getNavigation().stop();
        work.skippedStorageTargets.add(target.immutable());
        if (target.equals(work.workstation)) work.workstation = null;
        work.lastTeleportTarget = null;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        npc.setStatus(status);
    }

    private boolean approachGroundBuildTarget(ActiveWork work, BlockPos target, double reach, double speed) {
        double interactionDistance = gatherInteractionDistance(target);
        if (interactionDistance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(Vec3.atCenterOf(target));
            work.stalledTicks = 0;
            work.lastDistance = -1;
            work.buildPathFailures = 0;
            work.lastBuildPathAttemptTick = -1;
            work.buildStandPathCursor = 0;
            return true;
        }

        double distance = npc.position().distanceTo(Vec3.atCenterOf(target));
        if (teleportNearTaskTargetWhenAllowed(work, target, distance, "建筑目标距离过远，已传送到附近")) {
            return false;
        }
        maintainTaskChunkTicket(target);

        boolean navigationInProgress = !npc.getNavigation().isDone() && npc.getNavigation().getPath() != null;
        boolean stalledAlternative = BuildNavigationPolicy.shouldTryAlternativeStand(work.stalledTicks);
        boolean needsPath = !navigationInProgress || work.lastDistance < 0.0D || stalledAlternative;
        if (needsPath && BuildNavigationPolicy.shouldAttemptPath(work.ticks, work.lastBuildPathAttemptTick)) {
            work.lastBuildPathAttemptTick = work.ticks;
            if (stalledAlternative) {
                npc.getNavigation().stop();
                work.buildPathFailures++;
                work.stalledTicks = 0;
                work.lastDistance = -1;
            }
            Path path = findBuildStandPath(work, target, reach);
            if (path != null && npc.getNavigation().moveTo(path, speed)) {
                navigationInProgress = true;
            } else {
                work.buildPathFailures++;
                navigationInProgress = false;
            }
        }

        npc.addExhaustion(0.002F);
        taskStatus(work, "正在接近建造目标，距离 " + Math.round(distance) + " 格");
        double previousDistance = work.lastDistance;
        trackNavigation(work, distance);
        if (previousDistance >= 0.0D && distance < previousDistance - NavigationProgressPolicy.MIN_PROGRESS_PER_SAMPLE) {
            work.buildPathFailures = 0;
        }

        if (BuildNavigationPolicy.exhaustedAlternativeStands(work.buildPathFailures)) {
            if (recoverBuildTargetWithCheatTeleport(work, target, distance)) return false;
            fail(work, "无法走到建造目标附近的任何安全站位", "PATH_NOT_FOUND");
        }
        return false;
    }

    private Path findBuildStandPath(ActiveWork work, BlockPos target, double reach) {
        List<BlockPos> candidates = new ArrayList<>();
        int horizontalRadius = GatherNavigationPolicy.horizontalCandidateRadius(reach);
        for (int dy = GatherNavigationPolicy.MIN_VERTICAL_OFFSET; dy <= GatherNavigationPolicy.MAX_VERTICAL_OFFSET; dy++) {
            for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    if (!GatherNavigationPolicy.allowsStandOffset(dx, dy, dz)) continue;
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (gatherInteractionDistanceFromStand(candidate, target) > reach) continue;
                    if (!isSafeGatherStand(candidate)) continue;
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
            candidate -> npc.position().distanceToSqr(Vec3.atBottomCenterOf(candidate))
        ));
        int candidateCount = candidates.size();
        int attemptCount = BuildNavigationPolicy.candidateAttemptCount(candidateCount);
        int cursor = BuildNavigationPolicy.normalizedCandidateCursor(work.buildStandPathCursor, candidateCount);
        for (int attempt = 0; attempt < attemptCount; attempt++) {
            BlockPos candidate = candidates.get((cursor + attempt) % candidateCount);
            Path path = npc.getNavigation().createPath(candidate, 0);
            if (path != null && pathGetsWithinGatherReach(path, target, reach)) {
                work.buildStandPathCursor = BuildNavigationPolicy.nextCandidateCursor(
                    cursor,
                    attempt + 1,
                    candidateCount
                );
                return path;
            }
        }
        work.buildStandPathCursor = BuildNavigationPolicy.nextCandidateCursor(
            cursor,
            attemptCount,
            candidateCount
        );
        return null;
    }

    private boolean recoverBuildTargetWithCheatTeleport(ActiveWork work, BlockPos target, double distance) {
        ServerPlayer owner = npc.owner();
        boolean alreadyTeleported = target.equals(work.lastTeleportTarget);
        if (owner == null || !(npc.level() instanceof ServerLevel level)
            || !BuildNavigationPolicy.mayUseCheatRecovery(
                owner.hasPermissions(2),
                alreadyTeleported,
                distance
            )) return false;
        BlockPos destination = safeTaskPositionNear(level, target);
        double destinationDistance = Vec3.atCenterOf(destination).distanceTo(Vec3.atCenterOf(target));
        if (!GatherRetryPolicy.teleportDestinationIsUseful(destinationDistance)) return false;
        npc.getNavigation().stop();
        npc.teleportTo(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0;
        work.lastTeleportTarget = target.immutable();
        work.buildPathFailures = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.lastBuildPathAttemptTick = -1;
        progress(work, activeProgress(work), "多次安全站位寻路失败，已使用作弊权限传送到建造目标附近");
        return true;
    }

    /**
     * Long block-target trips are walked in loaded, surface-safe segments.
     * This keeps no-cheat home/storage and expedition returns moving without
     * turning the operation into a teleport or requiring the player nearby.
     */
    private boolean navigateTowardBlock(BlockPos target, double speed) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
        Vec3 offset = Vec3.atCenterOf(target).subtract(npc.position());
        double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        if (horizontal <= 32.0) {
            maintainTaskChunkTicket(target);
            return npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
        double step = Math.min(24.0, horizontal);
        int x = (int) Math.floor(npc.getX() + offset.x / horizontal * step);
        int z = (int) Math.floor(npc.getZ() + offset.z / horizontal * step);
        ChunkPos chunk = new ChunkPos(x >> 4, z >> 4);
        level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
        level.getChunk(chunk.x, chunk.z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return npc.getNavigation().moveTo(x + 0.5, y, z + 0.5, speed);
    }

    private void trackNavigation(ActiveWork work, double distance) {
        if (work.ticks % 20 != 0) return;
        NavigationProgressPolicy.Sample sample = NavigationProgressPolicy.sample(
            work.stalledTicks,
            work.lastDistance,
            distance
        );
        work.stalledTicks = sample.stalledTicks();
        work.lastDistance = sample.lastDistance();
    }

    private LivingEntity nearestHostile(LivingEntity center, double radius, String requestedType) {
        return center.level().getEntitiesOfClass(
            LivingEntity.class,
            center.getBoundingBox().inflate(radius),
            entity -> entity.isAlive() && entity != npc && matchesCombatTarget(entity, requestedType)
        ).stream().min(Comparator.comparingDouble(center::distanceToSqr)).orElse(null);
    }

    private boolean matchesCombatTarget(LivingEntity entity, String requestedType) {
        ServerPlayer owner = npc.owner();
        boolean allowed = CombatAssistPolicy.shouldAssist(
            entity.isAlive(),
            entity == owner,
            entity instanceof CodexNpcEntity,
            owner != null && (owner.isAlliedTo(entity) || entity.isAlliedTo(owner)),
            npc.isAlliedTo(entity) || entity.isAlliedTo(npc),
            entity instanceof Player,
            config.allowPvp
        );
        if (!allowed) return false;
        String type = id(entity);
        if (!requestedType.equals("hostile")) {
            return type.equalsIgnoreCase(requestedType) || entity.getName().getString().equalsIgnoreCase(requestedType);
        }
        return entity instanceof Monster
            || entity instanceof Player
            || config.hostileEntityAllowlist.stream().anyMatch(type::equalsIgnoreCase);
    }

    private void attack(LivingEntity target) {
        double distance = npc.distanceTo(target);
        npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        updateShield(target, distance);
        if (distance > 2.8) {
            npc.getNavigation().moveTo(target, 1.25);
            return;
        }
        npc.getNavigation().stop();
        if (attackCooldown > 0) return;
        lowerShield();
        npc.swing(InteractionHand.MAIN_HAND);
        npc.doHurtTarget(target);
        npc.addExhaustion(0.1F);
        attackCooldown = 12;
    }

    private void updateShield(LivingEntity target, double distance) {
        ItemStack offhand = npc.inventory().getStackInSlot(CodexNpcEntity.OFF_HAND_SLOT);
        boolean shield = offhand.canPerformAction(ToolActions.SHIELD_BLOCK);
        if (CombatDefensePolicy.shouldRaiseShield(shield, target.isAlive(), distance, attackCooldown)) {
            if (!npc.isUsingItem()) npc.startUsingItem(InteractionHand.OFF_HAND);
        } else {
            lowerShield();
        }
    }

    private void lowerShield() {
        if (npc.isUsingItem()
            && npc.getUsedItemHand() == InteractionHand.OFF_HAND
            && npc.getUseItem().canPerformAction(ToolActions.SHIELD_BLOCK)) {
            npc.stopUsingItem();
        }
    }

    private boolean deepMiningActiveFor(ActiveWork work, String itemId) {
        return work != null
            && !work.deepMiningPhase.isBlank()
            && work.deepMiningItemId.equals(itemId);
    }

    private void startDeepMining(ActiveWork work, String itemId) {
        if (deepMiningActiveFor(work, itemId)) return;
        clearDeepMining(work);
        work.deepMiningItemId = itemId;
        work.deepMiningTargetY = DeepMiningPolicy.targetY(itemId);
        Direction preferredDirection = Direction.fromYRot(npc.getYRot());
        work.deepMiningDirection = DeepMiningPolicy.retainedDirection(
            chooseDeepMiningDirection(npc.blockPosition(), preferredDirection),
            preferredDirection
        );
        work.deepMiningPhase = "preflight";
        work.deepMiningPhaseStartedTick = work.ticks;
        work.targetBlock = null;
        work.destination = null;
        work.gatherTargets.clear();
        work.skippedGatherTargets.clear();
        npc.getNavigation().stop();
        progress(work, activeProgress(work),
            "附近没有可见的 " + itemId + "，开始准备深层采矿补给");
    }

    private void clearDeepMining(ActiveWork work) {
        work.deepMiningPhase = "";
        work.deepMiningItemId = "";
        work.deepMiningTargetY = Integer.MAX_VALUE;
        work.deepMiningDirection = Direction.NORTH;
        work.deepMiningEntrance = null;
        work.deepMiningLanding = null;
        work.deepMiningLastSafeStand = null;
        work.deepMiningCaveTarget = null;
        work.deepMiningPhaseStartedTick = 0;
        work.deepMiningStaircaseStep = 0;
        work.deepMiningBranchIndex = 0;
        work.deepMiningBranchProgress = 0;
        work.deepMiningRegionIndex = 0;
        work.deepMiningLastTorchProgress = 0;
        work.deepMiningBrokenBlocks = 0;
        work.deepMiningPlacedTorches = 0;
        work.deepMiningBlockedTurns = 0;
        work.deepMiningMarkerStage = 0;
        work.deepMiningEntrySearchIndex = 0;
        work.deepMiningEntryTargetStartedTick = 0;
        work.deepMiningPreflightComplete = false;
        work.deepMiningExcavationTarget = false;
        work.deepMiningResourceTimedTarget = null;
        work.deepMiningResourceTargetStartedTick = 0;
        work.deepMiningResourceChaseStartedTick = 0;
    }

    private void tickDeepMining(
        ActiveWork work,
        String itemId,
        ResourceSelector selector,
        int requested,
        boolean prerequisite
    ) {
        if (!deepMiningActiveFor(work, itemId)) startDeepMining(work, itemId);
        int configuredTargetY = DeepMiningPolicy.targetY(itemId);
        if (configuredTargetY != Integer.MAX_VALUE) work.deepMiningTargetY = configuredTargetY;
        if (repairInconsistentDeepMiningState(work)) return;
        if (tickMiningInventoryCleanup(work, null, itemId)) return;
        if (tickDeepMiningFoodReserve(work)) return;
        if (!work.deepMiningPreflightComplete) {
            if (tickDeepMiningPreflight(work, itemId)) return;
            work.deepMiningPreflightComplete = true;
            work.deepMiningEntrance = npc.blockPosition().immutable();
            work.deepMiningLastSafeStand = work.deepMiningEntrance;
            Direction safeDirection = chooseDeepMiningDirection(
                work.deepMiningEntrance,
                Direction.fromYRot(npc.getYRot())
            );
            if (safeDirection == null) {
                work.deepMiningPhase = "waiting-entry";
                work.deepMiningPhaseStartedTick = work.ticks;
                work.deepMiningCaveTarget = null;
                npc.setStatus("等待可达的安全陆地入口");
                progress(work, activeProgress(work), "当前四个方向都不适合下矿，等待安全入口");
                return;
            }
            work.deepMiningDirection = safeDirection;
            work.deepMiningPhase = "seek-cave";
            work.deepMiningPhaseStartedTick = work.ticks;
            progress(work, activeProgress(work),
                "深层采矿补给已备齐：32 梯子、32 火把和双镐，正在寻找安全洞穴入口");
            return;
        }

        if (DeepMiningPolicy.needsHigherEntry(itemId, npc.blockPosition().getY())) {
            if (!"waiting-entry".equals(work.deepMiningPhase)) {
                npc.getNavigation().stop();
                work.targetBlock = null;
                work.destination = null;
                work.gatherTargets.clear();
                work.deepMiningCaveTarget = null;
                work.deepMiningEntrySearchIndex = 0;
                work.deepMiningEntryTargetStartedTick = 0;
                work.deepMiningPhase = "waiting-entry";
                work.deepMiningPhaseStartedTick = work.ticks - 20;
                work.stalledTicks = 0;
                work.lastDistance = -1.0D;
                progress(work, activeProgress(work),
                    "当前深度不适合采集 " + itemId + "，正在返回更高的安全矿层");
            }
            tickDeepMiningWaitingEntry(work);
            return;
        }

        String requiredPickaxe = DeepMiningPolicy.requiredPickaxe(itemId);
        int remainingPickaxeDurability = totalPickaxeRemainingDurability(requiredPickaxe);
        if (DeepMiningPolicy.suppliesNeedRefresh(
            npc.creativeResources(),
            remainingPickaxeDurability,
            inventoryCount("minecraft:torch"),
            DeepMiningPolicy.requiredTorches(itemId) > 0
        )) {
            npc.getNavigation().stop();
            work.targetBlock = null;
            if (tickDeepMiningPreflight(work, itemId)) return;
        }

        if (recoverBlockingTaskWorkstation(work, false, null)) return;

        if (restoreDisplacedDeepMiningCheckpoint(work)) return;

        // Finish a branch return before scanning for another visible vein.
        // Otherwise an exposed ore near the junction can repeatedly pull the
        // NPC away from the persisted checkpoint; displacement recovery then
        // teleports it back and the same target is selected again forever.
        if ("returning".equals(work.deepMiningPhase)) {
            work.targetBlock = null;
            work.deepMiningResourceTimedTarget = null;
            work.deepMiningResourceTargetStartedTick = 0;
            work.deepMiningResourceChaseStartedTick = 0;
            tickDeepMiningReturn(work);
            return;
        }

        if (tickDeepMiningVisibleResource(work, itemId, selector, requested, prerequisite)) return;

        switch (work.deepMiningPhase) {
            case "seek-cave" -> tickDeepMiningSeekCave(work);
            case "descending" -> tickDeepMiningDescending(work, selector);
            case "branching" -> tickDeepMiningBranch(work, selector);
            case "waiting-entry" -> tickDeepMiningWaitingEntry(work);
            default -> {
                work.deepMiningPhase = "preflight";
                work.deepMiningPreflightComplete = false;
            }
        }
    }

    private boolean tickMiningInventoryCleanup(ActiveWork work, Recipe<?> recipe, String outputItemId) {
        List<MiningInventoryCleanupPolicy.InventorySlot> inventory = new ArrayList<>();
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            inventory.add(new MiningInventoryCleanupPolicy.InventorySlot(
                slot,
                itemId(stack),
                stack.getCount()
            ));
        }
        List<String> taskItemIds = new ArrayList<>();
        taskItemIds.add(outputItemId);
        taskItemIds.add(work.outputItemId);
        taskItemIds.add(work.deepMiningItemId);
        taskItemIds.add(work.craftGatherItemId);
        if (!work.buildMaterialGoals.isEmpty()) {
            BuildMaterialGoal goal = work.buildMaterialGoals.peekFirst();
            taskItemIds.add(goal.itemId);
            taskItemIds.add(goal.selector);
            // A smelting goal may finish gathering eight furnace blocks one
            // tick before prepareSmeltingWorkstation consumes them. Keep both
            // vanilla furnace-stone variants protected across that boundary;
            // otherwise the near-full cleanup pass can discard the completed
            // prerequisite and create an endless gather-eight/drop-eight loop.
            if (BuildMaterialPrerequisitePolicy.plan(goal.itemId).action()
                    == BuildMaterialPrerequisitePolicy.Action.SMELT
                || findSmeltingRecipeByOutput(goal.itemId) != null) {
                taskItemIds.add("minecraft:cobblestone");
                taskItemIds.add("minecraft:cobbled_deepslate");
                taskItemIds.add("minecraft:furnace");
            }
        }
        String requiredPickaxe = DeepMiningPolicy.requiredPickaxe(work.deepMiningItemId);
        taskItemIds.add(requiredPickaxe);
        if (hasSuspendedDeepMining(work)) taskItemIds.add("#minecraft:logs");
        // Policy protection uses concrete item IDs. Expand active task tags to
        // the matching stacks that are actually in this backpack so a cleanup
        // tick cannot throw away a just-completed tagged prerequisite between
        // gather completion and its consumption by the next action.
        for (String taskSelector : new ArrayList<>(taskItemIds)) {
            if (taskSelector == null || !taskSelector.startsWith("#")) continue;
            ResourceSelector selector = ResourceSelector.parse(taskSelector);
            for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
                ItemStack stack = npc.inventory().getStackInSlot(slot);
                if (!stack.isEmpty() && selector.matches(stack)) taskItemIds.add(itemId(stack));
            }
        }
        Set<String> protectedItems = MiningInventoryCleanupPolicy.protectedItems(
            taskItemIds,
            recipe == null ? List.of() : recipeIngredientOptions(recipe)
        );
        List<MiningInventoryCleanupPolicy.Drop> planned = MiningInventoryCleanupPolicy.plan(
            CodexNpcEntity.BACKPACK_SIZE,
            inventory,
            protectedItems
        );
        if (planned.isEmpty()) return false;

        int discardedStacks = 0;
        int discardedItems = 0;
        for (MiningInventoryCleanupPolicy.Drop drop : planned) {
            ItemStack current = npc.inventory().getStackInSlot(drop.slot());
            if (current.isEmpty() || !itemId(current).equals(drop.itemId())
                || current.getCount() < drop.count()) continue;
            ItemStack discarded = npc.inventory().extractItem(drop.slot(), drop.count(), false);
            if (discarded.isEmpty()) continue;
            spawnThrownStack(discarded, null);
            recordInventoryAction(work, "drop");
            discardedStacks++;
            discardedItems += discarded.getCount();
        }
        if (discardedStacks <= 0) return false;
        npc.swing(InteractionHand.MAIN_HAND);
        progress(work, activeProgress(work),
            "背包接近满载，已丢弃 " + discardedStacks + " 组、共 " + discardedItems
                + " 个多余低级工具或石料，保留当前任务材料并继续原任务");
        return true;
    }

    private boolean tickDeepMiningPreflight(ActiveWork work, String itemId) {
        if (npc.creativeResources()) return false;
        int requiredLadders = DeepMiningPolicy.requiredLadders(itemId, npc.blockPosition().getY());
        if (inventoryCount("minecraft:ladder") < requiredLadders) {
            beginBuildMaterialGoal(
                work,
                "minecraft:ladder",
                "minecraft:ladder",
                requiredLadders,
                "下矿前先准备 " + requiredLadders + " 个梯子"
            );
            return true;
        }
        int requiredTorches = DeepMiningPolicy.requiredTorches(itemId);
        if (inventoryCount("minecraft:torch") < requiredTorches) {
            beginBuildMaterialGoal(
                work,
                "minecraft:torch",
                "minecraft:torch",
                requiredTorches,
                "下矿前先准备 " + requiredTorches + " 个火把"
            );
            return true;
        }
        String pickaxeId = DeepMiningPolicy.requiredPickaxe(itemId);
        int usable = usablePickaxeCount(pickaxeId, DeepMiningPolicy.MIN_PICKAXE_REMAINING_DURABILITY);
        if (!pickaxeId.isBlank() && usable < DeepMiningPolicy.REQUIRED_IRON_PICKAXES) {
            if (tickMiningInventoryCleanup(work, findCraftRecipe(pickaxeId), pickaxeId)) return true;
            int target = inventoryCount(pickaxeId) + DeepMiningPolicy.REQUIRED_IRON_PICKAXES - usable;
            beginBuildMaterialGoal(
                work,
                pickaxeId,
                pickaxeId,
                target,
                "下矿前先准备主用和备用镐：" + pickaxeId
            );
            return true;
        }
        int logCount = inventoryCount("#minecraft:logs");
        if (DeepMiningPolicy.logReserveNeedsRefresh(npc.creativeResources(), logCount)) {
            beginBuildMaterialGoal(
                work,
                "minecraft:oak_log",
                "#minecraft:logs",
                DeepMiningPolicy.REQUIRED_LOG_RESERVE,
                "下矿前先补满一组备用原木"
            );
            return true;
        }
        return false;
    }

    private boolean tickDeepMiningFoodReserve(ActiveWork work) {
        int reserve = safeProvisioningFoodCount();
        if (!DeepMiningPolicy.foodReserveNeedsRefresh(
            npc.creativeResources(),
            work.deepMiningPreflightComplete,
            reserve
        )) return false;

        progress(work, activeProgress(work),
            "深挖口粮不足（" + reserve + "/" + DeepMiningPolicy.REQUIRED_FOOD_RESERVE
                + "），先暂停原任务补足安全口粮");
        beginDeepMiningFoodProvision(work);
        return true;
    }

    private void beginDeepMiningFoodProvision(ActiveWork miningWork) {
        if (active != miningWork) return;
        pauseActive("深挖前补充安全口粮");

        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "provision-food");
        spec.addProperty("source", "auto");
        spec.addProperty("destination", "backpack");
        spec.addProperty("count", foodReserveTarget());
        spec.addProperty("requestedBy", "deep-mining-survival");
        spec.addProperty("priority", Math.min(1000, miningWork.priority + 1));
        ActiveWork food = new ActiveWork(
            "local:deep-mining-food:" + miningWork.id,
            spec,
            new JsonObject(),
            Stance.WORK
        );
        active = food;
        npc.setStance(Stance.WORK);
        npc.setStatus("正在为深挖任务补充安全口粮");
    }

    private int safeProvisioningFoodCount() {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (isSafeProvisioningFood(stack)) count += stack.getCount();
        }
        return count;
    }

    private boolean restoreDisplacedDeepMiningCheckpoint(ActiveWork work) {
        if (!List.of("descending", "branching", "returning").contains(work.deepMiningPhase)) return false;
        // Visible veins deliberately lead away from the staircase or branch
        // checkpoint. Let their bounded 15-second chase finish or skip the
        // target before treating that movement as external displacement.
        if (DeepMiningPolicy.resourceChaseOwnsMovement(
            work.targetBlock != null,
            work.deepMiningResourceChaseStartedTick
        )) return false;
        // Returning deliberately moves away from the most recent checkpoint.
        // Never mistake valid progress through the already-opened branch for an
        // external displacement, even when a mineshaft floor makes the entity
        // walk one block above the logical tunnel layer.
        if ("returning".equals(work.deepMiningPhase)
            && work.deepMiningLanding != null
            && work.deepMiningDirection != null) {
            BlockPos origin = DeepMiningPolicy.branchOrigin(
                work.deepMiningLanding,
                work.deepMiningDirection,
                work.deepMiningBranchIndex,
                work.deepMiningRegionIndex
            );
            BlockPos branchEnd = DeepMiningPolicy.branchStand(
                work.deepMiningLanding,
                work.deepMiningDirection,
                work.deepMiningBranchIndex,
                work.deepMiningRegionIndex,
                DeepMiningPolicy.BRANCH_LENGTH
            );
            if (DeepMiningPolicy.isOnReturnCorridor(origin, branchEnd, npc.blockPosition())) return false;
        }
        BlockPos checkpoint = safeDeepMiningCheckpoint(work);
        if (checkpoint == null) return false;
        ServerPlayer owner = npc.owner();
        boolean ownerCanCheat = owner != null && owner.hasPermissions(2);
        double distance = npc.position().distanceTo(Vec3.atBottomCenterOf(checkpoint));
        int verticalDelta = npc.blockPosition().getY() - checkpoint.getY();
        if (!DeepMiningPolicy.shouldRestoreCheckpoint(ownerCanCheat, distance, verticalDelta)) return false;
        reconcileDeepMiningCheckpoint(work, checkpoint);
        restoreDeepMiningCheckpoint(work, checkpoint);
        progress(work, activeProgress(work),
            "检测到深挖任务位置偏离，已使用玩家开启的作弊权限返回最后安全矿道检查点");
        return true;
    }

    /**
     * A recovery may fall back from a blocked underground stand to the surface
     * entrance. The physical fallback and the logical staircase/branch state
     * must move together; otherwise a completed 129-step descent is reused as
     * a horizontal surface tunnel.
     */
    private boolean repairInconsistentDeepMiningState(ActiveWork work) {
        if ("descending".equals(work.deepMiningPhase)) {
            int retained = DeepMiningPolicy.staircaseProgress(
                work.deepMiningEntrance,
                work.deepMiningDirection,
                work.deepMiningStaircaseStep,
                work.deepMiningLastSafeStand
            );
            if (retained >= 0) {
                if (retained != work.deepMiningStaircaseStep) {
                    work.deepMiningStaircaseStep = retained;
                    work.deepMiningLastTorchProgress = Math.min(
                        work.deepMiningLastTorchProgress,
                        retained
                    );
                }
                return false;
            }
            restartDeepMiningFromCurrentStand(work,
                "深挖台阶检查点与实际位置不一致，已从当前位置重新寻找向下入口");
            return true;
        }

        if (!"branching".equals(work.deepMiningPhase)
            && !"returning".equals(work.deepMiningPhase)) return false;
        if (DeepMiningPolicy.isConsistentBranchCheckpoint(
            work.deepMiningTargetY,
            work.deepMiningLanding,
            work.deepMiningLastSafeStand
        )) return false;

        BlockPos landing = work.deepMiningLanding;
        ServerPlayer owner = npc.owner();
        if (DeepMiningPolicy.isValidMiningLayer(work.deepMiningTargetY, landing)
            && isSafeGatherStand(landing)
            && owner != null
            && owner.hasPermissions(2)) {
            resetDeepMiningBranchesAt(work, landing);
            restoreDeepMiningCheckpoint(work, landing);
            progress(work, activeProgress(work),
                "检测到地表位置误用了深层分支进度，已返回目标矿层并重置当前分支");
            return true;
        }

        restartDeepMiningFromCurrentStand(work,
            "检测到地表位置误用了深层分支进度，已停止横向开掘并重新建立下降路线");
        return true;
    }

    private void reconcileDeepMiningCheckpoint(ActiveWork work, BlockPos checkpoint) {
        if ("descending".equals(work.deepMiningPhase)) {
            int retained = DeepMiningPolicy.staircaseProgress(
                work.deepMiningEntrance,
                work.deepMiningDirection,
                work.deepMiningStaircaseStep,
                checkpoint
            );
            if (retained >= 0) {
                work.deepMiningStaircaseStep = retained;
                work.deepMiningLastTorchProgress = Math.min(
                    work.deepMiningLastTorchProgress,
                    retained
                );
                return;
            }
            restartDeepMiningAt(work, checkpoint);
            return;
        }
        if (("branching".equals(work.deepMiningPhase) || "returning".equals(work.deepMiningPhase))
            && !DeepMiningPolicy.isConsistentBranchCheckpoint(
                work.deepMiningTargetY,
                work.deepMiningLanding,
                checkpoint
            )) {
            restartDeepMiningAt(work, checkpoint);
            return;
        }
        reconcileDeepMiningBranchCheckpoint(work, checkpoint);
    }

    private void restartDeepMiningFromCurrentStand(ActiveWork work, String message) {
        BlockPos current = npc.blockPosition().immutable();
        if (!isSafeGatherStand(current)) {
            current = work.deepMiningEntrance != null && isSafeGatherStand(work.deepMiningEntrance)
                ? work.deepMiningEntrance.immutable()
                : current;
        }
        restartDeepMiningAt(work, current);
        progress(work, activeProgress(work), message);
    }

    private void restartDeepMiningAt(ActiveWork work, BlockPos entrance) {
        npc.getNavigation().stop();
        work.deepMiningEntrance = entrance.immutable();
        work.deepMiningLastSafeStand = entrance.immutable();
        work.deepMiningLanding = null;
        work.deepMiningCaveTarget = null;
        work.deepMiningStaircaseStep = 0;
        work.deepMiningBranchIndex = 0;
        work.deepMiningBranchProgress = 0;
        work.deepMiningRegionIndex = 0;
        work.deepMiningLastTorchProgress = 0;
        work.deepMiningBlockedTurns = 0;
        work.deepMiningEntrySearchIndex = 0;
        work.deepMiningEntryTargetStartedTick = 0;
        work.deepMiningResourceTimedTarget = null;
        work.deepMiningResourceTargetStartedTick = 0;
        work.deepMiningResourceChaseStartedTick = 0;
        work.targetBlock = null;
        work.destination = null;
        work.gatherTargets.clear();
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        work.failedActions = 0;
        Direction direction = chooseDeepMiningDirection(entrance, work.deepMiningDirection);
        if (direction == null) {
            work.deepMiningPhase = "waiting-entry";
            work.deepMiningPhaseStartedTick = work.ticks - 20;
        } else {
            work.deepMiningDirection = direction;
            work.deepMiningPhase = "descending";
            work.deepMiningPhaseStartedTick = work.ticks;
        }
    }

    private void resetDeepMiningBranchesAt(ActiveWork work, BlockPos landing) {
        work.deepMiningLanding = landing.immutable();
        work.deepMiningLastSafeStand = landing.immutable();
        work.deepMiningBranchIndex = 0;
        work.deepMiningBranchProgress = 0;
        work.deepMiningRegionIndex = 0;
        work.deepMiningLastTorchProgress = 0;
        work.deepMiningPhase = "branching";
        work.deepMiningPhaseStartedTick = work.ticks;
        work.targetBlock = null;
        work.destination = null;
        work.gatherTargets.clear();
        work.deepMiningResourceTimedTarget = null;
        work.deepMiningResourceTargetStartedTick = 0;
        work.deepMiningResourceChaseStartedTick = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        work.failedActions = 0;
    }

    private void reconcileDeepMiningBranchCheckpoint(ActiveWork work, BlockPos checkpoint) {
        if (!"branching".equals(work.deepMiningPhase) || work.deepMiningBranchProgress <= 0) return;
        int retainedProgress = DeepMiningPolicy.retainedBranchProgress(
            work.deepMiningLanding,
            work.deepMiningDirection,
            work.deepMiningBranchIndex,
            work.deepMiningRegionIndex,
            work.deepMiningBranchProgress,
            checkpoint
        );
        if (retainedProgress == work.deepMiningBranchProgress) return;

        work.deepMiningBranchProgress = retainedProgress;
        work.deepMiningLastTorchProgress = 0;
        work.targetBlock = null;
        work.destination = null;
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        work.failedActions = 0;
        progress(work, activeProgress(work),
            "深挖安全点已回退到主矿道，已同步分支进度并从安全点继续开掘");
    }

    private BlockPos safeDeepMiningCheckpoint(ActiveWork work) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        for (BlockPos candidate : new BlockPos[] {
            work.deepMiningLastSafeStand,
            work.deepMiningLanding,
            work.deepMiningEntrance
        }) {
            if (candidate == null) continue;
            if (!isDeepMiningCheckpointCompatible(work, candidate)) continue;
            ChunkPos chunk = new ChunkPos(candidate);
            level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
            level.getChunk(chunk.x, chunk.z);
            if (isSafeGatherStand(candidate)) return candidate.immutable();
        }
        return null;
    }

    private boolean isDeepMiningCheckpointCompatible(ActiveWork work, BlockPos candidate) {
        if ("descending".equals(work.deepMiningPhase)) {
            return DeepMiningPolicy.staircaseProgress(
                work.deepMiningEntrance,
                work.deepMiningDirection,
                work.deepMiningStaircaseStep,
                candidate
            ) >= 0;
        }
        if ("branching".equals(work.deepMiningPhase) || "returning".equals(work.deepMiningPhase)) {
            return DeepMiningPolicy.isConsistentBranchCheckpoint(
                work.deepMiningTargetY,
                work.deepMiningLanding,
                candidate
            );
        }
        return true;
    }

    private void restoreDeepMiningCheckpoint(ActiveWork work, BlockPos checkpoint) {
        maintainTaskChunkTicket(checkpoint);
        npc.getNavigation().stop();
        npc.teleportTo(checkpoint.getX() + 0.5D, checkpoint.getY(), checkpoint.getZ() + 0.5D);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        work.deepMiningLastSafeStand = checkpoint.immutable();
        work.targetBlock = null;
        work.destination = null;
        work.lastTeleportTarget = checkpoint.immutable();
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        work.failedActions = 0;
    }

    private int usablePickaxeCount(String itemId, int minimumRemainingDurability) {
        if (itemId == null || itemId.isBlank()) return 0;
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!itemId(stack).equals(itemId)) continue;
            int remaining = stack.isDamageableItem()
                ? stack.getMaxDamage() - stack.getDamageValue()
                : Integer.MAX_VALUE;
            int required = stack.isDamageableItem()
                ? DeepMiningPolicy.minimumUsablePickaxeDurability(
                    stack.getMaxDamage(),
                    minimumRemainingDurability
                )
                : minimumRemainingDurability;
            if (remaining >= required) count += stack.getCount();
        }
        return count;
    }

    private int totalPickaxeRemainingDurability(String itemId) {
        if (itemId == null || itemId.isBlank()) return Integer.MAX_VALUE;
        long remaining = 0L;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!itemId(stack).equals(itemId)) continue;
            int perItem = stack.isDamageableItem()
                ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue())
                : Integer.MAX_VALUE;
            remaining += (long) perItem * Math.max(1, stack.getCount());
            if (remaining >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) remaining;
    }

    private void tickDeepMiningSeekCave(ActiveWork work) {
        if (chooseDeepMiningDirection(work.deepMiningEntrance, work.deepMiningDirection) == null) {
            work.deepMiningPhase = "waiting-entry";
            work.deepMiningPhaseStartedTick = work.ticks;
            work.deepMiningCaveTarget = null;
            npc.getNavigation().stop();
            npc.setStatus("等待可达的安全陆地入口");
            return;
        }
        if (work.deepMiningCaveTarget == null) {
            work.deepMiningCaveTarget = findBlockAt(position ->
                position.getY() <= npc.blockPosition().getY() - 4
                    && isSafeGatherStand(position)
                    && isSafeDeepMiningDirection(position)
                    && hasDeepMiningStandLineOfSight(npc.getEyePosition(), position),
                12,
                16
            );
            if (work.deepMiningCaveTarget == null) {
                work.deepMiningCaveTarget = findBlockAt(position ->
                    isSafeGatherStand(position)
                        && isSafeDeepMiningDirection(position)
                        && hasDeepMiningStandLineOfSight(npc.getEyePosition(), position),
                    32,
                    6
                );
            }
        }
        if (work.deepMiningCaveTarget != null) {
            double distance = npc.position().distanceTo(Vec3.atBottomCenterOf(work.deepMiningCaveTarget));
            if (distance <= 1.25D) {
                work.deepMiningEntrance = work.deepMiningCaveTarget.immutable();
                work.deepMiningLastSafeStand = work.deepMiningEntrance;
                beginDeepMiningDescent(work, "已进入附近的安全洞穴，继续向目标层开阶梯");
                return;
            }
            npc.getNavigation().moveTo(
                work.deepMiningCaveTarget.getX() + 0.5D,
                work.deepMiningCaveTarget.getY(),
                work.deepMiningCaveTarget.getZ() + 0.5D,
                1.05D
            );
            if (work.ticks - work.deepMiningPhaseStartedTick <= 20 * 10) {
                if (work.ticks % 40 == 0) npc.setStatus("正在走向可见的安全洞穴入口");
                return;
            }
        }
        work.deepMiningCaveTarget = null;
        npc.getNavigation().stop();
        BlockPos directEntry = npc.blockPosition().immutable();
        Direction directDirection = chooseDeepMiningDirection(directEntry, work.deepMiningDirection);
        if (DeepMiningPolicy.canStartDirectDescent(
            isSafeGatherStand(directEntry),
            directDirection != null,
            DeepMiningPolicy.needsHigherEntry(work.deepMiningItemId, directEntry.getY())
        )) {
            work.deepMiningEntrance = directEntry;
            work.deepMiningLastSafeStand = directEntry;
            work.deepMiningDirection = directDirection;
            beginDeepMiningDescent(work, "附近没有天然洞穴，已从当前安全位置直接开挖阶梯");
            return;
        }
        work.deepMiningPhase = "waiting-entry";
        work.deepMiningPhaseStartedTick = work.ticks - 20;
        npc.setStatus("当前区域不能安全向下开挖，正在寻找其他陆地入口");
    }

    private void tickDeepMiningWaitingEntry(ActiveWork work) {
        if (work.ticks - work.deepMiningPhaseStartedTick < 20) return;
        work.deepMiningPhaseStartedTick = work.ticks;
        boolean needsHigherEntry = DeepMiningPolicy.needsHigherEntry(
            work.deepMiningItemId,
            npc.blockPosition().getY()
        );

        // A water edge, shoreline, or uneven generated surface can make all
        // four stair candidates around the current block unsafe.  Keep the
        // same task alive and walk to a nearby dry stand that has at least one
        // valid staircase direction instead of waiting forever at the lip.
        if (work.deepMiningCaveTarget == null
            || !isSafeGatherStand(work.deepMiningCaveTarget)
            || chooseDeepMiningDirection(work.deepMiningCaveTarget, work.deepMiningDirection) == null
            || needsHigherEntry && DeepMiningPolicy.needsHigherEntry(
                work.deepMiningItemId,
                work.deepMiningCaveTarget.getY()
            )) {
            BlockPos previousTarget = work.deepMiningCaveTarget;
            work.deepMiningCaveTarget = null;
            if (needsHigherEntry && work.deepMiningEntrySearchIndex == 0) {
                work.deepMiningCaveTarget = findSurfaceDeepMiningEntryNear(
                    npc.blockPosition(),
                    work.deepMiningDirection,
                    48,
                    null
                );
                work.deepMiningEntrySearchIndex = 1;
            } else if (!needsHigherEntry && work.deepMiningEntrySearchIndex == 0) {
                work.deepMiningCaveTarget = findNearbyDeepMiningEntry(
                    npc.blockPosition(),
                    work.deepMiningDirection,
                    16,
                    6
                );
                if (work.deepMiningCaveTarget == null) {
                    work.deepMiningCaveTarget = findNearbyDeepMiningEntry(
                        npc.blockPosition(),
                        work.deepMiningDirection,
                        32,
                        10
                    );
                }
                if (work.deepMiningCaveTarget == null) {
                    work.deepMiningCaveTarget = findSurfaceDeepMiningEntryNear(
                        npc.blockPosition(),
                        work.deepMiningDirection,
                        96,
                        null
                    );
                }
                // Index zero means that the bounded local scan has not run.
                // Mark it consumed even when it produced a candidate, so an
                // unreachable shoreline stand cannot be selected forever.
                work.deepMiningEntrySearchIndex = 1;
            }
            if (work.deepMiningCaveTarget == null) {
                work.deepMiningCaveTarget = findDeepMiningExpeditionEntry(work);
            }
            if (work.deepMiningCaveTarget != null
                && !work.deepMiningCaveTarget.equals(previousTarget)) {
                work.stalledTicks = 0;
                work.lastDistance = -1.0D;
                work.deepMiningEntryTargetStartedTick = work.ticks;
            }
        }

        if (work.deepMiningCaveTarget != null) {
            double distance = npc.position().distanceTo(
                Vec3.atBottomCenterOf(work.deepMiningCaveTarget)
            );
            trackNavigation(work, distance);
            ServerPlayer owner = npc.owner();
            boolean ownerCanCheat = owner != null && owner.hasPermissions(2);
            int targetAgeTicks = Math.max(0, work.ticks - work.deepMiningEntryTargetStartedTick);
            boolean targetTimedOut = DeepMiningPolicy.entryTargetTimedOut(distance, targetAgeTicks);
            if (DeepMiningPolicy.shouldTeleportToEntry(
                ownerCanCheat,
                distance,
                work.stalledTicks
            ) || (ownerCanCheat && targetTimedOut)) {
                maintainTaskChunkTicket(work.deepMiningCaveTarget);
                npc.getNavigation().stop();
                npc.teleportTo(
                    work.deepMiningCaveTarget.getX() + 0.5D,
                    work.deepMiningCaveTarget.getY(),
                    work.deepMiningCaveTarget.getZ() + 0.5D
                );
                npc.setDeltaMovement(Vec3.ZERO);
                npc.fallDistance = 0.0F;
                work.lastTeleportTarget = work.deepMiningCaveTarget.immutable();
                work.stalledTicks = 0;
                work.lastDistance = -1.0D;
                work.deepMiningEntryTargetStartedTick = 0;
                progress(work, activeProgress(work),
                    "已使用玩家开启的作弊权限传送到远处安全陆地下矿入口");
                if (acceptDeepMiningEntry(work, "已到达远处安全陆地入口，继续深层采矿")) return;
            }
            if (!ownerCanCheat && targetTimedOut) {
                BlockPos unreachable = work.deepMiningCaveTarget;
                npc.getNavigation().stop();
                work.deepMiningCaveTarget = null;
                work.deepMiningEntryTargetStartedTick = 0;
                work.stalledTicks = 0;
                work.lastDistance = -1.0D;
                progress(work, activeProgress(work),
                    "入口步行不可达，已跳过并继续扩大远征搜索范围");
                maintainTaskChunkTicket(unreachable);
                return;
            }
            if (distance > 2.0D) {
                navigateTowardBlock(work.deepMiningCaveTarget, 1.05D);
                if (work.ticks % 40 == 0) {
                    npc.setStatus("正在前往附近安全的深挖入口");
                    progress(work, activeProgress(work), "当前入口不安全，正在步行寻找附近安全陆地入口");
                }
                return;
            }
            if (acceptDeepMiningEntry(work, "已到达附近安全陆地入口，继续寻找深挖路径")) return;
            work.deepMiningCaveTarget = null;
            work.deepMiningEntryTargetStartedTick = 0;
            work.stalledTicks = 0;
            work.lastDistance = -1.0D;
        }

        if (!acceptDeepMiningEntry(work, "检测到安全入口，继续深层采矿")) {
            if (work.ticks % 100 == 0) npc.setStatus("等待可达的安全陆地入口");
            return;
        }
    }

    private boolean acceptDeepMiningEntry(ActiveWork work, String message) {
        BlockPos actual = npc.blockPosition();
        Direction actualDirection = chooseDeepMiningDirection(actual, work.deepMiningDirection);
        if (DeepMiningPolicy.canStartDirectDescent(
            isSafeGatherStand(actual),
            actualDirection != null,
            DeepMiningPolicy.needsHigherEntry(work.deepMiningItemId, actual.getY())
        )) {
            npc.getNavigation().stop();
            work.deepMiningEntrance = actual.immutable();
            work.deepMiningLastSafeStand = actual.immutable();
            work.deepMiningDirection = actualDirection;
            work.deepMiningCaveTarget = null;
            work.deepMiningEntryTargetStartedTick = 0;
            work.deepMiningBlockedTurns = 0;
            work.deepMiningPhase = "seek-cave";
            progress(work, activeProgress(work), message);
            return true;
        }

        // Navigation can stop one block above the requested stand on a shore
        // or a shallow ledge.  Only accept the requested candidate when the
        // entity has actually entered that stand; never lock a stale y value
        // into the staircase checkpoint.
        BlockPos candidate = work.deepMiningCaveTarget;
        if (candidate == null || actual.distManhattan(candidate) > 1) return false;
        Direction candidateDirection = chooseDeepMiningDirection(candidate, work.deepMiningDirection);
        if (!isSafeGatherStand(candidate)
            || candidateDirection == null
            || DeepMiningPolicy.needsHigherEntry(work.deepMiningItemId, candidate.getY())) return false;
        npc.getNavigation().moveTo(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D, 1.0D);
        return false;
    }

    private BlockPos findNearbyDeepMiningEntry(
        BlockPos origin,
        Direction preferred,
        int radius,
        int verticalRadius
    ) {
        return findBlockAt(position -> DeepMiningPolicy.isUsableNearbyEntry(
                position.equals(origin),
                isSafeGatherStand(position),
                chooseDeepMiningDirection(position, preferred) != null
            ),
            radius,
            verticalRadius
        );
    }

    private BlockPos findDeepMiningExpeditionEntry(ActiveWork work) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        // Index one is the first expedition probe because zero is reserved for
        // the one-time local scan performed by tickDeepMiningWaitingEntry.
        int attempt = Math.max(0, work.deepMiningEntrySearchIndex - 1);
        work.deepMiningEntrySearchIndex = Math.max(2, work.deepMiningEntrySearchIndex + 1);
        BlockPos searchCenter;
        NpcHomeStorage.Home home = null;
        ServerPlayer owner = npc.owner();
        if (owner != null) home = NpcHomeStorage.resolve(owner);
        if (home != null && home.dimension().equals(level.dimension()) && attempt < 4) {
            Direction[] directions = {
                Direction.NORTH,
                Direction.EAST,
                Direction.SOUTH,
                Direction.WEST,
            };
            searchCenter = home.position().relative(directions[attempt], 32);
        } else {
            int expeditionAttempt = home != null && home.dimension().equals(level.dimension())
                ? Math.max(0, attempt - 4)
                : attempt;
            searchCenter = DeepMiningPolicy.entryExpeditionProbe(
                npc.blockPosition(),
                expeditionAttempt
            );
        }

        ChunkPos chunk = new ChunkPos(searchCenter);
        level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
        level.getChunk(chunk.x, chunk.z);
        BlockPos candidate = findSurfaceDeepMiningEntryNear(
            searchCenter,
            work.deepMiningDirection,
            DeepMiningPolicy.ENTRY_PROBE_RADIUS,
            home
        );
        if (candidate == null && attempt % 4 == 3) {
            progress(work, activeProgress(work),
                "附近没有安全陆地入口，正在扩大远征搜索范围（已检查 " + (attempt + 1) + " 个区域）");
        }
        return candidate;
    }

    private BlockPos findSurfaceDeepMiningEntryNear(
        BlockPos center,
        Direction preferred,
        int radius,
        NpcHomeStorage.Home protectedHome
    ) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        int boundedRadius = Math.max(1, radius);
        for (int ring = 0; ring <= boundedRadius; ring++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    BlockPos chunkProbe = new BlockPos(x, center.getY(), z);
                    if (!level.hasChunkAt(chunkProbe) || !level.getWorldBorder().isWithinBounds(chunkProbe)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (protectedHome != null
                        && protectedHome.dimension().equals(level.dimension())
                        && candidate.distSqr(protectedHome.position())
                            < DeepMiningPolicy.ENTRY_HOME_CLEARANCE * DeepMiningPolicy.ENTRY_HOME_CLEARANCE) continue;
                    if (!isSafeGatherStand(candidate)
                        || chooseDeepMiningDirection(candidate, preferred) == null) continue;
                    double distance = candidate.distSqr(center);
                    if (distance >= bestDistance) continue;
                    best = candidate.immutable();
                    bestDistance = distance;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private void beginDeepMiningDescent(ActiveWork work, String message) {
        npc.getNavigation().stop();
        work.deepMiningPhase = "descending";
        work.deepMiningPhaseStartedTick = work.ticks;
        work.deepMiningStaircaseStep = 0;
        work.deepMiningLastTorchProgress = 0;
        work.targetBlock = null;
        progress(work, activeProgress(work), message + "，目标 Y=" + work.deepMiningTargetY);
    }

    private void tickDeepMiningDescending(ActiveWork work, ResourceSelector selector) {
        BlockPos current = work.deepMiningLastSafeStand == null
            ? npc.blockPosition()
            : work.deepMiningLastSafeStand;
        if (current.getY() <= work.deepMiningTargetY) {
            work.deepMiningLanding = current.immutable();
            work.deepMiningPhase = "branching";
            work.deepMiningPhaseStartedTick = work.ticks;
            work.deepMiningBranchIndex = 0;
            work.deepMiningBranchProgress = 0;
            work.deepMiningLastTorchProgress = 0;
            progress(work, activeProgress(work),
                "已到目标层 Y=" + current.getY() + "，开始 32 格分支挖矿");
            return;
        }
        int nextStep = work.deepMiningStaircaseStep + 1;
        BlockPos desired = DeepMiningPolicy.staircaseStand(
            work.deepMiningEntrance,
            work.deepMiningDirection,
            nextStep
        );
        if (desired.getY() < work.deepMiningTargetY) {
            work.deepMiningLanding = current.immutable();
            work.deepMiningPhase = "branching";
            return;
        }
        if (tickDeepMiningCorridor(work, desired, selector)) {
            work.deepMiningStaircaseStep = nextStep;
            work.deepMiningLastSafeStand = desired.immutable();
            placeDeepMiningTorch(work, previousStairStand(work), work.deepMiningStaircaseStep);
            tickDeepMiningEntranceMarker(work);
        }
    }

    private void tickDeepMiningBranch(ActiveWork work, ResourceSelector selector) {
        if (work.deepMiningLanding == null) {
            work.deepMiningLanding = npc.blockPosition().immutable();
        }
        if (DeepMiningPolicy.regionComplete(work.deepMiningBranchIndex)) {
            work.deepMiningRegionIndex++;
            work.deepMiningBranchIndex = 0;
            work.deepMiningBranchProgress = 0;
            work.deepMiningLastTorchProgress = 0;
            progress(work, activeProgress(work),
                "当前矿区资源不足，沿主矿道前往第 " + (work.deepMiningRegionIndex + 1) + " 个矿区");
        }

        BlockPos branchOrigin = DeepMiningPolicy.branchOrigin(
            work.deepMiningLanding,
            work.deepMiningDirection,
            work.deepMiningBranchIndex,
            work.deepMiningRegionIndex
        );
        BlockPos current = work.deepMiningLastSafeStand == null
            ? npc.blockPosition()
            : work.deepMiningLastSafeStand;
        if (DeepMiningPolicy.branchComplete(work.deepMiningBranchProgress)) {
            work.deepMiningPhase = "returning";
            work.deepMiningPhaseStartedTick = work.ticks;
            npc.getNavigation().moveTo(
                branchOrigin.getX() + 0.5D,
                branchOrigin.getY(),
                branchOrigin.getZ() + 0.5D,
                1.05D
            );
            return;
        }
        if (DeepMiningPolicy.shouldApproachBranchOrigin(
            work.deepMiningBranchProgress,
            current.equals(branchOrigin)
        )) {
            BlockPos nextSpine = nextHorizontalStep(current, branchOrigin);
            if (tickDeepMiningCorridor(work, nextSpine, selector)) {
                work.deepMiningLastSafeStand = nextSpine.immutable();
                int spineProgress = work.deepMiningRegionIndex * DeepMiningPolicy.REGION_SPACING
                    + (work.deepMiningBranchIndex / 2) * DeepMiningPolicy.BRANCH_SPACING;
                placeDeepMiningTorch(work, current, spineProgress);
            }
            return;
        }

        int nextProgress = work.deepMiningBranchProgress + 1;
        BlockPos desired = DeepMiningPolicy.branchStand(
            work.deepMiningLanding,
            work.deepMiningDirection,
            work.deepMiningBranchIndex,
            work.deepMiningRegionIndex,
            nextProgress
        );
        if (tickDeepMiningCorridor(work, desired, selector)) {
            BlockPos previous = work.deepMiningLastSafeStand;
            work.deepMiningLastSafeStand = desired.immutable();
            work.deepMiningBranchProgress = nextProgress;
            placeDeepMiningTorch(work, previous, nextProgress);
        }
    }

    private void tickDeepMiningReturn(ActiveWork work) {
        BlockPos origin = DeepMiningPolicy.branchOrigin(
            work.deepMiningLanding,
            work.deepMiningDirection,
            work.deepMiningBranchIndex,
            work.deepMiningRegionIndex
        );
        BlockPos branchEnd = DeepMiningPolicy.branchStand(
            work.deepMiningLanding,
            work.deepMiningDirection,
            work.deepMiningBranchIndex,
            work.deepMiningRegionIndex,
            DeepMiningPolicy.BRANCH_LENGTH
        );
        boolean checkpointOnReturnCorridor = DeepMiningPolicy.isOnReturnCorridor(
            origin,
            branchEnd,
            work.deepMiningLastSafeStand
        );
        if (DeepMiningPolicy.shouldRelocateRegionAfterSpineReroute(
            work.deepMiningBranchProgress,
            checkpointOnReturnCorridor
        )) {
            relocateDeepMiningRegionToCheckpoint(work,
                "通往下一矿区的主矿道遇到障碍，已从最后安全位置建立新矿区继续采集");
            return;
        }
        BlockPos current = npc.blockPosition();
        if (isSafeGatherStand(current) && DeepMiningPolicy.isImprovedReturnCheckpoint(
            origin,
            branchEnd,
            current,
            work.deepMiningLastSafeStand
        )) {
            work.deepMiningLastSafeStand = DeepMiningPolicy.canonicalReturnCheckpoint(origin, current).immutable();
        }
        Vec3 originPoint = Vec3.atBottomCenterOf(origin);
        double distance = npc.position().distanceTo(originPoint);
        // Navigation commonly stops one collision-width short of the centre of
        // a one-block branch junction.  The tunnel is already excavated, so use
        // the same collision-checked short physical step as descent movement to
        // cross the final block boundary instead of waiting forever at ~1.27 m.
        if (!DeepMiningPolicy.reachedStand(npc.position(), origin)
            && distance <= 3.2D
            && isSafeGatherStand(origin)) {
            Vec3 step = DeepMiningPolicy.closeRangeStep(npc.position(), originPoint);
            if (step.lengthSqr() > 0.000001D) {
                npc.move(MoverType.SELF, step);
                current = npc.blockPosition();
                distance = npc.position().distanceTo(originPoint);
                if (isSafeGatherStand(current) && DeepMiningPolicy.isImprovedReturnCheckpoint(
                    origin,
                    branchEnd,
                    current,
                    work.deepMiningLastSafeStand
                )) {
                    work.deepMiningLastSafeStand = DeepMiningPolicy.canonicalReturnCheckpoint(origin, current).immutable();
                }
            }
        }
        if (DeepMiningPolicy.reachedReturnOrigin(npc.position(), origin)
            || DeepMiningPolicy.reachedStand(npc.position(), origin)
            || distance <= 0.75D) {
            npc.getNavigation().stop();
            work.deepMiningLastSafeStand = origin.immutable();
            work.deepMiningBranchIndex++;
            work.deepMiningBranchProgress = 0;
            work.deepMiningLastTorchProgress = 0;
            work.deepMiningPhase = "branching";
            work.deepMiningPhaseStartedTick = work.ticks;
            progress(work, activeProgress(work),
                "已返回主矿道，开始第 " + (work.deepMiningBranchIndex + 1) + " 条分支");
            return;
        }
        npc.getNavigation().moveTo(originPoint.x, originPoint.y, originPoint.z, 1.05D);
        if (work.ticks % 40 == 0) npc.setStatus("正在沿已开掘矿道返回主矿道");
    }

    private BlockPos nextHorizontalStep(BlockPos from, BlockPos to) {
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = dx == 0 ? Integer.compare(to.getZ(), from.getZ()) : 0;
        return from.offset(dx, 0, dz);
    }

    /** Returns true only after the NPC has physically reached the excavated stand. */
    private boolean tickDeepMiningCorridor(ActiveWork work, BlockPos desiredStand, ResourceSelector selector) {
        if (recoverBlockingTaskWorkstation(work, false, desiredStand)) return false;
        maintainTaskChunkTicket(desiredStand);
        BlockPos floor = desiredStand.below();
        if (!hasSolidSafeMiningFloor(floor)) {
            BlockState floorState = npc.level().getBlockState(floor);
            if (mayBreakDeepMiningAccess(floor)) {
                if (work.ticks - work.lastActionTick < 8) return false;
                int floorToolSlot = bestToolSlot(floorState);
                if (floorToolSlot >= 0) equipMainHand(floorToolSlot);
                work.lastActionTick = work.ticks;
                npc.swing(InteractionHand.MAIN_HAND);
                if (proxy.breakBlock(floor, floorToolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1)) {
                    recordInventoryAction(work, "deep-mining-clear-floor");
                    work.deepMiningBrokenBlocks++;
                    npc.absorbNearbyItemsAt(Vec3.atCenterOf(floor), 2.5D);
                }
                return false;
            }
            if (!placeDeepMiningFloor(work, floor)) {
                rerouteDeepMining(work, "前方地板 " + id(npc.level().getBlockState(floor).getBlock())
                    + " 无法形成安全支撑，已改道");
            }
            return false;
        }

        for (BlockPos excavation : DeepMiningPolicy.corridorExcavations(
            work.deepMiningLastSafeStand,
            desiredStand
        )) {
            BlockState state = npc.level().getBlockState(excavation);
            if (!npc.level().getFluidState(excavation).isEmpty()) {
                rerouteDeepMining(work, "前方 " + id(state.getBlock())
                    + " 含有液体，已从最后安全位置改道");
                return false;
            }
            if (state.getCollisionShape(npc.level(), excavation).isEmpty()) continue;
            if (matchesGatherBlock(excavation, selector, bestToolStack())) {
                work.targetBlock = excavation.immutable();
                work.deepMiningExcavationTarget = false;
                return false;
            }
            if (!mayBreakDeepMiningAccess(excavation)) {
                rerouteDeepMining(work, "矿道方块 " + id(state.getBlock())
                    + " 不可安全破坏，已改道");
                return false;
            }
            if (work.ticks - work.lastActionTick < 8) return false;
            int toolSlot = bestToolSlot(state);
            if (state.requiresCorrectToolForDrops() && (toolSlot < 0
                || !npc.inventory().getStackInSlot(toolSlot).isCorrectToolForDrops(state))) {
                prepareGatherTool(work, state, work.deepMiningItemId);
                return false;
            }
            if (toolSlot >= 0) equipMainHand(toolSlot);
            work.lastActionTick = work.ticks;
            npc.swing(InteractionHand.MAIN_HAND);
            if (!proxy.breakBlock(excavation, toolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1)) {
                if (++work.failedActions >= 3) {
                    rerouteDeepMining(work, "矿道方块无法破坏，已改道");
                }
                return false;
            }
            npc.absorbNearbyItemsAt(Vec3.atCenterOf(excavation), 3.0D);
            recordInventoryAction(work, "deep-mining-access");
            work.failedActions = 0;
            work.deepMiningBrokenBlocks++;
            npc.addExhaustion(0.08F);
            progress(work, activeProgress(work),
                "正在开掘安全矿道，当前 Y=" + npc.blockPosition().getY()
                    + "，已破坏 " + work.deepMiningBrokenBlocks + " 个方块");
            return false;
        }

        double distance = npc.position().distanceTo(Vec3.atBottomCenterOf(desiredStand));
        if (DeepMiningPolicy.reachedStand(npc.position(), desiredStand)
            && isSafeGatherStand(desiredStand)) {
            npc.getNavigation().stop();
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        // Vanilla mob navigation can refuse a newly opened one-block descent
        // until a path node is rescanned.  When the corridor is already clear
        // and the target is close, take a short collision-checked physical step
        // so the NPC enters the stair instead of rotating forever at its lip.
        if (distance <= 3.2D) {
            Vec3 step = DeepMiningPolicy.closeRangeStep(
                npc.position(),
                Vec3.atBottomCenterOf(desiredStand)
            );
            if (step.lengthSqr() > 0.000001D) {
                npc.move(MoverType.SELF, step);
                if (DeepMiningPolicy.reachedStand(npc.position(), desiredStand)
                    && isSafeGatherStand(desiredStand)) {
                    npc.getNavigation().stop();
                    work.stalledTicks = 0;
                    work.lastDistance = -1;
                    return true;
                }
            }
        }
        npc.getNavigation().moveTo(
            desiredStand.getX() + 0.5D,
            desiredStand.getY(),
            desiredStand.getZ() + 0.5D,
            1.0D
        );
        if (distance + 0.05D < work.lastDistance || work.lastDistance < 0.0D) {
            work.lastDistance = distance;
            work.stalledTicks = 0;
        } else {
            work.stalledTicks++;
        }
        if (work.stalledTicks > 20 * 2
            && recoverBlockingTaskWorkstation(work, false, desiredStand)) return false;
        if (work.stalledTicks > 20 * 8) rerouteDeepMining(work, "矿道移动受阻，已从最后安全位置改道");
        return false;
    }

    private boolean tickDeepMiningVisibleResource(
        ActiveWork work,
        String itemId,
        ResourceSelector selector,
        int requested,
        boolean prerequisite
    ) {
        if (!isCurrentDeepMiningResourceValid(work, selector)) {
            work.targetBlock = pollGatherTarget(work, selector);
            if (work.targetBlock == null) {
                BlockPos exposed = findExposedGatherBlock(selector, 8, 5, work.skippedGatherTargets);
                if (exposed != null) {
                    enqueueConnectedResources(work, exposed, selector);
                    work.targetBlock = pollGatherTarget(work, selector);
                }
            }
            if (work.targetBlock == null) {
                work.deepMiningResourceTimedTarget = null;
                work.deepMiningResourceTargetStartedTick = 0;
            } else if (!work.targetBlock.equals(work.deepMiningResourceTimedTarget)) {
                work.deepMiningResourceTimedTarget = work.targetBlock.immutable();
                work.deepMiningResourceTargetStartedTick = work.ticks;
            }
        }
        if (work.targetBlock == null) {
            work.deepMiningResourceChaseStartedTick = 0;
            return false;
        }
        if (work.deepMiningResourceChaseStartedTick <= 0) {
            work.deepMiningResourceChaseStartedTick = work.ticks;
        }
        if (work.deepMiningResourceTimedTarget == null
            || !work.targetBlock.equals(work.deepMiningResourceTimedTarget)) {
            work.deepMiningResourceTimedTarget = work.targetBlock.immutable();
            work.deepMiningResourceTargetStartedTick = work.ticks;
        }
        if (DeepMiningPolicy.resourceTargetTimedOut(
            work.ticks - work.deepMiningResourceChaseStartedTick
        )) {
            BlockPos unreachable = work.targetBlock.immutable();
            work.skippedGatherTargets.add(unreachable);
            work.skippedGatherTargets.addAll(work.gatherTargets);
            work.gatherTargets.clear();
            work.targetBlock = null;
            work.deepMiningResourceTimedTarget = null;
            work.deepMiningResourceTargetStartedTick = 0;
            work.deepMiningResourceChaseStartedTick = 0;
            npc.getNavigation().stop();
            work.stalledTicks = 0;
            work.gatherPathFailures = 0;
            work.lastDistance = -1.0D;
            work.lastGatherPathAttemptTick = -1;
            work.gatherStandPathCursor = 0;
            progress(work, activeProgress(work), "矿脉目标 " + unreachable.toShortString()
                + " 在 15 秒内没有可达交互站位，已记住并继续当前矿道");
            return true;
        }
        if (!approachGatherTarget(work, work.targetBlock, 2.8D, 1.1D)) return true;
        BlockPos brokenTarget = work.targetBlock.immutable();
        BlockState state = npc.level().getBlockState(brokenTarget);
        if (!matchesGatherBlock(brokenTarget, selector, bestToolStack())) {
            work.targetBlock = null;
            work.deepMiningResourceTimedTarget = null;
            work.deepMiningResourceTargetStartedTick = 0;
            return true;
        }
        if (!npc.creativeResources() && state.requiresCorrectToolForDrops() && !hasUsableToolFor(state)) {
            prepareGatherTool(work, state, itemId);
            return true;
        }
        if (work.ticks - work.lastActionTick < 8) return true;
        int before = inventoryCount(itemId);
        int toolSlot = bestGatherToolSlot(state, selector);
        if (toolSlot >= 0) equipMainHand(toolSlot);
        work.lastActionTick = work.ticks;
        npc.swing(InteractionHand.MAIN_HAND);
        if (!proxy.breakBlock(brokenTarget, toolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1)) {
            if (++work.failedActions >= 3) {
                work.skippedGatherTargets.add(brokenTarget);
                work.targetBlock = null;
                work.deepMiningResourceTimedTarget = null;
                work.deepMiningResourceTargetStartedTick = 0;
                work.failedActions = 0;
            }
            return true;
        }
        npc.absorbNearbyItemsAt(Vec3.atCenterOf(brokenTarget), 3.0D);
        recordInventoryAction(work, "deep-mining-pickup");
        refreshConnectedResourcesAfterBreak(work, brokenTarget, selector, false);
        work.deepMiningBrokenBlocks++;
        work.targetBlock = null;
        work.deepMiningResourceTimedTarget = null;
        work.deepMiningResourceTargetStartedTick = 0;
        work.deepMiningResourceChaseStartedTick = 0;
        work.failedActions = 0;
        npc.addExhaustion(0.08F);
        if (prerequisite) {
            int acquired = GatherProgressPolicy.afterBreak(work.craftGatherCompleted, before, inventoryCount(itemId));
            work.craftGatherCompleted = GatherProgressPolicy.retained(
                acquired,
                work.craftGatherInitialCount,
                inventoryCount(itemId)
            );
            progress(work, activeProgress(work),
                "深层采矿已获得 " + work.craftGatherCompleted + "/" + requested + " 个 " + itemId);
        } else {
            int acquired = GatherProgressPolicy.afterBreak(work.completed, before, inventoryCount(itemId));
            work.completed = GatherProgressPolicy.retained(acquired, work.initialCount, inventoryCount(itemId));
            progress(work, Math.min(0.99D, work.completed / (double) requested),
                "深层采矿已获得 " + work.completed + "/" + requested + " 个 " + itemId);
        }
        return true;
    }

    private boolean isCurrentDeepMiningResourceValid(ActiveWork work, ResourceSelector selector) {
        return work.targetBlock != null
            && !work.deepMiningExcavationTarget
            && matchesGatherBlock(work.targetBlock, selector, bestToolStack())
            && !isProtectedHomeGatherResource(work.targetBlock)
            && hasSafeGatherStand(work.targetBlock, GATHER_INTERACTION_REACH);
    }

    private BlockPos findExposedGatherBlock(
        ResourceSelector selector,
        int radius,
        int verticalRadius,
        Set<BlockPos> skippedTargets
    ) {
        return findBlockAt(position -> npc.level().hasChunkAt(position)
            && !skippedTargets.contains(position)
            && isSafeGatherSeed(position, selector)
            && hasExposedMiningFace(position)
            && hasSafeGatherStand(position, GATHER_INTERACTION_REACH), radius, verticalRadius);
    }

    private boolean hasExposedMiningFace(BlockPos position) {
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = position.relative(direction);
            BlockState state = npc.level().getBlockState(adjacent);
            if (state.getCollisionShape(npc.level(), adjacent).isEmpty()
                && npc.level().getFluidState(adjacent).isEmpty()) return true;
        }
        return false;
    }

    private boolean hasSolidSafeMiningFloor(BlockPos floor) {
        BlockState state = npc.level().getBlockState(floor);
        return state.isSolidRender(npc.level(), floor)
            && npc.level().getFluidState(floor).isEmpty()
            && state.getDestroySpeed(npc.level(), floor) >= 0.0F;
    }

    private boolean placeDeepMiningFloor(ActiveWork work, BlockPos target) {
        if (!npc.level().getFluidState(target).isEmpty()) return false;
        BlockState current = npc.level().getBlockState(target);
        if (!current.canBeReplaced()) return false;
        int slot = findDeepMiningFloorMaterialSlot();
        if (slot < 0) return false;
        Direction supportDirection = findSupport(target);
        if (supportDirection == null) return false;
        BlockPos support = target.relative(supportDirection);
        ItemStack material = npc.inventory().getStackInSlot(slot);
        work.lastActionTick = work.ticks;
        npc.swing(InteractionHand.MAIN_HAND);
        proxy.useItemOn(support, supportDirection.getOpposite(), material, slot);
        if (!hasSolidSafeMiningFloor(target)) return false;
        recordInventoryAction(work, "deep-mining-floor");
        return true;
    }

    private int findDeepMiningFloorMaterialSlot() {
        for (String selector : DeepMiningPolicy.floorMaterialSelectors()) {
            int slot = findItemSlot(selector);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    private boolean mayBreakDeepMiningAccess(BlockPos position) {
        BlockState state = npc.level().getBlockState(position);
        return DeepMiningPolicy.mayBreakCorridorObstacle(
            state.isAir(),
            !npc.level().getFluidState(position).isEmpty(),
            state.getDestroySpeed(npc.level(), position),
            npc.level().getBlockEntity(position) != null,
            isProtectedHomeGatherResource(position)
        );
    }

    private void rerouteDeepMining(ActiveWork work, String message) {
        npc.getNavigation().stop();
        work.targetBlock = null;
        work.stalledTicks = 0;
        work.lastDistance = -1;
        work.deepMiningBlockedTurns++;
        if (work.deepMiningPhase.equals("descending")) {
            work.deepMiningEntrance = work.deepMiningLastSafeStand == null
                ? npc.blockPosition().immutable()
                : work.deepMiningLastSafeStand.immutable();
            Direction reroute = chooseDeepMiningDirection(
                work.deepMiningEntrance,
                work.deepMiningBlockedTurns % 2 == 0
                    ? work.deepMiningDirection.getClockWise()
                    : work.deepMiningDirection.getCounterClockWise()
            );
            if (reroute == null || work.deepMiningBlockedTurns >= 4) {
                work.deepMiningPhase = "waiting-entry";
                work.deepMiningPhaseStartedTick = work.ticks;
                npc.setStatus("等待可达的安全陆地入口");
                progress(work, activeProgress(work), "四个方向均不可安全下矿，任务等待安全入口");
                return;
            }
            work.deepMiningDirection = reroute;
            work.deepMiningStaircaseStep = 0;
        } else if (work.deepMiningPhase.equals("branching")) {
            BlockPos origin = DeepMiningPolicy.branchOrigin(
                work.deepMiningLanding,
                work.deepMiningDirection,
                work.deepMiningBranchIndex,
                work.deepMiningRegionIndex
            );
            BlockPos branchEnd = DeepMiningPolicy.branchStand(
                work.deepMiningLanding,
                work.deepMiningDirection,
                work.deepMiningBranchIndex,
                work.deepMiningRegionIndex,
                DeepMiningPolicy.BRANCH_LENGTH
            );
            boolean checkpointOnReturnCorridor = DeepMiningPolicy.isOnReturnCorridor(
                origin,
                branchEnd,
                work.deepMiningLastSafeStand
            );
            if (DeepMiningPolicy.shouldRelocateRegionAfterSpineReroute(
                work.deepMiningBranchProgress,
                checkpointOnReturnCorridor
            )) {
                relocateDeepMiningRegionToCheckpoint(work, message);
                return;
            }
            work.deepMiningPhase = "returning";
        }
        progress(work, activeProgress(work), message);
    }

    private void relocateDeepMiningRegionToCheckpoint(ActiveWork work, String message) {
        BlockPos checkpoint = work.deepMiningLastSafeStand == null
            ? npc.blockPosition().immutable()
            : work.deepMiningLastSafeStand.immutable();
        npc.getNavigation().stop();
        work.deepMiningLanding = checkpoint;
        work.deepMiningRegionIndex = 0;
        work.deepMiningBranchIndex = 0;
        work.deepMiningBranchProgress = 0;
        work.deepMiningLastTorchProgress = 0;
        work.deepMiningPhase = "branching";
        work.deepMiningPhaseStartedTick = work.ticks;
        work.deepMiningBlockedTurns = 0;
        work.targetBlock = null;
        work.destination = null;
        work.deepMiningResourceTimedTarget = null;
        work.deepMiningResourceTargetStartedTick = 0;
        work.deepMiningResourceChaseStartedTick = 0;
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        work.failedActions = 0;
        progress(work, activeProgress(work), message);
    }

    private Direction chooseDeepMiningDirection(BlockPos origin, Direction preferred) {
        Direction first = preferred != null && preferred.getAxis().isHorizontal() ? preferred : Direction.NORTH;
        Direction[] candidates = {
            first,
            first.getClockWise(),
            first.getCounterClockWise(),
            first.getOpposite(),
        };
        for (Direction direction : candidates) {
            BlockPos stand = DeepMiningPolicy.staircaseStand(origin, direction, 1);
            if (isSafeDeepMiningDirection(stand)) return direction;
        }
        return null;
    }

    private boolean isSafeDeepMiningDirection(BlockPos stand) {
        BlockState floor = npc.level().getBlockState(stand.below());
        if (!npc.level().getFluidState(stand.below()).isEmpty()) return false;
        if (!hasSolidSafeMiningFloor(stand.below()) && !floor.canBeReplaced()) return false;
        for (BlockPos position : List.of(stand.above(), stand)) {
            BlockState state = npc.level().getBlockState(position);
            if (!npc.level().getFluidState(position).isEmpty()) return false;
            if (state.getCollisionShape(npc.level(), position).isEmpty()) continue;
            if (!mayBreakDeepMiningAccess(position)) return false;
        }
        return true;
    }

    private BlockPos previousStairStand(ActiveWork work) {
        return DeepMiningPolicy.staircaseStand(
            work.deepMiningEntrance,
            work.deepMiningDirection,
            Math.max(0, work.deepMiningStaircaseStep - 1)
        );
    }

    private void placeDeepMiningTorch(ActiveWork work, BlockPos position, int progressValue) {
        if (position == null
            || !DeepMiningPolicy.shouldPlaceTorch(progressValue, work.deepMiningLastTorchProgress)) return;
        BlockState target = npc.level().getBlockState(position);
        if (target.is(Blocks.TORCH) || target.is(Blocks.WALL_TORCH)) {
            work.deepMiningLastTorchProgress = progressValue;
            return;
        }
        int slot = findItemSlot("minecraft:torch");
        if (slot < 0) return;
        if (!target.canBeReplaced() || !hasSolidSafeMiningFloor(position.below())) return;
        ItemStack torch = npc.inventory().getStackInSlot(slot);
        npc.swing(InteractionHand.MAIN_HAND);
        proxy.useItemOn(position.below(), Direction.UP, torch, slot);
        BlockState placed = npc.level().getBlockState(position);
        if (placed.is(Blocks.TORCH) || placed.is(Blocks.WALL_TORCH)) {
            work.deepMiningLastTorchProgress = progressValue;
            work.deepMiningPlacedTorches++;
            recordInventoryAction(work, "deep-mining-torch");
        }
    }

    private void tickDeepMiningEntranceMarker(ActiveWork work) {
        if (work.deepMiningMarkerStage >= 3 || work.deepMiningStaircaseStep < 2) return;
        Direction side = work.deepMiningDirection.getClockWise();
        BlockPos base = work.deepMiningEntrance.relative(side, 2);
        BlockPos target = switch (work.deepMiningMarkerStage) {
            case 0 -> base;
            case 1 -> base.above();
            default -> base.above(2);
        };
        String materialId = work.deepMiningMarkerStage < 2 ? "#forge:cobblestone" : "minecraft:torch";
        int slot = findItemSlot(materialId);
        if (slot < 0 || !npc.level().getBlockState(target).canBeReplaced()) return;
        Direction supportDirection = findSupport(target);
        if (supportDirection == null) return;
        ItemStack material = npc.inventory().getStackInSlot(slot);
        npc.swing(InteractionHand.MAIN_HAND);
        proxy.useItemOn(target.relative(supportDirection), supportDirection.getOpposite(), material, slot);
        if (!npc.level().getBlockState(target).canBeReplaced()) {
            work.deepMiningMarkerStage++;
            recordInventoryAction(work, "deep-mining-marker");
        }
    }

    private BlockPos findGatherBlock(
        ResourceSelector selector,
        int radius,
        int verticalRadius,
        Set<BlockPos> skippedTargets
    ) {
        Predicate<BlockPos> candidate = position -> npc.level().hasChunkAt(position)
            && !skippedTargets.contains(position)
            && isSafeGatherSeed(position, selector)
            && hasSafeGatherStand(position, GATHER_INTERACTION_REACH);
        int preferredVerticalRadius = GatherSearchPolicy.preferredVerticalRadius(verticalRadius);
        BlockPos nearLevel = findBlockAt(candidate, radius, preferredVerticalRadius);
        if (nearLevel != null || preferredVerticalRadius == verticalRadius) return nearLevel;
        return findBlockAt(candidate, radius, verticalRadius);
    }

    private boolean isCurrentGatherTargetValid(ActiveWork work, ResourceSelector selector) {
        if (work.targetBlock == null) return false;
        if (work.gatherAccessTarget) {
            return mayBreakMiningAccess(work.targetBlock)
                && hasSafeGatherStand(work.targetBlock, GATHER_INTERACTION_REACH)
                && leadsToGatherResource(work.targetBlock, selector);
        }
        return matchesGatherBlock(work.targetBlock, selector, bestToolStack())
            && !isProtectedHomeGatherResource(work.targetBlock);
    }

    private BlockPos findMiningAccessBlock(ActiveWork work, ResourceSelector selector) {
        if (!MiningAccessPolicy.supportsSelector(selector.parsed)) return null;
        BlockPos origin = npc.blockPosition();
        for (int ring = 0; ring <= 8; ring++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int y = -4; y <= 4; y++) {
                for (int x = -ring; x <= ring; x++) {
                    for (int z = -ring; z <= ring; z++) {
                        if (ring > 0 && Math.abs(x) != ring && Math.abs(z) != ring) continue;
                        BlockPos stand = origin.offset(x, y, z);
                        if (!isSafeGatherStand(stand)) continue;
                        for (BlockPos target : miningAccessCandidates(stand)) {
                            if (work.skippedGatherTargets.contains(target)) continue;
                            if (!isReachableFromStand(stand, target)) continue;
                            if (!hasSafeGatherStand(target, GATHER_INTERACTION_REACH)) continue;
                            if (isSafeGatherSeed(target, selector)) {
                                double distance = stand.distSqr(origin);
                                if (distance < bestDistance) {
                                    best = target.immutable();
                                    bestDistance = distance;
                                }
                                continue;
                            }
                            if (!mayBreakMiningAccess(target) || !leadsToGatherResource(target, selector)) continue;
                            double distance = stand.distSqr(origin);
                            if (distance < bestDistance) {
                                best = target.immutable();
                                bestDistance = distance;
                            }
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private List<BlockPos> miningAccessCandidates(BlockPos stand) {
        List<BlockPos> result = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            result.add(stand.relative(direction));
            result.add(stand.relative(direction).above());
            result.add(stand.relative(direction).below());
        }
        return result;
    }

    private boolean isReachableFromStand(BlockPos stand, BlockPos target) {
        return gatherInteractionDistanceFromStand(stand, target) <= GATHER_INTERACTION_REACH;
    }

    private double gatherInteractionDistance(BlockPos target) {
        Vec3 eye = npc.getEyePosition();
        return GatherNavigationPolicy.blockTouchDistance(
            eye.x,
            eye.y,
            eye.z,
            target.getX(),
            target.getY(),
            target.getZ()
        );
    }

    private double gatherInteractionDistanceFromStand(BlockPos stand, BlockPos target) {
        Vec3 eye = Vec3.atBottomCenterOf(stand).add(0, npcEyeHeight(), 0);
        return GatherNavigationPolicy.blockTouchDistance(
            eye.x,
            eye.y,
            eye.z,
            target.getX(),
            target.getY(),
            target.getZ()
        );
    }

    private double npcEyeHeight() {
        return Math.max(0.0, npc.getEyePosition().y - npc.getY());
    }

    private boolean mayBreakMiningAccess(BlockPos position) {
        BlockState state = npc.level().getBlockState(position);
        return MiningAccessPolicy.mayBreakAsAccess(
            id(state.getBlock()),
            state.isAir(),
            !npc.level().getFluidState(position).isEmpty(),
            state.getDestroySpeed(npc.level(), position)
        );
    }

    private boolean leadsToGatherResource(BlockPos access, ResourceSelector selector) {
        if (isSafeGatherSeed(access, selector)) return true;
        for (int depth = 1; depth <= 24; depth++) {
            BlockPos below = access.below(depth);
            if (!npc.level().hasChunkAt(below)) break;
            if (!npc.level().getFluidState(below).isEmpty()) return false;
            if (isSafeGatherSeed(below, selector)) return true;
            if (!mayBreakMiningAccess(below)) return false;
        }
        return false;
    }

    private BlockPos pollGatherTarget(ActiveWork work, ResourceSelector selector) {
        while (!work.gatherTargets.isEmpty()) {
            BlockPos candidate = work.gatherTargets.pollFirst();
            if (work.skippedGatherTargets.contains(candidate)) continue;
            if (GatherRetryPolicy.queuedTargetMayBeAttempted(
                matchesGatherBlock(candidate, selector, ItemStack.EMPTY),
                isProtectedHomeGatherResource(candidate),
                hasSafeGatherStand(candidate, GATHER_INTERACTION_REACH),
                work.gatherTreeCluster && work.gatherClusterReached
            )) return candidate;
        }
        return null;
    }

    private void enqueueConnectedResources(ActiveWork work, BlockPos seed, ResourceSelector selector) {
        BlockState seedState = npc.level().getBlockState(seed);
        Set<BlockPos> alreadyQueued = new HashSet<>(work.gatherTargets);
        if (seedState.is(BlockTags.LOGS) && npc.level() instanceof ServerLevel level) {
            NaturalTreeScanner.Cluster cluster = NaturalTreeScanner.inspect(
                level, seed, GATHER_MAX_CONNECTED_TARGETS
            );
            work.gatherTreeCluster = cluster.natural();
            work.gatherClusterReached = false;
            if (!cluster.natural()) return;
            for (BlockPos position : NaturalTreeScanner.orderedTargets(cluster, seed)) {
                if (work.gatherTargets.size() >= GATHER_MAX_CONNECTED_TARGETS) break;
                if (work.skippedGatherTargets.contains(position)) continue;
                if (matchesGatherBlock(position, selector, ItemStack.EMPTY)
                    && !isProtectedHomeGatherResource(position)
                    && alreadyQueued.add(position)) {
                    work.gatherTargets.addLast(position.immutable());
                }
            }
            return;
        }
        Deque<BlockPos> frontier = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        frontier.add(seed.immutable());
        work.gatherTreeCluster = false;
        work.gatherClusterReached = false;
        int queuedBefore = work.gatherTargets.size();
        while (!frontier.isEmpty()
            && work.gatherTargets.size() - queuedBefore < GATHER_MAX_CONNECTED_TARGETS) {
            BlockPos position = frontier.removeFirst();
            if (!visited.add(position) || work.skippedGatherTargets.contains(position)) continue;
            if (!matchesGatherBlock(position, selector, ItemStack.EMPTY)) continue;
            if (alreadyQueued.add(position)) work.gatherTargets.addLast(position.immutable());
            // Ores generated as one vein may touch at an edge or corner. Treat
            // the full 3x3x3 neighborhood as one local cluster so the NPC does
            // not abandon diagonal blocks and start a distant expedition.
            for (GatherClusterPolicy.Offset offset : GatherClusterPolicy.connectedNeighbors()) {
                frontier.addLast(position.offset(offset.x(), offset.y(), offset.z()));
            }
        }
    }

    private void refreshConnectedResourcesAfterBreak(
        ActiveWork work,
        BlockPos brokenTarget,
        ResourceSelector selector,
        boolean naturalTreeCluster
    ) {
        if (naturalTreeCluster) return;
        // Breaking an exposed block can make previously occluded vein blocks
        // reachable. Forget only nearby path skips and rebuild the connected
        // queue before any remote-recovery decision is considered.
        work.skippedGatherTargets.removeIf(position ->
            GatherClusterPolicy.reconsiderSkippedAfterBreak(position.distSqr(brokenTarget))
        );
        for (GatherClusterPolicy.Offset offset : GatherClusterPolicy.connectedNeighbors()) {
            BlockPos neighbor = brokenTarget.offset(offset.x(), offset.y(), offset.z());
            if (matchesGatherBlock(neighbor, selector, ItemStack.EMPTY)) {
                enqueueConnectedResources(work, neighbor, selector);
            }
        }
    }

    private boolean isSafeGatherSeed(BlockPos position, ResourceSelector selector) {
        if (!matchesGatherBlock(position, selector, bestToolStack())) return false;
        BlockState state = npc.level().getBlockState(position);
        if (!(npc.level() instanceof ServerLevel level)) return !state.is(BlockTags.LOGS);
        if (isProtectedHomeGatherResource(position)) return false;
        if (!state.is(BlockTags.LOGS)) return true;
        return NaturalTreeScanner.inspect(level, position, GATHER_MAX_CONNECTED_TARGETS).natural();
    }

    private boolean isProtectedHomeGatherResource(BlockPos position) {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        BlockState state = level.getBlockState(position);
        ServerPlayer owner = npc.owner();
        if (owner == null) return false;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        return GatherCandidatePolicy.protectedHomeResource(
            home.dimension().equals(level.dimension()),
            position.distSqr(home.position()),
            GATHER_HOME_PROTECTION_RADIUS,
            state.is(BlockTags.LOGS) || state.is(Blocks.BAMBOO)
        );
    }

    private Vec3 nextGatherSearchDestination(int excursion) {
        ServerLevel level = (ServerLevel) npc.level();
        double angle = excursion * Math.PI * (3.0 - Math.sqrt(5.0));
        int targetX = (int) Math.floor(npc.getX() + Math.cos(angle) * GATHER_EXCURSION_DISTANCE);
        int targetZ = (int) Math.floor(npc.getZ() + Math.sin(angle) * GATHER_EXCURSION_DISTANCE);
        ChunkPos targetChunk = new ChunkPos(targetX >> 4, targetZ >> 4);
        level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, targetChunk, 2, npc.getUUID());
        level.getChunk(targetChunk.x, targetChunk.z);
        int targetY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
        return new Vec3(targetX + 0.5, targetY, targetZ + 0.5);
    }

    private void maintainTaskChunkTicket(BlockPos position) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        ChunkPos chunk = new ChunkPos(position);
        level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
        if (active == null) return;
        if (forcedTaskLevel == level && chunk.equals(forcedTaskChunk)) return;

        releaseForcedTaskChunk();
        level.setChunkForced(chunk.x, chunk.z, true);
        forcedTaskLevel = level;
        forcedTaskChunk = chunk;
    }

    private void releaseForcedTaskChunk() {
        if (forcedTaskLevel != null && forcedTaskChunk != null) {
            forcedTaskLevel.setChunkForced(forcedTaskChunk.x, forcedTaskChunk.z, false);
        }
        forcedTaskLevel = null;
        forcedTaskChunk = null;
    }

    private boolean matchesGatherBlock(BlockPos position, ResourceSelector selector, ItemStack tool) {
        BlockState state = npc.level().getBlockState(position);
        if (state.isAir()) return false;
        String requestedSelector = (selector.parsed.tag() ? "#" : "") + selector.parsed.resourceId();
        String blockId = id(state.getBlock());
        if (!GatherCandidatePolicy.mayProduce(requestedSelector, blockId, state.is(BlockTags.LOGS))) return false;
        if (GatherCandidatePolicy.isProbabilisticKnownSource(requestedSelector, blockId)) return true;
        if (npc.creativeResources() && selector.matches(state)) return true;
        try {
            ItemStack effectiveTool = bestGatherToolForState(state, selector);
            return Block.getDrops(state, (ServerLevel) npc.level(), position, null, npc, effectiveTool).stream()
                .anyMatch(selector::matches);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ItemStack creativeGatherStack(
        BlockPos position,
        BlockState state,
        ItemStack tool,
        ResourceSelector selector
    ) {
        try {
            ItemStack drop = Block.getDrops(state, (ServerLevel) npc.level(), position, null, npc, tool).stream()
                .filter(selector::matches)
                .findFirst()
                .orElse(ItemStack.EMPTY);
            if (!drop.isEmpty()) return drop.copy();
        } catch (RuntimeException ignored) {
        }
        ItemStack blockItem = new ItemStack(state.getBlock().asItem());
        if (selector.matches(blockItem)) return blockItem;
        Item fallback = selector.firstItem();
        return fallback == Items.AIR ? ItemStack.EMPTY : new ItemStack(fallback);
    }

    private BlockPos findFarmTarget(String cropId, String action, int radius) {
        BlockPos existing = findBlockAt(position -> {
            BlockState state = npc.level().getBlockState(position);
            if (state.getBlock() instanceof CropBlock crop) {
                return !action.equals("plant") && id(state.getBlock()).equals(cropId) && crop.isMaxAge(state);
            }
            return !action.equals("harvest") && state.isAir() && npc.level().getBlockState(position.below()).getBlock() instanceof FarmBlock;
        }, radius, 3);
        if (existing != null || !NpcLifeSkillPolicy.mayTillNewGround(action)) return existing;
        return findBlockAt(position -> stateAllowsTilling(position, action), radius, 3);
    }

    private boolean stateAllowsTilling(BlockPos cropPosition, String action) {
        if (!npc.level().getBlockState(cropPosition).isAir()) return false;
        BlockState ground = npc.level().getBlockState(cropPosition.below());
        String groundId = id(ground.getBlock());
        return NpcLifeSkillPolicy.isTillableGround(groundId)
            && (!"cycle".equalsIgnoreCase(action) || NpcLifeSkillPolicy.isPreparedFarmGround(groundId));
    }

    private int findHoeSlot() {
        ItemStack main = npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT);
        if (itemId(main).endsWith("_hoe") && (!main.isDamageableItem() || main.getDamageValue() < main.getMaxDamage() - 1)) {
            return CodexNpcEntity.MAIN_HAND_SLOT;
        }
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (itemId(stack).endsWith("_hoe") && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1)) return slot;
        }
        return -1;
    }

    private boolean plantCrop(BlockPos position, String cropId) {
        String seedId = NpcLifeSkillPolicy.seedItemId(cropId);
        int slot = npc.creativeResources() ? -1 : findItemSlot(seedId);
        if (!npc.creativeResources() && slot < 0) return false;
        ItemStack seed = npc.creativeResources() ? new ItemStack(item(seedId)) : npc.inventory().getStackInSlot(slot);
        InteractionResult result = proxy.useItemOn(position.below(), Direction.UP, seed, slot);
        return result.consumesAction() && !npc.level().getBlockState(position).isAir();
    }

    private Recipe<?> findCraftRecipe(String outputId) {
        return findCraftRecipe(outputId, outputId);
    }

    private Recipe<?> findCraftRecipe(String outputId, String materialContextId) {
        List<Recipe<?>> recipes = new ArrayList<>();
        List<CraftRecipeSelectionPolicy.Candidate> policyCandidates = new ArrayList<>();
        for (Recipe<?> recipe : npc.level().getRecipeManager().getRecipes()) {
            if (recipe.getType() != RecipeType.CRAFTING) continue;
            ItemStack output = recipe.getResultItem(npc.level().registryAccess());
            if (output.isEmpty() || !itemId(output).equals(outputId) || !recipe.canCraftInDimensions(3, 3)) continue;
            List<List<String>> options = recipeIngredientOptions(recipe);
            if (!BuildMaterialPrerequisitePolicy.recipePreservesWoodFamily(
                materialContextId,
                options
            )) continue;
            boolean inventoryReady = allocateIngredients(recipe.getIngredients()) != null;
            recipes.add(recipe);
            policyCandidates.add(new CraftRecipeSelectionPolicy.Candidate(
                recipe.getId().toString(),
                inventoryReady,
                recipe.canCraftInDimensions(2, 2),
                options
            ));
        }
        int selected = CraftRecipeSelectionPolicy.choose(outputId, policyCandidates);
        return selected < 0 ? null : recipes.get(selected);
    }

    private List<List<String>> recipeIngredientOptions(Recipe<?> recipe) {
        return recipe.getIngredients().stream()
            .map(ingredient -> Arrays.stream(ingredient.getItems())
                .map(this::itemId)
                .filter(candidateId -> !candidateId.equals("minecraft:air"))
                .distinct()
                .toList())
            .toList();
    }

    private Recipe<?> findBasicBedCraftRecipe(String outputId) {
        Recipe<?> fallback = null;
        for (Recipe<?> recipe : npc.level().getRecipeManager().getRecipes()) {
            if (recipe.getType() != RecipeType.CRAFTING || !recipe.canCraftInDimensions(3, 3)) continue;
            ItemStack output = recipe.getResultItem(npc.level().registryAccess());
            if (output.isEmpty() || !itemId(output).equals(outputId)) continue;
            if (fallback == null) fallback = recipe;
            int woolSlots = countRecipeIngredientSlots(recipe, id -> id.startsWith("minecraft:") && id.endsWith("_wool"));
            int plankSlots = countRecipeIngredientSlots(recipe, CraftPrerequisitePolicy::isWoodCraftingIngredient);
            if (woolSlots == 3 && plankSlots == 3 && recipe.getIngredients().size() == 6) return recipe;
        }
        return fallback;
    }

    private boolean prepareCraftingWorkstation(ActiveWork work, String outputId) {
        return prepareCraftingWorkstation(work, outputId, true);
    }

    private boolean prepareCraftingWorkstation(ActiveWork work, String outputId, boolean required) {
        validateTaskOwnedWorkstation(work);
        if (recoverBlockingTaskWorkstation(work, true, null)) return false;
        if (work.workstation != null
            && !id(npc.level().getBlockState(work.workstation).getBlock()).equals("minecraft:crafting_table")) {
            work.workstation = null;
            work.stalledTicks = 0;
            work.lastDistance = -1;
        }
        if (work.workstation != null
            && npc.position().distanceTo(Vec3.atCenterOf(work.workstation)) > 3.5
            && !isReachableCraftingTable(work, work.workstation)) {
            work.skippedWorkstationTargets.add(work.workstation.immutable());
            work.workstation = null;
            work.stalledTicks = 0;
            work.lastDistance = -1;
        }
        if (work.workstation == null) work.workstation = findCraftingTableForTask(work);
        if (work.workstation == null) {
            if (!required) return true;
            if (prepareMissingCraftingTableMaterials(work, outputId)) return false;
            work.workstation = ensureWorkstation(work, "minecraft:crafting_table");
            if (work.workstation == null) return false;
        }
        boolean ready = approachCraftingWorkstation(work, work.workstation, 3.5, 1.1);
        if (ready) ResourcePriorityLiveFixture.recordCraftingTableUse(npc, work.workstation);
        return ready;
    }

    private BlockPos findCraftingTableForTask(ActiveWork work) {
        BlockPos local = findBlockAt(
            position -> isReachableCraftingTable(work, position),
            CRAFT_WORKSTATION_SEARCH_RADIUS,
            12
        );
        if (local != null) return local;
        ServerPlayer owner = npc.owner();
        if (owner != null && npc.level() instanceof ServerLevel level) {
            BlockPos homeTable = NpcHomeStorage.findCraftingTable(
                level,
                NpcHomeStorage.resolve(owner),
                HomeStoragePolicy.MAX_RADIUS
            );
            if (homeTable != null && isReachableCraftingTable(work, homeTable)) return homeTable;
        }
        return null;
    }

    private boolean isReachableCraftingTable(ActiveWork work, BlockPos position) {
        if (position == null || work.skippedWorkstationTargets.contains(position)
            || !id(npc.level().getBlockState(position).getBlock()).equals("minecraft:crafting_table")) return false;
        if (npc.position().distanceTo(Vec3.atCenterOf(position)) <= 3.5) return true;
        Path path = npc.getNavigation().createPath(position, 2);
        return pathGetsWithinWorkstationReach(path, position, 3.5);
    }

    private boolean pathGetsWithinWorkstationReach(Path path, BlockPos target, double reach) {
        boolean hasEndNode = path != null && path.getNodeCount() > 0 && path.getEndNode() != null;
        Vec3 end = hasEndNode ? path.getEndNode().asVec3().add(0.5D, 0.0D, 0.5D) : Vec3.ZERO;
        Vec3 targetCenter = Vec3.atCenterOf(target);
        return WorkstationNavigationPolicy.pathGetsWithinInteractionReach(
            path != null && path.canReach(),
            hasEndNode,
            end.x,
            end.y,
            end.z,
            targetCenter.x,
            targetCenter.y,
            targetCenter.z,
            reach
        );
    }

    private String resolveCraftItemId(String requestedId) {
        return switch (requestedId) {
            case "minecraft:pickaxe" -> bestCraftCandidate(List.of(
                "minecraft:diamond_pickaxe",
                "minecraft:iron_pickaxe",
                "minecraft:stone_pickaxe",
                "minecraft:golden_pickaxe",
                "minecraft:wooden_pickaxe"
            ), "minecraft:wooden_pickaxe");
            case "minecraft:axe" -> bestCraftCandidate(List.of(
                "minecraft:diamond_axe",
                "minecraft:iron_axe",
                "minecraft:stone_axe",
                "minecraft:golden_axe",
                "minecraft:wooden_axe"
            ), "minecraft:wooden_axe");
            case "minecraft:shovel" -> bestCraftCandidate(List.of(
                "minecraft:diamond_shovel",
                "minecraft:iron_shovel",
                "minecraft:stone_shovel",
                "minecraft:golden_shovel",
                "minecraft:wooden_shovel"
            ), "minecraft:wooden_shovel");
            case "minecraft:hoe" -> bestCraftCandidate(List.of(
                "minecraft:diamond_hoe",
                "minecraft:iron_hoe",
                "minecraft:stone_hoe",
                "minecraft:golden_hoe",
                "minecraft:wooden_hoe"
            ), "minecraft:wooden_hoe");
            case "minecraft:melee_weapon" -> bestCraftCandidate(List.of(
                "minecraft:diamond_sword",
                "minecraft:iron_sword",
                "minecraft:stone_sword",
                "minecraft:golden_sword",
                "minecraft:wooden_sword"
            ), "minecraft:wooden_sword");
            default -> requestedId;
        };
    }

    private String bestCraftCandidate(List<String> candidates, String fallback) {
        if (npc.creativeResources()) return candidates.get(0);
        for (String candidate : candidates) {
            Recipe<?> recipe = findCraftRecipe(candidate);
            if (recipe != null && allocateIngredients(recipe.getIngredients()) != null) return candidate;
            if (canSupplyVanillaToolMaterials(candidate)) return candidate;
        }
        return fallback;
    }

    private boolean canSupplyVanillaToolMaterials(String itemId) {
        int separator = itemId.indexOf('_');
        if (!itemId.startsWith("minecraft:") || separator < 0) return false;
        String tier = itemId.substring("minecraft:".length(), separator);
        String tool = itemId.substring(separator + 1);
        int headCount = tool.equals("sword") ? 2 : 3;
        int stickCount = tool.equals("sword") ? 1 : 2;
        return hasToolHeadMaterial(tier, headCount) && hasStickSupply(stickCount);
    }

    private boolean hasToolHeadMaterial(String tier, int count) {
        return switch (tier) {
            case "wooden" -> inventoryCount("#minecraft:planks") + inventoryCount("#minecraft:logs") * 4 >= count;
            case "stone" -> stoneToolMaterialCount() >= count;
            case "golden" -> inventoryCount("minecraft:gold_ingot") >= count;
            case "iron" -> inventoryCount("minecraft:iron_ingot") >= count;
            case "diamond" -> inventoryCount("minecraft:diamond") >= count;
            default -> false;
        };
    }

    private boolean hasStickSupply(int count) {
        int sticks = inventoryCount("minecraft:stick");
        int plankStickPotential = inventoryCount("#minecraft:planks") / 2 * 4;
        int logStickPotential = inventoryCount("#minecraft:logs") * 8;
        return sticks + plankStickPotential + logStickPotential >= count;
    }

    private int stoneToolMaterialCount() {
        int tagged = inventoryCount("#minecraft:stone_tool_materials");
        if (tagged > 0) return tagged;
        return inventoryCount("minecraft:cobblestone")
            + inventoryCount("minecraft:cobbled_deepslate")
            + inventoryCount("minecraft:blackstone");
    }

    private boolean hasWoodSupplyForTool(String itemId) {
        int headCount = vanillaToolHeadCount(itemId);
        int stickCount = vanillaToolStickCount(itemId);
        String tier = vanillaToolTier(itemId);
        int planks = inventoryCount("#minecraft:planks");
        int logs = inventoryCount("#minecraft:logs");
        int sticks = inventoryCount("minecraft:stick");
        int requiredPlanks = "wooden".equals(tier) ? headCount : 0;
        int missingSticks = Math.max(0, stickCount - sticks);
        int planksForSticks = (int) Math.ceil(missingSticks / 4.0D) * 2;
        return planks + logs * 4 >= requiredPlanks + planksForSticks;
    }

    private String vanillaToolTier(String itemId) {
        if (!itemId.startsWith("minecraft:")) return "";
        String local = itemId.substring("minecraft:".length());
        int separator = local.indexOf('_');
        if (separator <= 0) return "";
        String tier = local.substring(0, separator);
        return switch (tier) {
            case "wooden", "stone", "golden", "iron", "diamond", "netherite" -> tier;
            default -> "";
        };
    }

    private String vanillaToolKind(String itemId) {
        if (!itemId.startsWith("minecraft:")) return "";
        String local = itemId.substring("minecraft:".length());
        int separator = local.indexOf('_');
        return separator <= 0 || separator >= local.length() - 1 ? "" : local.substring(separator + 1);
    }

    private int vanillaToolHeadCount(String itemId) {
        return switch (vanillaToolKind(itemId)) {
            case "sword", "hoe" -> 2;
            case "shovel" -> 1;
            case "pickaxe", "axe" -> 3;
            default -> 0;
        };
    }

    private int vanillaToolStickCount(String itemId) {
        return switch (vanillaToolKind(itemId)) {
            case "sword", "shovel" -> 1;
            case "pickaxe", "axe", "hoe" -> 2;
            default -> 0;
        };
    }

    private boolean hasUsablePickaxeForStone() {
        return hasUsablePickaxeFor(Blocks.STONE.defaultBlockState());
    }

    private boolean hasUsablePickaxeFor(BlockState targetState) {
        return hasUsableToolFor(targetState, "_pickaxe");
    }

    private boolean hasUsableToolFor(BlockState targetState) {
        return hasUsableToolFor(targetState, "");
    }

    private boolean hasUsableToolFor(BlockState targetState, String requiredSuffix) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty() || !requiredSuffix.isBlank() && !itemId(stack).endsWith(requiredSuffix)) continue;
            if (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1) continue;
            if (stack.isCorrectToolForDrops(targetState)) return true;
        }
        return false;
    }

    private boolean approachCraftingWorkstation(ActiveWork work, BlockPos target, double reach, double speed) {
        double distance = npc.position().distanceTo(Vec3.atCenterOf(target));
        if (distance <= reach) {
            npc.getNavigation().stop();
            npc.getLookControl().setLookAt(Vec3.atCenterOf(target));
            work.stalledTicks = 0;
            work.lastDistance = -1;
            return true;
        }
        if (teleportNearTaskTargetWhenAllowed(
            work,
            target,
            distance,
            "\u5de5\u4f5c\u53f0\u8ddd\u79bb\u8fc7\u8fdc\uff0c\u5df2\u4f20\u9001\u5230\u9644\u8fd1"
        )) return false;
        maintainTaskChunkTicket(target);
        Path path = npc.getNavigation().getPath();
        if (path == null || npc.getNavigation().isDone() || work.lastDistance < 0.0D) {
            Path candidate = npc.getNavigation().createPath(target, 2);
            if (pathGetsWithinWorkstationReach(candidate, target, reach)) {
                npc.getNavigation().moveTo(candidate, speed);
            }
        }
        npc.addExhaustion(0.002F);
        taskStatus(work, "\u6b63\u5728\u63a5\u8fd1\u5de5\u4f5c\u53f0\uff0c\u8ddd\u79bb " + Math.round(distance) + " \u683c");
        trackNavigation(work, distance);
        if (WorkstationNavigationPolicy.shouldTryAnotherWorkstation(work.stalledTicks)) {
            npc.getNavigation().stop();
            work.skippedWorkstationTargets.add(target.immutable());
            if (target.equals(work.workstation)) work.workstation = null;
            work.targetBlock = null;
            work.stalledTicks = 0;
            work.lastDistance = -1;
            npc.setStatus("\u5f53\u524d\u5de5\u4f5c\u53f0\u4e0d\u53ef\u8fbe\uff0c\u6b63\u5728\u5bfb\u627e\u5907\u9009\u6216\u5c31\u5730\u653e\u7f6e");
        }
        return false;
    }

    private void validateTaskOwnedWorkstation(ActiveWork work) {
        if (work.taskOwnedWorkstation == null) return;
        String currentId = id(npc.level().getBlockState(work.taskOwnedWorkstation).getBlock());
        if (!currentId.equals(work.taskOwnedWorkstationId)) {
            work.taskOwnedWorkstation = null;
            work.taskOwnedWorkstationId = null;
        }
    }

    /**
     * Removes only a workstation placed by this task when it occupies a
     * required deep-mining passage. Existing player stations are never owned
     * by this field and therefore remain untouched.
     */
    private boolean recoverBlockingTaskWorkstation(
        ActiveWork work,
        boolean workstationStillRequired,
        BlockPos explicitDestination
    ) {
        validateTaskOwnedWorkstation(work);
        BlockPos owned = work.taskOwnedWorkstation;
        if (owned == null || work.deepMiningPhase.isBlank()) return false;
        Set<BlockPos> passage = requiredDeepMiningPassage(work, explicitDestination);
        boolean blocksPassage = passage.contains(owned);
        WorkstationPolicy.BlockingAction action = WorkstationPolicy.blockingAction(
            true,
            true,
            blocksPassage,
            workstationStillRequired
        );
        if (action == WorkstationPolicy.BlockingAction.KEEP) return false;

        String expectedId = work.taskOwnedWorkstationId;
        if (work.ticks - work.lastActionTick < 8) return true;
        work.lastActionTick = work.ticks;
        npc.getNavigation().stop();
        npc.getLookControl().setLookAt(Vec3.atCenterOf(owned));
        npc.swing(InteractionHand.MAIN_HAND, true);
        if (!proxy.breakBlock(owned, -1)
            && id(npc.level().getBlockState(owned).getBlock()).equals(expectedId)) {
            taskStatus(work, "临时工作台挡住矿道，正在重试回收");
            return true;
        }
        if (!npc.creativeResources()) npc.absorbNearbyItemsAt(Vec3.atCenterOf(owned), 3.0D);
        recordInventoryAction(work, "task-workstation-recovery");
        work.skippedWorkstationTargets.add(owned.immutable());
        if (owned.equals(work.workstation)) work.workstation = null;
        work.taskOwnedWorkstation = null;
        work.taskOwnedWorkstationId = null;
        work.pendingWorkstationPlacement = null;
        work.targetBlock = null;
        work.stalledTicks = 0;
        work.lastDistance = -1.0D;
        progress(
            work,
            activeProgress(work),
            action == WorkstationPolicy.BlockingAction.RECOVER_AND_RELOCATE
                ? "已回收堵路的临时工作台，正在改放到矿道侧方"
                : "已回收堵路的临时工作台，继续原采矿任务"
        );
        return true;
    }

    private Set<BlockPos> requiredDeepMiningPassage(ActiveWork work, BlockPos explicitDestination) {
        Set<BlockPos> passage = new HashSet<>();
        addPassageBody(passage, npc.blockPosition());
        addPassageBody(passage, work.deepMiningLastSafeStand);
        addPassageBody(passage, explicitDestination);
        addPassageBody(passage, work.targetBlock);

        if (work.deepMiningPhase.equals("descending") && work.deepMiningEntrance != null) {
            addPassageBody(passage, DeepMiningPolicy.staircaseStand(
                work.deepMiningEntrance,
                work.deepMiningDirection,
                Math.max(0, work.deepMiningStaircaseStep - 1)
            ));
            addPassageBody(passage, DeepMiningPolicy.staircaseStand(
                work.deepMiningEntrance,
                work.deepMiningDirection,
                work.deepMiningStaircaseStep + 1
            ));
        }
        if (work.deepMiningLanding != null
            && (work.deepMiningPhase.equals("branching") || work.deepMiningPhase.equals("returning"))) {
            BlockPos origin = DeepMiningPolicy.branchOrigin(
                work.deepMiningLanding,
                work.deepMiningDirection,
                work.deepMiningBranchIndex,
                work.deepMiningRegionIndex
            );
            addPassageBody(passage, origin);
            addPassageBody(passage, DeepMiningPolicy.branchStand(
                work.deepMiningLanding,
                work.deepMiningDirection,
                work.deepMiningBranchIndex,
                work.deepMiningRegionIndex,
                work.deepMiningBranchProgress + 1
            ));
        }
        return passage;
    }

    private static void addPassageBody(Set<BlockPos> passage, BlockPos feet) {
        if (feet == null) return;
        passage.add(feet.immutable());
        passage.add(feet.above().immutable());
    }

    private Recipe<?> findCookingRecipe(String inputId) {
        ItemStack input = new ItemStack(item(inputId));
        for (RecipeType<?> preferredType : List.of(RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING)) {
            for (Recipe<?> recipe : npc.level().getRecipeManager().getRecipes()) {
                if (recipe.getType() != preferredType) continue;
                if (recipe.getIngredients().stream().anyMatch(ingredient -> ingredient.test(input))) return recipe;
            }
        }
        return null;
    }

    private Recipe<?> findSmeltingRecipeByOutput(String outputId) {
        return findSmeltingRecipeByOutput(outputId, List.of());
    }

    private Recipe<?> findSmeltingRecipeByOutput(
        String outputId,
        List<BuildMaterialPrerequisitePolicy.Requirement> policyRequirements
    ) {
        List<Recipe<?>> candidates = new ArrayList<>();
        List<SmeltingRecipeSelectionPolicy.Candidate> policyCandidates = new ArrayList<>();
        for (Recipe<?> recipe : npc.level().getRecipeManager().getRecipes()) {
            if (recipe.getType() != RecipeType.SMELTING) continue;
            ItemStack output = recipe.getResultItem(npc.level().registryAccess());
            if (output.isEmpty() || !itemId(output).equals(outputId) || recipe.getIngredients().isEmpty()) continue;
            List<String> inputIds = Arrays.stream(recipe.getIngredients().get(0).getItems())
                .map(this::itemId)
                .filter(candidateId -> !candidateId.equals("minecraft:air"))
                .distinct()
                .toList();
            candidates.add(recipe);
            policyCandidates.add(new SmeltingRecipeSelectionPolicy.Candidate(inputIds));
        }
        Set<String> inventoryIds = new HashSet<>();
        for (int slot = 0; slot < npc.inventory().getSlots(); slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!stack.isEmpty()) inventoryIds.add(itemId(stack));
        }
        List<String> preferredInputIds = policyRequirements.stream()
            .map(BuildMaterialPrerequisitePolicy.Requirement::selector)
            .map(this::concreteBuildMaterialId)
            .filter(candidateId -> !candidateId.isBlank())
            .distinct()
            .toList();
        int selected = SmeltingRecipeSelectionPolicy.choose(
            policyCandidates,
            inventoryIds,
            preferredInputIds
        );
        return selected < 0 ? null : candidates.get(selected);
    }

    private String cookingWorkstation(RecipeType<?> type) {
        if (type == RecipeType.BLASTING) return "minecraft:blast_furnace";
        if (type == RecipeType.SMOKING) return "minecraft:smoker";
        return "minecraft:furnace";
    }

    private List<Integer> allocateIngredients(List<Ingredient> ingredients) {
        return CraftingIngredientAllocator.allocate(
            ingredients,
            CodexNpcEntity.INVENTORY_SIZE,
            slot -> npc.inventory().getStackInSlot(slot)
        );
    }

    /**
     * Crafts one reachable prerequisite at a time, using only recipes and items
     * already available in the current world. This mirrors a player's normal
     * log -> plank -> stick workflow without granting missing raw resources.
     */
    private boolean craftMissingIngredient(ActiveWork work, Recipe<?> parent, int depth, Set<String> visiting) {
        String targetItemId = materialContextItemId(
            work,
            itemId(parent.getResultItem(npc.level().registryAccess()))
        );
        return craftMissingIngredient(work, parent, targetItemId, depth, visiting);
    }

    private boolean craftMissingIngredient(
        ActiveWork work,
        Recipe<?> parent,
        String targetItemId,
        int depth,
        Set<String> visiting
    ) {
        if (depth >= MAX_CRAFT_DEPENDENCY_DEPTH) return false;
        Ingredient missing = firstMissingIngredient(parent.getIngredients());
        if (missing == null) return false;

        List<String> acceptedItemIds = Arrays.stream(missing.getItems())
            .map(this::itemId)
            .filter(candidateId -> !candidateId.equals("minecraft:air"))
            .distinct()
            .toList();
        List<List<String>> parentIngredientOptions = recipeIngredientOptions(parent);

        List<Recipe<?>> candidates = npc.level().getRecipeManager().getRecipes().stream()
            .filter(candidate -> candidate.getType() == RecipeType.CRAFTING)
            .filter(candidate -> candidate.canCraftInDimensions(3, 3))
            .filter(candidate -> {
                ItemStack output = candidate.getResultItem(npc.level().registryAccess());
                return !output.isEmpty() && missing.test(output);
            })
            .sorted(Comparator
                .<Recipe<?>>comparingInt(candidate -> {
                    ItemStack output = candidate.getResultItem(npc.level().registryAccess());
                    return CraftRecipeSelectionPolicy.preferenceScore(
                        itemId(output),
                        craftRecipePolicyCandidate(candidate)
                    );
                })
                .reversed()
                .thenComparing(candidate -> candidate.getId().toString()))
            .toList();

        for (Recipe<?> candidate : candidates) {
            if (candidate.getType() != RecipeType.CRAFTING || !candidate.canCraftInDimensions(3, 3)) continue;
            ItemStack output = candidate.getResultItem(npc.level().registryAccess()).copy();
            if (output.isEmpty() || !missing.test(output)) continue;
            List<List<String>> candidateIngredientOptions = recipeIngredientOptions(candidate);
            if (!BuildMaterialPrerequisitePolicy.recipePreservesWoodFamily(
                targetItemId,
                candidateIngredientOptions
            )) continue;
            String outputId = itemId(output);
            if (!BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
                targetItemId,
                outputId,
                acceptedItemIds
            )) continue;
            if (CraftRecipeSelectionPolicy.unsafePrerequisite(
                targetItemId,
                parentIngredientOptions,
                outputId,
                candidateIngredientOptions
            )) continue;
            if (!visiting.add(outputId)) continue;
            try {
                List<Integer> allocation = allocateIngredients(candidate.getIngredients());
                if (allocation == null && CraftRecipeSelectionPolicy.unsafeRecursivePrerequisite(
                    outputId,
                    candidateIngredientOptions
                )) continue;
                if (allocation == null
                    && craftMissingIngredient(work, candidate, targetItemId, depth + 1, visiting)) return true;
                allocation = allocateIngredients(candidate.getIngredients());
                if (allocation == null) continue;

                if (!candidate.canCraftInDimensions(2, 2)
                    && !prepareCraftingWorkstation(work, outputId)) return true;
                if (work.ticks - work.lastActionTick < 8) return true;
                if (!CraftingIngredientAllocator.canInsertAfterConsumption(
                    allocation,
                    CodexNpcEntity.BACKPACK_SIZE,
                    slot -> npc.inventory().getStackInSlot(slot),
                    output
                )) {
                    fail(work, "NPC 背包没有空间接收前置材料 " + outputId, "INVENTORY_FULL");
                    return true;
                }

                work.lastActionTick = work.ticks;
                consumeIngredients(allocation);
                npc.insert(output);
                recordInventoryAction(work, "craft-output");
                npc.swing(InteractionHand.MAIN_HAND);
                npc.addExhaustion(0.02F);
                progress(work, activeProgress(work), "先制作配方材料 " + outputId);
                return true;
            } finally {
                visiting.remove(outputId);
            }
        }
        return false;
    }

    private Ingredient firstMissingIngredient(List<Ingredient> ingredients) {
        return CraftingIngredientAllocator.firstMissing(
            ingredients,
            CodexNpcEntity.INVENTORY_SIZE,
            slot -> npc.inventory().getStackInSlot(slot)
        );
    }

    private int countRecipeIngredientSlots(Recipe<?> recipe, Predicate<String> idPredicate) {
        int count = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty() && ingredientMatches(ingredient, idPredicate)) count++;
        }
        return count;
    }

    private boolean ingredientMatches(Ingredient ingredient, Predicate<String> idPredicate) {
        for (ItemStack candidate : ingredient.getItems()) {
            if (!candidate.isEmpty() && idPredicate.test(itemId(candidate))) return true;
        }
        return false;
    }

    private CraftRecipeSelectionPolicy.Candidate craftRecipePolicyCandidate(Recipe<?> recipe) {
        return new CraftRecipeSelectionPolicy.Candidate(
            recipe.getId().toString(),
            allocateIngredients(recipe.getIngredients()) != null,
            recipe.canCraftInDimensions(2, 2),
            recipeIngredientOptions(recipe)
        );
    }

    private void consumeIngredients(List<Integer> slots) {
        for (int slot : slots) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            Item remainder = stack.getItem().hasCraftingRemainingItem() ? stack.getItem().getCraftingRemainingItem() : Items.AIR;
            stack.shrink(1);
            npc.inventory().setStackInSlot(slot, stack);
            if (remainder != Items.AIR) npc.insert(new ItemStack(remainder));
        }
        recordInventoryAction("craft-consume");
    }

    private void recordInventoryAction(String action) {
        recordInventoryAction(active, action);
    }

    private void recordInventoryAction(ActiveWork work, String action) {
        npc.recordInventoryTransaction(work == null ? "" : work.id, action);
    }

    private ServerPlayer findOnlinePlayer(String playerName) {
        if (npc.level().getServer() == null) return null;
        return npc.level().getServer().getPlayerList().getPlayers().stream()
            .filter(player -> player.getGameProfile().getName().equalsIgnoreCase(playerName))
            .findFirst()
            .orElse(null);
    }

    private ServerPlayer findRecipientPlayer(String playerName) {
        String normalized = playerName == null ? "" : playerName.trim();
        ServerPlayer owner = npc.owner();
        if (normalized.isBlank()
            || normalized.equalsIgnoreCase("player")
            || normalized.equalsIgnoreCase("owner")
            || normalized.equalsIgnoreCase("me")
            || normalized.equals("我")) {
            return owner;
        }
        ServerPlayer exact = findOnlinePlayer(normalized);
        if (exact != null) return exact;
        if (owner != null && normalized.equalsIgnoreCase(owner.getGameProfile().getName())) return owner;
        return null;
    }

    private int throwItems(String requestedId, int requested, ServerPlayer recipient) {
        int moved = 0;
        while (moved < requested) {
            ItemStack stack = extract(requestedId, Math.min(64, requested - moved));
            if (stack.isEmpty()) break;
            moved += stack.getCount();
            spawnThrownStack(stack, recipient);
        }
        if (moved > 0) {
            recordInventoryAction(recipient == null ? "drop" : "deliver");
            npc.swing(InteractionHand.MAIN_HAND);
        }
        return moved;
    }

    private void spawnThrownStack(ItemStack stack, ServerPlayer recipient) {
        if (stack.isEmpty() || !(npc.level() instanceof ServerLevel level)) return;
        Vec3 spawn = npc.position().add(0.0D, npc.getEyeHeight() - 0.25D, 0.0D);
        if (recipient != null) {
            Vec3 recipientPoint = recipient.position().add(0.0D, 0.35D, 0.0D);
            Vec3 towardNpc = npc.position().subtract(recipientPoint);
            if (towardNpc.lengthSqr() < 0.0001D) towardNpc = new Vec3(1.0D, 0.0D, 0.0D);
            spawn = recipientPoint.add(towardNpc.normalize().scale(0.65D));
        }
        ItemEntity dropped = new ItemEntity(level, spawn.x, spawn.y, spawn.z, stack);
        Vec3 target = recipient == null
            ? npc.getLookAngle().scale(3.0).add(npc.position())
            : recipient.getEyePosition();
        Vec3 direction = target.subtract(dropped.position());
        if (direction.lengthSqr() < 0.0001) direction = npc.getLookAngle();
        direction = direction.normalize();
        double speed = recipient == null ? 0.35D : 0.12D;
        dropped.setDeltaMovement(direction.x * speed, Math.max(0.08D, direction.y * speed + 0.08D), direction.z * speed);
        dropped.setThrower(npc.getUUID());
        if (recipient != null) {
            dropped.setTarget(recipient.getUUID());
            dropped.getPersistentData().putUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG, recipient.getUUID());
            dropped.setPickUpDelay(0);
        } else {
            dropped.getPersistentData().putUUID(CodexNpcEntity.DISCARDED_BY_TAG, npc.getUUID());
            dropped.setPickUpDelay(8);
        }
        level.addFreshEntity(dropped);
    }

    private int findFishingRodSlot() {
        ItemStack main = npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT);
        if (main.is(Items.FISHING_ROD) && (!main.isDamageableItem() || main.getDamageValue() < main.getMaxDamage() - 1)) {
            return CodexNpcEntity.MAIN_HAND_SLOT;
        }
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(Items.FISHING_ROD) && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1)) return slot;
        }
        return -1;
    }

    private int findUsableSwordSlot() {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty() || !itemId(stack).endsWith("_sword")) continue;
            if (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage() - 1) return slot;
        }
        return -1;
    }

    private BlockPos findFishingWater(int requestedRadius) {
        int radius = Math.max(4, Math.min(64, requestedRadius));
        return findBlockAt(this::isFishableWater, radius, 6);
    }

    private boolean isFishableWater(BlockPos position) {
        return position != null
            && npc.level().getFluidState(position).is(FluidTags.WATER)
            && npc.level().getFluidState(position.above()).isEmpty()
            && npc.level().getBlockState(position.above()).getCollisionShape(npc.level(), position.above()).isEmpty();
    }

    private List<ItemStack> rollFishingLoot(ServerLevel level, BlockPos water, ItemStack rod) {
        LootTable table = level.getServer().getLootData().getLootTable(BuiltInLootTables.FISHING);
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(water))
            .withParameter(LootContextParams.TOOL, rod.copy())
            .withOptionalParameter(LootContextParams.THIS_ENTITY, npc)
            .withLuck(EnchantmentHelper.getFishingLuckBonus(rod))
            .create(LootContextParamSets.FISHING);
        return table.getRandomItems(params);
    }

    private BlockPos findSleepBed(int requestedRadius) {
        ServerPlayer owner = npc.owner();
        ServerLevel level = (ServerLevel) npc.level();
        if (owner != null && owner.getRespawnPosition() != null
            && owner.getRespawnDimension().equals(level.dimension())) {
            BlockPos respawn = owner.getRespawnPosition();
            if (level.hasChunkAt(respawn) && level.getBlockState(respawn).is(BlockTags.BEDS)) return respawn.immutable();
        }
        int radius = Math.max(4, Math.min(64, requestedRadius));
        return findBlock(state -> state.is(BlockTags.BEDS), radius, 8);
    }

    private int transferToPlayer(ServerPlayer recipient, String requestedId, int requested) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        int moved = 0;
        for (int source = 0; source < CodexNpcEntity.INVENTORY_SIZE && moved < requested; source++) {
            ItemStack stack = npc.inventory().getStackInSlot(source);
            if (!selector.matches(stack)) continue;
            int offered = Math.min(stack.getCount(), requested - moved);
            ItemStack transfer = stack.copyWithCount(offered);
            recipient.getInventory().add(transfer);
            int accepted = offered - transfer.getCount();
            if (accepted <= 0) continue;
            stack.shrink(accepted);
            npc.inventory().setStackInSlot(source, stack);
            moved += accepted;
        }
        if (moved > 0) {
            recordInventoryAction("deliver");
            recipient.getInventory().setChanged();
            recipient.containerMenu.broadcastChanges();
        }
        return moved;
    }

    private int deposit(Container container, String requestedId, int requested) {
        ResourceSelector selector = requestedId == null ? null : ResourceSelector.parse(requestedId);
        int sourceLimit = requestedId == null ? CodexNpcEntity.BACKPACK_SIZE : CodexNpcEntity.INVENTORY_SIZE;
        int moved = 0;
        for (int source = 0; source < sourceLimit && moved < requested; source++) {
            ItemStack stack = npc.inventory().getStackInSlot(source);
            if (stack.isEmpty() || selector != null && !selector.matches(stack)) continue;
            int remaining = Math.min(stack.getCount(), requested - moved);
            for (int target = 0; target < container.getContainerSize() && remaining > 0; target++) {
                if (!container.canPlaceItem(target, stack)) continue;
                ItemStack existing = container.getItem(target);
                if (existing.isEmpty()) {
                    int count = Math.min(remaining, stack.getMaxStackSize());
                    container.setItem(target, stack.copyWithCount(count));
                    stack.shrink(count);
                    moved += count;
                    remaining -= count;
                } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                    int count = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                    if (count <= 0) continue;
                    existing.grow(count);
                    stack.shrink(count);
                    moved += count;
                    remaining -= count;
                }
            }
            npc.inventory().setStackInSlot(source, stack);
        }
        if (moved > 0) recordInventoryAction("store");
        return moved;
    }

    private int withdraw(Container container, String requestedId, int requested) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        int moved = 0;
        for (int source = 0; source < container.getContainerSize() && moved < requested; source++) {
            ItemStack stored = container.getItem(source);
            if (stored.isEmpty() || !selector.matches(stored)) continue;
            int wanted = Math.min(stored.getCount(), requested - moved);
            ItemStack remainder = stored.copyWithCount(wanted);
            for (int target = 0; target < CodexNpcEntity.BACKPACK_SIZE && !remainder.isEmpty(); target++) {
                remainder = npc.inventory().insertItem(target, remainder, false);
            }
            int accepted = wanted - remainder.getCount();
            if (accepted <= 0) break;
            stored.shrink(accepted);
            container.setItem(source, stored);
            moved += accepted;
        }
        if (moved > 0) recordInventoryAction("retrieve");
        return moved;
    }

    private int depositOrganizable(Container container, int requested) {
        int moved = 0;
        for (int source = 0; source < CodexNpcEntity.BACKPACK_SIZE && moved < requested; source++) {
            ItemStack stack = npc.inventory().getStackInSlot(source);
            if (!isOrganizable(source, stack)) continue;
            int remaining = Math.min(stack.getCount(), requested - moved);
            for (int target = 0; target < container.getContainerSize() && remaining > 0; target++) {
                if (!container.canPlaceItem(target, stack)) continue;
                ItemStack existing = container.getItem(target);
                if (existing.isEmpty()) {
                    int count = Math.min(remaining, stack.getMaxStackSize());
                    container.setItem(target, stack.copyWithCount(count));
                    stack.shrink(count);
                    moved += count;
                    remaining -= count;
                } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                    int count = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                    if (count <= 0) continue;
                    existing.grow(count);
                    stack.shrink(count);
                    moved += count;
                    remaining -= count;
                }
            }
            npc.inventory().setStackInSlot(source, stack);
        }
        return moved;
    }

    private StorageSortResult organizeHomeStorageTransactional(int radius) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) return StorageSortResult.UNAVAILABLE;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        List<BlockPos> positions = NpcHomeStorage.findContainers(level, home, radius);
        if (positions.isEmpty()) return StorageSortResult.UNAVAILABLE;

        List<StorageSnapshot> snapshots = new ArrayList<>();
        List<ItemStack> pool = new ArrayList<>();
        for (BlockPos position : positions) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            List<ItemStack> contents = new ArrayList<>(container.getContainerSize());
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack copy = container.getItem(slot).copy();
                contents.add(copy);
                if (!copy.isEmpty()) pool.add(copy.copy());
            }
            snapshots.add(new StorageSnapshot(container, contents));
        }
        if (snapshots.isEmpty()) return StorageSortResult.UNAVAILABLE;

        List<Integer> npcSlots = new ArrayList<>();
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!isOrganizable(slot, stack)) continue;
            npcSlots.add(slot);
            pool.add(stack.copy());
        }
        pool.sort(Comparator
            .comparingInt((ItemStack stack) -> storageCategory(stack).ordinal())
            .thenComparing(this::itemId)
            .thenComparing(stack -> stack.getHoverName().getString()));

        for (StorageSnapshot snapshot : snapshots) {
            for (int slot = 0; slot < snapshot.container().getContainerSize(); slot++) {
                snapshot.container().setItem(slot, ItemStack.EMPTY);
            }
        }
        for (ItemStack pooled : pool) {
            ItemStack remainder = pooled.copy();
            for (StorageSnapshot snapshot : snapshots) {
                remainder = insertIntoContainer(snapshot.container(), remainder);
                if (remainder.isEmpty()) break;
            }
            if (!remainder.isEmpty()) {
                restoreStorageSnapshots(snapshots);
                return StorageSortResult.FULL;
            }
        }

        for (int slot : npcSlots) npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        for (StorageSnapshot snapshot : snapshots) snapshot.container().setChanged();
        return StorageSortResult.SUCCEEDED;
    }

    private ItemStack insertIntoContainer(Container container, ItemStack source) {
        ItemStack remainder = source.copy();
        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            if (!container.canPlaceItem(slot, remainder)) continue;
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) continue;
            if (!ItemStack.isSameItemSameTags(existing, remainder)) continue;
            int limit = Math.min(container.getMaxStackSize(), existing.getMaxStackSize());
            int moved = Math.min(remainder.getCount(), limit - existing.getCount());
            if (moved <= 0) continue;
            existing.grow(moved);
            remainder.shrink(moved);
        }
        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            if (!container.canPlaceItem(slot, remainder) || !container.getItem(slot).isEmpty()) continue;
            int moved = Math.min(remainder.getCount(), Math.min(container.getMaxStackSize(), remainder.getMaxStackSize()));
            container.setItem(slot, remainder.copyWithCount(moved));
            remainder.shrink(moved);
        }
        return remainder;
    }

    private void restoreStorageSnapshots(List<StorageSnapshot> snapshots) {
        for (StorageSnapshot snapshot : snapshots) {
            for (int slot = 0; slot < snapshot.container().getContainerSize(); slot++) {
                snapshot.container().setItem(slot, snapshot.contents().get(slot).copy());
            }
            snapshot.container().setChanged();
        }
    }

    private HomeStoragePolicy.Category storageCategory(ItemStack stack) {
        return HomeStoragePolicy.category(itemId(stack), stack.getFoodProperties(npc) != null, stack.hasFoil());
    }

    private int organizableInventoryCount() {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (isOrganizable(slot, stack)) count += stack.getCount();
        }
        return count;
    }

    private boolean isOrganizable(int sourceSlot, ItemStack stack) {
        if (stack.isEmpty() || stack.hasCustomHoverName() || stack.hasFoil() || stack.getFoodProperties(npc) != null) return false;
        String value = itemId(stack);
        if (HomeStoragePolicy.isRareCarryItem(value)) return false;
        String group = workingGearGroup(value);
        if (group == null) return true;
        boolean rare = value.contains("netherite") || value.contains("diamond") || value.endsWith("elytra");
        return !HomeStoragePolicy.shouldRetainBackpackGear(isBestWorkingGear(sourceSlot, group, stack), rare);
    }

    private String workingGearGroup(String value) {
        if (value.endsWith("_helmet")) return "armor:head";
        if (value.endsWith("_chestplate") || value.endsWith("elytra")) return "armor:chest";
        if (value.endsWith("_leggings")) return "armor:legs";
        if (value.endsWith("_boots")) return "armor:feet";
        if (value.endsWith("_sword") || value.endsWith("bow") || value.endsWith("crossbow") || value.endsWith("trident")) return "weapon";
        if (value.endsWith("_pickaxe")) return "tool:pickaxe";
        if (value.endsWith("_axe")) return "tool:axe";
        if (value.endsWith("_shovel")) return "tool:shovel";
        if (value.endsWith("_hoe")) return "tool:hoe";
        if (value.endsWith("shield")) return "utility:shield";
        if (value.endsWith("fishing_rod")) return "utility:fishing";
        return null;
    }

    private boolean isBestWorkingGear(int sourceSlot, String group, ItemStack candidate) {
        double score = workingGearScore(group, candidate);
        for (int slot = 0; slot < npc.inventory().getSlots(); slot++) {
            if (slot == sourceSlot) continue;
            ItemStack other = npc.inventory().getStackInSlot(slot);
            if (!group.equals(workingGearGroup(itemId(other)))) continue;
            double otherScore = workingGearScore(group, other);
            // Equipped slots win ties.  Among backpack ties, keep only the
            // first one and archive the duplicates.
            if (otherScore > score + 0.001D
                || Math.abs(otherScore - score) <= 0.001D
                    && (slot >= CodexNpcEntity.BACKPACK_SIZE || slot < sourceSlot)) return false;
        }
        return true;
    }

    private double workingGearScore(String group, ItemStack stack) {
        if (stack.isEmpty()) return Double.NEGATIVE_INFINITY;
        double durability = stack.isDamageableItem()
            ? Math.max(0.0D, (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage())
            : 1.0D;
        int enchantment = EnchantmentHelper.getEnchantments(stack).entrySet().stream()
            .mapToInt(entry -> (entry.getKey().isCurse() ? -8 : 2) * entry.getValue())
            .sum();
        if (group.startsWith("armor:")) {
            net.minecraft.world.entity.EquipmentSlot slot = switch (group) {
                case "armor:head" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
                case "armor:chest" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
                case "armor:legs" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
                default -> net.minecraft.world.entity.EquipmentSlot.FEET;
            };
            double armor = stack.getAttributeModifiers(slot).get(Attributes.ARMOR).stream().mapToDouble(AttributeModifier::getAmount).sum();
            double toughness = stack.getAttributeModifiers(slot).get(Attributes.ARMOR_TOUGHNESS).stream().mapToDouble(AttributeModifier::getAmount).sum();
            return EquipmentPolicy.score(armor, toughness, enchantment, durability);
        }
        if (group.equals("weapon")) return weaponScore(stack);
        String value = itemId(stack);
        double tier = value.contains("netherite") ? 6 : value.contains("diamond") ? 5
            : value.contains("iron") ? 4 : value.contains("stone") ? 3
            : value.contains("golden") ? 2 : value.contains("wooden") ? 1 : 0;
        return tier * 100 + enchantment * 3 + durability * 5;
    }

    private HomeRetrieveInspection inspectHomeRetrieve(ResourceSelector selector, int requested, int radius) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) {
            return new HomeRetrieveInspection(0, false);
        }
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        ensureHomeStorageChunks(level, home, radius);

        List<ItemStack> simulatedInventory = new ArrayList<>(CodexNpcEntity.BACKPACK_SIZE);
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            simulatedInventory.add(npc.inventory().getStackInSlot(slot).copy());
        }

        int available = 0;
        int remainingCapacity = requested;
        for (BlockPos position : NpcHomeStorage.findContainers(level, home, radius)) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (stored.isEmpty() || !selector.matches(stored)) continue;
                available = Math.min(Integer.MAX_VALUE, available + stored.getCount());
                if (remainingCapacity <= 0) continue;
                int wanted = Math.min(stored.getCount(), remainingCapacity);
                ItemStack remainder = insertIntoInventorySnapshot(simulatedInventory, stored.copyWithCount(wanted));
                int accepted = wanted - remainder.getCount();
                remainingCapacity -= accepted;
                // The runtime withdraw loop sees storage slots in this order as
                // well. Once a matching stack cannot fit, it cannot safely move
                // on without risking a partial transfer.
                if (!remainder.isEmpty()) remainingCapacity = Math.max(1, remainingCapacity);
            }
        }
        return new HomeRetrieveInspection(available, remainingCapacity <= 0);
    }

    private ItemStack insertIntoInventorySnapshot(List<ItemStack> inventory, ItemStack source) {
        ItemStack remainder = source.copy();
        for (int slot = 0; slot < inventory.size() && !remainder.isEmpty(); slot++) {
            ItemStack existing = inventory.get(slot);
            if (existing.isEmpty()) {
                int moved = Math.min(remainder.getCount(), Math.min(64, remainder.getMaxStackSize()));
                inventory.set(slot, remainder.copyWithCount(moved));
                remainder.shrink(moved);
                continue;
            }
            if (!ItemStack.isSameItemSameTags(existing, remainder)) continue;
            int limit = Math.min(64, existing.getMaxStackSize());
            int moved = Math.min(remainder.getCount(), limit - existing.getCount());
            if (moved <= 0) continue;
            existing.grow(moved);
            remainder.shrink(moved);
        }
        return remainder;
    }

    private void ensureHomeStorageChunks(ServerLevel level, NpcHomeStorage.Home home, int radius) {
        if (!home.dimension().equals(level.dimension())) return;
        int clampedRadius = HomeStoragePolicy.clampRadius(radius);
        ChunkPos homeChunk = new ChunkPos(home.position());
        int chunkRadius = Math.max(0, (clampedRadius + 15) / 16);
        for (int x = homeChunk.x - chunkRadius; x <= homeChunk.x + chunkRadius; x++) {
            for (int z = homeChunk.z - chunkRadius; z <= homeChunk.z + chunkRadius; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
                level.getChunk(x, z);
            }
        }
    }

    private BlockPos findHomeStorage(ResourceSelector selector, boolean requireContents, ActiveWork work, int radius) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) return null;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        ensureHomeStorageChunks(level, home, radius);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos position : NpcHomeStorage.findContainers(level, home, radius)) {
            if (work.skippedStorageTargets.contains(position)) continue;
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            if (requireContents && (selector == null || !containerContains(container, selector))) continue;
            if (!requireContents && !containerHasSpace(container)) continue;
            double distance = npc.position().distanceToSqr(Vec3.atCenterOf(position));
            if (distance < nearestDistance) {
                nearest = position.immutable();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * Expands the owner's home storage without commands or direct setBlock
     * calls.  Vanilla item placement is performed by the interaction proxy,
     * so protection mods and spawn/claim permissions can reject it normally.
     */
    private BlockPos createHomeStorageIfPossible(ActiveWork work, int radius) {
        ServerPlayer owner = npc.owner();
        if (owner == null || !(npc.level() instanceof ServerLevel level)) {
            fail(work, "无法确认 NPC 主人或家园维度", "HOME_UNAVAILABLE");
            return null;
        }
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        if (!home.dimension().equals(level.dimension())) {
            fail(work, "家园位于其他维度，当前不能安全扩建仓库", "HOME_DIMENSION_UNREACHABLE");
            return null;
        }

        int chestSlot = findUnreservedExpansionItemSlot(work, "minecraft:chest");
        BlockPos table = NpcHomeStorage.findCraftingTable(level, home, radius);
        int planks = usableExpansionMaterialCount(work, "#minecraft:planks");
        if (!HomeStoragePolicy.canExpandStorage(chestSlot < 0 ? 0 : 1, planks, table != null, npc.creativeResources())) {
            int required = HomeStoragePolicy.CHEST_PLANKS + (table == null ? HomeStoragePolicy.CRAFTING_TABLE_PLANKS : 0);
            fail(work, "家中仓库已满，扩建需要箱子或至少 " + required + " 个木板", "HOME_STORAGE_MATERIALS_MISSING");
            return null;
        }
        if (work.ticks - work.lastActionTick < 8) return null;

        if (chestSlot < 0 && !npc.creativeResources() && table == null) {
            BlockPos tablePosition = NpcHomeStorage.findSafePlacement(
                level,
                home,
                home.position(),
                radius,
                position -> isStoragePlacementCellClear(level, position)
            );
            if (tablePosition == null) {
                fail(work, "家附近没有可以安全放置工作台的位置", "HOME_PLACEMENT_BLOCKED");
                return null;
            }
            if (!approach(work, tablePosition, 3.2, 1.08)) return null;
            ItemStack tableStack = new ItemStack(Items.CRAFTING_TABLE);
            InteractionResult result = proxy.useItemOn(tablePosition.below(), Direction.UP, tableStack, -1);
            work.lastActionTick = work.ticks;
            if (!result.consumesAction() || !level.getBlockState(tablePosition).is(Blocks.CRAFTING_TABLE)) {
                fail(work, "工作台放置被世界保护或其他模组拒绝", "HOME_PLACEMENT_DENIED");
                return null;
            }
            consumeExpansionMaterial(work, "#minecraft:planks", HomeStoragePolicy.CRAFTING_TABLE_PLANKS);
            npc.swing(InteractionHand.MAIN_HAND);
            work.storageExpanded = true;
            progress(work, activeProgress(work), "已在家园制作并放置工作台，准备制作箱子");
            return null;
        }

        BlockPos placementAnchor = table == null ? home.position() : table;
        BlockPos chestPosition = NpcHomeStorage.findSafePlacement(
            level,
            home,
            placementAnchor,
            table == null ? radius : 4,
            position -> isStoragePlacementCellClear(level, position)
        );
        if (chestPosition == null) {
            fail(work, "家附近没有可以安全放置新箱子的位置", "HOME_PLACEMENT_BLOCKED");
            return null;
        }
        if (table != null && chestSlot < 0 && !approach(work, table, 3.2, 1.08)) return null;
        if (!approach(work, chestPosition, 3.2, 1.08)) return null;

        ItemStack chestStack = chestSlot >= 0
            ? npc.inventory().getStackInSlot(chestSlot)
            : new ItemStack(Items.CHEST);
        InteractionResult result = proxy.useItemOn(chestPosition.below(), Direction.UP, chestStack, chestSlot);
        work.lastActionTick = work.ticks;
        BlockEntity placed = level.getBlockEntity(chestPosition);
        if (!result.consumesAction() || !(placed instanceof Container)) {
            fail(work, "箱子放置被世界保护或其他模组拒绝", "HOME_PLACEMENT_DENIED");
            return null;
        }
        if (chestSlot < 0 && !npc.creativeResources()) {
            consumeExpansionMaterial(work, "#minecraft:planks", HomeStoragePolicy.CHEST_PLANKS);
        }
        npc.swing(InteractionHand.MAIN_HAND);
        work.storageExpanded = true;
        if (work.kind.equals("organize-storage") || work.kind.equals("store") && explicitStoreSelector(work) == null) {
            work.requestedCount = work.completed + organizableInventoryCount();
        }
        progress(work, activeProgress(work), "家中仓库已满，已制作并放置新箱子");
        return chestPosition.immutable();
    }

    private boolean isStoragePlacementCellClear(ServerLevel level, BlockPos position) {
        AABB cell = new AABB(position);
        return level.getEntitiesOfClass(LivingEntity.class, cell, Entity::isAlive).isEmpty();
    }

    private ResourceSelector explicitStoreSelector(ActiveWork work) {
        if (!work.kind.equals("store") || !work.spec.has("itemId") || work.spec.get("itemId").isJsonNull()) return null;
        String requestedId = work.spec.get("itemId").getAsString();
        return requestedId.isBlank() ? null : ResourceSelector.parse(requestedId);
    }

    private int pendingStoreReservation(ActiveWork work) {
        return explicitStoreSelector(work) == null ? 0 : Math.max(0, work.requestedCount - work.completed);
    }

    private int usableExpansionMaterialCount(ActiveWork work, String materialId) {
        ResourceSelector material = ResourceSelector.parse(materialId);
        ResourceSelector reserved = explicitStoreSelector(work);
        int total = 0;
        int overlap = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!material.matches(stack)) continue;
            total += stack.getCount();
            if (reserved != null && reserved.matches(stack)) overlap += stack.getCount();
        }
        return HomeStoragePolicy.usableExpansionMaterial(total, overlap, pendingStoreReservation(work));
    }

    private int findUnreservedExpansionItemSlot(ActiveWork work, String itemSelector) {
        ResourceSelector target = ResourceSelector.parse(itemSelector);
        ResourceSelector reserved = explicitStoreSelector(work);
        int overlap = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!target.matches(stack)) continue;
            if (reserved == null || !reserved.matches(stack)) return slot;
            overlap += stack.getCount();
        }
        int remainingReserve = Math.min(overlap, pendingStoreReservation(work));
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!target.matches(stack) || reserved == null || !reserved.matches(stack)) continue;
            int reservedHere = Math.min(stack.getCount(), remainingReserve);
            remainingReserve -= reservedHere;
            if (stack.getCount() > reservedHere) return slot;
        }
        return -1;
    }

    private boolean consumeExpansionMaterial(ActiveWork work, String materialId, int requested) {
        if (usableExpansionMaterialCount(work, materialId) < requested) return false;
        ResourceSelector material = ResourceSelector.parse(materialId);
        ResourceSelector reserved = explicitStoreSelector(work);
        int remaining = requested;

        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!material.matches(stack) || reserved != null && reserved.matches(stack)) continue;
            int count = Math.min(remaining, stack.getCount());
            stack.shrink(count);
            npc.inventory().setStackInSlot(slot, stack);
            remaining -= count;
        }

        int overlap = 0;
        if (reserved != null) {
            for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
                ItemStack stack = npc.inventory().getStackInSlot(slot);
                if (material.matches(stack) && reserved.matches(stack)) overlap += stack.getCount();
            }
        }
        int remainingReserve = Math.min(overlap, pendingStoreReservation(work));
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!material.matches(stack) || reserved == null || !reserved.matches(stack)) continue;
            int reservedHere = Math.min(stack.getCount(), remainingReserve);
            remainingReserve -= reservedHere;
            int count = Math.min(remaining, stack.getCount() - reservedHere);
            if (count <= 0) continue;
            stack.shrink(count);
            npc.inventory().setStackInSlot(slot, stack);
            remaining -= count;
        }
        return remaining == 0;
    }

    private BlockPos ensureWorkstation(ActiveWork work, String workstationId) {
        if (!(npc.level() instanceof ServerLevel level)) {
            fail(work, "无法在当前世界放置工作站", "WORLD_UNAVAILABLE");
            return null;
        }
        Item workstationItem = item(workstationId);
        if (!(workstationItem instanceof BlockItem)) {
            fail(work, "无效的工作站物品 " + workstationId, "WORKSTATION_INVALID");
            return null;
        }
        int sourceSlot = findItemSlot(workstationId);
        WorkstationPolicy.MaterialCost fallback = WorkstationPolicy.fallbackMaterialCost(workstationId);
        int materialCount = fallback == null ? 0 : inventoryCount(fallback.selector());
        if (sourceSlot < 0
            && !npc.creativeResources()
            && fallback != null
            && materialCount < fallback.count()
            && craftOneMatching(work, fallback.selector())) {
            return null;
        }
        if (!WorkstationPolicy.canSupply(sourceSlot >= 0, materialCount, npc.creativeResources(), fallback)) {
            String requirement = fallback == null
                ? "NPC 背包中需要一个可放置的 " + workstationId
                : "NPC 背包中需要 " + workstationId + "，或至少 " + fallback.count() + " 个 " + fallback.selector();
            fail(work, requirement, "WORKSTATION_NOT_FOUND");
            return null;
        }

        if (work.pendingWorkstationPlacement != null
            && !isReachableWorkstationPlacement(work.pendingWorkstationPlacement, 3.2)) {
            work.pendingWorkstationPlacement = null;
            work.stalledTicks = 0;
            work.lastDistance = -1;
        }
        if (work.pendingWorkstationPlacement == null) {
            work.pendingWorkstationPlacement = findSafeTaskWorkstationPlacement(work, level);
            if (work.pendingWorkstationPlacement == null) {
                fail(work, "附近没有可安全放置工作站的位置", "WORKSTATION_PLACEMENT_BLOCKED");
                return null;
            }
        }
        if (!level.getBlockState(work.pendingWorkstationPlacement).canBeReplaced()) {
            if (!prepareDeepMiningWorkstationAlcove(work, work.pendingWorkstationPlacement)) return null;
        }
        if (!approach(work, work.pendingWorkstationPlacement, 3.2, 1.08)) return null;
        if (work.ticks - work.lastActionTick < 8) return null;

        BlockPos placement = work.pendingWorkstationPlacement.immutable();
        ItemStack stack = sourceSlot >= 0
            ? npc.inventory().getStackInSlot(sourceSlot)
            : new ItemStack(workstationItem);
        InteractionResult result = proxy.useItemOn(placement.below(), Direction.UP, stack, sourceSlot);
        work.lastActionTick = work.ticks;
        boolean placed = id(level.getBlockState(placement).getBlock()).equals(workstationId);
        boolean placedDirectly = false;
        if (WorkstationPolicy.shouldAttemptDirectPlacement(placed)) {
            placed = placeWorkstationDirectly(level, placement, workstationId, workstationItem, sourceSlot, fallback);
            placedDirectly = placed;
        }
        if (!placed || !id(level.getBlockState(placement).getBlock()).equals(workstationId)) {
            work.skippedWorkstationTargets.add(placement);
            work.pendingWorkstationPlacement = null;
            work.stalledTicks = 0;
            work.lastDistance = -1.0D;
            progress(work, activeProgress(work),
                "当前工作站放置点被占用或拒绝，正在改用附近其他安全位置");
            return null;
        }
        if (sourceSlot < 0 && !npc.creativeResources() && fallback != null
            && result.consumesAction() && !placedDirectly) {
            consumeMatching(fallback.selector(), fallback.count());
        }
        work.taskOwnedWorkstation = placement.immutable();
        work.taskOwnedWorkstationId = workstationId;
        work.pendingWorkstationPlacement = null;
        npc.swing(InteractionHand.MAIN_HAND);
        progress(work, activeProgress(work), "已制作并安全放置 " + workstationId);
        return placement;
    }

    private boolean prepareDeepMiningWorkstationAlcove(ActiveWork work, BlockPos placement) {
        if (work.deepMiningPhase.isBlank() || !mayBreakDeepMiningAccess(placement)) {
            work.skippedWorkstationTargets.add(placement.immutable());
            work.pendingWorkstationPlacement = null;
            return false;
        }
        if (work.ticks - work.lastActionTick < 8) return false;
        BlockState state = npc.level().getBlockState(placement);
        int toolSlot = bestToolSlot(state);
        if (state.requiresCorrectToolForDrops()
            && (toolSlot < 0 || !npc.inventory().getStackInSlot(toolSlot).isCorrectToolForDrops(state))) {
            prepareGatherTool(work, state, work.deepMiningItemId);
            return false;
        }
        if (toolSlot >= 0) equipMainHand(toolSlot);
        work.lastActionTick = work.ticks;
        npc.getLookControl().setLookAt(Vec3.atCenterOf(placement));
        npc.swing(InteractionHand.MAIN_HAND, true);
        if (!proxy.breakBlock(
            placement,
            toolSlot >= 0 ? CodexNpcEntity.MAIN_HAND_SLOT : -1
        )) {
            if (++work.failedActions >= 3) {
                work.skippedWorkstationTargets.add(placement.immutable());
                work.pendingWorkstationPlacement = null;
                work.failedActions = 0;
            }
            return false;
        }
        npc.absorbNearbyItemsAt(Vec3.atCenterOf(placement), 3.0D);
        recordInventoryAction(work, "deep-mining-workstation-alcove");
        work.deepMiningBrokenBlocks++;
        work.failedActions = 0;
        progress(work, activeProgress(work), "已在矿道侧方挖出临时工作站凹槽");
        return false;
    }

    /**
     * Keeps temporary crafting tables and furnaces outside a pending build's
     * horizontal footprint so the resumed blueprint cannot collide with the
     * prerequisite workstation it just placed.
     */
    private BlockPos findSafeTaskWorkstationPlacement(ActiveWork work, ServerLevel level) {
        NpcHomeStorage.Home local = new NpcHomeStorage.Home(level.dimension(), npc.blockPosition(), true);
        Predicate<BlockPos> reachable = position -> !work.skippedWorkstationTargets.contains(position)
            && isReachableWorkstationPlacement(position, 3.2);
        if (!work.deepMiningPhase.isBlank()) {
            BlockPos miningPlacement = findDeepMiningWorkstationPlacement(work, level);
            if (miningPlacement != null) return miningPlacement;
        }
        if (!work.kind.equals("build") || work.buildOrigin == null || work.buildBlocks == null) {
            return NpcHomeStorage.findSafePlacement(level, local, npc.blockPosition(), 12, reachable);
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (var element : work.buildBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            if (string(entry, "blockId", "minecraft:air").equals("minecraft:air")
                || !entry.has("position") || !entry.get("position").isJsonObject()) continue;
            BlockPos absolute = work.buildOrigin.offset(block(entry.getAsJsonObject("position")));
            minX = Math.min(minX, absolute.getX());
            maxX = Math.max(maxX, absolute.getX());
            minZ = Math.min(minZ, absolute.getZ());
            maxZ = Math.max(maxZ, absolute.getZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return NpcHomeStorage.findSafePlacement(level, local, npc.blockPosition(), 6, reachable);
        }

        final int footprintMinX = minX;
        final int footprintMaxX = maxX;
        final int footprintMinZ = minZ;
        final int footprintMaxZ = maxZ;
        Predicate<BlockPos> outsideFootprint = position -> position.getX() < footprintMinX
            || position.getX() > footprintMaxX
            || position.getZ() < footprintMinZ
            || position.getZ() > footprintMaxZ;
        Predicate<BlockPos> usablePlacement = outsideFootprint.and(reachable);
        BlockPos npcPosition = npc.blockPosition();
        int clampedX = Math.max(footprintMinX, Math.min(footprintMaxX, npcPosition.getX()));
        int clampedZ = Math.max(footprintMinZ, Math.min(footprintMaxZ, npcPosition.getZ()));
        List<BlockPos> anchors = new ArrayList<>(List.of(
            new BlockPos(footprintMinX - 2, npcPosition.getY(), clampedZ),
            new BlockPos(footprintMaxX + 2, npcPosition.getY(), clampedZ),
            new BlockPos(clampedX, npcPosition.getY(), footprintMinZ - 2),
            new BlockPos(clampedX, npcPosition.getY(), footprintMaxZ + 2)
        ));
        anchors.sort(Comparator.comparingDouble(position -> position.distSqr(npcPosition)));
        for (BlockPos anchor : anchors) {
            BlockPos placement = NpcHomeStorage.findSafePlacement(
                level,
                local,
                anchor,
                4,
                usablePlacement
            );
            if (placement != null) return placement;
        }
        return NpcHomeStorage.findSafePlacement(
            level,
            local,
            npcPosition,
            12,
            usablePlacement
        );
    }

    private BlockPos findDeepMiningWorkstationPlacement(ActiveWork work, ServerLevel level) {
        BlockPos anchor = work.deepMiningLastSafeStand == null
            ? npc.blockPosition()
            : work.deepMiningLastSafeStand;
        Direction travel = work.deepMiningDirection;
        if (("branching".equals(work.deepMiningPhase) || "returning".equals(work.deepMiningPhase))
            && work.deepMiningLanding != null) {
            travel = work.deepMiningBranchProgress > 0
                ? DeepMiningPolicy.branchDirection(work.deepMiningDirection, work.deepMiningBranchIndex)
                : work.deepMiningDirection;
        }
        travel = DeepMiningPolicy.retainedDirection(travel, Direction.NORTH);
        Direction clockwise = travel.getClockWise();
        Direction counterClockwise = travel.getCounterClockWise();
        List<BlockPos> candidates = new ArrayList<>(List.of(
            anchor.relative(clockwise),
            anchor.relative(counterClockwise),
            anchor.relative(travel.getOpposite()).relative(clockwise),
            anchor.relative(travel.getOpposite()).relative(counterClockwise),
            anchor.relative(travel.getOpposite(), 2).relative(clockwise),
            anchor.relative(travel.getOpposite(), 2).relative(counterClockwise)
        ));
        Set<BlockPos> passage = requiredDeepMiningPassage(work, null);
        Direction rankedTravel = travel;
        candidates.sort(Comparator
            .comparingInt((BlockPos position) ->
                WorkstationPolicy.miningPlacementRank(position, anchor, rankedTravel))
            .thenComparingDouble(position -> position.distSqr(npc.blockPosition())));
        for (BlockPos candidate : candidates) {
            if (work.skippedWorkstationTargets.contains(candidate)
                || !level.hasChunkAt(candidate)
                || !level.getWorldBorder().isWithinBounds(candidate)
                || !WorkstationPolicy.allowsTemporaryPlacement(
                    candidate,
                    npc.blockPosition(),
                    work.targetBlock,
                    passage
                )
                || !level.getFluidState(candidate).isEmpty()
                || !hasSolidSafeMiningFloor(candidate.below())) continue;
            BlockState state = level.getBlockState(candidate);
            if (!state.canBeReplaced() && !mayBreakDeepMiningAccess(candidate)) continue;
            return candidate.immutable();
        }
        return null;
    }

    private boolean isReachableWorkstationPlacement(BlockPos position, double reach) {
        if (npc.position().distanceTo(Vec3.atCenterOf(position)) <= reach) return true;
        Path path = npc.getNavigation().createPath(position, 0);
        return pathGetsWithinWorkstationReach(path, position, reach);
    }

    private boolean placeWorkstationDirectly(
        ServerLevel level,
        BlockPos placement,
        String workstationId,
        Item workstationItem,
        int sourceSlot,
        WorkstationPolicy.MaterialCost fallback
    ) {
        if (!(workstationItem instanceof BlockItem blockItem)) return false;
        if (!level.hasChunkAt(placement) || !level.getWorldBorder().isWithinBounds(placement)) return false;
        if (id(level.getBlockState(placement).getBlock()).equals(workstationId)) return true;
        BlockState current = level.getBlockState(placement);
        BlockPos support = placement.below();
        if (!current.canBeReplaced() || !level.getFluidState(placement).isEmpty()) return false;
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return false;
        if (!npc.creativeResources()) {
            if (sourceSlot >= 0) {
                if (npc.inventory().getStackInSlot(sourceSlot).isEmpty()) return false;
            } else if (fallback == null || inventoryCount(fallback.selector()) < fallback.count()) {
                return false;
            }
        }
        if (!level.setBlock(placement, blockItem.getBlock().defaultBlockState(), Block.UPDATE_ALL)) return false;
        if (!id(level.getBlockState(placement).getBlock()).equals(workstationId)) return false;
        if (!npc.creativeResources()) {
            if (sourceSlot >= 0) {
                ItemStack source = npc.inventory().getStackInSlot(sourceSlot);
                source.shrink(1);
                npc.inventory().setStackInSlot(sourceSlot, source);
            } else if (fallback != null) {
                consumeMatching(fallback.selector(), fallback.count());
            }
        }
        npc.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    private boolean craftOneMatching(ActiveWork work, String requestedId) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        for (Recipe<?> candidate : npc.level().getRecipeManager().getRecipes()) {
            if (candidate.getType() != RecipeType.CRAFTING || !candidate.canCraftInDimensions(2, 2)) continue;
            ItemStack output = candidate.getResultItem(npc.level().registryAccess()).copy();
            if (output.isEmpty() || !selector.matches(output)) continue;
            List<Integer> allocation = allocateIngredients(candidate.getIngredients());
            if (allocation == null && craftMissingIngredient(work, candidate, 0, new HashSet<>())) return true;
            allocation = allocateIngredients(candidate.getIngredients());
            if (allocation == null) continue;
            if (work.ticks - work.lastActionTick < 8) return true;
            if (!canInsert(output)) {
                fail(work, "NPC 背包没有空间接收工作站前置材料 " + itemId(output), "INVENTORY_FULL");
                return true;
            }
            work.lastActionTick = work.ticks;
            consumeIngredients(allocation);
            npc.insert(output);
            recordInventoryAction(work, "craft-output");
            npc.swing(InteractionHand.MAIN_HAND);
            npc.addExhaustion(0.02F);
            progress(work, activeProgress(work), "先制作工作站材料 " + itemId(output));
            return true;
        }
        return false;
    }

    private boolean consumeMatching(String requestedId, int requested) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        if (inventoryCount(requestedId) < requested) return false;
        int remaining = requested;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE && remaining > 0; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!selector.matches(stack)) continue;
            int count = Math.min(remaining, stack.getCount());
            stack.shrink(count);
            npc.inventory().setStackInSlot(slot, stack);
            remaining -= count;
        }
        return remaining == 0;
    }

    private boolean containerContains(Container container, ResourceSelector selector) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (selector.matches(container.getItem(slot))) return true;
        }
        return false;
    }

    private boolean containerHasSpace(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || stack.getCount() < Math.min(stack.getMaxStackSize(), container.getMaxStackSize())) return true;
        }
        return false;
    }

    private BlockPos findStorage(int radius, int verticalRadius) {
        return findBlockAt(position -> {
            BlockEntity blockEntity = npc.level().getBlockEntity(position);
            if (!(blockEntity instanceof Container) || blockEntity instanceof AbstractFurnaceBlockEntity) return false;
            String blockId = id(npc.level().getBlockState(position).getBlock());
            return blockId.endsWith("chest") || blockId.endsWith("barrel") || blockId.endsWith("shulker_box");
        }, radius, verticalRadius);
    }

    private BlockPos findBlock(Predicate<BlockState> predicate, int radius, int verticalRadius) {
        return findBlockAt(position -> predicate.test(npc.level().getBlockState(position)), radius, verticalRadius);
    }

    private BlockPos findBlockAt(Predicate<BlockPos> predicate, int radius, int verticalRadius) {
        BlockPos origin = npc.blockPosition();
        for (int ring = 0; ring <= radius; ring++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int y = -verticalRadius; y <= verticalRadius; y++) {
                for (int x = -ring; x <= ring; x++) {
                    for (int z = -ring; z <= ring; z++) {
                        if (ring > 0 && Math.abs(x) != ring && Math.abs(z) != ring) continue;
                        BlockPos position = origin.offset(x, y, z);
                        double distance = position.distSqr(origin);
                        if (distance >= bestDistance || !predicate.test(position)) continue;
                        best = position.immutable();
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private Direction findSupport(BlockPos target) {
        for (Direction direction : Direction.values()) {
            BlockPos support = target.relative(direction);
            BlockState state = npc.level().getBlockState(support);
            if (BuildPlacementPolicy.isClickableSupport(
                state.isAir(),
                state.canBeReplaced(),
                state.getCollisionShape(npc.level(), support).isEmpty()
            )) return direction;
        }
        return null;
    }

    private int bestToolSlot(BlockState state) {
        int best = -1;
        float speed = 1.0F;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            float candidate = stack.getDestroySpeed(state) + (stack.isCorrectToolForDrops(state) ? 100.0F : 0.0F);
            if (candidate > speed) {
                speed = candidate;
                best = slot;
            }
        }
        return best;
    }

    private int bestGatherToolSlot(BlockState state, ResourceSelector selector) {
        if (state.is(Blocks.COBWEB) && selector.isExact("minecraft:string")) {
            int sword = findUsableSwordSlot();
            if (sword >= 0) return sword;
        }
        return bestToolSlot(state);
    }

    private ItemStack bestGatherToolForState(BlockState state, ResourceSelector selector) {
        int slot = bestGatherToolSlot(state, selector);
        return slot >= 0
            ? npc.inventory().getStackInSlot(slot)
            : npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT);
    }

    private ItemStack bestToolForState(BlockState state) {
        int slot = bestToolSlot(state);
        if (slot >= 0) return npc.inventory().getStackInSlot(slot);
        return npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT);
    }

    private ItemStack bestToolStack() {
        ItemStack best = ItemStack.EMPTY;
        float speed = 1.0F;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            float candidate = stack.isDamageableItem() ? 2.0F : 1.0F;
            if (candidate > speed) {
                speed = candidate;
                best = stack;
            }
        }
        return best;
    }

    private void equipMainHand(int sourceSlot) {
        if (sourceSlot == CodexNpcEntity.MAIN_HAND_SLOT) return;
        ItemStack selected = npc.inventory().getStackInSlot(sourceSlot);
        ItemStack current = npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT);
        npc.inventory().setStackInSlot(sourceSlot, current);
        npc.inventory().setStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT, selected);
    }

    private void equipBestWeapon() {
        int bestSlot = -1;
        int bestScore = weaponScore(npc.inventory().getStackInSlot(CodexNpcEntity.MAIN_HAND_SLOT));
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            int score = weaponScore(npc.inventory().getStackInSlot(slot));
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) equipMainHand(bestSlot);
    }

    private int weaponScore(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        String value = itemId(stack);
        if (!value.endsWith("_sword") && !value.endsWith("_axe") && !value.endsWith("trident")) return 0;
        double damage = stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
            .get(Attributes.ATTACK_DAMAGE).stream().mapToDouble(AttributeModifier::getAmount).sum();
        double speed = stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
            .get(Attributes.ATTACK_SPEED).stream().mapToDouble(AttributeModifier::getAmount).sum();
        int enchantments = EnchantmentHelper.getEnchantments(stack).entrySet().stream()
            .mapToInt(entry -> (entry.getKey().isCurse() ? -4 : 2) * entry.getValue()).sum();
        double durability = stack.isDamageableItem()
            ? (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage()
            : 1.0D;
        return WeaponSelectionPolicy.score(damage, speed, enchantments, durability);
    }

    private int findDragonFoodSlot(Entity target, boolean healing) {
        int fallback = -1;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if ((healing && itemId(stack).equals("saintsdragons:hearty_dragon_meal"))
                || invokeBoolean(target, "isFood", stack)
                || invokeBoolean(target, healing ? "isFavoriteFood" : "isGeneralFood", stack)
                || invokeBoolean(target, "isSpecialFood", stack)
                || invokeBoolean(target, "isFavoriteFood", stack)) return slot;
            if (fallback < 0 && stack.getFoodProperties(npc) != null) fallback = slot;
        }
        return fallback;
    }

    private DragonCareAcceptancePolicy.State dragonCareBefore(ActiveWork work) {
        return new DragonCareAcceptancePolicy.State(
            work.dragonCareIdentity,
            true,
            work.dragonCareItemCount,
            work.dragonCareHealth,
            work.dragonCareFood,
            work.dragonCareHappiness,
            work.dragonCareOwned,
            -1.0D
        );
    }

    private DragonCareAcceptancePolicy.State dragonCareState(
        Entity target,
        int slot,
        ServerPlayer owner
    ) {
        int items = slot >= 0 ? npc.inventory().getStackInSlot(slot).getCount() : 0;
        double health = target instanceof LivingEntity living ? living.getHealth() : -1.0D;
        double food = numberValue(invokeNoArg(target, "getHunger"));
        if (food < 0.0D) {
            Object needs = invokeNoArg(target, "getNeedsSystem");
            food = numberValue(needs == null ? null : invokeNoArg(needs, "getFoodLevel"));
            double saturation = numberValue(needs == null ? null : invokeNoArg(needs, "getSaturationLevel"));
            if (food >= 0.0D && saturation >= 0.0D) food += saturation;
        }
        double happiness = numberValue(invokeNoArg(target, "getHappiness"));
        DragonAdapter adapter = DragonAdapters.forEntity(target);
        boolean owned = adapter != null && owner != null && adapter.isOwnedBy(target, owner);
        return new DragonCareAcceptancePolicy.State(
            target.getUUID().toString(),
            target.isAlive(),
            items,
            health,
            food,
            happiness,
            owned,
            -1.0D
        );
    }

    private double rawDragonFoodLevel(Entity target) {
        double food = numberValue(invokeNoArg(target, "getHunger"));
        if (food >= 0.0D) return food;
        Object needs = invokeNoArg(target, "getNeedsSystem");
        return numberValue(needs == null ? null : invokeNoArg(needs, "getFoodLevel"));
    }

    private DragonCareAcceptancePolicy.State eggCareState(
        String identity,
        boolean present,
        double progressValue
    ) {
        return new DragonCareAcceptancePolicy.State(
            identity, present, 0, -1.0D, -1.0D, -1.0D, false, progressValue
        );
    }

    private double dragonEggProgress(Object egg) {
        if (egg == null) return -1.0D;
        Object current = invokeNoArg(egg, "getCurrentHatchTime");
        Object total = invokeNoArg(egg, "getTotalHatchTime");
        if (current instanceof Number currentNumber && total instanceof Number totalNumber) {
            double currentValue = currentNumber.doubleValue();
            double totalValue = totalNumber.doubleValue();
            return Double.isFinite(currentValue) && Double.isFinite(totalValue)
                ? Math.max(0.0D, totalValue - currentValue)
                : -1.0D;
        }
        return numberValue(invokeNoArg(egg, "getHatchProgress"));
    }

    private double numberValue(Object value) {
        if (!(value instanceof Number number)) return -1.0D;
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : -1.0D;
    }

    private Entity findDragonEggEntity(ActiveWork work) {
        String targetId = string(work.spec, "targetId", "");
        if (work.dragonTargetId != null && npc.level().getServer() != null) {
            for (ServerLevel level : npc.level().getServer().getAllLevels()) {
                Entity locked = level.getEntity(work.dragonTargetId);
                if (locked != null && locked.isAlive() && isDragonEgg(locked)) return locked;
            }
            work.dragonTargetId = null;
        }
        UUID explicitId = parseUuid(targetId);
        double radius = Math.max(24.0D, config.observeRadius);
        Entity result = npc.level().getEntities(
            npc,
            npc.getBoundingBox().inflate(radius),
            entity -> entity.isAlive()
                && isDragonEgg(entity)
                && (targetId.isBlank()
                    || entity.getUUID().equals(explicitId)
                    || id(entity).equalsIgnoreCase(targetId)
                    || entity.getName().getString().equalsIgnoreCase(targetId))
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
        if (result != null) work.dragonTargetId = result.getUUID();
        return result;
    }

    private BlockPos findDragonEggBlock(ActiveWork work) {
        String targetId = string(work.spec, "targetId", "");
        if (work.workstation != null && dragonEggBlockMatches(work.workstation, targetId)) {
            return work.workstation;
        }
        work.workstation = null;
        int radius = Math.max(8, Math.min(64, (int) Math.ceil(config.observeRadius)));
        int vertical = 12;
        BlockPos center = npc.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
            center.offset(-radius, -vertical, -radius),
            center.offset(radius, vertical, radius)
        )) {
            if (!dragonEggBlockMatches(candidate, targetId)) continue;
            double distance = candidate.distSqr(center);
            if (distance < bestDistance) {
                best = candidate.immutable();
                bestDistance = distance;
            }
        }
        work.workstation = best;
        return best;
    }

    private boolean dragonEggBlockMatches(BlockPos position, String targetId) {
        BlockState state = npc.level().getBlockState(position);
        String blockId = id(state.getBlock());
        BlockEntity blockEntity = npc.level().getBlockEntity(position);
        String value = (blockId + " " + (blockEntity == null ? "" : blockEntity.getClass().getName()))
            .toLowerCase(Locale.ROOT);
        boolean egg = value.contains("egg")
            && (value.contains("dragon") || blockId.startsWith("saintsdragons:"));
        return egg && (targetId.isBlank() || blockId.equalsIgnoreCase(targetId));
    }

    private Entity resolveDragonTarget(ActiveWork work) {
        String action = string(work.spec, "action", "observe");
        String targetId = work.spec.has("targetId") ? work.spec.get("targetId").getAsString() : "";
        ServerPlayer owner = npc.owner();
        boolean ownerOnly = DragonTargetSelectionPolicy.requiresOwner(action);
        if (!action.equals("care-for-egg") && work.dragonTargetId != null) {
            Entity locked = findDragonByUuid(work.dragonTargetId, true);
            if (locked != null && (!ownerOnly || owner == null || isOwnedDragon(locked, owner))) {
                if (isOwnedDragon(locked, owner)) rememberDragon(locked);
                return locked;
            }
            work.dragonTargetId = null;
        }
        Entity mounted = ridingDragon();
        if (mounted != null && (targetId.isBlank() || mounted.getUUID().toString().equalsIgnoreCase(targetId))) {
            return selectDragonTarget(work, mounted);
        }
        Entity explicit = findDragonByUuid(parseUuid(targetId), true);
        if (explicit != null) {
            return selectDragonTarget(work, explicit);
        }
        UUID rememberedId = npc.boundDragonUuid();
        Entity remembered = targetId.isBlank() && !action.equals("care-for-egg")
            ? findDragonByUuid(rememberedId, true)
            : null;
        if (remembered != null && ownerOnly && isOwnedDragon(remembered, owner)) {
            return selectDragonTarget(work, remembered);
        }
        double radius = action.equals("recall") ? Math.max(128.0, config.observeRadius) : config.observeRadius;
        Entity nearby = npc.level().getEntities(npc, npc.getBoundingBox().inflate(radius), Entity::isAlive).stream()
            .filter(entity -> action.equals("care-for-egg") ? isDragonEgg(entity) : isDragon(entity))
            .filter(entity -> targetId.isBlank() || entity.getUUID().toString().equalsIgnoreCase(targetId)
                || id(entity).equalsIgnoreCase(targetId) || entity.getName().getString().equalsIgnoreCase(targetId))
            .filter(entity -> !ownerOnly || owner == null || isOwnedDragon(entity, owner))
            .min(Comparator
                .comparingInt((Entity entity) -> DragonTargetSelectionPolicy.rank(
                    rememberedId != null && rememberedId.equals(entity.getUUID()),
                    isOwnedDragon(entity, owner)
                ))
                .thenComparingDouble(npc::distanceToSqr))
            .orElse(null);
        if (nearby != null) {
            return selectDragonTarget(work, nearby);
        }
        if (remembered != null && (!ownerOnly || owner == null || isOwnedDragon(remembered, owner))) {
            return selectDragonTarget(work, remembered);
        }
        return null;
    }

    private boolean isOwnedDragon(Entity dragon, ServerPlayer owner) {
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        return adapter != null && owner != null && adapter.isOwnedBy(dragon, owner);
    }

    private Entity selectDragonTarget(ActiveWork work, Entity target) {
        if (isDragon(target)) {
            if (work.dragonTargetId == null) work.dragonTargetId = target.getUUID();
            if (isOwnedDragon(target, npc.owner())) rememberDragon(target);
        }
        return target;
    }

    private LivingEntity resolveDragonCombatTarget(ActiveWork work, ServerPlayer owner) {
        LivingEntity lastHurt = owner.getLastHurtMob();
        LivingEntity lastAttacker = owner.getLastHurtByMob();
        if (work.dragonCombatTargetId == null) {
            if (isInitialDragonCombatTarget(lastHurt, owner)) return lastHurt;
            return isInitialDragonCombatTarget(lastAttacker, owner) ? lastAttacker : null;
        }
        if (lastHurt != null && work.dragonCombatTargetId.equals(lastHurt.getUUID())) return lastHurt;
        if (lastAttacker != null && work.dragonCombatTargetId.equals(lastAttacker.getUUID())) return lastAttacker;
        if (npc.level().getServer() == null) return null;
        for (ServerLevel level : npc.level().getServer().getAllLevels()) {
            Entity entity = level.getEntity(work.dragonCombatTargetId);
            if (entity instanceof LivingEntity living) return living;
        }
        return null;
    }

    private boolean isInitialDragonCombatTarget(LivingEntity target, ServerPlayer owner) {
        return target != null && isValidAssistTarget(owner, target);
    }

    private Entity findDragonByUuid(UUID uuid, boolean loadRememberedChunk) {
        if (uuid == null || npc.level().getServer() == null) return null;
        for (ServerLevel level : npc.level().getServer().getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && entity.isAlive() && isDragon(entity)) return entity;
        }
        if (!loadRememberedChunk || !uuid.equals(npc.boundDragonUuid())) return null;
        ResourceLocation dimensionId = ResourceLocation.tryParse(npc.boundDragonDimension());
        BlockPos rememberedPosition = npc.boundDragonPosition();
        if (dimensionId == null || rememberedPosition == null) return null;
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel level = npc.level().getServer().getLevel(dimension);
        if (level == null) return null;
        ChunkPos chunk = new ChunkPos(rememberedPosition);
        level.getChunk(rememberedPosition);
        level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, chunk, 2, npc.getUUID());
        Entity entity = level.getEntity(uuid);
        return entity != null && entity.isAlive() && isDragon(entity) ? entity : null;
    }

    private void rememberDragon(Entity dragon) {
        npc.rememberDragon(dragon);
        if (dragon.level() instanceof ServerLevel level) {
            level.getChunkSource().addRegionTicket(TASK_CHUNK_TICKET, new ChunkPos(dragon.blockPosition()), 2, npc.getUUID());
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isDragon(Entity entity) {
        String value = (id(entity) + " " + entity.getClass().getName()).toLowerCase(Locale.ROOT);
        return value.contains("dragon") && !value.contains("egg");
    }

    private boolean isDragonEgg(Entity entity) {
        String value = (id(entity) + " " + entity.getClass().getName()).toLowerCase(Locale.ROOT);
        return value.contains("dragon") && value.contains("egg");
    }

    private Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private boolean invokeOneArg(Object target, String name, Object argument) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (!(parameter.isInstance(argument)
                || parameter == int.class && argument instanceof Integer
                || parameter == boolean.class && argument instanceof Boolean
                || parameter == float.class && argument instanceof Float
                || parameter == double.class && argument instanceof Double
                || parameter == long.class && argument instanceof Long)) continue;
            try {
                method.invoke(target, argument);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean invokeBoolean(Object target, String name, Object argument) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(argument)) continue;
            try {
                return Boolean.TRUE.equals(method.invoke(target, argument));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean invokeEnumOneArg(Object target, String name, String constant) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (!parameter.isEnum()) continue;
            for (Object value : parameter.getEnumConstants()) {
                if (!(value instanceof Enum<?> enumValue) || !enumValue.name().equals(constant)) continue;
                try {
                    method.invoke(target, value);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean hasSafeFurnaceFuel() {
        return findSafeFurnaceFuelSlot() >= 0;
    }

    private boolean beginPreferredCoalFuelAcquisition(
        ActiveWork work,
        int remainingSmelts,
        String purpose
    ) {
        int fuelItems = SmeltingPrerequisitePolicy.preferredCoalFuelItems(remainingSmelts);
        if (fuelItems <= 0) return false;
        ResourceSelector coal = ResourceSelector.parse("#minecraft:coals");
        HomeRetrieveInspection home = inspectHomeRetrieve(
            coal,
            fuelItems,
            HomeStoragePolicy.DEFAULT_RADIUS
        );
        BlockPos nearby = home.available() > 0
            ? null
            : findGatherBlock(coal, GATHER_LOCAL_SEARCH_RADIUS, 24, Set.of());
        if (home.available() <= 0 && nearby == null) return false;
        return beginBuildMaterialGoal(
            work,
            "minecraft:coal",
            "#minecraft:coals",
            inventoryCount("#minecraft:coals") + fuelItems,
            purpose + "缺少安全燃料，优先取得 " + fuelItems + " 个煤炭"
        );
    }

    private boolean shouldSupplyFurnaceFuel(BlockPos position, ItemStack fuel) {
        BlockState state = npc.level().getBlockState(position);
        boolean lit = state.hasProperty(AbstractFurnaceBlock.LIT)
            && state.getValue(AbstractFurnaceBlock.LIT);
        return SmeltingPrerequisitePolicy.shouldSupplyFuel(fuel.isEmpty(), lit);
    }

    private int findSafeFurnaceFuelSlot() {
        int bestSlot = -1;
        int bestPriority = Integer.MAX_VALUE;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            int priority = safeFurnaceFuelPriority(stack);
            if (priority < bestPriority) {
                bestPriority = priority;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private int safeFurnaceFuelPriority(ItemStack stack) {
        return SmeltingPrerequisitePolicy.safeFuelPriority(
            itemId(stack),
            !stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack),
            stack.isDamageableItem(),
            stack.is(ItemTags.LOGS),
            stack.is(ItemTags.PLANKS)
        );
    }

    private ItemStack extractFuel() {
        int slot = findSafeFurnaceFuelSlot();
        if (slot < 0) return ItemStack.EMPTY;
        ItemStack stack = npc.inventory().getStackInSlot(slot);
        ItemStack result = stack.split(1);
        npc.inventory().setStackInSlot(slot, stack);
        if (!result.isEmpty()) recordInventoryAction("furnace-fuel");
        return result;
    }

    private ItemStack extract(String requestedId, int count) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE && count > 0; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!selector.matches(stack)
                || !result.isEmpty() && !ItemStack.isSameItemSameTags(result, stack)) continue;
            int take = Math.min(count, stack.getCount());
            if (result.isEmpty()) result = stack.copyWithCount(take);
            else result.grow(take);
            stack.shrink(take);
            npc.inventory().setStackInSlot(slot, stack);
            count -= take;
        }
        return result;
    }

    private boolean canInsert(ItemStack stack) {
        ItemStack remainder = stack.copy();
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE && !remainder.isEmpty(); slot++) {
            remainder = npc.inventory().insertItem(slot, remainder, true);
        }
        return remainder.isEmpty();
    }

    private int inventoryCount(String requestedId) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        int total = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (selector.matches(stack)) total += stack.getCount();
        }
        return total;
    }

    private int inventoryTotal() {
        int total = 0;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) total += npc.inventory().getStackInSlot(slot).getCount();
        return total;
    }

    private int findItemSlot(String requestedId) {
        ResourceSelector selector = ResourceSelector.parse(requestedId);
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            if (selector.matches(npc.inventory().getStackInSlot(slot))) return slot;
        }
        return -1;
    }

    private int findFoodSlot(String requestedId) {
        ResourceSelector selector = requestedId.isBlank() ? null : ResourceSelector.parse(requestedId);
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty() || stack.getFoodProperties(npc) == null) continue;
            if (selector == null || selector.matches(stack)) return slot;
        }
        return -1;
    }

    private Item item(String value) {
        try {
            return ResourceSelector.parse(value).firstItem();
        } catch (RuntimeException ignored) {
            return Items.AIR;
        }
    }

    private String itemId(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private String id(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private Vec3 target(JsonObject value) {
        return new Vec3(value.get("x").getAsDouble(), value.get("y").getAsDouble(), value.get("z").getAsDouble());
    }

    private BlockPos block(JsonObject value) {
        return BlockPos.containing(value.get("x").getAsDouble(), value.get("y").getAsDouble(), value.get("z").getAsDouble());
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    /** Returns exact engine/inventory facts only while a gather phase is active. */
    private TaskProgressCounts progressCounts(ActiveWork work) {
        String selector;
        int completed;
        int target;
        int inventoryAtStart;
        if (hasCraftGatherPrerequisite(work)) {
            selector = work.craftGatherItemId;
            completed = work.craftGatherCompleted;
            target = work.craftGatherCount;
            inventoryAtStart = work.craftGatherInitialCount;
        } else if ("gather".equals(work.kind) && work.initialized) {
            selector = string(work.spec, "itemId", "");
            completed = work.completed;
            target = work.requestedCount > 0 ? work.requestedCount : integer(work.spec, "count", 0);
            inventoryAtStart = work.initialCount;
        } else {
            return null;
        }
        if (selector == null || selector.isBlank() || completed < 0 || target <= 0) return null;
        int retained = GatherProgressPolicy.retained(completed, inventoryAtStart, inventoryCount(selector));
        return new TaskProgressCounts(completed, target, retained);
    }

    private void sendProgressUpdate(ActiveWork work, double value, String message, String phase) {
        if (work.id.startsWith("local:")) return;
        CodexNetwork.sendProgress(npc, work.id, value, message, phase, progressCounts(work));
    }

    private void progress(ActiveWork work, double value, String message) {
        if (active != work) return;
        npc.setStatus(message);
        sendProgressUpdate(work, value, message, "active");
    }

    private void taskStatus(ActiveWork work, String message) {
        npc.setStatus(message);
        if (active != work || work.id.startsWith("local:")) return;
        if (work.lastProgressHeartbeatTick > 0 && work.ticks - work.lastProgressHeartbeatTick < 40) return;
        work.lastProgressHeartbeatTick = work.ticks;
        sendProgressUpdate(work, activeProgress(work), message, "active");
    }

    private void complete(ActiveWork work, String message) {
        suspendLifeInteraction(work);
        FurnaceRecoverySummary recovery = recoverAllTaskFurnaces(work, "task-complete");
        message += recovery.detail();
        TaskProgressCounts finalCounts = progressCounts(work);
        if (active == work) active = null;
        if (recoverableBuild == work) recoverableBuild = null;
        npc.getNavigation().stop();
        npc.setStatus(message);
        if (!work.id.startsWith("local:")) CodexNetwork.sendResult(npc, work.id, true, message, null, finalCounts);
        if (resumePausedWork()) return;
        Stance resume = work.resumeStance == Stance.WORK ? Stance.FOLLOW : work.resumeStance;
        setStance(resume);
    }

    private void completePersistentStance(ActiveWork work, String message) {
        npc.setStatus(message);
        CodexNetwork.sendResult(npc, work.id, true, message, null);
    }

    private void fail(ActiveWork work, String message, String code) {
        suspendLifeInteraction(work);
        FurnaceRecoverySummary recovery = recoverAllTaskFurnaces(work, "task-failed:" + code);
        message += recovery.detail();
        if ("build".equals(work.kind) && BuildFailureRecoveryPolicy.isRecoverable(code)) {
            work.failureCode = code;
            work.failureMessage = message;
            work.pauseReason = "失败点 " + work.buildIndex + " @ "
                + (work.targetBlock == null ? "未知位置" : work.targetBlock.toShortString())
                + "：" + code;
            recoverableBuild = work;
        } else if (recoverableBuild == work) {
            recoverableBuild = null;
        }
        if (active == work) active = null;
        if ("eat".equals(work.kind)) {
            npc.cancelManagedEating();
        }
        npc.getNavigation().stop();
        npc.setTarget(null);
        npc.setStatus(message);
        if (!work.id.startsWith("local:")) CodexNetwork.sendResult(npc, work.id, false, message, code);
        if (resumePausedWork()) return;
        Stance resume = work.resumeStance == Stance.WORK ? Stance.FOLLOW : work.resumeStance;
        setStance(resume);
    }

    private JsonObject observableTask(ActiveWork work, String phase, String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("id", work.id);
        result.addProperty("kind", work.kind);
        result.addProperty("phase", phase);
        result.addProperty("priority", work.priority);
        result.addProperty("progress", activeProgress(work));
        if (reason != null && !reason.isBlank()) result.addProperty("pauseReason", reason);
        return result;
    }

    private NpcTaskPersistence.WorkState toPersistentWork(ActiveWork work) {
        JsonObject checkpoint = new JsonObject();
        checkpoint.addProperty("ticks", work.ticks);
        checkpoint.addProperty("lastActionTick", work.lastActionTick);
        checkpoint.addProperty("lastProgressTick", work.lastProgressTick);
        checkpoint.addProperty("smeltStartedTick", work.smeltStartedTick);
        checkpoint.addProperty("noWorkTicks", work.noWorkTicks);
        checkpoint.addProperty("failedActions", work.failedActions);
        checkpoint.addProperty("stalledTicks", work.stalledTicks);
        checkpoint.addProperty("stableTicks", work.stableTicks);
        checkpoint.addProperty("dragonActionStartedTick", work.dragonActionStartedTick);
        checkpoint.addProperty("gatherPathFailures", work.gatherPathFailures);
        checkpoint.addProperty("lastGatherPathAttemptTick", work.lastGatherPathAttemptTick);
        checkpoint.addProperty("gatherStandPathCursor", work.gatherStandPathCursor);
        checkpoint.addProperty("buildPathFailures", work.buildPathFailures);
        checkpoint.addProperty("lastBuildPathAttemptTick", work.lastBuildPathAttemptTick);
        checkpoint.addProperty("buildStandPathCursor", work.buildStandPathCursor);
        checkpoint.addProperty("gatherSearchRadius", work.gatherSearchRadius);
        checkpoint.addProperty("gatherExcursions", work.gatherExcursions);
        checkpoint.addProperty("gatherTreeCluster", work.gatherTreeCluster);
        checkpoint.addProperty("gatherClusterReached", work.gatherClusterReached);
        checkpoint.addProperty("gatherAccessTarget", work.gatherAccessTarget);
        checkpoint.addProperty("lastSearchTick", work.lastSearchTick);
        checkpoint.addProperty("deepMiningPhase", work.deepMiningPhase);
        checkpoint.addProperty("deepMiningItemId", work.deepMiningItemId);
        checkpoint.addProperty("deepMiningTargetY", work.deepMiningTargetY);
        checkpoint.addProperty(
            "deepMiningDirection",
            DeepMiningPolicy.retainedDirection(work.deepMiningDirection, Direction.NORTH).getName()
        );
        checkpoint.addProperty("deepMiningPhaseStartedTick", work.deepMiningPhaseStartedTick);
        checkpoint.addProperty("deepMiningStaircaseStep", work.deepMiningStaircaseStep);
        checkpoint.addProperty("deepMiningBranchIndex", work.deepMiningBranchIndex);
        checkpoint.addProperty("deepMiningBranchProgress", work.deepMiningBranchProgress);
        checkpoint.addProperty("deepMiningRegionIndex", work.deepMiningRegionIndex);
        checkpoint.addProperty("deepMiningLastTorchProgress", work.deepMiningLastTorchProgress);
        checkpoint.addProperty("deepMiningBrokenBlocks", work.deepMiningBrokenBlocks);
        checkpoint.addProperty("deepMiningPlacedTorches", work.deepMiningPlacedTorches);
        checkpoint.addProperty("deepMiningBlockedTurns", work.deepMiningBlockedTurns);
        checkpoint.addProperty("deepMiningMarkerStage", work.deepMiningMarkerStage);
        checkpoint.addProperty("deepMiningEntrySearchIndex", work.deepMiningEntrySearchIndex);
        checkpoint.addProperty("deepMiningEntryTargetStartedTick", work.deepMiningEntryTargetStartedTick);
        checkpoint.addProperty("deepMiningPreflightComplete", work.deepMiningPreflightComplete);
        checkpoint.addProperty("deepMiningExcavationTarget", work.deepMiningExcavationTarget);
        checkpoint.addProperty("deepMiningResourceTargetStartedTick", work.deepMiningResourceTargetStartedTick);
        checkpoint.addProperty("deepMiningResourceChaseStartedTick", work.deepMiningResourceChaseStartedTick);
        checkpoint.addProperty("fishingCast", work.fishingCast);
        checkpoint.addProperty("fishingReadyTick", work.fishingReadyTick);
        checkpoint.addProperty("fishingStoragePhase", work.fishingStoragePhase);
        checkpoint.addProperty("initialCount", work.initialCount);
        checkpoint.addProperty("requestedCount", work.requestedCount);
        checkpoint.addProperty("completed", work.completed);
        checkpoint.addProperty("loaded", work.loaded);
        checkpoint.addProperty("sourceSlot", work.sourceSlot);
        checkpoint.addProperty("buildIndex", work.buildIndex);
        checkpoint.addProperty("startDistance", work.startDistance);
        checkpoint.addProperty("lastDistance", work.lastDistance);
        checkpoint.addProperty("dragonCareItemCount", work.dragonCareItemCount);
        checkpoint.addProperty("dragonCareHealth", work.dragonCareHealth);
        checkpoint.addProperty("dragonCareFood", work.dragonCareFood);
        checkpoint.addProperty("dragonCareHappiness", work.dragonCareHappiness);
        checkpoint.addProperty("dragonCareOwned", work.dragonCareOwned);
        checkpoint.addProperty("dragonCombatTargetDefeated", work.dragonCombatTargetDefeated);
        if (work.dragonCareIdentity != null) {
            checkpoint.addProperty("dragonCareIdentity", work.dragonCareIdentity);
        }
        checkpoint.addProperty("initialized", work.initialized);
        checkpoint.addProperty("requiresTable", work.requiresTable);
        checkpoint.addProperty("storageExpanded", work.storageExpanded);
        checkpoint.addProperty("smeltingWorkstationClaimed", work.smeltingWorkstationClaimed);
        checkpoint.addProperty("craftDeliveryPending", work.craftDeliveryPending);
        checkpoint.addProperty("bedPlacementPending", work.bedPlacementPending);
        checkpoint.addProperty("craftDelivered", work.craftDelivered);
        if (work.outputItemId != null) checkpoint.addProperty("outputItemId", work.outputItemId);
        if (work.craftGatherItemId != null) checkpoint.addProperty("craftGatherItemId", work.craftGatherItemId);
        if (work.dragonTargetId != null) checkpoint.addProperty("dragonTargetId", work.dragonTargetId.toString());
        if (work.dragonCombatTargetId != null) checkpoint.addProperty("dragonCombatTargetId", work.dragonCombatTargetId.toString());
        if (work.bedSheepTargetId != null) checkpoint.addProperty("bedSheepTargetId", work.bedSheepTargetId.toString());
        if (work.foodAnimalTargetId != null) checkpoint.addProperty("foodAnimalTargetId", work.foodAnimalTargetId.toString());
        if (work.ranchAnimalTargetId != null) checkpoint.addProperty("ranchAnimalTargetId", work.ranchAnimalTargetId.toString());
        if (work.ranchTemporaryKnotId != null) checkpoint.addProperty("ranchTemporaryKnotId", work.ranchTemporaryKnotId.toString());
        if (work.foodCookingInputId != null) checkpoint.addProperty("foodCookingInputId", work.foodCookingInputId);
        if (work.foodCookingOutputId != null) checkpoint.addProperty("foodCookingOutputId", work.foodCookingOutputId);
        checkpoint.addProperty("craftGatherCount", work.craftGatherCount);
        checkpoint.addProperty("craftGatherInitialCount", work.craftGatherInitialCount);
        checkpoint.addProperty("craftGatherCompleted", work.craftGatherCompleted);
        checkpoint.addProperty("craftGatherStartedTick", work.craftGatherStartedTick);
        checkpoint.addProperty("bedStoragePhase", work.bedStoragePhase);
        checkpoint.addProperty("bedSmeltLoaded", work.bedSmeltLoaded);
        checkpoint.addProperty("foodPhase", work.foodPhase);
        checkpoint.addProperty("ranchPhase", work.ranchPhase);
        checkpoint.addProperty("ranchLeadStorageChecked", work.ranchLeadStorageChecked);
        checkpoint.addProperty("ranchReachedOutsideGate", work.ranchReachedOutsideGate);
        checkpoint.addProperty("ranchExitStaged", work.ranchExitStaged);
        checkpoint.addProperty("foodCookingTargetCount", work.foodCookingTargetCount);
        checkpoint.addProperty("foodCookedCount", work.foodCookedCount);
        checkpoint.addProperty("foodTransferredCount", work.foodTransferredCount);
        checkpoint.addProperty("buildPhase", work.buildPhase);
        checkpoint.addProperty("buildPhaseStartedTick", work.buildPhaseStartedTick);
        checkpoint.addProperty("buildLastProgressTick", work.buildLastProgressTick);
        if (work.failureCode != null) checkpoint.addProperty("failureCode", work.failureCode);
        if (work.failureMessage != null) checkpoint.addProperty("failureMessage", work.failureMessage);
        checkpoint.add("buildMaterialGoals", buildMaterialGoals(work.buildMaterialGoals));
        checkpoint.add("furnaceTransactions", furnaceTransactions(work.furnaceTransactions));
        putVector(checkpoint, "destination", work.destination);
        putBlock(checkpoint, "targetBlock", work.targetBlock);
        putBlock(checkpoint, "lastTeleportTarget", work.lastTeleportTarget);
        putBlock(checkpoint, "workstation", work.workstation);
        putBlock(checkpoint, "taskOwnedWorkstation", work.taskOwnedWorkstation);
        if (work.taskOwnedWorkstationId != null) {
            checkpoint.addProperty("taskOwnedWorkstationId", work.taskOwnedWorkstationId);
        }
        putBlock(checkpoint, "buildOrigin", work.buildOrigin);
        putBlock(checkpoint, "bedPlacementFoot", work.bedPlacementFoot);
        putBlock(checkpoint, "ranchPenCenter", work.ranchPenCenter);
        putBlock(checkpoint, "deepMiningEntrance", work.deepMiningEntrance);
        putBlock(checkpoint, "deepMiningLanding", work.deepMiningLanding);
        putBlock(checkpoint, "deepMiningLastSafeStand", work.deepMiningLastSafeStand);
        putBlock(checkpoint, "deepMiningCaveTarget", work.deepMiningCaveTarget);
        putBlock(checkpoint, "deepMiningResourceTimedTarget", work.deepMiningResourceTimedTarget);
        if (work.bedPlacementFacing != null) checkpoint.addProperty("bedPlacementFacing", work.bedPlacementFacing.getName());
        checkpoint.add("skippedGatherTargets", blocks(work.skippedGatherTargets));
        checkpoint.add("skippedStorageTargets", blocks(work.skippedStorageTargets));
        checkpoint.add("skippedWorkstationTargets", blocks(work.skippedWorkstationTargets));
        checkpoint.add("gatherTargets", blocks(work.gatherTargets));
        checkpoint.add("skippedBedPlacements", blocks(work.skippedBedPlacements));
        checkpoint.add("skippedBedSheepTargets", uuids(work.skippedBedSheepTargets));
        checkpoint.add("skippedFoodAnimalTargets", uuids(work.skippedFoodAnimalTargets));
        checkpoint.add("skippedRanchAnimalTargets", uuids(work.skippedRanchAnimalTargets));
        checkpoint.add("securedRanchAnimalIds", uuids(work.securedRanchAnimalIds));
        return new NpcTaskPersistence.WorkState(
            work.id,
            work.kind,
            work.spec.deepCopy(),
            work.plan == null ? new JsonObject() : work.plan.deepCopy(),
            work.resumeStance.name(),
            work.priority,
            work.pauseReason,
            checkpoint
        );
    }

    private ActiveWork fromPersistentWork(NpcTaskPersistence.WorkState state) {
        Stance resume;
        try {
            resume = Stance.valueOf(state.resumeStance());
        } catch (IllegalArgumentException ignored) {
            resume = Stance.FOLLOW;
        }
        ActiveWork work = new ActiveWork(state.id(), state.spec().deepCopy(), state.plan().deepCopy(), resume);
        work.priority = state.priority();
        work.pauseReason = state.pauseReason();
        JsonObject value = state.checkpoint();
        work.ticks = integer(value, "ticks", 0);
        work.lastActionTick = integer(value, "lastActionTick", 0);
        work.lastProgressTick = integer(value, "lastProgressTick", 0);
        work.smeltStartedTick = integer(value, "smeltStartedTick", -1);
        work.noWorkTicks = integer(value, "noWorkTicks", 0);
        work.failedActions = integer(value, "failedActions", 0);
        work.stalledTicks = integer(value, "stalledTicks", 0);
        work.stableTicks = integer(value, "stableTicks", 0);
        work.dragonActionStartedTick = integer(value, "dragonActionStartedTick", -1);
        work.gatherPathFailures = integer(value, "gatherPathFailures", 0);
        work.lastGatherPathAttemptTick = integer(value, "lastGatherPathAttemptTick", -1);
        work.gatherStandPathCursor = Math.max(0, integer(value, "gatherStandPathCursor", 0));
        work.buildPathFailures = integer(value, "buildPathFailures", 0);
        work.lastBuildPathAttemptTick = integer(value, "lastBuildPathAttemptTick", -1);
        work.buildStandPathCursor = integer(value, "buildStandPathCursor", 0);
        work.gatherSearchRadius = Math.max(16, integer(value, "gatherSearchRadius", 16));
        work.gatherExcursions = Math.max(0, integer(value, "gatherExcursions", 0));
        work.gatherTreeCluster = value.has("gatherTreeCluster") && value.get("gatherTreeCluster").getAsBoolean();
        work.gatherClusterReached = value.has("gatherClusterReached") && value.get("gatherClusterReached").getAsBoolean();
        work.gatherAccessTarget = value.has("gatherAccessTarget") && value.get("gatherAccessTarget").getAsBoolean();
        work.lastSearchTick = integer(value, "lastSearchTick", -10);
        work.deepMiningPhase = string(value, "deepMiningPhase", "");
        work.deepMiningItemId = string(value, "deepMiningItemId", "");
        work.deepMiningTargetY = integer(value, "deepMiningTargetY", Integer.MAX_VALUE);
        Direction restoredMiningDirection = Direction.byName(string(value, "deepMiningDirection", "north"));
        work.deepMiningDirection = restoredMiningDirection == null
            || !restoredMiningDirection.getAxis().isHorizontal()
            ? Direction.NORTH
            : restoredMiningDirection;
        work.deepMiningPhaseStartedTick = integer(value, "deepMiningPhaseStartedTick", work.ticks);
        work.deepMiningStaircaseStep = Math.max(0, integer(value, "deepMiningStaircaseStep", 0));
        work.deepMiningBranchIndex = Math.max(0, integer(value, "deepMiningBranchIndex", 0));
        work.deepMiningBranchProgress = Math.max(0, integer(value, "deepMiningBranchProgress", 0));
        work.deepMiningRegionIndex = Math.max(0, integer(value, "deepMiningRegionIndex", 0));
        work.deepMiningLastTorchProgress = Math.max(0, integer(value, "deepMiningLastTorchProgress", 0));
        work.deepMiningBrokenBlocks = Math.max(0, integer(value, "deepMiningBrokenBlocks", 0));
        work.deepMiningPlacedTorches = Math.max(0, integer(value, "deepMiningPlacedTorches", 0));
        work.deepMiningBlockedTurns = Math.max(0, integer(value, "deepMiningBlockedTurns", 0));
        work.deepMiningMarkerStage = Math.max(0, Math.min(3, integer(value, "deepMiningMarkerStage", 0)));
        work.deepMiningEntrySearchIndex = Math.max(0, integer(value, "deepMiningEntrySearchIndex", 0));
        work.deepMiningEntryTargetStartedTick = Math.max(
            0,
            integer(value, "deepMiningEntryTargetStartedTick", 0)
        );
        work.deepMiningPreflightComplete = value.has("deepMiningPreflightComplete")
            && value.get("deepMiningPreflightComplete").getAsBoolean();
        work.deepMiningExcavationTarget = value.has("deepMiningExcavationTarget")
            && value.get("deepMiningExcavationTarget").getAsBoolean();
        work.deepMiningResourceTargetStartedTick = Math.max(
            0,
            integer(value, "deepMiningResourceTargetStartedTick", 0)
        );
        work.deepMiningResourceChaseStartedTick = Math.max(
            0,
            integer(value, "deepMiningResourceChaseStartedTick", 0)
        );
        work.fishingCast = value.has("fishingCast") && value.get("fishingCast").getAsBoolean();
        work.fishingReadyTick = integer(value, "fishingReadyTick", 0);
        work.fishingStoragePhase = Math.max(
            0,
            Math.min(
                FishingRodPrerequisitePolicy.storagePhaseCount(),
                integer(value, "fishingStoragePhase", 0)
            )
        );
        work.initialCount = integer(value, "initialCount", 0);
        work.requestedCount = integer(value, "requestedCount", 0);
        work.completed = integer(value, "completed", 0);
        work.loaded = integer(value, "loaded", 0);
        work.sourceSlot = Math.max(-1, Math.min(
            CodexNpcEntity.INVENTORY_SIZE - 1,
            integer(value, "sourceSlot", -1)
        ));
        work.buildIndex = integer(value, "buildIndex", 0);
        work.startDistance = number(value, "startDistance", -1);
        work.lastDistance = number(value, "lastDistance", -1);
        work.dragonCareItemCount = integer(value, "dragonCareItemCount", -1);
        work.dragonCareHealth = number(value, "dragonCareHealth", -1);
        work.dragonCareFood = number(value, "dragonCareFood", -1);
        work.dragonCareHappiness = number(value, "dragonCareHappiness", -1);
        work.dragonCareOwned = value.has("dragonCareOwned") && value.get("dragonCareOwned").getAsBoolean();
        work.dragonCombatTargetDefeated = value.has("dragonCombatTargetDefeated")
            && value.get("dragonCombatTargetDefeated").getAsBoolean();
        work.dragonCareIdentity = string(value, "dragonCareIdentity", null);
        work.initialized = value.has("initialized") && value.get("initialized").getAsBoolean();
        work.requiresTable = value.has("requiresTable") && value.get("requiresTable").getAsBoolean();
        work.storageExpanded = value.has("storageExpanded") && value.get("storageExpanded").getAsBoolean();
        work.smeltingWorkstationClaimed = value.has("smeltingWorkstationClaimed")
            && value.get("smeltingWorkstationClaimed").getAsBoolean();
        work.craftDeliveryPending = value.has("craftDeliveryPending") && value.get("craftDeliveryPending").getAsBoolean();
        work.bedPlacementPending = value.has("bedPlacementPending") && value.get("bedPlacementPending").getAsBoolean();
        work.craftDelivered = integer(value, "craftDelivered", 0);
        work.outputItemId = string(value, "outputItemId", null);
        work.craftGatherItemId = string(value, "craftGatherItemId", null);
        work.dragonTargetId = parseUuid(string(value, "dragonTargetId", null));
        work.dragonCombatTargetId = parseUuid(string(value, "dragonCombatTargetId", null));
        work.bedSheepTargetId = parseUuid(string(value, "bedSheepTargetId", null));
        work.foodAnimalTargetId = parseUuid(string(value, "foodAnimalTargetId", null));
        work.ranchAnimalTargetId = parseUuid(string(value, "ranchAnimalTargetId", null));
        work.ranchTemporaryKnotId = parseUuid(string(value, "ranchTemporaryKnotId", null));
        work.foodCookingInputId = string(value, "foodCookingInputId", null);
        work.foodCookingOutputId = string(value, "foodCookingOutputId", null);
        work.craftGatherCount = integer(value, "craftGatherCount", 0);
        work.craftGatherInitialCount = integer(value, "craftGatherInitialCount", 0);
        work.craftGatherCompleted = integer(value, "craftGatherCompleted", 0);
        work.craftGatherStartedTick = integer(value, "craftGatherStartedTick", -1);
        if (hasCraftGatherPrerequisite(work) && work.craftGatherStartedTick < 0) {
            work.craftGatherStartedTick = work.ticks;
        }
        work.bedStoragePhase = Math.max(0, Math.min(5, integer(value, "bedStoragePhase", 0)));
        work.bedSmeltLoaded = Math.max(0, integer(value, "bedSmeltLoaded", 0));
        work.foodPhase = Math.max(0, Math.min(3, integer(value, "foodPhase", 0)));
        work.ranchPhase = Math.max(0, Math.min(3, integer(value, "ranchPhase", 0)));
        work.ranchLeadStorageChecked = value.has("ranchLeadStorageChecked")
            && value.get("ranchLeadStorageChecked").getAsBoolean();
        work.ranchReachedOutsideGate = value.has("ranchReachedOutsideGate")
            && value.get("ranchReachedOutsideGate").getAsBoolean();
        work.ranchExitStaged = value.has("ranchExitStaged") && value.get("ranchExitStaged").getAsBoolean();
        work.foodCookingTargetCount = Math.max(0, integer(value, "foodCookingTargetCount", 0));
        work.foodCookedCount = Math.max(0, integer(value, "foodCookedCount", 0));
        work.foodTransferredCount = Math.max(0, integer(value, "foodTransferredCount", 0));
        work.buildPhase = string(value, "buildPhase", "scan");
        work.buildPhaseStartedTick = integer(value, "buildPhaseStartedTick", work.ticks);
        work.buildLastProgressTick = integer(value, "buildLastProgressTick", work.ticks);
        work.failureCode = string(value, "failureCode", null);
        work.failureMessage = string(value, "failureMessage", null);
        work.buildCheckpointInvalid = !isValidBuildPhase(work.buildPhase);
        readBuildMaterialGoals(value, work);
        readFurnaceTransactions(value, work);
        work.destination = readVector(value, "destination");
        work.targetBlock = readBlock(value, "targetBlock");
        work.lastTeleportTarget = readBlock(value, "lastTeleportTarget");
        work.workstation = readBlock(value, "workstation");
        work.taskOwnedWorkstation = readBlock(value, "taskOwnedWorkstation");
        work.taskOwnedWorkstationId = string(value, "taskOwnedWorkstationId", null);
        work.buildOrigin = readBlock(value, "buildOrigin");
        work.bedPlacementFoot = readBlock(value, "bedPlacementFoot");
        work.ranchPenCenter = readBlock(value, "ranchPenCenter");
        work.deepMiningEntrance = readBlock(value, "deepMiningEntrance");
        work.deepMiningLanding = readBlock(value, "deepMiningLanding");
        work.deepMiningLastSafeStand = readBlock(value, "deepMiningLastSafeStand");
        work.deepMiningCaveTarget = readBlock(value, "deepMiningCaveTarget");
        work.deepMiningResourceTimedTarget = readBlock(value, "deepMiningResourceTimedTarget");
        work.bedPlacementFacing = Direction.byName(string(value, "bedPlacementFacing", ""));
        readBlocks(value, "skippedGatherTargets", work.skippedGatherTargets);
        readBlocks(value, "skippedStorageTargets", work.skippedStorageTargets);
        readBlocks(value, "skippedWorkstationTargets", work.skippedWorkstationTargets);
        readBlocks(value, "gatherTargets", work.gatherTargets);
        readBlocks(value, "skippedBedPlacements", work.skippedBedPlacements);
        readUuids(value, "skippedBedSheepTargets", work.skippedBedSheepTargets);
        readUuids(value, "skippedFoodAnimalTargets", work.skippedFoodAnimalTargets);
        readUuids(value, "skippedRanchAnimalTargets", work.skippedRanchAnimalTargets);
        readUuids(value, "securedRanchAnimalIds", work.securedRanchAnimalIds);
        // Registry-backed runtime objects cannot be serialized safely. Re-resolve
        // them on the first tick while retaining counters and physical progress.
        if (work.kind.equals("craft") || work.kind.equals("smelt") || work.kind.equals("build")) {
            work.initialized = false;
        }
        if (work.kind.equals("eat")) work.sourceSlot = -1;
        if (work.kind.equals("fish")) {
            // FishingHook entities are not owned by this NPC entity across reloads;
            // recast visibly while preserving completed catches and the chosen water.
            work.fishingCast = false;
            work.fishingReadyTick = 0;
        }
        return work;
    }

    private static void putVector(JsonObject target, String key, Vec3 value) {
        if (value == null) return;
        JsonObject position = new JsonObject();
        position.addProperty("x", value.x);
        position.addProperty("y", value.y);
        position.addProperty("z", value.z);
        target.add(key, position);
    }

    private static Vec3 readVector(JsonObject source, String key) {
        if (!source.has(key) || !source.get(key).isJsonObject()) return null;
        JsonObject value = source.getAsJsonObject(key);
        return new Vec3(number(value, "x", 0), number(value, "y", 0), number(value, "z", 0));
    }

    private static void putBlock(JsonObject target, String key, BlockPos value) {
        if (value == null) return;
        JsonObject position = new JsonObject();
        position.addProperty("x", value.getX());
        position.addProperty("y", value.getY());
        position.addProperty("z", value.getZ());
        target.add(key, position);
    }

    private static BlockPos readBlock(JsonObject source, String key) {
        if (!source.has(key) || !source.get(key).isJsonObject()) return null;
        JsonObject value = source.getAsJsonObject(key);
        return new BlockPos(integer(value, "x", 0), integer(value, "y", 0), integer(value, "z", 0));
    }

    private static JsonArray blocks(Iterable<BlockPos> positions) {
        JsonArray result = new JsonArray();
        for (BlockPos position : positions) {
            JsonObject value = new JsonObject();
            value.addProperty("x", position.getX());
            value.addProperty("y", position.getY());
            value.addProperty("z", position.getZ());
            result.add(value);
        }
        return result;
    }

    private static void readBlocks(JsonObject source, String key, java.util.Collection<BlockPos> output) {
        if (!source.has(key) || !source.get(key).isJsonArray()) return;
        source.getAsJsonArray(key).forEach(element -> {
            if (!element.isJsonObject()) return;
            JsonObject value = element.getAsJsonObject();
            output.add(new BlockPos(integer(value, "x", 0), integer(value, "y", 0), integer(value, "z", 0)));
        });
    }

    private static JsonArray uuids(Iterable<UUID> values) {
        JsonArray result = new JsonArray();
        for (UUID value : values) result.add(value.toString());
        return result;
    }

    private static void readUuids(JsonObject source, String key, java.util.Collection<UUID> output) {
        if (!source.has(key) || !source.get(key).isJsonArray()) return;
        source.getAsJsonArray(key).forEach(element -> {
            if (!element.isJsonPrimitive()) return;
            UUID value = parseUuid(element.getAsString());
            if (value != null) output.add(value);
        });
    }

    private static JsonArray buildMaterialGoals(Iterable<BuildMaterialGoal> goals) {
        JsonArray result = new JsonArray();
        for (BuildMaterialGoal goal : goals) {
            JsonObject value = new JsonObject();
            value.addProperty("itemId", goal.itemId);
            value.addProperty("selector", goal.selector);
            value.addProperty("materialContextId", goal.materialContextId);
            value.addProperty("targetCount", goal.targetCount);
            value.addProperty("lastInventoryCount", goal.lastInventoryCount);
            value.addProperty("startedTick", goal.startedTick);
            value.addProperty("stalledTicks", goal.stalledTicks);
            value.addProperty("attemptedRoutes", goal.attemptedRoutes);
            if (goal.suspendedGatherItemId != null) {
                value.addProperty("suspendedGatherItemId", goal.suspendedGatherItemId);
                value.addProperty("suspendedGatherCount", goal.suspendedGatherCount);
                value.addProperty("suspendedGatherInitialCount", goal.suspendedGatherInitialCount);
                value.addProperty("suspendedGatherCompleted", goal.suspendedGatherCompleted);
                value.addProperty("suspendedGatherStartedTick", goal.suspendedGatherStartedTick);
            }
            if (goal.suspendedDeepMining != null) {
                value.add("suspendedDeepMining", goal.suspendedDeepMining.toJson());
            }
            putBlock(value, "ownedFurnace", goal.ownedFurnace);
            result.add(value);
        }
        return result;
    }

    private static void readBuildMaterialGoals(JsonObject source, ActiveWork work) {
        if (!source.has("buildMaterialGoals")) return;
        if (!source.get("buildMaterialGoals").isJsonArray()) {
            work.buildCheckpointInvalid = true;
            return;
        }
        JsonArray values = source.getAsJsonArray("buildMaterialGoals");
        if (values.size() > BUILD_MATERIAL_MAX_DEPTH) {
            work.buildCheckpointInvalid = true;
            return;
        }
        for (var element : values) {
            if (!element.isJsonObject()) {
                work.buildCheckpointInvalid = true;
                return;
            }
            JsonObject value = element.getAsJsonObject();
            String itemId = string(value, "itemId", "");
            String selector = string(value, "selector", itemId);
            String materialContextId = string(value, "materialContextId", itemId);
            int targetCount = integer(value, "targetCount", 0);
            if (itemId.isBlank() || selector.isBlank() || materialContextId.isBlank() || targetCount <= 0) {
                work.buildCheckpointInvalid = true;
                return;
            }
            BuildMaterialGoal goal = new BuildMaterialGoal(
                itemId,
                selector,
                materialContextId,
                targetCount,
                Math.max(0, integer(value, "lastInventoryCount", 0)),
                Math.max(0, integer(value, "startedTick", 0)),
                string(value, "suspendedGatherItemId", null),
                Math.max(0, integer(value, "suspendedGatherCount", 0)),
                Math.max(0, integer(value, "suspendedGatherInitialCount", 0)),
                Math.max(0, integer(value, "suspendedGatherCompleted", 0)),
                integer(value, "suspendedGatherStartedTick", -1),
                value.has("suspendedDeepMining") && value.get("suspendedDeepMining").isJsonObject()
                    ? DeepMiningCheckpoint.fromJson(value.getAsJsonObject("suspendedDeepMining"))
                    : null
            );
            goal.stalledTicks = Math.max(0, integer(value, "stalledTicks", 0));
            goal.attemptedRoutes = Math.max(0, integer(value, "attemptedRoutes", 0));
            goal.ownedFurnace = readBlock(value, "ownedFurnace");
            work.buildMaterialGoals.addLast(goal);
        }
    }

    private static JsonArray furnaceTransactions(Iterable<FurnaceTransaction> transactions) {
        JsonArray result = new JsonArray();
        int saved = 0;
        for (FurnaceTransaction transaction : transactions) {
            if (saved++ >= MAX_TASK_FURNACE_TRANSACTIONS) break;
            JsonObject value = new JsonObject();
            value.addProperty("claimId", transaction.claimId.toString());
            value.addProperty("dimension", transaction.dimensionId);
            putBlock(value, "position", transaction.position);
            value.addProperty("inputItemId", transaction.inputItemId);
            value.addProperty("outputItemId", transaction.outputItemId);
            value.addProperty("outputPerInput", transaction.outputPerInput);
            value.addProperty("inputDeposited", transaction.inputDeposited);
            value.addProperty("outputWithdrawn", transaction.outputWithdrawn);
            JsonArray fuels = new JsonArray();
            transaction.fuelDeposited.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(16)
                .forEach(entry -> {
                    JsonObject fuel = new JsonObject();
                    fuel.addProperty("itemId", entry.getKey());
                    fuel.addProperty("count", entry.getValue());
                    fuels.add(fuel);
                });
            value.add("fuelDeposited", fuels);
            result.add(value);
        }
        return result;
    }

    private static void readFurnaceTransactions(JsonObject source, ActiveWork work) {
        if (!source.has("furnaceTransactions") || !source.get("furnaceTransactions").isJsonArray()) return;
        JsonArray values = source.getAsJsonArray("furnaceTransactions");
        int limit = Math.min(values.size(), MAX_TASK_FURNACE_TRANSACTIONS);
        for (int index = 0; index < limit; index++) {
            if (!values.get(index).isJsonObject()) continue;
            JsonObject value = values.get(index).getAsJsonObject();
            String dimensionId = string(value, "dimension", "");
            String inputItemId = string(value, "inputItemId", "");
            String outputItemId = string(value, "outputItemId", "");
            UUID claimId = parseUuid(string(value, "claimId", ""));
            BlockPos position = readBlock(value, "position");
            if (claimId == null
                || ResourceLocation.tryParse(dimensionId) == null
                || ResourceLocation.tryParse(inputItemId) == null
                || ResourceLocation.tryParse(outputItemId) == null
                || position == null) continue;
            boolean duplicate = work.furnaceTransactions.stream().anyMatch(transaction ->
                transaction.dimensionId.equals(dimensionId) && transaction.position.equals(position)
            );
            if (duplicate) continue;
            FurnaceTransaction transaction = new FurnaceTransaction(
                claimId,
                dimensionId,
                position,
                inputItemId,
                outputItemId,
                Math.max(1, integer(value, "outputPerInput", 1))
            );
            transaction.inputDeposited = Math.max(0, integer(value, "inputDeposited", 0));
            transaction.outputWithdrawn = Math.max(0, integer(value, "outputWithdrawn", 0));
            if (value.has("fuelDeposited") && value.get("fuelDeposited").isJsonArray()) {
                JsonArray fuels = value.getAsJsonArray("fuelDeposited");
                for (int fuelIndex = 0; fuelIndex < Math.min(16, fuels.size()); fuelIndex++) {
                    if (!fuels.get(fuelIndex).isJsonObject()) continue;
                    JsonObject fuel = fuels.get(fuelIndex).getAsJsonObject();
                    String itemId = string(fuel, "itemId", "");
                    int count = Math.max(0, integer(fuel, "count", 0));
                    if (ResourceLocation.tryParse(itemId) != null && count > 0) {
                        transaction.fuelDeposited.merge(itemId, count, NpcTaskEngine::saturatingAdd);
                    }
                }
            }
            work.furnaceTransactions.add(transaction);
        }
    }

    private static boolean isValidBuildPhase(String phase) {
        return Set.of("scan", "storage", "craft", "smelt", "gather", "resume-parent").contains(phase);
    }

    private static int defaultPriority(String kind, boolean local) {
        return TaskPriorityPolicy.defaultPriority(kind, local);
    }

    private static final class ResourceSelector {
        private final ResourceSelectorPolicy.Parsed parsed;
        private final ResourceLocation location;

        private ResourceSelector(ResourceSelectorPolicy.Parsed parsed) {
            this.parsed = parsed;
            this.location = ResourceLocation.parse(parsed.resourceId());
        }

        private static ResourceSelector parse(String value) {
            return new ResourceSelector(ResourceSelectorPolicy.parse(value));
        }

        private boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            String candidateId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            return ResourceSelectorPolicy.matches(
                parsed,
                candidateId,
                ignored -> stack.is(TagKey.create(Registries.ITEM, location))
            );
        }

        private boolean matches(BlockState state) {
            String candidateId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return ResourceSelectorPolicy.matches(
                parsed,
                candidateId,
                ignored -> state.is(TagKey.create(Registries.BLOCK, location))
            );
        }

        private boolean isExact(String itemId) {
            return !parsed.tag() && parsed.resourceId().equals(itemId);
        }

        private Item firstItem() {
            if (!parsed.tag()) return BuiltInRegistries.ITEM.get(location);
            return BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, location))
                .flatMap(items -> items.stream().findFirst())
                .map(holder -> holder.value())
                .orElse(Items.AIR);
        }
    }

    private enum StorageSortResult { SUCCEEDED, FULL, UNAVAILABLE }

    private record StorageSnapshot(Container container, List<ItemStack> contents) {}

    private record HomeRetrieveInspection(int available, boolean inventoryFits) {}

    private static final class BuildMaterialGoal {
        private final String itemId;
        private final String selector;
        private final String materialContextId;
        private final int targetCount;
        private final int startedTick;
        private int lastInventoryCount;
        private int stalledTicks;
        private int attemptedRoutes;
        private BlockPos ownedFurnace;
        private final String suspendedGatherItemId;
        private final int suspendedGatherCount;
        private final int suspendedGatherInitialCount;
        private final int suspendedGatherCompleted;
        private final int suspendedGatherStartedTick;
        private final DeepMiningCheckpoint suspendedDeepMining;

        private BuildMaterialGoal(
            String itemId,
            String selector,
            String materialContextId,
            int targetCount,
            int lastInventoryCount,
            int startedTick,
            String suspendedGatherItemId,
            int suspendedGatherCount,
            int suspendedGatherInitialCount,
            int suspendedGatherCompleted,
            int suspendedGatherStartedTick,
            DeepMiningCheckpoint suspendedDeepMining
        ) {
            this.itemId = itemId;
            this.selector = selector;
            this.materialContextId = materialContextId;
            this.targetCount = targetCount;
            this.lastInventoryCount = lastInventoryCount;
            this.startedTick = startedTick;
            this.suspendedGatherItemId = suspendedGatherItemId;
            this.suspendedGatherCount = suspendedGatherCount;
            this.suspendedGatherInitialCount = suspendedGatherInitialCount;
            this.suspendedGatherCompleted = suspendedGatherCompleted;
            this.suspendedGatherStartedTick = suspendedGatherStartedTick;
            this.suspendedDeepMining = suspendedDeepMining;
        }
    }

    private static final class ActiveWork {
        private final String id;
        private final JsonObject spec;
        private final JsonObject plan;
        private final String kind;
        private final Stance resumeStance;
        private int priority;
        private String pauseReason = "";
        private int ticks;
        private int lastActionTick;
        private int lastProgressTick;
        private int smeltStartedTick = -1;
        private int lastProgressHeartbeatTick;
        private int noWorkTicks;
        private int failedActions;
        private int stalledTicks;
        private int stableTicks;
        private int dragonActionStartedTick = -1;
        private int gatherPathFailures;
        private int lastGatherPathAttemptTick = -1;
        private int gatherStandPathCursor;
        private int buildPathFailures;
        private int lastBuildPathAttemptTick = -1;
        private int buildStandPathCursor;
        private int gatherSearchRadius = 16;
        private int gatherExcursions;
        private int lastSearchTick = -10;
        private int deepMiningTargetY = Integer.MAX_VALUE;
        private int deepMiningPhaseStartedTick;
        private int deepMiningStaircaseStep;
        private int deepMiningBranchIndex;
        private int deepMiningBranchProgress;
        private int deepMiningRegionIndex;
        private int deepMiningLastTorchProgress;
        private int deepMiningBrokenBlocks;
        private int deepMiningPlacedTorches;
        private int deepMiningBlockedTurns;
        private int deepMiningMarkerStage;
        private int deepMiningEntrySearchIndex;
        private int deepMiningEntryTargetStartedTick;
        private int eatingSequence;
        private int fishingReadyTick;
        private int fishingStoragePhase;
        private int initialCount;
        private int requestedCount;
        private int completed;
        private int loaded;
        private int sourceSlot = -1;
        private int dragonCareItemCount = -1;
        private int buildIndex;
        private int buildPhaseStartedTick;
        private int buildLastProgressTick;
        private double startDistance = -1;
        private double lastDistance = -1;
        private double dragonCareHealth = -1;
        private double dragonCareFood = -1;
        private double dragonCareHappiness = -1;
        private boolean initialized;
        private boolean dragonCareOwned;
        private boolean dragonCombatTargetDefeated;
        private boolean dragonLandingCommitted;
        private boolean gatherTreeCluster;
        private boolean gatherClusterReached;
        private boolean gatherAccessTarget;
        private boolean deepMiningPreflightComplete;
        private boolean deepMiningExcavationTarget;
        private int deepMiningResourceTargetStartedTick;
        private int deepMiningResourceChaseStartedTick;
        private boolean fishingCast;
        private boolean requiresTable;
        private boolean storageExpanded;
        private boolean smeltingWorkstationClaimed;
        private boolean craftDeliveryPending;
        private boolean bedPlacementPending;
        private boolean buildCheckpointInvalid;
        private String outputItemId;
        private String dragonCareIdentity;
        private String craftGatherItemId;
        private String deepMiningPhase = "";
        private String deepMiningItemId = "";
        private String taskOwnedWorkstationId;
        private String buildPhase = "scan";
        private String failureCode;
        private String failureMessage;
        private UUID dragonTargetId;
        private UUID dragonCombatTargetId;
        private UUID bedSheepTargetId;
        private UUID foodAnimalTargetId;
        private UUID ranchAnimalTargetId;
        private UUID ranchTemporaryKnotId;
        private String foodCookingInputId;
        private String foodCookingOutputId;
        private int craftGatherCount;
        private int craftGatherInitialCount;
        private int craftGatherCompleted;
        private int craftGatherStartedTick = -1;
        private int craftDelivered;
        private int bedStoragePhase;
        private int bedSmeltLoaded;
        private int foodPhase;
        private int ranchPhase;
        private boolean ranchLeadStorageChecked;
        private boolean ranchReachedOutsideGate;
        private boolean ranchExitStaged;
        private int foodCookingTargetCount;
        private int foodCookedCount;
        private int foodTransferredCount;
        private Vec3 destination;
        private BlockPos targetBlock;
        private BlockPos lastTeleportTarget;
        private BlockPos workstation;
        private BlockPos taskOwnedWorkstation;
        private BlockPos pendingWorkstationPlacement;
        private BlockPos buildOrigin;
        private BlockPos bedPlacementFoot;
        private BlockPos ranchPenCenter;
        private BlockPos deepMiningEntrance;
        private BlockPos deepMiningLanding;
        private BlockPos deepMiningLastSafeStand;
        private BlockPos deepMiningCaveTarget;
        private BlockPos deepMiningResourceTimedTarget;
        private Direction bedPlacementFacing;
        private Direction deepMiningDirection = Direction.NORTH;
        private Recipe<?> recipe;
        private JsonArray buildBlocks;
        private final Set<BlockPos> skippedGatherTargets = new HashSet<>();
        private final Set<BlockPos> skippedStorageTargets = new HashSet<>();
        private final Set<BlockPos> skippedWorkstationTargets = new HashSet<>();
        private final Deque<BlockPos> gatherTargets = new ArrayDeque<>();
        private final Set<BlockPos> skippedBedPlacements = new HashSet<>();
        private final Set<UUID> skippedBedSheepTargets = new HashSet<>();
        private final Set<UUID> skippedFoodAnimalTargets = new HashSet<>();
        private final Set<UUID> skippedRanchAnimalTargets = new HashSet<>();
        private final Set<UUID> securedRanchAnimalIds = new HashSet<>();
        private final List<FurnaceTransaction> furnaceTransactions = new ArrayList<>();
        private final Deque<BuildMaterialGoal> buildMaterialGoals = new ArrayDeque<>();

        private ActiveWork(String id, JsonObject spec, JsonObject plan, Stance resumeStance) {
            this.id = id;
            this.spec = spec;
            this.plan = plan;
            this.kind = spec.get("kind").getAsString();
            this.resumeStance = resumeStance;
            this.priority = spec.has("priority")
                ? Math.max(0, Math.min(1000, spec.get("priority").getAsInt()))
                : defaultPriority(this.kind, id.startsWith("local:"));
        }
    }

    private static final class FurnaceTransaction {
        private final UUID claimId;
        private final String dimensionId;
        private final BlockPos position;
        private final String inputItemId;
        private final String outputItemId;
        private final int outputPerInput;
        private int inputDeposited;
        private int outputWithdrawn;
        private final Map<String, Integer> fuelDeposited = new HashMap<>();

        private FurnaceTransaction(
            UUID claimId,
            String dimensionId,
            BlockPos position,
            String inputItemId,
            String outputItemId,
            int outputPerInput
        ) {
            this.claimId = claimId;
            this.dimensionId = dimensionId;
            this.position = position.immutable();
            this.inputItemId = inputItemId;
            this.outputItemId = outputItemId;
            this.outputPerInput = Math.max(1, outputPerInput);
        }

        private boolean matches(
            String expectedDimension,
            BlockPos expectedPosition,
            String expectedInput,
            String expectedOutput,
            int expectedOutputPerInput
        ) {
            return dimensionId.equals(expectedDimension)
                && position.equals(expectedPosition)
                && inputItemId.equals(expectedInput)
                && outputItemId.equals(expectedOutput)
                && outputPerInput == Math.max(1, expectedOutputPerInput);
        }
    }

    private record FurnaceRecoverySummary(int inventoryCount, int escrowCount, int retainedCount) {
        static FurnaceRecoverySummary empty() {
            return new FurnaceRecoverySummary(0, 0, 0);
        }

        FurnaceRecoverySummary plus(FurnaceRecoverySummary other) {
            return new FurnaceRecoverySummary(
                inventoryCount + other.inventoryCount,
                escrowCount + other.escrowCount,
                retainedCount + other.retainedCount
            );
        }

        boolean changed() {
            return inventoryCount > 0 || escrowCount > 0 || retainedCount > 0;
        }

        String detail() {
            if (!changed()) return "";
            StringJoiner detail = new StringJoiner("，", "（任务炉回收：", "）");
            if (inventoryCount > 0) detail.add(inventoryCount + " 个已放回背包");
            if (escrowCount > 0) detail.add(escrowCount + " 个因背包已满安全暂存在 NPC 脚边");
            if (retainedCount > 0) detail.add(retainedCount + " 个因世界拒绝生成暂存实体而保留在原炉");
            return detail.toString();
        }
    }
}
