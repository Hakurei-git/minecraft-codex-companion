package cn.codex.minecraftbridge.forge;

import java.util.Set;

/** Pure scheduling policy for the companion's always-stocked survival ration. */
final class NpcFoodReservePolicy {
    private static final Set<String> EXCLUSIVE_TASKS = Set.of(
        "combat", "eat", "provision-food", "deliver", "drop"
    );

    private NpcFoodReservePolicy() {
    }

    static int target(int configured) {
        return Math.max(0, Math.min(64, configured));
    }

    /**
     * Only autonomous reserve tasks inherit the standing reserve setting.
     * Explicit food orders and deep-mining prerequisites keep their own count,
     * even when automatic reserves are disabled or configured below that count.
     * Legacy autonomous meal-buffer targets are migrated once.
     */
    static int taskTarget(String taskId, int configuredTarget, int persistedTarget, boolean targetFrozen) {
        boolean migrateAutonomous = taskId != null && taskId.startsWith("local:auto-food:") && !targetFrozen;
        return target(migrateAutonomous ? configuredTarget : persistedTarget);
    }

    static boolean shouldProvision(
        boolean creativeResources,
        int configuredTarget,
        int safeFoodCount,
        String activeKind,
        boolean combatAssist,
        boolean hostileNearby,
        boolean downed
    ) {
        int target = target(configuredTarget);
        if (creativeResources || target <= 0 || combatAssist || hostileNearby || downed) return false;
        String kind = activeKind == null ? "" : activeKind.trim().toLowerCase(java.util.Locale.ROOT);
        if (EXCLUSIVE_TASKS.contains(kind)) return false;
        return Math.max(0, safeFoodCount) < target;
    }
}
