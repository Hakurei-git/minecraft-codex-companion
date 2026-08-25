package cn.codex.minecraftbridge.forge;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class BedPlacementConfirmationPolicyTest {
    @Test
    void acceptsTheValidFacingMinecraftActuallyPlaced() {
        assertEquals(Direction.WEST, BedPlacementConfirmationPolicy.confirmedFacing(
            true, BedPart.FOOT, BedPart.HEAD, Direction.WEST, Direction.WEST
        ));
        assertNull(BedPlacementConfirmationPolicy.confirmedFacing(
            true, BedPart.FOOT, BedPart.HEAD, Direction.WEST, Direction.NORTH
        ));
        assertNull(BedPlacementConfirmationPolicy.confirmedFacing(
            false, BedPart.FOOT, BedPart.HEAD, Direction.WEST, Direction.WEST
        ));
    }
}
