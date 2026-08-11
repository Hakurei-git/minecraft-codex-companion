package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePriorityLiveFixtureTest {
    @Test
    void stagesTwoSeparatedEightBlockTwentySixConnectedVeins() {
        BlockPos origin = new BlockPos(10, 96, -30);
        List<BlockPos> local = ResourcePriorityLiveFixture.localVein(origin);
        List<BlockPos> remote = ResourcePriorityLiveFixture.remoteVein(origin);

        assertEquals(8, local.size());
        assertEquals(8, remote.size());
        assertTrue(ResourcePriorityLiveFixture.allTwentySixConnected(local));
        assertTrue(ResourcePriorityLiveFixture.allTwentySixConnected(remote));
        assertTrue(local.stream().allMatch(first -> remote.stream().allMatch(second ->
            Math.abs(first.getX() - second.getX()) > 1
                || Math.abs(first.getY() - second.getY()) > 1
                || Math.abs(first.getZ() - second.getZ()) > 1
        )));
    }

    @Test
    void rejectsCleanupBeforeAnyMutationWhenUnknownContentExists() {
        assertTrue(ResourcePriorityLiveFixture.cleanupMayProceed(0, 0, 0, 0));
        assertFalse(ResourcePriorityLiveFixture.cleanupMayProceed(1, 0, 0, 0));
        assertFalse(ResourcePriorityLiveFixture.cleanupMayProceed(0, 1, 0, 0));
        assertFalse(ResourcePriorityLiveFixture.cleanupMayProceed(0, 0, 1, 0));
        assertFalse(ResourcePriorityLiveFixture.cleanupMayProceed(0, 0, 0, 1));
    }

    @Test
    void exposesOnlyTheThreeFixedReversibleScenarios() {
        assertEquals("priority", ResourcePriorityLiveFixture.requireScenario("priority"));
        assertEquals("fishing", ResourcePriorityLiveFixture.requireScenario("fishing"));
        assertEquals("torches", ResourcePriorityLiveFixture.requireScenario("torches"));
        assertThrows(IllegalArgumentException.class, () -> ResourcePriorityLiveFixture.requireScenario("custom"));
    }
}
