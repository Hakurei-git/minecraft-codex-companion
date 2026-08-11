package cn.codex.minecraftbridge.client;

import cn.codex.minecraftbridge.NeoMinecraftCodexBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;

@EventBusSubscriber(modid = NeoMinecraftCodexBridge.MOD_ID, value = Dist.CLIENT)
public final class NeoClientBridgeEvents {
    private static final BridgeClient CLIENT = new BridgeClient(BridgeConfig.load());

    private NeoClientBridgeEvents() {
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        CLIENT.onLogin();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CLIENT.onLogout();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        CLIENT.tick();
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent.Player event) {
        Minecraft minecraft = Minecraft.getInstance();
        String sender = resolveSender(minecraft, event.getSender());
        if (sender.equalsIgnoreCase(CLIENT.config().name)) return;
        CLIENT.onIncomingChat(sender, event.getMessage().getString());
    }

    private static String resolveSender(Minecraft minecraft, UUID senderId) {
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(senderId);
            if (info != null) return info.getProfile().getName();
        }
        return "Player";
    }
}
