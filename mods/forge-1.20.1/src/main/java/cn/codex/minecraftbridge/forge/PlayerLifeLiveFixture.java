package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fully reversible, entity-level acceptance fixtures for player-like NPC life skills. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class PlayerLifeLiveFixture {
    private static final String STATE_SUITE = "player-state";
    private static final String EAT_SUITE = "eating-action";
    private static final String FISH_SUITE = "fishing-action";
    private static final String FARM_SUITE = "farm-action";
    private static final String GUARD_SUITE = "guard-resume";
    private static final String MARKER_TAG = "CodexAcceptancePlayerLifeMarker";
    private static final String OUTPUT_TAG = "CodexAcceptancePlayerLifeOutput";
    private static final String FISH_HOOK_TAG = "CodexAcceptancePlayerLifeHook";
    private static final String HOSTILE_TAG = "CodexAcceptanceGuardResumeHostile";
    private static final String EATING_ITEM_TAG = "CodexAcceptanceEatingItem";
    private static final String STATE_KEY = "CodexAcceptancePlayerLifeState";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final int SEARCH_RADIUS = 512;
    private static final int[] SITE_OFFSETS = { 64, -64, 96, -96, 128, -128, 160, -160 };

    private PlayerLifeLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String suite, String mode) {
        if (npc == null) throw new IllegalStateException("Player life fixture requires an in-world NPC");
        switch (suite) {
            case STATE_SUITE -> applyState(player, npc, mode);
            case EAT_SUITE -> applyEating(player, npc, mode);
            case FISH_SUITE -> applyFishing(player, npc, mode);
            case FARM_SUITE -> applyFarm(player, npc, mode);
            case GUARD_SUITE -> applyGuard(player, npc, mode);
            default -> throw new IllegalArgumentException("Unknown player life fixture suite");
        }
    }

    @SubscribeEvent
    public static void recordBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof FakePlayer)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 40.0D);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        String suite = state.getString("Suite");
        if (FARM_SUITE.equals(suite) && contains(state.getLongArray("FarmCrops"), event.getPos())) {
            state.putInt("FarmBreaks", state.getInt("FarmBreaks") + 1);
        } else if (GUARD_SUITE.equals(suite) && contains(state.getLongArray("GuardLogs"), event.getPos())) {
            state.putInt("GuardBreaks", state.getInt("GuardBreaks") + 1);
        } else {
            return;
        }
        state.putLong("LastBreakGameTime", level.getGameTime());
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        ArmorStand marker = markerNear(level, entity.position(), 40.0D);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (entity instanceof FishingHook hook && FISH_SUITE.equals(state.getString("Suite"))) {
            hook.addTag(FISH_HOOK_TAG);
            state.putInt("HookSpawns", state.getInt("HookSpawns") + 1);
            Entity owner = hook.getOwner();
            if (owner != null && state.hasUUID("OwnerUuid") && state.getUUID("OwnerUuid").equals(owner.getUUID())) {
                state.putInt("OwnedHookSpawns", state.getInt("OwnedHookSpawns") + 1);
            }
            marker.getPersistentData().put(STATE_KEY, state);
            return;
        }
        if (!(entity instanceof ItemEntity item)) return;
        long lastBreak = state.getLong("LastBreakGameTime");
        if (lastBreak > 0L && level.getGameTime() - lastBreak <= 4L
            && (FARM_SUITE.equals(state.getString("Suite")) || GUARD_SUITE.equals(state.getString("Suite")))) {
            item.addTag(OUTPUT_TAG);
        }
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void recordEatingStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntity() instanceof CodexNpcEntity npc)
            || !(npc.level() instanceof ServerLevel level)) return;
        ArmorStand marker = eatingMarker(level, npc);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        String token = eatingItemToken(event.getItem());
        state.putString("StartedItem", token);
        if (validEatingEvent(state, event.getItem())) {
            state.putInt("UseStarts", state.getInt("UseStarts") + 1);
        } else {
            state.putInt("UseViolations", state.getInt("UseViolations") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void recordEatingFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof CodexNpcEntity npc)
            || !(npc.level() instanceof ServerLevel level)) return;
        ArmorStand marker = eatingMarker(level, npc);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        String token = eatingItemToken(event.getItem());
        state.putString("FinishedItem", token);
        int consumed = event.getItem().getCount() - event.getResultStack().getCount();
        if (validEatingEvent(state, event.getItem()) && consumed == 1) {
            state.putInt("UseFinishes", state.getInt("UseFinishes") + 1);
        } else {
            state.putInt("UseViolations", state.getInt("UseViolations") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void applyState(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "setup" -> setupState(player, npc);
            case "inspect" -> inspectState(player, npc);
            case "cleanup" -> cleanup(player, npc, STATE_SUITE, true);
            default -> throw new IllegalArgumentException("Unknown player state fixture mode");
        }
    }

    private static void applyEating(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "setup-rotten" -> setupEating(player, npc, "rotten", 10);
            case "setup-melon" -> setupEating(player, npc, "melon", 16);
            case "setup-full" -> setupEating(player, npc, "full", 20);
            case "inspect" -> inspectEating(player, npc);
            case "cleanup" -> cleanup(player, npc, EAT_SUITE, true);
            default -> throw new IllegalArgumentException("Unknown eating action fixture mode");
        }
    }

    private static void applyFishing(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "setup" -> setupFishing(player, npc);
            case "inspect" -> inspectFishing(player, npc);
            case "cleanup" -> cleanup(player, npc, FISH_SUITE, true);
            default -> throw new IllegalArgumentException("Unknown fishing action fixture mode");
        }
    }

    private static void applyFarm(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "setup-work" -> setupFarm(player, npc, false);
            case "setup-empty" -> setupFarm(player, npc, true);
            case "inspect" -> inspectFarm(player, npc);
            case "cleanup" -> cleanup(player, npc, FARM_SUITE, true);
            default -> throw new IllegalArgumentException("Unknown farm action fixture mode");
        }
    }

    private static void applyGuard(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "setup" -> setupGuard(player, npc);
            case "arm" -> armGuard(player, npc);
            case "release" -> releaseGuard(player, npc);
            case "inspect" -> inspectGuard(player, npc);
            case "cleanup" -> cleanup(player, npc, GUARD_SUITE, true);
            default -> throw new IllegalArgumentException("Unknown guard resume fixture mode");
        }
    }

    private static void setupState(ServerPlayer player, CodexNpcEntity npc) {
        prepareSetup(player, npc, STATE_SUITE);
        FixtureContext context = begin(player, npc, STATE_SUITE, "state", npc.blockPosition(), Set.of());
        try {
            npc.setHealth(12.0F);
            npc.setFoodLevel(8);
            npc.setSaturationLevel(0.0F);
            npc.setExhaustionLevel(0.0F);
            context.state().putInt("EatingSequence", npc.eatingCompletionSequence());
            insert(npc, new ItemStack(Items.COOKED_BEEF, 4));
            insert(npc, new ItemStack(Items.LEATHER_HELMET));
            insert(npc, new ItemStack(Items.DIAMOND_HELMET));
            insert(npc, new ItemStack(Items.LEATHER_CHESTPLATE));
            insert(npc, new ItemStack(Items.DIAMOND_CHESTPLATE));
            insert(npc, new ItemStack(Items.SHIELD));
            context.marker().getPersistentData().put(STATE_KEY, context.state());
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            npc.setStatus("state-fixture:setup");
        } catch (RuntimeException error) {
            cleanup(player, npc, STATE_SUITE, false);
            throw error;
        }
    }

    private static void inspectState(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, STATE_SUITE);
        int eaten = npc.eatingCompletionSequence() - context.state().getInt("EatingSequence");
        int natural = context.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION) ? 1 : 0;
        npc.setStatus("state-fixture:h=" + Math.round(npc.getHealth() * 1000.0F)
            + ",f=" + npc.foodLevel()
            + ",e=" + eaten
            + ",beef=" + countItem(npc, Items.COOKED_BEEF)
            + ",managed=" + bool(npc.isManagedEating())
            + ",using=" + bool(npc.isUsingItem())
            + ",dh=" + bool(npc.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET))
            + ",dc=" + bool(npc.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE))
            + ",sh=" + bool(npc.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD))
            + ",regen=" + natural);
    }

    private static void setupEating(ServerPlayer player, CodexNpcEntity npc, String scenario, int food) {
        prepareSetup(player, npc, EAT_SUITE);
        FixtureContext context = begin(player, npc, EAT_SUITE, scenario, npc.blockPosition(), Set.of());
        try {
            npc.setHealth(npc.getMaxHealth());
            // Reset any transient automatic-eating latch before lowering hunger for this scenario.
            npc.setFoodLevel(20);
            npc.setFoodLevel(food);
            npc.setSaturationLevel(0.0F);
            npc.setExhaustionLevel(0.0F);
            context.state().putInt("EatingSequence", npc.eatingCompletionSequence());
            context.state().putInt("UseStarts", 0);
            context.state().putInt("UseFinishes", 0);
            context.state().putInt("UseViolations", 0);
            context.state().putString("StartedItem", "none");
            context.state().putString("FinishedItem", "none");
            insert(npc, fixtureFood(new ItemStack(Items.ROTTEN_FLESH, 3)));
            insert(npc, fixtureFood(new ItemStack(Items.MELON_SLICE, 2)));
            context.marker().getPersistentData().put(STATE_KEY, context.state());
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            npc.setStatus("eat-fixture:setup-" + scenario);
        } catch (RuntimeException error) {
            cleanup(player, npc, EAT_SUITE, false);
            throw error;
        }
    }

    private static void inspectEating(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, EAT_SUITE);
        npc.setStatus(eatingInspectionStatus(
            context.state().getString("Scenario"),
            npc.foodLevel(),
            npc.eatingCompletionSequence() - context.state().getInt("EatingSequence"),
            countItem(npc, Items.ROTTEN_FLESH),
            countItem(npc, Items.MELON_SLICE),
            context.state().getInt("UseStarts"),
            context.state().getInt("UseFinishes"),
            context.state().getString("StartedItem"),
            context.state().getString("FinishedItem"),
            context.state().getInt("UseViolations"),
            npc.isManagedEating(),
            npc.isUsingItem()
        ));
    }

    static String eatingInspectionStatus(
        String scenario,
        int food,
        int eaten,
        int rottenFlesh,
        int melonSlices,
        int useStarts,
        int useFinishes,
        String startedItem,
        String finishedItem,
        int violations,
        boolean managedEating,
        boolean usingItem
    ) {
        return "eat-fixture:c=" + scenario
            + ",f=" + food
            + ",e=" + eaten
            + ",r=" + rottenFlesh
            + ",m=" + melonSlices
            + ",s=" + useStarts
            + ",x=" + useFinishes
            + ",si=" + startedItem
            + ",fi=" + finishedItem
            + ",v=" + violations
            + ",mg=" + bool(managedEating)
            + ",u=" + bool(usingItem);
    }

    private static void setupFishing(ServerPlayer player, CodexNpcEntity npc) {
        prepareSetup(player, npc, FISH_SUITE);
        BlockPos origin = findSite(player.serverLevel(), npc.blockPosition(), -3, 12, -3, 12, 5);
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>();
        for (int x = -3; x <= 12; x++) {
            for (int z = -3; z <= 12; z++) modified.add(origin.offset(x, -1, z));
        }
        for (int x = 4; x <= 10; x++) {
            for (int z = 4; z <= 10; z++) modified.add(origin.offset(x, 0, z));
        }
        FixtureContext context = begin(player, npc, FISH_SUITE, "fishing", origin, modified);
        try {
            for (int x = -3; x <= 12; x++) {
                for (int z = -3; z <= 12; z++) set(context.level(), origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
            for (int x = 4; x <= 10; x++) {
                for (int z = 4; z <= 10; z++) {
                    boolean rim = x == 4 || x == 10 || z == 4 || z == 10;
                    set(context.level(), origin.offset(x, 0, z), rim
                        ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
                }
            }
            ItemStack rod = new ItemStack(Items.FISHING_ROD);
            insert(npc, rod);
            context.state().putInt("RodDamageBefore", rod.getDamageValue());
            context.marker().getPersistentData().put(STATE_KEY, context.state());
            moveNpc(npc, origin.offset(1, 0, 1));
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            npc.setStatus("fish-fixture:setup");
        } catch (RuntimeException error) {
            cleanup(player, npc, FISH_SUITE, false);
            throw error;
        }
    }

    private static void inspectFishing(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, FISH_SUITE);
        int activeHooks = context.level().getEntitiesOfClass(
            FishingHook.class,
            new AABB(context.origin()).inflate(32.0D),
            hook -> hook.isAlive() && hook.getTags().contains(FISH_HOOK_TAG)
        ).size();
        int loot = 0;
        int rodDamage = -1;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.FISHING_ROD)) rodDamage = Math.max(rodDamage, stack.getDamageValue());
            else loot += stack.getCount();
        }
        npc.setStatus("fish-fixture:hooks=" + context.state().getInt("HookSpawns")
            + ",owned=" + context.state().getInt("OwnedHookSpawns")
            + ",active=" + activeHooks
            + ",loot=" + loot
            + ",damage=" + rodDamage);
    }

    private static void setupFarm(ServerPlayer player, CodexNpcEntity npc, boolean empty) {
        prepareSetup(player, npc, FARM_SUITE);
        BlockPos origin = findSite(player.serverLevel(), npc.blockPosition(), -4, 10, -4, 8, 5);
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>();
        for (int x = -4; x <= 10; x++) {
            for (int z = -4; z <= 8; z++) modified.add(origin.offset(x, -1, z));
        }
        List<BlockPos> crops = List.of(origin.offset(5, 0, 1), origin.offset(6, 0, 1));
        modified.addAll(crops);
        FixtureContext context = begin(player, npc, FARM_SUITE, empty ? "empty" : "work", origin, modified);
        try {
            for (int x = -4; x <= 10; x++) {
                for (int z = -4; z <= 8; z++) set(context.level(), origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
            for (BlockPos crop : crops) {
                set(context.level(), crop.below(), empty ? Blocks.DIRT.defaultBlockState() : Blocks.FARMLAND.defaultBlockState());
                if (!empty) set(context.level(), crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
            }
            context.state().putLongArray("FarmCrops", longs(crops));
            context.marker().getPersistentData().put(STATE_KEY, context.state());
            if (!empty) {
                insert(npc, new ItemStack(Items.WHEAT_SEEDS, 8));
                insert(npc, new ItemStack(Items.IRON_HOE));
            }
            moveNpc(npc, origin.offset(1, 0, 1));
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            npc.setStatus(empty ? "farm-fixture:setup-empty" : "farm-fixture:setup-work");
        } catch (RuntimeException error) {
            cleanup(player, npc, FARM_SUITE, false);
            throw error;
        }
    }

    private static void inspectFarm(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, FARM_SUITE);
        int mature = 0;
        int young = 0;
        for (long packed : context.state().getLongArray("FarmCrops")) {
            BlockState cropState = context.level().getBlockState(BlockPos.of(packed));
            if (!(cropState.getBlock() instanceof CropBlock crop)) continue;
            if (crop.isMaxAge(cropState)) mature++;
            else young++;
        }
        npc.setStatus("farm-fixture:case=" + context.state().getString("Scenario")
            + ",mature=" + mature
            + ",young=" + young
            + ",breaks=" + context.state().getInt("FarmBreaks"));
    }

    private static void setupGuard(ServerPlayer player, CodexNpcEntity npc) {
        prepareSetup(player, npc, GUARD_SUITE);
        BlockPos origin = findSite(player.serverLevel(), npc.blockPosition(), -4, 20, -6, 8, 5);
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>();
        for (int x = -4; x <= 20; x++) {
            for (int z = -6; z <= 8; z++) modified.add(origin.offset(x, -1, z));
        }
        List<BlockPos> roots = List.of(
            origin.offset(6, 0, 1),
            origin.offset(11, 0, 1),
            origin.offset(16, 0, 1)
        );
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> leaves = new LinkedHashSet<>();
        for (BlockPos root : roots) {
            for (int y = 0; y < 4; y++) logs.add(root.above(y));
            leaves.addAll(canopy(root.above(3)));
        }
        modified.addAll(logs);
        modified.addAll(leaves);
        FixtureContext context = begin(player, npc, GUARD_SUITE, "guard", origin, modified);
        try {
            for (int x = -4; x <= 20; x++) {
                for (int z = -6; z <= 8; z++) set(context.level(), origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
            for (BlockPos root : roots) set(context.level(), root.below(), Blocks.DIRT.defaultBlockState());
            for (BlockPos log : logs) set(context.level(), log, Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
            for (BlockPos position : leaves) if (!logs.contains(position)) set(context.level(), position, leaf);
            context.state().putLongArray("GuardLogs", longs(logs));
            context.marker().getPersistentData().put(STATE_KEY, context.state());
            insert(npc, new ItemStack(Items.DIAMOND_AXE));
            insert(npc, new ItemStack(Items.DIAMOND_SWORD));
            moveNpc(npc, origin.offset(1, 0, 1));
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            npc.setStatus("guard-fixture:setup");
        } catch (RuntimeException error) {
            cleanup(player, npc, GUARD_SUITE, false);
            throw error;
        }
    }

    private static void armGuard(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, GUARD_SUITE);
        String taskId = npc.tasks().activeTaskId();
        if (taskId == null || taskId.isBlank() || taskId.startsWith("local:")) {
            throw new IllegalStateException("Guard fixture requires an active external gather task");
        }
        int progress = progressMilli(npc.tasks().observableTaskQueue(), taskId);
        if (progress <= 0 || progress >= 1000) throw new IllegalStateException("Gather task must have partial progress before combat");
        releaseHostiles(context.level(), context.origin());
        // Hostile mobs are removed almost immediately on Peaceful difficulty,
        // which made the combat pause too short for an external inspector to
        // observe. An isolated invulnerable armor stand exercises the same
        // owner-assist scheduler and attack path without depending on difficulty.
        ArmorStand hostile = EntityType.ARMOR_STAND.create(context.level());
        if (hostile == null) throw new IllegalStateException("Guard fixture hostile could not be created");
        hostile.moveTo(npc.getX() + 2.0D, npc.getY(), npc.getZ(), 0.0F, 0.0F);
        hostile.setSilent(true);
        hostile.setInvulnerable(true);
        hostile.setNoGravity(true);
        hostile.setInvisible(true);
        hostile.addTag(HOSTILE_TAG);
        if (!context.level().addFreshEntity(hostile)) throw new IllegalStateException("Guard fixture hostile was rejected");
        context.state().putString("InterruptedTask", taskId);
        context.state().putInt("InterruptedProgress", progress);
        context.marker().getPersistentData().put(STATE_KEY, context.state());
        npc.tasks().assistOwnerAgainst(player, hostile);
        npc.setStatus("guard-fixture:armed");
    }

    private static void releaseGuard(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, GUARD_SUITE);
        releaseHostiles(context.level(), context.origin());
        context.state().putBoolean("Released", true);
        context.marker().getPersistentData().put(STATE_KEY, context.state());
        npc.setStatus("guard-fixture:released");
    }

    private static void inspectGuard(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = require(player, npc, GUARD_SUITE);
        CompoundTag state = context.state();
        String interruptedTask = state.getString("InterruptedTask");
        int current = interruptedTask.isBlank() ? 0 : progressMilli(npc.tasks().observableTaskQueue(), interruptedTask);
        boolean same = !interruptedTask.isBlank() && hasTask(npc.tasks().observableTaskQueue(), interruptedTask);
        if (state.getBoolean("Released") && same && current >= state.getInt("InterruptedProgress")
            && !"combat".equals(npc.tasks().activeTaskKind())) {
            state.putBoolean("ResumeObserved", true);
            context.marker().getPersistentData().put(STATE_KEY, state);
        }
        int hostiles = context.level().getEntitiesOfClass(
            ArmorStand.class,
            new AABB(context.origin()).inflate(40.0D),
            entity -> entity.isAlive() && entity.getTags().contains(HOSTILE_TAG)
        ).size();
        npc.setStatus("guard-fixture:phase=" + ("combat".equals(npc.tasks().activeTaskKind()) ? "combat" : "work")
            + ",paused=" + npc.tasks().pausedTaskCount()
            + ",pre=" + state.getInt("InterruptedProgress")
            + ",now=" + current
            + ",hostile=" + hostiles
            + ",same=" + bool(same)
            + ",resumed=" + bool(state.getBoolean("ResumeObserved"))
            + ",logs=" + countItem(npc, Items.OAK_LOG)
            + ",breaks=" + state.getInt("GuardBreaks"));
    }

    private static FixtureContext begin(
        ServerPlayer player,
        CodexNpcEntity npc,
        String suite,
        String scenario,
        BlockPos origin,
        Set<BlockPos> modified
    ) {
        requireIdle(npc);
        ServerLevel level = player.serverLevel();
        if (!modified.isEmpty()) {
            for (BlockPos position : modified) {
                if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                    throw new IllegalStateException("Fixture site changed before setup at " + position.toShortString());
                }
            }
        }
        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Player life fixture marker could not be created");
        marker.moveTo(origin.getX() + 0.5D, origin.getY() + 18.0D, origin.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);
        CompoundTag state = new CompoundTag();
        state.putInt("Version", 1);
        state.putString("Suite", suite);
        state.putString("Scenario", scenario);
        state.putLong("Origin", origin.asLong());
        state.putUUID("NpcUuid", npc.getUUID());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putLongArray("ModifiedBlocks", longs(modified));
        state.putDouble("StartX", npc.getX());
        state.putDouble("StartY", npc.getY());
        state.putDouble("StartZ", npc.getZ());
        state.putFloat("StartYaw", npc.getYRot());
        state.putFloat("StartPitch", npc.getXRot());
        state.putByte("StartStance", npc.stance().id());
        state.putFloat("StartHealth", npc.getHealth());
        state.putInt("StartFood", npc.foodLevel());
        state.putFloat("StartSaturation", npc.saturationLevel());
        state.putFloat("StartExhaustion", npc.exhaustionLevel());
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        saveInventory(npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            restoreInventory(npc, state);
            throw new IllegalStateException("Player life fixture marker was rejected");
        }
        return new FixtureContext(marker, state, level, origin);
    }

    private static void cleanup(
        ServerPlayer player,
        CodexNpcEntity npc,
        String requestedSuite,
        boolean report
    ) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (report) npc.setNextLiveFixtureAckStatus(prefix(requestedSuite) + ":cleanup none");
            return;
        }
        requireNoTasks(npc);
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        String suite = state.getString("Suite");
        ServerLevel level = (ServerLevel) marker.level();
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        releaseHostiles(level, origin);
        for (FishingHook hook : level.getEntitiesOfClass(
            FishingHook.class,
            new AABB(origin).inflate(40.0D),
            entity -> entity.getTags().contains(FISH_HOOK_TAG)
        )) {
            Entity owner = hook.getOwner();
            if (owner != null && state.hasUUID("OwnerUuid") && state.getUUID("OwnerUuid").equals(owner.getUUID())) hook.discard();
        }
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(origin).inflate(48.0D),
            entity -> entity.getTags().contains(OUTPUT_TAG)
        )) item.discard();

        int conflicts = 0;
        long[] blocks = state.getLongArray("ModifiedBlocks");
        for (int index = blocks.length - 1; index >= 0; index--) {
            BlockPos position = BlockPos.of(blocks[index]);
            BlockState current = level.getBlockState(position);
            if (current.isAir()) continue;
            if (!isFixtureBlock(current.getBlock())) {
                conflicts++;
                continue;
            }
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        if (conflicts > 0) {
            npc.setStatus(prefix(suite) + ":cleanup conflicts=" + conflicts);
            throw new IllegalStateException("Player life fixture cleanup found unexpected blocks: " + conflicts);
        }

        npc.cancelManagedEating();
        clearInventory(npc);
        if (state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag savedNpc = state.getCompound(SAVED_NPC_KEY);
            // LivingEntity#readAdditionalSaveData merges effects into the live map.
            // Clear fixture effects first so rotten-flesh hunger cannot survive the restore.
            npc.removeAllEffects();
            if (!npc.getActiveEffects().isEmpty()) {
                throw new IllegalStateException("Player life fixture NPC effects could not be cleared before restore");
            }
            npc.load(savedNpc.copy());
            npc.getNavigation().stop();
            npc.teleportTo(state.getDouble("StartX"), state.getDouble("StartY"), state.getDouble("StartZ"));
            npc.setYRot(state.getFloat("StartYaw"));
            npc.setXRot(state.getFloat("StartPitch"));
            if (!npcSnapshotRestored(npc, state, savedNpc)) {
                npc.setStatus(prefix(suite) + ":cleanup incomplete");
                throw new IllegalStateException("Player life fixture NPC snapshot was not restored exactly");
            }
        } else {
            // Backward-compatible cleanup for a fixture created by an older deployed JAR.
            restoreInventory(npc, state);
            npc.teleportTo(state.getDouble("StartX"), state.getDouble("StartY"), state.getDouble("StartZ"));
            npc.setYRot(state.getFloat("StartYaw"));
            npc.setXRot(state.getFloat("StartPitch"));
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.setFoodLevel(state.getInt("StartFood"));
            npc.setSaturationLevel(state.getFloat("StartSaturation"));
            npc.setExhaustionLevel(state.getFloat("StartExhaustion"));
            npc.setHealth(Math.min(npc.getMaxHealth(), Math.max(1.0F, state.getFloat("StartHealth"))));
            npc.tasks().setStance(NpcTaskEngine.Stance.fromId(state.getByte("StartStance")));
        }
        state.putBoolean("Closed", true);
        marker.getPersistentData().put(STATE_KEY, state);
        marker.discard();
        if (report) npc.setNextLiveFixtureAckStatus(prefix(suite) + ":cleanup restored");
    }

    private static boolean npcSnapshotRestored(CodexNpcEntity npc, CompoundTag state, CompoundTag saved) {
        CompoundTag current = npc.saveWithoutId(new CompoundTag());
        return close(npc.getX(), state.getDouble("StartX"))
            && close(npc.getY(), state.getDouble("StartY"))
            && close(npc.getZ(), state.getDouble("StartZ"))
            && Float.compare(npc.getYRot(), state.getFloat("StartYaw")) == 0
            && Float.compare(npc.getXRot(), state.getFloat("StartPitch")) == 0
            && stableNpcFieldsMatch(saved, current);
    }

    static boolean stableNpcFieldsMatch(CompoundTag expected, CompoundTag actual) {
        return stableFieldsMatch(expected, actual,
            "UUID", "CustomName", "CustomNameVisible", "Health", "AbsorptionAmount", "ActiveEffects",
            "Motion", "FallDistance", "OnGround", "Invulnerable", "NoAI", "Silent", "Glowing",
            "HandItems", "ArmorItems", "CodexOwner", "CodexStance", "CodexDowned",
            "CodexRecoveryTicks", "CodexFood", "CodexSaturation", "CodexExhaustion",
            "CodexNaturalRegenerationTicks", "CodexEatingCompletionSequence", "CodexLastEatenName",
            "CodexAutomaticEatingUntilFull", "CodexStatus", "CodexInventory", "CodexTaskSchedulerV2",
            "CodexBoundDragon", "CodexBoundDragonDimension", "CodexBoundDragonPosition");
    }

    private static boolean stableFieldsMatch(CompoundTag expected, CompoundTag actual, String... keys) {
        for (String key : keys) {
            Tag expectedValue = expected.get(key);
            Tag actualValue = actual.get(key);
            if (expectedValue == null ? actualValue != null : !expectedValue.equals(actualValue)) return false;
        }
        return true;
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= 0.01D;
    }

    static String failureCode(RuntimeException error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("NPC snapshot was not restored")) return "npc-restore";
        if (message.contains("effects could not be cleared")) return "npc-effects";
        if (message.contains("unexpected blocks")) return "block-conflict";
        if (message.contains("no active or paused task") || message.contains("Finish NPC tasks")) return "npc-not-idle";
        if (message.contains("item use")) return "npc-using-item";
        if (message.contains("has not been set up")) return "fixture-missing";
        if (message.contains("suite mismatch")) return "suite-mismatch";
        return "fixture-failed";
    }

    private static FixtureContext require(ServerPlayer player, CodexNpcEntity npc, String suite) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Player life fixture has not been set up");
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!suite.equals(state.getString("Suite"))) throw new IllegalStateException("Player life fixture suite mismatch");
        return new FixtureContext(marker, state, (ServerLevel) marker.level(), BlockPos.of(state.getLong("Origin")));
    }

    private static BlockPos findSite(
        ServerLevel level,
        BlockPos near,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        int clearance
    ) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int centerX = near.getX() + dx;
                int centerZ = near.getZ() + dz;
                int surface = level.getMinBuildHeight();
                boolean border = true;
                for (int x = minX; x <= maxX && border; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos column = new BlockPos(centerX + x, near.getY(), centerZ + z);
                        if (!level.getWorldBorder().isWithinBounds(column)) {
                            border = false;
                            break;
                        }
                        surface = Math.max(surface, level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            column.getX(),
                            column.getZ()
                        ));
                    }
                }
                if (!border) continue;
                int y = surface + clearance + 1;
                if (y + 24 >= level.getMaxBuildHeight()) continue;
                BlockPos origin = new BlockPos(centerX, y, centerZ);
                boolean clear = true;
                for (int x = minX; x <= maxX && clear; x++) {
                    for (int z = minZ; z <= maxZ && clear; z++) {
                        for (int dy = -1; dy <= 18; dy++) {
                            BlockPos position = origin.offset(x, dy, z);
                            if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                }
                if (!clear) continue;
                AABB bounds = new AABB(
                    origin.getX() + minX,
                    origin.getY() - 1,
                    origin.getZ() + minZ,
                    origin.getX() + maxX + 1,
                    origin.getY() + 19,
                    origin.getZ() + maxZ + 1
                );
                if (level.getEntities(null, bounds).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated player life fixture site was found");
    }

    private static void requireIdle(CodexNpcEntity npc) {
        requireNoTasks(npc);
        if (npc.isManagedEating() || npc.isUsingItem()) {
            throw new IllegalStateException("Finish NPC item use before changing a player life fixture");
        }
    }

    private static ArmorStand eatingMarker(ServerLevel level, CodexNpcEntity npc) {
        ArmorStand marker = markerNear(level, npc.position(), 40.0D);
        if (marker == null) return null;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        return EAT_SUITE.equals(state.getString("Suite"))
            && state.hasUUID("NpcUuid")
            && state.getUUID("NpcUuid").equals(npc.getUUID())
            ? marker
            : null;
    }

    private static ItemStack fixtureFood(ItemStack stack) {
        stack.getOrCreateTag().putBoolean(EATING_ITEM_TAG, true);
        return stack;
    }

    private static boolean validEatingEvent(CompoundTag state, ItemStack stack) {
        if ("full".equals(state.getString("Scenario"))) return false;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.getBoolean(EATING_ITEM_TAG)) return false;
        String expected = "rotten".equals(state.getString("Scenario")) ? "rotten" : "melon";
        return expected.equals(eatingItemToken(stack));
    }

    private static String eatingItemToken(ItemStack stack) {
        if (stack.is(Items.ROTTEN_FLESH)) return "rotten";
        if (stack.is(Items.MELON_SLICE)) return "melon";
        if (stack.isEmpty()) return "none";
        return "other";
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Finish NPC tasks before changing a player life fixture");
        }
    }

    private static void prepareSetup(ServerPlayer player, CodexNpcEntity npc, String suite) {
        requireIdle(npc);
        cleanup(player, npc, suite, false);
        requireIdle(npc);
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            ArmorStand marker = level.getEntitiesOfClass(
                ArmorStand.class,
                npc.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> isActiveMarker(candidate)
                    && candidate.getPersistentData().getCompound(STATE_KEY).hasUUID("NpcUuid")
                    && candidate.getPersistentData().getCompound(STATE_KEY).getUUID("NpcUuid").equals(npc.getUUID())
            ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
            if (marker != null) return marker;
        }
        return null;
    }

    private static ArmorStand markerNear(ServerLevel level, Vec3 position, double radius) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(position, position).inflate(radius),
            PlayerLifeLiveFixture::isActiveMarker
        ).stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(position))).orElse(null);
    }

    private static boolean isActiveMarker(ArmorStand marker) {
        return marker.isAlive()
            && !marker.isRemoved()
            && marker.getTags().contains(MARKER_TAG)
            && !marker.getPersistentData().getCompound(STATE_KEY).getBoolean("Closed");
    }

    private static void saveInventory(CodexNpcEntity npc, CompoundTag state) {
        ListTag saved = new ListTag();
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", slot);
                entry.put("Stack", stack.save(new CompoundTag()));
                saved.add(entry);
            }
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        state.put("SavedInventory", saved);
    }

    private static void restoreInventory(CodexNpcEntity npc, CompoundTag state) {
        ListTag saved = state.getList("SavedInventory", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag entry = saved.getCompound(index);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < CodexNpcEntity.INVENTORY_SIZE) {
                npc.inventory().setStackInSlot(slot, ItemStack.of(entry.getCompound("Stack")));
            }
        }
    }

    private static void clearInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void insert(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = npc.insert(stack.copy());
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected player life fixture items");
    }

    private static int countItem(CodexNpcEntity npc, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Player life fixture block could not be placed at " + position.toShortString());
        }
    }

    private static Set<BlockPos> canopy(BlockPos top) {
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) + Math.abs(z) <= 3 && (x != 0 || z != 0)) result.add(top.offset(x, 0, z));
            }
        }
        result.add(top.above());
        result.add(top.above().north());
        result.add(top.above().south());
        result.add(top.above().east());
        result.add(top.above().west());
        return result;
    }

    private static void releaseHostiles(ServerLevel level, BlockPos origin) {
        for (ArmorStand hostile : level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(origin).inflate(48.0D),
            entity -> entity.getTags().contains(HOSTILE_TAG)
        )) hostile.discard();
    }

    private static int progressMilli(JsonArray workStates, String taskId) {
        for (JsonElement element : workStates) {
            JsonObject work = element.getAsJsonObject();
            if (!taskId.equals(work.get("id").getAsString())) continue;
            return (int) Math.round(work.get("progress").getAsDouble() * 1000.0D);
        }
        return 0;
    }

    private static boolean hasTask(JsonArray workStates, String taskId) {
        for (JsonElement element : workStates) {
            if (taskId.equals(element.getAsJsonObject().get("id").getAsString())) return true;
        }
        return false;
    }

    private static boolean contains(long[] values, BlockPos position) {
        long packed = position.asLong();
        for (long value : values) if (value == packed) return true;
        return false;
    }

    private static long[] longs(Iterable<BlockPos> positions) {
        List<Long> result = new ArrayList<>();
        for (BlockPos position : positions) result.add(position.asLong());
        long[] packed = new long[result.size()];
        for (int index = 0; index < result.size(); index++) packed[index] = result.get(index);
        return packed;
    }

    private static boolean isFixtureBlock(Block block) {
        return Set.of(
            Blocks.STONE,
            Blocks.DIRT,
            Blocks.FARMLAND,
            Blocks.WATER,
            Blocks.WHEAT,
            Blocks.OAK_LOG,
            Blocks.OAK_LEAVES
        ).contains(block);
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    private static String prefix(String suite) {
        return switch (suite) {
            case STATE_SUITE -> "state-fixture";
            case EAT_SUITE -> "eat-fixture";
            case FISH_SUITE -> "fish-fixture";
            case FARM_SUITE -> "farm-fixture";
            case GUARD_SUITE -> "guard-fixture";
            default -> "life-fixture";
        };
    }

    private record FixtureContext(
        ArmorStand marker,
        CompoundTag state,
        ServerLevel level,
        BlockPos origin
    ) {
    }
}
