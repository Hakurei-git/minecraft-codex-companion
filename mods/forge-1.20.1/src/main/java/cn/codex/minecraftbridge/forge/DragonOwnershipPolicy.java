package cn.codex.minecraftbridge.forge;

import java.util.UUID;

/** Pure ownership identity check that remains valid across unloaded chunks. */
final class DragonOwnershipPolicy {
    private DragonOwnershipPolicy() {
    }

    static boolean isOwnedBy(boolean tamed, UUID dragonOwner, UUID requestedOwner) {
        return tamed && requestedOwner != null && requestedOwner.equals(dragonOwner);
    }
}
