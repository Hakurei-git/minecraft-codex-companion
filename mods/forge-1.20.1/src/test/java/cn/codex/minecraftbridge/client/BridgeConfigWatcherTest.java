package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeConfigWatcherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsACompleteConnectionChangeWithoutMutatingTheActiveConfig() throws IOException {
        Path path = temporaryDirectory.resolve("minecraft-codex-companion.json");
        BridgeConfig active = readyConfig(8765, "aaaaaaaaaaaaaaaa");
        write(path, 8766, "bbbbbbbbbbbbbbbb");

        BridgeConfig changed = new BridgeConfigWatcher(path).changedConfiguration(active).orElseThrow();

        assertEquals("ws://127.0.0.1:8766/bridge", changed.serverUrl);
        assertEquals("ws://127.0.0.1:8765/bridge", active.serverUrl);
        assertTrue(active.connectionSettingsDiffer(changed));
    }

    @Test
    void ignoresMalformedIncompleteAndRemoteConfigurations() throws IOException {
        Path path = temporaryDirectory.resolve("minecraft-codex-companion.json");
        BridgeConfig active = readyConfig(8765, "aaaaaaaaaaaaaaaa");
        BridgeConfigWatcher watcher = new BridgeConfigWatcher(path);

        Files.writeString(path, "{\"serverUrl\":", StandardCharsets.UTF_8);
        assertTrue(watcher.changedConfiguration(active).isEmpty());

        Files.writeString(path, "{\"serverUrl\":\"ws://127.0.0.1:8766/bridge\",\"token\":\"\"}", StandardCharsets.UTF_8);
        assertTrue(watcher.changedConfiguration(active).isEmpty());

        Files.writeString(path, "{\"serverUrl\":\"ws://example.invalid:8766/bridge\",\"token\":\"bbbbbbbbbbbbbbbb\"}", StandardCharsets.UTF_8);
        assertTrue(watcher.changedConfiguration(active).isEmpty());
        assertEquals("ws://127.0.0.1:8765/bridge", active.serverUrl);
    }

    @Test
    void doesNotReportAnUnchangedConfiguration() throws IOException {
        Path path = temporaryDirectory.resolve("minecraft-codex-companion.json");
        BridgeConfig active = readyConfig(8765, "aaaaaaaaaaaaaaaa");
        write(path, 8765, "aaaaaaaaaaaaaaaa");

        assertFalse(new BridgeConfigWatcher(path).changedConfiguration(active).isPresent());
    }

    private static BridgeConfig readyConfig(int port, String token) {
        BridgeConfig config = new BridgeConfig();
        config.serverUrl = "ws://127.0.0.1:" + port + "/bridge";
        config.token = token;
        return config;
    }

    private static void write(Path path, int port, String token) throws IOException {
        Files.writeString(
            path,
            "{\"serverUrl\":\"ws://127.0.0.1:" + port + "/bridge\",\"token\":\"" + token + "\"}",
            StandardCharsets.UTF_8
        );
    }
}
