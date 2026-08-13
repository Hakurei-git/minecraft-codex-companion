package cn.codex.minecraftbridge.forge;

import java.util.Locale;
import java.util.Set;

/** Conservative inventory rules for renewable food fishing. */
final class FishingProvisioningPolicy {
    private static final Set<String> DISPOSABLE_JUNK = Set.of(
        "minecraft:bowl",
        "minecraft:tripwire_hook",
        "minecraft:rotten_flesh"
    );

    private FishingProvisioningPolicy() {
    }

    static boolean keepLoot(boolean safeFood, boolean acceptedSource) {
        return safeFood && acceptedSource;
    }

    static boolean disposableJunk(String itemId) {
        String normalized = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
        return DISPOSABLE_JUNK.contains(normalized);
    }
}
