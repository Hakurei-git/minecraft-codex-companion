package cn.codex.minecraftbridge.forge;

/**
 * Exact count facts attached to a task progress update.
 *
 * <p>{@code completedCount} is the task engine's observed completed amount,
 * {@code targetCount} is the explicit requested amount, and
 * {@code retainedCount} is the amount currently satisfying the task. For an
 * acquire-mode gather this is the task output still present beyond the phase
 * baseline; for an inventory-total gather it is the current matching inventory
 * capped at the target. None of these values may be reconstructed from a
 * percentage.</p>
 */
public record TaskProgressCounts(int completedCount, int targetCount, int retainedCount) {
    public TaskProgressCounts {
        if (completedCount < 0) throw new IllegalArgumentException("completedCount must be non-negative");
        if (targetCount <= 0) throw new IllegalArgumentException("targetCount must be positive");
        if (retainedCount < 0) throw new IllegalArgumentException("retainedCount must be non-negative");
    }
}
