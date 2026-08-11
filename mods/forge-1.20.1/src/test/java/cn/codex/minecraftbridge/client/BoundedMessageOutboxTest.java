package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BoundedMessageOutboxTest {
    @Test
    void replacesDuplicateKeysAndEvictsTheOldestMessage() {
        BoundedMessageOutbox<String> outbox = new BoundedMessageOutbox<>(2);

        outbox.put("task-a", "a1");
        outbox.put("task-a", "a2");
        outbox.put("task-b", "b");
        outbox.put("task-c", "c");

        assertEquals(2, outbox.size());
        assertEquals(List.of("b", "c"), outbox.drain());
        assertEquals(0, outbox.size());
    }
}
