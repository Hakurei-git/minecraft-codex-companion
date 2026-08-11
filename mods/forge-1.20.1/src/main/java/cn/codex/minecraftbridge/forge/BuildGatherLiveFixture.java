package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reversible world state used by loopback build and gather acceptance tests. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class BuildGatherLiveFixture {
    private static final String BUILD_SUITE = "build-palette";
    private static final String TREE_SUITE = "natural-tree";
    private static final String MATERIAL_SUITE = "build-material-chain";
    private static final String MARKER_TAG = "CodexAcceptanceBuildGatherMarker";
    private static final String STATE_KEY = "CodexAcceptanceBuildGatherState";
    private static final int SEARCH_RADIUS = 512;
    private static final int[] SITE_OFFSETS = { 64, -64, 96, -96, 128, -128, 160, -160, 192, -192 };
    private static final List<BlockPos> BUILD_RELATIVE = List.of(
        new BlockPos(0, 0, 0),
        new BlockPos(2, 0, 0),
        new BlockPos(4, 0, 0),
        new BlockPos(0, 0, 2),
        new BlockPos(2, 0, 2),
        new BlockPos(4, 0, 2)
    );
    private static final List<Block> MIXED_EXPECTED = List.of(
        Blocks.BIRCH_PLANKS,
        Blocks.SPRUCE_STAIRS,
        Blocks.JUNGLE_SLAB,
        Blocks.ACACIA_FENCE,
        Blocks.DARK_OAK_TRAPDOOR,
        Blocks.MANGROVE_PRESSURE_PLATE
    );
    private static final List<Block> CHAIN_EXPECTED = List.of(
        Blocks.DARK_OAK_PLANKS,
        Blocks.DARK_OAK_STAIRS,
        Blocks.DARK_OAK_SLAB,
        Blocks.DARK_OAK_FENCE,
        Blocks.DARK_OAK_TRAPDOOR,
        Blocks.DARK_OAK_PRESSURE_PLATE
    );
    private static final List<BlockPos> MASONRY_RELATIVE = List.of(
        new BlockPos(0, 0, 0),
        new BlockPos(2, 0, 0),
        new BlockPos(4, 0, 0)
    );
    private static final List<BlockPos> MATERIAL_RELATIVE = List.of(
        new BlockPos(0, 0, 0),
        new BlockPos(2, 0, 0),
        new BlockPos(4, 0, 0),
        new BlockPos(0, 1, 0),
        new BlockPos(2, 1, 0)
    );
    private static final List<Block> MATERIAL_EXPECTED = List.of(
        Blocks.COBBLESTONE,
        Blocks.OAK_PLANKS,
        Blocks.GLASS,
        Blocks.TORCH,
        Blocks.GLASS_PANE
    );

    private BuildGatherLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String suite, String mode) {
        if (npc == null) throw new IllegalStateException("Build/gather fixture requires an in-world NPC");
        if (BUILD_SUITE.equals(suite)) {
            if (mode.equals("catalog")) {
                inspectBuildCatalog(player, npc, -1);
                return;
            }
            int catalogIndex = numericModeSuffix(mode, "catalog-");
            if (catalogIndex >= 0) {
                inspectBuildCatalog(player, npc, catalogIndex);
                return;
            }
            int setupFamilyIndex = numericModeSuffix(mode, "setup-family-");
            if (setupFamilyIndex >= 0) {
                setupBuildFamily(player, npc, setupFamilyIndex);
                return;
            }
            int inspectFamilyIndex = numericModeSuffix(mode, "inspect-family-");
            if (inspectFamilyIndex >= 0) {
                inspectBuildFamily(player, npc, inspectFamilyIndex);
                return;
            }
            switch (mode) {
                case "setup-mixed" -> setupBuild(player, npc, "mixed");
                case "inspect-mixed" -> inspectBuild(player, npc, "mixed");
                case "setup-chain" -> setupBuild(player, npc, "chain");
                case "inspect-chain" -> inspectBuild(player, npc, "chain");
                case "cleanup" -> cleanup(player, npc, BUILD_SUITE, true);
                default -> throw new IllegalArgumentException("Unknown build palette fixture mode");
            }
            return;
        }
        if (TREE_SUITE.equals(suite)) {
            switch (mode) {
                case "setup" -> setupTrees(player, npc);
                case "inspect" -> inspectTrees(player, npc);
                case "cleanup" -> cleanup(player, npc, TREE_SUITE, true);
                default -> throw new IllegalArgumentException("Unknown natural tree fixture mode");
            }
            return;
        }
        if (MATERIAL_SUITE.equals(suite)) {
            switch (mode) {
                case "setup" -> setupMaterialChain(player, npc);
                case "inspect" -> inspectMaterialChain(player, npc);
                case "cleanup" -> cleanup(player, npc, MATERIAL_SUITE, true);
                default -> throw new IllegalArgumentException("Unknown build material fixture mode");
            }
            return;
        }
        throw new IllegalArgumentException("Unknown build/gather fixture suite");
    }

    @SubscribeEvent
    public static void recordFixtureBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof FakePlayer player)
            || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos target = event.getPos();
        ArmorStand marker = level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(target).inflate(64.0D),
            candidate -> candidate.getTags().contains(MARKER_TAG)
        ).stream().filter(candidate -> {
            CompoundTag candidateState = candidate.getPersistentData().getCompound(STATE_KEY);
            String suite = candidateState.getString("Suite");
            return (TREE_SUITE.equals(suite)
                && blockSet(candidateState.getLongArray("HarvestLogs")).contains(target))
                || (MATERIAL_SUITE.equals(suite)
                && blockSet(candidateState.getLongArray("ModifiedBlocks")).contains(target));
        }).min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(Vec3.atCenterOf(target))))
            .orElse(null);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (MATERIAL_SUITE.equals(state.getString("Suite"))) {
            if (blockSet(state.getLongArray("MaterialLogs")).contains(target)) {
                state.putInt("MaterialLogBreaks", state.getInt("MaterialLogBreaks") + 1);
            } else if (blockSet(state.getLongArray("MaterialStone")).contains(target)) {
                state.putInt("MaterialStoneBreaks", state.getInt("MaterialStoneBreaks") + 1);
            } else if (blockSet(state.getLongArray("MaterialSand")).contains(target)) {
                state.putInt("MaterialSandBreaks", state.getInt("MaterialSandBreaks") + 1);
            } else if (blockSet(state.getLongArray("MaterialCoal")).contains(target)) {
                state.putInt("MaterialCoalBreaks", state.getInt("MaterialCoalBreaks") + 1);
            } else if (blockSet(state.getLongArray("MaterialLeaves")).contains(target)) {
                // Leaves belong to the fixture trees and may be cleared for physical access.
            } else {
                state.putInt("UnexpectedBreaks", state.getInt("UnexpectedBreaks") + 1);
            }
        } else {
            state.putInt("BreakCount", state.getInt("BreakCount") + 1);
        }
        recordBreakReachEvidence(level, player, target, state);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void recordFixturePlacement(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos target = event.getPos();
        ArmorStand marker = level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(target).inflate(64.0D),
            candidate -> candidate.getTags().contains(MARKER_TAG)
                && MATERIAL_SUITE.equals(candidate.getPersistentData().getCompound(STATE_KEY).getString("Suite"))
        ).stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(Vec3.atCenterOf(target))))
            .orElse(null);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!(event.getEntity() instanceof FakePlayer)) {
            state.putInt("UnknownWorldEdits", state.getInt("UnknownWorldEdits") + 1);
            marker.getPersistentData().put(STATE_KEY, state);
            return;
        }
        if (!blockSet(state.getLongArray("ModifiedBlocks")).contains(target)) {
            state.putInt("UnknownWorldEdits", state.getInt("UnknownWorldEdits") + 1);
        }
        BlockState placed = event.getPlacedBlock();
        if (placed.is(Blocks.CRAFTING_TABLE)) {
            state.putInt("MaterialTablePlacements", state.getInt("MaterialTablePlacements") + 1);
        } else if (placed.is(Blocks.FURNACE)) {
            state.putInt("MaterialFurnacePlacements", state.getInt("MaterialFurnacePlacements") + 1);
        }
        if (placed.is(Blocks.COBBLESTONE)) state.putBoolean("SawMaterialCobblestone", true);
        if (placed.is(Blocks.OAK_PLANKS)) state.putBoolean("SawMaterialPlanks", true);
        if (placed.is(Blocks.GLASS)) state.putBoolean("SawMaterialGlass", true);
        if (placed.is(Blocks.TORCH)) state.putBoolean("SawMaterialTorch", true);
        if (placed.is(Blocks.GLASS_PANE)) state.putBoolean("SawMaterialPane", true);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    public static void recordMaterialFurnaceFuelSupply(
        CodexNpcEntity npc,
        BlockPos position,
        ItemStack fuel
    ) {
        if (position == null || fuel.isEmpty() || !AbstractFurnaceBlockEntity.isFuel(fuel)
            || !(npc.level() instanceof ServerLevel level)
            || !level.getBlockState(position).is(Blocks.FURNACE)) return;
        ArmorStand marker = level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(position).inflate(64.0D),
            candidate -> candidate.getTags().contains(MARKER_TAG)
                && MATERIAL_SUITE.equals(candidate.getPersistentData().getCompound(STATE_KEY).getString("Suite"))
        ).stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(Vec3.atCenterOf(position))))
            .orElse(null);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!blockSet(state.getLongArray("ModifiedBlocks")).contains(position)) return;
        state.putBoolean("SawFurnaceFuel", true);
        marker.getPersistentData().put(STATE_KEY, state);
    }

    @SubscribeEvent
    public static void observeMaterialTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
            || !(event.player instanceof ServerPlayer player)
            || player instanceof FakePlayer
            || player.tickCount % 5 != 0) return;
        CodexNpcEntity npc = NpcManager.find(player);
        if (npc == null) return;
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) return;
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!MATERIAL_SUITE.equals(state.getString("Suite"))) return;
        observeMaterialState(marker, npc);
    }

    private static void recordBreakReachEvidence(
        ServerLevel level,
        FakePlayer player,
        BlockPos target,
        CompoundTag state
    ) {
        CodexNpcEntity actionNpc = level.getEntitiesOfClass(
            CodexNpcEntity.class,
            player.getBoundingBox().inflate(64.0D),
            candidate -> candidate.isAlive()
                && !candidate.isInvisible()
                && candidate.ownerUuid().map(player.getUUID()::equals)
                    .orElseGet(() -> candidate.getUUID().equals(player.getUUID()))
        ).stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(player)))
            .orElse(null);
        if (actionNpc == null) {
            state.putInt("RemoteBreakCount", state.getInt("RemoteBreakCount") + 1);
            state.putInt("LosViolationCount", state.getInt("LosViolationCount") + 1);
            state.putInt("SyncViolationCount", state.getInt("SyncViolationCount") + 1);
            return;
        }
        if (actionNpc.position().distanceToSqr(player.position()) > 1.0E-6D) {
            state.putInt("SyncViolationCount", state.getInt("SyncViolationCount") + 1);
        }
        Vec3 eye = actionNpc.getEyePosition();
        double distance = GatherNavigationPolicy.blockTouchDistance(
            eye.x, eye.y, eye.z, target.getX(), target.getY(), target.getZ()
        );
        int touchMilli = (int) Math.ceil(distance * 1_000.0D);
        state.putInt("MaxTouchMilli", Math.max(state.getInt("MaxTouchMilli"), touchMilli));
        if (touchMilli > 4_500) state.putInt("RemoteBreakCount", state.getInt("RemoteBreakCount") + 1);

        BlockHitResult hit = level.clip(new ClipContext(
            eye,
            Vec3.atCenterOf(target),
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            actionNpc
        ));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
            state.putInt("LosViolationCount", state.getInt("LosViolationCount") + 1);
        }
    }

    private static void setupBuild(ServerPlayer player, CodexNpcEntity npc, String scenario) {
        requireIdle(npc);
        cleanup(player, npc, BUILD_SUITE, false);
        Site site = findSite(player.serverLevel(), npc.blockPosition(), -5, 7, -4, 4, 12);
        BlockPos origin = site.origin();
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>(platformPositions(origin, -5, 7, -4, 4));
        BlockPos craftingTable = origin.offset(-3, 0, 0);
        modified.add(craftingTable);
        for (BlockPos relative : BUILD_RELATIVE) modified.add(origin.offset(relative));

        FixtureContext context = beginFixture(player, npc, BUILD_SUITE, scenario, origin, modified);
        try {
            placePlatform(context.level(), origin, -5, 7, -4, 4, Blocks.STONE);
            setFixtureBlock(context.level(), craftingTable, Blocks.CRAFTING_TABLE.defaultBlockState());
            if (scenario.equals("mixed")) {
                for (Block block : MIXED_EXPECTED) insertFixtureItem(npc, block.asItem(), 1);
            } else {
                insertFixtureItem(npc, Items.DARK_OAK_LOG, 16);
            }
            moveNpc(npc, origin.offset(-3, 0, 3));
            movePlayer(player, origin.offset(-4, 0, 3));
            npc.tasks().stay();
            npc.setStatus("build-fixture:setup scenario=" + scenario + " origin="
                + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, BUILD_SUITE, false);
            throw error;
        }
    }

    private static void inspectBuild(ServerPlayer player, CodexNpcEntity npc, String scenario) {
        FixtureContext context = requireContext(player, npc, BUILD_SUITE, scenario);
        String activeDiagnostic = npc.tasks().activeBuildDiagnosticForFixture();
        if (!activeDiagnostic.isBlank()) {
            npc.setStatus("build-fixture:active " + activeDiagnostic);
            return;
        }
        List<Block> expected = scenario.equals("mixed") ? MIXED_EXPECTED : CHAIN_EXPECTED;
        int matching = 0;
        int wrong = 0;
        for (int index = 0; index < BUILD_RELATIVE.size(); index++) {
            BlockState state = context.level().getBlockState(context.origin().offset(BUILD_RELATIVE.get(index)));
            if (state.is(expected.get(index))) matching++;
            else wrong++;
        }
        npc.setStatus("build-fixture:" + scenario + " expected=" + expected.size()
            + ",matching=" + matching + ",wrong=" + wrong);
    }

    private static void inspectBuildCatalog(ServerPlayer player, CodexNpcEntity npc, int index) {
        List<BuildMaterialPaletteResolver.AuditFamily> families =
            BuildMaterialPaletteResolver.auditFamilies(player.serverLevel());
        if (index < 0) {
            long supported = families.stream().filter(BuildMaterialPaletteResolver.AuditFamily::supported).count();
            npc.setStatus("build-fixture:catalog count=" + families.size() + ",supported=" + supported);
            return;
        }
        if (index >= families.size()) throw new IllegalArgumentException("Build family catalog index is out of range");
        BuildMaterialPaletteResolver.AuditFamily family = families.get(index);
        String evidence = "build-fixture:catalog index=" + index
            + ",count=" + families.size()
            + ",category=" + family.category()
            + ",base=" + family.baseId()
            + ",source=" + (family.sourceId().isBlank() ? "none" : family.sourceId())
            + ",supported=" + bit(family.supported())
            + ",reason=" + (family.skipReason().isBlank() ? "none" : family.skipReason())
            + ",blocks=" + (family.blockIds().isEmpty() ? "none" : String.join("|", family.blockIds()));
        npc.setStatus("build-fixture:catalog index=" + index + ",count=" + families.size());
        npc.setNextLiveFixtureAckEvidence(evidence);
    }

    private static void setupBuildFamily(ServerPlayer player, CodexNpcEntity npc, int index) {
        List<BuildMaterialPaletteResolver.AuditFamily> families =
            BuildMaterialPaletteResolver.auditFamilies(player.serverLevel());
        if (index < 0 || index >= families.size()) {
            throw new IllegalArgumentException("Build family catalog index is out of range");
        }
        BuildMaterialPaletteResolver.AuditFamily family = families.get(index);
        if (!family.supported()) {
            throw new IllegalArgumentException("Build family is not safely testable: " + family.skipReason());
        }
        requireIdle(npc);
        cleanup(player, npc, BUILD_SUITE, false);
        String scenario = "family-" + index;
        List<BlockPos> relative = family.category().equals("wood") ? BUILD_RELATIVE : MASONRY_RELATIVE;
        if (relative.size() != family.blockIds().size()) {
            throw new IllegalStateException("Build family component count does not match its fixture geometry");
        }
        Site site = findSite(player.serverLevel(), npc.blockPosition(), -5, 7, -4, 4, 12);
        BlockPos origin = site.origin();
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>(platformPositions(origin, -5, 7, -4, 4));
        BlockPos craftingTable = origin.offset(-3, 0, 0);
        modified.add(craftingTable);
        for (BlockPos position : relative) modified.add(origin.offset(position));

        FixtureContext context = beginFixture(player, npc, BUILD_SUITE, scenario, origin, modified);
        context.state().putString("FamilyCategory", family.category());
        context.state().putString("FamilyBaseId", family.baseId());
        context.state().putString("FamilySourceId", family.sourceId());
        context.state().putString("ExpectedBlockIds", String.join("|", family.blockIds()));
        context.marker().getPersistentData().put(STATE_KEY, context.state());
        try {
            placePlatform(context.level(), origin, -5, 7, -4, 4, Blocks.STONE);
            setFixtureBlock(context.level(), craftingTable, Blocks.CRAFTING_TABLE.defaultBlockState());
            Item source = BuiltInRegistries.ITEM.get(ResourceLocation.parse(family.sourceId()));
            if (source == Items.AIR) throw new IllegalStateException("Build family source item is unavailable");
            insertFixtureItem(npc, source, family.category().equals("wood") ? 32 : 64);
            moveNpc(npc, origin.offset(-3, 0, 3));
            movePlayer(player, origin.offset(-4, 0, 3));
            npc.tasks().stay();
            npc.setStatus("build-fixture:setup scenario=" + scenario + " origin="
                + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, BUILD_SUITE, false);
            throw error;
        }
    }

    private static void inspectBuildFamily(ServerPlayer player, CodexNpcEntity npc, int index) {
        String scenario = "family-" + index;
        FixtureContext context = requireContext(player, npc, BUILD_SUITE, scenario);
        String activeDiagnostic = npc.tasks().activeBuildDiagnosticForFixture();
        if (!activeDiagnostic.isBlank()) {
            npc.setStatus("build-fixture:active " + activeDiagnostic);
            return;
        }
        List<String> expected = expectedBlockIds(context.state());
        List<BlockPos> relative = expected.size() == BUILD_RELATIVE.size() ? BUILD_RELATIVE : MASONRY_RELATIVE;
        if (expected.size() != relative.size()) throw new IllegalStateException("Build family fixture metadata is damaged");
        int matching = 0;
        for (int component = 0; component < expected.size(); component++) {
            ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(
                context.level().getBlockState(context.origin().offset(relative.get(component))).getBlock()
            );
            if (actual != null && actual.toString().equals(expected.get(component))) matching++;
        }
        npc.setStatus("build-fixture:" + scenario + " expected=" + expected.size()
            + ",matching=" + matching + ",wrong=" + (expected.size() - matching));
    }

    private static void setupTrees(ServerPlayer player, CodexNpcEntity npc) {
        requireIdle(npc);
        cleanup(player, npc, TREE_SUITE, false);
        Site site = findSite(player.serverLevel(), npc.blockPosition(), -3, 32, -8, 8, 40);
        BlockPos origin = site.origin();
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>(platformPositions(origin, -3, 32, -8, 8));

        List<BlockPos> protectedLogs = verticalLogs(origin.offset(8, 0, 0), 4);
        List<BlockPos> remoteLogs = verticalLogs(origin.offset(28, 0, 0), 4);
        List<BlockPos> artificialLogs = verticalLogs(origin.offset(20, 0, -5), 4);
        List<BlockPos> boundaryLogs = new ArrayList<>(verticalLogs(origin.offset(18, 0, 4), 4));
        for (int x = 17; x >= 14; x--) boundaryLogs.add(new BlockPos(x + origin.getX(), origin.getY() + 3, origin.getZ() + 4));
        List<BlockPos> boundaryProtected = boundaryLogs.stream()
            .filter(position -> position.distSqr(origin) <= 16 * 16)
            .toList();
        List<BlockPos> harvestLogs = new ArrayList<>();
        harvestLogs.addAll(remoteLogs);
        for (BlockPos position : boundaryLogs) if (!boundaryProtected.contains(position)) harvestLogs.add(position);

        LinkedHashSet<BlockPos> leaves = new LinkedHashSet<>();
        leaves.addAll(canopy(protectedLogs.get(protectedLogs.size() - 1)));
        leaves.addAll(canopy(remoteLogs.get(remoteLogs.size() - 1)));
        leaves.addAll(canopy(boundaryLogs.get(boundaryLogs.size() - 1)));
        leaves.removeAll(protectedLogs);
        leaves.removeAll(remoteLogs);
        leaves.removeAll(artificialLogs);
        leaves.removeAll(boundaryLogs);
        modified.addAll(protectedLogs);
        modified.addAll(remoteLogs);
        modified.addAll(artificialLogs);
        modified.addAll(boundaryLogs);
        modified.addAll(leaves);

        FixtureContext context = beginFixture(player, npc, TREE_SUITE, "trees", origin, modified);
        CompoundTag state = context.state();
        state.putLongArray("ProtectedLogs", longs(protectedLogs));
        state.putLongArray("RemoteLogs", longs(remoteLogs));
        state.putLongArray("ArtificialLogs", longs(artificialLogs));
        state.putLongArray("BoundaryLogs", longs(boundaryLogs));
        state.putLongArray("BoundaryProtectedLogs", longs(boundaryProtected));
        state.putLongArray("HarvestLogs", longs(harvestLogs));
        context.marker().getPersistentData().put(STATE_KEY, state);

        try {
            placePlatform(context.level(), origin, -3, 32, -8, 8, Blocks.STONE);
            for (BlockPos root : List.of(
                protectedLogs.get(0), remoteLogs.get(0), artificialLogs.get(0), boundaryLogs.get(0)
            )) {
                setFixtureBlock(context.level(), root.below(), Blocks.DIRT.defaultBlockState());
            }
            for (BlockPos position : protectedLogs) setFixtureBlock(context.level(), position, Blocks.OAK_LOG.defaultBlockState());
            for (BlockPos position : remoteLogs) setFixtureBlock(context.level(), position, Blocks.OAK_LOG.defaultBlockState());
            for (BlockPos position : artificialLogs) setFixtureBlock(context.level(), position, Blocks.OAK_LOG.defaultBlockState());
            for (BlockPos position : boundaryLogs) setFixtureBlock(context.level(), position, Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
            for (BlockPos position : leaves) setFixtureBlock(context.level(), position, leaf);

            saveRespawn(player, state);
            player.setRespawnPosition(context.level().dimension(), origin, 0.0F, true, false);
            context.marker().getPersistentData().put(STATE_KEY, state);
            insertFixtureItem(npc, Items.DIAMOND_AXE, 1);
            moveNpc(npc, origin.offset(0, 0, 2));
            npc.tasks().stay();
            npc.setStatus("tree-fixture:setup");
        } catch (RuntimeException error) {
            cleanup(player, npc, TREE_SUITE, false);
            throw error;
        }
    }

    private static void inspectTrees(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc, TREE_SUITE, "trees");
        CompoundTag state = context.state();
        int protectedCount = countLogs(context.level(), state.getLongArray("ProtectedLogs"));
        int remoteCount = countLogs(context.level(), state.getLongArray("RemoteLogs"));
        int boundaryProtected = countLogs(context.level(), state.getLongArray("BoundaryProtectedLogs"));
        int artificial = countLogs(context.level(), state.getLongArray("ArtificialLogs"));
        int npcLogs = countNpcLogs(npc);
        npc.setStatus("tree-fixture:protected=" + protectedCount
            + ",remote=" + remoteCount
            + ",npcLogs=" + npcLogs
            + ",boundaryProtected=" + boundaryProtected
            + ",artificial=" + artificial
            + ",breaks=" + state.getInt("BreakCount")
            + ",rb=" + state.getInt("RemoteBreakCount")
            + ",los=" + state.getInt("LosViolationCount")
            + ",max=" + state.getInt("MaxTouchMilli")
            + ",sync=" + state.getInt("SyncViolationCount"));
    }

    private static void setupMaterialChain(ServerPlayer player, CodexNpcEntity npc) {
        requireIdle(npc);
        cleanup(player, npc, MATERIAL_SUITE, false);
        requireIdle(npc);
        if (npc.creativeResources()) {
            throw new IllegalStateException("Build material fixture requires survival material mode");
        }

        Site site = findSite(player.serverLevel(), npc.blockPosition(), -12, 20, -12, 12, 12);
        BlockPos origin = site.origin();
        LinkedHashSet<BlockPos> modified = new LinkedHashSet<>();
        for (int x = -12; x <= 20; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = -1; y <= 3; y++) modified.add(origin.offset(x, y, z));
            }
        }

        List<BlockPos> logs = new ArrayList<>();
        LinkedHashSet<BlockPos> leaves = new LinkedHashSet<>();
        for (BlockPos root : List.of(
            origin.offset(17, 0, -8),
            origin.offset(17, 0, 0),
            origin.offset(17, 0, 8)
        )) {
            List<BlockPos> tree = verticalLogs(root, 5);
            logs.addAll(tree);
            leaves.addAll(canopy(tree.get(tree.size() - 1)));
        }
        leaves.removeAll(logs);

        List<BlockPos> stone = rectangle(origin.offset(4, 0, -6), 4, 4);
        List<BlockPos> sand = rectangle(origin.offset(13, 0, -6), 4, 4);
        List<BlockPos> coal = rectangle(origin.offset(8, 0, 7), 4, 4);
        List<BlockPos> targets = MATERIAL_RELATIVE.stream().map(origin::offset).toList();
        modified.addAll(logs);
        modified.addAll(leaves);
        modified.addAll(stone);
        modified.addAll(sand);
        modified.addAll(coal);
        modified.addAll(targets);

        FixtureContext context = beginFixture(player, npc, MATERIAL_SUITE, "survival", origin, modified);
        CompoundTag state = context.state();
        state.putLongArray("MaterialLogs", longs(logs));
        state.putLongArray("MaterialLeaves", longs(leaves));
        state.putLongArray("MaterialStone", longs(stone));
        state.putLongArray("MaterialSand", longs(sand));
        state.putLongArray("MaterialCoal", longs(coal));
        state.putLongArray("MaterialTargets", longs(targets));
        context.marker().getPersistentData().put(STATE_KEY, state);

        try {
            clearInventory(npc);
            placePlatform(context.level(), origin, -12, 20, -12, 12, Blocks.DIRT);
            for (BlockPos position : stone) setFixtureBlock(context.level(), position, Blocks.STONE.defaultBlockState());
            for (BlockPos position : sand) setFixtureBlock(context.level(), position, Blocks.SAND.defaultBlockState());
            for (BlockPos position : coal) setFixtureBlock(context.level(), position, Blocks.COAL_ORE.defaultBlockState());
            for (BlockPos position : logs) setFixtureBlock(context.level(), position, Blocks.OAK_LOG.defaultBlockState());
            BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false)
                .setValue(LeavesBlock.DISTANCE, 1);
            for (BlockPos position : leaves) setFixtureBlock(context.level(), position, leaf);

            player.setRespawnPosition(context.level().dimension(), origin, 0.0F, true, false);
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            moveNpc(npc, origin.offset(8, 0, 0));
            movePlayer(player, origin.offset(-10, 0, 0));
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            npc.setNextLiveFixtureAckStatus("bmc:setup="
                + origin.getX() + "," + origin.getY() + "," + origin.getZ());
        } catch (RuntimeException error) {
            cleanup(player, npc, MATERIAL_SUITE, false);
            throw error;
        }
    }

    private static void inspectMaterialChain(ServerPlayer player, CodexNpcEntity npc) {
        FixtureContext context = requireContext(player, npc, MATERIAL_SUITE, "survival");
        observeMaterialState(context.marker(), npc);
        CompoundTag state = context.marker().getPersistentData().getCompound(STATE_KEY);
        int matching = 0;
        int wrong = 0;
        for (int index = 0; index < MATERIAL_RELATIVE.size(); index++) {
            BlockState block = context.level().getBlockState(context.origin().offset(MATERIAL_RELATIVE.get(index)));
            if (block.is(MATERIAL_EXPECTED.get(index))) matching++;
            else wrong++;
        }
        int finalDistanceMilli = (int) Math.ceil(Math.sqrt(
            npc.position().distanceToSqr(Vec3.atCenterOf(context.origin()))
        ) * 1_000.0D);
        String taskId = materialTaskToken(state.getString("MaterialTaskId"));
        int[] values = {
            MATERIAL_EXPECTED.size(), matching, wrong,
            countBlocks(context.level(), state.getLongArray("MaterialLogs"), Blocks.OAK_LOG),
            countBlocks(context.level(), state.getLongArray("MaterialStone"), Blocks.STONE),
            countBlocks(context.level(), state.getLongArray("MaterialSand"), Blocks.SAND),
            countBlocks(context.level(), state.getLongArray("MaterialCoal"), Blocks.COAL_ORE),
            state.getInt("MaterialLogBreaks"),
            state.getInt("MaterialStoneBreaks"),
            state.getInt("MaterialSandBreaks"),
            state.getInt("MaterialCoalBreaks"),
            state.getInt("MaterialTablePlacements"),
            state.getInt("MaterialFurnacePlacements"),
            bit(state.getBoolean("SawLitFurnace")),
            bit(state.getBoolean("SawMaterialLogs")),
            bit(state.getBoolean("SawMaterialPlanks")),
            bit(state.getBoolean("SawMaterialCobblestone")),
            bit(state.getBoolean("SawMaterialSand")),
            bit(state.getBoolean("SawMaterialGlass")),
            bit(state.getBoolean("SawMaterialCoal")),
            bit(state.getBoolean("SawMaterialStick")),
            bit(state.getBoolean("SawMaterialTorch")),
            bit(state.getBoolean("SawMaterialPane")),
            bit(state.getBoolean("SawWoodenPickaxe")),
            bit(state.getBoolean("SawActiveBuild")),
            state.getInt("MaterialTaskIdChanges"),
            state.getInt("MaterialMaxDistanceMilli"),
            finalDistanceMilli,
            state.getInt("UnexpectedBreaks"),
            state.getInt("UnknownWorldEdits"),
            state.getInt("RemoteBreakCount"),
            state.getInt("LosViolationCount"),
            state.getInt("SyncViolationCount"),
            state.getInt("MaxTouchMilli"),
            bit(state.getBoolean("SawSandInFurnace")),
            bit(state.getBoolean("SawFurnaceFuel")),
            bit(state.getBoolean("SawMaterialTable")),
            bit(state.getBoolean("SawMaterialFurnace"))
        };
        StringBuilder status = new StringBuilder("bmc:i=");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) status.append(',');
            status.append(values[index]);
        }
        status.append(",task=").append(taskId);
        npc.setNextLiveFixtureAckStatus(status.toString());
    }

    private static String materialTaskToken(String taskId) {
        if (taskId == null || taskId.isBlank()) return "none";
        String compact = taskId.replace("-", "");
        if (compact.length() < 16 || !compact.matches("[0-9a-fA-F]+")) return "invalid";
        return compact.substring(0, 16).toLowerCase(java.util.Locale.ROOT);
    }

    private static void observeMaterialState(ArmorStand marker, CodexNpcEntity npc) {
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        int distanceMilli = (int) Math.ceil(Math.sqrt(
            npc.position().distanceToSqr(Vec3.atCenterOf(origin))
        ) * 1_000.0D);
        state.putInt("MaterialMaxDistanceMilli", Math.max(
            state.getInt("MaterialMaxDistanceMilli"), distanceMilli
        ));

        String activeTaskId = npc.tasks().activeTaskId();
        if (activeTaskId != null && !activeTaskId.isBlank()) {
            String previous = state.getString("MaterialTaskId");
            if (previous.isBlank()) state.putString("MaterialTaskId", activeTaskId);
            else if (!previous.equals(activeTaskId)) {
                state.putInt("MaterialTaskIdChanges", state.getInt("MaterialTaskIdChanges") + 1);
            }
            if (!npc.tasks().activeBuildDiagnosticForFixture().isBlank()) {
                state.putBoolean("SawActiveBuild", true);
            }
        }

        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(ItemTags.LOGS)) state.putBoolean("SawMaterialLogs", true);
            if (stack.is(Items.OAK_PLANKS)) state.putBoolean("SawMaterialPlanks", true);
            if (stack.is(Items.COBBLESTONE)) state.putBoolean("SawMaterialCobblestone", true);
            if (stack.is(Items.SAND)) state.putBoolean("SawMaterialSand", true);
            if (stack.is(Items.GLASS)) state.putBoolean("SawMaterialGlass", true);
            if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) state.putBoolean("SawMaterialCoal", true);
            if (stack.is(Items.STICK)) state.putBoolean("SawMaterialStick", true);
            if (stack.is(Items.TORCH)) state.putBoolean("SawMaterialTorch", true);
            if (stack.is(Items.GLASS_PANE)) state.putBoolean("SawMaterialPane", true);
            if (stack.is(Items.WOODEN_PICKAXE)) state.putBoolean("SawWoodenPickaxe", true);
        }

        ServerLevel level = (ServerLevel) marker.level();
        for (long packed : state.getLongArray("ModifiedBlocks")) {
            BlockPos position = BlockPos.of(packed);
            BlockState block = level.getBlockState(position);
            if (block.is(Blocks.CRAFTING_TABLE)) state.putBoolean("SawMaterialTable", true);
            if (!block.is(Blocks.FURNACE)) continue;
            state.putBoolean("SawMaterialFurnace", true);
            if (block.hasProperty(BlockStateProperties.LIT) && block.getValue(BlockStateProperties.LIT)) {
                state.putBoolean("SawLitFurnace", true);
            }
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) continue;
            if (furnace.getItem(0).is(Items.SAND)) state.putBoolean("SawSandInFurnace", true);
            if (!furnace.getItem(1).isEmpty()) state.putBoolean("SawFurnaceFuel", true);
            if (furnace.getItem(2).is(Items.GLASS)) state.putBoolean("SawMaterialGlass", true);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static FixtureContext beginFixture(
        ServerPlayer player,
        CodexNpcEntity npc,
        String suite,
        String scenario,
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
        state.putInt("Version", 2);
        state.putString("Suite", suite);
        state.putString("Scenario", scenario);
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
        state.putDouble("PlayerStartX", player.getX());
        state.putDouble("PlayerStartY", player.getY());
        state.putDouble("PlayerStartZ", player.getZ());
        state.putFloat("PlayerStartYaw", player.getYRot());
        state.putFloat("PlayerStartPitch", player.getXRot());
        state.putDouble("PlayerMotionX", player.getDeltaMovement().x);
        state.putDouble("PlayerMotionY", player.getDeltaMovement().y);
        state.putDouble("PlayerMotionZ", player.getDeltaMovement().z);
        state.putFloat("PlayerFallDistance", player.fallDistance);
        saveRespawn(player, state);
        saveInventory(npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            restoreInventory(npc, state);
            throw new IllegalStateException("Fixture marker was rejected");
        }
        return new FixtureContext(marker, state, level, origin);
    }

    private static void cleanup(
        ServerPlayer player,
        CodexNpcEntity npc,
        String requestedSuite,
        boolean report
    ) {
        requireIdle(npc);
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) {
            if (report) npc.setStatus(prefix(requestedSuite) + ":cleanup none");
            return;
        }
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        String actualSuite = state.getString("Suite");
        if (MATERIAL_SUITE.equals(actualSuite) && state.getInt("UnknownWorldEdits") > 0) {
            if (report) npc.setNextLiveFixtureAckStatus("bmc:cleanup=conflict,"
                + state.getInt("UnknownWorldEdits"));
            throw new IllegalStateException("Build material fixture cleanup found unknown world edits");
        }
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("Dimension"))
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Fixture dimension is unavailable");

        clearInventory(npc);
        restoreInventory(npc, state);
        restoreRespawn(player, state);
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
        if (state.getInt("Version") >= 2 && state.contains("PlayerStartX", Tag.TAG_DOUBLE)) {
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
        }

        int conflicts = 0;
        long[] modified = state.getLongArray("ModifiedBlocks");
        for (int index = modified.length - 1; index >= 0; index--) {
            BlockPos position = BlockPos.of(modified[index]);
            BlockState current = level.getBlockState(position);
            if (current.isAir()) continue;
            if (!isFixtureBlock(current.getBlock())
                && !isRecordedBuildFixtureBlock(state, position, current.getBlock())) {
                conflicts++;
                continue;
            }
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
        }
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(origin).inflate(48.0D, 16.0D, 48.0D),
            entity -> isFixtureOutput(entity.getItem()) || isRecordedBuildFixtureOutput(state, entity.getItem())
        )) item.discard();

        if (conflicts > 0) {
            npc.setStatus(prefix(actualSuite) + ":cleanup conflicts=" + conflicts);
            marker.getPersistentData().put(STATE_KEY, state);
            throw new IllegalStateException("Fixture cleanup found unexpected blocks: " + conflicts);
        }
        marker.discard();
        if (report) npc.setStatus(prefix(actualSuite) + ":cleanup restored");
    }

    private static FixtureContext requireContext(
        ServerPlayer player,
        CodexNpcEntity npc,
        String suite,
        String scenario
    ) {
        ArmorStand marker = findMarker(player, npc);
        if (marker == null) throw new IllegalStateException("Fixture has not been set up");
        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        if (!suite.equals(state.getString("Suite")) || !scenario.equals(state.getString("Scenario"))) {
            throw new IllegalStateException("Fixture scenario mismatch");
        }
        ResourceKey<Level> dimension = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.parse(state.getString("Dimension"))
        );
        ServerLevel level = player.getServer().getLevel(dimension);
        if (level == null) throw new IllegalStateException("Fixture dimension is unavailable");
        return new FixtureContext(marker, state, level, BlockPos.of(state.getLong("Origin")));
    }

    private static Site findSite(
        ServerLevel level,
        BlockPos near,
        int minOffsetX,
        int maxOffsetX,
        int minOffsetZ,
        int maxOffsetZ,
        int clearance
    ) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int centerX = near.getX() + dx;
                int centerZ = near.getZ() + dz;
                int maximumSurface = level.getMinBuildHeight();
                boolean withinBorder = true;
                for (int x = minOffsetX; x <= maxOffsetX && withinBorder; x++) {
                    for (int z = minOffsetZ; z <= maxOffsetZ; z++) {
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
                int platformY = maximumSurface + clearance;
                if (platformY + 10 >= level.getMaxBuildHeight()) continue;
                BlockPos origin = new BlockPos(centerX, platformY + 1, centerZ);
                boolean clear = true;
                for (int x = minOffsetX; x <= maxOffsetX && clear; x++) {
                    for (int z = minOffsetZ; z <= maxOffsetZ && clear; z++) {
                        for (int y = -1; y <= 7; y++) {
                            BlockPos position = origin.offset(x, y, z);
                            if (!level.getBlockState(position).isAir() || !level.getFluidState(position).isEmpty()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                }
                if (!clear) continue;
                AABB volume = new AABB(
                    origin.getX() + minOffsetX,
                    origin.getY() - 1,
                    origin.getZ() + minOffsetZ,
                    origin.getX() + maxOffsetX + 1,
                    origin.getY() + 8,
                    origin.getZ() + maxOffsetZ + 1
                );
                if (!level.getEntities(null, volume).isEmpty()) continue;
                return new Site(origin);
            }
        }
        throw new IllegalStateException("No isolated fixture site was found");
    }

    private static List<BlockPos> platformPositions(
        BlockPos origin,
        int minX,
        int maxX,
        int minZ,
        int maxZ
    ) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) result.add(origin.offset(x, -1, z));
        }
        return result;
    }

    private static void placePlatform(
        ServerLevel level,
        BlockPos origin,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        Block block
    ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setFixtureBlock(level, origin.offset(x, -1, z), block.defaultBlockState());
            }
        }
    }

    private static List<BlockPos> verticalLogs(BlockPos root, int count) {
        List<BlockPos> result = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) result.add(root.above(offset));
        return result;
    }

    private static List<BlockPos> rectangle(BlockPos corner, int width, int depth) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) result.add(corner.offset(x, 0, z));
        }
        return result;
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

    private static void setFixtureBlock(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Fixture block could not be placed at " + position.toShortString());
        }
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
    }

    private static void movePlayer(ServerPlayer player, BlockPos position) {
        player.connection.teleport(
            position.getX() + 0.5D,
            position.getY(),
            position.getZ() + 0.5D,
            player.getYRot(),
            player.getXRot()
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }

    private static void requireIdle(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank()) || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("Cancel and finish NPC tasks before changing a live fixture");
        }
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

    private static int countLogs(ServerLevel level, long[] positions) {
        int count = 0;
        for (long value : positions) {
            if (level.getBlockState(BlockPos.of(value)).is(net.minecraft.tags.BlockTags.LOGS)) count++;
        }
        return count;
    }

    private static int countBlocks(ServerLevel level, long[] positions, Block block) {
        int count = 0;
        for (long value : positions) if (level.getBlockState(BlockPos.of(value)).is(block)) count++;
        return count;
    }

    private static int countNpcLogs(CodexNpcEntity npc) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.is(ItemTags.LOGS)) count += stack.getCount();
        }
        return count;
    }

    private static boolean isFixtureBlock(Block block) {
        return Set.of(
            Blocks.STONE,
            Blocks.DIRT,
            Blocks.CRAFTING_TABLE,
            Blocks.OAK_LOG,
            Blocks.OAK_LEAVES,
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
            Blocks.MANGROVE_PRESSURE_PLATE,
            Blocks.DARK_OAK_PLANKS,
            Blocks.DARK_OAK_STAIRS,
             Blocks.DARK_OAK_SLAB,
             Blocks.DARK_OAK_FENCE,
             Blocks.DARK_OAK_PRESSURE_PLATE,
             Blocks.SAND,
             Blocks.COAL_ORE,
             Blocks.COBBLESTONE,
             Blocks.GLASS,
             Blocks.GLASS_PANE,
             Blocks.TORCH,
             Blocks.FURNACE
         ).contains(block);
    }

    private static boolean isFixtureOutput(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(ItemTags.LOGS)
             || stack.is(Items.OAK_SAPLING)
             || stack.is(Items.STICK)
             || stack.is(Items.APPLE)
             || stack.is(Items.COBBLESTONE)
             || stack.is(Items.SAND)
             || stack.is(Items.COAL)
             || stack.is(Items.CHARCOAL)
             || stack.is(Items.OAK_PLANKS)
             || stack.is(Items.GLASS)
             || stack.is(Items.GLASS_PANE)
             || stack.is(Items.TORCH)
             || stack.is(Items.CRAFTING_TABLE)
             || stack.is(Items.FURNACE)
             || stack.is(Items.WOODEN_PICKAXE)
             || stack.is(Items.STONE_PICKAXE)
             || MIXED_EXPECTED.stream().anyMatch(block -> stack.is(block.asItem()))
             || CHAIN_EXPECTED.stream().anyMatch(block -> stack.is(block.asItem()));
    }

    private static boolean isRecordedBuildFixtureBlock(CompoundTag state, BlockPos position, Block block) {
        if (!BUILD_SUITE.equals(state.getString("Suite"))) return false;
        List<String> expected = expectedBlockIds(state);
        List<BlockPos> relative = expected.size() == BUILD_RELATIVE.size() ? BUILD_RELATIVE : MASONRY_RELATIVE;
        if (expected.size() != relative.size()) return false;
        BlockPos origin = BlockPos.of(state.getLong("Origin"));
        ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(block);
        if (actual == null) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (origin.offset(relative.get(index)).equals(position)
                && expected.get(index).equals(actual.toString())) return true;
        }
        return false;
    }

    private static boolean isRecordedBuildFixtureOutput(CompoundTag state, ItemStack stack) {
        if (stack.isEmpty() || !BUILD_SUITE.equals(state.getString("Suite"))) return false;
        ResourceLocation actual = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (actual == null) return false;
        return expectedBlockIds(state).contains(actual.toString())
            || actual.toString().equals(state.getString("FamilySourceId"));
    }

    private static List<String> expectedBlockIds(CompoundTag state) {
        String encoded = state.getString("ExpectedBlockIds");
        if (encoded.isBlank()) return List.of();
        return List.of(encoded.split("\\|", -1)).stream()
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static int numericModeSuffix(String mode, String prefix) {
        if (!mode.startsWith(prefix)) return -1;
        String suffix = mode.substring(prefix.length());
        if (suffix.isEmpty() || suffix.length() > 4) return -1;
        for (int index = 0; index < suffix.length(); index++) {
            if (!Character.isDigit(suffix.charAt(index))) return -1;
        }
        return Integer.parseInt(suffix);
    }

    private static Set<BlockPos> blockSet(long[] values) {
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (long value : values) result.add(BlockPos.of(value));
        return Set.copyOf(result);
    }

    private static long[] longs(Iterable<BlockPos> positions) {
        List<Long> values = new ArrayList<>();
        for (BlockPos position : positions) values.add(position.asLong());
        long[] result = new long[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    private static String prefix(String suite) {
        if (BUILD_SUITE.equals(suite)) return "build-fixture";
        if (MATERIAL_SUITE.equals(suite)) return "bmc";
        return "tree-fixture";
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private record Site(BlockPos origin) {
    }

    private record FixtureContext(
        ArmorStand marker,
        CompoundTag state,
        ServerLevel level,
        BlockPos origin
    ) {
    }
}
