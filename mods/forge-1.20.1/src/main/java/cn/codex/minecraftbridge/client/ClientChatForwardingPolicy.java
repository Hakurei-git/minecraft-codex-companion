package cn.codex.minecraftbridge.client;

/** Keeps command feedback and other system messages out of the AI chat loop. */
final class ClientChatForwardingPolicy {
    private ClientChatForwardingPolicy() {
    }

    static boolean shouldForward(boolean systemMessage, String sender, String companionName) {
        if (systemMessage || sender == null || sender.isBlank()) return false;
        return companionName == null || !sender.equalsIgnoreCase(companionName.trim());
    }
}
