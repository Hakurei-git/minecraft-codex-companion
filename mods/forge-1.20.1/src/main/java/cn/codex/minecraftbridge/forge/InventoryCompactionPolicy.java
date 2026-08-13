package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure transfer planner for merging identical backpack stacks. */
final class InventoryCompactionPolicy {
    record Slot(int index, String stackKey, int count, int maxCount) {
    }

    record Transfer(int fromSlot, int toSlot, int count) {
    }

    private InventoryCompactionPolicy() {
    }

    static List<Transfer> plan(int capacity, List<Slot> rawSlots) {
        if (capacity <= 0 || rawSlots == null || rawSlots.isEmpty()) return List.of();
        List<Slot> slots = rawSlots.stream()
            .filter(slot -> slot != null
                && slot.index() >= 0
                && slot.index() < capacity
                && slot.stackKey() != null
                && !slot.stackKey().isBlank()
                && slot.count() > 0
                && slot.maxCount() > 1)
            .sorted(Comparator.comparingInt(Slot::index))
            .toList();
        Map<Integer, Integer> counts = new HashMap<>();
        for (Slot slot : slots) counts.putIfAbsent(slot.index(), Math.min(slot.count(), slot.maxCount()));

        List<Transfer> transfers = new ArrayList<>();
        for (int targetIndex = 0; targetIndex < slots.size(); targetIndex++) {
            Slot target = slots.get(targetIndex);
            int targetCount = counts.getOrDefault(target.index(), 0);
            if (targetCount <= 0 || targetCount >= target.maxCount()) continue;
            for (int sourceIndex = targetIndex + 1; sourceIndex < slots.size(); sourceIndex++) {
                Slot source = slots.get(sourceIndex);
                int sourceCount = counts.getOrDefault(source.index(), 0);
                if (sourceCount <= 0 || !source.stackKey().equals(target.stackKey())) continue;
                int moved = Math.min(sourceCount, target.maxCount() - targetCount);
                if (moved <= 0) break;
                transfers.add(new Transfer(source.index(), target.index(), moved));
                sourceCount -= moved;
                targetCount += moved;
                counts.put(source.index(), sourceCount);
                counts.put(target.index(), targetCount);
                if (targetCount >= target.maxCount()) break;
            }
        }
        return List.copyOf(transfers);
    }
}
