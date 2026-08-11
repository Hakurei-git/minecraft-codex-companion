package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NpcTaskCheckpointCacheTest {
    @Test
    void retainsLastValidSnapshotWhenTheNextCandidateIsCorrupt() {
        JsonObject spec = new JsonObject();
        spec.addProperty("kind", "gather");
        NpcTaskPersistence.WorkState work = new NpcTaskPersistence.WorkState(
            "gather-before-save-failure",
            "gather",
            spec,
            new JsonObject(),
            "FOLLOW",
            50,
            "",
            new JsonObject()
        );
        byte[] valid = NpcTaskPersistence.encodeCompressed(new NpcTaskPersistence.SchedulerState(
            NpcTaskPersistence.VERSION,
            "ready",
            work,
            List.of()
        ));
        NpcTaskCheckpointCache cache = new NpcTaskCheckpointCache();

        assertArrayEquals(valid, cache.remember(valid));
        assertThrows(IllegalArgumentException.class, () -> cache.remember(new byte[] {1, 2, 3}));
        byte[] retained = cache.lastValid();
        assertArrayEquals(valid, retained);
        assertEquals(
            "gather-before-save-failure",
            NpcTaskPersistence.decodeCompressed(retained).active().id()
        );
    }

    @Test
    void neverExposesItsMutableBackingArray() {
        NpcTaskCheckpointCache cache = new NpcTaskCheckpointCache();
        byte[] first = cache.lastValid();
        first[0] ^= 0x7f;

        NpcTaskPersistence.SchedulerState restored = NpcTaskPersistence.decodeCompressed(cache.lastValid());
        assertEquals(NpcTaskPersistence.VERSION, restored.version());
    }
}
