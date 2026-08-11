package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

public interface CompanionActor {
    @FunctionalInterface
    interface ProgressSink {
        void send(
            String taskId,
            double progress,
            String message,
            String phase,
            BridgeTaskDetails details
        );
    }

    @FunctionalInterface
    interface ResultSink {
        void send(String taskId, boolean ok, String message, String code, BridgeTaskDetails details);
    }

    default void onLogin() {
    }

    default void onLogout() {
    }

    default boolean ready(Minecraft minecraft) {
        return minecraft.player != null && minecraft.level != null;
    }

    void tick(Minecraft minecraft);

    JsonObject snapshot(Minecraft minecraft);

    default void decorateDescriptor(JsonObject companion) {
        companion.addProperty("embodiment", "remote-player");
    }

    void start(JsonObject task, JsonObject buildPlan);

    void cancel(String taskId, String reason);

    void emergencyStop();

    void connectionLost();

    void speak(Minecraft minecraft, String message);

    default void control(String action) {
    }

    default void runLiveFixture(JsonObject request) {
    }
}
