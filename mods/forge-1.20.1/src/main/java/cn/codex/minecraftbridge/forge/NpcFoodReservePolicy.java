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
     * New reserve tasks persist their configured target. Legacy tasks from the
     * meal-buffer policy are migrated once instead of continuing to move their
     * completion line as hunger falls.
     */
    static int taskTarget(int configuredTarget, int persistedTarget, boolean targetFrozen) {
        return target(targetFrozen ? persistedTarget : configuredTarget);
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
