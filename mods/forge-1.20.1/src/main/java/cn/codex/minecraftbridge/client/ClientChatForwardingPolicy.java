package cn.codex.minecraftbridge.client;

/** Keeps local commands, chat echoes and mod output out of the AI chat loop. */
final class ClientChatForwardingPolicy {
    private ClientChatForwardingPolicy() {
    }

    static boolean shouldForwardLocal(String message) {
        if (message == null) return false;
        String cleaned = message.trim();
        return !cleaned.isEmpty() && !cleaned.startsWith("/");
    }

    static boolean shouldForwardRemote(
        String sender,
        String localPlayerName,
        String companionName,
        String message
    ) {
        if (sender == null || sender.isBlank() || message == null || message.isBlank()) return false;
        if (sameName(sender, localPlayerName) || sameName(sender, companionName)) return false;
        return !sender.trim().equalsIgnoreCase("Baritone");
    }

    private static boolean sameName(String left, String right) {
        return right != null && !right.isBlank() && left.trim().equalsIgnoreCase(right.trim());
    }
}
