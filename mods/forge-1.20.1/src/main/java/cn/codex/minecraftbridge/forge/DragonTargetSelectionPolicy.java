package cn.codex.minecraftbridge.forge;

import java.util.Set;

/** Pure ranking rules for implicit dragon task targets. */
final class DragonTargetSelectionPolicy {
    private static final Set<String> OWNER_ONLY_ACTIONS = Set.of(
        "follow",
        "stay",
        "mount",
        "recall",
        "assist-combat",
        "land",
        "fly-to"
    );

    private DragonTargetSelectionPolicy() {
    }

    static boolean requiresOwner(String action) {
        return OWNER_ONLY_ACTIONS.contains(action == null ? "" : action);
    }

    static int rank(boolean remembered, boolean owned) {
        if (remembered && owned) return 0;
        if (owned) return 1;
        if (remembered) return 2;
        return 3;
    }
}
