package cn.codex.minecraftbridge.client;

import cn.codex.minecraftbridge.NeoMinecraftCodexBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;

@EventBusSubscriber(modid = NeoMinecraftCodexBridge.MOD_ID, value = Dist.CLIENT)
public final class NeoClientBridgeEvents {
    private static final int CONFIG_RELOAD_INTERVAL_TICKS = 20;
    private static final BridgeConfig CONFIG = BridgeConfig.load();
    private static final BridgeClient CLIENT = new BridgeClient(CONFIG);
    private static final BridgeConfigWatcher CONFIG_WATCHER = new BridgeConfigWatcher(BridgeConfig.path());
    private static int configReloadTicks;

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
        if (++configReloadTicks >= CONFIG_RELOAD_INTERVAL_TICKS) {
            configReloadTicks = 0;
            CONFIG_WATCHER.changedConfiguration(CLIENT.config()).ifPresent(CLIENT::applyConfig);
        }
        CLIENT.tick();
    }

    @SubscribeEvent
    public static void onLocalChat(ClientChatEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        String sender = minecraft.player.getGameProfile().getName();
        String message = event.getMessage();
        if (!ClientChatForwardingPolicy.shouldForwardLocal(message)) return;
        CLIENT.onIncomingChat(sender, message);
    }

    @SubscribeEvent
    public static void onRemoteChat(ClientChatReceivedEvent event) {
        if (!(event instanceof ClientChatReceivedEvent.Player) || event.isSystem()) return;
        Minecraft minecraft = Minecraft.getInstance();
        String sender = resolveSender(minecraft, event.getSender());
        String localPlayer = minecraft.player == null ? null : minecraft.player.getGameProfile().getName();
        if (!ClientChatForwardingPolicy.shouldForwardRemote(
            sender,
            localPlayer,
            CLIENT.config().name,
            event.getMessage().getString()
        )) return;
        CLIENT.onIncomingChat(sender, event.getMessage().getString());
    }

    private static String resolveSender(Minecraft minecraft, UUID senderId) {
        if (minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(senderId);
            if (info != null) return info.getProfile().getName();
        }
        return null;
    }
}
