package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TaskPriorityPolicyTest {
    @Test
    void keepsSafetyAndImmediateNeedsAheadOfLifeWork() {
        int gather = TaskPriorityPolicy.defaultPriority("gather", false);
        int retrieve = TaskPriorityPolicy.defaultPriority("retrieve", false);
        int eat = TaskPriorityPolicy.defaultPriority("eat", false);
        int combat = TaskPriorityPolicy.defaultPriority("combat", false);
        int guard = TaskPriorityPolicy.defaultPriority("guard", false);

        assertTrue(TaskPriorityPolicy.COMBAT_ASSIST > guard);
        assertTrue(guard > combat);
        assertTrue(combat > eat);
        assertTrue(eat > retrieve);
        assertTrue(retrieve > gather);
    }

    @Test
    void queuesLowerAndEqualPriorityButLetsHigherPriorityPreempt() {
        assertFalse(TaskPriorityPolicy.shouldPreempt(49, 50));
        assertFalse(TaskPriorityPolicy.shouldPreempt(50, 50));
        assertTrue(TaskPriorityPolicy.shouldPreempt(80, 50));
    }

    @Test
    void autonomousMaintenanceNeverDisplacesExplicitWork() {
        int autonomous = TaskPriorityPolicy.defaultPriority("organize-storage", true);
        int explicit = TaskPriorityPolicy.defaultPriority("organize-storage", false);

        assertFalse(TaskPriorityPolicy.shouldPreempt(autonomous, explicit));
    }

    @Test
    void resumesHighestPriorityInsteadOfQueueHead() {
        List<Candidate> paused = List.of(
            new Candidate("gather", 50),
            new Candidate("deliver", 60)
        );

        assertEquals("deliver", TaskPriorityPolicy.highestPriorityFirst(paused, Candidate::priority).id());
    }

    @Test
    void preservesArrivalOrderBetweenEqualPriorities() {
        List<Candidate> paused = List.of(
            new Candidate("older", 80),
            new Candidate("newer", 80),
            new Candidate("lower", 50)
        );

        assertEquals("older", TaskPriorityPolicy.highestPriorityFirst(paused, Candidate::priority).id());
    }

    @Test
    void nestedPreemptionResumesInPriorityThenFifoOrder() {
        List<Candidate> paused = new ArrayList<>(List.of(
            new Candidate("combat-first", 80),
            new Candidate("gather", 50),
            new Candidate("combat-later", 80),
            new Candidate("deliver", 60)
        ));

        assertEquals("combat-first", takeNext(paused).id());
        assertEquals("combat-later", takeNext(paused).id());
        assertEquals("deliver", takeNext(paused).id());
        assertEquals("gather", takeNext(paused).id());
    }

    private static Candidate takeNext(List<Candidate> paused) {
        Candidate next = TaskPriorityPolicy.highestPriorityFirst(paused, Candidate::priority);
        paused.remove(next);
        return next;
    }

    private record Candidate(String id, int priority) {}
}
