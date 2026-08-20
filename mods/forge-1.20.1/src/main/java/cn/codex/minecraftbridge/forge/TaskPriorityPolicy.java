package cn.codex.minecraftbridge.forge;

import java.util.Set;
import java.util.function.ToIntFunction;

final class TaskPriorityPolicy {
    static final int COMBAT_ASSIST = 100;
    private static final Set<String> EXPLICIT_FOOD_ACTIONS = Set.of(
        "provision-food", "eat", "deliver", "drop"
    );

    private TaskPriorityPolicy() {}

    static int defaultPriority(String kind, boolean localAutonomy) {
        if (localAutonomy) return 10;
        return switch (kind) {
            case "follow", "guard" -> 90;
            case "combat" -> 80;
            case "eat", "provision-food" -> 70;
            case "deliver", "retrieve" -> 60;
            default -> 50;
        };
    }

    static boolean shouldPreempt(int incomingPriority, int activePriority) {
        return incomingPriority > activePriority;
    }

    static boolean explicitFoodReplacesAutonomousReserve(
        String incomingId,
        String incomingKind,
        String activeId
    ) {
        return incomingId != null
            && !incomingId.startsWith("local:")
            && incomingKind != null
            && EXPLICIT_FOOD_ACTIONS.contains(incomingKind)
            && activeId != null
            && activeId.startsWith("local:auto-food:");
    }

    static <T> T highestPriorityFirst(Iterable<T> candidates, ToIntFunction<T> priority) {
        T selected = null;
        int highest = Integer.MIN_VALUE;
        for (T candidate : candidates) {
            int candidatePriority = priority.applyAsInt(candidate);
            if (selected == null || candidatePriority > highest) {
                selected = candidate;
                highest = candidatePriority;
            }
        }
        return selected;
    }
}
