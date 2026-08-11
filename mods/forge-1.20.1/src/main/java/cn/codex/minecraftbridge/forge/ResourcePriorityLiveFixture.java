package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strictly reversible live proof for nearby-workstation preference and
 * connected-ore ordering.  The request surface contains no coordinates or
 * commands; every world mutation is fixed here and recorded before use.
 */
@SuppressWarnings("deprecation")
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class ResourcePriorityLiveFixture {
    private static final String MARKER_TAG = "CodexAcceptanceResourcePriorityMarker";
    private static final String OUTPUT_TAG = "CodexAcceptanceResourcePriorityOutput";
    private static final String STATE_KEY = "CodexAcceptanceResourcePriorityState";
    private static final String ITEM_MARKER_KEY = "CodexAcceptanceResourcePriorityItem";
    private static final String MARKER_UUID_KEY = "CodexAcceptanceResourcePriorityMarkerUuid";
    private static final String MARKER_DIMENSION_KEY = "CodexAcceptanceResourcePriorityMarkerDimension";
    private static final String MARKER_POSITION_KEY = "CodexAcceptanceResourcePriorityMarkerPosition";
    private static final String SCENARIO_KEY = "Scenario";
    private static final String PRIORITY_SCENARIO = "priority";
    private static final String FISHING_SCENARIO = "fishing";
    private static final String TORCH_SCENARIO = "torches";
    private static final int SEARCH_RADIUS = 512;
    private static final int MIN_X = -5;
    private static final int MAX_X = 44;
    private static final int MIN_Z = -6;
    private static final int MAX_Z = 8;
    private static final int MIN_Y = -1;
    private static final int MAX_Y = 3;
    private static final int[] SITE_OFFSETS = { 96, -96, 128, -128, 160, -160, 192, -192 };

    private ResourcePriorityLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Resource priority fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc, PRIORITY_SCENARIO);
            case "setup-fishing" -> setup(player, npc, FISHING_SCENARIO);
            case "setup-torches" -> setup(player, npc, TORCH_SCENARIO);
            case "inspect" -> inspect(player, npc);
            case "inspect-craft" -> inspectCraft(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown resource priority fixture mode");
        }
    }

    @SubscribeEvent
    public static void recordBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 72.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!insideSavedVolume(state, event.getPos())) return;
        if (!(event.getPlayer() instanceof FakePlayer)) {
            state.putInt("UnknownWorldEdits", state.getInt("UnknownWorldEdits") + 1);
        } else if (contains(state.getLongArray("LocalOre"), event.getPos())) {
            state.putInt("LocalBreaks", state.getInt("LocalBreaks") + 1);
        } else if (contains(state.getLongArray("RemoteOre"), event.getPos())) {
            if (remainingOre(level, state.getLongArray("LocalOre")) > 0) {
                state.putInt("OrderViolations", state.getInt("OrderViolations") + 1);
            }
            state.putInt("RemoteBreaks", state.getInt("RemoteBreaks") + 1);
        } else {
            state.putInt("UnexpectedTaskBreaks", state.getInt("UnexpectedTaskBreaks") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordPlacement(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 72.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!insideSavedVolume(state, event.getPos())) return;
        if (!(event.getEntity() instanceof FakePlayer)) {
            state.putInt("UnknownWorldEdits", state.getInt("UnknownWorldEdits") + 1);
        } else if (event.getPlacedBlock().is(Blocks.CRAFTING_TABLE)) {
            appendUnique(state, "TaskPlacedTables", event.getPos());
            state.putInt("NewTablePlacements", state.getInt("NewTablePlacements") + 1);
        } else {
            state.putInt("UnknownWorldEdits", state.getInt("UnknownWorldEdits") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordItemJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        ArmorStand marker = markerNear(level, item.position(), 72.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        ItemStack stack = item.getItem();
        boolean oreDrop = stack.is(Items.COAL) && nearAnyOre(state, item.position(), 3.25D);
        boolean delivered = (stack.is(Items.COAL) || isExpectedOutput(state, stack))
            && item.getPersistentData().hasUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
            && state.hasUUID("OwnerUuid")
            && item.getPersistentData().getUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
                .equals(state.getUUID("OwnerUuid"));
        if (!oreDrop && !delivered) return;
        markFixtureItem(stack);
        item.setItem(stack);
        item.addTag(OUTPUT_TAG);
        if (delivered && stack.is(Items.COAL)) state.putBoolean("CoalDeliverySeen", true);
        if (delivered && stack.is(Items.DIAMOND_PICKAXE)) state.putBoolean("PickDeliverySeen", true);
        if (delivered && !PRIORITY_SCENARIO.equals(scenario(state))) {
            state.putBoolean("CraftDeliverySeen", true);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void observeTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        CodexNpcEntity npc = NpcManager.find(player);
        if (npc == null) return;
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        BlockPos table = BlockPos.of(state.getLong("ExistingTable"));
        if ("craft".equals(npc.tasks().activeTaskKind())
            && npc.position().distanceTo(Vec3.atCenterOf(table)) <= 3.75D) {
            state.putBoolean("SawExistingTableReach", true);
        }
        markConsumedRecipeOutput(npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    static void recordCraftingTableUse(CodexNpcEntity npc, BlockPos position) {
        if (npc == null || position == null) return;
        ArmorStand marker = markerForNpc(npc);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (position.asLong() != state.getLong("ExistingTable")
            || !npc.level().getBlockState(position).is(Blocks.CRAFTING_TABLE)) return;
        state.putBoolean("SawExistingTableReach", true);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc, String requestedScenario) {
        String scenario = requireScenario(requestedScenario);
        requireIdle(npc);
        cleanup(player, npc, false);
        requireIdle(npc);
        if (npc.creativeResources()) {
            throw new IllegalStateException("Resource priority fixture requires survival material mode");
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            throw new IllegalStateException("Clear the carried cursor stack before resource priority setup");
        }

        ServerLevel level = player.serverLevel();
        BlockPos origin = findSite(level, npc.blockPosition());
        List<BlockPos> volume = savedVolume(origin);
        long[] savedPositions = longs(volume);
        int[] savedStateIds = new int[volume.size()];
        for (int index = 0; index < volume.size(); index++) {
            BlockPos position = volume.get(index);
            BlockState current = level.getBlockState(position);
            if (!current.isAir() || !level.getFluidState(position).isEmpty()
                || level.getBlockEntity(position) != null) {
                throw new IllegalStateException("Resource priority site changed before setup");
            }
            savedStateIds[index] = Block.getId(current);
        }

        List<BlockPos> floor = floor(origin);
        List<BlockPos> localOre = PRIORITY_SCENARIO.equals(scenario) ? localVein(origin) : List.of();
        List<BlockPos> remoteOre = PRIORITY_SCENARIO.equals(scenario) ? remoteVein(origin) : List.of();
        BlockPos table = origin.offset(6, 0, -2);
        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Resource priority marker could not be created");
        marker.moveTo(origin.getX() + 0.5D, origin.getY() + 3.25D, origin.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 2);
        state.putString(SCENARIO_KEY, scenario);
        state.putLong("Origin", origin.asLong());
        state.putUUID("NpcUuid", npc.getUUID());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putLongArray("SavedPositions", savedPositions);
        state.putIntArray("SavedStateIds", savedStateIds);
        state.putLongArray("Floor", longs(floor));
        state.putLongArray("LocalOre", longs(localOre));
        state.putLongArray("RemoteOre", longs(remoteOre));
        state.putLong("ExistingTable", table.asLong());
        state.putLongArray("TaskPlacedTables", new long[0]);
        saveNpcState(npc, state);
        savePlayerState(player, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            restoreNpcState(npc, state);
            restorePlayerState(player, state);
            throw new IllegalStateException("Resource priority marker was rejected");
        }
        rememberMarker(npc, marker);

        try {
            for (BlockPos position : floor) set(level, position, Blocks.STONE.defaultBlockState());
            set(level, table, Blocks.CRAFTING_TABLE.defaultBlockState());
            for (BlockPos position : localOre) set(level, position, Blocks.COAL_ORE.defaultBlockState());
            for (BlockPos position : remoteOre) set(level, position, Blocks.COAL_ORE.defaultBlockState());

            switch (scenario) {
                case PRIORITY_SCENARIO -> {
                    insertFixture(npc, new ItemStack(Items.DIAMOND, 3));
                    insertFixture(npc, new ItemStack(Items.STICK, 2));
                    insertFixture(npc, new ItemStack(Items.IRON_PICKAXE, 1));
                }
                case FISHING_SCENARIO -> {
                    insertFixture(npc, new ItemStack(Items.STICK, 3));
                    insertFixture(npc, new ItemStack(Items.STRING, 2));
                }
                case TORCH_SCENARIO -> {
                    insertFixture(npc, new ItemStack(Items.COAL, 16));
                    insertFixture(npc, new ItemStack(Items.STICK, 16));
                }
                default -> throw new IllegalStateException("Unsupported resource priority fixture scenario");
            }
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            moveNpc(npc, origin);
            player.connection.teleport(
                origin.getX() - 2.5D,
                origin.getY(),
                origin.getZ() + 0.5D,
                270.0F,
                0.0F
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            String prefix = PRIORITY_SCENARIO.equals(scenario)
                ? "rp:setup="
                : "rpc:setup=" + scenario + ",";
            npc.setNextLiveFixtureAckStatus(prefix + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = (ServerLevel) marker.level();
        int tableCount = 0;
        for (long packed : state.getLongArray("SavedPositions")) {
            if (level.getBlockState(BlockPos.of(packed)).is(Blocks.CRAFTING_TABLE)) tableCount++;
        }
        int npcPick = taggedCount(npc, Items.DIAMOND_PICKAXE);
        int playerPick = taggedCount(player, Items.DIAMOND_PICKAXE);
        int worldPick = taggedWorldCount(level, fixtureBounds(state), Items.DIAMOND_PICKAXE);
        int npcCoal = taggedCount(npc, Items.COAL);
        int playerCoal = taggedCount(player, Items.COAL);
        int worldCoal = taggedWorldCount(level, fixtureBounds(state), Items.COAL);
        npc.setNextLiveFixtureAckStatus("rp:i="
            + tableCount + ","
            + state.getInt("NewTablePlacements") + ","
            + bit(state.getBoolean("SawExistingTableReach")) + ","
            + npcPick + "," + playerPick + "," + worldPick + ","
            + remainingOre(level, state.getLongArray("LocalOre")) + ","
            + remainingOre(level, state.getLongArray("RemoteOre")) + ","
            + state.getInt("LocalBreaks") + ","
            + state.getInt("RemoteBreaks") + ","
            + state.getInt("OrderViolations") + ","
            + npcCoal + "," + playerCoal + "," + worldCoal + ","
            + bit(state.getBoolean("CoalDeliverySeen")) + ","
            + state.getInt("UnexpectedTaskBreaks"));
    }

    private static void inspectCraft(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        String scenario = scenario(state);
        if (PRIORITY_SCENARIO.equals(scenario)) {
            throw new IllegalStateException("Craft inspection requires a fishing or torch fixture");
        }
        markConsumedRecipeOutput(npc, state);
        ServerLevel level = (ServerLevel) marker.level();
        int tableCount = 0;
        for (long packed : state.getLongArray("SavedPositions")) {
            if (level.getBlockState(BlockPos.of(packed)).is(Blocks.CRAFTING_TABLE)) tableCount++;
        }
        Item output = expectedOutput(scenario);
        Item secondInput = FISHING_SCENARIO.equals(scenario) ? Items.STRING : Items.COAL;
        npc.setNextLiveFixtureAckStatus("rpc:i="
            + (FISHING_SCENARIO.equals(scenario) ? 1 : 2) + ","
            + tableCount + ","
            + state.getInt("NewTablePlacements") + ","
            + bit(state.getBoolean("SawExistingTableReach")) + ","
            + taggedCount(npc, output) + ","
            + taggedCount(player, output) + ","
            + taggedWorldCount(level, fixtureBounds(state), output) + ","
            + taggedCount(npc, Items.STICK) + ","
            + taggedCount(npc, secondInput) + ","
            + bit(state.getBoolean("CraftDeliverySeen")) + ","
            + state.getInt("UnexpectedTaskBreaks") + ","
            + state.getInt("UnknownWorldEdits"));
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static boolean cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (hasMarkerReference(npc)) {
                throw new IllegalStateException("Resource priority marker reference could not be resolved");
            }
            if (report) npc.setNextLiveFixtureAckStatus("rp:cleanup=none");
            return true;
        }
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = (ServerLevel) marker.level();
        int inventoryConflicts = unknownInventoryStacks(npc) + unknownInventoryStacks(player);
        int entityConflicts = unknownItemEntities(level, fixtureBounds(state));
        int blockConflicts = blockConflicts(level, state);
        int unknownEdits = state.getInt("UnknownWorldEdits");
        if (!cleanupMayProceed(inventoryConflicts, entityConflicts, blockConflicts, unknownEdits)) {
            if (report) npc.setNextLiveFixtureAckStatus("rp:cleanup=conflict,"
                + inventoryConflicts + "," + entityConflicts + "," + blockConflicts + "," + unknownEdits);
            else throw new IllegalStateException("Resource priority cleanup found unknown content");
            return false;
        }

        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state),
            entity -> entity.getTags().contains(OUTPUT_TAG) || isFixtureItem(entity.getItem())
        )) item.discard();
        restoreBlocks(level, state);
        restoreNpcState(npc, state);
        restorePlayerState(player, state);
        marker.discard();
        clearMarkerReference(npc);
        if (report) npc.setNextLiveFixtureAckStatus("rp:cleanup=restored");
        return true;
    }

    static List<BlockPos> localVein(BlockPos origin) {
        return vein(origin.offset(8, 0, 3));
    }

    static List<BlockPos> remoteVein(BlockPos origin) {
        return vein(origin.offset(38, 0, 3));
    }

    private static List<BlockPos> vein(BlockPos corner) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) result.add(corner.offset(x, y, z));
            }
        }
        return List.copyOf(result);
    }

    static boolean allTwentySixConnected(List<BlockPos> positions) {
        if (positions.isEmpty()) return false;
        Set<BlockPos> remaining = new HashSet<>(positions);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos first = remaining.iterator().next();
        remaining.remove(first);
        queue.add(first);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            List<BlockPos> discovered = remaining.stream().filter(candidate ->
                Math.abs(candidate.getX() - current.getX()) <= 1
                    && Math.abs(candidate.getY() - current.getY()) <= 1
                    && Math.abs(candidate.getZ() - current.getZ()) <= 1
            ).toList();
            remaining.removeAll(discovered);
            queue.addAll(discovered);
        }
        return remaining.isEmpty();
    }

    static boolean cleanupMayProceed(
        int unknownInventoryStacks,
        int unknownItemEntities,
        int unknownBlocks,
        int unknownWorldEdits
    ) {
        return unknownInventoryStacks == 0
            && unknownItemEntities == 0
            && unknownBlocks == 0
            && unknownWorldEdits == 0;
    }

    private static List<BlockPos> savedVolume(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN_Z; z <= MAX_Z; z++) result.add(origin.offset(x, y, z));
            }
        }
        return result;
    }

    private static List<BlockPos> floor(BlockPos origin) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int z = MIN_Z; z <= MAX_Z; z++) result.add(origin.offset(x, -1, z));
        }
        return result;
    }

    private static BlockPos findSite(ServerLevel level, BlockPos near) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int centerX = near.getX() + dx;
                int centerZ = near.getZ() + dz;
                int surface = level.getMinBuildHeight();
                boolean border = true;
                for (int x = MIN_X; x <= MAX_X && border; x += 4) {
                    for (int z = MIN_Z; z <= MAX_Z; z += 4) {
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
                BlockPos origin = new BlockPos(centerX, surface + 32, centerZ);
                if (origin.getY() + MAX_Y >= level.getMaxBuildHeight()) continue;
                boolean clear = true;
                for (BlockPos position : savedVolume(origin)) {
                    if (!level.getBlockState(position).isAir()
                        || !level.getFluidState(position).isEmpty()
                        || level.getBlockEntity(position) != null) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) continue;
                if (level.getEntities(null, fixtureBounds(origin)).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated resource priority fixture site was found");
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
        player.containerMenu.broadcastChanges();
    }

    private static int unknownInventoryStacks(CodexNpcEntity npc) {
        int conflicts = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (!stack.isEmpty() && !isFixtureItem(stack)) conflicts++;
        }
        return conflicts;
    }

    private static int unknownInventoryStacks(ServerPlayer player) {
        int conflicts = 0;
        for (ItemStack stack : player.getInventory().items) if (!stack.isEmpty() && !isFixtureItem(stack)) conflicts++;
        for (ItemStack stack : player.getInventory().armor) if (!stack.isEmpty() && !isFixtureItem(stack)) conflicts++;
        for (ItemStack stack : player.getInventory().offhand) if (!stack.isEmpty() && !isFixtureItem(stack)) conflicts++;
        return conflicts;
    }

    private static int unknownItemEntities(ServerLevel level, AABB bounds) {
        return level.getEntitiesOfClass(ItemEntity.class, bounds).stream()
            .mapToInt(item -> item.getTags().contains(OUTPUT_TAG) || isFixtureItem(item.getItem()) ? 0 : 1)
            .sum();
    }

    private static int blockConflicts(ServerLevel level, CompoundTag state) {
        int conflicts = 0;
        long[] positions = state.getLongArray("SavedPositions");
        int[] savedIds = state.getIntArray("SavedStateIds");
        for (int index = 0; index < positions.length; index++) {
            BlockPos position = BlockPos.of(positions[index]);
            BlockState current = level.getBlockState(position);
            BlockState saved = index < savedIds.length ? Block.stateById(savedIds[index]) : Blocks.AIR.defaultBlockState();
            boolean allowed = current.equals(saved)
                || (contains(state.getLongArray("Floor"), position) && current.is(Blocks.STONE))
                || (position.asLong() == state.getLong("ExistingTable") && current.is(Blocks.CRAFTING_TABLE))
                || ((contains(state.getLongArray("LocalOre"), position)
                    || contains(state.getLongArray("RemoteOre"), position)) && current.is(Blocks.COAL_ORE))
                || (contains(state.getLongArray("TaskPlacedTables"), position) && current.is(Blocks.CRAFTING_TABLE));
            if (!allowed) conflicts++;
        }
        return conflicts;
    }

    private static void restoreBlocks(ServerLevel level, CompoundTag state) {
        long[] positions = state.getLongArray("SavedPositions");
        int[] savedIds = state.getIntArray("SavedStateIds");
        if (positions.length != savedIds.length) {
            throw new IllegalStateException("Resource priority saved block arrays are inconsistent");
        }
        for (int index = positions.length - 1; index >= 0; index--) {
            level.setBlockAndUpdate(BlockPos.of(positions[index]), Block.stateById(savedIds[index]));
        }
    }

    private static void insertFixture(CodexNpcEntity npc, ItemStack stack) {
        markFixtureItem(stack);
        ItemStack remainder = npc.insert(stack);
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected resource priority items");
    }

    private static void markConsumedRecipeOutput(CodexNpcEntity npc, CompoundTag state) {
        if (!state.getBoolean("SawExistingTableReach")) return;
        String scenario = scenario(state);
        boolean consumed = switch (scenario) {
            case PRIORITY_SCENARIO -> countItem(npc, Items.DIAMOND) == 0 && countItem(npc, Items.STICK) == 0;
            case FISHING_SCENARIO -> countItem(npc, Items.STICK) == 0 && countItem(npc, Items.STRING) == 0;
            case TORCH_SCENARIO -> countItem(npc, Items.STICK) == 0 && countItem(npc, Items.COAL) == 0;
            default -> false;
        };
        if (!consumed) return;
        Item output = expectedOutput(scenario);
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(output)) markFixtureItem(stack);
        }
    }

    private static boolean isExpectedOutput(CompoundTag state, ItemStack stack) {
        return stack.is(expectedOutput(scenario(state)));
    }

    private static Item expectedOutput(String scenario) {
        return switch (scenario) {
            case PRIORITY_SCENARIO -> Items.DIAMOND_PICKAXE;
            case FISHING_SCENARIO -> Items.FISHING_ROD;
            case TORCH_SCENARIO -> Items.TORCH;
            default -> throw new IllegalStateException("Unknown resource priority fixture scenario");
        };
    }

    private static String scenario(CompoundTag state) {
        return state.contains(SCENARIO_KEY, Tag.TAG_STRING)
            ? requireScenario(state.getString(SCENARIO_KEY))
            : PRIORITY_SCENARIO;
    }

    static String requireScenario(String value) {
        if (PRIORITY_SCENARIO.equals(value) || FISHING_SCENARIO.equals(value) || TORCH_SCENARIO.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("Unknown resource priority fixture scenario");
    }

    private static void markFixtureItem(ItemStack stack) {
        if (!stack.isEmpty()) stack.getOrCreateTag().putBoolean(ITEM_MARKER_KEY, true);
    }

    private static boolean isFixtureItem(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(ITEM_MARKER_KEY);
    }

    private static int taggedCount(CodexNpcEntity npc, Item item) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(item) && isFixtureItem(stack)) count += stack.getCount();
        }
        return count;
    }

    private static int taggedCount(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item) && isFixtureItem(stack)) count += stack.getCount();
        for (ItemStack stack : player.getInventory().armor) if (stack.is(item) && isFixtureItem(stack)) count += stack.getCount();
        for (ItemStack stack : player.getInventory().offhand) if (stack.is(item) && isFixtureItem(stack)) count += stack.getCount();
        return count;
    }

    private static int taggedWorldCount(ServerLevel level, AABB bounds, Item item) {
        return level.getEntitiesOfClass(ItemEntity.class, bounds, entity ->
            entity.getItem().is(item) && isFixtureItem(entity.getItem())
        ).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static int countItem(CodexNpcEntity npc, Item item) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int remainingOre(ServerLevel level, long[] positions) {
        int count = 0;
        for (long packed : positions) if (level.getBlockState(BlockPos.of(packed)).is(Blocks.COAL_ORE)) count++;
        return count;
    }

    private static boolean nearAnyOre(CompoundTag state, Vec3 position, double radius) {
        double max = radius * radius;
        for (long packed : state.getLongArray("LocalOre")) {
            if (Vec3.atCenterOf(BlockPos.of(packed)).distanceToSqr(position) <= max) return true;
        }
        for (long packed : state.getLongArray("RemoteOre")) {
            if (Vec3.atCenterOf(BlockPos.of(packed)).distanceToSqr(position) <= max) return true;
        }
        return false;
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Resource priority fixture has not been set up");
        return marker;
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
        if (npc.getPersistentData().hasUUID(MARKER_UUID_KEY)) {
            var dimension = npc.getPersistentData().getString(MARKER_DIMENSION_KEY);
            for (ServerLevel level : player.getServer().getAllLevels()) {
                if (!level.dimension().location().toString().equals(dimension)) continue;
                var entity = level.getEntity(npc.getPersistentData().getUUID(MARKER_UUID_KEY));
                if (entity instanceof ArmorStand marker && marker.getTags().contains(MARKER_TAG)) return marker;
            }
        }
        for (ServerLevel level : player.getServer().getAllLevels()) {
            ArmorStand marker = level.getEntitiesOfClass(
                ArmorStand.class,
                npc.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> candidate.getTags().contains(MARKER_TAG)
                    && candidate.getPersistentData().getCompound(STATE_KEY).hasUUID("NpcUuid")
                    && candidate.getPersistentData().getCompound(STATE_KEY).getUUID("NpcUuid").equals(npc.getUUID())
            ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
            if (marker != null) return marker;
        }
        return null;
    }

    private static ArmorStand markerForNpc(CodexNpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        if (npc.getPersistentData().hasUUID(MARKER_UUID_KEY)) {
            var entity = level.getEntity(npc.getPersistentData().getUUID(MARKER_UUID_KEY));
            if (entity instanceof ArmorStand marker && marker.getTags().contains(MARKER_TAG)) return marker;
        }
        return level.getEntitiesOfClass(
            ArmorStand.class,
            npc.getBoundingBox().inflate(SEARCH_RADIUS),
            candidate -> candidate.getTags().contains(MARKER_TAG)
                && candidate.getPersistentData().getCompound(STATE_KEY).hasUUID("NpcUuid")
                && candidate.getPersistentData().getCompound(STATE_KEY).getUUID("NpcUuid").equals(npc.getUUID())
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

    private static void rememberMarker(CodexNpcEntity npc, ArmorStand marker) {
        npc.getPersistentData().putUUID(MARKER_UUID_KEY, marker.getUUID());
        npc.getPersistentData().putString(MARKER_DIMENSION_KEY, marker.level().dimension().location().toString());
        npc.getPersistentData().putLong(MARKER_POSITION_KEY, marker.blockPosition().asLong());
    }

    private static boolean hasMarkerReference(CodexNpcEntity npc) {
        return npc.getPersistentData().hasUUID(MARKER_UUID_KEY);
    }

    private static void clearMarkerReference(CodexNpcEntity npc) {
        npc.getPersistentData().remove(MARKER_UUID_KEY);
        npc.getPersistentData().remove(MARKER_DIMENSION_KEY);
        npc.getPersistentData().remove(MARKER_POSITION_KEY);
    }

    private static boolean insideSavedVolume(CompoundTag state, BlockPos position) {
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        int x = position.getX() - origin.getX();
        int y = position.getY() - origin.getY();
        int z = position.getZ() - origin.getZ();
        return x >= MIN_X && x <= MAX_X && y >= MIN_Y && y <= MAX_Y && z >= MIN_Z && z <= MAX_Z;
    }

    private static AABB fixtureBounds(CompoundTag state) {
        return fixtureBounds(BlockPos.of(state.getLong("Origin"))).inflate(2.0D);
    }

    private static AABB fixtureBounds(BlockPos origin) {
        return new AABB(
            origin.getX() + MIN_X,
            origin.getY() + MIN_Y,
            origin.getZ() + MIN_Z,
            origin.getX() + MAX_X + 1,
            origin.getY() + MAX_Y + 1,
            origin.getZ() + MAX_Z + 1
        );
    }

    private static void requireIdle(CodexNpcEntity npc) {
        requireNoTasks(npc);
        if (npc.isManagedEating()) throw new IllegalStateException("Finish NPC eating before resource priority setup");
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Finish NPC tasks before changing the resource priority fixture");
        }
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Resource priority block could not be placed");
        }
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

    private static boolean contains(long[] values, BlockPos position) {
        long packed = position.asLong();
        for (long value : values) if (value == packed) return true;
        return false;
    }

    private static long[] longs(List<BlockPos> positions) {
        long[] values = new long[positions.size()];
        for (int index = 0; index < positions.size(); index++) values[index] = positions.get(index).asLong();
        return values;
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
