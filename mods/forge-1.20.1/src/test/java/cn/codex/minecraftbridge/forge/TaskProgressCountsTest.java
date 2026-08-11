package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskProgressCountsTest {
    @Test
    void preservesObservedCountsWithoutUsingTheParentPercentage() {
        TaskProgressCounts counts = new TaskProgressCounts(53, 64, 51);

        assertEquals(53, counts.completedCount());
        assertEquals(64, counts.targetCount());
        assertEquals(51, counts.retainedCount());
    }

    @Test
    void rejectsInvalidFactsInsteadOfNormalizingOrEstimatingThem() {
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressCounts(-1, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressCounts(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgressCounts(0, 64, -1));
    }
}
