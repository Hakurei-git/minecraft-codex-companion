public final class HmclCrashProbeAgentTest {
    private HmclCrashProbeAgentTest() {
    }

    public static void main(String[] arguments) {
        String report = "java.lang.IllegalStateException: local-path-omitted\n"
                + "\tat org.jackhuang.hmcl.game.LauncherHelper.launch0(LauncherHelper.java:1)\n"
                + "Caused by: java.nio.file.AccessDeniedException: sensitive-local-value\n"
                + "\tat java.nio.file.Files.newByteChannel(Files.java:2)\n"
                + "https://example.invalid/private?token=not-exported";
        String sanitized = HmclCrashProbeAgent.sanitizeCrashText(report);
        assertContains(sanitized, "java.lang.IllegalStateException");
        assertContains(sanitized, "java.nio.file.AccessDeniedException");
        assertContains(sanitized, "org.jackhuang.hmcl.game.LauncherHelper.launch0");
        assertContains(sanitized, "java.nio.file.Files.newByteChannel");
        assertContains(sanitized, "ACCESS_DENIED");
        assertMissing(sanitized, "sensitive-local-value");
        assertMissing(sanitized, "example.invalid");
        assertMissing(sanitized, "token");
        System.out.println("HMCL crash-probe sanitization tests passed");
    }

    private static void assertContains(String value, String expected) {
        if (!value.contains(expected)) {
            throw new AssertionError("Missing sanitized diagnostic: " + expected);
        }
    }

    private static void assertMissing(String value, String forbidden) {
        if (value.contains(forbidden)) {
            throw new AssertionError("Sensitive crash detail escaped sanitization: " + forbidden);
        }
    }
}
