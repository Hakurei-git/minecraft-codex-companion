package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientDesktopSafetyLiveFixtureTest {
    @Test
    void suppressesMouseGrabOnlyForTheExactLiveFixtureOptIn() {
        assertTrue(ClientDesktopSafetyLiveFixture.suppressMouseGrab("1"));
        assertFalse(ClientDesktopSafetyLiveFixture.suppressMouseGrab(null));
        assertFalse(ClientDesktopSafetyLiveFixture.suppressMouseGrab(""));
        assertFalse(ClientDesktopSafetyLiveFixture.suppressMouseGrab("true"));
        assertFalse(ClientDesktopSafetyLiveFixture.suppressMouseGrab("0"));
    }
}
