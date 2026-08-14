package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BridgeClientChatOutboxTest {
    @Test
    void queuesLocalChatWhileTheControlServiceIsOffline() {
        BridgeClient client = client();

        client.onIncomingChat("PlayerOne", "稍后也要送达");
        client.onIncomingChat("PlayerOne", "  ");

        assertEquals(1, client.pendingChatCount());
    }

    @Test
    void boundsOfflineChatWithoutCollapsingDistinctMessages() {
        BridgeClient client = client();

        for (int index = 0; index < 70; index++) {
            client.onIncomingChat("PlayerOne", "消息 " + index);
        }

        assertEquals(64, client.pendingChatCount());
    }

    private static BridgeClient client() {
        return new BridgeClient(new BridgeConfig(), (progress, result, config) -> new CompanionActor() {
            @Override
            public void tick(Minecraft minecraft) {
            }

            @Override
            public JsonObject snapshot(Minecraft minecraft) {
                return new JsonObject();
            }

            @Override
            public void start(JsonObject task, JsonObject buildPlan) {
            }

            @Override
            public void cancel(String taskId, String reason) {
            }

            @Override
            public void emergencyStop() {
            }

            @Override
            public void connectionLost() {
            }

            @Override
            public void speak(Minecraft minecraft, String message, String deliveryId) {
            }
        });
    }
}
