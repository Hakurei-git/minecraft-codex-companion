package cn.codex.minecraftbridge.forge;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Confirms the pair Minecraft actually placed, independent of requested yaw. */
final class BedPlacementConfirmationPolicy {
    private BedPlacementConfirmationPolicy() {
    }

    static Direction confirmedFacing(
        boolean sameBedBlock,
        BedPart footPart,
        BedPart headPart,
        Direction footFacing,
        Direction headFacing
    ) {
        if (!sameBedBlock || footPart != BedPart.FOOT || headPart != BedPart.HEAD) return null;
        return headFacing == footFacing ? footFacing : null;
    }
}
