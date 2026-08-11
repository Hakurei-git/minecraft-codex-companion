package cn.codex.minecraftbridge.forge;

/** Counts only matching drops obtained by the current block-breaking action. */
final class GatherProgressPolicy {
    private GatherProgressPolicy() {
    }

    static int afterBreak(int completed, int inventoryBeforeBreak, int inventoryAfterBreak) {
        int safeCompleted = Math.max(0, completed);
        int acquired = Math.max(0, inventoryAfterBreak - Math.max(0, inventoryBeforeBreak));
        return (int) Math.min(Integer.MAX_VALUE, (long) safeCompleted + acquired);
    }

    static int retained(int acquiredByTask, int inventoryAtStart, int inventoryNow) {
        int acquired = Math.max(0, acquiredByTask);
        int retainedSinceStart = Math.max(0, inventoryNow - Math.max(0, inventoryAtStart));
        return Math.min(acquired, retainedSinceStart);
    }

    static int includingExternalSupply(int completed, int inventoryAtStart, int inventoryNow) {
        return Math.max(0, inventoryNow - Math.max(0, inventoryAtStart));
    }
}
