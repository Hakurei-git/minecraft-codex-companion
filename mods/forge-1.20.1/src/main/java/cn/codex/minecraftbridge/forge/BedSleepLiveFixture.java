package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Strictly reversible world proof for crafting, placing, sleeping in, and leaving a bed. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class BedSleepLiveFixture {
    private static final String SUITE = "bed-sleep";
    private static final String MARKER_TAG = "CodexAcceptanceBedSleepMarker";
    private static final String SHEEP_TAG = "CodexAcceptanceBedSleepSheep";
    private static final String ITEM_TAG = "CodexAcceptanceBedSleepItem";
    private static final String STATE_KEY = "CodexAcceptanceBedSleepState";
    private static final String SAVED_PLAYER_KEY = "SavedPlayer";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final String MARKER_UUID_KEY = "CodexAcceptanceBedSleepMarkerUuid";
    private static final String MARKER_DIMENSION_KEY = "CodexAcceptanceBedSleepMarkerDimension";
    private static final int PLATFORM_RADIUS = 24;
    private static final int SEARCH_RADIUS = 512;
    private static final int[] SITE_OFFSETS = { 64, -64, 96, -96, 128, -128, 160, -160 };

    private BedSleepLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Bed sleep fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "inspect" -> inspect(player, npc);
            case "prepare-night" -> prepareNight(player, npc);
            case "wake-day" -> wakeDay(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown bed sleep fixture mode");
        }
    }

    @SubscribeEvent
    public static void recordBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
            || !(event.getLevel() instanceof ServerLevel level)
            || !(event.getPlayer() instanceof FakePlayer)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 64.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!contains(state.getLongArray("TreeLogs"), event.getPos())) return;
        state.putInt("LogBreaks", state.getInt("LogBreaks") + 1);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordPlacement(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()
            || !(event.getLevel() instanceof ServerLevel level)
            || !(event.getEntity() instanceof FakePlayer)) return;
        BlockState placed = event.getPlacedBlock();
        if (!placed.is(Blocks.CRAFTING_TABLE) && !(placed.getBlock() instanceof BedBlock)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 64.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (placed.is(Blocks.CRAFTING_TABLE)) {
            if (appendUnique(state, "DynamicBlocks", event.getPos())) {
                state.putInt("TablePlacements", state.getInt("TablePlacements") + 1);
            }
            state.putBoolean("SawCraftingTable", true);
        } else {
            recordBedPair(state, event.getPos(), placed);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordItemJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        ArmorStand marker = markerNear(level, item.position(), 64.0D);
        if (marker == null || !insideFixture(fixtureState(marker), item.position())) return;
        if (isFixtureItem(item.getItem().getItem())) item.addTag(ITEM_TAG);
    }

    @SubscribeEvent
    public static void observeTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        CodexNpcEntity npc = NpcManager.find(player);
        if (npc == null) return;
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) return;
        try {
            observePhysicalState(marker, npc, player.tickCount % 10 == 0);
        } catch (RuntimeException error) {
            CompoundTag state = fixtureState(marker);
            state.putInt("ObservationErrors", state.getInt("ObservationErrors") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
        }
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        if (player.level() != npc.level()) {
            throw new IllegalStateException("Bed sleep fixture requires the owner and NPC in the same dimension");
        }
        cleanup(player, npc, false);
        requireSafeSetup(player, npc);

        ServerLevel level = player.serverLevel();
        BlockPos home = findSite(level, npc.blockPosition());
        LinkedHashSet<BlockPos> fixtureBlocks = new LinkedHashSet<>();
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                fixtureBlocks.add(home.offset(x, -1, z));
            }
        }

        List<BlockPos> treeLogs = new ArrayList<>();
        LinkedHashSet<BlockPos> leaves = new LinkedHashSet<>();
        List<BlockPos> roots = List.of(
            home.offset(20, 0, -12),
            home.offset(20, 0, 0),
            home.offset(20, 0, 12)
        );
        for (BlockPos root : roots) {
            for (int y = 0; y < 5; y++) treeLogs.add(root.above(y));
            leaves.addAll(canopy(root.above(4)));
            fixtureBlocks.add(root.below());
        }
        leaves.removeAll(treeLogs);
        fixtureBlocks.addAll(treeLogs);
        fixtureBlocks.addAll(leaves);

        for (BlockPos position : fixtureBlocks) {
            if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                throw new IllegalStateException("Bed sleep fixture site changed before setup at " + position.toShortString());
            }
        }

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Bed sleep fixture marker could not be created");
        marker.moveTo(home.getX() + 0.5D, home.getY() + 10.0D, home.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 1);
        state.putString("FixtureDimension", level.dimension().location().toString());
        state.putLong("Home", home.asLong());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putUUID("NpcUuid", npc.getUUID());
        state.putLongArray("FixtureBlocks", longs(fixtureBlocks));
        state.putLongArray("TreeLogs", longs(treeLogs));
        state.putLongArray("DynamicBlocks", new long[0]);
        saveWorldState(level, state);
        saveRespawn(player, state);
        saveActorState(player, npc, state);
        state.putBoolean("RecipesVerified", verifyRecipes(level));
        if (!state.getBoolean("RecipesVerified")) {
            throw new IllegalStateException("Bed sleep fixture could not verify the vanilla bed dependency recipes");
        }
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            throw new IllegalStateException("Bed sleep fixture marker was rejected");
        }
        rememberMarker(npc, marker, level);

        try {
            for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
                for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                    set(level, home.offset(x, -1, z), Blocks.SEA_LANTERN.defaultBlockState());
                }
            }
            for (BlockPos root : roots) set(level, root.below(), Blocks.DIRT.defaultBlockState());
            for (BlockPos position : treeLogs) set(level, position, Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
            for (BlockPos position : leaves) set(level, position, leaf);

            spawnSheep(level, home.offset(6, 0, -5));
            spawnSheep(level, home.offset(8, 0, 0));
            spawnSheep(level, home.offset(6, 0, 5));

            clearNpcInventory(npc);
            insert(npc, new ItemStack(Items.IRON_INGOT, 2));
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            npc.cancelManagedEating();
            npc.teleportTo(home.getX() + 0.5D, home.getY(), home.getZ() + 0.5D);
            npc.getNavigation().stop();
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.tasks().stay();

            BlockPos playerStart = home.offset(-4, 0, 0);
            player.connection.teleport(
                playerStart.getX() + 0.5D,
                playerStart.getY(),
                playerStart.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot()
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;

            player.getAbilities().invulnerable = true;
            player.setAirSupply(player.getMaxAirSupply());
            player.clearFire();
            player.onUpdateAbilities();
            player.setRespawnPosition(level.dimension(), home, 0.0F, true, false);
            npc.setNextLiveFixtureAckStatus("bed-sleep:setup|home="
                + home.getX() + "," + home.getY() + "," + home.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        observePhysicalState(marker, npc, true);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = (ServerLevel) marker.level();
        BlockPos bed = state.contains("BedFoot", Tag.TAG_LONG) ? BlockPos.of(state.getLong("BedFoot")) : null;
        int bedPair = bed != null && isWhiteBedPair(level, bed) ? 1 : 0;
        int homeDistance = bed == null ? -1 : (int) Math.round(bed.distSqr(BlockPos.of(state.getLong("Home"))));
        String bedPosition = bed == null ? "none" : bed.getX() + "," + bed.getY() + "," + bed.getZ();
        String evidence = "bed-sleep:i|"
            + countItem(npc, Items.IRON_INGOT) + ","
            + countItem(npc, Items.OAK_LOG) + ","
            + countItem(npc, Items.OAK_PLANKS) + ","
            + countItem(npc, Items.SHEARS) + ","
            + countItem(npc, Items.WHITE_WOOL) + ","
            + countItem(npc, Items.CRAFTING_TABLE) + ","
            + countItem(npc, Items.WHITE_BED) + ","
            + state.getInt("LogBreaks") + ","
            + state.getInt("SheepSheared") + ","
            + state.getInt("TablePlacements") + ","
            + state.getInt("BedPlacements") + ","
            + bedPair + ","
            + homeDistance + ","
            + bit(state.getBoolean("SawPlanks")) + ","
            + bit(state.getBoolean("SawShears")) + ","
            + bit(state.getBoolean("SawWool")) + ","
            + bit(state.getBoolean("SawCraftingTable")) + ","
            + bit(state.getBoolean("SawBedItem")) + ","
            + bit(state.getBoolean("SawSleeping")) + ","
            + bit(npc.isSleeping()) + ","
            + bit(state.getBoolean("SawLeftBed")) + ","
            + bit(level.isDay()) + ","
            + bit(state.getBoolean("RecipesVerified")) + ","
            + state.getInt("ObservationErrors")
            + "|bed=" + bedPosition;
        npc.setNextLiveFixtureAckStatus(evidence);
    }

    private static void prepareNight(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = requireFixtureLevel(player, npc, state);
        long dayTime = level.getDayTime();
        level.setDayTime(dayTime - Math.floorMod(dayTime, 24_000L) + 13_000L);
        level.setWeatherParameters(6_000, 0, false, false);
        state.putBoolean("NightPrepared", true);
        marker.getPersistentData().put(STATE_KEY, state);
        npc.setNextLiveFixtureAckStatus("bed-sleep:night|day=" + bit(level.isDay()));
    }

    private static void wakeDay(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = requireFixtureLevel(player, npc, state);
        if (!"sleep".equals(npc.tasks().activeTaskKind()) || !state.getBoolean("SawSleeping")) {
            throw new IllegalStateException("Bed sleep fixture requires an observed active sleep task before waking");
        }
        long dayTime = level.getDayTime();
        level.setDayTime(dayTime + (24_000L - Math.floorMod(dayTime, 24_000L)));
        state.putBoolean("DayRequested", true);
        marker.getPersistentData().put(STATE_KEY, state);
        npc.setNextLiveFixtureAckStatus("bed-sleep:day|day=" + bit(level.isDay()));
    }

    private static void observePhysicalState(ArmorStand marker, CodexNpcEntity npc, boolean scanBlocks) {
        CompoundTag state = fixtureState(marker);
        if (countItem(npc, Items.OAK_PLANKS) > 0) state.putBoolean("SawPlanks", true);
        if (countItem(npc, Items.SHEARS) > 0) state.putBoolean("SawShears", true);
        if (countItem(npc, Items.WHITE_WOOL) > 0) state.putBoolean("SawWool", true);
        if (countItem(npc, Items.CRAFTING_TABLE) > 0) state.putBoolean("SawCraftingTable", true);
        if (countItem(npc, Items.WHITE_BED) > 0) state.putBoolean("SawBedItem", true);

        if (npc.isSleeping()) {
            state.putBoolean("SawSleeping", true);
            npc.getSleepingPos().ifPresent(position -> state.putLong("SleepingBed", position.asLong()));
        } else if (state.getBoolean("SawSleeping")) {
            state.putBoolean("SawLeftBed", true);
        }

        ServerLevel level = (ServerLevel) marker.level();
        int sheared = level.getEntitiesOfClass(
            Sheep.class,
            fixtureBounds(state).inflate(2.0D),
            sheep -> sheep.getTags().contains(SHEEP_TAG) && sheep.isSheared()
        ).size();
        state.putInt("SheepSheared", Math.max(state.getInt("SheepSheared"), sheared));

        if (scanBlocks) {
            BlockPos home = BlockPos.of(state.getLong("Home"));
            BlockPos bed = findWhiteBedFoot(level, home, 12);
            if (bed != null) {
                BlockState bedState = level.getBlockState(bed);
                recordBedPair(state, bed, bedState);
            }
            BlockPos origin = home;
            for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
                for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                    BlockPos position = origin.offset(x, 0, z);
                    if (!level.getBlockState(position).is(Blocks.CRAFTING_TABLE)) continue;
                    if (appendUnique(state, "DynamicBlocks", position)) {
                        state.putInt("TablePlacements", state.getInt("TablePlacements") + 1);
                    }
                    state.putBoolean("SawCraftingTable", true);
                }
            }
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void recordBedPair(CompoundTag state, BlockPos position, BlockState placed) {
        if (!(placed.getBlock() instanceof BedBlock)
            || !placed.hasProperty(BedBlock.PART)
            || !placed.hasProperty(BedBlock.FACING)) return;
        Direction facing = placed.getValue(BedBlock.FACING);
        BlockPos foot = placed.getValue(BedBlock.PART) == BedPart.FOOT
            ? position
            : position.relative(facing.getOpposite());
        BlockPos head = foot.relative(facing);
        boolean newPair = appendUnique(state, "DynamicBlocks", foot);
        appendUnique(state, "DynamicBlocks", head);
        state.putLong("BedFoot", foot.asLong());
        if (newPair) state.putInt("BedPlacements", state.getInt("BedPlacements") + 1);
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (hasMarkerReference(npc)) {
                throw new IllegalStateException("Bed sleep fixture marker reference could not be resolved");
            }
            if (report) npc.setNextLiveFixtureAckStatus("bed-sleep:cleanup|none");
            return;
        }
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = requireFixtureLevel(player, npc, state);

        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(8.0D),
            entity -> entity.getTags().contains(ITEM_TAG)
        )) item.discard();
        for (Sheep sheep : level.getEntitiesOfClass(
            Sheep.class,
            fixtureBounds(state).inflate(8.0D),
            entity -> entity.getTags().contains(SHEEP_TAG)
        )) sheep.discard();

        int conflicts = restoreAir(level, reverse(state.getLongArray("DynamicBlocks")), true);
        conflicts += restoreAir(level, reverse(state.getLongArray("FixtureBlocks")), false);
        restoreRespawn(player, state);
        restoreWorldState(level, state);
        boolean playerRestored = restorePlayerState(player, state);
        boolean npcRestored = restoreNpcState(npc, state);
        boolean timeRestored = level.getDayTime() == state.getLong("DayTime");
        boolean weatherRestored = weatherMatches(level, state);
        boolean respawnRestored = respawnMatches(player, state);
        boolean blocksRestored = conflicts == 0
            && allAir(level, state.getLongArray("DynamicBlocks"))
            && allAir(level, state.getLongArray("FixtureBlocks"));
        boolean entitiesRestored = level.getEntitiesOfClass(
            Entity.class,
            fixtureBounds(state).inflate(8.0D),
            entity -> entity.getTags().contains(SHEEP_TAG) || entity.getTags().contains(ITEM_TAG)
        ).isEmpty();
        boolean complete = playerRestored && npcRestored && timeRestored && weatherRestored
            && respawnRestored && blocksRestored && entitiesRestored;
        String evidence = "bed-sleep:cleanup|r="
            + bit(playerRestored) + "," + bit(npcRestored) + "," + bit(timeRestored) + ","
            + bit(weatherRestored) + "," + bit(respawnRestored) + "," + bit(blocksRestored) + ","
            + bit(entitiesRestored);
        if (complete) marker.discard();
        if (report) npc.setNextLiveFixtureAckStatus(evidence);
        if (!complete && !report) {
            throw new IllegalStateException("Bed sleep fixture cleanup restoration failed");
        }
    }

    static String setupRefusalReason(
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
        if (!alive) return "Bed sleep fixture requires living owner and NPC actors";
        if (downed) return "Bed sleep fixture requires a recovered NPC";
        if (activeTaskId != null && !activeTaskId.isBlank()) return "Bed sleep fixture requires no active task";
        if (pausedTaskCount > 0) return "Bed sleep fixture requires no paused task";
        if (!"idle".equals(schedulerLifecycle)) return "Bed sleep fixture requires an idle scheduler";
        if (managedEating) return "Bed sleep fixture requires NPC eating to finish";
        if (actorSleeping) return "Bed sleep fixture requires awake owner and NPC actors";
        if (!sameDimension) return "Bed sleep fixture requires the owner and NPC in the same dimension";
        if (!naturalDimension) return "Bed sleep fixture requires a natural sleeping dimension";
        if (creativeResources) return "Bed sleep fixture requires survival material mode";
        if (mounted) return "Bed sleep fixture requires dismounted owner and NPC actors";
        return "";
    }

    static String cleanupDimensionRefusalReason(String fixtureDimension, String playerDimension, String npcDimension) {
        if (fixtureDimension == null || fixtureDimension.isBlank()) {
            return "Bed sleep fixture dimension snapshot is missing";
        }
        if (!fixtureDimension.equals(playerDimension) || !fixtureDimension.equals(npcDimension)) {
            return "Bed sleep fixture cleanup requires the owner and NPC in the fixture dimension";
        }
        return "";
    }

    static String failureCode(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("active task") || message.contains("paused task")
            || message.contains("idle scheduler") || message.contains("eating")) return "npc-not-idle";
        if (message.contains("same dimension") || message.contains("fixture dimension")) return "dimension-mismatch";
        if (message.contains("survival material mode")) return "survival-required";
        if (message.contains("natural sleeping dimension")) return "natural-dimension-required";
        if (message.contains("marker")) return "fixture-state-missing";
        if (message.contains("cleanup restoration")) return "cleanup-incomplete";
        return "fixture-failed";
    }

    private static void requireSafeSetup(ServerPlayer player, CodexNpcEntity npc) {
        String refusal = setupRefusalReason(
            player.isAlive() && npc.isAlive(),
            npc.isDowned(),
            npc.tasks().activeTaskId(),
            npc.tasks().pausedTaskCount(),
            npc.tasks().schedulerLifecycle(),
            npc.isManagedEating(),
            player.isSleeping() || npc.isSleeping(),
            player.level() == npc.level(),
            player.level().dimensionType().natural(),
            npc.creativeResources(),
            player.isPassenger() || npc.isPassenger()
                || !player.getPassengers().isEmpty() || !npc.getPassengers().isEmpty()
        );
        if (!refusal.isEmpty()) throw new IllegalStateException(refusal);
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Bed sleep fixture requires no active or paused task during cleanup");
        }
    }

    private static ServerLevel requireFixtureLevel(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        String refusal = cleanupDimensionRefusalReason(
            state.getString("FixtureDimension"),
            player.level().dimension().location().toString(),
            npc.level().dimension().location().toString()
        );
        if (!refusal.isEmpty()) throw new IllegalStateException(refusal);
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("FixtureDimension"))
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Bed sleep fixture dimension is unavailable");
        return level;
    }

    private static void saveActorState(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        state.put(SAVED_PLAYER_KEY, player.saveWithoutId(new CompoundTag()));
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        state.putDouble("PlayerX", player.getX());
        state.putDouble("PlayerY", player.getY());
        state.putDouble("PlayerZ", player.getZ());
        state.putFloat("PlayerYaw", player.getYRot());
        state.putFloat("PlayerPitch", player.getXRot());
    }

    private static boolean restorePlayerState(ServerPlayer player, CompoundTag state) {
        if (!state.contains(SAVED_PLAYER_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag saved = state.getCompound(SAVED_PLAYER_KEY);
        player.load(saved.copy());
        player.connection.teleport(
            state.getDouble("PlayerX"),
            state.getDouble("PlayerY"),
            state.getDouble("PlayerZ"),
            state.getFloat("PlayerYaw"),
            state.getFloat("PlayerPitch")
        );
        player.containerMenu.broadcastChanges();
        player.onUpdateAbilities();
        return player.saveWithoutId(new CompoundTag()).equals(saved);
    }

    private static boolean restoreNpcState(CodexNpcEntity npc, CompoundTag state) {
        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag saved = state.getCompound(SAVED_NPC_KEY);
        npc.load(saved.copy());
        npc.getNavigation().stop();
        return npc.saveWithoutId(new CompoundTag()).equals(saved);
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

    private static boolean respawnMatches(ServerPlayer player, CompoundTag state) {
        if (!state.getBoolean("HadRespawn")) return player.getRespawnPosition() == null;
        return player.getRespawnPosition() != null
            && player.getRespawnPosition().equals(BlockPos.of(state.getLong("RespawnPosition")))
            && player.getRespawnDimension().location().toString().equals(state.getString("RespawnDimension"))
            && Float.compare(player.getRespawnAngle(), state.getFloat("RespawnAngle")) == 0
            && player.isRespawnForced() == state.getBoolean("RespawnForced");
    }

    private static void saveWorldState(ServerLevel level, CompoundTag state) {
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        state.putLong("DayTime", level.getDayTime());
        state.putInt("ClearWeatherTime", data.getClearWeatherTime());
        state.putInt("RainTime", data.getRainTime());
        state.putBoolean("Raining", data.isRaining());
        state.putInt("ThunderTime", data.getThunderTime());
        state.putBoolean("Thundering", data.isThundering());
    }

    private static void restoreWorldState(ServerLevel level, CompoundTag state) {
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        level.setDayTime(state.getLong("DayTime"));
        data.setClearWeatherTime(state.getInt("ClearWeatherTime"));
        data.setRainTime(state.getInt("RainTime"));
        data.setRaining(state.getBoolean("Raining"));
        data.setThunderTime(state.getInt("ThunderTime"));
        data.setThundering(state.getBoolean("Thundering"));
    }

    private static boolean weatherMatches(ServerLevel level, CompoundTag state) {
        ServerLevelData data = (ServerLevelData) level.getLevelData();
        return data.getClearWeatherTime() == state.getInt("ClearWeatherTime")
            && data.getRainTime() == state.getInt("RainTime")
            && data.isRaining() == state.getBoolean("Raining")
            && data.getThunderTime() == state.getInt("ThunderTime")
            && data.isThundering() == state.getBoolean("Thundering");
    }

    private static boolean verifyRecipes(ServerLevel level) {
        return hasCraftingOutput(level, Items.SHEARS)
            && hasCraftingOutput(level, Items.CRAFTING_TABLE)
            && hasCraftingOutput(level, Items.WHITE_BED)
            && hasCraftingTransition(level, Items.OAK_LOG, Items.OAK_PLANKS);
    }

    private static boolean hasCraftingOutput(ServerLevel level, Item output) {
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            if (recipe.getType() == RecipeType.CRAFTING
                && recipe.getResultItem(level.registryAccess()).is(output)) return true;
        }
        return false;
    }

    private static boolean hasCraftingTransition(ServerLevel level, Item input, Item output) {
        ItemStack probe = new ItemStack(input);
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            if (recipe.getType() == RecipeType.CRAFTING
                && recipe.getResultItem(level.registryAccess()).is(output)
                && recipe.getIngredients().stream().anyMatch(ingredient -> ingredient.test(probe))) return true;
        }
        return false;
    }

    private static void spawnSheep(ServerLevel level, BlockPos position) {
        Sheep sheep = EntityType.SHEEP.create(level);
        if (sheep == null) throw new IllegalStateException("Bed sleep fixture sheep could not be created");
        sheep.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0.0F, 0.0F);
        sheep.setColor(DyeColor.WHITE);
        sheep.setAge(0);
        sheep.setSheared(false);
        sheep.setNoAi(true);
        sheep.setPersistenceRequired();
        sheep.addTag(SHEEP_TAG);
        if (!level.addFreshEntity(sheep)) throw new IllegalStateException("Bed sleep fixture sheep was rejected");
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
                        for (int dy = -1; dy <= 8; dy++) {
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
                    origin.getY() + 9,
                    origin.getZ() + PLATFORM_RADIUS + 1
                );
                if (level.getEntities(null, bounds).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated bed sleep fixture site was found");
    }

    private static Set<BlockPos> canopy(BlockPos top) {
        LinkedHashSet<BlockPos> leaves = new LinkedHashSet<>();
        for (int y = -2; y <= 1; y++) {
            int radius = y <= 0 ? 2 : 1;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) leaves.add(top.offset(x, y, z));
            }
        }
        return leaves;
    }

    private static BlockPos findWhiteBedFoot(ServerLevel level, BlockPos home, int radius) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos position = home.offset(x, 0, z);
                if (!isWhiteBedPair(level, position)) continue;
                double distance = position.distSqr(home);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = position.immutable();
                }
            }
        }
        return best;
    }

    private static boolean isWhiteBedPair(ServerLevel level, BlockPos foot) {
        BlockState footState = level.getBlockState(foot);
        if (!footState.is(Blocks.WHITE_BED)
            || !footState.hasProperty(BedBlock.PART)
            || footState.getValue(BedBlock.PART) != BedPart.FOOT) return false;
        Direction facing = footState.getValue(BedBlock.FACING);
        BlockState headState = level.getBlockState(foot.relative(facing));
        return headState.is(Blocks.WHITE_BED)
            && headState.getValue(BedBlock.PART) == BedPart.HEAD
            && headState.getValue(BedBlock.FACING) == facing;
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Bed sleep fixture has not been set up");
        return marker;
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag data = npc.getPersistentData();
        if (data.hasUUID(MARKER_UUID_KEY) && data.contains(MARKER_DIMENSION_KEY, Tag.TAG_STRING)) {
            ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(data.getString(MARKER_DIMENSION_KEY))
            );
            ServerLevel level = player.getServer().getLevel(dimension);
            Entity entity = level == null ? null : level.getEntity(data.getUUID(MARKER_UUID_KEY));
            if (entity instanceof ArmorStand marker && marker.getTags().contains(MARKER_TAG)) return marker;
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

    private static ArmorStand markerNear(ServerLevel level, Vec3 position, double radius) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(position, position).inflate(radius),
            marker -> marker.getTags().contains(MARKER_TAG)
        ).stream().min(Comparator.comparingDouble(marker -> marker.distanceToSqr(position))).orElse(null);
    }

    private static CompoundTag fixtureState(ArmorStand marker) {
        return marker.getPersistentData().getCompound(STATE_KEY);
    }

    private static void rememberMarker(CodexNpcEntity npc, ArmorStand marker, ServerLevel level) {
        CompoundTag data = npc.getPersistentData();
        data.putUUID(MARKER_UUID_KEY, marker.getUUID());
        data.putString(MARKER_DIMENSION_KEY, level.dimension().location().toString());
    }

    private static boolean hasMarkerReference(CodexNpcEntity npc) {
        return npc.getPersistentData().hasUUID(MARKER_UUID_KEY);
    }

    private static AABB fixtureBounds(CompoundTag state) {
        BlockPos home = BlockPos.of(state.getLong("Home"));
        return new AABB(
            home.getX() - PLATFORM_RADIUS - 2,
            home.getY() - 2,
            home.getZ() - PLATFORM_RADIUS - 2,
            home.getX() + PLATFORM_RADIUS + 3,
            home.getY() + 10,
            home.getZ() + PLATFORM_RADIUS + 3
        );
    }

    private static boolean insideFixture(CompoundTag state, Vec3 position) {
        return fixtureBounds(state).inflate(2.0D).contains(position);
    }

    private static int restoreAir(ServerLevel level, long[] positions, boolean dynamic) {
        int conflicts = 0;
        for (long packed : positions) {
            BlockPos position = BlockPos.of(packed);
            BlockState current = level.getBlockState(position);
            if (current.isAir()) continue;
            boolean allowed = dynamic
                ? current.is(Blocks.CRAFTING_TABLE) || current.is(BlockTags.BEDS)
                : isFixtureBlock(current.getBlock());
            if (!allowed) {
                conflicts++;
                continue;
            }
            set(level, position, Blocks.AIR.defaultBlockState());
        }
        return conflicts;
    }

    private static boolean allAir(ServerLevel level, long[] positions) {
        for (long packed : positions) if (!level.getBlockState(BlockPos.of(packed)).isAir()) return false;
        return true;
    }

    private static boolean isFixtureBlock(Block block) {
        return Set.of(
            Blocks.SEA_LANTERN,
            Blocks.DIRT,
            Blocks.OAK_LOG,
            Blocks.OAK_LEAVES
        ).contains(block);
    }

    private static boolean isFixtureItem(Item item) {
        return Set.of(
            Items.IRON_INGOT,
            Items.OAK_LOG,
            Items.OAK_PLANKS,
            Items.OAK_SAPLING,
            Items.APPLE,
            Items.SHEARS,
            Items.WHITE_WOOL,
            Items.CRAFTING_TABLE,
            Items.WHITE_BED
        ).contains(item);
    }

    private static void clearNpcInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void insert(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = npc.insert(stack.copy());
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected bed sleep fixture items");
    }

    private static int countItem(CodexNpcEntity npc, Item item) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
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

    private static long[] longs(Iterable<BlockPos> values) {
        ArrayList<Long> packed = new ArrayList<>();
        for (BlockPos value : values) packed.add(value.asLong());
        long[] result = new long[packed.size()];
        for (int index = 0; index < packed.size(); index++) result[index] = packed.get(index);
        return result;
    }

    private static long[] reverse(long[] values) {
        long[] reversed = new long[values.length];
        for (int index = 0; index < values.length; index++) reversed[index] = values[values.length - index - 1];
        return reversed;
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state) {
        level.setBlock(position, state, Block.UPDATE_ALL);
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
