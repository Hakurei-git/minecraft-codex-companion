package cn.codex.minecraftbridge.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "minecraft-codex-companion.json";

    public String serverUrl = "ws://127.0.0.1:8765/bridge";
    public String token = "";
    public String companionId = "codex-forge";
    public String name = "Codex";
    public String ownerName = "";
    public boolean autoReconnect = true;
    public int snapshotIntervalTicks = 10;
    public int observeRadius = 32;
    public boolean allowPvp = false;
    public boolean allowBreakingContainers = false;
    public List<String> hostileEntityAllowlist = new ArrayList<>();
    public boolean npcAutoSpawn = true;
    public int npcRecallDistance = 48;
    public int npcRecoveryTicks = 200;
    public String npcMaterialMode = "owner";
    public String npcSkinPath = "config/minecraft-codex-companion-skin.png";
    public boolean keepSingleplayerRunningInBackground = true;

    public static BridgeConfig load() {
        Path path = path();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                BridgeConfig loaded = GSON.fromJson(reader, BridgeConfig.class);
                if (loaded != null) {
                    loaded.normalize();
                    return loaded;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        BridgeConfig created = new BridgeConfig();
        created.save();
        return created;
    }

    public void save() {
        normalize();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isReady() {
        return isLoopbackBridgeUrl(serverUrl) && token != null && token.length() >= 16;
    }

    static boolean isLoopbackBridgeUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            if (!"ws".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return false;
            if (!"/bridge".equals(uri.getPath())) return false;
            if (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) return false;
            String host = LoopbackWebSocketClient.connectionHost(uri);
            return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equalsIgnoreCase(host);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public String backend() {
        return isNeoForge() ? "neoforge-1.21.1" : "forge-1.20.1";
    }

    public String gameVersion() {
        return isNeoForge() ? "1.21.1" : "1.20.1";
    }

    public String loader() {
        return isNeoForge() ? "NeoForge 21.1.182" : "Forge 47.4.21";
    }

    private void normalize() {
        if (snapshotIntervalTicks < 2) snapshotIntervalTicks = 2;
        if (observeRadius < 8) observeRadius = 8;
        if (observeRadius > 96) observeRadius = 96;
        if (npcRecallDistance < 16) npcRecallDistance = 16;
        if (npcRecallDistance > 256) npcRecallDistance = 256;
        if (npcRecoveryTicks < 40) npcRecoveryTicks = 40;
        if (npcRecoveryTicks > 1200) npcRecoveryTicks = 1200;
        if (!List.of("owner", "survival", "creative").contains(npcMaterialMode)) npcMaterialMode = "owner";
        if (npcSkinPath == null || npcSkinPath.isBlank()) npcSkinPath = "config/minecraft-codex-companion-skin.png";
        if (hostileEntityAllowlist == null) hostileEntityAllowlist = new ArrayList<>();
        if (companionId == null || companionId.isBlank()) companionId = "codex-forge";
        name = normalizeName(name);
        if (ownerName == null) ownerName = "";
    }

    private static Path path() {
        return Path.of("config").resolve(FILE_NAME);
    }

    static String normalizeName(String value) {
        if (value == null || value.isBlank() || value.matches("^(?:\\?|？|\\uFFFD)+$")) return "Codex";
        return value;
    }

    private static boolean isNeoForge() {
        try {
            Class.forName("net.neoforged.fml.loading.FMLLoader", false, BridgeConfig.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
