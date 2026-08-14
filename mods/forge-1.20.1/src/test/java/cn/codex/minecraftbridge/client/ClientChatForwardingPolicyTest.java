package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChatForwardingPolicyTest {
    @Test
    void forwardsLocalTChatWithoutDependingOnServerSignatures() {
        assertTrue(ClientChatForwardingPolicy.shouldForwardLocal("让我们回家"));
    }

    @Test
    void rejectsBlankLocalLinesAndCommands() {
        assertFalse(ClientChatForwardingPolicy.shouldForwardLocal("  "));
        assertFalse(ClientChatForwardingPolicy.shouldForwardLocal("/tp @s 0 80 0"));
    }

    @Test
    void rejectsTheLocalPlayersServerEcho() {
        assertFalse(ClientChatForwardingPolicy.shouldForwardRemote("PlayerOne", "playerone", "Companion", "重复回显"));
    }

    @Test
    void rejectsCompanionAndBaritoneOutput() {
        assertFalse(ClientChatForwardingPolicy.shouldForwardRemote("cOmPaNiOn", "PlayerOne", "Companion", "已完成"));
        assertFalse(ClientChatForwardingPolicy.shouldForwardRemote("Baritone", "PlayerOne", "Companion", "Goal: mine"));
    }

    @Test
    void forwardsAnotherRealPlayersChat() {
        assertTrue(ClientChatForwardingPolicy.shouldForwardRemote("Alex", "PlayerOne", "Companion", "一起走"));
    }
}
