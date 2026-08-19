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
import java.util.Objects;
import java.util.Optional;

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
    /** Number of ordinary safe food items the NPC keeps in its backpack. Set to 0 to disable. */
    public int npcFoodReserveCount = 8;
    /** NPC resources are survival by default even when the owner observes in creative mode. */
    public String npcMaterialMode = "survival";
    public String npcSkinPath = "config/minecraft-codex-companion-skin.png";
    public boolean keepSingleplayerRunningInBackground = true;

    public static BridgeConfig load() {
        Path path = path();
        Optional<BridgeConfig> loaded = read(path, false);
        if (loaded.isPresent()) return loaded.get();
        BridgeConfig created = new BridgeConfig();
        created.save();
        return created;
    }

    static Optional<BridgeConfig> readReady(Path path) {
        return read(path, true);
    }

    private static Optional<BridgeConfig> read(Path path, boolean requireReady) {
        if (!Files.isRegularFile(path)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            BridgeConfig loaded = GSON.fromJson(reader, BridgeConfig.class);
            if (loaded == null) return Optional.empty();
            loaded.normalize();
            return !requireReady || loaded.isReady() ? Optional.of(loaded) : Optional.empty();
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
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

    boolean sameValues(BridgeConfig other) {
        return other != null
            && Objects.equals(serverUrl, other.serverUrl)
            && Objects.equals(token, other.token)
            && Objects.equals(companionId, other.companionId)
            && Objects.equals(name, other.name)
            && Objects.equals(ownerName, other.ownerName)
            && autoReconnect == other.autoReconnect
            && snapshotIntervalTicks == other.snapshotIntervalTicks
            && observeRadius == other.observeRadius
            && allowPvp == other.allowPvp
            && allowBreakingContainers == other.allowBreakingContainers
            && Objects.equals(hostileEntityAllowlist, other.hostileEntityAllowlist)
            && npcAutoSpawn == other.npcAutoSpawn
            && npcRecallDistance == other.npcRecallDistance
            && npcRecoveryTicks == other.npcRecoveryTicks
            && npcFoodReserveCount == other.npcFoodReserveCount
            && Objects.equals(npcMaterialMode, other.npcMaterialMode)
            && Objects.equals(npcSkinPath, other.npcSkinPath)
            && keepSingleplayerRunningInBackground == other.keepSingleplayerRunningInBackground;
    }

    boolean connectionSettingsDiffer(BridgeConfig other) {
        return other == null
            || !Objects.equals(serverUrl, other.serverUrl)
            || !Objects.equals(token, other.token)
            || !Objects.equals(companionId, other.companionId)
            || !Objects.equals(name, other.name)
            || !Objects.equals(ownerName, other.ownerName);
    }

    void applyFrom(BridgeConfig source) {
        source.normalize();
        serverUrl = source.serverUrl;
        token = source.token;
        companionId = source.companionId;
        name = source.name;
        ownerName = source.ownerName;
        autoReconnect = source.autoReconnect;
        snapshotIntervalTicks = source.snapshotIntervalTicks;
        observeRadius = source.observeRadius;
        allowPvp = source.allowPvp;
        allowBreakingContainers = source.allowBreakingContainers;
        hostileEntityAllowlist = new ArrayList<>(source.hostileEntityAllowlist);
        npcAutoSpawn = source.npcAutoSpawn;
        npcRecallDistance = source.npcRecallDistance;
        npcRecoveryTicks = source.npcRecoveryTicks;
        npcFoodReserveCount = source.npcFoodReserveCount;
        npcMaterialMode = source.npcMaterialMode;
        npcSkinPath = source.npcSkinPath;
        keepSingleplayerRunningInBackground = source.keepSingleplayerRunningInBackground;
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
        if (npcFoodReserveCount < 0) npcFoodReserveCount = 0;
        if (npcFoodReserveCount > 64) npcFoodReserveCount = 64;
        if (!List.of("owner", "survival", "creative").contains(npcMaterialMode)) npcMaterialMode = "survival";
        if (npcSkinPath == null || npcSkinPath.isBlank()) npcSkinPath = "config/minecraft-codex-companion-skin.png";
        if (hostileEntityAllowlist == null) hostileEntityAllowlist = new ArrayList<>();
        if (companionId == null || companionId.isBlank()) companionId = "codex-forge";
        name = normalizeName(name);
        if (ownerName == null) ownerName = "";
    }

    static Path path() {
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
