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
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

@SuppressWarnings("deprecation")
final class NpcHomeStorage {
    record Home(ResourceKey<Level> dimension, BlockPos position, boolean temporary) {}
    record BedPlacement(BlockPos foot, Direction facing) {}

    private NpcHomeStorage() {}

    static Home resolve(ServerPlayer owner) {
        BlockPos respawn = owner.getRespawnPosition();
        if (respawn != null) return new Home(owner.getRespawnDimension(), respawn.immutable(), false);
        MinecraftServer server = owner.getServer();
        ServerLevel overworld = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (overworld != null) return new Home(Level.OVERWORLD, overworld.getSharedSpawnPos().immutable(), true);
        return new Home(owner.level().dimension(), owner.blockPosition().immutable(), true);
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
