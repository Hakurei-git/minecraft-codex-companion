package cn.codex.minecraftbridge.forge;

import java.util.Set;

/** Pure scheduling policy for the companion's always-stocked survival ration. */
final class NpcFoodReservePolicy {
    private static final int MIN_SAFE_FOOD_NUTRITION = 2;
    private static final Set<String> EXCLUSIVE_TASKS = Set.of(
        "combat", "eat", "provision-food", "deliver", "drop"
    );

    private NpcFoodReservePolicy() {
    }

    static int target(int configured) {
        return Math.max(0, Math.min(64, configured));
    }

    /**
     * Reserve enough items for the meal that will run immediately after the
     * provisioning task. Without this buffer, finding one item at 18/20 hunger
     * completes an 8-item reserve, eating that item drops it back to seven, and
     * the original task is pre-empted again forever.
     */
    static int targetWithMealBuffer(int reserveTarget, int foodLevel, int maxFoodLevel) {
        int reserve = target(reserveTarget);
        if (reserve <= 0) return 0;
        int maximum = Math.max(1, maxFoodLevel);
        int current = Math.max(0, Math.min(maximum, foodLevel));
        int missingFoodPoints = maximum - current;
        int mealItems = (missingFoodPoints + MIN_SAFE_FOOD_NUTRITION - 1) / MIN_SAFE_FOOD_NUTRITION;
        return Math.min(64, reserve + mealItems);
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
