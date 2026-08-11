package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WebSocketTextAccumulatorTest {
    @Test
    void joinsFragmentsAndResetsAfterCompleteMessage() {
        WebSocketTextAccumulator accumulator = new WebSocketTextAccumulator(32);

        assertNull(accumulator.append("{\"type\":", false));
        assertEquals("{\"type\":\"chat\"}", accumulator.append("\"chat\"}", true));
        assertEquals("next", accumulator.append("next", true));
    }

    @Test
    void rejectsOversizedMessageAndCanReceiveTheNextOne() {
        WebSocketTextAccumulator accumulator = new WebSocketTextAccumulator(5);

        assertNull(accumulator.append("123", false));
        assertThrows(IllegalArgumentException.class, () -> accumulator.append("456", true));
        assertEquals("ok", accumulator.append("ok", true));
    }
}
