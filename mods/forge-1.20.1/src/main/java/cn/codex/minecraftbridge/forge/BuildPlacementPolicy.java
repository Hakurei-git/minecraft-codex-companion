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

    /** A compound origin is locked into the persisted plan after its first successful validation. */
    static boolean shouldResolveHomeCompoundSite(
        String sitePolicy,
        int completedBlueprintBlocks,
        boolean placementAlreadyLocked
    ) {
        return "home-compound".equals(sitePolicy)
            && completedBlueprintBlocks <= 0
            && !placementAlreadyLocked;
    }

    /** Shortest horizontal edge-to-edge distance between two complete blueprint/facility bounds. */
    static double horizontalGap(NpcHomeStorage.Bounds left, NpcHomeStorage.Bounds right) {
        if (left == null || right == null) return Double.POSITIVE_INFINITY;
        long dx = left.max().getX() < right.min().getX()
            ? (long) right.min().getX() - left.max().getX()
            : right.max().getX() < left.min().getX()
                ? (long) left.min().getX() - right.max().getX()
                : 0;
        long dz = left.max().getZ() < right.min().getZ()
            ? (long) right.min().getZ() - left.max().getZ()
            : right.max().getZ() < left.min().getZ()
                ? (long) left.min().getZ() - right.max().getZ()
                : 0;
        return Math.hypot((double) dx, (double) dz);
    }

    static boolean insideCompoundRing(
        NpcHomeStorage.Bounds candidate,
        NpcHomeStorage.Bounds home,
        int minimumDistance,
        int maximumDistance
    ) {
        double distance = horizontalGap(candidate, home);
        return distance >= Math.max(0, minimumDistance)
            && distance <= Math.max(Math.max(0, minimumDistance), maximumDistance);
    }

    static boolean overlapsWithMargin(
        NpcHomeStorage.Bounds left,
        NpcHomeStorage.Bounds right,
        int margin
    ) {
        if (left == null || right == null) return false;
        long padding = Math.max(0, margin);
        return left.max().getX() >= (long) right.min().getX() - padding
            && left.min().getX() <= (long) right.max().getX() + padding
            && left.max().getZ() >= (long) right.min().getZ() - padding
            && left.min().getZ() <= (long) right.max().getZ() + padding;
    }

    /** Inclusive block-coordinate span guard; [0, 63] is 64 blocks, [0, 64] is not. */
    static boolean inclusiveSpanAtMost(int minimum, int maximum, int maximumSize) {
        return maximum >= minimum
            && maximumSize > 0
            && (long) maximum - minimum + 1L <= maximumSize;
    }

    /**
     * Protected infrastructure is immutable during compound construction. An
     * already exact matching block is skipped before this guard is reached;
     * even a same-ID state correction could rotate a workstation or alter a
     * redstone circuit that appeared after site discovery.
     */
    static boolean mayModifyCompoundTarget(
        String currentBlockId,
        String desiredBlockId,
        boolean protectedInfrastructure
    ) {
        return !protectedInfrastructure;
    }

    static boolean compoundLockMatches(
        String currentDimension,
        BlockPos restoredOrigin,
        String lockedDimension,
        BlockPos lockedOrigin
    ) {
        return currentDimension != null
            && !currentDimension.isBlank()
            && currentDimension.equals(lockedDimension)
            && restoredOrigin != null
            && restoredOrigin.equals(lockedOrigin);
    }

    static int maximumTerrainDelta(String preparationMode) {
        return "light".equals(preparationMode) ? 2 : 0;
    }

    /** Site discovery may clear plants/leaves, but never chooses occupied infrastructure. */
    static boolean mayUseCompoundVolumeCell(
        boolean air,
        boolean replaceable,
        boolean leaves,
        boolean fluid,
        boolean hasBlockEntity,
        boolean protectedInfrastructure,
        float destroySpeed
    ) {
        if (fluid || hasBlockEntity || protectedInfrastructure || destroySpeed < 0.0F) return false;
        return air || replaceable || leaves;
    }

    static boolean isProtectedCompoundInfrastructureId(String blockId) {
        if (blockId == null || blockId.isBlank()) return true;
        String path = blockId.contains(":") ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        return path.endsWith("_bed")
            || path.endsWith("_door")
            || path.endsWith("_trapdoor")
            || path.endsWith("_fence")
            || path.endsWith("_fence_gate")
            || path.endsWith("_sign")
            || path.endsWith("_wall_sign")
            || path.endsWith("_hanging_sign")
            || path.endsWith("_wall_hanging_sign")
            || path.endsWith("_button")
            || path.endsWith("_pressure_plate")
            || path.endsWith("_rail")
            || path.equals("rail")
            || path.contains("redstone")
            || path.contains("repeater")
            || path.contains("comparator")
            || path.contains("piston")
            || path.contains("observer")
            || path.contains("lever")
            || path.contains("hopper")
            || path.contains("dispenser")
            || path.contains("dropper")
            || path.contains("crafting_table")
            || path.contains("furnace")
            || path.contains("smoker")
            || path.contains("stonecutter")
            || path.contains("loom")
            || path.contains("smithing_table")
            || path.contains("cartography_table")
            || path.contains("fletching_table")
            || path.contains("grindstone")
            || path.contains("anvil")
            || path.contains("brewing_stand")
            || path.contains("enchanting_table")
            || path.contains("cauldron")
            || path.contains("composter")
            || path.contains("lectern")
            || path.contains("bookshelf")
            || path.contains("beehive")
            || path.contains("bee_nest")
            || path.contains("bell")
            || path.contains("jukebox")
            || path.contains("note_block")
            || path.contains("respawn_anchor")
            || path.contains("lodestone")
            || path.contains("beacon")
            || path.contains("chest")
            || path.contains("barrel")
            || path.contains("shulker_box")
            || path.contains("spawner")
            || path.endsWith("_crop")
            || path.equals("wheat")
            || path.equals("carrots")
            || path.equals("potatoes")
            || path.equals("beetroots")
            || path.equals("nether_wart")
            || path.equals("cocoa")
            || path.endsWith("_stem");
    }

    static boolean terrainFits(int minimumSurface, int maximumSurface, int maximumDelta) {
        return minimumSurface != Integer.MAX_VALUE
            && maximumSurface != Integer.MIN_VALUE
            && maximumSurface - minimumSurface <= Math.max(0, maximumDelta);
    }
}
