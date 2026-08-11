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
    static final int MIN_PICKAXE_REMAINING_DURABILITY = 200;
    static final int TORCH_INTERVAL = 8;
    static final int BRANCH_LENGTH = 32;
    static final int BRANCH_SPACING = 3;
    static final int BRANCHES_PER_REGION = 8;
    static final int REGION_SPACING = 128;

    private static final Set<String> UNSAFE_GRAVITY_BLOCKS = Set.of(
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:gravel",
        "minecraft:suspicious_sand",
        "minecraft:suspicious_gravel"
    );

    private DeepMiningPolicy() {
    }

    static int targetY(String itemId) {
        return switch (normalize(itemId)) {
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

    static boolean branchComplete(int progress) {
        return progress >= BRANCH_LENGTH;
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
        if (fluidPresent || destroySpeed < 0.0F) return true;
        return UNSAFE_GRAVITY_BLOCKS.contains(normalize(blockId));
    }

    private static Direction horizontal(Direction direction) {
        return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
    }

    private static String normalize(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
