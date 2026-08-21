package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;

public final class LocalPlayerActor implements CompanionActor {
    private final BridgeConfig config;
    private final SnapshotFactory snapshots = new SnapshotFactory();
    private final TaskEngine tasks;

    public LocalPlayerActor(ProgressSink progressSink, ResultSink resultSink, BridgeConfig config) {
        this.config = config;
        this.tasks = new TaskEngine(
            (taskId, progress, message) -> progressSink.send(
                taskId,
                progress,
                message,
                "active",
                BridgeTaskDetails.empty()
            ),
            (taskId, ok, message, code) -> resultSink.send(
                taskId,
                ok,
                message,
                code,
                BridgeTaskDetails.empty()
            ),
            config
        );
    }

    @Override
    public void onLogout() {
        tasks.emergencyStop();
    }

    @Override
    public void tick(Minecraft minecraft) {
        tasks.tick(minecraft);
    }

    @Override
    public JsonObject snapshot(Minecraft minecraft) {
        JsonObject snapshot = snapshots.capture(minecraft, config, tasks.status());
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
    public void start(JsonObject task, JsonObject buildPlan) {
        tasks.start(task, buildPlan);
    }

    @Override
    public void cancel(String taskId, String reason) {
        tasks.cancel(taskId, reason);
    }

    @Override
    public void emergencyStop() {
        tasks.emergencyStop();
    }

    @Override
    public void connectionLost() {
        tasks.connectionLost();
    }

    @Override
    public void speak(Minecraft minecraft, String message, String deliveryId) {
        minecraft.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
            "<" + config.name + "> " + message
        ));
    }

    @Override
    public boolean synchronousChatDelivery() {
        return true;
    }
}
