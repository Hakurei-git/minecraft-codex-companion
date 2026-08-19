package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@SuppressWarnings("deprecation")
final class NpcHomeStorage {
    private static final long HOUSE_SCAN_CACHE_TICKS = 40L;
    private static final Map<UUID, CachedHome> HOME_CACHE = new ConcurrentHashMap<>();

    record Bounds(BlockPos min, BlockPos max) {
        Bounds {
            min = min.immutable();
            max = max.immutable();
        }
    }

    record Home(
        ResourceKey<Level> dimension,
        BlockPos position,
        boolean temporary,
        Bounds bounds,
        String boundarySource,
        double confidence
    ) {
        Home(ResourceKey<Level> dimension, BlockPos position, boolean temporary) {
            this(dimension, position, temporary, null, "radius-fallback", 0.0D);
        }
    }
    record BedPlacement(BlockPos foot, Direction facing) {}
    private record CachedHome(ResourceKey<Level> dimension, BlockPos anchor, long gameTime, Home home) {}

    private NpcHomeStorage() {}

    static Home resolve(ServerPlayer owner) {
        BlockPos respawn = owner.getRespawnPosition();
        if (respawn != null) {
            ServerLevel level = owner.getServer() == null ? null : owner.getServer().getLevel(owner.getRespawnDimension());
            BlockPos anchor = level == null ? respawn.immutable() : normalizeBedAnchor(level, respawn);
            return level == null
                ? homeWithBoundary(owner.getRespawnDimension(), anchor, false, null)
                : cachedHome(owner, level, owner.getRespawnDimension(), anchor, false);
        }
        MinecraftServer server = owner.getServer();
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld != null) return cachedHome(owner, overworld, Level.OVERWORLD, overworld.getSharedSpawnPos().immutable(), true);
        return cachedHome(owner, owner.serverLevel(), owner.level().dimension(), owner.blockPosition().immutable(), true);
    }

    private static Home cachedHome(
        ServerPlayer owner,
        ServerLevel level,
        ResourceKey<Level> dimension,
        BlockPos anchor,
        boolean temporary
    ) {
        long now = level.getGameTime();
        CachedHome cached = HOME_CACHE.get(owner.getUUID());
        if (cached != null
            && cached.dimension().equals(dimension)
            && cached.anchor().equals(anchor)
            && now >= cached.gameTime()
            && now - cached.gameTime() <= HOUSE_SCAN_CACHE_TICKS) return cached.home();
        Home home = homeWithBoundary(dimension, anchor, temporary, scanHouseBoundary(level, anchor));
        HOME_CACHE.put(owner.getUUID(), new CachedHome(dimension, anchor.immutable(), now, home));
        return home;
    }

    private static Home homeWithBoundary(ResourceKey<Level> dimension, BlockPos anchor, boolean temporary, HouseBoundary boundary) {
        if (boundary != null) return new Home(dimension, anchor, temporary, boundary.bounds(), "enclosed-scan", boundary.confidence());
        return new Home(dimension, anchor, temporary, radiusFallback(anchor), "radius-fallback", 0.15D);
    }

    private static Bounds radiusFallback(BlockPos anchor) {
        return new Bounds(
            new BlockPos(anchor.getX() - HomeStoragePolicy.DEFAULT_RADIUS, anchor.getY() - 12, anchor.getZ() - HomeStoragePolicy.DEFAULT_RADIUS),
            new BlockPos(anchor.getX() + HomeStoragePolicy.DEFAULT_RADIUS, anchor.getY() + 12, anchor.getZ() + HomeStoragePolicy.DEFAULT_RADIUS)
        );
    }

    private static BlockPos normalizeBedAnchor(ServerLevel level, BlockPos respawn) {
        BlockPos candidate = respawn.immutable();
        BlockState state = level.getBlockState(candidate);
        if (state.getBlock() instanceof BedBlock) {
            return state.getValue(BedBlock.PART) == BedPart.HEAD
                ? candidate.relative(state.getValue(BedBlock.FACING).getOpposite()).immutable()
                : candidate;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = candidate.relative(direction);
            BlockState adjacentState = level.getBlockState(adjacent);
            if (adjacentState.getBlock() instanceof BedBlock) {
                return adjacentState.getValue(BedBlock.PART) == BedPart.HEAD
                    ? adjacent.relative(adjacentState.getValue(BedBlock.FACING).getOpposite()).immutable()
                    : adjacent.immutable();
            }
        }
        return candidate;
    }

    private record HouseBoundary(Bounds bounds, double confidence, int cells) {}

    /** Bounded indoor-space scan for player-built houses. Never scans an open world indefinitely. */
    private static HouseBoundary scanHouseBoundary(ServerLevel level, BlockPos anchor) {
        final int horizontal = 32;
        final int vertical = 12;
        final int maxCells = 8192;
        ArrayList<BlockPos> starts = new ArrayList<>();
        for (int y = -1; y <= 2; y++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos position = anchor.offset(direction.getStepX(), y, direction.getStepZ());
                if (isIndoorCell(level, position)) starts.add(position.immutable());
            }
            BlockPos position = anchor.above(y + 1);
            if (isIndoorCell(level, position)) starts.add(position.immutable());
        }
        if (starts.isEmpty()) return null;
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        for (BlockPos start : starts) {
            if (visited.add(start)) queue.add(start);
        }
        int minX = anchor.getX(), minY = anchor.getY(), minZ = anchor.getZ();
        int maxX = anchor.getX(), maxY = anchor.getY() + 1, maxZ = anchor.getZ();
        int maximumRoofY = anchor.getY() + 2;
        while (!queue.isEmpty() && visited.size() <= maxCells) {
            BlockPos position = queue.removeFirst();
            minX = Math.min(minX, position.getX()); maxX = Math.max(maxX, position.getX());
            minY = Math.min(minY, position.getY()); maxY = Math.max(maxY, position.getY());
            minZ = Math.min(minZ, position.getZ()); maxZ = Math.max(maxZ, position.getZ());
            maximumRoofY = Math.max(maximumRoofY, position.getY() + roofDistance(level, position));
            for (Direction direction : Direction.values()) {
                BlockPos next = position.relative(direction);
                if (Math.abs(next.getX() - anchor.getX()) > horizontal
                    || Math.abs(next.getZ() - anchor.getZ()) > horizontal
                    || Math.abs(next.getY() - anchor.getY()) > vertical
                    || visited.contains(next)
                    || !isIndoorCell(level, next)) continue;
                visited.add(next);
                queue.addLast(next);
            }
        }
        if (visited.size() < 2 || visited.size() > maxCells) return null;
        double confidence = Math.min(0.98D, 0.55D + Math.min(0.4D, visited.size() / 1024.0D));
        // The traversal represents walkable interior cells. Expand through the
        // floor/walls and up to the detected roof so protection and indoor
        // placement checks describe the physical player-built house, not just
        // a thin carpet of standing positions.
        return new HouseBoundary(new Bounds(
            new BlockPos(minX - 1, minY - 1, minZ - 1),
            new BlockPos(maxX + 1, Math.max(maxY + 1, maximumRoofY), maxZ + 1)
        ), confidence, visited.size());
    }

    private static boolean isIndoorCell(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) return false;
        BlockState state = level.getBlockState(position);
        if (!state.getCollisionShape(level, position).isEmpty() || !level.getFluidState(position).isEmpty()) return false;
        BlockPos floor = position.below();
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return false;
        if (level.canSeeSky(position)) return false;
        return roofDistance(level, position) > 0;
    }

    private static int roofDistance(ServerLevel level, BlockPos position) {
        for (int y = 1; y <= 8; y++) {
            BlockPos roof = position.above(y);
            if (!level.getBlockState(roof).getCollisionShape(level, roof).isEmpty()) return y;
        }
        return -1;
    }

    static List<BlockPos> findContainers(ServerLevel level, Home home, int requestedRadius) {
        if (!home.dimension().equals(level.dimension())) return List.of();
        int radius = HomeStoragePolicy.clampRadius(requestedRadius);
        BlockPos origin = home.position();
        List<BlockPos> result = new ArrayList<>();
        for (int y = -8; y <= 8; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    if (!level.hasChunkAt(position)) continue;
                    BlockEntity blockEntity = level.getBlockEntity(position);
                    if (isStorageContainer(level, position, blockEntity)) {
                        result.add(position.immutable());
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(position -> position.distSqr(origin)));
        return List.copyOf(result);
    }

    private static boolean isStorageContainer(ServerLevel level, BlockPos position, BlockEntity blockEntity) {
        if (!(blockEntity instanceof Container) || blockEntity instanceof AbstractFurnaceBlockEntity) return false;
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).toString();
        return id.endsWith("chest") || id.endsWith("barrel") || id.endsWith("shulker_box");
    }

    static BlockPos findCraftingTable(ServerLevel level, Home home, int requestedRadius) {
        if (!home.dimension().equals(level.dimension())) return null;
        int radius = HomeStoragePolicy.clampRadius(requestedRadius);
        BlockPos origin = home.position();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int y = -8; y <= 8; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos position = origin.offset(x, y, z);
                    if (!level.hasChunkAt(position) || !level.getBlockState(position).is(Blocks.CRAFTING_TABLE)) continue;
                    double distance = position.distSqr(origin);
                    if (distance < bestDistance) {
                        best = position.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Chooses a non-destructive placement cell.  The actual placement still
     * goes through the FakePlayer interaction proxy so spawn protection,
     * claims and Forge cancellation hooks remain authoritative.
     */
    static BlockPos findSafePlacement(ServerLevel level, Home home, BlockPos preferredAnchor, int requestedRadius) {
        return findSafePlacement(level, home, preferredAnchor, requestedRadius, ignored -> true);
    }

    static BlockPos findSafePlacement(
        ServerLevel level,
        Home home,
        BlockPos preferredAnchor,
        int requestedRadius,
        Predicate<BlockPos> allowedPosition
    ) {
        if (!home.dimension().equals(level.dimension())) return null;
        int radius = Math.max(1, Math.min(12, requestedRadius));
        BlockPos origin = preferredAnchor == null ? home.position() : preferredAnchor;
        List<BlockPos> candidates = new ArrayList<>();
        for (int y = -4; y <= 4; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && z == 0 && y == 0) continue;
                    BlockPos position = origin.offset(x, y, z);
                    if (!level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) continue;
                    if (position.equals(home.position())) continue;
                    if (!allowedPosition.test(position)) continue;
                    BlockState state = level.getBlockState(position);
                    BlockPos support = position.below();
                    if (!state.canBeReplaced() || !level.getFluidState(position).isEmpty()) continue;
                    if (!level.getBlockState(support).isFaceSturdy(level, support, net.minecraft.core.Direction.UP)) continue;
                    candidates.add(position.immutable());
                }
            }
        }
        candidates.sort(Comparator
            .comparingDouble((BlockPos position) -> position.distSqr(origin))
            .thenComparingDouble(position -> position.distSqr(home.position())));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static BedPlacement findSafeBedPlacement(
        ServerLevel level,
        Home home,
        BlockPos preferredAnchor,
        int requestedRadius,
        Predicate<BlockPos> allowedFoot
    ) {
        if (!home.dimension().equals(level.dimension())) return null;
        int radius = Math.max(2, Math.min(12, requestedRadius));
        BlockPos origin = preferredAnchor == null ? home.position() : preferredAnchor;
        List<BedPlacement> candidates = new ArrayList<>();
        Direction[] directions = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
        for (int y = -4; y <= 4; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos foot = origin.offset(x, y, z);
                    if (foot.equals(home.position()) || !allowedFoot.test(foot)) continue;
                    for (Direction facing : directions) {
                        BlockPos head = foot.relative(facing);
                        if (head.equals(home.position())) continue;
                        if (!isSafeBedCell(level, foot) || !isSafeBedCell(level, head)) continue;
                        candidates.add(new BedPlacement(foot.immutable(), facing));
                    }
                }
            }
        }
        candidates.sort(Comparator
            .comparingDouble((BedPlacement placement) -> placement.foot().distSqr(origin))
            .thenComparingDouble(placement -> placement.foot().distSqr(home.position()))
            .thenComparing(placement -> placement.facing().getName()));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static boolean isSafeBedCell(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) return false;
        BlockState state = level.getBlockState(position);
        BlockPos support = position.below();
        return state.canBeReplaced()
            && level.getFluidState(position).isEmpty()
            && level.getBlockState(support).isFaceSturdy(level, support, Direction.UP);
    }
}
