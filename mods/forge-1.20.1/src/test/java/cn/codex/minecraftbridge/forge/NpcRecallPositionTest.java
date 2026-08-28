package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NpcRecallPositionTest {
    @Test
    void keepsTheValidatedSafeHeightInsteadOfFallingBackIntoThePlayer() {
        Vec3 recalled = NpcManager.recallPosition(new BlockPos(12, 91, -7));

        assertEquals(12.5D, recalled.x);
        assertEquals(91.0D, recalled.y);
        assertEquals(-6.5D, recalled.z);
    }

    @Test
    void restoresRememberedNpcBesideOwnerInsteadOfLoadingAStaleForeignDimensionChunk() {
        assertTrue(NpcManager.shouldRestoreBesideOwner("minecraft:the_nether", "minecraft:overworld"));
        assertTrue(NpcManager.shouldRestoreBesideOwner("", "minecraft:overworld"));
        assertFalse(NpcManager.shouldRestoreBesideOwner("minecraft:overworld", "minecraft:overworld"));
    }

    @Test
    void keepsSameDimensionRecallRetriesInsideTheControlAcknowledgementWindow() {
        int remaining = 40;
        for (int tick = 0; tick < 40; tick++) remaining = NpcManager.nextRecallRetryTicks(remaining);

        assertEquals(0, remaining);
        assertEquals(0, NpcManager.nextRecallRetryTicks(0));
    }
}
