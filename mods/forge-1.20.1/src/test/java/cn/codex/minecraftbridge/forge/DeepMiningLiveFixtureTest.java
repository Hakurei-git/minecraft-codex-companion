package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeepMiningLiveFixtureTest {
    @Test
    void fixturePlacesThreeOreTargetsAfterTheTorchInterval() {
        BlockPos origin = new BlockPos(20, -54, 30);
        assertEquals(new BlockPos(10, -58, 34), DeepMiningLiveFixture.oreStand(origin, 0));
        assertEquals(new BlockPos(9, -58, 34), DeepMiningLiveFixture.oreStand(origin, 1));
        assertEquals(new BlockPos(8, -58, 34), DeepMiningLiveFixture.oreStand(origin, 2));
    }

    @Test
    void acceptanceRequiresSuppliesMovementMiningAndPhysicalDelivery() {
        assertTrue(DeepMiningLiveFixture.acceptanceComplete(
            32, 32, 2,
            true, true,
            4, 8, 1, 20, 3, 1,
            true, true, 2, 128, true, 0
        ));
        assertFalse(DeepMiningLiveFixture.acceptanceComplete(
            31, 32, 2,
            true, true,
            4, 8, 1, 20, 3, 1,
            true, true, 2, 128, true, 0
        ));
        assertFalse(DeepMiningLiveFixture.acceptanceComplete(
            32, 32, 2,
            true, true,
            4, 8, 1, 20, 3, 0,
            false, true, 2, 128, true, 0
        ));
        assertFalse(DeepMiningLiveFixture.acceptanceComplete(
            32, 32, 2,
            true, true,
            4, 8, 1, 20, 3, 1,
            true, true, 1, 64, false, 0
        ));
    }
}
