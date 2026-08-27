package cn.codex.minecraftbridge.client;

/** Test-only desktop safety policy shared by both supported Minecraft clients. */
public final class ClientDesktopSafetyLiveFixture {
    private static final String LIVE_FIXTURE_ENVIRONMENT = "MC_ENABLE_LIVE_FIXTURES";

    private ClientDesktopSafetyLiveFixture() {
    }

    /** Prevents Minecraft from confining the desktop cursor during an explicitly enabled live test. */
    public static boolean suppressMouseGrab() {
        return suppressMouseGrab(System.getenv(LIVE_FIXTURE_ENVIRONMENT));
    }

    static boolean suppressMouseGrab(String configuredValue) {
        return "1".equals(configuredValue);
    }
}
