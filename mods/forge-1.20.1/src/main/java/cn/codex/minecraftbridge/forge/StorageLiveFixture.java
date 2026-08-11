package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reversible home-storage state used only by loopback live acceptance tests. */
@SuppressWarnings("deprecation")
final class StorageLiveFixture {
    private static final String MARKER_TAG = "CodexAcceptanceStorageMarker";
    private static final String ITEM_TAG = "CodexAcceptanceStorageItem";
    private static final String ITEM_ROLE = "CodexAcceptanceStorageRole";
    private static final String STATE_KEY = "CodexAcceptanceStorageState";
    private static final String MARKER_UUID_KEY = "CodexAcceptanceStorageMarkerUuid";
    private static final String MARKER_DIMENSION_KEY = "CodexAcceptanceStorageMarkerDimension";
    private static final String MARKER_POSITION_KEY = "CodexAcceptanceStorageMarkerPosition";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final int SEARCH_RADIUS = 512;
    private static final int RESTART_ITEM_COUNT = 96;
    private static final int RESTART_COLUMN_HEIGHT = 3;
    private static final int RESTART_COLUMN_COUNT = RESTART_ITEM_COUNT / RESTART_COLUMN_HEIGHT;
    private static final int[] HOME_OFFSETS = { 0, 64, -64, 96, -96, 128, -128, 160, -160 };

    private StorageLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Storage fixture requires an in-world NPC");
        if (mode.startsWith("setup-")) requireSafeSetup(player, npc);
        switch (mode) {
            case "setup-retrieve" -> setup(player, npc, "retrieve");
            case "inspect-retrieve" -> inspectRetrieve(player, npc);
            case "setup-organize" -> setup(player, npc, "organize");
            case "inspect-organize" -> inspectOrganize(player, npc);
            case "setup-expand" -> setup(player, npc, "expand");
            case "inspect-expand" -> inspectExpand(player, npc);
            case "setup-restart" -> setup(player, npc, "restart");
            case "inspect-restart" -> inspectRestart(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown storage fixture mode");
        }
    }

    static String setupRefusalReason(
        boolean downed,
        String activeTaskId,
        int pausedTaskCount,
        int queuedTaskCount,
        String schedulerLifecycle,
        boolean sameDimension,
        boolean mounted
    ) {
        if (downed) return "Storage fixture requires an active NPC";
        if (activeTaskId != null && !activeTaskId.isBlank()) {
            return "Storage fixture requires no active task";
        }
        if (pausedTaskCount != 0) return "Storage fixture requires no paused task";
        if (queuedTaskCount != 0) return "Storage fixture requires an empty task queue";
        if (!"idle".equals(schedulerLifecycle)) return "Storage fixture requires an idle scheduler";
        if (!sameDimension) return "Storage fixture requires the owner and NPC in the same dimension";
        if (mounted) return "Storage fixture requires a dismounted NPC";
        return "";
    }

    private static void requireSafeSetup(ServerPlayer player, CodexNpcEntity npc) {
        String refusal = setupRefusalReason(
            npc.isDowned(),
            npc.tasks().activeTaskId(),
            npc.tasks().pausedTaskCount(),
            npc.tasks().observableTaskQueue().size(),
            npc.tasks().schedulerLifecycle(),
            player.level() == npc.level(),
            npc.isPassenger()
        );
        if (!refusal.isEmpty()) throw new IllegalStateException(refusal);
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc, String scenario) {
        cleanup(player, npc, false);
        requireSafeSetup(player, npc);
        ServerLevel level = player.serverLevel();
        BlockPos fixtureHome = findIsolatedHome(level, npc.blockPosition(), scenario);
        if (fixtureHome == null) throw new IllegalStateException("No isolated temporary home was found");

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Storage fixture marker could not be created");
        marker.moveTo(fixtureHome.getX() + 0.5D, fixtureHome.getY() - 2.0D,
            fixtureHome.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putString("Scenario", scenario);
        state.putString("FixtureDimension", level.dimension().location().toString());
        state.putLong("FixtureHome", fixtureHome.asLong());
        state.putDouble("StartX", npc.getX());
        state.putDouble("StartY", npc.getY());
        state.putDouble("StartZ", npc.getZ());
        state.putFloat("StartYaw", npc.getYRot());
        state.putFloat("StartPitch", npc.getXRot());
        saveRespawn(player, state);
        savePlayerSafety(player, state);
        protectPlayer(player);
        saveNpcState(npc, state);
        npc.cancelManagedEating();
        clearNpcInventory(npc);
        npc.setHealth(npc.getMaxHealth());
        npc.setFoodLevel(20);
        npc.setSaturationLevel(20.0F);
        npc.setExhaustionLevel(0.0F);

        player.setRespawnPosition(level.dimension(), fixtureHome, 0.0F, true, false);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            restoreRespawn(player, state);
            restorePlayerSafety(player, state);
            restoreNpcState(npc, state);
            throw new IllegalStateException("Storage fixture marker was rejected");
        }
        rememberMarker(npc, marker, level);

        List<BlockPos> fixtureChests = new ArrayList<>();
        try {
            NpcHomeStorage.Home home = new NpcHomeStorage.Home(level.dimension(), fixtureHome, true);
            switch (scenario) {
                case "retrieve" -> setupRetrieve(level, home, fixtureChests);
                case "organize" -> setupOrganize(level, home, npc, fixtureChests);
                case "expand" -> setupExpand(level, home, npc, fixtureChests);
                case "restart" -> setupRestart(level, home, fixtureChests);
                default -> throw new IllegalArgumentException("Unknown storage fixture scenario");
            }
            state.putLongArray("FixtureChests", fixtureChests.stream().mapToLong(BlockPos::asLong).toArray());
            marker.getPersistentData().put(STATE_KEY, state);

            BlockPos stand = scenario.equals("restart")
                ? findRestartStandPosition(level, home, fixtureChests)
                : findStandPosition(level, home, fixtureChests.get(0));
            if (stand == null) throw new IllegalStateException("No safe NPC position was found near fixture storage");
            npc.teleportTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            npc.getNavigation().stop();
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.tasks().stay();
            npc.setStatus("storage-fixture:setup scenario=" + scenario);
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void setupRetrieve(ServerLevel level, NpcHomeStorage.Home home, List<BlockPos> fixtureChests) {
        BlockPos first = placeEmptyChest(level, home, fixtureChests);
        BlockPos second = placeEmptyChest(level, home, fixtureChests);
        ((Container) level.getBlockEntity(first)).setItem(0, fixtureStack(Items.COBBLESTONE, 4, "retrieve"));
        ((Container) level.getBlockEntity(second)).setItem(0, fixtureStack(Items.COBBLESTONE, 4, "retrieve"));
    }

    private static void setupOrganize(
        ServerLevel level,
        NpcHomeStorage.Home home,
        CodexNpcEntity npc,
        List<BlockPos> fixtureChests
    ) {
        placeEmptyChest(level, home, fixtureChests);
        insertNpcStack(npc, fixtureStack(Items.DIRT, 4, "surplus"));
        insertNpcStack(npc, fixtureStack(Items.BREAD, 4, "food"));
    }

    private static void setupExpand(
        ServerLevel level,
        NpcHomeStorage.Home home,
        CodexNpcEntity npc,
        List<BlockPos> fixtureChests
    ) {
        BlockPos full = placeEmptyChest(level, home, fixtureChests);
        Container container = (Container) level.getBlockEntity(full);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, fixtureStack(Items.COBBLESTONE, 64, "filler"));
        }
        container.setChanged();
        insertNpcStack(npc, fixtureStack(Items.CHEST, 1, "expansion-chest"));
        insertNpcStack(npc, fixtureStack(Items.DIRT, 4, "surplus"));
    }

    private static void setupRestart(
        ServerLevel level,
        NpcHomeStorage.Home home,
        List<BlockPos> fixtureChests
    ) {
        loadPlacementArea(level, home.position(), HomeStoragePolicy.DEFAULT_RADIUS);
        List<BlockPos> columns = findRestartColumns(level, home, RESTART_COLUMN_COUNT);
        if (columns.size() < RESTART_COLUMN_COUNT) {
            throw new IllegalStateException(
                "Storage restart fixture found only " + columns.size() + " of " + RESTART_COLUMN_COUNT + " columns"
            );
        }
        for (BlockPos base : columns) {
            for (int height = 0; height < RESTART_COLUMN_HEIGHT; height++) {
                BlockPos position = base.above(height);
                if (!level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState())
                    || !(level.getBlockEntity(position) instanceof Container container)) {
                    throw new IllegalStateException(
                        "Storage restart fixture column could not be placed after " + fixtureChests.size()
                    );
                }
                fixtureChests.add(position.immutable());
                container.setItem(0, fixtureStack(Items.COBBLESTONE, 1, "restart"));
                container.setChanged();
            }
        }
    }

    private static List<BlockPos> findRestartColumns(
        ServerLevel level,
        NpcHomeStorage.Home home,
        int limit
    ) {
        BlockPos origin = home.position();
        List<BlockPos> best = List.of();
        for (int y = -4; y <= 4; y++) {
            List<BlockPos> candidates = new ArrayList<>();
            for (int x = -HomeStoragePolicy.DEFAULT_RADIUS; x <= HomeStoragePolicy.DEFAULT_RADIUS; x++) {
                for (int z = -HomeStoragePolicy.DEFAULT_RADIUS; z <= HomeStoragePolicy.DEFAULT_RADIUS; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    if (position.equals(origin) || !level.hasChunkAt(position)
                        || !level.getWorldBorder().isWithinBounds(position)) continue;
                    BlockPos support = position.below();
                    if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) continue;
                    if (restartColumnIsClear(level, home, List.of(), position)) {
                        candidates.add(position.immutable());
                    }
                }
            }
            candidates.sort(Comparator.comparingDouble(position -> position.distSqr(origin)));

            List<BlockPos> columns = new ArrayList<>();
            List<BlockPos> selectedCells = new ArrayList<>();
            for (BlockPos candidate : candidates) {
                if (!restartColumnIsClear(level, home, selectedCells, candidate)) continue;
                columns.add(candidate);
                for (int height = 0; height < RESTART_COLUMN_HEIGHT; height++) {
                    selectedCells.add(candidate.above(height).immutable());
                }
                if (columns.size() >= limit) return List.copyOf(columns);
            }
            if (columns.size() > best.size()) best = List.copyOf(columns);
        }
        return best;
    }

    private static boolean restartColumnIsClear(
        ServerLevel level,
        NpcHomeStorage.Home home,
        List<BlockPos> existing,
        BlockPos base
    ) {
        for (int height = 0; height < RESTART_COLUMN_HEIGHT; height++) {
            BlockPos position = base.above(height);
            if (!withinHomeStorageBounds(home, position) || existing.contains(position)) return false;
            if (!level.getBlockState(position).canBeReplaced() || !level.getFluidState(position).isEmpty()) return false;
            if (!isolatedChestCell(level, position) || adjacentRestartChest(existing, position)) return false;
        }
        return restartColumnHasWalkway(level, base);
    }

    private static boolean adjacentRestartChest(List<BlockPos> existing, BlockPos position) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (existing.contains(position.relative(direction))) return true;
        }
        return false;
    }

    private static boolean restartColumnHasWalkway(ServerLevel level, BlockPos base) {
        int accessibleSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (isRestartStandCell(level, base.relative(direction))) accessibleSides++;
        }
        return accessibleSides >= 2;
    }

    private static boolean isRestartStandCell(ServerLevel level, BlockPos position) {
        BlockPos support = position.below();
        return level.hasChunkAt(position)
            && level.getWorldBorder().isWithinBounds(position)
            && level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
            && level.getBlockState(position).getCollisionShape(level, position).isEmpty()
            && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
            && level.getFluidState(position).isEmpty()
            && level.getFluidState(position.above()).isEmpty();
    }

    private static boolean withinHomeStorageBounds(NpcHomeStorage.Home home, BlockPos position) {
        BlockPos origin = home.position();
        return Math.abs(position.getX() - origin.getX()) <= HomeStoragePolicy.DEFAULT_RADIUS
            && Math.abs(position.getY() - origin.getY()) <= 8
            && Math.abs(position.getZ() - origin.getZ()) <= HomeStoragePolicy.DEFAULT_RADIUS;
    }

    private static void loadPlacementArea(ServerLevel level, BlockPos center, int radius) {
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static BlockPos placeEmptyChest(
        ServerLevel level,
        NpcHomeStorage.Home home,
        List<BlockPos> existing
    ) {
        BlockPos position = NpcHomeStorage.findSafePlacement(
            level,
            home,
            home.position(),
            12,
            candidate -> !existing.contains(candidate) && isolatedChestCell(level, candidate)
        );
        if (position == null || !level.setBlockAndUpdate(position, Blocks.CHEST.defaultBlockState())
            || !(level.getBlockEntity(position) instanceof Container)) {
            throw new IllegalStateException("Storage fixture chest could not be placed");
        }
        existing.add(position.immutable());
        return position;
    }

    private static BlockPos findStandPosition(ServerLevel level, NpcHomeStorage.Home home, BlockPos anchor) {
        return NpcHomeStorage.findSafePlacement(
            level,
            home,
            anchor,
            5,
            position -> !position.equals(anchor)
                && level.getBlockState(position).isAir()
                && level.getBlockState(position.above()).isAir()
        );
    }

    private static BlockPos findRestartStandPosition(
        ServerLevel level,
        NpcHomeStorage.Home home,
        List<BlockPos> fixtureChests
    ) {
        Set<BlockPos> placed = new HashSet<>(fixtureChests);
        List<BlockPos> bases = fixtureChests.stream()
            .filter(position -> !placed.contains(position.below()))
            .sorted(Comparator.comparingDouble(position -> position.distSqr(home.position())))
            .toList();
        for (BlockPos base : bases) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos stand = base.relative(direction);
                if (isRestartStandCell(level, stand) && !placed.contains(stand)) return stand.immutable();
            }
        }
        return null;
    }

    private static BlockPos findIsolatedHome(ServerLevel level, BlockPos origin, String scenario) {
        for (int dx : HOME_OFFSETS) {
            for (int dz : HOME_OFFSETS) {
                if (Math.abs(dx) + Math.abs(dz) < 64) continue;
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!level.getWorldBorder().isWithinBounds(candidate)) continue;
                level.getChunkAt(candidate);
                loadPlacementArea(level, candidate, HomeStoragePolicy.DEFAULT_RADIUS);
                NpcHomeStorage.Home home = new NpcHomeStorage.Home(level.dimension(), candidate, true);
                if (!NpcHomeStorage.findContainers(level, home, HomeStoragePolicy.DEFAULT_RADIUS).isEmpty()) continue;
                if (scenario.equals("restart") && restartCellCapacity(level, home) < RESTART_ITEM_COUNT) continue;
                BlockPos placement = NpcHomeStorage.findSafePlacement(
                    level, home, candidate, 10, position -> isolatedChestCell(level, position)
                );
                if (placement != null) return candidate.immutable();
            }
        }
        return null;
    }

    private static int restartCellCapacity(ServerLevel level, NpcHomeStorage.Home home) {
        loadPlacementArea(level, home.position(), HomeStoragePolicy.DEFAULT_RADIUS);
        return findRestartColumns(level, home, RESTART_COLUMN_COUNT).size() * RESTART_COLUMN_HEIGHT;
    }

    private static void inspectRetrieve(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc, "retrieve");
        int home = countHome(context, "retrieve");
        int npcCount = countNpc(npc, "retrieve");
        int playerCount = countPlayer(player, "retrieve");
        int world = countWorld(player, context, "retrieve");
        int near = countWorldNearPlayer(player, "retrieve");
        int containers = containersWithRole(context, "retrieve");
        npc.tasks().stay();
        npc.setStatus("storage-fixture:retrieve home=" + home + ",npc=" + npcCount
            + ",player=" + playerCount + ",world=" + world + ",near=" + near
            + ",containers=" + containers);
    }

    private static void inspectOrganize(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc, "organize");
        int homeSurplus = countHome(context, "surplus");
        int npcSurplus = countNpc(npc, "surplus");
        int npcFood = countNpc(npc, "food");
        int homeFood = countHome(context, "food");
        int containers = containersWithFixtureItems(context);
        npc.tasks().stay();
        npc.setStatus("storage-fixture:organize homeSurplus=" + homeSurplus + ",npcSurplus=" + npcSurplus
            + ",npcFood=" + npcFood + ",homeFood=" + homeFood + ",containers=" + containers);
    }

    private static void inspectExpand(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc, "expand");
        int homeFiller = countHome(context, "filler");
        int homeSurplus = countHome(context, "surplus");
        int npcFixture = countNpc(npc, null);
        Set<BlockPos> fixtureChests = blockSet(context.state().getLongArray("FixtureChests"));
        int expanded = 0;
        for (BlockPos position : NpcHomeStorage.findContainers(context.level(), context.home(), HomeStoragePolicy.DEFAULT_RADIUS)) {
            if (!fixtureChests.contains(position)) expanded++;
        }
        npc.tasks().stay();
        npc.setStatus("storage-fixture:expand homeFiller=" + homeFiller + ",homeSurplus=" + homeSurplus
            + ",npc=" + npcFixture + ",expanded=" + expanded);
    }

    private static void inspectRestart(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc, "restart");
        int home = countHome(context, "restart");
        int npcCount = countNpc(npc, "restart");
        int playerCount = countPlayer(player, "restart");
        int world = countWorld(player, context, "restart");
        int near = countWorldNearPlayer(player, "restart");
        int containers = containersWithRole(context, "restart");
        npc.tasks().stay();
        npc.setStatus("storage-fixture:restart home=" + home + ",npc=" + npcCount
            + ",player=" + playerCount + ",world=" + world + ",near=" + near
            + ",containers=" + containers);
    }

    private static FixtureContext requireContext(ServerPlayer player, CodexNpcEntity npc, String scenario) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Storage fixture has not been set up");
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!scenario.equals(state.getString("Scenario"))) throw new IllegalStateException("Storage fixture scenario mismatch");
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("FixtureDimension"))
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Storage fixture dimension is unavailable");
        return new FixtureContext(marker, state, level,
            new NpcHomeStorage.Home(dimension, BlockPos.of(state.getLong("FixtureHome")), true));
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (hasMarkerReference(npc)) {
                throw new IllegalStateException("Storage fixture marker reference could not be resolved");
            }
            removeFixtureStacks(player, npc);
            if (report) reportAndRestoreStatus(player, npc, "storage-fixture:cleanup none", npc.status());
            return;
        }

        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        String fixtureDimension = state.getString("FixtureDimension");
        String cleanupRefusal = cleanupDimensionRefusalReason(
            fixtureDimension,
            player.level().dimension().location().toString(),
            npc.level().dimension().location().toString()
        );
        if (!cleanupRefusal.isEmpty()) throw new IllegalStateException(cleanupRefusal);
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(fixtureDimension)
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Storage fixture dimension is unavailable during cleanup");
        removeFixtureStacks(player, npc);
        NpcHomeStorage.Home home = new NpcHomeStorage.Home(
            dimension, BlockPos.of(state.getLong("FixtureHome")), true
        );
        removeFixtureStacksFromHome(level, home);
        Set<BlockPos> original = blockSet(state.getLongArray("FixtureChests"));
        List<BlockPos> candidates = new ArrayList<>(NpcHomeStorage.findContainers(
            level, home, HomeStoragePolicy.DEFAULT_RADIUS
        ));
        candidates.sort(Comparator.comparingDouble(position -> -position.distSqr(home.position())));
        for (BlockPos position : candidates) {
            if (!original.contains(position) && !isEmptyFixtureChest(level, position)) continue;
            if (isEmptyFixtureChest(level, position)) level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(home.position()).inflate(SEARCH_RADIUS),
            entity -> isFixtureStack(entity.getItem())
        )) item.discard();
        for (ItemEntity item : player.serverLevel().getEntitiesOfClass(
            ItemEntity.class,
            player.getBoundingBox().inflate(SEARCH_RADIUS),
            entity -> isFixtureStack(entity.getItem())
        )) item.discard();

        restoreRespawn(player, state);
        restorePlayerSafety(player, state);
        boolean restoredFullState = restoreNpcState(npc, state);
        if (!restoredFullState) {
            restoreBackpack(npc, state);
            npc.teleportTo(state.getDouble("StartX"), state.getDouble("StartY"), state.getDouble("StartZ"));
            npc.setYRot(state.getFloat("StartYaw"));
            npc.setXRot(state.getFloat("StartPitch"));
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.tasks().followOwner();
        }
        String restoredStatus = npc.status();
        clearMarkerReference(npc);
        marker.discard();
        if (report) reportAndRestoreStatus(player, npc, "storage-fixture:cleanup restored", restoredStatus);
    }

    static String cleanupDimensionRefusalReason(
        String fixtureDimension,
        String playerDimension,
        String npcDimension
    ) {
        if (fixtureDimension == null || fixtureDimension.isBlank()) {
            return "Storage fixture dimension snapshot is missing";
        }
        if (!fixtureDimension.equals(playerDimension) || !fixtureDimension.equals(npcDimension)) {
            return "Storage fixture cleanup requires the owner and NPC in the fixture dimension";
        }
        return "";
    }

    private static void saveNpcState(CodexNpcEntity npc, CompoundTag state) {
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
    }

    private static boolean restoreNpcState(CodexNpcEntity npc, CompoundTag state) {
        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) return false;
        npc.load(state.getCompound(SAVED_NPC_KEY));
        npc.getNavigation().stop();
        return true;
    }

    private static void clearNpcInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void rememberMarker(CodexNpcEntity npc, ArmorStand marker, ServerLevel level) {
        CompoundTag data = npc.getPersistentData();
        data.putUUID(MARKER_UUID_KEY, marker.getUUID());
        data.putString(MARKER_DIMENSION_KEY, level.dimension().location().toString());
        data.putLong(MARKER_POSITION_KEY, marker.blockPosition().asLong());
    }

    private static boolean hasMarkerReference(CodexNpcEntity npc) {
        return npc.getPersistentData().hasUUID(MARKER_UUID_KEY);
    }

    private static void clearMarkerReference(CodexNpcEntity npc) {
        CompoundTag data = npc.getPersistentData();
        data.remove(MARKER_UUID_KEY);
        data.remove(MARKER_DIMENSION_KEY);
        data.remove(MARKER_POSITION_KEY);
    }

    private static void reportAndRestoreStatus(
        ServerPlayer player,
        CodexNpcEntity npc,
        String acknowledgementStatus,
        String restoredStatus
    ) {
        npc.setStatus(acknowledgementStatus);
        var server = player.getServer();
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            if (!npc.isRemoved() && npc.tasks().activeTaskId().isBlank()) npc.setStatus(restoredStatus);
        }));
    }

    private static void saveRespawn(ServerPlayer player, CompoundTag state) {
        BlockPos position = player.getRespawnPosition();
        state.putBoolean("HadRespawn", position != null);
        if (position == null) return;
        state.putString("RespawnDimension", player.getRespawnDimension().location().toString());
        state.putLong("RespawnPosition", position.asLong());
        state.putFloat("RespawnAngle", player.getRespawnAngle());
        state.putBoolean("RespawnForced", player.isRespawnForced());
    }

    private static void restoreRespawn(ServerPlayer player, CompoundTag state) {
        if (!state.getBoolean("HadRespawn")) {
            player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
            return;
        }
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("RespawnDimension"))
        );
        player.setRespawnPosition(
            dimension,
            BlockPos.of(state.getLong("RespawnPosition")),
            state.getFloat("RespawnAngle"),
            state.getBoolean("RespawnForced"),
            false
        );
    }

    private static void savePlayerSafety(ServerPlayer player, CompoundTag state) {
        state.putBoolean("PlayerSafetySaved", true);
        state.putBoolean("PlayerInvulnerable", player.getAbilities().invulnerable);
        state.putInt("PlayerAirSupply", player.getAirSupply());
        state.putInt("PlayerFireTicks", player.getRemainingFireTicks());
    }

    private static void protectPlayer(ServerPlayer player) {
        player.getAbilities().invulnerable = true;
        player.setAirSupply(player.getMaxAirSupply());
        player.clearFire();
        player.onUpdateAbilities();
    }

    private static void restorePlayerSafety(ServerPlayer player, CompoundTag state) {
        if (!state.getBoolean("PlayerSafetySaved")) return;
        player.getAbilities().invulnerable = state.getBoolean("PlayerInvulnerable");
        player.setAirSupply(state.getInt("PlayerAirSupply"));
        player.setRemainingFireTicks(state.getInt("PlayerFireTicks"));
        player.onUpdateAbilities();
    }

    private static void saveBackpack(CodexNpcEntity npc, CompoundTag state) {
        ListTag saved = new ListTag();
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Stack", stack.save(new CompoundTag()));
            saved.add(entry);
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        state.put("SavedBackpack", saved);
    }

    private static void restoreBackpack(CodexNpcEntity npc, CompoundTag state) {
        ListTag saved = state.getList("SavedBackpack", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag entry = saved.getCompound(index);
            int slot = entry.getInt("Slot");
            ItemStack stack = ItemStack.of(entry.getCompound("Stack"));
            if (slot >= 0 && slot < CodexNpcEntity.BACKPACK_SIZE
                && npc.inventory().getStackInSlot(slot).isEmpty()) {
                npc.inventory().setStackInSlot(slot, stack);
                continue;
            }
            ItemStack remainder = npc.insert(stack);
            if (!remainder.isEmpty() && npc.level() instanceof ServerLevel level) {
                level.addFreshEntity(new ItemEntity(level, npc.getX(), npc.getY() + 0.5D, npc.getZ(), remainder));
            }
        }
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag data = npc.getPersistentData();
        if (data.hasUUID(MARKER_UUID_KEY)
            && data.contains(MARKER_DIMENSION_KEY, Tag.TAG_STRING)
            && data.contains(MARKER_POSITION_KEY, Tag.TAG_LONG)) {
            ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(data.getString(MARKER_DIMENSION_KEY))
            );
            ServerLevel level = player.getServer().getLevel(dimension);
            if (level != null) {
                BlockPos markerPosition = BlockPos.of(data.getLong(MARKER_POSITION_KEY));
                level.getChunkAt(markerPosition);
                if (level.getEntity(data.getUUID(MARKER_UUID_KEY)) instanceof ArmorStand marker
                    && marker.getTags().contains(MARKER_TAG)) return marker;
                ArmorStand nearby = level.getEntitiesOfClass(
                    ArmorStand.class,
                    new AABB(markerPosition).inflate(4.0D),
                    candidate -> candidate.getUUID().equals(data.getUUID(MARKER_UUID_KEY))
                        && candidate.getTags().contains(MARKER_TAG)
                ).stream().findFirst().orElse(null);
                if (nearby != null) return nearby;
            }
        }
        for (ServerLevel level : player.getServer().getAllLevels()) {
            ArmorStand marker = level.getEntitiesOfClass(
                ArmorStand.class,
                npc.getBoundingBox().inflate(SEARCH_RADIUS),
                candidate -> candidate.getTags().contains(MARKER_TAG)
            ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
            if (marker != null) return marker;
        }
        return null;
    }

    private static void removeFixtureStacks(ServerPlayer player, CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            if (isFixtureStack(npc.inventory().getStackInSlot(slot))) npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isFixtureStack(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
    }

    private static void removeFixtureStacksFromHome(ServerLevel level, NpcHomeStorage.Home home) {
        for (BlockPos position : NpcHomeStorage.findContainers(level, home, HomeStoragePolicy.DEFAULT_RADIUS)) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            boolean changed = false;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!isFixtureStack(container.getItem(slot))) continue;
                container.setItem(slot, ItemStack.EMPTY);
                changed = true;
            }
            if (changed) container.setChanged();
        }
    }

    private static int countHome(FixtureContext context, String role) {
        int count = 0;
        for (BlockPos position : NpcHomeStorage.findContainers(
            context.level(), context.home(), HomeStoragePolicy.DEFAULT_RADIUS
        )) {
            BlockEntity blockEntity = context.level().getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            count += countContainer(container, role);
        }
        return count;
    }

    private static int containersWithRole(FixtureContext context, String role) {
        int count = 0;
        for (BlockPos position : NpcHomeStorage.findContainers(
            context.level(), context.home(), HomeStoragePolicy.DEFAULT_RADIUS
        )) {
            BlockEntity blockEntity = context.level().getBlockEntity(position);
            if (blockEntity instanceof Container container && countContainer(container, role) > 0) count++;
        }
        return count;
    }

    private static int containersWithFixtureItems(FixtureContext context) {
        return containersWithRole(context, null);
    }

    private static int countContainer(Container container, String role) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isFixtureStack(stack) && (role == null || role.equals(itemRole(stack)))) count += stack.getCount();
        }
        return count;
    }

    private static int countNpc(CodexNpcEntity npc, String role) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (isFixtureStack(stack) && (role == null || role.equals(itemRole(stack)))) count += stack.getCount();
        }
        return count;
    }

    private static int countPlayer(ServerPlayer player, String role) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isFixtureStack(stack) && (role == null || role.equals(itemRole(stack)))) count += stack.getCount();
        }
        return count;
    }

    private static int countWorld(ServerPlayer player, FixtureContext context, String role) {
        return context.level().getEntitiesOfClass(
            ItemEntity.class,
            new AABB(context.home().position()).inflate(SEARCH_RADIUS),
            entity -> isFixtureStack(entity.getItem()) && role.equals(itemRole(entity.getItem()))
        ).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static int countWorldNearPlayer(ServerPlayer player, String role) {
        return player.serverLevel().getEntitiesOfClass(
            ItemEntity.class,
            player.getBoundingBox().inflate(3.0D),
            entity -> isFixtureStack(entity.getItem()) && role.equals(itemRole(entity.getItem()))
        ).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static ItemStack fixtureStack(Item item, int count, String role) {
        ItemStack stack = new ItemStack(item, count);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(ITEM_TAG, true);
        tag.putString(ITEM_ROLE, role);
        return stack;
    }

    private static void insertNpcStack(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = npc.insert(stack);
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC backpack has no room for storage fixture state");
    }

    private static boolean isFixtureStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getTag() != null && stack.getTag().getBoolean(ITEM_TAG);
    }

    private static String itemRole(ItemStack stack) {
        return stack.getTag() == null ? "" : stack.getTag().getString(ITEM_ROLE);
    }

    private static boolean isolatedChestCell(ServerLevel level, BlockPos position) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(position.relative(direction)).is(Blocks.CHEST)
                || level.getBlockState(position.relative(direction)).is(Blocks.TRAPPED_CHEST)) return false;
        }
        return true;
    }

    private static boolean isEmptyFixtureChest(ServerLevel level, BlockPos position) {
        if (!level.getBlockState(position).is(Blocks.CHEST)
            && !level.getBlockState(position).is(Blocks.TRAPPED_CHEST)) return false;
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (!(blockEntity instanceof Container container)) return false;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    private static Set<BlockPos> blockSet(long[] values) {
        Set<BlockPos> result = new HashSet<>();
        for (long value : values) result.add(BlockPos.of(value));
        return result;
    }

    private record FixtureContext(
        ArmorStand marker,
        CompoundTag state,
        ServerLevel level,
        NpcHomeStorage.Home home
    ) {}
}
