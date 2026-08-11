package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reversible end-to-end acceptance for NPC hunting, cooking, interruption, restart, and delivery. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class FoodSurvivalLiveFixture {
    private static final String MARKER_TAG = "CodexAcceptanceFoodSurvivalMarker";
    private static final String ANIMAL_TAG = "CodexAcceptanceFoodSurvivalAnimal";
    private static final String PROTECTED_TAG = "CodexAcceptanceFoodSurvivalProtected";
    private static final String HOSTILE_TAG = "CodexAcceptanceFoodSurvivalHostile";
    private static final String ITEM_ENTITY_TAG = "CodexAcceptanceFoodSurvivalDrop";
    private static final String PRESERVED_ITEM_TAG = "CodexAcceptanceFoodSurvivalPreservedItem";
    private static final String ITEM_TAG = "CodexAcceptanceFoodSurvivalItem";
    private static final String STATE_KEY = "CodexAcceptanceFoodSurvivalState";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final int PLATFORM_RADIUS = 10;
    private static final int SEARCH_LIMIT = 512;
    private static final int LEGACY_TARGET_COUNT = 4;
    private static final int STRICT_TARGET_COUNT = 16;
    private static final int PROTECTED_ANIMAL_COUNT = 3;
    private static final int[] SITE_OFFSETS = { 64, -64, 96, -96, 128, -128, 160, -160, 192, -192 };

    private FoodSurvivalLiveFixture() {
    }

    record Evidence(
        int attacks,
        int kills,
        int rawDrops,
        boolean inputObserved,
        boolean litObserved,
        boolean outputObserved,
        int withdrawn,
        boolean guardObserved,
        boolean resumeObserved,
        boolean restartObserved,
        int survivingAdults,
        int protectedAlive,
        int violations,
        int physicalDelivered,
        boolean sameTaskObserved,
        int targetCount,
        int huntableCount
    ) {
    }

    enum FurnaceContentKind {
        RAW_BEEF,
        COOKED_BEEF,
        FUEL,
        FUEL_RESIDUE,
        OTHER
    }

    record FurnaceCleanupEvidence(
        boolean initiallyEmpty,
        boolean inputObserved,
        boolean fuelObserved,
        boolean litObserved,
        int rawDrops,
        int withdrawn,
        int remainingTaggedRaw,
        int suppliedFuel
    ) {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Food survival fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc, LEGACY_TARGET_COUNT);
            case "setup-16" -> setup(player, npc, STRICT_TARGET_COUNT);
            case "inspect" -> inspect(player, npc);
            case "arm-guard" -> armGuard(player, npc);
            case "release-guard" -> releaseGuard(player, npc);
            case "checkpoint" -> checkpoint(player, npc);
            case "verify-restart" -> verifyRestart(player, npc);
            case "recover-cleanup" -> recoverCleanup(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown food survival fixture mode");
        }
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void recordNpcAttack(LivingHurtEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Cow cow)
            || !cow.getTags().contains(ANIMAL_TAG) || !(cow.level() instanceof ServerLevel level)) return;
        ArmorStand marker = markerNear(level, cow.position());
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        Entity source = event.getSource().getEntity();
        boolean expectedActor = source instanceof CodexNpcEntity npc
            && state.hasUUID("NpcUuid")
            && state.getUUID("NpcUuid").equals(npc.getUUID());
        boolean protectedAnimal = cow.getTags().contains(PROTECTED_TAG);
        if (expectedActor && !protectedAnimal) {
            state.putInt("Attacks", state.getInt("Attacks") + 1);
        } else {
            state.putInt("Violations", state.getInt("Violations") + 1);
        }
        updateState(marker, state);
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    public static void recordAnimalDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof Cow cow)
            || !cow.getTags().contains(ANIMAL_TAG) || !(cow.level() instanceof ServerLevel level)) return;
        ArmorStand marker = markerNear(level, cow.position());
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        Entity source = event.getSource().getEntity();
        boolean expectedActor = source instanceof CodexNpcEntity npc
            && state.hasUUID("NpcUuid")
            && state.getUUID("NpcUuid").equals(npc.getUUID());
        if (expectedActor && !cow.getTags().contains(PROTECTED_TAG)) {
            state.putInt("Kills", state.getInt("Kills") + 1);
        } else {
            state.putInt("Violations", state.getInt("Violations") + 1);
        }
        updateState(marker, state);
    }

    @SubscribeEvent
    public static void tagAnimalDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Cow cow) || !cow.getTags().contains(ANIMAL_TAG)
            || !(cow.level() instanceof ServerLevel level)) return;
        ArmorStand marker = markerNear(level, cow.position());
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        int raw = 0;
        for (ItemEntity item : event.getDrops()) {
            item.addTag(ITEM_ENTITY_TAG);
            item.getItem().getOrCreateTag().putBoolean(ITEM_TAG, true);
            if (item.getItem().is(Items.BEEF)) raw += item.getItem().getCount();
        }
        state.putInt("RawDrops", state.getInt("RawDrops") + raw);
        updateState(marker, state);
    }

    @SubscribeEvent
    public static void suppressFixtureExperience(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof Cow cow && cow.getTags().contains(ANIMAL_TAG)) {
            event.setDroppedExperience(0);
        }
    }

    static void observeFurnace(
        CodexNpcEntity npc,
        BlockPos position,
        AbstractFurnaceBlockEntity furnace,
        String inputId,
        String outputId
    ) {
        ArmorStand marker = markerForNpc(npc);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (position == null || !position.equals(BlockPos.of(state.getLong("Furnace")))) return;
        if (!"minecraft:beef".equals(inputId) || !"minecraft:cooked_beef".equals(outputId)) {
            state.putInt("Violations", state.getInt("Violations") + 1);
            updateState(marker, state);
            return;
        }

        ItemStack input = furnace.getItem(0);
        if (!input.isEmpty()) {
            if (input.is(Items.BEEF) && isFixtureStack(input)) state.putBoolean("InputObserved", true);
            else state.putInt("Violations", state.getInt("Violations") + 1);
        }
        ItemStack fuel = furnace.getItem(1);
        if (!fuel.isEmpty()) {
            if (isFixtureStack(fuel) && (fuel.is(Items.COAL) || fuel.is(Items.CHARCOAL))) {
                state.putBoolean("FuelObserved", true);
            } else {
                state.putInt("Violations", state.getInt("Violations") + 1);
            }
        }
        BlockState furnaceState = npc.level().getBlockState(position);
        if (state.getBoolean("InputObserved")
            && furnaceState.hasProperty(AbstractFurnaceBlock.LIT)
            && furnaceState.getValue(AbstractFurnaceBlock.LIT)) {
            state.putBoolean("LitObserved", true);
        }
        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty()) {
            if (output.is(Items.COOKED_BEEF) && state.getBoolean("InputObserved")) {
                output.getOrCreateTag().putBoolean(ITEM_TAG, true);
                state.putBoolean("OutputObserved", true);
                state.putInt("OutputPeak", Math.max(state.getInt("OutputPeak"), output.getCount()));
                furnace.setChanged();
            } else {
                state.putInt("Violations", state.getInt("Violations") + 1);
            }
        }
        updateState(marker, state);
    }

    static void recordCookedWithdrawal(CodexNpcEntity npc, BlockPos position, int moved) {
        if (moved <= 0) return;
        ArmorStand marker = markerForNpc(npc);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (position == null || !position.equals(BlockPos.of(state.getLong("Furnace")))) return;
        if (!state.getBoolean("OutputObserved")) state.putInt("Violations", state.getInt("Violations") + 1);
        state.putInt("Withdrawn", state.getInt("Withdrawn") + moved);
        updateState(marker, state);
    }

    static void recordDelivery(CodexNpcEntity npc, ServerPlayer recipient, ItemStack transfer) {
        ArmorStand marker = markerForNpc(npc);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        boolean expectedRecipient = state.hasUUID("OwnerUuid")
            && state.getUUID("OwnerUuid").equals(recipient.getUUID());
        if (expectedRecipient && transfer.is(Items.COOKED_BEEF) && isFixtureStack(transfer)) {
            state.putInt("DeliveryAttempts", state.getInt("DeliveryAttempts") + transfer.getCount());
        } else {
            state.putInt("Violations", state.getInt("Violations") + 1);
        }
        updateState(marker, state);
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc, int requestedTargetCount) {
        int targetCount = requireTargetCount(requestedTargetCount);
        int huntableCount = targetCount + FoodProvisionPolicy.BREEDING_RESERVE;
        cleanup(player, npc, false);
        requireSafeSetup(player, npc);
        ServerLevel level = player.serverLevel();
        BlockPos origin = findIsolatedOrigin(level, npc.blockPosition(), player);
        BlockPos furnace = origin.offset(-7, 0, 0);
        Set<BlockPos> modified = fixtureBlocks(origin, furnace);

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Food survival fixture marker could not be created");
        marker.moveTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 3);
        state.putInt("TargetCount", targetCount);
        state.putInt("HuntableCount", huntableCount);
        state.putString("Dimension", level.dimension().location().toString());
        state.putLong("Origin", origin.asLong());
        state.putLong("Furnace", furnace.asLong());
        state.putInt("ExpectedBlockCount", modified.size());
        state.putLongArray("ModifiedBlocks", new long[0]);
        state.putUUID("NpcUuid", npc.getUUID());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putDouble("StartX", npc.getX());
        state.putDouble("StartY", npc.getY());
        state.putDouble("StartZ", npc.getZ());
        state.putFloat("StartYaw", npc.getYRot());
        state.putFloat("StartPitch", npc.getXRot());
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) throw new IllegalStateException("Food survival fixture marker was rejected");

        try {
            for (BlockPos position : modified) {
                set(level, marker, state, position, fixtureBlockState(position, origin, furnace));
            }
            if (state.getLongArray("ModifiedBlocks").length != state.getInt("ExpectedBlockCount")) {
                throw new IllegalStateException("Food survival fixture block placement was incomplete");
            }
            if (!(level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity fixtureFurnace)) {
                throw new IllegalStateException("Food survival fixture furnace could not be created");
            }
            for (int slot = 0; slot < fixtureFurnace.getContainerSize(); slot++) {
                if (!fixtureFurnace.getItem(slot).isEmpty()) {
                    throw new IllegalStateException("Food survival fixture furnace was not initially empty");
                }
            }
            state.putBoolean("FurnaceInitiallyEmpty", true);
            int fuelCount = Math.max(1, (targetCount + 7) / 8);
            state.putInt("FuelSupplied", fuelCount);
            updateState(marker, state);

            npc.cancelManagedEating();
            npc.removeAllEffects();
            clearInventory(npc);
            state.putBoolean("NpcMutated", true);
            updateState(marker, state);
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.getOrCreateTag().putBoolean("Unbreakable", true);
            insertFixtureStack(npc, sword);
            insertFixtureStack(npc, new ItemStack(Items.COAL, fuelCount));
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            moveNpc(npc, origin.offset(-5, 0, 0));
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);

            List<Cow> ordinary = new ArrayList<>();
            for (BlockPos position : ordinaryCowPositions(origin, huntableCount)) {
                ordinary.add(spawnCow(level, position, false, false, marker));
            }
            spawnCow(level, origin.offset(-1, 0, 5), true, false, marker);
            Cow named = spawnCow(level, origin.offset(-3, 0, 5), false, true, marker);
            named.setCustomName(Component.literal("AcceptanceProtectedCow"));
            Cow leashed = spawnCow(level, origin.offset(-5, 0, 6), false, true, marker);
            leashed.setLeashedTo(marker, true);
            for (Cow cow : ordinary) {
                if (npc.getNavigation().createPath(cow, 0) == null) {
                    throw new IllegalStateException("Food survival fixture cow is not reachable by the NPC");
                }
            }
            state.putBoolean("SetupComplete", true);
            updateState(marker, state);
            npc.setNextLiveFixtureAckStatus("food-survival:setup adults=" + huntableCount
                + ",protected=" + PROTECTED_ANIMAL_COUNT + ",target=" + targetCount + ",furnace=empty");
        } catch (RuntimeException error) {
            try {
                cleanup(player, npc, false);
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        NpcTaskEngine.ProvisionFoodDiagnostics diagnostics = npc.tasks().provisionFoodDiagnosticsForFixture();
        String taskId = state.getString("TaskId");
        if (!taskId.isBlank() && taskId.equals(diagnostics.taskId())) {
            state.putBoolean("SameTaskObserved", true);
            if (state.getBoolean("GuardReleased") && !diagnostics.paused()) {
                state.putBoolean("ResumeObserved", true);
            }
        }
        updateState(marker, state);
        Evidence evidence = evidence(player, npc, marker, state);
        npc.setNextLiveFixtureAckStatus(inspectionStatus(evidence));
    }

    private static void armGuard(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        NpcTaskEngine.ProvisionFoodDiagnostics diagnostics = requireCookingTask(npc);
        if (!state.getBoolean("InputObserved") || !state.getBoolean("LitObserved")) {
            throw new IllegalStateException("Food survival fixture furnace has not started cooking");
        }
        if (!state.getString("TaskId").isBlank() && !state.getString("TaskId").equals(diagnostics.taskId())) {
            throw new IllegalStateException("Food survival fixture task identity changed before guard interruption");
        }
        releaseHostiles((ServerLevel) marker.level());
        ArmorStand hostile = EntityType.ARMOR_STAND.create((ServerLevel) marker.level());
        if (hostile == null) throw new IllegalStateException("Food survival guard target could not be created");
        hostile.moveTo(npc.getX() + 2.0D, npc.getY(), npc.getZ(), 0.0F, 0.0F);
        hostile.setSilent(true);
        hostile.setInvulnerable(true);
        hostile.setNoGravity(true);
        hostile.setInvisible(true);
        hostile.addTag(HOSTILE_TAG);
        if (!marker.level().addFreshEntity(hostile)) {
            throw new IllegalStateException("Food survival guard target was rejected");
        }
        state.putString("TaskId", diagnostics.taskId());
        state.putInt("GuardCooked", diagnostics.cookedCount());
        state.putBoolean("GuardObserved", true);
        updateState(marker, state);
        npc.tasks().assistOwnerAgainst(player, hostile);
        if (!npc.tasks().provisionFoodDiagnosticsForFixture().paused()) {
            throw new IllegalStateException("Food survival task did not pause for owner protection");
        }
        npc.setNextLiveFixtureAckStatus("food-survival:guard armed=1,same=1");
    }

    private static void releaseGuard(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        releaseHostiles((ServerLevel) marker.level());
        state.putBoolean("GuardReleased", true);
        updateState(marker, state);
        npc.setNextLiveFixtureAckStatus("food-survival:guard released=1");
    }

    private static void checkpoint(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        NpcTaskEngine.ProvisionFoodDiagnostics diagnostics = requireCookingTask(npc);
        if (!state.getBoolean("GuardObserved") || !state.getBoolean("ResumeObserved")) {
            throw new IllegalStateException("Food survival fixture guard interruption has not resumed");
        }
        if (diagnostics.loaded() <= 0 || diagnostics.cookingTargetCount() < targetCount(state)) {
            throw new IllegalStateException("Food survival fixture cooking checkpoint is incomplete");
        }
        if (!state.getString("TaskId").equals(diagnostics.taskId())) {
            throw new IllegalStateException("Food survival fixture task identity changed at checkpoint");
        }
        state.putBoolean("RestartCheckpointed", true);
        state.putInt("CheckpointLoaded", diagnostics.loaded());
        state.putInt("CheckpointCooked", diagnostics.cookedCount());
        updateState(marker, state);
        npc.setNextLiveFixtureAckStatus("food-survival:checkpoint loaded=" + diagnostics.loaded()
            + ",cooked=" + diagnostics.cookedCount());
    }

    private static void verifyRestart(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        if (!state.getBoolean("RestartCheckpointed")) {
            throw new IllegalStateException("Food survival fixture restart checkpoint is missing");
        }
        NpcTaskEngine.ProvisionFoodDiagnostics diagnostics = npc.tasks().provisionFoodDiagnosticsForFixture();
        if (diagnostics.taskId().isBlank() || !state.getString("TaskId").equals(diagnostics.taskId())) {
            throw new IllegalStateException("Food survival fixture task identity changed after restart");
        }
        boolean cookingAdvanced = diagnostics.loaded() >= state.getInt("CheckpointLoaded")
            && diagnostics.cookedCount() >= state.getInt("CheckpointCooked");
        boolean cookingFinished = diagnostics.foodPhase() == 2 && diagnostics.completed() >= targetCount(state);
        if (!cookingAdvanced && !cookingFinished) {
            throw new IllegalStateException("Food survival fixture cooking checkpoint regressed after restart");
        }
        state.putBoolean("RestartObserved", true);
        state.putBoolean("SameTaskObserved", true);
        updateState(marker, state);
        npc.setNextLiveFixtureAckStatus("food-survival:restart same=1,phase=" + diagnostics.foodPhase()
            + ",cooked=" + diagnostics.cookedCount());
    }

    private static NpcTaskEngine.ProvisionFoodDiagnostics requireCookingTask(CodexNpcEntity npc) {
        NpcTaskEngine.ProvisionFoodDiagnostics diagnostics = npc.tasks().provisionFoodDiagnosticsForFixture();
        if (diagnostics.taskId().isBlank() || !"minecraft:beef".equals(diagnostics.inputId())
            || !"minecraft:cooked_beef".equals(diagnostics.outputId())
            || diagnostics.workstation() == null) {
            throw new IllegalStateException("Food survival fixture requires active beef cooking");
        }
        return diagnostics;
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (report) npc.setNextLiveFixtureAckStatus("food-survival:cleanup none");
            return;
        }
        if (marker.level() != player.level() || marker.level() != npc.level()) {
            throw new IllegalStateException("Food survival fixture cleanup requires the original dimension");
        }
        ServerLevel level = (ServerLevel) marker.level();
        releaseHostiles(level);
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        BlockPos furnacePos = BlockPos.of(state.getLong("Furnace"));

        if (!state.getBoolean("WorldCleared")) {
            boolean legacyPartial = !state.contains("ExpectedBlockCount", Tag.TAG_INT);
            boolean cleanupStarted = state.getBoolean("CleanupStarted");
            int worldConflicts = blockConflicts(level, state, origin, furnacePos, legacyPartial || cleanupStarted);
            if (worldConflicts > 0) {
                throw new IllegalStateException("Food survival fixture cleanup found world-block-conflict");
            }

            BlockEntity furnaceEntity = level.getBlockEntity(furnacePos);
            if (furnaceEntity instanceof AbstractFurnaceBlockEntity furnace) {
                FurnaceCleanupEvidence furnaceEvidence = furnaceCleanupEvidence(
                    state,
                    fixtureRawBeefCount(player, npc, level, furnace)
                );
                for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
                    ItemStack stack = furnace.getItem(slot);
                    if (!stack.isEmpty()) {
                        FurnaceContentKind kind = furnaceContentKind(stack);
                        boolean tagged = isFixtureStack(stack);
                        if (!fixtureFurnaceContentAllowed(
                            slot,
                            kind,
                            stack.getCount(),
                            tagged,
                            furnaceEvidence
                        )) {
                            throw new IllegalStateException(
                                "Food survival fixture cleanup found "
                                    + furnaceContentConflictCode(slot, kind, tagged)
                            );
                        }
                    }
                }
            }
            if (state.getBoolean("NpcMutated")) {
                for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
                    ItemStack stack = npc.inventory().getStackInSlot(slot);
                    if (!stack.isEmpty() && !isFixtureStack(stack)) {
                        throw new IllegalStateException("Food survival fixture cleanup found npc-inventory-conflict");
                    }
                }
            }
            state.putBoolean("CleanupStarted", true);
            updateState(marker, state);

            removeFixtureStacks(player, npc, level, state);
            List<Entity> discard = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains(ANIMAL_TAG)
                    || entity.getTags().contains(HOSTILE_TAG)
                    || entity.getTags().contains(ITEM_ENTITY_TAG)) {
                    discard.add(entity);
                }
            }
            for (Entity entity : discard) entity.discard();
            for (int index = state.getLongArray("ModifiedBlocks").length - 1; index >= 0; index--) {
                BlockPos position = BlockPos.of(state.getLongArray("ModifiedBlocks")[index]);
                if (expectedFixtureBlock(level.getBlockState(position), position, origin, furnacePos)) {
                    if (!level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState())
                        && !level.getBlockState(position).isAir()) {
                        throw new IllegalStateException("Food survival fixture block could not be cleared");
                    }
                }
            }
            for (long packed : state.getLongArray("ModifiedBlocks")) {
                if (!level.getBlockState(BlockPos.of(packed)).isAir()) {
                    throw new IllegalStateException("Food survival fixture block cleanup was incomplete");
                }
            }
            state.putBoolean("WorldCleared", true);
            updateState(marker, state);
        }

        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Food survival fixture NPC snapshot is missing");
        }
        CompoundTag savedNpc = state.getCompound(SAVED_NPC_KEY);
        npc.cancelManagedEating();
        npc.removeAllEffects();
        if (!npc.getActiveEffects().isEmpty()) {
            throw new IllegalStateException("Food survival fixture NPC effects could not be cleared");
        }
        npc.load(savedNpc.copy());
        npc.getNavigation().stop();
        npc.teleportTo(state.getDouble("StartX"), state.getDouble("StartY"), state.getDouble("StartZ"));
        npc.setYRot(state.getFloat("StartYaw"));
        npc.setXRot(state.getFloat("StartPitch"));
        CompoundTag restored = npc.saveWithoutId(new CompoundTag());
        if (!close(npc.getX(), state.getDouble("StartX"))
            || !close(npc.getY(), state.getDouble("StartY"))
            || !close(npc.getZ(), state.getDouble("StartZ"))
            || !PlayerLifeLiveFixture.stableNpcFieldsMatch(savedNpc, restored)) {
            throw new IllegalStateException("Food survival fixture NPC snapshot was not restored exactly");
        }
        state.putBoolean("Closed", true);
        updateState(marker, state);
        marker.discard();
        if (report) npc.setNextLiveFixtureAckStatus("food-survival:cleanup restored");
    }

    private static void recoverCleanup(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = (ServerLevel) marker.level();
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        BlockPos furnacePos = BlockPos.of(state.getLong("Furnace"));
        AbstractFurnaceBlockEntity furnace = null;
        int recoverableSlot = -1;
        List<ItemStack> preservedStacks = new ArrayList<>();
        if (!state.getBoolean("WorldCleared")) {
            boolean legacyPartial = !state.contains("ExpectedBlockCount", Tag.TAG_INT);
            if (blockConflicts(level, state, origin, furnacePos,
                legacyPartial || state.getBoolean("CleanupStarted")) > 0) {
                throw new IllegalStateException("Food survival fixture recovery found world-block-conflict");
            }
            BlockEntity furnaceEntity = level.getBlockEntity(furnacePos);
            if (!(furnaceEntity instanceof AbstractFurnaceBlockEntity currentFurnace)) {
                throw new IllegalStateException("Food survival fixture recovery furnace is unavailable");
            }
            furnace = currentFurnace;
            FurnaceCleanupEvidence evidence = furnaceCleanupEvidence(
                state,
                fixtureRawBeefCount(player, npc, level, furnace)
            );
            for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
                ItemStack stack = furnace.getItem(slot);
                if (stack.isEmpty()) continue;
                FurnaceContentKind kind = furnaceContentKind(stack);
                boolean tagged = isFixtureStack(stack);
                if (fixtureFurnaceContentAllowed(slot, kind, stack.getCount(), tagged, evidence)) continue;
                if (recoverableFuelSlotConflict(slot, stack.getCount(), evidence)
                    && recoverableSlot < 0) {
                    recoverableSlot = slot;
                    preservedStacks.add(stack.copy());
                    continue;
                }
                throw new IllegalStateException(
                    "Food survival fixture recovery found " + furnaceContentConflictCode(slot, kind, tagged)
                );
            }
        }

        List<Integer> preservedNpcSlots = new ArrayList<>();
        if (state.getBoolean("NpcMutated")) {
            for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
                ItemStack stack = npc.inventory().getStackInSlot(slot);
                if (shouldPreserveNpcInventoryConflict(true, stack.isEmpty(), isFixtureStack(stack))) {
                    preservedNpcSlots.add(slot);
                    preservedStacks.add(stack.copy());
                }
            }
        }
        if (preservedStacks.isEmpty()) {
            cleanup(player, npc, false);
            npc.setNextLiveFixtureAckStatus("food-survival:recover restored,stacks=0,items=0");
            return;
        }

        List<ItemEntity> preservedEntities = new ArrayList<>();
        for (ItemStack preservedStack : preservedStacks) {
            ItemEntity preserved = new ItemEntity(
                level,
                player.getX(),
                player.getY() + 0.5D,
                player.getZ(),
                preservedStack.copy()
            );
            preserved.setPickUpDelay(0);
            preserved.addTag(PRESERVED_ITEM_TAG);
            if (!level.addFreshEntity(preserved)) {
                preservedEntities.forEach(Entity::discard);
                throw new IllegalStateException("Food survival fixture recovery could not preserve every conflict item");
            }
            preservedEntities.add(preserved);
        }
        if (recoverableSlot >= 0 && furnace != null) {
            furnace.setItem(recoverableSlot, ItemStack.EMPTY);
            furnace.setChanged();
        }
        for (int slot : preservedNpcSlots) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        int preservedItems = preservedStacks.stream().mapToInt(ItemStack::getCount).sum();
        cleanup(player, npc, false);
        npc.setNextLiveFixtureAckStatus("food-survival:recover restored,stacks="
            + preservedStacks.size() + ",items=" + preservedItems);
    }

    static boolean shouldPreserveNpcInventoryConflict(
        boolean npcMutated,
        boolean stackEmpty,
        boolean fixtureTagged
    ) {
        return npcMutated && !stackEmpty && !fixtureTagged;
    }

    private static Evidence evidence(
        ServerPlayer player,
        CodexNpcEntity npc,
        ArmorStand marker,
        CompoundTag state
    ) {
        int survivingAdults = 0;
        int protectedAlive = 0;
        for (Cow cow : fixtureCows((ServerLevel) marker.level(), marker)) {
            if (cow.getTags().contains(PROTECTED_TAG)) protectedAlive++;
            else if (!cow.isBaby()) survivingAdults++;
        }
        return new Evidence(
            state.getInt("Attacks"),
            state.getInt("Kills"),
            state.getInt("RawDrops"),
            state.getBoolean("InputObserved"),
            state.getBoolean("LitObserved"),
            state.getBoolean("OutputObserved"),
            state.getInt("Withdrawn"),
            state.getBoolean("GuardObserved"),
            state.getBoolean("ResumeObserved"),
            state.getBoolean("RestartObserved"),
            survivingAdults,
            protectedAlive,
            state.getInt("Violations"),
            physicalDeliveryCount(player),
            state.getBoolean("SameTaskObserved"),
            targetCount(state),
            huntableCount(state)
        );
    }

    static String inspectionStatus(Evidence value) {
        return "food-survival:a=" + value.attacks()
            + ",k=" + value.kills()
            + ",r=" + value.rawDrops()
            + ",i=" + bool(value.inputObserved())
            + ",l=" + bool(value.litObserved())
            + ",o=" + bool(value.outputObserved())
            + ",w=" + value.withdrawn()
            + ",g=" + bool(value.guardObserved())
            + ",u=" + bool(value.resumeObserved())
            + ",x=" + bool(value.restartObserved())
            + ",s=" + value.survivingAdults()
            + ",p=" + value.protectedAlive()
            + ",v=" + value.violations()
            + ",d=" + value.physicalDelivered()
            + ",t=" + bool(value.sameTaskObserved())
            + ",q=" + value.targetCount()
            + ",h=" + value.huntableCount();
    }

    static boolean completeEvidence(Evidence value) {
        return value.attacks() >= value.kills()
            && value.kills() >= 1
            && value.targetCount() >= 1
            && value.huntableCount() == value.targetCount() + FoodProvisionPolicy.BREEDING_RESERVE
            && value.kills() + value.survivingAdults() == value.huntableCount()
            && value.rawDrops() >= value.targetCount()
            && value.inputObserved()
            && value.litObserved()
            && value.outputObserved()
            && value.withdrawn() >= value.targetCount()
            && value.guardObserved()
            && value.resumeObserved()
            && value.restartObserved()
            && value.survivingAdults() >= FoodProvisionPolicy.BREEDING_RESERVE
            && value.protectedAlive() == PROTECTED_ANIMAL_COUNT
            && value.violations() == 0
            && value.physicalDelivered() == value.targetCount()
            && value.sameTaskObserved();
    }

    static String failureCode(RuntimeException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        if (message.contains("world-block-conflict")) return "world-block-conflict";
        if (message.contains("furnace-input-kind-conflict")) return "furnace-input-kind-conflict";
        if (message.contains("furnace-input-source-conflict")) return "furnace-input-source-conflict";
        if (message.contains("furnace-input-conflict")) return "furnace-input-conflict";
        if (message.contains("furnace-fuel-kind-conflict")) return "furnace-fuel-kind-conflict";
        if (message.contains("furnace-fuel-source-conflict")) return "furnace-fuel-source-conflict";
        if (message.contains("furnace-fuel-conflict")) return "furnace-fuel-conflict";
        if (message.contains("furnace-output-kind-conflict")) return "furnace-output-kind-conflict";
        if (message.contains("furnace-output-ledger-conflict")) return "furnace-output-ledger-conflict";
        if (message.contains("furnace-content-conflict")) return "furnace-content-conflict";
        if (message.contains("npc-inventory-conflict")) return "npc-inventory-conflict";
        if (message.contains("snapshot") || message.contains("effects")) return "npc-restore";
        if (message.contains("protected")) return "protected-animal";
        if (message.contains("idle npc task scheduler")) return "npc-not-idle";
        if (message.contains("task") || message.contains("checkpoint")) return "task-state";
        if (message.contains("furnace") || message.contains("cooking")) return "furnace-state";
        if (message.contains("dimension")) return "dimension";
        if (message.contains("isolated") || message.contains("site")) return "site-unavailable";
        if (message.contains("reachable")) return "npc-path-unavailable";
        if (message.contains("block") || message.contains("unexpected state")) return "block-conflict";
        if (message.contains("idle item use")) return "npc-using-item";
        if (message.contains("survival materials")) return "npc-creative-resources";
        if (message.contains("dismounted")) return "npc-mounted";
        if (message.contains("living actors")) return "actor-unavailable";
        if (message.contains("backpack is full")) return "inventory-full";
        if (message.contains("has not been set up")) return "fixture-missing";
        return "fixture-failed";
    }

    private static int physicalDeliveryCount(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(Items.COOKED_BEEF) && isFixtureStack(stack)) count += stack.getCount();
        }
        for (ItemEntity item : player.serverLevel().getEntitiesOfClass(
            ItemEntity.class,
            player.getBoundingBox().inflate(8.0D),
            entity -> entity.getItem().is(Items.COOKED_BEEF) && isFixtureStack(entity.getItem())
        )) count += item.getItem().getCount();
        return count;
    }

    private static void removeFixtureStacks(
        ServerPlayer player,
        CodexNpcEntity npc,
        ServerLevel level,
        CompoundTag state
    ) {
        BlockEntity blockEntity = level.getBlockEntity(BlockPos.of(state.getLong("Furnace")));
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            FurnaceCleanupEvidence furnaceEvidence = furnaceCleanupEvidence(
                state,
                fixtureRawBeefCount(player, npc, level, furnace)
            );
            for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
                ItemStack stack = furnace.getItem(slot);
                if (fixtureFurnaceContentAllowed(
                    slot,
                    furnaceContentKind(stack),
                    stack.getCount(),
                    isFixtureStack(stack),
                    furnaceEvidence
                )) {
                    furnace.setItem(slot, ItemStack.EMPTY);
                }
            }
            furnace.setChanged();
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isFixtureStack(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            if (isFixtureStack(npc.inventory().getStackInSlot(slot))) {
                npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item && isFixtureStack(item.getItem())) item.discard();
        }
    }

    private static FurnaceCleanupEvidence furnaceCleanupEvidence(CompoundTag state, int remainingTaggedRaw) {
        // Version 2 markers predate the explicit flag but already created a fresh, empty furnace.
        boolean initiallyEmpty = state.getBoolean("FurnaceInitiallyEmpty")
            || state.getInt("Version") >= 2 && state.getBoolean("SetupComplete");
        return new FurnaceCleanupEvidence(
            initiallyEmpty,
            state.getBoolean("InputObserved"),
            state.getBoolean("FuelObserved"),
            state.getBoolean("LitObserved"),
            state.getInt("RawDrops"),
            state.getInt("Withdrawn"),
            remainingTaggedRaw,
            state.contains("FuelSupplied", Tag.TAG_INT)
                ? Math.max(0, state.getInt("FuelSupplied"))
                : state.getInt("Version") >= 2 && state.getBoolean("SetupComplete") ? 1 : 0
        );
    }

    static boolean fixtureFurnaceContentAllowed(
        int slot,
        FurnaceContentKind kind,
        int count,
        boolean fixtureTagged,
        FurnaceCleanupEvidence evidence
    ) {
        if (count <= 0) return false;
        if (fixtureTagged) {
            return slot == 0 && kind == FurnaceContentKind.RAW_BEEF
                || slot == 1 && kind == FurnaceContentKind.FUEL
                || slot == 2 && kind == FurnaceContentKind.COOKED_BEEF;
        }
        // Version 2 cancellation could move the fixture's tagged coal through a vanilla
        // furnace path that discarded its NBT. Accept only the owned, initially-empty
        // furnace's observed fuel slot, bounded by the one-item setup ledger.
        if (slot == 1 && kind == FurnaceContentKind.FUEL && evidence != null) {
            return evidence.initiallyEmpty()
                && evidence.fuelObserved()
                && evidence.suppliedFuel() > 0
                && count <= evidence.suppliedFuel();
        }
        // Smelting does not copy input NBT to its output. A cancel/restart can therefore expose
        // cooked beef before observeFurnace tags it; the closed raw-food ledger proves its origin.
        if (slot != 2 || kind != FurnaceContentKind.COOKED_BEEF || evidence == null
            || !evidence.initiallyEmpty() || !evidence.inputObserved()
            || !evidence.litObserved()
            || evidence.rawDrops() < 0 || evidence.withdrawn() < 0 || evidence.remainingTaggedRaw() < 0) {
            return false;
        }
        long accounted = (long) evidence.withdrawn() + evidence.remainingTaggedRaw() + count;
        return accounted <= evidence.rawDrops();
    }

    static boolean recoverableFuelSlotConflict(
        int slot,
        int count,
        FurnaceCleanupEvidence evidence
    ) {
        return slot == 1
            && count > 0
            && count <= 64
            && evidence != null
            && evidence.initiallyEmpty();
    }

    static String furnaceContentConflictCode(int slot, FurnaceContentKind kind, boolean fixtureTagged) {
        if (slot == 0 && kind != FurnaceContentKind.RAW_BEEF) return "furnace-input-kind-conflict";
        if (slot == 0 && !fixtureTagged) return "furnace-input-source-conflict";
        if (slot == 0) return "furnace-input-conflict";
        if (slot == 1 && kind != FurnaceContentKind.FUEL) return "furnace-fuel-kind-conflict";
        if (slot == 1 && !fixtureTagged) return "furnace-fuel-source-conflict";
        if (slot == 1) return "furnace-fuel-conflict";
        if (slot == 2 && kind != FurnaceContentKind.COOKED_BEEF) {
            return "furnace-output-kind-conflict";
        }
        if (slot == 2 && kind == FurnaceContentKind.COOKED_BEEF && !fixtureTagged) {
            return "furnace-output-ledger-conflict";
        }
        return "furnace-content-conflict";
    }

    private static FurnaceContentKind furnaceContentKind(ItemStack stack) {
        if (stack.is(Items.BEEF)) return FurnaceContentKind.RAW_BEEF;
        if (stack.is(Items.COOKED_BEEF)) return FurnaceContentKind.COOKED_BEEF;
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) return FurnaceContentKind.FUEL;
        if (AbstractFurnaceBlockEntity.isFuel(stack) || stack.is(Items.BUCKET)) {
            return FurnaceContentKind.FUEL_RESIDUE;
        }
        return FurnaceContentKind.OTHER;
    }

    private static int fixtureRawBeefCount(
        ServerPlayer player,
        CodexNpcEntity npc,
        ServerLevel level,
        AbstractFurnaceBlockEntity furnace
    ) {
        int count = fixtureRawBeefCount(furnace.getItem(0));
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            count += fixtureRawBeefCount(player.getInventory().getItem(slot));
        }
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            count += fixtureRawBeefCount(npc.inventory().getStackInSlot(slot));
        }
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity item) count += fixtureRawBeefCount(item.getItem());
        }
        return count;
    }

    private static int fixtureRawBeefCount(ItemStack stack) {
        return stack.is(Items.BEEF) && isFixtureStack(stack) ? stack.getCount() : 0;
    }

    private static void requireSafeSetup(ServerPlayer player, CodexNpcEntity npc) {
        if (player.level() != npc.level()) throw new IllegalStateException("Food survival fixture requires one dimension");
        if (!player.isAlive() || !npc.isAlive() || npc.isDowned()) {
            throw new IllegalStateException("Food survival fixture requires living actors");
        }
        requireNoTasks(npc);
        if (npc.isManagedEating() || npc.isUsingItem()) {
            throw new IllegalStateException("Food survival fixture requires idle item use");
        }
        if (npc.creativeResources()) throw new IllegalStateException("Food survival fixture requires survival materials");
        if (npc.isPassenger() || !npc.getPassengers().isEmpty()) {
            throw new IllegalStateException("Food survival fixture requires a dismounted NPC");
        }
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        if (!npc.tasks().activeTaskId().isBlank()
            || npc.tasks().pausedTaskCount() > 0
            || !"idle".equals(npc.tasks().schedulerLifecycle())
            || !npc.tasks().observableTaskQueue().isEmpty()) {
            throw new IllegalStateException("Food survival fixture requires an idle NPC task scheduler");
        }
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Food survival fixture has not been set up");
        if (marker.level() != player.level() || marker.level() != npc.level()) {
            throw new IllegalStateException("Food survival fixture requires the original dimension");
        }
        return marker;
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ArmorStand marker) || !marker.getTags().contains(MARKER_TAG)) continue;
                CompoundTag state = fixtureState(marker);
                if (!state.getBoolean("Closed") && state.hasUUID("NpcUuid")
                    && state.getUUID("NpcUuid").equals(npc.getUUID())) return marker;
            }
        }
        return null;
    }

    private static ArmorStand markerForNpc(CodexNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ArmorStand marker) || !marker.getTags().contains(MARKER_TAG)) continue;
            CompoundTag state = fixtureState(marker);
            if (!state.getBoolean("Closed") && state.hasUUID("NpcUuid")
                && state.getUUID("NpcUuid").equals(npc.getUUID())) return marker;
        }
        return null;
    }

    private static ArmorStand markerNear(ServerLevel level, Vec3 position) {
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ArmorStand marker) || !marker.getTags().contains(MARKER_TAG)
                || marker.distanceToSqr(position) > 48.0D * 48.0D) continue;
            CompoundTag state = fixtureState(marker);
            if (!state.getBoolean("Closed")) return marker;
        }
        return null;
    }

    private static CompoundTag fixtureState(ArmorStand marker) {
        return marker.getPersistentData().getCompound(STATE_KEY);
    }

    private static void updateState(ArmorStand marker, CompoundTag state) {
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static List<Cow> fixtureCows(ServerLevel level, ArmorStand marker) {
        return level.getEntitiesOfClass(
            Cow.class,
            new AABB(BlockPos.of(fixtureState(marker).getLong("Origin"))).inflate(32.0D, 8.0D, 32.0D),
            cow -> cow.isAlive() && cow.getTags().contains(ANIMAL_TAG)
        );
    }

    private static void releaseHostiles(ServerLevel level) {
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity.getTags().contains(HOSTILE_TAG)) targets.add(entity);
        }
        for (Entity target : targets) target.discard();
    }

    static List<BlockPos> ordinaryCowPositions(BlockPos origin, int count) {
        if (count < 1 || count > STRICT_TARGET_COUNT + FoodProvisionPolicy.BREEDING_RESERVE) {
            throw new IllegalArgumentException("Food survival fixture huntable count is out of range");
        }
        List<BlockPos> positions = new ArrayList<>();
        for (int x : new int[] { 1, 4, 7 }) {
            for (int z : new int[] { -8, -5, -2, 1, 4, 7 }) {
                positions.add(origin.offset(x, 0, z));
                if (positions.size() == count) return List.copyOf(positions);
            }
        }
        throw new IllegalArgumentException("Food survival fixture does not have enough cow positions");
    }

    private static int requireTargetCount(int value) {
        if (value < 1 || value > STRICT_TARGET_COUNT) {
            throw new IllegalArgumentException("Food survival fixture target count is out of range");
        }
        return value;
    }

    private static int targetCount(CompoundTag state) {
        return state.contains("TargetCount", Tag.TAG_INT)
            ? requireTargetCount(state.getInt("TargetCount"))
            : LEGACY_TARGET_COUNT;
    }

    private static int huntableCount(CompoundTag state) {
        int expected = targetCount(state) + FoodProvisionPolicy.BREEDING_RESERVE;
        int value = state.contains("HuntableCount", Tag.TAG_INT) ? state.getInt("HuntableCount") : expected;
        if (value != expected) throw new IllegalStateException("Food survival fixture huntable ledger is invalid");
        return value;
    }

    private static Cow spawnCow(
        ServerLevel level,
        BlockPos position,
        boolean baby,
        boolean protectedAnimal,
        ArmorStand marker
    ) {
        Cow cow = EntityType.COW.create(level);
        if (cow == null) throw new IllegalStateException("Food survival fixture cow could not be created");
        cow.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0.0F, 0.0F);
        cow.finalizeSpawn(level, level.getCurrentDifficultyAt(position), MobSpawnType.COMMAND, null, null);
        cow.setAge(baby ? -24_000 : 0);
        cow.setPersistenceRequired();
        cow.addTag(ANIMAL_TAG);
        if (baby || protectedAnimal) {
            cow.addTag(PROTECTED_TAG);
            cow.setNoAi(true);
        }
        if (!level.addFreshEntity(cow)) throw new IllegalStateException("Food survival fixture cow was rejected");
        if (protectedAnimal && marker.isRemoved()) {
            throw new IllegalStateException("Food survival fixture protected marker is unavailable");
        }
        return cow;
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        npc.setTarget(null);
    }

    private static BlockPos findIsolatedOrigin(ServerLevel level, BlockPos near, ServerPlayer player) {
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(player);
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int centerX = near.getX() + dx;
                int centerZ = near.getZ() + dz;
                if (home.dimension().equals(level.dimension())) {
                    double homeDx = centerX - home.position().getX();
                    double homeDz = centerZ - home.position().getZ();
                    if (homeDx * homeDx + homeDz * homeDz <= 48.0D * 48.0D) continue;
                }
                int surface = level.getMinBuildHeight();
                boolean insideBorder = true;
                for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS && insideBorder; x++) {
                    for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                        BlockPos column = new BlockPos(centerX + x, near.getY(), centerZ + z);
                        if (!level.getWorldBorder().isWithinBounds(column)) {
                            insideBorder = false;
                            break;
                        }
                        surface = Math.max(surface, level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            column.getX(),
                            column.getZ()
                        ));
                    }
                }
                if (!insideBorder) continue;
                BlockPos origin = new BlockPos(centerX, surface + 32, centerZ);
                if (origin.getY() + 4 >= level.getMaxBuildHeight()) continue;
                boolean clear = true;
                for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS && clear; x++) {
                    for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS && clear; z++) {
                        for (int y = -1; y <= 2; y++) {
                            BlockPos position = origin.offset(x, y, z);
                            if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                }
                if (!clear) continue;
                AABB bounds = new AABB(
                    origin.getX() - PLATFORM_RADIUS,
                    origin.getY() - 1,
                    origin.getZ() - PLATFORM_RADIUS,
                    origin.getX() + PLATFORM_RADIUS + 1,
                    origin.getY() + 3,
                    origin.getZ() + PLATFORM_RADIUS + 1
                );
                if (level.getEntities(null, bounds).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated food survival fixture site was found");
    }

    static Set<BlockPos> fixtureBlocks(BlockPos origin, BlockPos furnace) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) positions.add(origin.offset(x, -1, z));
        }
        for (int offset = -PLATFORM_RADIUS; offset <= PLATFORM_RADIUS; offset++) {
            positions.add(origin.offset(-PLATFORM_RADIUS, 0, offset));
            positions.add(origin.offset(PLATFORM_RADIUS, 0, offset));
            positions.add(origin.offset(offset, 0, -PLATFORM_RADIUS));
            positions.add(origin.offset(offset, 0, PLATFORM_RADIUS));
        }
        positions.add(furnace);
        return positions;
    }

    private static int blockConflicts(
        ServerLevel level,
        CompoundTag state,
        BlockPos origin,
        BlockPos furnace,
        boolean allowMissing
    ) {
        int conflicts = 0;
        for (long packed : state.getLongArray("ModifiedBlocks")) {
            BlockPos position = BlockPos.of(packed);
            BlockState current = level.getBlockState(position);
            if (trackedBlockConflict(allowMissing, current.isAir(),
                expectedFixtureBlock(current, position, origin, furnace))) conflicts++;
        }
        return conflicts;
    }

    static boolean trackedBlockConflict(boolean allowMissing, boolean air, boolean expected) {
        return !(allowMissing && air) && !expected;
    }

    private static boolean expectedFixtureBlock(
        BlockState current,
        BlockPos position,
        BlockPos origin,
        BlockPos furnace
    ) {
        return current.is(fixtureBlockState(position, origin, furnace).getBlock());
    }

    private static BlockState fixtureBlockState(BlockPos position, BlockPos origin, BlockPos furnace) {
        if (position.equals(furnace)) return Blocks.FURNACE.defaultBlockState();
        if (position.getY() == origin.getY() - 1) return Blocks.SEA_LANTERN.defaultBlockState();
        return Blocks.COBBLESTONE_WALL.defaultBlockState();
    }

    private static void set(
        ServerLevel level,
        ArmorStand marker,
        CompoundTag fixtureState,
        BlockPos position,
        BlockState state
    ) {
        BlockState current = level.getBlockState(position);
        if (current.equals(state)) return;
        if (!current.isAir() || !level.getFluidState(position).isEmpty()) {
            throw new IllegalStateException("Food survival fixture block position changed during setup");
        }
        if (!level.setBlockAndUpdate(position, state) && !level.getBlockState(position).equals(state)) {
            throw new IllegalStateException("Food survival fixture block could not be placed");
        }
        long packed = position.asLong();
        long[] recorded = fixtureState.getLongArray("ModifiedBlocks");
        for (long existing : recorded) {
            if (existing == packed) return;
        }
        long[] expanded = Arrays.copyOf(recorded, recorded.length + 1);
        expanded[recorded.length] = packed;
        fixtureState.putLongArray("ModifiedBlocks", expanded);
        updateState(marker, fixtureState);
    }

    private static void insertFixtureStack(CodexNpcEntity npc, ItemStack stack) {
        stack.getOrCreateTag().putBoolean(ITEM_TAG, true);
        ItemStack remainder = stack;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE && !remainder.isEmpty(); slot++) {
            remainder = npc.inventory().insertItem(slot, remainder, false);
        }
        if (!remainder.isEmpty()) throw new IllegalStateException("Food survival fixture NPC backpack is full");
    }

    private static void clearInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static boolean isFixtureStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getTag() != null && stack.getTag().getBoolean(ITEM_TAG);
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    private static boolean close(double first, double second) {
        return Math.abs(first - second) <= 1.0E-4D;
    }
}
