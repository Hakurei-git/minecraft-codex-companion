package cn.codex.minecraftbridge.forge;

import java.util.Set;

/** Classifies construction failures whose exact checkpoint can be retried. */
final class BuildFailureRecoveryPolicy {
    private static final Set<String> NON_RECOVERABLE = Set.of(
        "BUILD_PLAN_MISSING",
        "BUILD_PLAN_INVALID",
        "BUILD_CHECKPOINT_INVALID",
        "INVALID_BUILD_STATE",
        "INVALID_BUILD_BLOCK",
        "CANCELLED",
        "EMERGENCY_STOP",
        "STANCE_CHANGED",
        "TASK_EXCEPTION"
    );

    private BuildFailureRecoveryPolicy() {
    }

    static boolean isRecoverable(String code) {
        return code != null && !code.isBlank() && !NON_RECOVERABLE.contains(code);
    }

    static boolean shouldDiscardOnCancellation(String checkpointTaskId, String requestedTaskId) {
        return checkpointTaskId != null
            && !checkpointTaskId.isBlank()
            && checkpointTaskId.equals(requestedTaskId);
    }
}
