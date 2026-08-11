package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reversible failure-and-resume construction state used by loopback acceptance tests. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class BuildResumeLiveFixture {
    private static final String SUITE = "build-resume";
    private static final String MARKER_TAG = "CodexAcceptanceBuildResumeMarker";
    private static final String STATE_KEY = "CodexAcceptanceBuildResumeState";
    private static final int SEARCH_RADIUS = 512;
    private static final int BLOCKER_INDEX = 3;
    private static final int[] SITE_OFFSETS = { 72, -72, 104, -104, 136, -136, 168, -168, 200, -200 };
    private static final List<BlockPos> RELATIVE = List.of(
        new BlockPos(0, 0, 0),
        new BlockPos(2, 0, 0),
        new BlockPos(4, 0, 0),
        new BlockPos(0, 0, 2),
        new BlockPos(2, 0, 2),
        new BlockPos(4, 0, 2)
    );
    private static final List<Block> EXPECTED = List.of(
        Blocks.BIRCH_PLANKS,
        Blocks.SPRUCE_STAIRS,
        Blocks.JUNGLE_SLAB,
        Blocks.ACACIA_FENCE,
        Blocks.DARK_OAK_TRAPDOOR,
        Blocks.MANGROVE_PRESSURE_PLATE
    );

    private BuildResumeLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Build resume fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "inspect-failed" -> inspectFailed(player, npc);
            case "release" -> release(player, npc);
            case "inspect-complete" -> inspectComplete(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown build resume fixture mode");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void protectFixtureBlocker(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof FakePlayer)
            || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos target = event.getPos();
        ArmorStand marker = findMarkerNear(level, target);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (state.getLong("Blocker") != target.asLong()) return;
        if (!state.getBoolean("Released")) {
            state.putInt("DeniedBreaks", state.getInt("DeniedBreaks") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
            event.setCanceled(true);
            return;
        }
        state.putInt("ReleasedBreaks", state.getInt("ReleasedBreaks") + 1);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        requireIdle(npc);
        cleanup(player, npc, false);
        BlockPos origin = findSite(player.serverLevel(), npc.blockPosition());
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>();
        for (int x = -5; x <= 7; x++) {
            for (int z = -4; z <= 4; z++) modified.add(origin.offset(x, -1, z));
        }
        for (BlockPos relative : RELATIVE) modified.add(origin.offset(relative));

        FixtureContext context = beginFixture(player, npc, origin, modified);
        try {
            for (int x = -5; x <= 7; x++) {
                for (int z = -4; z <= 4; z++) {
                    setFixtureBlock(context.level(), origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                }
            }
            BlockPos blocker = origin.offset(RELATIVE.get(BLOCKER_INDEX));
            setFixtureBlock(context.level(), blocker, Blocks.COBBLESTONE.defaultBlockState());
            CompoundTag state = context.state();
            state.putLong("Blocker", blocker.asLong());
            context.marker().getPersistentData().put(STATE_KEY, state);

            for (Block expected : EXPECTED) insertFixtureItem(npc, expected.asItem(), 1);
            insertFixtureItem(npc, Items.DIAMOND_PICKAXE, 1);
            moveNpc(npc, origin.offset(-3, 0, 3));
            npc.tasks().stay();
            npc.setStatus("build-resume:setup origin="
                + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void inspectFailed(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc);
        NpcTaskEngine tasks = npc.tasks();
        BlockPos expectedTarget = context.origin().offset(RELATIVE.get(BLOCKER_INDEX));
        BlockPos checkpointTarget = tasks.recoverableBuildTargetForFixture();
        String taskId = tasks.recoverableBuildTaskIdForFixture();
        int index = tasks.recoverableBuildIndexForFixture();
        int total = tasks.recoverableBuildTotalForFixture();
        String code = tasks.recoverableBuildFailureCodeForFixture();
        if (taskId.isBlank() || !expectedTarget.equals(checkpointTarget)) {
            throw new IllegalStateException("Expected build checkpoint was not retained at the fixture blocker");
        }

        int prefix = matchingRange(context.level(), context.origin(), 0, BLOCKER_INDEX);
        int tail = matchingRange(context.level(), context.origin(), BLOCKER_INDEX + 1, EXPECTED.size());
        int blocker = context.level().getBlockState(expectedTarget).is(Blocks.COBBLESTONE) ? 1 : 0;
        CompoundTag state = context.state();
        state.putString("TaskId", taskId);
        context.marker().getPersistentData().put(STATE_KEY, state);
        npc.setStatus("build-resume:f=" + taskId
            + "," + index
            + "," + total
            + "," + code
            + "," + prefix
            + "," + blocker
            + "," + tail
            + "," + state.getInt("DeniedBreaks")
            + "," + state.getInt("ReleasedBreaks"));
    }

    private static void release(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc);
        NpcTaskEngine tasks = npc.tasks();
        BlockPos expectedTarget = context.origin().offset(RELATIVE.get(BLOCKER_INDEX));
        if (!expectedTarget.equals(tasks.recoverableBuildTargetForFixture())) {
            throw new IllegalStateException("Build resume fixture can release only its retained failure point");
        }
        CompoundTag state = context.state();
        state.putBoolean("Released", true);
        context.marker().getPersistentData().put(STATE_KEY, state);
        npc.setStatus("build-resume:release task=" + tasks.recoverableBuildTaskIdForFixture()
            + ",index=" + tasks.recoverableBuildIndexForFixture());
    }

    private static void inspectComplete(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc);
        int matching = matchingRange(context.level(), context.origin(), 0, EXPECTED.size());
        int wrong = EXPECTED.size() - matching;
        CompoundTag state = context.state();
        String retained = npc.tasks().recoverableBuildTaskIdForFixture();
        npc.setStatus("build-resume:complete expected=" + EXPECTED.size()
            + ",matching=" + matching
            + ",wrong=" + wrong
            + ",denied=" + state.getInt("DeniedBreaks")
            + ",releasedBreaks=" + state.getInt("ReleasedBreaks")
            + ",recoverable=" + (retained.isBlank() ? 0 : 1));
    }

    private static FixtureContext beginFixture(
        ServerPlayer player,
        CodexNpcEntity npc,
        BlockPos origin,
        Set<BlockPos> modified
    ) {
        ServerLevel level = player.serverLevel();
        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Fixture marker could not be created");
        marker.moveTo(origin.getX() + 0.5D, origin.getY() + 6.0D, origin.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 1);
        state.putString("Suite", SUITE);
        state.putString("Dimension", level.dimension().location().toString());
        state.putLong("Origin", origin.asLong());
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
        saveInventory(npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            restoreInventory(npc, state);
            throw new IllegalStateException("Fixture marker was rejected");
        }
        return new FixtureContext(marker, state, level, origin);
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (report) npc.setStatus("build-resume:cleanup none");
            return;
        }
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        BlockPos blocker = BlockPos.of(state.getLong("Blocker"));
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("Dimension"))
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Fixture dimension is unavailable");

        int conflicts = 0;
        long[] modified = state.getLongArray("ModifiedBlocks");
        for (long value : modified) {
            BlockState current = level.getBlockState(BlockPos.of(value));
            if (!current.isAir() && !isFixtureBlock(current.getBlock())) conflicts++;
        }
        if (conflicts > 0) {
            if (report) npc.setStatus("build-resume:cleanup conflicts=" + conflicts);
            throw new IllegalStateException("Fixture cleanup found unexpected blocks: " + conflicts);
        }

        NpcTaskEngine tasks = npc.tasks();
        boolean matchingCheckpoint = blocker.equals(tasks.recoverableBuildTargetForFixture());
        String active = tasks.activeTaskId();
        int unrelatedPaused = tasks.pausedTaskCount() - (matchingCheckpoint ? 1 : 0);
        if (active != null && !active.isBlank() || unrelatedPaused > 0) {
            throw new IllegalStateException("Cancel and finish NPC tasks before cleaning the build resume fixture");
        }
        if (matchingCheckpoint) tasks.discardRecoverableBuildCheckpointForFixture(blocker);

        clearInventory(npc);
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

        for (int index = modified.length - 1; index >= 0; index--) {
            BlockPos position = BlockPos.of(modified[index]);
            BlockState current = level.getBlockState(position);
            if (current.isAir()) continue;
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(origin).inflate(24.0D, 12.0D, 24.0D),
            entity -> isFixtureOutput(entity.getItem())
        )) item.discard();

        marker.discard();
        if (report) npc.setStatus("build-resume:cleanup restored");
    }

    private static FixtureContext requireContext(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Build resume fixture has not been set up");
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!SUITE.equals(state.getString("Suite"))) throw new IllegalStateException("Fixture suite mismatch");
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("Dimension"))
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Fixture dimension is unavailable");
        return new FixtureContext(marker, state, level, BlockPos.of(state.getLong("Origin")));
    }

    private static BlockPos findSite(ServerLevel level, BlockPos near) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int centerX = near.getX() + dx;
                int centerZ = near.getZ() + dz;
                int maximumSurface = level.getMinBuildHeight();
                boolean withinBorder = true;
                for (int x = -5; x <= 7 && withinBorder; x++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos column = new BlockPos(centerX + x, near.getY(), centerZ + z);
                        if (!level.getWorldBorder().isWithinBounds(column)) {
                            withinBorder = false;
                            break;
                        }
                        maximumSurface = Math.max(maximumSurface, level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            column.getX(),
                            column.getZ()
                        ));
                    }
                }
                if (!withinBorder) continue;
                BlockPos origin = new BlockPos(centerX, maximumSurface + 13, centerZ);
                if (origin.getY() + 8 >= level.getMaxBuildHeight()) continue;
                boolean clear = true;
                for (int x = -5; x <= 7 && clear; x++) {
                    for (int z = -4; z <= 4 && clear; z++) {
                        for (int y = -1; y <= 7; y++) {
                            BlockPos position = origin.offset(x, y, z);
                            if (!level.getBlockState(position).isAir()
                                || !level.getFluidState(position).isEmpty()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                }
                if (!clear) continue;
                AABB volume = new AABB(
                    origin.getX() - 5,
                    origin.getY() - 1,
                    origin.getZ() - 4,
                    origin.getX() + 8,
                    origin.getY() + 8,
                    origin.getZ() + 5
                );
                if (level.getEntities(null, volume).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated build resume fixture site was found");
    }

    private static ArmorStand findMarker(ServerPlayer player, CodexNpcEntity npc) {
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

    private static ArmorStand findMarkerNear(ServerLevel level, BlockPos target) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(target).inflate(16.0D),
            candidate -> candidate.getTags().contains(MARKER_TAG)
                && SUITE.equals(candidate.getPersistentData().getCompound(STATE_KEY).getString("Suite"))
        ).stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(Vec3.atCenterOf(target))))
            .orElse(null);
    }

    private static int matchingRange(ServerLevel level, BlockPos origin, int start, int end) {
        int matching = 0;
        for (int index = start; index < end; index++) {
            if (level.getBlockState(origin.offset(RELATIVE.get(index))).is(EXPECTED.get(index))) matching++;
        }
        return matching;
    }

    private static void requireIdle(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if (active != null && !active.isBlank() || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Cancel and finish NPC tasks before changing a live fixture");
        }
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
    }

    private static void setFixtureBlock(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Fixture block could not be placed at " + position.toShortString());
        }
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

    private static void clearInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void restoreInventory(CodexNpcEntity npc, CompoundTag state) {
        ListTag saved = state.getList("SavedInventory", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag entry = saved.getCompound(index);
            int slot = entry.getInt("Slot");
            if (slot < 0 || slot >= CodexNpcEntity.INVENTORY_SIZE) continue;
            npc.inventory().setStackInSlot(slot, ItemStack.of(entry.getCompound("Stack")));
        }
    }

    private static void insertFixtureItem(CodexNpcEntity npc, Item item, int count) {
        ItemStack remainder = npc.insert(new ItemStack(item, count));
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected fixture items");
    }

    private static boolean isFixtureBlock(Block block) {
        return Set.of(
            Blocks.STONE,
            Blocks.COBBLESTONE,
            Blocks.OAK_PLANKS,
            Blocks.OAK_STAIRS,
            Blocks.OAK_SLAB,
            Blocks.OAK_FENCE,
            Blocks.OAK_TRAPDOOR,
            Blocks.OAK_PRESSURE_PLATE,
            Blocks.BIRCH_PLANKS,
            Blocks.SPRUCE_STAIRS,
            Blocks.JUNGLE_SLAB,
            Blocks.ACACIA_FENCE,
            Blocks.DARK_OAK_TRAPDOOR,
            Blocks.MANGROVE_PRESSURE_PLATE
        ).contains(block);
    }

    private static boolean isFixtureOutput(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.COBBLESTONE)
            || EXPECTED.stream().anyMatch(block -> stack.is(block.asItem())));
    }

    private static long[] longs(Iterable<BlockPos> positions) {
        List<Long> values = new ArrayList<>();
        for (BlockPos position : positions) values.add(position.asLong());
        long[] result = new long[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    private record FixtureContext(
        ArmorStand marker,
        CompoundTag state,
        ServerLevel level,
        BlockPos origin
    ) {
    }
}
