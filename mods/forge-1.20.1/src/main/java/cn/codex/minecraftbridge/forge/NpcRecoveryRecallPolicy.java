package cn.codex.minecraftbridge.forge;

final class NpcRecoveryRecallPolicy {
    private NpcRecoveryRecallPolicy() {
    }

    static boolean canReturnToOwner(boolean ownerPresent, boolean ownerAlive, boolean ownerRemoved) {
        return ownerPresent && ownerAlive && !ownerRemoved;
    }
}
