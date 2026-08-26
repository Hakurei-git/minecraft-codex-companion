package cn.codex.minecraftbridge.forge;

/** Read-only acceptance telemetry for the autonomous food reserve contract. */
final class FoodReserveAuditLiveFixture {
    private FoodReserveAuditLiveFixture() {
    }

    static void apply(CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Food reserve audit requires an in-world NPC");
        if (!"inspect".equals(mode)) {
            throw new IllegalArgumentException("Unknown food reserve audit fixture mode");
        }
        NpcTaskEngine.ProvisionFoodDiagnostics diagnostics = npc.tasks().provisionFoodDiagnosticsForFixture();
        npc.setNextLiveFixtureAckStatus(
            "food-reserve:task=" + bit(!diagnostics.taskId().isBlank())
                + ",target=" + diagnostics.requestedCount()
                + ",food=" + npc.foodLevel()
                + ",managed=" + bit(npc.isManagedEating())
                + ",paused=" + bit(diagnostics.paused())
                + ",eatSeq=" + npc.eatingCompletionSequence()
        );
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
