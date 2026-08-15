package cn.codex.minecraftbridge.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class BridgeClient {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_TERMINAL_OUTBOX = 64;
    private static final int MAX_CHAT_OUTBOX = 64;
    private static final List<String> CAPABILITIES = List.of(
        "chat", "observe", "move", "follow", "combat", "gather", "craft", "smelt",
        "farm", "storage", "fish", "sleep", "build", "commands", "dragon-care", "multi-bot"
    );

    private final BridgeConfig config;
    private final CompanionActor actor;
    private final BoundedMessageOutbox<JsonObject> terminalOutbox = new BoundedMessageOutbox<>(MAX_TERMINAL_OUTBOX);
    private final BoundedMessageOutbox<JsonObject> chatOutbox = new BoundedMessageOutbox<>(MAX_CHAT_OUTBOX);
    private volatile BridgeChannel socket;
    private volatile BridgeChannel announcedSocket;
    private volatile long connectingGeneration = -1;
    private volatile boolean sessionActive;
    private volatile long configurationGeneration;
    private volatile long connectionAttempts;
    private volatile String lastConnectionFailureCategory = "none";
    private long ticks;
    private volatile long reconnectAfterTick;
    private long lastHeartbeatTick;

    public BridgeClient(BridgeConfig config) {
        this(config, LocalPlayerActor::new);
    }

    public BridgeClient(BridgeConfig config, ActorFactory actorFactory) {
        this.config = config;
        this.actor = actorFactory.create(this::sendTaskProgress, this::sendTaskResult, config);
    }

    public BridgeConfig config() {
        return config;
    }

    void applyConfig(BridgeConfig updated) {
        if (updated == null || !updated.isReady() || config.sameValues(updated)) return;
        boolean reconnect = config.connectionSettingsDiffer(updated);
        config.applyFrom(updated);
        if (!reconnect) return;

        configurationGeneration++;
        connectingGeneration = -1;
        reconnectAfterTick = ticks;
        BridgeChannel current = socket;
        socket = null;
        announcedSocket = null;
        if (current != null) {
            actor.connectionLost();
            current.sendClose(1000, "bridge configuration changed");
        }
    }

    public void onLogin() {
        sessionActive = true;
        reconnectAfterTick = 0;
        actor.onLogin();
    }

    public void onLogout() {
        sessionActive = false;
        actor.onLogout();
        BridgeChannel current = socket;
        socket = null;
        announcedSocket = null;
        if (current != null) current.sendClose(1000, "logout");
    }

    public void tick() {
        ticks++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        actor.tick(minecraft);
        if (!actor.ready(minecraft)) return;
        if (sessionActive && !isConnected() && connectingGeneration < 0
            && config.autoReconnect && config.isReady() && ticks >= reconnectAfterTick) connect();
        if (!isConnected()) return;
        if (ticks % config.snapshotIntervalTicks == 0) sendSnapshot(minecraft);
        if (ticks - lastHeartbeatTick >= 100) {
            JsonObject heartbeat = envelope("heartbeat");
            heartbeat.addProperty("at", Instant.now().toString());
            send(heartbeat);
            lastHeartbeatTick = ticks;
        }
    }

    public void onIncomingChat(String sender, String message) {
        if (sender == null || sender.isBlank() || message == null || message.isBlank()) return;
        String cleaned = message;
        String prefix = "<" + sender + "> ";
        if (cleaned.startsWith(prefix)) cleaned = cleaned.substring(prefix.length());
        if (cleaned.isBlank()) return;
        JsonObject chat = envelope("chat");
        chat.addProperty("messageId", UUID.randomUUID().toString());
        chat.addProperty("sender", sender);
        chat.addProperty("message", cleaned);
        chat.addProperty("at", Instant.now().toString());
        sendChat(chat);
    }

    private void connect() {
        long generation = configurationGeneration;
        connectingGeneration = generation;
        connectionAttempts++;
        connectWithBlockingLoopback(generation, config.serverUrl);
    }

    private void connectWithBlockingLoopback(long generation, String serverUrl) {
        try {
            LoopbackWebSocketClient.connect(
                URI.create(serverUrl),
                Duration.ofSeconds(5),
                new BlockingListener(generation)
            ).whenComplete((connected, error) -> {
                if (connectingGeneration == generation) connectingGeneration = -1;
                if (generation != configurationGeneration) {
                    if (connected != null) connected.sendClose(1000, "stale bridge configuration");
                    return;
                }
                if (error != null) {
                    lastConnectionFailureCategory = connectionFailureCategory(error);
                    scheduleReconnect(null);
                }
            });
        } catch (RuntimeException error) {
            if (connectingGeneration == generation) connectingGeneration = -1;
            if (generation != configurationGeneration) return;
            lastConnectionFailureCategory = connectionFailureCategory(error);
            scheduleReconnect(null);
        }
    }

    private void sendHello(BridgeChannel connection) {
        if (socket != connection || !connection.isOpen()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocolVersion", 1);
        hello.addProperty("token", config.token);
        JsonObject companion = new JsonObject();
        companion.addProperty("id", config.companionId);
        companion.addProperty("name", config.name);
        companion.addProperty("backend", config.backend());
        companion.addProperty("gameVersion", config.gameVersion());
        companion.addProperty("loader", config.loader());
        companion.addProperty("bridgeVersion", bridgeVersion());
        JsonArray capabilities = new JsonArray();
        CAPABILITIES.forEach(capabilities::add);
        companion.add("capabilities", capabilities);
        companion.add("snapshot", actor.snapshot(minecraft));
        actor.decorateDescriptor(companion);
        hello.add("companion", companion);
        connection.sendText(GSON.toJson(hello)).whenComplete((ignored, error) -> {
            if (error != null) {
                scheduleReconnect(connection);
                return;
            }
            if (socket != connection || !sessionActive) return;
            announcedSocket = connection;
            flushChatOutbox(connection);
            flushTerminalOutbox(connection);
        });
    }

    private static String bridgeVersion() {
        String version = BridgeClient.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private void sendSnapshot(Minecraft minecraft) {
        JsonObject message = envelope("snapshot");
        message.add("snapshot", actor.snapshot(minecraft));
        send(message);
    }

    private JsonObject envelope(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("companionId", config.companionId);
        return object;
    }

    private void handleCommand(BridgeChannel source, String raw) {
        JsonObject command = JsonParser.parseString(raw).getAsJsonObject();
        String type = command.get("type").getAsString();
        Minecraft.getInstance().execute(() -> {
            if (!sessionActive || socket != source) return;
            switch (type) {
                case "run-task" -> actor.start(command.getAsJsonObject("task"), command.getAsJsonObject("buildPlan"));
                case "cancel-task" -> actor.cancel(
                    command.get("taskId").getAsString(),
                    command.has("reason") ? command.get("reason").getAsString() : "任务已取消"
                );
                case "chat" -> sendGameChat(
                    command.get("message").getAsString(),
                    command.has("deliveryId") ? command.get("deliveryId").getAsString() : null
                );
                case "npc-control" -> actor.control(command.get("action").getAsString());
                case "live-fixture" -> {
                    LOGGER.info(
                        "Forwarding live fixture {}:{} to the integrated server",
                        command.has("suite") ? command.get("suite").getAsString() : "unknown",
                        command.has("mode") ? command.get("mode").getAsString() : "unknown"
                    );
                    actor.runLiveFixture(command);
                }
                case "emergency-stop" -> {
                    actor.emergencyStop();
                    if (command.has("disconnect") && command.get("disconnect").getAsBoolean()) disconnectGame();
                }
                default -> {
                }
            }
        });
    }

    private void sendGameChat(String message, String deliveryId) {
        actor.speak(Minecraft.getInstance(), message, deliveryId);
    }

    public void acknowledgeChatDelivery(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) return;
        JsonObject acknowledgement = envelope("chat-delivered");
        acknowledgement.addProperty("deliveryId", deliveryId);
        send(acknowledgement);
    }

    private void disconnectGame() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().getConnection().disconnect(Component.literal("Codex emergency stop"));
        }
    }

    private void sendTaskProgress(
        String taskId,
        double progress,
        String message,
        String phase,
        BridgeTaskDetails details
    ) {
        JsonObject result = envelope("task-progress");
        result.addProperty("taskId", taskId);
        result.addProperty("progress", Math.max(0, Math.min(1, progress)));
        result.addProperty("message", message);
        if (phase != null && !phase.isBlank()) result.addProperty("phase", phase);
        if (details != null) details.appendTo(result);
        send(result);
    }

    private void sendTaskResult(
        String taskId,
        boolean ok,
        String message,
        String code,
        BridgeTaskDetails details
    ) {
        JsonObject result = envelope("task-result");
        result.addProperty("taskId", taskId);
        result.addProperty("ok", ok);
        result.addProperty("message", message);
        if (code != null && !code.isBlank()) result.addProperty("code", code);
        if (details != null) details.appendTo(result);
        sendTerminal(result, taskId);
    }

    private boolean isConnected() {
        BridgeChannel current = socket;
        return current != null && current.isOpen();
    }

    private void send(JsonObject message) {
        BridgeChannel current = socket;
        if (current != null && announcedSocket == current && current.isOpen()) {
            current.sendText(GSON.toJson(message)).whenComplete((ignored, error) -> {
                if (error != null) scheduleReconnect(current);
            });
        }
    }

    private void sendChat(JsonObject message) {
        String messageId = message.get("messageId").getAsString();
        BridgeChannel current = socket;
        if (current == null || announcedSocket != current || !current.isOpen()) {
            chatOutbox.put(messageId, message.deepCopy());
            return;
        }
        current.sendText(GSON.toJson(message)).whenComplete((ignored, error) -> {
            if (error == null) return;
            chatOutbox.put(messageId, message.deepCopy());
            scheduleReconnect(current);
        });
    }

    private void flushChatOutbox(BridgeChannel connection) {
        if (socket != connection || announcedSocket != connection || !connection.isOpen()) return;
        for (JsonObject message : chatOutbox.drain()) sendChat(message);
    }

    private void sendTerminal(JsonObject message, String taskId) {
        BridgeChannel current = socket;
        if (current == null || announcedSocket != current || !current.isOpen()) {
            terminalOutbox.put(taskId, message.deepCopy());
            return;
        }
        current.sendText(GSON.toJson(message)).whenComplete((ignored, error) -> {
            if (error == null) return;
            terminalOutbox.put(taskId, message.deepCopy());
            scheduleReconnect(current);
        });
    }

    private void flushTerminalOutbox(BridgeChannel connection) {
        if (socket != connection || announcedSocket != connection || !connection.isOpen()) return;
        for (JsonObject message : terminalOutbox.drain()) {
            String taskId = message.has("taskId") ? message.get("taskId").getAsString() : "unknown";
            sendTerminal(message, taskId);
        }
    }

    private void scheduleReconnect(BridgeChannel failedConnection) {
        if (failedConnection != null && socket != failedConnection) return;
        if (failedConnection == null && socket != null) return;
        socket = null;
        if (announcedSocket == failedConnection || failedConnection == null) announcedSocket = null;
        reconnectAfterTick = ticks + 100;
    }

    private final class BlockingListener implements LoopbackWebSocketClient.Listener {
        private final long generation;

        private BlockingListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(BridgeChannel channel) {
            handleOpen(channel, generation);
        }

        @Override
        public void onText(BridgeChannel channel, String message) {
            if (generation != configurationGeneration || socket != channel || !sessionActive) return;
            try {
                handleCommand(channel, message);
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void onClose(BridgeChannel channel, int statusCode, String reason) {
            if (generation != configurationGeneration || socket != channel) return;
            actor.connectionLost();
            scheduleReconnect(channel);
        }

        @Override
        public void onError(BridgeChannel channel, Throwable error) {
            if (generation != configurationGeneration || socket != channel) return;
            actor.connectionLost();
            scheduleReconnect(channel);
        }
    }

    private void handleOpen(BridgeChannel connection, long generation) {
        if (!sessionActive || generation != configurationGeneration) {
            connection.sendClose(1000, generation == configurationGeneration ? "session closed" : "stale bridge configuration");
            return;
        }
        socket = connection;
        announcedSocket = null;
        lastConnectionFailureCategory = "none";
        Minecraft.getInstance().execute(() -> sendHello(connection));
    }

    static String connectionFailureCategory(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof SocketTimeoutException) return "timeout";
        if (current instanceof ConnectException) return "connect-refused";
        if (current instanceof UnknownHostException) return "loopback-resolution";
        if (current instanceof SecurityException) return "access-denied";
        if (current instanceof IllegalArgumentException) return "invalid-config";
        if (current instanceof IOException) return "other-io";
        return "other";
    }

    int pendingChatCount() {
        return chatOutbox.size();
    }

    @FunctionalInterface
    public interface ActorFactory {
        CompanionActor create(CompanionActor.ProgressSink progressSink, CompanionActor.ResultSink resultSink, BridgeConfig config);
    }
}
