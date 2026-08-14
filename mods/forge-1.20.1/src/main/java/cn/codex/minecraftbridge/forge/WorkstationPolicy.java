package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Set;

final class WorkstationPolicy {
    record MaterialCost(String selector, int count) {}

    enum BlockingAction {
        KEEP,
        RECOVER,
        RECOVER_AND_RELOCATE
    }

    private WorkstationPolicy() {}

    static MaterialCost fallbackMaterialCost(String workstationId) {
        return switch (workstationId) {
            case "minecraft:crafting_table" -> new MaterialCost("#minecraft:planks", 4);
            case "minecraft:furnace" -> new MaterialCost("#minecraft:stone_crafting_materials", 8);
            default -> null;
        };
    }

    static boolean canSupply(boolean hasWorkstationItem, int materialCount, boolean creative, MaterialCost fallback) {
        return creative || hasWorkstationItem || fallback != null && materialCount >= fallback.count();
    }

    /**
     * An interaction can report SUCCESS because the support block opened its
     * menu while the requested workstation was never placed.  The block state,
     * not the interaction result, is authoritative.
     */
    static boolean shouldAttemptDirectPlacement(boolean requestedBlockPresent) {
        return !requestedBlockPresent;
    }

    static boolean shouldRelocateBlockedMiningPlacement(
        boolean requiresCorrectTool,
        boolean hasCorrectTool
    ) {
        return requiresCorrectTool && !hasCorrectTool;
    }

    /**
     * Temporary stations must never occupy the NPC body, the next task target,
     * or a known one-block-wide mining passage. The caller supplies the exact
     * passage cells because descent and branch geometry live in the mining
     * policy rather than in this generic workstation policy.
     */
    static boolean allowsTemporaryPlacement(
        BlockPos candidate,
        BlockPos npcFeet,
        BlockPos destination,
        Set<BlockPos> requiredPassage
    ) {
        if (candidate == null) return false;
        if (npcFeet != null && (candidate.equals(npcFeet) || candidate.equals(npcFeet.above()))) {
            return false;
        }
        if (destination != null
            && (candidate.equals(destination) || candidate.equals(destination.above()))) {
            return false;
        }
        return requiredPassage == null || !requiredPassage.contains(candidate);
    }

    /** Lower values are preferred: a side alcove, then a side-rear alcove. */
    static int miningPlacementRank(BlockPos candidate, BlockPos anchor, Direction travelDirection) {
        if (candidate == null || anchor == null || candidate.getY() != anchor.getY()) {
            return Integer.MAX_VALUE;
        }
        Direction forward = horizontal(travelDirection);
        int dx = candidate.getX() - anchor.getX();
        int dz = candidate.getZ() - anchor.getZ();
        int forwardOffset = dx * forward.getStepX() + dz * forward.getStepZ();
        int sideOffset = dx * forward.getStepZ() - dz * forward.getStepX();
        if (Math.abs(sideOffset) != 1) return Integer.MAX_VALUE;
        if (forwardOffset == 0) return 0;
        if (forwardOffset >= -2 && forwardOffset < 0) return 1;
        return 2;
    }

    static BlockingAction blockingAction(
        boolean taskOwned,
        boolean expectedBlockPresent,
        boolean blocksRequiredPassage,
        boolean workstationStillRequired
    ) {
        if (!taskOwned || !expectedBlockPresent || !blocksRequiredPassage) return BlockingAction.KEEP;
        return workstationStillRequired ? BlockingAction.RECOVER_AND_RELOCATE : BlockingAction.RECOVER;
    }

    private static Direction horizontal(Direction direction) {
        return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
    }
}
