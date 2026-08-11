package cn.codex.minecraftbridge.forge.client;

import cn.codex.minecraftbridge.client.BridgeConfig;
import cn.codex.minecraftbridge.client.ClientBridgeEvents;
import cn.codex.minecraftbridge.client.CompanionActor;
import cn.codex.minecraftbridge.client.BridgeTaskDetails;
import cn.codex.minecraftbridge.forge.CodexNetwork;
import cn.codex.minecraftbridge.forge.CodexNpcEntity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

public final class ForgeNpcActor implements CompanionActor {
    private static ForgeNpcActor active;

    private final ProgressSink progressSink;
    private final ResultSink resultSink;
    private final BridgeConfig config;
    private JsonObject latestSnapshot;
    private int ticks;

    public ForgeNpcActor(ProgressSink progressSink, ResultSink resultSink, BridgeConfig config) {
        this.progressSink = progressSink;
        this.resultSink = resultSink;
        this.config = config;
        active = this;
    }

    @Override
    public void onLogin() {
        latestSnapshot = null;
        ticks = 0;
    }

    @Override
    public void onLogout() {
        latestSnapshot = null;
    }

    @Override
    public boolean ready(Minecraft minecraft) {
        return latestSnapshot != null;
    }

    @Override
    public void tick(Minecraft minecraft) {
        ticks++;
        if (latestSnapshot == null && ticks % 20 == 1) CodexNetwork.sendToServer("ensure", new JsonObject());
        else if (ticks % 100 == 0) CodexNetwork.sendToServer("snapshot", new JsonObject());
    }

    @Override
    public JsonObject snapshot(Minecraft minecraft) {
        if (latestSnapshot == null) throw new IllegalStateException("Codex NPC snapshot is not ready");
        JsonObject snapshot = latestSnapshot.deepCopy();
        snapshot.addProperty("clientUiState", clientUiState(minecraft));
        return snapshot;
    }

    private String clientUiState(Minecraft minecraft) {
        if (minecraft.screen == null) return "gameplay";
        if (minecraft.screen instanceof ChatScreen) return "chat";
        if (minecraft.screen instanceof PauseScreen) return "pause";
        if (minecraft.screen instanceof DeathScreen) return "death";
        return "other";
    }

    @Override
    public void decorateDescriptor(JsonObject companion) {
        companion.addProperty("embodiment", "in-world-npc");
        companion.addProperty("ownerName", config.ownerName);
        if (latestSnapshot != null && latestSnapshot.has("npcEntityUuid")) {
            companion.addProperty("entityUuid", latestSnapshot.get("npcEntityUuid").getAsString());
        }
    }

    @Override
    public void start(JsonObject task, JsonObject buildPlan) {
        JsonObject payload = new JsonObject();
        payload.add("task", task);
        if (buildPlan != null) payload.add("buildPlan", buildPlan);
        CodexNetwork.sendToServer("run-task", payload);
    }

    @Override
    public void cancel(String taskId, String reason) {
        JsonObject payload = new JsonObject();
        payload.addProperty("taskId", taskId);
        payload.addProperty("reason", reason);
        CodexNetwork.sendToServer("cancel-task", payload);
    }

    @Override
    public void emergencyStop() {
        CodexNetwork.sendToServer("stop", new JsonObject());
    }

    @Override
    public void connectionLost() {
        // The in-world NPC remains autonomous while the local control bridge reconnects.
    }

    @Override
    public void speak(Minecraft minecraft, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("message", message);
        CodexNetwork.sendToServer("speak", payload);
    }

    @Override
    public void control(String action) {
        CodexNetwork.sendToServer(action, new JsonObject());
    }

    @Override
    public void runLiveFixture(JsonObject request) {
        CodexNetwork.sendToServer("live-fixture", request.deepCopy());
    }

    public static void accept(String type, String rawPayload) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(rawPayload).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }
        if (type.equals("book-dragon-input-reset")) {
            BookDragonClientControl.reset(payload);
            return;
        }
        if (type.equals("background-pause-arm")) {
            long leaseMillis = payload.has("leaseMillis") ? payload.get("leaseMillis").getAsLong() : 0L;
            ClientBridgeEvents.armBackgroundPauseLease(leaseMillis);
            return;
        }
        ForgeNpcActor actor = active;
        if (actor == null) return;
        switch (type) {
            case "snapshot" -> actor.latestSnapshot = payload;
            case "task-progress" -> actor.progressSink.send(
                payload.get("taskId").getAsString(),
                payload.get("progress").getAsDouble(),
                payload.get("message").getAsString(),
                payload.has("phase") ? payload.get("phase").getAsString() : "active",
                BridgeTaskDetails.from(payload)
            );
            case "task-result" -> actor.resultSink.send(
                payload.get("taskId").getAsString(),
                payload.get("ok").getAsBoolean(),
                payload.get("message").getAsString(),
                payload.has("code") ? payload.get("code").getAsString() : null,
                BridgeTaskDetails.from(payload)
            );
            case "speech" -> {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.gui.getChat().addMessage(Component.literal(
                    "<" + payload.get("name").getAsString() + "> " + payload.get("message").getAsString()
                ));
                if (minecraft.level != null && minecraft.player != null) {
                    minecraft.level.getEntitiesOfClass(
                        CodexNpcEntity.class,
                        minecraft.player.getBoundingBox().inflate(128)
                    ).forEach(CodexNpcEntity::triggerSpeechAnimation);
                }
            }
            default -> {
            }
        }
    }
}
