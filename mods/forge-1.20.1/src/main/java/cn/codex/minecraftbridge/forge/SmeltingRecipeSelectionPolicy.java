package cn.codex.minecraftbridge.forge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chooses a deterministic cooking recipe when several recipes share one output. */
final class SmeltingRecipeSelectionPolicy {
    record Candidate(List<String> inputItemIds) {
        Candidate {
            inputItemIds = List.copyOf(inputItemIds);
        }
    }

    private SmeltingRecipeSelectionPolicy() {
    }

    static int choose(
        List<Candidate> candidates,
        Set<String> inventoryItemIds,
        List<String> preferredInputIds
    ) {
        if (candidates.isEmpty()) return -1;
        Set<String> inventory = new HashSet<>(inventoryItemIds);
        Set<String> preferred = new HashSet<>(preferredInputIds);
        int selected = 0;
        int selectedRank = rank(candidates.get(0), inventory, preferred);
        for (int index = 1; index < candidates.size(); index++) {
            int rank = rank(candidates.get(index), inventory, preferred);
            if (rank < selectedRank) {
                selected = index;
                selectedRank = rank;
            }
        }
        return selected;
    }

    private static int rank(Candidate candidate, Set<String> inventory, Set<String> preferred) {
        if (candidate.inputItemIds().stream().anyMatch(inventory::contains)) return 0;
        if (candidate.inputItemIds().stream().anyMatch(preferred::contains)) return 1;
        return 2;
    }
}
