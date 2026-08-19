package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;

final class BuildPlacementPolicy {
    private BuildPlacementPolicy() {}

    static String materialItemId(String blockId) {
        return switch (blockId) {
            case "minecraft:water" -> "minecraft:water_bucket";
            case "minecraft:lava" -> "minecraft:lava_bucket";
            default -> blockId;
        };
    }

    static boolean isFluidSource(String blockId) {
        return "minecraft:water".equals(blockId) || "minecraft:lava".equals(blockId);
    }

    static boolean isClickableSupport(boolean air, boolean replaceable, boolean collisionEmpty) {
        return !air && !replaceable && !collisionEmpty;
    }

    static int supportScore(boolean clickable, boolean sturdyOnPlacementFace) {
        if (!clickable) return 0;
        return sturdyOnPlacementFace ? 2 : 1;
    }

    static int originY(String placement, int plannedY, int surfaceY, int verticalOffset) {
        return "companion".equals(placement) ? surfaceY + verticalOffset : plannedY;
    }

    static BlockPos surfaceProbe(String placement, BlockPos plannedOrigin, BlockPos placementAnchor) {
        return "companion".equals(placement) && placementAnchor != null
            ? placementAnchor
            : plannedOrigin;
    }

    static boolean clearsHome(BlockPos candidate, BlockPos home, int minimumHorizontalDistance) {
        if (candidate == null || home == null) return true;
        int distance = Math.max(0, minimumHorizontalDistance);
        long dx = (long) candidate.getX() - home.getX();
        long dz = (long) candidate.getZ() - home.getZ();
        return dx * dx + dz * dz >= (long) distance * distance;
    }

    /** Conservative rectangle check for a remembered player-built house. */
    static boolean clearsHome(BlockPos candidate, NpcHomeStorage.Bounds bounds, int margin) {
        if (candidate == null || bounds == null) return true;
        int padding = Math.max(0, margin);
        return candidate.getX() < bounds.min().getX() - padding
            || candidate.getX() > bounds.max().getX() + padding
            || candidate.getZ() < bounds.min().getZ() - padding
            || candidate.getZ() > bounds.max().getZ() + padding;
    }

    /**
     * A failed outdoor-site lookup can leave a raw request origin in the
     * persisted task checkpoint. Revalidate while no blueprint block has been
     * committed; once construction has started the locked origin must never
     * move on a retry.
     */
    static boolean shouldResolveOutdoorSite(String sitePolicy, int completedBlueprintBlocks) {
        return "outdoor".equals(sitePolicy) && completedBlueprintBlocks <= 0;
    }

    static boolean terrainFits(int minimumSurface, int maximumSurface, int maximumDelta) {
        return minimumSurface != Integer.MAX_VALUE
            && maximumSurface != Integer.MIN_VALUE
            && maximumSurface - minimumSurface <= Math.max(0, maximumDelta);
    }
}
