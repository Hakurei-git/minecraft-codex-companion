package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientChatForwardingPolicyTest {
    @Test
    void rejectsSystemCommandFeedbackEvenWhenItWasPreviouslyAttributedToTheOwner() {
        assertFalse(ClientChatForwardingPolicy.shouldForward(true, "PlayerOne", "Codex"));
    }

    @Test
    void rejectsCompanionEchoes() {
        assertFalse(ClientChatForwardingPolicy.shouldForward(false, "cOdEx", "Codex"));
    }

    @Test
    void forwardsRealOwnerChat() {
        assertTrue(ClientChatForwardingPolicy.shouldForward(false, "PlayerOne", "Codex"));
    }
}
