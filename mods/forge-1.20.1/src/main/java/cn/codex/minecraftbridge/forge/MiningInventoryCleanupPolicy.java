package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded, conservative inventory cleanup for long-running mining tasks. */
final class MiningInventoryCleanupPolicy {
    static final int TRIGGER_FREE_SLOTS = 1;
    static final int TARGET_FREE_SLOTS = 2;
    static final int RETAINED_STONE_ITEMS = 64;

    private static final Set<String> DISCARDABLE_STONE = Set.of(
        "minecraft:cobblestone",
        "minecraft:stone",
        "minecraft:deepslate",
        "minecraft:cobbled_deepslate",
        "minecraft:andesite",
        "minecraft:diorite",
        "minecraft:granite",
        "minecraft:tuff"
    );

    private static final Set<String> CORE_SUPPLIES = Set.of(
        "minecraft:ladder",
        "minecraft:torch",
        "minecraft:wooden_pickaxe",
        "minecraft:stone_pickaxe",
        "minecraft:iron_pickaxe",
        "minecraft:diamond_pickaxe",
        "minecraft:netherite_pickaxe",
        "minecraft:crafting_table",
        "minecraft:chest"
    );

    record InventorySlot(int index, String itemId, int count) {
    }

    record Drop(int slot, String itemId, int count) {
    }

    private MiningInventoryCleanupPolicy() {
    }

    static Set<String> protectedItems(
        Collection<String> taskItemIds,
        List<List<String>> recipeIngredientOptions
    ) {
        Set<String> protectedIds = new HashSet<>(CORE_SUPPLIES);
        if (taskItemIds != null) {
            for (String itemId : taskItemIds) addNormalized(protectedIds, itemId);
        }
        if (recipeIngredientOptions != null) {
            for (List<String> options : recipeIngredientOptions) {
                if (options == null) continue;
                for (String itemId : options) addNormalized(protectedIds, itemId);
            }
        }
        return Set.copyOf(protectedIds);
    }

    static List<Drop> plan(
        int capacity,
        List<InventorySlot> inventory,
        Set<String> protectedItemIds
    ) {
        if (capacity <= 0 || inventory == null || inventory.isEmpty()) return List.of();

        Map<Integer, InventorySlot> occupied = new LinkedHashMap<>();
        for (InventorySlot raw : inventory) {
            if (raw == null || raw.index() < 0 || raw.index() >= capacity || raw.count() <= 0) continue;
            String itemId = normalize(raw.itemId());
            if (itemId.isBlank()) continue;
            occupied.putIfAbsent(raw.index(), new InventorySlot(raw.index(), itemId, raw.count()));
        }

        int freeSlots = Math.max(0, capacity - occupied.size());
        if (freeSlots > TRIGGER_FREE_SLOTS) return List.of();
        int slotsToFree = TARGET_FREE_SLOTS - freeSlots;
        if (slotsToFree <= 0) return List.of();

        Set<String> protectedIds = new HashSet<>();
        if (protectedItemIds != null) {
            for (String itemId : protectedItemIds) {
                String normalized = normalize(itemId);
                if (!normalized.isBlank()) protectedIds.add(normalized);
            }
        }

        List<InventorySlot> candidates = occupied.values().stream()
            .filter(slot -> DISCARDABLE_STONE.contains(slot.itemId()))
            .filter(slot -> !protectedIds.contains(slot.itemId()))
            .sorted(Comparator
                .comparingInt((InventorySlot slot) -> discardPriority(slot.itemId()))
                .thenComparingInt(InventorySlot::index))
            .toList();
        int discardableCount = candidates.stream().mapToInt(InventorySlot::count).sum();
        int discardBudget = Math.max(0, discardableCount - RETAINED_STONE_ITEMS);
        if (discardBudget <= 0) return List.of();

        List<Drop> drops = new ArrayList<>(slotsToFree);
        for (InventorySlot candidate : candidates) {
            if (drops.size() >= slotsToFree) break;
            // Partial-stack disposal does not release an inventory slot.
            if (candidate.count() > discardBudget) continue;
            drops.add(new Drop(candidate.index(), candidate.itemId(), candidate.count()));
            discardBudget -= candidate.count();
        }
        return List.copyOf(drops);
    }

    static boolean isDiscardedBy(UUID npcId, UUID discardedBy) {
        return npcId != null && npcId.equals(discardedBy);
    }

    static boolean isDiscardableStone(String itemId) {
        return DISCARDABLE_STONE.contains(normalize(itemId));
    }

    private static int discardPriority(String itemId) {
        return switch (itemId) {
            case "minecraft:tuff" -> 0;
            case "minecraft:andesite", "minecraft:diorite", "minecraft:granite" -> 1;
            case "minecraft:stone", "minecraft:deepslate" -> 2;
            case "minecraft:cobbled_deepslate" -> 3;
            case "minecraft:cobblestone" -> 4;
            default -> Integer.MAX_VALUE;
        };
    }

    private static void addNormalized(Set<String> target, String itemId) {
        String normalized = normalize(itemId);
        if (!normalized.isBlank()) target.add(normalized);
    }

    private static String normalize(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    }
}
