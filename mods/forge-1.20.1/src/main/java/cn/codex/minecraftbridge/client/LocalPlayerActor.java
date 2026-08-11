package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

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
        return snapshots.capture(minecraft, config, tasks.status());
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
    public void speak(Minecraft minecraft, String message) {
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendChat(message);
        }
    }
}
