package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Conservative loaded-chunk-only classifier for harvestable natural trees. */
@SuppressWarnings("deprecation")
final class NaturalTreeScanner {
    record Cluster(Set<BlockPos> logs, boolean natural) {}

    private NaturalTreeScanner() {}

    static Cluster inspect(ServerLevel level, BlockPos seed, int maximumLogs) {
        BlockState seedState = level.getBlockState(seed);
        if (!isNaturalTrunk(seedState)) return new Cluster(Set.of(), false);
        String family = trunkFamily(seedState);
        if (family.isBlank()) return new Cluster(Set.of(), false);

        int limit = Math.max(3, maximumLogs);
        Set<BlockPos> logs = new LinkedHashSet<>();
        Set<BlockPos> visited = new LinkedHashSet<>();
        Deque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(seed.immutable());
        boolean verticalPair = false;
        boolean overflow = false;
        int minX = seed.getX();
        int maxX = seed.getX();
        int minY = seed.getY();
        int maxY = seed.getY();
        int minZ = seed.getZ();
        int maxZ = seed.getZ();
        while (!frontier.isEmpty()) {
            BlockPos position = frontier.removeFirst();
            if (!visited.add(position) || !level.hasChunkAt(position)) continue;
            BlockState state = level.getBlockState(position);
            if (!family.equals(trunkFamily(state))) continue;
            if (logs.size() >= limit) {
                overflow = true;
                continue;
            }
            logs.add(position.immutable());
            minX = Math.min(minX, position.getX());
            maxX = Math.max(maxX, position.getX());
            minY = Math.min(minY, position.getY());
            maxY = Math.max(maxY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxZ = Math.max(maxZ, position.getZ());
            if (family.equals(trunkFamily(level.getBlockState(position.above())))
                || family.equals(trunkFamily(level.getBlockState(position.below())))) {
                verticalPair = true;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) frontier.addLast(position.offset(dx, dy, dz));
                    }
                }
            }
        }
        int rootedBases = 0;
        for (BlockPos log : logs) {
            if (!logs.contains(log.below()) && isNaturalSupport(level.getBlockState(log.below()))) rootedBases++;
        }
        boolean canopy = hasCanopyNearTop(level, logs, family, maxY);
        return new Cluster(
            Set.copyOf(logs),
            BuildMaterialPalettePolicy.naturalTreeShape(
                logs.size(),
                verticalPair,
                canopy,
                rootedBases,
                maxX - minX,
                maxZ - minZ,
                maxY - minY,
                overflow
            )
        );
    }

    static List<BlockPos> orderedTargets(Cluster cluster, BlockPos seed) {
        if (!cluster.natural() || !cluster.logs().contains(seed)) return List.of();
        List<BlockPos> ordered = new ArrayList<>(cluster.logs().size());
        ordered.add(seed.immutable());
        cluster.logs().stream()
            .filter(position -> !position.equals(seed))
            .sorted(Comparator
                .comparingInt((BlockPos position) -> position.getY())
                .thenComparingDouble((BlockPos position) -> position.distSqr(seed)))
            .map(BlockPos::immutable)
            .forEachOrdered(ordered::add);
        return List.copyOf(ordered);
    }

    private static boolean isNaturalTrunk(BlockState state) {
        if (!state.is(BlockTags.LOGS)) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return BuildMaterialPalettePolicy.naturalTrunkId(id);
    }

    private static String trunkFamily(BlockState state) {
        if (!isNaturalTrunk(state)) return "";
        return BuildMaterialPalettePolicy.naturalTrunkFamily(
            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
        );
    }

    private static boolean isNaturalSupport(BlockState state) {
        return state.is(BlockTags.DIRT)
            || state.is(BlockTags.NYLIUM)
            || state.is(Blocks.MOSS_BLOCK)
            || state.is(Blocks.MANGROVE_ROOTS)
            || state.is(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    private static boolean hasCanopyNearTop(
        ServerLevel level,
        Set<BlockPos> logs,
        String family,
        int highestLogY
    ) {
        for (BlockPos log : logs) {
            if (log.getY() < highestLogY - 1 || !hasCanopyNearby(level, log, family)) continue;
            return true;
        }
        return false;
    }

    private static boolean hasCanopyNearby(ServerLevel level, BlockPos trunk, String family) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 4; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    probe.set(trunk.getX() + dx, trunk.getY() + dy, trunk.getZ() + dz);
                    if (!level.hasChunkAt(probe)) continue;
                    BlockState state = level.getBlockState(probe);
                    if (state.getBlock() instanceof LeavesBlock
                        && state.hasProperty(LeavesBlock.PERSISTENT)
                        && state.hasProperty(LeavesBlock.DISTANCE)
                        && BuildMaterialPalettePolicy.naturalLeaf(
                            state.getValue(LeavesBlock.PERSISTENT),
                            state.getValue(LeavesBlock.DISTANCE)
                        )) return true;
                    String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    if (matchesNetherCanopy(family, path)) return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesNetherCanopy(String family, String canopyPath) {
        return (family.endsWith(":crimson") && canopyPath.equals("nether_wart_block"))
            || (family.endsWith(":warped") && canopyPath.equals("warped_wart_block"));
    }
}
