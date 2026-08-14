package cn.codex.minecraftbridge.client;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import cn.codex.minecraftbridge.forge.client.ForgeNpcActor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientBridgeEvents {
    private static final BridgeClient CLIENT = new BridgeClient(BridgeConfig.load(), ForgeNpcActor::new);
    private static final BackgroundPauseLease BACKGROUND_PAUSE_LEASE = new BackgroundPauseLease();

    private ClientBridgeEvents() {
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        BACKGROUND_PAUSE_LEASE.clear();
        CLIENT.onLogin();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        BACKGROUND_PAUSE_LEASE.clear();
        CLIENT.onLogout();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            keepSingleplayerTasksRunning();
            CLIENT.tick();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
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
        // Only signed player messages belong here. Local T chat is captured by
        // ClientChatEvent, so its server echo must not create a second AI turn.
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

    private static void keepSingleplayerTasksRunning() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            BACKGROUND_PAUSE_LEASE.clear();
            return;
        }
        boolean windowActive = minecraft.isWindowActive();
        if (windowActive) BACKGROUND_PAUSE_LEASE.clear();
        if (!windowActive && minecraft.mouseHandler.isMouseGrabbed()) {
            minecraft.mouseHandler.releaseMouse();
        }
        if (!CLIENT.config().keepSingleplayerRunningInBackground) return;
        if (minecraft.options.pauseOnLostFocus) minecraft.options.pauseOnLostFocus = false;
        if (!windowActive && minecraft.screen instanceof PauseScreen
            && !BACKGROUND_PAUSE_LEASE.isActive(System.nanoTime())) {
            // Disabling pause-on-focus-loss does not resume an already-opened
            // pause screen. Close only that vanilla screen while the game is
            // in the background; chats, inventories and mod screens remain
            // untouched.
            minecraft.setScreen(null);
        }
    }

    public static void armBackgroundPauseLease(long requestedMillis) {
        BACKGROUND_PAUSE_LEASE.arm(System.nanoTime(), requestedMillis);
    }

    public static void acknowledgeChatDelivery(String deliveryId) {
        CLIENT.acknowledgeChatDelivery(deliveryId);
    }
}
