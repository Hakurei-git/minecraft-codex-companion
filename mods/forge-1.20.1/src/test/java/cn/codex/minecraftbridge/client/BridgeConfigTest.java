package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeConfigTest {
    @Test
    void newCompanionUsesTheConfiguredDefaultName() {
        BridgeConfig config = new BridgeConfig();
        assertEquals("Codex", config.name);
        assertEquals("", config.ownerName);
    }

    @Test
    void migratesLegacyEncodingReplacementNamesWithoutChangingCustomNames() {
        assertEquals("Codex", BridgeConfig.normalizeName("?"));
        assertEquals("Codex", BridgeConfig.normalizeName("？？"));
        assertEquals("Codex", BridgeConfig.normalizeName("\uFFFD"));
        assertEquals("红瞳猫娘", BridgeConfig.normalizeName("红瞳猫娘"));
    }

    @Test
    void bridgeEndpointIsRestrictedToTheLocalBridgeRoute() {
        assertTrue(BridgeConfig.isLoopbackBridgeUrl("ws://127.0.0.1:8765/bridge"));
        assertTrue(BridgeConfig.isLoopbackBridgeUrl("ws://localhost:9000/bridge"));
        assertTrue(BridgeConfig.isLoopbackBridgeUrl("ws://[::1]:8765/bridge"));
        assertFalse(BridgeConfig.isLoopbackBridgeUrl("wss://127.0.0.1:8765/bridge"));
        assertFalse(BridgeConfig.isLoopbackBridgeUrl("ws://example.invalid:8765/bridge"));
        assertFalse(BridgeConfig.isLoopbackBridgeUrl("ws://127.0.0.1:8765/bridge?target=remote"));
        assertFalse(BridgeConfig.isLoopbackBridgeUrl("ws://user@127.0.0.1:8765/bridge"));
    }
}
