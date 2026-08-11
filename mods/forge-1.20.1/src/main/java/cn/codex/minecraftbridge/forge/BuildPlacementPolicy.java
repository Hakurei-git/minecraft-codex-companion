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

    static int originY(String placement, int plannedY, int surfaceY, int verticalOffset) {
        return "companion".equals(placement) ? surfaceY + verticalOffset : plannedY;
    }

    static BlockPos surfaceProbe(String placement, BlockPos plannedOrigin, BlockPos placementAnchor) {
        return "companion".equals(placement) && placementAnchor != null
            ? placementAnchor
            : plannedOrigin;
    }
}
