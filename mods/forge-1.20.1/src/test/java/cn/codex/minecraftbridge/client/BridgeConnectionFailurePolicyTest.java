package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BridgeConnectionFailurePolicyTest {
    @Test
    void classifiesConnectionFailuresWithoutPersistingMessages() {
        assertEquals("timeout", BridgeClient.connectionFailureCategory(new SocketTimeoutException("private")));
        assertEquals("connect-refused", BridgeClient.connectionFailureCategory(new ConnectException("private")));
        assertEquals("loopback-resolution", BridgeClient.connectionFailureCategory(new UnknownHostException("private")));
        assertEquals("access-denied", BridgeClient.connectionFailureCategory(new SecurityException("private")));
        assertEquals("invalid-config", BridgeClient.connectionFailureCategory(new IllegalArgumentException("private")));
        assertEquals("other-io", BridgeClient.connectionFailureCategory(new IOException("private")));
        assertEquals(
            "connect-refused",
            BridgeClient.connectionFailureCategory(new CompletionException(new ConnectException("private")))
        );
        assertEquals("other", BridgeClient.connectionFailureCategory(new IllegalStateException("private")));
    }
}
