package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reversible proof that an unprivileged companion walks out, gathers, returns, and drops items. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class NoCheatExpeditionLiveFixture {
    static final int EXPECTED_LOGS = 4;
    static final int MIN_EXCURSION_MILLI = 55_000;
    static final int MAX_WALK_STEP_MILLI = 4_000;
    static final int MAX_RETURN_DISTANCE_MILLI = 3_200;
    static final int MAX_OWNER_DRIFT_MILLI = 1_500;

    private static final String SUITE = "no-cheat-expedition";
    private static final String MARKER_TAG = "CodexAcceptanceNoCheatExpeditionMarker";
    private static final String ITEM_TAG = "CodexAcceptanceNoCheatExpeditionItem";
    private static final String STATE_KEY = "CodexAcceptanceNoCheatExpeditionState";
    private static final String SAVED_PLAYER_KEY = "SavedPlayer";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final String MARKER_UUID_KEY = "CodexAcceptanceNoCheatExpeditionMarkerUuid";
    private static final String MARKER_DIMENSION_KEY = "CodexAcceptanceNoCheatExpeditionMarkerDimension";
    private static final double FIRST_SEARCH_DISTANCE = 72.0D;
    private static final int TREE_FORWARD_OFFSET = 8;
    private static final int PLATFORM_CLEARANCE = 32;
    private static final int SEARCH_RADIUS = 512;
    private static final int EVENT_MARKER_RADIUS = 128;
    private static final int[] SITE_OFFSETS = { 96, -96, 128, -128, 160, -160, 192, -192 };

    private NoCheatExpeditionLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("No-cheat expedition fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "inspect" -> inspect(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown no-cheat expedition fixture mode");
        }
    }

    @SubscribeEvent
    public static void recordBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
            || !(event.getLevel() instanceof ServerLevel level)
            || !(event.getPlayer() instanceof FakePlayer)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), EVENT_MARKER_RADIUS);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!contains(state.getLongArray("TreeLogs"), event.getPos())) return;

        Entity candidate = state.hasUUID("NpcUuid") ? level.getEntity(state.getUUID("NpcUuid")) : null;
        if (!(candidate instanceof CodexNpcEntity npc)
            || npc.position().distanceToSqr(event.getPlayer().position()) > 1.0E-6D) {
            state.putInt("BreakSyncErrors", state.getInt("BreakSyncErrors") + 1);
        }
        if (appendUnique(state, "BrokenLogs", event.getPos())) {
            state.putInt("LogBreaks", state.getInt("LogBreaks") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordItemJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        ArmorStand marker = markerNear(level, item.position(), EVENT_MARKER_RADIUS);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!insideFixture(state, item.position()) || !isFixtureItem(item.getItem().getItem())) return;
        item.addTag(ITEM_TAG);

        CompoundTag delivery = item.getPersistentData();
        if (item.getItem().is(Items.OAK_LOG)
            && state.hasUUID("OwnerUuid")
            && delivery.hasUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
            && delivery.getUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG).equals(state.getUUID("OwnerUuid"))) {
            state.putInt("DeliverySpawns", state.getInt("DeliverySpawns") + 1);
            state.putInt("DeliveryItems", state.getInt("DeliveryItems") + item.getItem().getCount());
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
        try {
            observe(marker, player, npc);
        } catch (RuntimeException error) {
            CompoundTag state = fixtureState(marker);
            state.putInt("ObservationErrors", state.getInt("ObservationErrors") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
        }
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        if (player.level() != npc.level()) {
            throw new IllegalStateException("No-cheat expedition fixture requires the owner and NPC in the same dimension");
        }
        cleanup(player, npc, false);
        requireSafeSetup(player, npc);

        ServerLevel level = player.serverLevel();
        Site site = findSite(level, npc.blockPosition());
        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("No-cheat expedition fixture marker could not be created");
        marker.moveTo(site.origin().getX() + 0.5D, site.origin().getY() + 8.0D,
            site.origin().getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 1);
        state.putString("FixtureDimension", level.dimension().location().toString());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putUUID("NpcUuid", npc.getUUID());
        state.putLong("Origin", site.origin().asLong());
        state.putLong("SearchCenter", site.searchCenter().asLong());
        state.putLong("TreeRoot", site.treeRoot().asLong());
        state.putLongArray("FixtureBlocks", longs(site.fixtureBlocks()));
        state.putLongArray("TreeLogs", longs(site.logs()));
        state.putLongArray("BrokenLogs", new long[0]);
        state.putInt("MinX", site.bounds().minX());
        state.putInt("MinY", site.bounds().minY());
        state.putInt("MinZ", site.bounds().minZ());
        state.putInt("MaxX", site.bounds().maxX());
        state.putInt("MaxY", site.bounds().maxY());
        state.putInt("MaxZ", site.bounds().maxZ());
        state.putBoolean("TaskIdStable", true);
        saveRespawn(player, state);
        saveActorState(player, npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            throw new IllegalStateException("No-cheat expedition fixture marker was rejected");
        }
        rememberMarker(npc, marker, level);

        try {
            for (BlockPos floor : site.floor()) set(level, floor, Blocks.SEA_LANTERN.defaultBlockState());
            set(level, site.treeRoot().below(), Blocks.DIRT.defaultBlockState());
            for (BlockPos log : site.logs()) set(level, log, Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
            for (BlockPos position : site.leaves()) set(level, position, leaf);

            clearNpcInventory(npc);
            insert(npc, new ItemStack(Items.DIAMOND_AXE));
            player.getInventory().clearContent();
            player.containerMenu.broadcastChanges();

            npc.cancelManagedEating();
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            moveNpc(npc, site.origin());
            npc.tasks().stay();

            BlockPos playerStart = site.origin().offset(-3, 0, 0);
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
            player.setRespawnPosition(level.dimension(), site.origin(), 0.0F, true, false);

            state.putLong("PlayerFixtureStart", playerStart.asLong());
            state.putDouble("NpcStartX", npc.getX());
            state.putDouble("NpcStartY", npc.getY());
            state.putDouble("NpcStartZ", npc.getZ());
            state.putDouble("LastNpcX", npc.getX());
            state.putDouble("LastNpcY", npc.getY());
            state.putDouble("LastNpcZ", npc.getZ());
            marker.getPersistentData().put(STATE_KEY, state);
            npc.setNextLiveFixtureAckStatus("no-cheat-expedition:setup|c=0,o="
                + site.origin().getX() + "," + site.origin().getY() + "," + site.origin().getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        observe(marker, player, npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = requireFixtureLevel(player, npc, state);
        int playerLogs = countPlayerItem(player, Items.OAK_LOG);
        int npcLogs = countNpcItem(npc, Items.OAK_LOG);
        int worldLogs = countWorldItem(level, state, Items.OAK_LOG);
        int remainingFixtureLogs = countRemainingFixtureLogs(level, state);
        NpcTaskEngine.GatherDiagnostics gather = npc.tasks().gatherDiagnosticsForFixture();
        int returnDistance = distanceMilli(npc.position(), player.position());
        boolean complete = acceptanceComplete(
            state.getBoolean("CheatsObserved"),
            state.getBoolean("CreativeObserved"),
            state.getBoolean("SawGather"),
            state.getBoolean("SawDeliver"),
            state.getBoolean("SawExcursion"),
            state.getInt("MaxDistanceMilli"),
            state.getInt("MaxStepMilli"),
            state.getInt("LogBreaks"),
            state.getInt("DeliveryItems"),
            playerLogs,
            npcLogs,
            worldLogs,
            returnDistance,
            state.getInt("MaxOwnerDriftMilli"),
            state.getBoolean("TaskIdStable"),
            state.getInt("ObservationErrors") + state.getInt("BreakSyncErrors")
        );
        String evidence = "no-cheat-expedition:i|"
            + bit(complete) + "," + bit(state.getBoolean("CheatsObserved")) + ","
            + bit(state.getBoolean("CreativeObserved")) + "," + bit(state.getBoolean("SawGather")) + ","
            + bit(state.getBoolean("SawDeliver")) + "," + bit(state.getBoolean("SawExcursion")) + ","
            + state.getInt("MaxDistanceMilli") + "," + state.getInt("MaxStepMilli") + ","
            + state.getInt("LogBreaks") + "," + state.getInt("DeliverySpawns") + ","
            + state.getInt("DeliveryItems") + "," + playerLogs + "," + npcLogs + "," + worldLogs + ","
            + returnDistance + "," + state.getInt("MaxOwnerDriftMilli") + ","
            + bit(state.getBoolean("TaskIdStable")) + ","
            + state.getInt("ObservationErrors") + "," + state.getInt("BreakSyncErrors") + ","
            + remainingFixtureLogs + "," + gather.queuedTargets() + ","
            + gather.skippedTargets() + "," + gather.excursions() + ","
            + bit(gather.treeCluster()) + "," + bit(gather.clusterReached()) + ","
            + bit(gather.targetSelected());
        npc.setNextLiveFixtureAckStatus(evidence);
    }

    private static void observe(ArmorStand marker, ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag state = fixtureState(marker);
        if (player.level() != marker.level() || npc.level() != marker.level()) {
            state.putInt("ObservationErrors", state.getInt("ObservationErrors") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
            return;
        }
        Vec3 current = npc.position();
        if (state.contains("LastNpcX", Tag.TAG_DOUBLE)) {
            int step = distanceMilli(current, new Vec3(
                state.getDouble("LastNpcX"), state.getDouble("LastNpcY"), state.getDouble("LastNpcZ")
            ));
            state.putInt("MaxStepMilli", Math.max(state.getInt("MaxStepMilli"), step));
        }
        state.putDouble("LastNpcX", current.x);
        state.putDouble("LastNpcY", current.y);
        state.putDouble("LastNpcZ", current.z);
        int distance = distanceMilli(current, new Vec3(
            state.getDouble("NpcStartX"), state.getDouble("NpcStartY"), state.getDouble("NpcStartZ")
        ));
        state.putInt("MaxDistanceMilli", Math.max(state.getInt("MaxDistanceMilli"), distance));
        if (state.contains("PlayerFixtureStart", Tag.TAG_LONG)) {
            int ownerDrift = distanceMilli(
                player.position(),
                Vec3.atBottomCenterOf(BlockPos.of(state.getLong("PlayerFixtureStart")))
            );
            state.putInt("MaxOwnerDriftMilli", Math.max(state.getInt("MaxOwnerDriftMilli"), ownerDrift));
        }
        if (distance >= MIN_EXCURSION_MILLI) state.putBoolean("SawExcursion", true);
        if (player.hasPermissions(2)) state.putBoolean("CheatsObserved", true);
        if (npc.creativeResources()) state.putBoolean("CreativeObserved", true);

        String kind = npc.tasks().activeTaskKind();
        String taskId = npc.tasks().activeTaskId();
        if (("gather".equals(kind) || "deliver".equals(kind))
            && taskId != null && !taskId.isBlank() && !taskId.startsWith("local:")) {
            String observed = state.getString("ObservedTaskId");
            if (observed.isBlank()) state.putString("ObservedTaskId", taskId);
            else if (!observed.equals(taskId)) state.putBoolean("TaskIdStable", false);
            if ("gather".equals(kind)) state.putBoolean("SawGather", true);
            else state.putBoolean("SawDeliver", true);
        }
        if (state.getBoolean("SawExcursion") && state.getBoolean("SawDeliver")
            && distanceMilli(npc.position(), player.position()) <= MAX_RETURN_DISTANCE_MILLI) {
            state.putBoolean("SawReturn", true);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (hasMarkerReference(npc)) {
                throw new IllegalStateException("No-cheat expedition fixture marker reference could not be resolved");
            }
            if (report) npc.setNextLiveFixtureAckStatus("no-cheat-expedition:cleanup|none");
            return;
        }
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = requireFixtureLevel(player, npc, state);
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(4.0D),
            entity -> entity.getTags().contains(ITEM_TAG)
        )) item.discard();

        int conflicts = restoreAir(level, reverse(state.getLongArray("FixtureBlocks")));
        restoreRespawn(player, state);
        boolean playerRestored = restorePlayerState(player, state);
        boolean npcRestored = restoreNpcState(npc, state);
        boolean respawnRestored = respawnMatches(player, state);
        boolean blocksRestored = conflicts == 0 && allAir(level, state.getLongArray("FixtureBlocks"));
        boolean itemsRestored = level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(4.0D),
            entity -> entity.getTags().contains(ITEM_TAG)
        ).isEmpty();
        boolean complete = playerRestored && npcRestored && respawnRestored && blocksRestored && itemsRestored;
        String evidence = "no-cheat-expedition:cleanup|r="
            + bit(playerRestored) + "," + bit(npcRestored) + "," + bit(respawnRestored) + ","
            + bit(blocksRestored) + "," + bit(itemsRestored);
        if (complete) marker.discard();
        if (report) npc.setNextLiveFixtureAckStatus(evidence);
        if (!complete && !report) {
            throw new IllegalStateException("No-cheat expedition fixture cleanup restoration failed");
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
        boolean hasCheats,
        boolean playerSurvival,
        boolean creativeResources,
        boolean mounted
    ) {
        if (!alive) return "No-cheat expedition fixture requires living owner and NPC actors";
        if (downed) return "No-cheat expedition fixture requires a recovered NPC";
        if (activeTaskId != null && !activeTaskId.isBlank()) {
            return "No-cheat expedition fixture requires no active task";
        }
        if (pausedTaskCount > 0) return "No-cheat expedition fixture requires no paused task";
        if (!"idle".equals(schedulerLifecycle)) return "No-cheat expedition fixture requires an idle scheduler";
        if (managedEating) return "No-cheat expedition fixture requires NPC eating to finish";
        if (actorSleeping) return "No-cheat expedition fixture requires awake owner and NPC actors";
        if (!sameDimension) return "No-cheat expedition fixture requires the owner and NPC in the same dimension";
        if (!naturalDimension) return "No-cheat expedition fixture requires a natural dimension";
        if (hasCheats) return "No-cheat expedition fixture requires cheats to be disabled";
        if (!playerSurvival) return "No-cheat expedition fixture requires player survival mode";
        if (creativeResources) return "No-cheat expedition fixture requires survival material mode";
        if (mounted) return "No-cheat expedition fixture requires dismounted owner and NPC actors";
        return "";
    }

    static String cleanupDimensionRefusalReason(String fixtureDimension, String playerDimension, String npcDimension) {
        if (fixtureDimension == null || fixtureDimension.isBlank()) {
            return "No-cheat expedition fixture dimension snapshot is missing";
        }
        if (!fixtureDimension.equals(playerDimension) || !fixtureDimension.equals(npcDimension)) {
            return "No-cheat expedition fixture cleanup requires the owner and NPC in the fixture dimension";
        }
        return "";
    }

    static String failureCode(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("active task") || message.contains("paused task")
            || message.contains("idle scheduler") || message.contains("eating")) return "npc-not-idle";
        if (message.contains("same dimension") || message.contains("fixture dimension")) return "dimension-mismatch";
        if (message.contains("cheats to be disabled")) return "cheats-enabled";
        if (message.contains("player survival mode") || message.contains("survival material mode")) {
            return "survival-required";
        }
        if (message.contains("natural dimension")) return "natural-dimension-required";
        if (message.contains("living owner and NPC") || message.contains("recovered NPC")) {
            return "actor-not-ready";
        }
        if (message.contains("awake owner and NPC")) return "actors-sleeping";
        if (message.contains("dismounted owner and NPC")) return "passenger-active";
        if (message.contains("No isolated no-cheat expedition fixture site")) return "site-unavailable";
        if (message.contains("fixture block could not be changed")) return "block-change-rejected";
        if (message.contains("inventory rejected expedition fixture items")) return "inventory-rejected";
        if (message.contains("marker")) return "fixture-state-missing";
        if (message.contains("cleanup restoration")) return "cleanup-incomplete";
        return "fixture-failed";
    }

    static BlockPos firstSearchCenter(BlockPos origin) {
        double angle = Math.PI * (3.0D - Math.sqrt(5.0D));
        int targetX = (int) Math.floor(origin.getX() + 0.5D + Math.cos(angle) * FIRST_SEARCH_DISTANCE);
        int targetZ = (int) Math.floor(origin.getZ() + 0.5D + Math.sin(angle) * FIRST_SEARCH_DISTANCE);
        return new BlockPos(targetX, origin.getY(), targetZ);
    }

    static BlockPos remoteTreeRoot(BlockPos origin, BlockPos searchCenter) {
        int dx = Integer.compare(searchCenter.getX(), origin.getX());
        int dz = Integer.compare(searchCenter.getZ(), origin.getZ());
        return searchCenter.offset(dx * TREE_FORWARD_OFFSET, 0, dz * TREE_FORWARD_OFFSET);
    }

    static boolean acceptanceComplete(
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
        return !cheatsObserved && !creativeObserved && sawGather && sawDeliver && sawExcursion
            && maxDistanceMilli >= MIN_EXCURSION_MILLI
            && maxStepMilli <= MAX_WALK_STEP_MILLI
            && breaks == EXPECTED_LOGS
            && deliveryItems == EXPECTED_LOGS
            && playerLogs == EXPECTED_LOGS
            && npcLogs == 0 && worldLogs == 0
            && returnDistanceMilli <= MAX_RETURN_DISTANCE_MILLI
            && ownerDriftMilli <= MAX_OWNER_DRIFT_MILLI
            && taskIdStable && errors == 0;
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
            player.hasPermissions(2),
            player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
            npc.creativeResources(),
            player.isPassenger() || npc.isPassenger()
                || !player.getPassengers().isEmpty() || !npc.getPassengers().isEmpty()
        );
        if (!refusal.isEmpty()) throw new IllegalStateException(refusal);
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("No-cheat expedition fixture requires no active or paused task during cleanup");
        }
    }

    private static Site findSite(ServerLevel level, BlockPos near) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                BlockPos horizontalOrigin = new BlockPos(near.getX() + dx, 0, near.getZ() + dz);
                BlockPos horizontalSearch = firstSearchCenter(horizontalOrigin);
                BlockPos horizontalTree = remoteTreeRoot(horizontalOrigin, horizontalSearch);
                Set<BlockPos> columns = corridorColumns(horizontalOrigin, horizontalTree);
                int surface = level.getMinBuildHeight();
                boolean insideBorder = true;
                for (BlockPos column : columns) {
                    if (!level.getWorldBorder().isWithinBounds(new BlockPos(column.getX(), near.getY(), column.getZ()))) {
                        insideBorder = false;
                        break;
                    }
                    surface = Math.max(surface, level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()
                    ));
                }
                if (!insideBorder) continue;
                int y = surface + PLATFORM_CLEARANCE;
                if (y + 10 >= level.getMaxBuildHeight()) continue;
                BlockPos origin = new BlockPos(horizontalOrigin.getX(), y, horizontalOrigin.getZ());
                Site site = siteGeometry(origin);
                boolean clear = true;
                for (BlockPos position : site.fixtureBlocks()) {
                    level.getChunkAt(position);
                    if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                        clear = false;
                        break;
                    }
                }
                if (!clear || !level.getEntities(null, site.bounds().aabb().inflate(1.0D)).isEmpty()) continue;
                return site;
            }
        }
        throw new IllegalStateException("No isolated no-cheat expedition fixture site was found");
    }

    private static Site siteGeometry(BlockPos origin) {
        BlockPos searchCenter = firstSearchCenter(origin);
        BlockPos treeRoot = remoteTreeRoot(origin, searchCenter);
        LinkedHashSet<BlockPos> columns = new LinkedHashSet<>(corridorColumns(origin, treeRoot));
        LinkedHashSet<BlockPos> floor = new LinkedHashSet<>();
        for (BlockPos column : columns) floor.add(new BlockPos(column.getX(), origin.getY() - 1, column.getZ()));

        List<BlockPos> logs = new ArrayList<>();
        for (int height = 0; height < EXPECTED_LOGS; height++) logs.add(treeRoot.above(height));
        LinkedHashSet<BlockPos> leaves = canopy(logs.get(logs.size() - 1));
        leaves.removeAll(logs);

        LinkedHashSet<BlockPos> fixtureBlocks = new LinkedHashSet<>(floor);
        fixtureBlocks.addAll(logs);
        fixtureBlocks.addAll(leaves);
        Bounds bounds = Bounds.of(fixtureBlocks);
        return new Site(origin, searchCenter, treeRoot, Set.copyOf(floor), List.copyOf(logs),
            Set.copyOf(leaves), Set.copyOf(fixtureBlocks), bounds);
    }

    private static Set<BlockPos> corridorColumns(BlockPos origin, BlockPos target) {
        LinkedHashSet<BlockPos> columns = new LinkedHashSet<>();
        addLine(columns, origin, target, 2);
        addPad(columns, origin, 5);
        addPad(columns, firstSearchCenter(origin), 4);
        addPad(columns, target, 4);
        return columns;
    }

    private static void addLine(Set<BlockPos> output, BlockPos start, BlockPos end, int radius) {
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int step = 0; step <= steps; step++) {
            double ratio = steps == 0 ? 0.0D : step / (double) steps;
            int x = (int) Math.round(start.getX() + dx * ratio);
            int z = (int) Math.round(start.getZ() + dz * ratio);
            for (int ox = -radius; ox <= radius; ox++) {
                for (int oz = -radius; oz <= radius; oz++) output.add(new BlockPos(x + ox, 0, z + oz));
            }
        }
    }

    private static void addPad(Set<BlockPos> output, BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) output.add(new BlockPos(center.getX() + x, 0, center.getZ() + z));
        }
    }

    private static LinkedHashSet<BlockPos> canopy(BlockPos top) {
        LinkedHashSet<BlockPos> leaves = new LinkedHashSet<>();
        for (int y = -1; y <= 1; y++) {
            int radius = y <= 0 ? 2 : 1;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) leaves.add(top.offset(x, y, z));
            }
        }
        return leaves;
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
        if (level == null) throw new IllegalStateException("No-cheat expedition fixture dimension is unavailable");
        return level;
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("No-cheat expedition fixture has not been set up");
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

    private static void saveActorState(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        state.put(SAVED_PLAYER_KEY, player.saveWithoutId(new CompoundTag()));
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        state.putDouble("PlayerX", player.getX());
        state.putDouble("PlayerY", player.getY());
        state.putDouble("PlayerZ", player.getZ());
        state.putFloat("PlayerYaw", player.getYRot());
        state.putFloat("PlayerPitch", player.getXRot());
        state.putDouble("NpcX", npc.getX());
        state.putDouble("NpcY", npc.getY());
        state.putDouble("NpcZ", npc.getZ());
        state.putFloat("NpcYaw", npc.getYRot());
        state.putFloat("NpcPitch", npc.getXRot());
    }

    private static boolean restorePlayerState(ServerPlayer player, CompoundTag state) {
        if (!state.contains(SAVED_PLAYER_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag saved = state.getCompound(SAVED_PLAYER_KEY);
        player.load(saved.copy());
        player.connection.teleport(
            state.getDouble("PlayerX"), state.getDouble("PlayerY"), state.getDouble("PlayerZ"),
            state.getFloat("PlayerYaw"), state.getFloat("PlayerPitch")
        );
        player.containerMenu.broadcastChanges();
        player.onUpdateAbilities();
        CompoundTag current = player.saveWithoutId(new CompoundTag());
        return close(player.getX(), state.getDouble("PlayerX"))
            && close(player.getY(), state.getDouble("PlayerY"))
            && close(player.getZ(), state.getDouble("PlayerZ"))
            && Float.compare(player.getYRot(), state.getFloat("PlayerYaw")) == 0
            && Float.compare(player.getXRot(), state.getFloat("PlayerPitch")) == 0
            && stableFieldsMatch(saved, current,
                "Inventory", "SelectedItemSlot", "Health", "AbsorptionAmount",
                "foodLevel", "foodSaturationLevel", "foodExhaustionLevel",
                "abilities", "ActiveEffects", "XpP", "XpLevel", "XpTotal",
                "Score", "EnderItems", "playerGameType", "previousPlayerGameType");
    }

    private static boolean restoreNpcState(CodexNpcEntity npc, CompoundTag state) {
        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag saved = state.getCompound(SAVED_NPC_KEY);
        npc.load(saved.copy());
        npc.getNavigation().stop();
        npc.teleportTo(state.getDouble("NpcX"), state.getDouble("NpcY"), state.getDouble("NpcZ"));
        npc.setYRot(state.getFloat("NpcYaw"));
        npc.setXRot(state.getFloat("NpcPitch"));
        CompoundTag current = npc.saveWithoutId(new CompoundTag());
        return close(npc.getX(), state.getDouble("NpcX"))
            && close(npc.getY(), state.getDouble("NpcY"))
            && close(npc.getZ(), state.getDouble("NpcZ"))
            && Float.compare(npc.getYRot(), state.getFloat("NpcYaw")) == 0
            && Float.compare(npc.getXRot(), state.getFloat("NpcPitch")) == 0
            && stableFieldsMatch(saved, current,
                "Health", "AbsorptionAmount", "ActiveEffects", "CodexOwner",
                "CodexStance", "CodexDowned", "CodexRecoveryTicks", "CodexFood",
                "CodexSaturation", "CodexExhaustion", "CodexStatus", "CodexInventory",
                "CodexTaskSchedulerV2", "CodexBoundDragon", "CodexBoundDragonDimension",
                "CodexBoundDragonPosition");
    }

    static boolean stableFieldsMatch(CompoundTag expected, CompoundTag actual, String... keys) {
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

    private static AABB fixtureBounds(CompoundTag state) {
        return new AABB(
            state.getInt("MinX"), state.getInt("MinY"), state.getInt("MinZ"),
            state.getInt("MaxX") + 1.0D, state.getInt("MaxY") + 1.0D, state.getInt("MaxZ") + 1.0D
        );
    }

    private static boolean insideFixture(CompoundTag state, Vec3 position) {
        return fixtureBounds(state).inflate(4.0D).contains(position);
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
            set(level, position, Blocks.AIR.defaultBlockState());
        }
        return conflicts;
    }

    private static boolean allAir(ServerLevel level, long[] positions) {
        for (long packed : positions) if (!level.getBlockState(BlockPos.of(packed)).isAir()) return false;
        return true;
    }

    private static boolean isFixtureBlock(Block block) {
        return Set.of(Blocks.SEA_LANTERN, Blocks.DIRT, Blocks.OAK_LOG, Blocks.OAK_LEAVES).contains(block);
    }

    private static boolean isFixtureItem(Item item) {
        return Set.of(Items.OAK_LOG, Items.OAK_SAPLING, Items.APPLE, Items.STICK).contains(item);
    }

    private static void clearNpcInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void insert(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = npc.insert(stack.copy());
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected expedition fixture items");
    }

    private static int countNpcItem(CodexNpcEntity npc, Item item) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countPlayerItem(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int countWorldItem(ServerLevel level, CompoundTag state, Item item) {
        int count = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(4.0D),
            candidate -> candidate.getTags().contains(ITEM_TAG) && candidate.getItem().is(item)
        )) count += entity.getItem().getCount();
        return count;
    }

    private static int countRemainingFixtureLogs(ServerLevel level, CompoundTag state) {
        int count = 0;
        for (long packed : state.getLongArray("TreeLogs")) {
            if (level.getBlockState(BlockPos.of(packed)).is(Blocks.OAK_LOG)) count++;
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
            throw new IllegalStateException("No-cheat expedition fixture block could not be changed at "
                + position.toShortString());
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

    private static long[] longs(Iterable<BlockPos> values) {
        ArrayList<Long> packed = new ArrayList<>();
        for (BlockPos value : values) packed.add(value.asLong());
        long[] result = new long[packed.size()];
        for (int index = 0; index < packed.size(); index++) result[index] = packed.get(index);
        return result;
    }

    private static long[] reverse(long[] values) {
        long[] result = new long[values.length];
        for (int index = 0; index < values.length; index++) result[index] = values[values.length - 1 - index];
        return result;
    }

    private static int distanceMilli(Vec3 left, Vec3 right) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(left.distanceTo(right) * 1_000.0D));
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private record Site(
        BlockPos origin,
        BlockPos searchCenter,
        BlockPos treeRoot,
        Set<BlockPos> floor,
        List<BlockPos> logs,
        Set<BlockPos> leaves,
        Set<BlockPos> fixtureBlocks,
        Bounds bounds
    ) {
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        static Bounds of(Iterable<BlockPos> positions) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos position : positions) {
                minX = Math.min(minX, position.getX());
                minY = Math.min(minY, position.getY());
                minZ = Math.min(minZ, position.getZ());
                maxX = Math.max(maxX, position.getX());
                maxY = Math.max(maxY, position.getY());
                maxZ = Math.max(maxZ, position.getZ());
            }
            if (minX == Integer.MAX_VALUE) throw new IllegalArgumentException("Fixture bounds require positions");
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        AABB aabb() {
            return new AABB(minX, minY, minZ, maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D);
        }
    }
}
