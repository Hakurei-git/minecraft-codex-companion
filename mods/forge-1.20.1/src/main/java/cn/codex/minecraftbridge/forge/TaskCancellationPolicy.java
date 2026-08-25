package cn.codex.minecraftbridge.forge;

/** Relationships between externally visible tasks and internal survival work. */
final class TaskCancellationPolicy {
    private static final String DEEP_MINING_FOOD_PREFIX = "local:deep-mining-food:";

    private TaskCancellationPolicy() {
    }

    static boolean isDeepMiningProvisionChild(String childTaskId, String parentTaskId) {
        return childTaskId != null
            && parentTaskId != null
            && childTaskId.equals(DEEP_MINING_FOOD_PREFIX + parentTaskId);
    }
}
