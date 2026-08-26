package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NpcRecoveryRecallPolicyTest {
    @Test
    void returnsOnlyToAnAvailableLivingOwner() {
        assertTrue(NpcRecoveryRecallPolicy.canReturnToOwner(true, true, false));
        assertFalse(NpcRecoveryRecallPolicy.canReturnToOwner(false, false, false));
        assertFalse(NpcRecoveryRecallPolicy.canReturnToOwner(true, false, false));
        assertFalse(NpcRecoveryRecallPolicy.canReturnToOwner(true, true, true));
    }
}
