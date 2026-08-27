package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientRecoveryLiveFixtureTest {
    @Test
    void recognizesOnlyTheFixedRespawnFixture() {
        JsonObject request = new JsonObject();
        request.addProperty("suite", "client-recovery");
        request.addProperty("mode", "respawn");

        assertTrue(ClientRecoveryLiveFixture.matchesRespawn(request));

        request.addProperty("mode", "teleport");
        assertFalse(ClientRecoveryLiveFixture.matchesRespawn(request));
    }

    @Test
    void rejectsMissingAndMalformedFields() {
        assertFalse(ClientRecoveryLiveFixture.matchesRespawn(null));
        assertFalse(ClientRecoveryLiveFixture.matchesRespawn(new JsonObject()));

        JsonObject malformed = new JsonObject();
        malformed.addProperty("suite", 7);
        malformed.addProperty("mode", "respawn");
        assertFalse(ClientRecoveryLiveFixture.matchesRespawn(malformed));
    }
}
