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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reversible underground fixture for the complete survival deep-mining chain. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class DeepMiningLiveFixture {
    private static final String MARKER_TAG = "CodexAcceptanceDeepMiningMarker";
    private static final String ITEM_TAG = "CodexAcceptanceDeepMiningItem";
    private static final String STATE_KEY = "CodexAcceptanceDeepMiningState";
    private static final String MARKER_UUID_KEY = "CodexAcceptanceDeepMiningMarkerUuid";
    private static final String MARKER_DIMENSION_KEY = "CodexAcceptanceDeepMiningMarkerDimension";
    private static final String SAVED_PLAYER_KEY = "SavedPlayer";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final int ENTRY_Y = -54;
    private static final int STAIR_STEPS = 4;
    private static final int ORE_START = 10;
    private static final int ORE_COUNT = 3;
    private static final int[] SITE_OFFSETS = {96, -96, 128, -128, 160, -160, 192, -192};
    private static final Set<String> GRAVITY_BLOCK_IDS = Set.of(
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:gravel",
        "minecraft:suspicious_sand",
        "minecraft:suspicious_gravel"
    );

    private DeepMiningLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Deep mining fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "inspect" -> inspect(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown deep mining fixture mode");
        }
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
        try {
            observe(marker, player, npc);
        } catch (RuntimeException error) {
            CompoundTag state = fixtureState(marker);
            state.putInt("ObservationErrors", state.getInt("ObservationErrors") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
        }
    }

    @SubscribeEvent
    public static void recordItemJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)) return;
        ArmorStand marker = markerNear(level, item.position(), 64.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!fixtureBounds(state).inflate(8.0D).contains(item.position())) return;
        item.addTag(ITEM_TAG);
        ResourceLocation joinedItemKey = ForgeRegistries.ITEMS.getKey(item.getItem().getItem());
        String joinedItemId = joinedItemKey == null ? "" : joinedItemKey.toString();
        if (state.hasUUID("NpcUuid")
            && item.getPersistentData().hasUUID(CodexNpcEntity.DISCARDED_BY_TAG)
            && MiningInventoryCleanupPolicy.isDiscardedBy(
                state.getUUID("NpcUuid"),
                item.getPersistentData().getUUID(CodexNpcEntity.DISCARDED_BY_TAG)
            )
            && MiningInventoryCleanupPolicy.isDiscardableStone(joinedItemId)) {
            state.putInt("DiscardedStoneStacks", state.getInt("DiscardedStoneStacks") + 1);
            state.putInt("DiscardedStoneItems",
                state.getInt("DiscardedStoneItems") + item.getItem().getCount());
            marker.getPersistentData().put(STATE_KEY, state);
        }
        if (item.getItem().is(Items.DIAMOND_PICKAXE)
            && state.hasUUID("OwnerUuid")
            && item.getPersistentData().hasUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
            && item.getPersistentData().getUUID(CodexNpcEntity.DELIVERY_RECIPIENT_TAG)
                .equals(state.getUUID("OwnerUuid"))) {
            state.putBoolean("DeliverySeen", true);
            marker.getPersistentData().put(STATE_KEY, state);
        }
    }

    @SubscribeEvent
    public static void recordItemPickup(EntityItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
            || !(player.level() instanceof ServerLevel level)
            || !event.getItem().getItem().is(Items.DIAMOND_PICKAXE)) return;
        ArmorStand marker = markerNear(level, event.getItem().position(), 64.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        if (!state.hasUUID("OwnerUuid") || !state.getUUID("OwnerUuid").equals(player.getUUID())) return;
        state.putBoolean("DeliverySeen", true);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        cleanup(player, npc, false);
        requireSafeSetup(player, npc);
        ServerLevel level = player.serverLevel();
        Site site = findSite(level, npc.blockPosition());

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Deep mining fixture marker could not be created");
        marker.moveTo(site.origin().getX() + 0.5D, site.origin().getY() + 5.0D,
            site.origin().getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 2);
        state.putString("FixtureDimension", level.dimension().location().toString());
        state.putUUID("OwnerUuid", player.getUUID());
        state.putUUID("NpcUuid", npc.getUUID());
        state.putLong("Origin", site.origin().asLong());
        state.putLongArray("FixtureBlocks", packed(site.desired().keySet()));
        state.putIntArray("OriginalStates", blockStateIds(level, site.desired().keySet()));
        state.putInt("MinX", site.bounds().minX());
        state.putInt("MinY", site.bounds().minY());
        state.putInt("MinZ", site.bounds().minZ());
        state.putInt("MaxX", site.bounds().maxX());
        state.putInt("MaxY", site.bounds().maxY());
        state.putInt("MaxZ", site.bounds().maxZ());
        state.putBoolean("TaskIdStable", true);
        saveActorState(player, npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            throw new IllegalStateException("Deep mining fixture marker was rejected");
        }
        rememberMarker(npc, marker, level);

        try {
            for (Map.Entry<BlockPos, BlockState> entry : site.desired().entrySet()) {
                set(level, entry.getKey(), entry.getValue());
            }
            clearNpcInventory(npc);
            insert(npc, Items.OAK_LOG, 16);
            insert(npc, Items.COAL, 8);
            insert(npc, Items.IRON_INGOT, 6);
            insert(npc, Items.COBBLESTONE, 32);
            insert(npc, Items.COOKED_BEEF, 16);
            Item[] ballast = {
                Blocks.TUFF.asItem(),
                Blocks.GRANITE.asItem(),
                Blocks.DIORITE.asItem(),
                Blocks.ANDESITE.asItem(),
                Blocks.STONE.asItem(),
                Blocks.DEEPSLATE.asItem(),
                Blocks.COBBLED_DEEPSLATE.asItem()
            };
            for (int index = 0; index < 22; index++) {
                insert(npc, ballast[index % ballast.length], 64);
            }
            player.getInventory().clearContent();
            player.containerMenu.broadcastChanges();

            npc.cancelManagedEating();
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            npc.setNoGravity(false);
            npc.setYRot(0.0F);
            npc.setXRot(0.0F);
            moveNpc(npc, site.origin());
            npc.tasks().stay();

            BlockPos playerStand = site.origin().east(2);
            player.connection.teleport(
                playerStand.getX() + 0.5D,
                playerStand.getY(),
                playerStand.getZ() + 0.5D,
                180.0F,
                0.0F
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.setAirSupply(player.getMaxAirSupply());
            player.clearFire();
            player.getAbilities().invulnerable = true;
            player.onUpdateAbilities();

            npc.setNextLiveFixtureAckStatus("deep-mining:setup|o="
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
        boolean complete = acceptanceComplete(
            state.getInt("MaxLadders"),
            state.getInt("MaxTorches"),
            state.getInt("MaxUsableIronPickaxes"),
            state.getBoolean("SawDescending"),
            state.getBoolean("SawBranching"),
            state.getInt("MaxStaircaseStep"),
            state.getInt("MaxBranchProgress"),
            state.getInt("MaxPlacedTorches"),
            state.getInt("MaxBrokenBlocks"),
            state.getInt("MaxDiamonds"),
            state.getInt("MaxPlayerDiamondPickaxes"),
            state.getBoolean("DeliverySeen"),
            state.getBoolean("TaskIdStable"),
            state.getInt("DiscardedStoneStacks"),
            state.getInt("DiscardedStoneItems"),
            state.getBoolean("SawStoneDropLedger"),
            state.getInt("ObservationErrors")
        );
        String evidence = "deep-mining:i|ok=" + bit(complete)
            + ",l=" + state.getInt("MaxLadders")
            + ",t=" + state.getInt("MaxTorches")
            + ",p=" + state.getInt("MaxUsableIronPickaxes")
            + ",d=" + bit(state.getBoolean("SawDescending"))
            + ",b=" + bit(state.getBoolean("SawBranching"))
            + ",s=" + state.getInt("MaxStaircaseStep")
            + ",r=" + state.getInt("MaxBranchProgress")
            + ",x=" + state.getInt("MaxPlacedTorches")
            + ",k=" + state.getInt("MaxBrokenBlocks")
            + ",o=" + state.getInt("MaxDiamonds")
            + ",g=" + state.getInt("MaxPlayerDiamondPickaxes")
            + ",v=" + bit(state.getBoolean("DeliverySeen"))
            + ",w=" + state.getInt("DiscardedStoneStacks")
            + ",j=" + state.getInt("DiscardedStoneItems")
            + ",n=" + bit(state.getBoolean("SawStoneDropLedger"))
            + ",e=" + state.getInt("ObservationErrors");
        npc.setNextLiveFixtureAckStatus(evidence);
    }

    private static void observe(ArmorStand marker, ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag state = fixtureState(marker);
        if (player.level() != marker.level() || npc.level() != marker.level()) {
            state.putInt("ObservationErrors", state.getInt("ObservationErrors") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
            return;
        }
        state.putInt("MaxLadders", Math.max(state.getInt("MaxLadders"), countNpcItem(npc, Items.LADDER)));
        state.putInt("MaxTorches", Math.max(state.getInt("MaxTorches"), countNpcItem(npc, Items.TORCH)));
        state.putInt("MaxUsableIronPickaxes", Math.max(
            state.getInt("MaxUsableIronPickaxes"),
            usableIronPickaxes(npc)
        ));
        state.putInt("MaxDiamonds", Math.max(state.getInt("MaxDiamonds"), countNpcItem(npc, Items.DIAMOND)));
        state.putInt("MaxPlayerDiamondPickaxes", Math.max(
            state.getInt("MaxPlayerDiamondPickaxes"),
            countPlayerItem(player, Items.DIAMOND_PICKAXE)
        ));

        NpcTaskEngine.DeepMiningDiagnostics diagnostics = npc.tasks().deepMiningDiagnostics();
        if (!diagnostics.phase().isBlank()) {
            state.putBoolean("SawDescending", state.getBoolean("SawDescending")
                || diagnostics.phase().equals("descending"));
            state.putBoolean("SawBranching", state.getBoolean("SawBranching")
                || diagnostics.phase().equals("branching") || diagnostics.phase().equals("returning"));
            state.putInt("MaxStaircaseStep", Math.max(
                state.getInt("MaxStaircaseStep"), diagnostics.staircaseStep()
            ));
            state.putInt("MaxBranchProgress", Math.max(
                state.getInt("MaxBranchProgress"), diagnostics.branchProgress()
            ));
            state.putInt("MaxPlacedTorches", Math.max(
                state.getInt("MaxPlacedTorches"), diagnostics.placedTorches()
            ));
            state.putInt("MaxBrokenBlocks", Math.max(
                state.getInt("MaxBrokenBlocks"), diagnostics.brokenBlocks()
            ));
        }
        String taskId = npc.tasks().activeTaskId();
        if (taskId != null && !taskId.isBlank() && !taskId.startsWith("local:")) {
            String observed = state.getString("ObservedTaskId");
            if (observed.isBlank()) state.putString("ObservedTaskId", taskId);
            else if (!observed.equals(taskId)) state.putBoolean("TaskIdStable", false);
        }
        for (ItemTransactionLedger.Entry entry : npc.recentItemTransactions()) {
            if (entry.action().equals("drop") && entry.delta() < 0
                && MiningInventoryCleanupPolicy.isDiscardableStone(entry.itemId())) {
                state.putBoolean("SawStoneDropLedger", true);
                break;
            }
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    static boolean acceptanceComplete(
        int ladders,
        int torches,
        int usableIronPickaxes,
        boolean sawDescending,
        boolean sawBranching,
        int staircaseStep,
        int branchProgress,
        int placedTorches,
        int brokenBlocks,
        int diamonds,
        int playerDiamondPickaxes,
        boolean deliverySeen,
        boolean taskIdStable,
        int discardedStoneStacks,
        int discardedStoneItems,
        boolean sawStoneDropLedger,
        int errors
    ) {
        return ladders >= DeepMiningPolicy.REQUIRED_LADDERS
            && torches >= DeepMiningPolicy.REQUIRED_TORCHES
            && usableIronPickaxes >= DeepMiningPolicy.REQUIRED_IRON_PICKAXES
            && sawDescending && sawBranching
            && staircaseStep >= STAIR_STEPS
            && branchProgress >= DeepMiningPolicy.TORCH_INTERVAL
            && placedTorches >= 1
            && brokenBlocks >= 20
            && diamonds >= ORE_COUNT
            && playerDiamondPickaxes >= 1
            && deliverySeen && taskIdStable
            && discardedStoneStacks >= 2
            && discardedStoneItems >= 128
            && sawStoneDropLedger
            && errors == 0;
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (hasMarkerReference(npc)) {
                throw new IllegalStateException("Deep mining fixture marker reference could not be resolved");
            }
            if (report) npc.setNextLiveFixtureAckStatus("deep-mining:cleanup|none");
            return;
        }
        requireNoTasks(npc);
        CompoundTag state = fixtureState(marker);
        ServerLevel level = requireFixtureLevel(player, npc, state);
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(12.0D),
            candidate -> candidate.getTags().contains(ITEM_TAG)
        )) item.discard();

        boolean playerRestored = restorePlayerState(player, state);
        boolean npcRestored = restoreNpcState(npc, state);
        boolean blocksRestored = restoreBlocks(level, state);
        boolean itemsRemoved = level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(12.0D),
            candidate -> candidate.getTags().contains(ITEM_TAG)
        ).isEmpty();
        boolean complete = playerRestored && npcRestored && blocksRestored && itemsRemoved;
        String evidence = "deep-mining:cleanup|r=" + bit(playerRestored) + "," + bit(npcRestored)
            + "," + bit(blocksRestored) + "," + bit(itemsRemoved);
        npc.getPersistentData().remove(MARKER_UUID_KEY);
        npc.getPersistentData().remove(MARKER_DIMENSION_KEY);
        if (complete) marker.discard();
        if (report) npc.setNextLiveFixtureAckStatus(evidence);
        if (!complete && !report) {
            throw new IllegalStateException("Deep mining fixture cleanup restoration failed");
        }
    }

    static String failureCode(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("active") || message.contains("paused") || message.contains("idle")) {
            return "npc-not-idle";
        }
        if (message.contains("dimension")) return "dimension-mismatch";
        if (message.contains("survival")) return "survival-required";
        if (message.contains("site")) return "site-unavailable";
        if (message.contains("marker")) return "fixture-state-missing";
        if (message.contains("inventory")) return "inventory-rejected";
        if (message.contains("restoration")) return "cleanup-incomplete";
        return "fixture-failed";
    }

    private static void requireSafeSetup(ServerPlayer player, CodexNpcEntity npc) {
        if (!player.isAlive() || !npc.isAlive() || npc.isDowned()) {
            throw new IllegalStateException("Deep mining fixture requires living actors");
        }
        if (player.level() != npc.level()) {
            throw new IllegalStateException("Deep mining fixture requires the same dimension");
        }
        if (!player.level().dimensionType().natural()) {
            throw new IllegalStateException("Deep mining fixture requires a natural dimension");
        }
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || npc.creativeResources()) {
            throw new IllegalStateException("Deep mining fixture requires survival mode");
        }
        if (npc.isManagedEating() || player.isSleeping() || npc.isSleeping()
            || player.isPassenger() || npc.isPassenger()) {
            throw new IllegalStateException("Deep mining fixture actors are not ready");
        }
        requireNoTasks(npc);
        if (!"idle".equals(npc.tasks().schedulerLifecycle())) {
            throw new IllegalStateException("Deep mining fixture requires an idle scheduler");
        }
    }

    private static void requireNoTasks(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Deep mining fixture requires no active or paused task");
        }
    }

    private static Site findSite(ServerLevel level, BlockPos near) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                BlockPos origin = new BlockPos(near.getX() + dx, ENTRY_Y, near.getZ() + dz);
                Site site = siteGeometry(origin);
                if (safeSite(level, site)) return site;
            }
        }
        throw new IllegalStateException("No isolated deep mining fixture site was found");
    }

    static Site siteGeometry(BlockPos origin) {
        LinkedHashMap<BlockPos, BlockState> desired = new LinkedHashMap<>();
        desired.put(origin.below(), Blocks.DEEPSLATE.defaultBlockState());
        desired.put(origin, Blocks.AIR.defaultBlockState());
        desired.put(origin.above(), Blocks.AIR.defaultBlockState());

        BlockPos workstationApproach = origin.west();
        desired.put(workstationApproach.below(), Blocks.DEEPSLATE.defaultBlockState());
        desired.put(workstationApproach, Blocks.AIR.defaultBlockState());
        desired.put(workstationApproach.above(), Blocks.AIR.defaultBlockState());
        desired.put(origin.west(2), Blocks.CRAFTING_TABLE.defaultBlockState());

        for (int offset = 1; offset <= 2; offset++) {
            BlockPos playerPath = origin.east(offset);
            desired.put(playerPath.below(), Blocks.DEEPSLATE.defaultBlockState());
            desired.put(playerPath, Blocks.AIR.defaultBlockState());
            desired.put(playerPath.above(), Blocks.AIR.defaultBlockState());
        }

        for (int step = 1; step <= STAIR_STEPS; step++) {
            BlockPos stand = DeepMiningPolicy.staircaseStand(origin, Direction.SOUTH, step);
            desired.put(stand.below(), Blocks.DEEPSLATE.defaultBlockState());
            BlockPos previous = DeepMiningPolicy.staircaseStand(origin, Direction.SOUTH, step - 1);
            for (BlockPos excavation : DeepMiningPolicy.corridorExcavations(previous, stand)) {
                desired.put(excavation, Blocks.DEEPSLATE.defaultBlockState());
            }
        }
        for (Direction blocked : List.of(Direction.NORTH, Direction.EAST, Direction.WEST)) {
            BlockPos stand = DeepMiningPolicy.staircaseStand(origin, blocked, 1);
            desired.put(stand.below(), Blocks.BEDROCK.defaultBlockState());
        }

        BlockPos landing = DeepMiningPolicy.staircaseStand(origin, Direction.SOUTH, STAIR_STEPS);
        for (int progress = 1; progress < ORE_START + ORE_COUNT; progress++) {
            BlockPos stand = DeepMiningPolicy.branchStand(landing, Direction.SOUTH, 0, 0, progress);
            desired.put(stand.below(), Blocks.DEEPSLATE.defaultBlockState());
            desired.put(stand, progress >= ORE_START
                ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()
                : Blocks.DEEPSLATE.defaultBlockState());
            desired.put(stand.above(), Blocks.DEEPSLATE.defaultBlockState());
        }
        return new Site(
            origin.immutable(),
            Collections.unmodifiableMap(new LinkedHashMap<>(desired)),
            Bounds.of(desired.keySet())
        );
    }

    static BlockPos oreStand(BlockPos origin, int oreIndex) {
        if (oreIndex < 0 || oreIndex >= ORE_COUNT) {
            throw new IllegalArgumentException("Ore index is outside the fixture vein");
        }
        BlockPos landing = DeepMiningPolicy.staircaseStand(origin, Direction.SOUTH, STAIR_STEPS);
        return DeepMiningPolicy.branchStand(
            landing,
            Direction.SOUTH,
            0,
            0,
            ORE_START + oreIndex
        );
    }

    private static boolean safeSite(ServerLevel level, Site site) {
        for (BlockPos position : site.desired().keySet()) {
            level.getChunkAt(position);
            if (!level.getWorldBorder().isWithinBounds(position)
                || position.getY() <= level.getMinBuildHeight()
                || position.getY() >= level.getMaxBuildHeight() - 1
                || level.getBlockEntity(position) != null
                || !level.getFluidState(position).isEmpty()
                || level.getBlockState(position).getDestroySpeed(level, position) < 0.0F) return false;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = position.relative(direction);
                BlockState state = level.getBlockState(neighbor);
                // Unbreakable neighboring support is safe. Only fixture-owned
                // positions must be replaceable; fluids and falling blocks can
                // still enter the corridor and therefore reject the site.
                if (!level.getFluidState(neighbor).isEmpty()
                    || GRAVITY_BLOCK_IDS.contains(String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())))) {
                    return false;
                }
            }
        }
        return level.getEntities(null, site.bounds().aabb().inflate(2.0D)).isEmpty();
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Deep mining fixture has not been set up");
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
                npc.getBoundingBox().inflate(512.0D),
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
        npc.getPersistentData().putUUID(MARKER_UUID_KEY, marker.getUUID());
        npc.getPersistentData().putString(MARKER_DIMENSION_KEY, level.dimension().location().toString());
    }

    private static boolean hasMarkerReference(CodexNpcEntity npc) {
        return npc.getPersistentData().hasUUID(MARKER_UUID_KEY);
    }

    private static ServerLevel requireFixtureLevel(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        String dimensionId = state.getString("FixtureDimension");
        if (!dimensionId.equals(player.level().dimension().location().toString())
            || !dimensionId.equals(npc.level().dimension().location().toString())) {
            throw new IllegalStateException("Deep mining fixture cleanup requires the original dimension");
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId));
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Deep mining fixture dimension is unavailable");
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
        state.putDouble("NpcX", npc.getX());
        state.putDouble("NpcY", npc.getY());
        state.putDouble("NpcZ", npc.getZ());
        state.putFloat("NpcYaw", npc.getYRot());
        state.putFloat("NpcPitch", npc.getXRot());
    }

    private static boolean restorePlayerState(ServerPlayer player, CompoundTag state) {
        if (!state.contains(SAVED_PLAYER_KEY, Tag.TAG_COMPOUND)) return false;
        player.load(state.getCompound(SAVED_PLAYER_KEY).copy());
        player.connection.teleport(
            state.getDouble("PlayerX"), state.getDouble("PlayerY"), state.getDouble("PlayerZ"),
            state.getFloat("PlayerYaw"), state.getFloat("PlayerPitch")
        );
        player.containerMenu.broadcastChanges();
        player.onUpdateAbilities();
        return close(player.getX(), state.getDouble("PlayerX"))
            && close(player.getY(), state.getDouble("PlayerY"))
            && close(player.getZ(), state.getDouble("PlayerZ"));
    }

    private static boolean restoreNpcState(CodexNpcEntity npc, CompoundTag state) {
        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) return false;
        npc.load(state.getCompound(SAVED_NPC_KEY).copy());
        npc.getNavigation().stop();
        npc.teleportTo(state.getDouble("NpcX"), state.getDouble("NpcY"), state.getDouble("NpcZ"));
        npc.setYRot(state.getFloat("NpcYaw"));
        npc.setXRot(state.getFloat("NpcPitch"));
        return close(npc.getX(), state.getDouble("NpcX"))
            && close(npc.getY(), state.getDouble("NpcY"))
            && close(npc.getZ(), state.getDouble("NpcZ"));
    }

    private static int[] blockStateIds(ServerLevel level, Iterable<BlockPos> positions) {
        ArrayList<Integer> ids = new ArrayList<>();
        for (BlockPos position : positions) ids.add(Block.getId(level.getBlockState(position)));
        int[] result = new int[ids.size()];
        for (int index = 0; index < ids.size(); index++) result[index] = ids.get(index);
        return result;
    }

    private static boolean restoreBlocks(ServerLevel level, CompoundTag state) {
        long[] positions = state.getLongArray("FixtureBlocks");
        int[] blockStates = state.getIntArray("OriginalStates");
        if (positions.length == 0 || positions.length != blockStates.length) return false;
        for (int index = positions.length - 1; index >= 0; index--) {
            set(level, BlockPos.of(positions[index]), Block.stateById(blockStates[index]));
        }
        for (int index = 0; index < positions.length; index++) {
            if (Block.getId(level.getBlockState(BlockPos.of(positions[index]))) != blockStates[index]) return false;
        }
        return true;
    }

    private static long[] packed(Iterable<BlockPos> positions) {
        ArrayList<Long> values = new ArrayList<>();
        for (BlockPos position : positions) values.add(position.asLong());
        long[] result = new long[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    private static int usableIronPickaxes(CodexNpcEntity npc) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(Items.IRON_PICKAXE)
                && stack.getMaxDamage() - stack.getDamageValue()
                    >= DeepMiningPolicy.MIN_PICKAXE_REMAINING_DURABILITY) count += stack.getCount();
        }
        return count;
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

    private static void clearNpcInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void insert(CodexNpcEntity npc, Item item, int count) {
        ItemStack remainder = npc.insert(new ItemStack(item, count));
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected deep mining fixture items");
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state) {
        if (level.getBlockState(position).equals(state)) return;
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Deep mining fixture block change was rejected at "
                + position.toShortString());
        }
    }

    private static AABB fixtureBounds(CompoundTag state) {
        return new AABB(
            state.getInt("MinX"), state.getInt("MinY"), state.getInt("MinZ"),
            state.getInt("MaxX") + 1.0D, state.getInt("MaxY") + 1.0D, state.getInt("MaxZ") + 1.0D
        );
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= 0.01D;
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    record Site(BlockPos origin, Map<BlockPos, BlockState> desired, Bounds bounds) {
    }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
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
