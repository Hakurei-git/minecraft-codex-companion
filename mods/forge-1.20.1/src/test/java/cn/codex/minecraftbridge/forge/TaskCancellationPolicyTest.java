package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskCancellationPolicyTest {
    @Test
    void recognizesOnlyTheProvisioningChildOfTheCancelledMiningTask() {
        assertTrue(TaskCancellationPolicy.isDeepMiningProvisionChild(
            "local:deep-mining-food:parent", "parent"
        ));
        assertFalse(TaskCancellationPolicy.isDeepMiningProvisionChild(
            "local:deep-mining-food:other", "parent"
        ));
        assertFalse(TaskCancellationPolicy.isDeepMiningProvisionChild(
            "local:auto-food:parent", "parent"
        ));
    }
}
