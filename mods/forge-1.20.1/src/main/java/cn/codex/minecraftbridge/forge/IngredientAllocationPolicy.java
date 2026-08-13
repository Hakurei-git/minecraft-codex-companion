package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

final class IngredientAllocationPolicy {
    private IngredientAllocationPolicy() {}

    record Result(List<Integer> slots, int missingIngredientIndex) {
        boolean complete() {
            return missingIngredientIndex < 0;
        }
    }

    static Result allocate(
        int ingredientCount,
        int slotCount,
        IntPredicate required,
        BiPredicate<Integer, Integer> matches,
        IntUnaryOperator stackCount
    ) {
        List<Integer> allocation = new ArrayList<>();
        Map<Integer, Integer> used = new HashMap<>();
        for (int ingredient = 0; ingredient < ingredientCount; ingredient++) {
            if (!required.test(ingredient)) continue;
            int found = -1;
            int smallestRemainingStack = Integer.MAX_VALUE;
            for (int slot = 0; slot < slotCount; slot++) {
                if (!matches.test(ingredient, slot)) continue;
                int remaining = stackCount.applyAsInt(slot) - used.getOrDefault(slot, 0);
                if (remaining <= 0 || remaining >= smallestRemainingStack) continue;
                found = slot;
                smallestRemainingStack = remaining;
            }
            if (found < 0) return new Result(List.copyOf(allocation), ingredient);
            used.merge(found, 1, Integer::sum);
            allocation.add(found);
        }
        return new Result(List.copyOf(allocation), -1);
    }
}
