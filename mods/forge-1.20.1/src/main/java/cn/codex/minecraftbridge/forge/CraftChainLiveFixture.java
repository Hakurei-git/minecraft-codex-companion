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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reversible entity-level fixture for the survival craft dependency stack. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class CraftChainLiveFixture {
    private static final String MARKER_TAG = "CodexAcceptanceCraftChainMarker";
    private static final String OUTPUT_TAG = "CodexAcceptanceCraftChainOutput";
    private static final String STATE_KEY = "CodexAcceptanceCraftChainState";
    private static final int SEARCH_RADIUS = 512;
    private static final int PLATFORM_RADIUS = 12;
    private static final int[] SITE_OFFSETS = { 64, -64, 96, -96, 128, -128, 160, -160 };

    private CraftChainLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Craft chain fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "inspect" -> inspect(player, npc);
            case "checkpoint" -> checkpoint(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown craft chain fixture mode");
        }
    }

    @SubscribeEvent
    public static void recordBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
            || !(event.getLevel() instanceof ServerLevel level)
            || !(event.getPlayer() instanceof FakePlayer)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 48.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (contains(state.getLongArray("TreeLogs"), event.getPos())) {
            state.putInt("LogBreaks", state.getInt("LogBreaks") + 1);
        } else if (contains(state.getLongArray("StoneBlocks"), event.getPos())) {
            state.putInt("StoneBreaks", state.getInt("StoneBreaks") + 1);
        } else {
            return;
        }
        state.putLong("LastBreakGameTime", level.getGameTime());
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordPlacement(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()
            || !(event.getLevel() instanceof ServerLevel level)
            || !(event.getEntity() instanceof FakePlayer)) return;
        BlockState placed = event.getPlacedBlock();
        if (!placed.is(Blocks.CRAFTING_TABLE) && !placed.is(Blocks.FURNACE)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 48.0D);
        if (marker == null) return;
        recordWorkstation(marker, event.getPos(), placed.getBlock());
    }

    @SubscribeEvent
    public static void recordItemJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        ArmorStand marker = markerNear(level, item.position(), 48.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        ItemStack stack = item.getItem();
        boolean delivered = stack.is(Items.IRON_PICKAXE)
            && item.getPersistentData().hasUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
            && state.hasUUID("OwnerUuid")
            && item.getPersistentData().getUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
                .equals(state.getUUID("OwnerUuid"));
        boolean fixtureDrop = insideFixture(state, item.position()) && isFixtureMaterial(stack.getItem());
        if (!delivered && !fixtureDrop) return;
        item.addTag(OUTPUT_TAG);
        if (delivered) {
            state.putBoolean("DeliverySeen", true);
            state.putBoolean("DeliveryRecipientSeen", true);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordItemPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !(player.level() instanceof ServerLevel level)) return;
        ItemEntity item = event.getItem();
        if (!item.getItem().is(Items.IRON_PICKAXE)
            || !item.getPersistentData().hasUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
            || !item.getPersistentData().getUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG).equals(player.getUUID())) return;
        ArmorStand marker = markerNear(level, item.position(), 48.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!state.hasUUID("OwnerUuid") || !state.getUUID("OwnerUuid").equals(player.getUUID())) return;
        item.addTag(OUTPUT_TAG);
        state.putBoolean("DeliverySeen", true);
        state.putBoolean("DeliveryRecipientSeen", true);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void observeTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
            || !(event.player instanceof ServerPlayer player)
            || player.tickCount % 5 != 0) return;
        CodexNpcEntity npc = NpcManager.find(player);
        if (npc == null) return;
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) return;
        observePhysicalState(marker, npc);
        if (player.tickCount % 20 == 0) observePersistentState(marker, npc);
    }

    static void recordFurnaceFuelSupply(CodexNpcEntity npc, BlockPos position, ItemStack fuel) {
        if (npc == null || position == null || fuel == null) return;
        ArmorStand marker = markerForNpc(npc);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        boolean inside = insideFixture(state, Vec3.atCenterOf(position));
        boolean furnace = npc.level().getBlockState(position).is(Blocks.FURNACE);
        boolean validFuel = !fuel.isEmpty() && AbstractFurnaceBlockEntity.isFuel(fuel);
        if (!shouldRecordFuelSupply(inside, furnace, validFuel)) return;
        state.putBoolean("SawFurnaceFuel", true);
        state.putInt("FuelSupplyEvents", state.getInt("FuelSupplyEvents") + 1);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    static boolean shouldRecordFuelSupply(boolean insideFixture, boolean furnacePresent, boolean validFuel) {
        return insideFixture && furnacePresent && validFuel;
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        requireIdle(npc);
        cleanup(player, npc, false);
        requireIdle(npc);
        if (npc.creativeResources()) {
            throw new IllegalStateException("Craft chain fixture requires survival material mode");
        }

        ServerLevel level = player.serverLevel();
        BlockPos origin = findSite(level, npc.blockPosition());
        LinkedHashSet<BlockPos> fixtureBlocks = new LinkedHashSet<>();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                fixtureBlocks.add(origin.offset(x, -1, z));
            }
        }

        List<BlockPos> treeLogs = new ArrayList<>();
        LinkedHashSet<BlockPos> leaves = new LinkedHashSet<>();
        for (BlockPos root : List.of(
            origin.offset(-7, 0, -5),
            origin.offset(-7, 0, 4),
            origin.offset(0, 0, 8)
        )) {
            for (int y = 0; y < 5; y++) treeLogs.add(root.above(y));
            leaves.addAll(canopy(root.above(4)));
        }
        leaves.removeAll(treeLogs);
        fixtureBlocks.addAll(treeLogs);
        fixtureBlocks.addAll(leaves);

        List<BlockPos> stoneBlocks = new ArrayList<>();
        for (int x = 5; x <= 9; x++) {
            for (int z = -5; z <= -2; z++) stoneBlocks.add(origin.offset(x, 0, z));
        }
        fixtureBlocks.addAll(stoneBlocks);
        for (BlockPos position : fixtureBlocks) {
            if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                throw new IllegalStateException("Craft chain fixture site changed before setup at " + position.toShortString());
            }
        }

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Craft chain fixture marker could not be created");
        marker.moveTo(origin.getX() + 0.5D, origin.getY() + 10.0D, origin.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 1);
        state.putLong("Origin", origin.asLong());
        state.putUUID("NpcUuid", npc.getUUID());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putLongArray("FixtureBlocks", longs(fixtureBlocks));
        state.putLongArray("TreeLogs", longs(treeLogs));
        state.putLongArray("StoneBlocks", longs(stoneBlocks));
        state.putLongArray("Workstations", new long[0]);
        saveNpcState(npc, state);
        savePlayerState(player, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            restoreNpcState(npc, state);
            restorePlayerState(player, state);
            throw new IllegalStateException("Craft chain fixture marker was rejected");
        }

        try {
            for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
                for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                    set(level, origin.offset(x, -1, z), Blocks.DIRT.defaultBlockState());
                }
            }
            for (BlockPos position : stoneBlocks) set(level, position, Blocks.STONE.defaultBlockState());
            for (BlockPos position : treeLogs) set(level, position, Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
            for (BlockPos position : leaves) set(level, position, leaf);

            insert(npc, new ItemStack(Items.RAW_IRON, 3));
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            moveNpc(npc, origin.offset(0, 0, 0));
            player.connection.teleport(
                origin.getX() - 3.5D,
                origin.getY(),
                origin.getZ() + 0.5D,
                270.0F,
                0.0F
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            state.putBoolean("GoldExecutable", goldExecutable(level));
            state.putBoolean("DiamondExecutable", diamondExecutable(level));
            marker.getPersistentData().put(STATE_KEY, state);
            npc.setStatus("craft-chain-fixture:setup raw=3 origin="
                + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        observePhysicalState(marker, npc);
        observePersistentState(marker, npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = (ServerLevel) marker.level();
        int worldPickaxes = level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(4.0D),
            item -> item.getTags().contains(OUTPUT_TAG) && item.getItem().is(Items.IRON_PICKAXE)
        ).stream().mapToInt(item -> item.getItem().getCount()).sum();
        npc.setStatus("craft-chain-fixture:i=" + countItem(npc, Items.RAW_IRON)
            + "," + countItem(npc, Items.OAK_LOG)
            + "," + countItem(npc, Items.OAK_PLANKS)
            + "," + countItem(npc, Items.STICK)
            + "," + countItem(npc, Items.COBBLESTONE)
            + "," + bool(state.getBoolean("SawWoodenPickaxe"))
            + "," + bool(state.getBoolean("SawCraftingTable"))
            + "," + bool(state.getBoolean("SawFurnace"))
            + "," + bool(state.getBoolean("SawRawIronInput"))
            + "," + bool(state.getBoolean("SawFurnaceFuel"))
            + "," + bool(state.getBoolean("SawLitFurnace"))
            + "," + bool(state.getBoolean("SawIronIngot"))
            + "," + bool(state.getBoolean("SawIronPickaxe"))
            + "," + bool(state.getBoolean("DeliverySeen")
                && state.getBoolean("DeliveryRecipientSeen"))
            + "," + countItem(npc, Items.IRON_PICKAXE)
            + "," + countPlayerItem(player, Items.IRON_PICKAXE)
            + "," + worldPickaxes
            + "," + state.getInt("LogBreaks")
            + "," + state.getInt("StoneBreaks")
            + "," + state.getInt("TablePlacements")
            + "," + state.getInt("FurnacePlacements")
            + "," + bool(state.getBoolean("PersistentGoalSeen"))
            + "," + state.getInt("CheckpointRoundTrips")
            + "," + bool(state.getBoolean("CheckpointSame"))
            + "," + state.getInt("MaxGoalDepth")
            + "," + bool(state.getBoolean("IronGoalSeen"))
            + "," + bool(state.getBoolean("GoldExecutable"))
            + "," + bool(state.getBoolean("DiamondExecutable"))
            + "," + state.getInt("PersistenceErrors"));
    }

    private static void checkpoint(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        byte[] encoded = npc.tasks().savePersistentStateBytes();
        NpcTaskPersistence.SchedulerState before = NpcTaskPersistence.decodeCompressed(encoded);
        NpcTaskPersistence.WorkState active = requireIronCraft(before);
        JsonArray beforeGoals = goalArray(active);
        if (beforeGoals.isEmpty() || !containsGoal(beforeGoals, "minecraft:iron_ingot")) {
            throw new IllegalStateException("Craft chain checkpoint requires the persistent iron material goal");
        }
        String beforeGoalJson = beforeGoals.toString();
        String taskId = active.id();
        npc.tasks().loadPersistentState(encoded);
        NpcTaskPersistence.SchedulerState after = NpcTaskPersistence.decodeCompressed(
            npc.tasks().savePersistentStateBytes()
        );
        NpcTaskPersistence.WorkState restored = requireIronCraft(after);
        JsonArray afterGoals = goalArray(restored);
        boolean same = taskId.equals(restored.id()) && beforeGoalJson.equals(afterGoals.toString());
        state.putInt("CheckpointRoundTrips", state.getInt("CheckpointRoundTrips") + 1);
        state.putBoolean("CheckpointSame", same);
        state.putInt("CheckpointBytes", encoded.length);
        state.putInt("MaxGoalDepth", Math.max(state.getInt("MaxGoalDepth"), beforeGoals.size()));
        state.putBoolean("PersistentGoalSeen", true);
        state.putBoolean("IronGoalSeen", true);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!same) throw new IllegalStateException("Craft material goal stack changed during persistence round trip");
        npc.setStatus("craft-chain-fixture:checkpoint same=1,depth="
            + beforeGoals.size() + ",bytes=" + encoded.length);
    }

    private static NpcTaskPersistence.WorkState requireIronCraft(NpcTaskPersistence.SchedulerState scheduler) {
        NpcTaskPersistence.WorkState active = scheduler.active();
        if (active == null
            || !"craft".equals(active.kind())
            || !active.spec().has("itemId")
            || !"minecraft:iron_pickaxe".equals(active.spec().get("itemId").getAsString())) {
            throw new IllegalStateException("Craft chain fixture requires an active iron pickaxe craft task");
        }
        return active;
    }

    private static void observePhysicalState(ArmorStand marker, CodexNpcEntity npc) {
        CompoundTag state = fixtureState(marker);
        if (countItem(npc, Items.WOODEN_PICKAXE) > 0) state.putBoolean("SawWoodenPickaxe", true);
        if (countItem(npc, Items.IRON_INGOT) > 0) state.putBoolean("SawIronIngot", true);
        if (countItem(npc, Items.IRON_PICKAXE) > 0) state.putBoolean("SawIronPickaxe", true);
        ServerLevel level = (ServerLevel) marker.level();
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                for (int y = 0; y <= 2; y++) {
                    BlockPos position = origin.offset(x, y, z);
                    BlockState blockState = level.getBlockState(position);
                    if (blockState.is(Blocks.CRAFTING_TABLE) || blockState.is(Blocks.FURNACE)) {
                        recordWorkstation(marker, position, blockState.getBlock());
                        state = fixtureState(marker);
                    }
                    if (!blockState.is(Blocks.FURNACE)) continue;
                    BlockEntity blockEntity = level.getBlockEntity(position);
                    if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) continue;
                    if (furnace.getItem(0).is(Items.RAW_IRON)) state.putBoolean("SawRawIronInput", true);
                    if (!furnace.getItem(1).isEmpty()) state.putBoolean("SawFurnaceFuel", true);
                    if (furnace.getItem(2).is(Items.IRON_INGOT)) state.putBoolean("SawIronIngot", true);
                    if (blockState.hasProperty(BlockStateProperties.LIT)
                        && blockState.getValue(BlockStateProperties.LIT)) {
                        state.putBoolean("SawLitFurnace", true);
                    }
                }
            }
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void observePersistentState(ArmorStand marker, CodexNpcEntity npc) {
        CompoundTag state = fixtureState(marker);
        try {
            NpcTaskPersistence.SchedulerState scheduler = NpcTaskPersistence.decodeCompressed(
                npc.tasks().savePersistentStateBytes()
            );
            NpcTaskPersistence.WorkState active = scheduler.active();
            if (active == null || !"craft".equals(active.kind())) return;
            JsonArray goals = goalArray(active);
            if (!goals.isEmpty()) {
                state.putBoolean("PersistentGoalSeen", true);
                state.putInt("MaxGoalDepth", Math.max(state.getInt("MaxGoalDepth"), goals.size()));
                if (containsGoal(goals, "minecraft:iron_ingot")) state.putBoolean("IronGoalSeen", true);
                for (JsonElement element : goals) {
                    if (element.isJsonObject() && element.getAsJsonObject().has("ownedFurnace")) {
                        state.putBoolean("OwnedFurnaceCheckpointSeen", true);
                    }
                }
            }
            marker.getPersistentData().put(STATE_KEY, state);
        } catch (RuntimeException error) {
            state.putInt("PersistenceErrors", state.getInt("PersistenceErrors") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
        }
    }

    private static JsonArray goalArray(NpcTaskPersistence.WorkState work) {
        JsonObject checkpoint = work.checkpoint();
        if (!checkpoint.has("buildMaterialGoals") || !checkpoint.get("buildMaterialGoals").isJsonArray()) {
            return new JsonArray();
        }
        return checkpoint.getAsJsonArray("buildMaterialGoals");
    }

    private static boolean containsGoal(JsonArray goals, String itemId) {
        for (JsonElement element : goals) {
            if (element.isJsonObject()
                && element.getAsJsonObject().has("itemId")
                && itemId.equals(element.getAsJsonObject().get("itemId").getAsString())) return true;
        }
        return false;
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (report) npc.setStatus("craft-chain-fixture:cleanup none");
            return;
        }
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = (ServerLevel) marker.level();
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(8.0D),
            entity -> entity.getTags().contains(OUTPUT_TAG)
        )) item.discard();

        restoreNpcState(npc, state);
        restorePlayerState(player, state);
        int conflicts = restoreAir(level, state.getLongArray("Workstations"));
        conflicts += restoreAir(level, reverse(state.getLongArray("FixtureBlocks")));
        if (conflicts > 0) {
            npc.setStatus("craft-chain-fixture:cleanup conflicts=" + conflicts);
            throw new IllegalStateException("Craft chain fixture cleanup found unexpected blocks: " + conflicts);
        }
        marker.discard();
        if (report) npc.setStatus("craft-chain-fixture:cleanup restored");
    }

    private static int restoreAir(ServerLevel level, long[] positions) {
        int conflicts = 0;
        for (long packed : positions) {
            BlockPos position = BlockPos.of(packed);
            BlockState current = level.getBlockState(position);
            if (current.isAir()) continue;
            if (!isFixtureBlock(current.getBlock())) {
                conflicts++;
                continue;
            }
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        return conflicts;
    }

    private static boolean goldExecutable(ServerLevel level) {
        BuildMaterialPrerequisitePolicy.MaterialPlan ingot =
            BuildMaterialPrerequisitePolicy.plan("minecraft:gold_ingot");
        BuildMaterialPrerequisitePolicy.MaterialPlan raw =
            BuildMaterialPrerequisitePolicy.plan("minecraft:raw_gold");
        return hasCraftingOutput(level, Items.GOLDEN_PICKAXE)
            && hasCraftingOutput(level, Items.IRON_PICKAXE)
            && hasSmeltingTransition(level, Items.RAW_GOLD, Items.GOLD_INGOT)
            && ingot.action() == BuildMaterialPrerequisitePolicy.Action.SMELT
            && !ingot.upstreamRequirements().isEmpty()
            && "minecraft:raw_gold".equals(ingot.upstreamRequirements().get(0).selector())
            && raw.action() == BuildMaterialPrerequisitePolicy.Action.GATHER
            && "minecraft:iron_pickaxe".equals(GatherToolPolicy.requiredPickaxe("minecraft:raw_gold"))
            && BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("minecraft:golden_pickaxe");
    }

    private static boolean diamondExecutable(ServerLevel level) {
        BuildMaterialPrerequisitePolicy.MaterialPlan diamond =
            BuildMaterialPrerequisitePolicy.plan("minecraft:diamond");
        return hasCraftingOutput(level, Items.DIAMOND_PICKAXE)
            && hasCraftingOutput(level, Items.IRON_PICKAXE)
            && diamond.action() == BuildMaterialPrerequisitePolicy.Action.GATHER
            && "minecraft:iron_pickaxe".equals(GatherToolPolicy.requiredPickaxe("minecraft:diamond"))
            && BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("minecraft:diamond_pickaxe");
    }

    private static boolean hasCraftingOutput(ServerLevel level, Item outputItem) {
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            if (recipe.getType() == RecipeType.CRAFTING
                && recipe.getResultItem(level.registryAccess()).is(outputItem)) return true;
        }
        return false;
    }

    private static boolean hasSmeltingTransition(ServerLevel level, Item inputItem, Item outputItem) {
        ItemStack input = new ItemStack(inputItem);
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            if (recipe.getType() == RecipeType.SMELTING
                && recipe.getResultItem(level.registryAccess()).is(outputItem)
                && recipe.getIngredients().stream().anyMatch(ingredient -> ingredient.test(input))) return true;
        }
        return false;
    }

    private static void saveNpcState(CodexNpcEntity npc, CompoundTag state) {
        state.putDouble("NpcStartX", npc.getX());
        state.putDouble("NpcStartY", npc.getY());
        state.putDouble("NpcStartZ", npc.getZ());
        state.putFloat("NpcStartYaw", npc.getYRot());
        state.putFloat("NpcStartPitch", npc.getXRot());
        state.putByte("NpcStartStance", npc.stance().id());
        state.putString("NpcStartStatus", npc.status());
        state.putFloat("NpcStartHealth", npc.getHealth());
        state.putInt("NpcStartFood", npc.foodLevel());
        state.putFloat("NpcStartSaturation", npc.saturationLevel());
        state.putFloat("NpcStartExhaustion", npc.exhaustionLevel());
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
        state.put("SavedNpcInventory", saved);
    }

    private static void restoreNpcState(CodexNpcEntity npc, CompoundTag state) {
        npc.cancelManagedEating();
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        ListTag saved = state.getList("SavedNpcInventory", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag entry = saved.getCompound(index);
            int slot = entry.getInt("Slot");
            if (slot >= 0 && slot < CodexNpcEntity.INVENTORY_SIZE) {
                npc.inventory().setStackInSlot(slot, ItemStack.of(entry.getCompound("Stack")));
            }
        }
        npc.teleportTo(state.getDouble("NpcStartX"), state.getDouble("NpcStartY"), state.getDouble("NpcStartZ"));
        npc.setYRot(state.getFloat("NpcStartYaw"));
        npc.setXRot(state.getFloat("NpcStartPitch"));
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        npc.setHealth(Math.min(npc.getMaxHealth(), Math.max(1.0F, state.getFloat("NpcStartHealth"))));
        npc.setFoodLevel(state.getInt("NpcStartFood"));
        npc.setSaturationLevel(state.getFloat("NpcStartSaturation"));
        npc.setExhaustionLevel(state.getFloat("NpcStartExhaustion"));
        npc.tasks().setStance(NpcTaskEngine.Stance.fromId(state.getByte("NpcStartStance")));
        npc.setStatus(state.getString("NpcStartStatus"));
    }

    private static void savePlayerState(ServerPlayer player, CompoundTag state) {
        state.putDouble("PlayerStartX", player.getX());
        state.putDouble("PlayerStartY", player.getY());
        state.putDouble("PlayerStartZ", player.getZ());
        state.putFloat("PlayerStartYaw", player.getYRot());
        state.putFloat("PlayerStartPitch", player.getXRot());
        state.putDouble("PlayerMotionX", player.getDeltaMovement().x);
        state.putDouble("PlayerMotionY", player.getDeltaMovement().y);
        state.putDouble("PlayerMotionZ", player.getDeltaMovement().z);
        state.putFloat("PlayerFallDistance", player.fallDistance);
        state.putFloat("PlayerStartHealth", player.getHealth());
        state.putInt("PlayerStartFood", player.getFoodData().getFoodLevel());
        state.putFloat("PlayerStartSaturation", player.getFoodData().getSaturationLevel());
        state.putFloat("PlayerStartExhaustion", player.getFoodData().getExhaustionLevel());
        state.putInt("PlayerSelectedSlot", player.getInventory().selected);
        state.put("SavedPlayerInventory", player.getInventory().save(new ListTag()));
        player.getInventory().clearContent();
        player.containerMenu.broadcastChanges();
    }

    private static void restorePlayerState(ServerPlayer player, CompoundTag state) {
        player.getInventory().clearContent();
        player.getInventory().load(state.getList("SavedPlayerInventory", Tag.TAG_COMPOUND));
        player.getInventory().selected = Math.max(0, Math.min(8, state.getInt("PlayerSelectedSlot")));
        player.connection.teleport(
            state.getDouble("PlayerStartX"),
            state.getDouble("PlayerStartY"),
            state.getDouble("PlayerStartZ"),
            state.getFloat("PlayerStartYaw"),
            state.getFloat("PlayerStartPitch")
        );
        player.setDeltaMovement(
            state.getDouble("PlayerMotionX"),
            state.getDouble("PlayerMotionY"),
            state.getDouble("PlayerMotionZ")
        );
        player.fallDistance = state.getFloat("PlayerFallDistance");
        player.setHealth(Math.min(player.getMaxHealth(), Math.max(1.0F, state.getFloat("PlayerStartHealth"))));
        player.getFoodData().setFoodLevel(state.getInt("PlayerStartFood"));
        player.getFoodData().setSaturation(state.getFloat("PlayerStartSaturation"));
        player.getFoodData().setExhaustion(state.getFloat("PlayerStartExhaustion"));
        player.containerMenu.broadcastChanges();
    }

    private static BlockPos findSite(ServerLevel level, BlockPos near) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int centerX = near.getX() + dx;
                int centerZ = near.getZ() + dz;
                int surface = level.getMinBuildHeight();
                boolean border = true;
                for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS && border; x++) {
                    for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
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
                int y = surface + 32;
                if (y + 12 >= level.getMaxBuildHeight()) continue;
                BlockPos origin = new BlockPos(centerX, y, centerZ);
                boolean clear = true;
                for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS && clear; x++) {
                    for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS && clear; z++) {
                        for (int dy = -1; dy <= 9; dy++) {
                            BlockPos position = origin.offset(x, dy, z);
                            if (!level.getBlockState(position).isAir()
                                || !level.getFluidState(position).isEmpty()) {
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
                    origin.getY() + 10,
                    origin.getZ() + PLATFORM_RADIUS + 1
                );
                if (level.getEntities(null, bounds).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated craft chain fixture site was found");
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Craft chain fixture has not been set up");
        return marker;
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
        for (ServerLevel level : player.getServer().getAllLevels()) {
            ArmorStand marker = level.getEntitiesOfClass(
                ArmorStand.class,
                npc.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> candidate.getTags().contains(MARKER_TAG)
                    && candidate.getPersistentData().getCompound(STATE_KEY).hasUUID("NpcUuid")
                    && candidate.getPersistentData().getCompound(STATE_KEY).getUUID("NpcUuid")
                        .equals(npc.getUUID())
            ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
            if (marker != null) return marker;
        }
        return null;
    }

    private static ArmorStand markerForNpc(CodexNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        return level.getEntitiesOfClass(
            ArmorStand.class,
            npc.getBoundingBox().inflate(SEARCH_RADIUS),
            candidate -> candidate.getTags().contains(MARKER_TAG)
                && candidate.getPersistentData().getCompound(STATE_KEY).hasUUID("NpcUuid")
                && candidate.getPersistentData().getCompound(STATE_KEY).getUUID("NpcUuid")
                    .equals(npc.getUUID())
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private static ArmorStand markerNear(ServerLevel level, Vec3 position, double radius) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(position, position).inflate(radius),
            candidate -> candidate.getTags().contains(MARKER_TAG)
        ).stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(position))).orElse(null);
    }

    private static CompoundTag fixtureState(ArmorStand marker) {
        return marker.getPersistentData().getCompound(STATE_KEY);
    }

    private static void recordWorkstation(ArmorStand marker, BlockPos position, Block block) {
        CompoundTag state = fixtureState(marker);
        boolean added = appendUnique(state, "Workstations", position);
        if (block == Blocks.CRAFTING_TABLE) {
            state.putBoolean("SawCraftingTable", true);
            if (added) state.putInt("TablePlacements", state.getInt("TablePlacements") + 1);
        } else if (block == Blocks.FURNACE) {
            state.putBoolean("SawFurnace", true);
            if (added) state.putInt("FurnacePlacements", state.getInt("FurnacePlacements") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static boolean appendUnique(CompoundTag state, String key, BlockPos position) {
        long packed = position.asLong();
        long[] current = state.getLongArray(key);
        for (long value : current) if (value == packed) return false;
        long[] updated = new long[current.length + 1];
        System.arraycopy(current, 0, updated, 0, current.length);
        updated[current.length] = packed;
        state.putLongArray(key, updated);
        return true;
    }

    private static boolean insideFixture(CompoundTag state, Vec3 position) {
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        return Math.abs(position.x - (origin.getX() + 0.5D)) <= PLATFORM_RADIUS + 2.0D
            && position.y >= origin.getY() - 2.0D
            && position.y <= origin.getY() + 10.0D
            && Math.abs(position.z - (origin.getZ() + 0.5D)) <= PLATFORM_RADIUS + 2.0D;
    }

    private static AABB fixtureBounds(CompoundTag state) {
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        return new AABB(
            origin.getX() - PLATFORM_RADIUS - 2,
            origin.getY() - 2,
            origin.getZ() - PLATFORM_RADIUS - 2,
            origin.getX() + PLATFORM_RADIUS + 3,
            origin.getY() + 11,
            origin.getZ() + PLATFORM_RADIUS + 3
        );
    }

    private static boolean isFixtureMaterial(Item item) {
        return Set.of(
            Items.OAK_LOG,
            Items.OAK_PLANKS,
            Items.STICK,
            Items.COBBLESTONE,
            Items.RAW_IRON,
            Items.IRON_INGOT,
            Items.WOODEN_PICKAXE,
            Items.CRAFTING_TABLE,
            Items.FURNACE,
            Items.IRON_PICKAXE,
            Items.OAK_SAPLING,
            Items.APPLE
        ).contains(item);
    }

    private static boolean isFixtureBlock(Block block) {
        return Set.of(
            Blocks.DIRT,
            Blocks.STONE,
            Blocks.OAK_LOG,
            Blocks.OAK_LEAVES,
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE
        ).contains(block);
    }

    private static void requireIdle(CodexNpcEntity npc) {
        requireNoTasks(npc);
        if (npc.isManagedEating()) throw new IllegalStateException("Finish NPC eating before craft chain setup");
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Finish NPC tasks before changing the craft chain fixture");
        }
    }

    private static void insert(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = npc.insert(stack.copy());
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected craft chain fixture items");
    }

    private static int countItem(CodexNpcEntity npc, Item item) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countPlayerItem(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) count += stack.getCount();
        for (ItemStack stack : player.getInventory().armor) if (stack.is(item)) count += stack.getCount();
        for (ItemStack stack : player.getInventory().offhand) if (stack.is(item)) count += stack.getCount();
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
            throw new IllegalStateException("Craft chain fixture block could not be placed at " + position.toShortString());
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

    private static boolean contains(long[] values, BlockPos position) {
        long packed = position.asLong();
        for (long value : values) if (value == packed) return true;
        return false;
    }

    private static long[] longs(Iterable<BlockPos> positions) {
        List<Long> values = new ArrayList<>();
        for (BlockPos position : positions) values.add(position.asLong());
        long[] packed = new long[values.size()];
        for (int index = 0; index < values.size(); index++) packed[index] = values.get(index);
        return packed;
    }

    private static long[] reverse(long[] values) {
        long[] reversed = new long[values.length];
        for (int index = 0; index < values.length; index++) reversed[index] = values[values.length - index - 1];
        return reversed;
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }
}
