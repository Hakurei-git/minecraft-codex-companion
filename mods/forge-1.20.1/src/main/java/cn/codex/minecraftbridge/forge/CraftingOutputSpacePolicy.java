package cn.codex.minecraftbridge.forge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure inventory-space simulation for an atomic crafting transaction. */
final class CraftingOutputSpacePolicy {
    record Slot(int index, String stackKey, int count, int maxCount) {
    }

    private CraftingOutputSpacePolicy() {
    }

    static boolean canInsertAfterConsumption(
        int capacity,
        List<Slot> inventory,
        List<Integer> consumedSlots,
        String outputKey,
        int outputCount,
        int outputMaxCount
    ) {
        if (capacity <= 0 || outputKey == null || outputKey.isBlank()
            || outputCount <= 0 || outputMaxCount <= 0) return false;

        Map<Integer, Slot> slots = new HashMap<>();
        if (inventory != null) {
            for (Slot slot : inventory) {
                if (slot == null || slot.index() < 0 || slot.index() >= capacity
                    || slot.stackKey() == null || slot.stackKey().isBlank()
                    || slot.count() <= 0 || slot.maxCount() <= 0) continue;
                slots.putIfAbsent(slot.index(), slot);
            }
        }

        Map<Integer, Integer> remainingCounts = new HashMap<>();
        for (Slot slot : slots.values()) remainingCounts.put(slot.index(), slot.count());
        if (consumedSlots != null) {
            for (int slotIndex : consumedSlots) {
                int count = remainingCounts.getOrDefault(slotIndex, 0);
                if (count <= 0) return false;
                remainingCounts.put(slotIndex, count - 1);
            }
        }

        int remainder = outputCount;
        for (Slot slot : slots.values()) {
            int count = remainingCounts.getOrDefault(slot.index(), 0);
            if (count <= 0 || !slot.stackKey().equals(outputKey)) continue;
            int free = Math.max(0, slot.maxCount() - count);
            remainder -= Math.min(remainder, free);
            if (remainder <= 0) return true;
        }

        int emptySlots = capacity - slots.size();
        for (Slot slot : slots.values()) {
            if (remainingCounts.getOrDefault(slot.index(), 0) <= 0) emptySlots++;
        }
        return (long) emptySlots * outputMaxCount >= remainder;
    }
}
