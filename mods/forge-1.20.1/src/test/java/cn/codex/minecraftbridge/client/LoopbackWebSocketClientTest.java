package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LoopbackWebSocketClientTest {
    @Test
    void normalizesBracketedIpv6LoopbackHosts() {
        assertEquals(
            "::1",
            LoopbackWebSocketClient.connectionHost(URI.create("ws://[::1]:8765/bridge"))
        );
    }

    @Test
    void computesTheRfcWebSocketAcceptanceValue() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            LoopbackWebSocketClient.expectedAccept("dGhlIHNhbXBsZSBub25jZQ==")
        );
    }

    @Test
    void masksClientTextFrames() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] mask = {1, 2, 3, 4};
        byte[] frame = LoopbackWebSocketClient.maskedFrame(1, payload, mask);
        assertEquals((byte) 0x81, frame[0]);
        assertTrue((frame[1] & 0x80) != 0);
        assertEquals(payload.length, frame[1] & 0x7f);
        byte[] decoded = new byte[payload.length];
        for (int index = 0; index < decoded.length; index++) {
            decoded[index] = (byte) (frame[6 + index] ^ mask[index % 4]);
        }
        assertArrayEquals(payload, decoded);
    }
}
