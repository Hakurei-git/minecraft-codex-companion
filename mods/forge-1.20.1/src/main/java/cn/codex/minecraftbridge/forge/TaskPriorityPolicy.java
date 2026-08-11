package cn.codex.minecraftbridge.forge;

import java.util.function.ToIntFunction;

final class TaskPriorityPolicy {
    static final int COMBAT_ASSIST = 100;

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
