package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;

/** Deterministic geometry and safety limits for survival deep-mining tasks. */
final class DeepMiningPolicy {
    static final int REQUIRED_LADDERS = 32;
    static final int REQUIRED_TORCHES = 32;
    static final int REQUIRED_IRON_PICKAXES = 2;
    static final int REQUIRED_LOG_RESERVE = 64;
    static final int REQUIRED_FOOD_RESERVE = 8;
    static final int MIN_FOOD_RESERVE = 2;
    static final int MIN_PICKAXE_REMAINING_DURABILITY = 200;
    static final int TORCH_INTERVAL = 8;
    static final int BRANCH_LENGTH = 32;
    static final int BRANCH_SPACING = 3;
    static final int BRANCHES_PER_REGION = 8;
    static final int REGION_SPACING = 128;
    static final int ENTRY_HOME_CLEARANCE = 20;
    static final int ENTRY_PROBE_RADIUS = 12;
    static final double ENTRY_TELEPORT_DISTANCE = 32.0D;
    static final int ENTRY_TELEPORT_STALLED_TICKS = 20 * 8;
    static final int ENTRY_TARGET_TIMEOUT_TICKS = 20 * 12;
    static final int MIN_TORCH_RESERVE = 4;
    static final double CHECKPOINT_RESTORE_DISTANCE = 16.0D;
    static final int CHECKPOINT_RESTORE_VERTICAL_DELTA = 2;
    static final int RESOURCE_TARGET_TIMEOUT_TICKS = 20 * 15;

    private DeepMiningPolicy() {
    }

    static int targetY(String itemId) {
        return switch (normalize(itemId)) {
            // Coal is most common high in the overworld. A target above the
            // usual surface means the miner opens branches at its current
            // safe level instead of descending away from the coal layer.
            case "#minecraft:coals", "minecraft:coal", "minecraft:coal_ore",
                 "minecraft:deepslate_coal_ore" -> 48;
            case "minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                 "minecraft:redstone", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore" -> -58;
            case "minecraft:lapis_lazuli", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore" -> 0;
            case "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore" -> -16;
            case "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore" -> 16;
            case "minecraft:raw_copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore" -> 48;
            default -> Integer.MAX_VALUE;
        };
    }

    static boolean supports(String itemId) {
        return targetY(itemId) != Integer.MAX_VALUE;
    }

    static String requiredPickaxe(String itemId) {
        return switch (normalize(itemId)) {
            // Coal bootstraps torch production. The corridor itself verifies
            // the equipped tool against every block and can craft a lower-tier
            // pickaxe if none works, so do not demand two exact stone pickaxes
            // when an iron or better tool is already available.
            case "#minecraft:coals", "minecraft:coal", "minecraft:coal_ore",
                 "minecraft:deepslate_coal_ore" -> "";
            case "minecraft:diamond", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                 "minecraft:redstone", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
                 "minecraft:lapis_lazuli", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
                 "minecraft:raw_gold", "minecraft:gold_ore", "minecraft:deepslate_gold_ore" ->
                "minecraft:iron_pickaxe";
            case "minecraft:raw_iron", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
                 "minecraft:raw_copper", "minecraft:copper_ore", "minecraft:deepslate_copper_ore" ->
                "minecraft:stone_pickaxe";
            default -> "";
        };
    }

    static int requiredLadders(String itemId, int currentY) {
        int target = targetY(itemId);
        return target != Integer.MAX_VALUE && currentY > target + 16 ? REQUIRED_LADDERS : 0;
    }

    static int requiredTorches(String itemId) {
        return isCoal(itemId) ? 0 : REQUIRED_TORCHES;
    }

    static boolean needsHigherEntry(String itemId, int currentY) {
        int target = targetY(itemId);
        return target != Integer.MAX_VALUE && currentY < target - 16;
    }

    static BlockPos staircaseStand(BlockPos entry, Direction direction, int step) {
        int normalizedStep = Math.max(0, step);
        return entry.relative(horizontal(direction), normalizedStep).below(normalizedStep);
    }

    static Direction branchDirection(Direction mainDirection, int branchIndex) {
        Direction horizontal = horizontal(mainDirection);
        return Math.floorMod(branchIndex, 2) == 0
            ? horizontal.getClockWise()
            : horizontal.getCounterClockWise();
    }

    static BlockPos branchOrigin(BlockPos landing, Direction mainDirection, int branchIndex, int regionIndex) {
        int normalizedBranch = Math.max(0, branchIndex);
        int normalizedRegion = Math.max(0, regionIndex);
        int spineOffset = normalizedRegion * REGION_SPACING
            + (normalizedBranch / 2) * BRANCH_SPACING;
        return landing.relative(horizontal(mainDirection), spineOffset);
    }

    static BlockPos branchStand(
        BlockPos landing,
        Direction mainDirection,
        int branchIndex,
        int regionIndex,
        int progress
    ) {
        BlockPos origin = branchOrigin(landing, mainDirection, branchIndex, regionIndex);
        return origin.relative(branchDirection(mainDirection, branchIndex), Math.max(0, progress));
    }

    /**
     * A persisted branch counter is only meaningful when its checkpoint is the
     * stand represented by that counter. If recovery fell back to the main
     * tunnel, retaining the old counter would target a distant stand and cause
     * a displacement-recovery loop.
     */
    static int retainedBranchProgress(
        BlockPos landing,
        Direction mainDirection,
        int branchIndex,
        int regionIndex,
        int progress,
        BlockPos checkpoint
    ) {
        int normalizedProgress = Math.max(0, progress);
        if (normalizedProgress == 0) return 0;
        if (landing == null || checkpoint == null) return 0;
        return branchStand(
            landing,
            mainDirection,
            branchIndex,
            regionIndex,
            normalizedProgress
        ).equals(checkpoint) ? normalizedProgress : 0;
    }

    static boolean branchComplete(int progress) {
        return progress >= BRANCH_LENGTH;
    }

    static boolean shouldApproachBranchOrigin(int progress, boolean currentAtOrigin) {
        return Math.max(0, progress) == 0 && !currentAtOrigin;
    }

    static boolean isImprovedReturnCheckpoint(
        BlockPos origin,
        BlockPos branchEnd,
        BlockPos current,
        BlockPos previous
    ) {
        if (origin == null || branchEnd == null || current == null || previous == null) return false;
        // Vanilla navigation can legitimately walk on top of an existing floor
        // block when an excavated branch intersects a mineshaft or another
        // structure.  In that case the entity feet are one block above the
        // logical branch stand.  Treat that as the same return corridor instead
        // of leaving the checkpoint behind and later teleporting the NPC back.
        if (Math.abs(current.getY() - origin.getY()) > 1
            || branchEnd.getY() != origin.getY()) return false;

        boolean xBranch = branchEnd.getZ() == origin.getZ() && branchEnd.getX() != origin.getX();
        boolean zBranch = branchEnd.getX() == origin.getX() && branchEnd.getZ() != origin.getZ();
        if (!xBranch && !zBranch) return false;
        if (xBranch && current.getZ() != origin.getZ()) return false;
        if (zBranch && current.getX() != origin.getX()) return false;

        int minX = Math.min(origin.getX(), branchEnd.getX());
        int maxX = Math.max(origin.getX(), branchEnd.getX());
        int minZ = Math.min(origin.getZ(), branchEnd.getZ());
        int maxZ = Math.max(origin.getZ(), branchEnd.getZ());
        if (current.getX() < minX || current.getX() > maxX
            || current.getZ() < minZ || current.getZ() > maxZ) return false;
        BlockPos canonicalCurrent = canonicalReturnCheckpoint(origin, current);
        BlockPos canonicalPrevious = canonicalReturnCheckpoint(origin, previous);
        return canonicalCurrent.distManhattan(origin) < canonicalPrevious.distManhattan(origin);
    }

    static boolean isOnReturnCorridor(BlockPos origin, BlockPos branchEnd, BlockPos current) {
        if (origin == null || branchEnd == null || current == null) return false;
        if (branchEnd.getY() != origin.getY()
            || Math.abs(current.getY() - origin.getY()) > 1) return false;

        boolean xBranch = branchEnd.getZ() == origin.getZ() && branchEnd.getX() != origin.getX();
        boolean zBranch = branchEnd.getX() == origin.getX() && branchEnd.getZ() != origin.getZ();
        if (xBranch) {
            return current.getZ() == origin.getZ()
                && current.getX() >= Math.min(origin.getX(), branchEnd.getX())
                && current.getX() <= Math.max(origin.getX(), branchEnd.getX());
        }
        if (zBranch) {
            return current.getX() == origin.getX()
                && current.getZ() >= Math.min(origin.getZ(), branchEnd.getZ())
                && current.getZ() <= Math.max(origin.getZ(), branchEnd.getZ());
        }
        return false;
    }

    /**
     * A zero-progress "return" whose checkpoint is not on the current branch
     * is not a real branch return. It means extending the main spine toward a
     * later region hit an obstacle and the old reroute code changed phases.
     */
    static boolean shouldRelocateRegionAfterSpineReroute(
        int branchProgress,
        boolean checkpointOnReturnCorridor
    ) {
        return Math.max(0, branchProgress) == 0 && !checkpointOnReturnCorridor;
    }

    static BlockPos canonicalReturnCheckpoint(BlockPos origin, BlockPos current) {
        if (origin == null || current == null) return current;
        return new BlockPos(current.getX(), origin.getY(), current.getZ());
    }

    static boolean reachedReturnOrigin(Vec3 current, BlockPos origin) {
        if (current == null || origin == null) return false;
        BlockPos feet = BlockPos.containing(current);
        if (feet.getX() != origin.getX() || feet.getZ() != origin.getZ()) return false;
        int verticalOffset = feet.getY() - origin.getY();
        return verticalOffset >= 0 && verticalOffset <= 1;
    }

    static boolean regionComplete(int branchIndex) {
        return branchIndex >= BRANCHES_PER_REGION;
    }

    static boolean shouldPlaceTorch(int tunnelProgress, int lastTorchProgress) {
        return tunnelProgress > 0
            && tunnelProgress % TORCH_INTERVAL == 0
            && tunnelProgress > lastTorchProgress;
    }

    static Vec3 closeRangeStep(Vec3 current, Vec3 target) {
        Vec3 delta = target.subtract(current);
        double horizontalDistance = Math.hypot(delta.x, delta.z);

        // Enter a one-block descent horizontally before applying downward motion.
        // Combining both axes while the NPC still overlaps the old floor can pin it
        // against the lip even though the excavated destination is clear.
        if (delta.y < -0.25D && horizontalDistance > 0.08D) {
            double amount = Math.min(0.24D, Math.max(0.08D, horizontalDistance * 0.35D));
            return new Vec3(
                delta.x * amount / horizontalDistance,
                0.0D,
                delta.z * amount / horizontalDistance
            );
        }

        double length = delta.length();
        if (length <= 0.001D) return Vec3.ZERO;
        double amount = Math.min(0.28D, Math.max(0.08D, length * 0.25D));
        return delta.scale(amount / length);
    }

    static List<BlockPos> corridorExcavations(BlockPos fromStand, BlockPos desiredStand) {
        if (fromStand != null && desiredStand.getY() < fromStand.getY()) {
            return List.of(desiredStand.above(2), desiredStand.above(), desiredStand);
        }
        return List.of(desiredStand.above(), desiredStand);
    }

    static boolean reachedStand(Vec3 current, BlockPos desiredStand) {
        if (current == null || desiredStand == null) return false;
        return BlockPos.containing(current).equals(desiredStand)
            && Math.abs(current.y - desiredStand.getY()) <= 0.55D;
    }

    static boolean isUnsafeExcavation(String blockId, boolean fluidPresent, float destroySpeed) {
        // Falling blocks are handled like a real player would handle them:
        // remain on the previous safe stand and keep breaking each block that
        // falls into the excavated column. Only liquids and unbreakable blocks
        // require a reroute.
        return fluidPresent || destroySpeed < 0.0F;
    }

    static boolean mayBreakCorridorObstacle(
        boolean air,
        boolean fluidPresent,
        float destroySpeed,
        boolean hasBlockEntity,
        boolean protectedArea
    ) {
        return !air
            && !fluidPresent
            && destroySpeed >= 0.0F
            && !hasBlockEntity
            && !protectedArea;
    }

    static List<String> floorMaterialSelectors() {
        return List.of(
            "#forge:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:rooted_dirt",
            "minecraft:moss_block",
            "#minecraft:planks"
        );
    }

    static boolean isUsableNearbyEntry(
        boolean sameAsOrigin,
        boolean safeStand,
        boolean hasSafeStairDirection
    ) {
        return !sameAsOrigin && safeStand && hasSafeStairDirection;
    }

    static Direction retainedDirection(Direction safeDirection, Direction preferredDirection) {
        if (safeDirection != null && safeDirection.getAxis().isHorizontal()) return safeDirection;
        if (preferredDirection != null && preferredDirection.getAxis().isHorizontal()) return preferredDirection;
        return Direction.NORTH;
    }

    /** Deterministic expanding probes used when the loaded shoreline has no dry mining entry. */
    static BlockPos entryExpeditionProbe(BlockPos origin, int attempt) {
        if (origin == null) return BlockPos.ZERO;
        int normalizedAttempt = Math.max(0, attempt);
        double angle = normalizedAttempt * Math.PI * (3.0D - Math.sqrt(5.0D));
        int distance = 64 + (int) Math.floor(32.0D * Math.sqrt(normalizedAttempt + 1.0D));
        int dx = (int) Math.round(Math.cos(angle) * distance);
        int dz = (int) Math.round(Math.sin(angle) * distance);
        return origin.offset(dx, 0, dz);
    }

    static boolean shouldTeleportToEntry(boolean ownerCanCheat, double distance, int stalledTicks) {
        return ownerCanCheat
            && Double.isFinite(distance)
            && distance > 2.0D
            && (distance >= ENTRY_TELEPORT_DISTANCE || stalledTicks >= ENTRY_TELEPORT_STALLED_TICKS);
    }

    static boolean entryTargetTimedOut(double distance, int targetAgeTicks) {
        return Double.isFinite(distance)
            && distance > 2.0D
            && targetAgeTicks >= ENTRY_TARGET_TIMEOUT_TICKS;
    }

    static boolean suppliesNeedRefresh(
        boolean creativeResources,
        int totalRequiredPickaxeDurability,
        int torchCount
    ) {
        return suppliesNeedRefresh(
            creativeResources,
            totalRequiredPickaxeDurability,
            torchCount,
            true
        );
    }

    static boolean suppliesNeedRefresh(
        boolean creativeResources,
        int totalRequiredPickaxeDurability,
        int torchCount,
        boolean torchesRequired
    ) {
        return !creativeResources
            && (totalRequiredPickaxeDurability < MIN_PICKAXE_REMAINING_DURABILITY
                || torchesRequired && torchCount < MIN_TORCH_RESERVE);
    }

    /**
     * The fixed 200-point reserve is appropriate for iron tools, but exceeds
     * the maximum durability of a stone pickaxe. Lower tiers therefore use an
     * 80% health floor while iron and better keep the original reserve.
     */
    static int minimumUsablePickaxeDurability(int maxDamage, int requestedMinimum) {
        int normalizedMaximum = Math.max(1, maxDamage);
        int normalizedRequested = Math.max(1, requestedMinimum);
        int tierRelativeMinimum = Math.max(1, (int) Math.ceil(normalizedMaximum * 0.8D));
        return Math.min(normalizedRequested, tierRelativeMinimum);
    }

    static boolean foodReserveNeedsRefresh(
        boolean creativeResources,
        boolean preflightComplete,
        int safeFoodCount
    ) {
        if (creativeResources) return false;
        int required = preflightComplete ? MIN_FOOD_RESERVE : REQUIRED_FOOD_RESERVE;
        return Math.max(0, safeFoodCount) < required;
    }

    static boolean logReserveNeedsRefresh(boolean creativeResources, int logCount) {
        return !creativeResources && Math.max(0, logCount) < REQUIRED_LOG_RESERVE;
    }

    static boolean shouldRestoreCheckpoint(
        boolean ownerCanCheat,
        double distance,
        int verticalDelta
    ) {
        return ownerCanCheat
            && Double.isFinite(distance)
            && (distance >= CHECKPOINT_RESTORE_DISTANCE
                || Math.abs(verticalDelta) >= CHECKPOINT_RESTORE_VERTICAL_DELTA);
    }

    static boolean resourceTargetTimedOut(int targetAgeTicks) {
        return Math.max(0, targetAgeTicks) >= RESOURCE_TARGET_TIMEOUT_TICKS;
    }

    static boolean resourceChaseOwnsMovement(boolean activeTarget, int chaseStartedTick) {
        return activeTarget && chaseStartedTick > 0;
    }

    private static Direction horizontal(Direction direction) {
        return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
    }

    private static String normalize(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isCoal(String itemId) {
        return switch (normalize(itemId)) {
            case "#minecraft:coals", "minecraft:coal", "minecraft:coal_ore",
                 "minecraft:deepslate_coal_ore" -> true;
            default -> false;
        };
    }
}
