import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class HmclBackgroundLaunchAgentTest {
    private HmclBackgroundLaunchAgentTest() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 3 && arguments[0].equals("--create-bridge-jar")) {
            createBridgeJar(Path.of(arguments[1]), arguments[2]);
            return;
        }
        FakeManager.reset();
        Path configuredPath = Path.of("runtime", "isolated-minecraft").toAbsolutePath().normalize();

        Object first = HmclBackgroundLaunchAgent.ensureConfiguredGameDirectory(
                FakeManager.class,
                FakeDirectory.class,
                FakePortablePath.class,
                FakeLocalizedText.class,
                configuredPath
        );
        assertTrue(first instanceof FakeDirectory, "unregistered directory was not created");
        assertEquals(FakeManager.addCalls, 1, "unregistered directory was not added exactly once");
        assertEquals(
                ((FakeDirectory) first).getPath().toPath(),
                configuredPath,
                "registered directory path changed"
        );

        Object second = HmclBackgroundLaunchAgent.ensureConfiguredGameDirectory(
                FakeManager.class,
                FakeDirectory.class,
                FakePortablePath.class,
                FakeLocalizedText.class,
                configuredPath
        );
        assertTrue(second == first, "registered directory was not reused");
        assertEquals(FakeManager.addCalls, 1, "registered directory was duplicated");

        Path installFixture = Path.of(
                System.getProperty("java.io.tmpdir"),
                "minecraft-codex-companion-tests",
                "hmcl-background-agent-tests",
                "fixtures",
                "bridge-" + UUID.randomUUID()
        ).toAbsolutePath().normalize();
        Files.createDirectories(installFixture);
        Path gameDirectory = installFixture.resolve("minecraft");
        Path instance = gameDirectory.resolve("versions").resolve("Codex-Test");
        Files.createDirectories(instance.resolve("mods"));
        Files.writeString(instance.resolve("CODEX-CLONE.json"), "{}", StandardCharsets.UTF_8);
        Path sourceDirectory = installFixture.resolve("source");
        Files.createDirectories(sourceDirectory);
        Path sourceJar = sourceDirectory.resolve("minecraft_codex_bridge-forge-1.20.1-0.2.0.jar");
        createBridgeJarInChildProcess(sourceJar, "first");
        String sourceHash = sha256(sourceJar);
        String installStatus = HmclBackgroundLaunchAgent.installBridge(
                sourceJar,
                gameDirectory,
                "Codex-Test",
                sourceHash
        );
        Path installedJar = instance.resolve("mods").resolve(sourceJar.getFileName());
        assertTrue(Files.isRegularFile(installedJar), "bridge JAR was not installed");
        assertEquals(sha256(installedJar), sourceHash, "installed bridge hash changed");
        assertTrue(installStatus.contains("BACKUPS=0"), "first install reported an unexpected backup");

        Path secondSourceDirectory = installFixture.resolve("second-source");
        Files.createDirectories(secondSourceDirectory);
        Path secondSourceJar = secondSourceDirectory.resolve(sourceJar.getFileName());
        createBridgeJarInChildProcess(secondSourceJar, "second");
        String secondHash = sha256(secondSourceJar);
        String updateStatus = HmclBackgroundLaunchAgent.installBridge(
                secondSourceJar,
                gameDirectory,
                "Codex-Test",
                secondHash
        );
        assertEquals(sha256(installedJar), secondHash, "updated bridge hash changed");
        assertTrue(updateStatus.contains("BACKUPS=1"), "bridge update did not preserve the previous JAR");
        try (var backups = Files.walk(instance.resolve("bridge-backups"))) {
            assertTrue(
                    backups.anyMatch(path -> path.getFileName().equals(sourceJar.getFileName())),
                    "previous bridge JAR was not retained in a backup"
            );
        }

        boolean traversalRejected = false;
        try {
            HmclBackgroundLaunchAgent.installBridge(sourceJar, gameDirectory, "..", sourceHash);
        } catch (IllegalArgumentException expected) {
            traversalRejected = true;
        }
        assertTrue(traversalRejected, "version traversal was not rejected");

        Path nonFileSource = sourceDirectory.resolve(
                "minecraft_codex_bridge-forge-1.20.1-directory.jar"
        );
        Files.createDirectory(nonFileSource);
        boolean nonFileRejected = false;
        try {
            HmclBackgroundLaunchAgent.installBridge(
                    nonFileSource,
                    gameDirectory,
                    "Codex-Test",
                    sourceHash
            );
        } catch (Exception expected) {
            nonFileRejected = true;
        }
        assertTrue(nonFileRejected, "non-file bridge source was not rejected");

        assertEquals(
                HmclBackgroundLaunchAgent.validateQuickPlayWorld("Codex-Test"),
                "Codex-Test",
                "valid Quick Play world changed"
        );
        for (String invalidWorld : List.of(".", "..", "../escape", "folder\\escape", "bad\nworld")) {
            boolean invalidWorldRejected = false;
            try {
                HmclBackgroundLaunchAgent.validateQuickPlayWorld(invalidWorld);
            } catch (IllegalArgumentException expected) {
                invalidWorldRejected = true;
            }
            assertTrue(invalidWorldRejected, "unsafe Quick Play world was accepted: " + invalidWorld);
        }
        System.out.println("HMCL configured-directory registration tests passed");
    }

    private static void createBridgeJar(Path path, String revision) throws Exception {
        List<String> entries = List.of(
                "META-INF/mods.toml",
                "cn/codex/minecraftbridge/client/BridgeClient.class",
                "cn/codex/minecraftbridge/client/BridgeChannel.class",
                "cn/codex/minecraftbridge/client/LoopbackWebSocketClient.class",
                "cn/codex/minecraftbridge/forge/CodexNpcEntity.class",
                "assets/minecraft_codex_bridge/textures/entity/codex_catgirl.png"
        );
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream archive = new JarOutputStream(output)) {
            for (String name : entries) {
                archive.putNextEntry(new JarEntry(name));
                archive.write(name.getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
            archive.putNextEntry(new JarEntry("revision.txt"));
            archive.write(revision.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
    }

    private static void createBridgeJarInChildProcess(Path path, String revision) throws Exception {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        );
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                HmclBackgroundLaunchAgentTest.class.getName(),
                "--create-bridge-jar",
                path.toAbsolutePath().toString(),
                revision
        ).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError("bridge fixture subprocess failed: " + exitCode);
        }
    }

    private static String sha256(Path path) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return java.util.HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (!actual.equals(expected)) {
            throw new AssertionError(message + " (expected=" + expected + " actual=" + actual + ")");
        }
    }

    public static final class FakeManager {
        private static final List<FakeDirectory> DIRECTORIES = new ArrayList<>();
        private static int addCalls;

        private FakeManager() {
        }

        public static void reset() {
            DIRECTORIES.clear();
            addCalls = 0;
        }

        public static Iterable<FakeDirectory> getGameDirectories() {
            return DIRECTORIES;
        }

        public static FakeId newGameDirectoryId() {
            return new FakeId();
        }

        public static void addUserGameDirectory(FakeDirectory directory) {
            addCalls++;
            DIRECTORIES.add(directory);
        }
    }

    public static final class FakeDirectory {
        private final FakePortablePath path;

        public FakeDirectory(FakeId id, FakeLocalizedText name, FakePortablePath path) {
            this.path = path;
        }

        public FakePortablePath getPath() {
            return path;
        }
    }

    public static final class FakeId {
    }

    public static final class FakeLocalizedText {
        public static FakeLocalizedText plain(String value) {
            return new FakeLocalizedText();
        }
    }

    public static final class FakePortablePath {
        private final Path path;

        private FakePortablePath(Path path) {
            this.path = path;
        }

        public static FakePortablePath fromPath(Path path) {
            return new FakePortablePath(path);
        }

        public Path toPath() {
            return path;
        }
    }
}
