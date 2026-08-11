package cn.codex.minecraftbridge.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bounded, privacy-safe inventory delta history for explaining where task
 * materials came from and where they went. It records item ids and counts,
 * never item NBT, player chat, filesystem paths, credentials, or world data.
 */
final class ItemTransactionLedger {
    static final int MAX_ENTRIES = 64;
    private static final int MAX_TRACKED_ITEMS = 256;
    private static final int MAX_TEXT_LENGTH = 160;

    record Entry(
        long sequence,
        long gameTime,
        String taskId,
        String action,
        String itemId,
        int delta,
        int balanceAfter
    ) {
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private Map<String, Integer> baseline = Map.of();
    private long sequence;
    private boolean initialized;

    void observe(
        long gameTime,
        String taskId,
        String action,
        Map<String, Integer> currentTotals
    ) {
        Map<String, Integer> current = normalizeTotals(currentTotals);
        if (!initialized) {
            baseline = current;
            initialized = true;
            return;
        }

        Set<String> itemIds = new TreeSet<>(baseline.keySet());
        itemIds.addAll(current.keySet());
        for (String itemId : itemIds) {
            int before = baseline.getOrDefault(itemId, 0);
            int after = current.getOrDefault(itemId, 0);
            int delta = after - before;
            if (delta == 0) continue;
            append(new Entry(
                ++sequence,
                Math.max(0L, gameTime),
                safeText(taskId, ""),
                safeText(action, "inventory-change"),
                safeText(itemId, "minecraft:air"),
                delta,
                after
            ));
        }
        baseline = current;
    }

    List<Entry> recent() {
        return List.copyOf(entries);
    }

    CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putLong("Sequence", sequence);
        root.putBoolean("Initialized", initialized);

        ListTag savedBaseline = new ListTag();
        baseline.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .limit(MAX_TRACKED_ITEMS)
            .forEach(value -> {
                CompoundTag item = new CompoundTag();
                item.putString("Item", value.getKey());
                item.putInt("Count", value.getValue());
                savedBaseline.add(item);
            });
        root.put("Baseline", savedBaseline);

        ListTag savedEntries = new ListTag();
        for (Entry entry : entries) {
            CompoundTag value = new CompoundTag();
            value.putLong("Sequence", entry.sequence());
            value.putLong("GameTime", entry.gameTime());
            value.putString("TaskId", entry.taskId());
            value.putString("Action", entry.action());
            value.putString("Item", entry.itemId());
            value.putInt("Delta", entry.delta());
            value.putInt("Balance", entry.balanceAfter());
            savedEntries.add(value);
        }
        root.put("Entries", savedEntries);
        return root;
    }

    void load(CompoundTag root) {
        entries.clear();
        baseline = Map.of();
        sequence = Math.max(0L, root.getLong("Sequence"));
        initialized = root.getBoolean("Initialized");

        Map<String, Integer> restoredBaseline = new HashMap<>();
        ListTag savedBaseline = root.getList("Baseline", Tag.TAG_COMPOUND);
        for (int index = 0; index < Math.min(savedBaseline.size(), MAX_TRACKED_ITEMS); index++) {
            CompoundTag value = savedBaseline.getCompound(index);
            String itemId = safeText(value.getString("Item"), "");
            int count = Math.max(0, value.getInt("Count"));
            if (!itemId.isBlank() && count > 0) restoredBaseline.put(itemId, count);
        }
        baseline = Map.copyOf(restoredBaseline);

        ListTag savedEntries = root.getList("Entries", Tag.TAG_COMPOUND);
        int first = Math.max(0, savedEntries.size() - MAX_ENTRIES);
        Set<Long> seenSequences = new HashSet<>();
        for (int index = first; index < savedEntries.size(); index++) {
            CompoundTag value = savedEntries.getCompound(index);
            long entrySequence = Math.max(0L, value.getLong("Sequence"));
            int delta = value.getInt("Delta");
            String itemId = safeText(value.getString("Item"), "");
            if (entrySequence <= 0 || delta == 0 || itemId.isBlank() || !seenSequences.add(entrySequence)) continue;
            append(new Entry(
                entrySequence,
                Math.max(0L, value.getLong("GameTime")),
                safeText(value.getString("TaskId"), ""),
                safeText(value.getString("Action"), "inventory-change"),
                itemId,
                delta,
                Math.max(0, value.getInt("Balance"))
            ));
            sequence = Math.max(sequence, entrySequence);
        }
    }

    private void append(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
    }

    private static Map<String, Integer> normalizeTotals(Map<String, Integer> totals) {
        if (totals == null || totals.isEmpty()) return Map.of();
        Map<String, Integer> normalized = new HashMap<>();
        totals.entrySet().stream()
            .filter(value -> value.getKey() != null && value.getValue() != null)
            .sorted(Map.Entry.comparingByKey())
            .limit(MAX_TRACKED_ITEMS)
            .forEach(value -> {
                String itemId = safeText(value.getKey(), "");
                int count = Math.max(0, value.getValue());
                if (!itemId.isBlank() && count > 0) normalized.put(itemId, count);
            });
        return Map.copyOf(normalized);
    }

    private static String safeText(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) normalized = fallback;
        return normalized.length() <= MAX_TEXT_LENGTH
            ? normalized
            : normalized.substring(0, MAX_TEXT_LENGTH);
    }
}
