package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/** Plans without touching live inventories. One category per logical chest/barrel/shulker. */
final class StorageLayoutPlanner {
    record Stack<T>(T item, int count, int limit, HomeStoragePolicy.Category category) {
        Stack<T> withCount(int value) { return new Stack<>(item, value, limit, category); }
    }
    record Plan<T>(List<List<Stack<T>>> containers, int missingSingleChests) {
        boolean fits() { return missingSingleChests == 0; }
    }

    private StorageLayoutPlanner() {}

    static <T> Plan<T> plan(List<Stack<T>> source, List<Integer> capacities, BiPredicate<T, T> sameItem) {
        Map<HomeStoragePolicy.Category, List<Stack<T>>> categories = new EnumMap<>(HomeStoragePolicy.Category.class);
        for (Stack<T> stack : source) {
            if (stack.count() <= 0) continue;
            if (stack.limit() <= 0) throw new IllegalArgumentException("Invalid stack limit");
            List<Stack<T>> group = categories.computeIfAbsent(stack.category(), ignored -> new ArrayList<>());
            int remaining = stack.count();
            for (int i = 0; i < group.size() && remaining > 0; i++) {
                Stack<T> existing = group.get(i);
                if (existing.limit() != stack.limit() || !sameItem.test(existing.item(), stack.item())) continue;
                int moved = Math.min(remaining, existing.limit() - existing.count());
                if (moved <= 0) continue;
                group.set(i, existing.withCount(existing.count() + moved));
                remaining -= moved;
            }
            while (remaining > 0) {
                int moved = Math.min(remaining, stack.limit());
                group.add(stack.withCount(moved));
                remaining -= moved;
            }
        }
        List<List<Stack<T>>> groups = new ArrayList<>(categories.values());
        // Large categories get large chests first; enum order breaks ties deterministically.
        groups.sort(Comparator.comparingInt((List<Stack<T>> group) -> group.size()).reversed());
        List<List<Stack<T>>> result = new ArrayList<>();
        for (int capacity : capacities) {
            if (capacity < 0) throw new IllegalArgumentException("Invalid container size");
            result.add(new ArrayList<>());
        }
        boolean[] used = new boolean[capacities.size()];
        int missing = 0;
        for (List<Stack<T>> group : groups) {
            int offset = 0;
            while (offset < group.size()) {
                int remaining = group.size() - offset;
                int best = -1;
                for (int i = 0; i < capacities.size(); i++) {
                    if (used[i] || capacities.get(i) <= 0) continue;
                    if (best < 0 || betterCapacity(capacities.get(i), capacities.get(best), remaining)) best = i;
                }
                if (best < 0) {
                    missing += (remaining + 26) / 27;
                    break;
                }
                used[best] = true;
                int end = Math.min(group.size(), offset + capacities.get(best));
                result.get(best).addAll(group.subList(offset, end));
                offset = end;
            }
        }
        return new Plan<>(result.stream().map(List::copyOf).toList(), missing);
    }

    private static boolean betterCapacity(int candidate, int current, int required) {
        if (candidate >= required && current >= required) return candidate < current;
        if (candidate >= required) return true;
        if (current >= required) return false;
        return candidate > current;
    }
}
