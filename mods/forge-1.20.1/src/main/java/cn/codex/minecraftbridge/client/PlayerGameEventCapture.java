package cn.codex.minecraftbridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Shared by Forge and NeoForge; NPC output and action-bar messages never enter here. */
final class PlayerGameEventCapture {
    private final PlayerGameEventPolicy policy = new PlayerGameEventPolicy();

    void reset() { policy.reset(); }

    void onSystemMessage(Component message, BridgeClient client) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || message == null) return;
        String player = minecraft.player.getGameProfile().getName();
        forward(message, message.getString(), player, client);
    }

    private boolean forward(Component component, String text, String player, BridgeClient client) {
        if (component.getContents() instanceof TranslatableContents translation) {
            Object[] args = translation.getArgs();
            String subject = args.length == 0 ? null
                : args[0] instanceof Component name ? name.getString() : String.valueOf(args[0]);
            String kind = PlayerGameEventPolicy.classify(translation.getKey(), subject, player);
            if (kind != null) {
                if (kind.equals("death") && !policy.deathBroadcastReceived()) return true;
                client.onGameEvent(kind, player, text, translation.getKey());
                return true;
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (forward(sibling, text, player, client)) return true;
        }
        return false;
    }

    void tick(BridgeClient client) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        String event = policy.tick(minecraft.player.isAlive());
        if (event == null) return;
        String player = minecraft.player.getGameProfile().getName();
        client.onGameEvent(event, player, player + (event.equals("death") ? " 已死亡" : " 已重生"), null);
    }
}
