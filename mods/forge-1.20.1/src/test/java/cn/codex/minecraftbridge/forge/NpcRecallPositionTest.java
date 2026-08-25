package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NpcRecallPositionTest {
    @Test
    void keepsTheValidatedSafeHeightInsteadOfFallingBackIntoThePlayer() {
        Vec3 recalled = NpcManager.recallPosition(new BlockPos(12, 91, -7));

        assertEquals(12.5D, recalled.x);
        assertEquals(91.0D, recalled.y);
        assertEquals(-6.5D, recalled.z);
    }
}
