package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerGameEventPolicyTest {
    @Test void identifiesOnlyTheLocalBroadcastSubject() {
        assertEquals("death", PlayerGameEventPolicy.classify("death.attack.player", "Crow", "Crow"));
        assertEquals("death", PlayerGameEventPolicy.classify("death.fell.accident.ladder", "Crow", "Crow"));
        assertNull(PlayerGameEventPolicy.classify("death.attack.player", "Zombie", "Crow"));
        assertNull(PlayerGameEventPolicy.classify("death.attack.player", "Crow2", "Crow"));
        for (String type : new String[] { "task", "goal", "challenge" }) {
            assertEquals("advancement", PlayerGameEventPolicy.classify("chat.type.advancement." + type, "Crow", "Crow"));
        }
        assertNull(PlayerGameEventPolicy.classify("chat.type.text", "Crow", "Crow"));
        assertNull(PlayerGameEventPolicy.classify("commands.say", "Crow", "Crow"));
        assertNull(PlayerGameEventPolicy.classify("chat.type.advancement.task", "Alex", "Crow"));
    }

    @Test void detectsDeathOnceAndRespawnWithoutFiringOnLogin() {
        PlayerGameEventPolicy policy = new PlayerGameEventPolicy();
        assertNull(policy.tick(true));
        for (int index = 0; index < 9; index++) assertNull(policy.tick(false));
        assertEquals("death", policy.tick(false));
        assertFalse(policy.deathBroadcastReceived(), "A delayed detailed broadcast must not repeat the fallback");
        for (int index = 0; index < 30; index++) assertNull(policy.tick(false));
        assertEquals("respawn", policy.tick(true));
        assertNull(policy.tick(true));
        policy.reset();
        assertNull(policy.tick(true));
    }

    @Test void detailedBroadcastSuppressesTheFallbackDeathNotice() {
        PlayerGameEventPolicy policy = new PlayerGameEventPolicy();
        policy.tick(true);
        assertTrue(policy.deathBroadcastReceived());
        assertFalse(policy.deathBroadcastReceived());
        for (int index = 0; index < 30; index++) assertNull(policy.tick(false));
        assertEquals("respawn", policy.tick(true));
    }
}
