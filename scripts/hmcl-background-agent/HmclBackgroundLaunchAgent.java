import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class HmclBackgroundLaunchAgent {
    private static volatile Object lastLauncherHelper;
    private static final ThreadLocal<String> BRIDGE_INSTALL_STAGE = new ThreadLocal<>();

    private HmclBackgroundLaunchAgent() {
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        String[] parts = arguments == null ? new String[0] : arguments.split("\\|", 6);
        String operation = parts.length < 2 ? "launch" : parts[0];
        String statusPath = parts.length < 2 ? arguments : parts[1];
        ClassLoader hmclLoader = findHmclClassLoader(instrumentation);
        if (hmclLoader == null) {
            writeStatus(statusPath, "ERROR:HMCL_CLASSLOADER_NOT_FOUND");
            return;
        }

        if (operation.equals("install-bridge") && parts.length == 6) {
            BRIDGE_INSTALL_STAGE.set("ARGUMENTS");
            try {
                String result = installBridge(
                        Path.of(decodeArgument(parts[2])),
                        Path.of(decodeArgument(parts[3])),
                        decodeArgument(parts[4]),
                        parts[5]
                );
                writeStatus(statusPath, result);
            } catch (Throwable error) {
                writeStatus(
                        statusPath,
                        "ERROR:BRIDGE_INSTALL_FAILED:STAGE=" + BRIDGE_INSTALL_STAGE.get()
                                + ":" + error.getClass().getSimpleName()
                );
            } finally {
                BRIDGE_INSTALL_STAGE.remove();
            }
            return;
        }
        if (operation.equals("unblock-java-scan") && parts.length == 2) {
            try {
                boolean changed = unblockJavaScan(hmclLoader);
                writeStatus(statusPath, changed ? "JAVA_SCAN_UNBLOCKED" : "JAVA_SCAN_READY");
            } catch (Throwable error) {
                writeStatus(statusPath, "ERROR:JAVA_SCAN_UNBLOCK_FAILED:" + error.getClass().getSimpleName());
            }
            return;
        }

        try {
            Class<?> platformClass = Class.forName("javafx.application.Platform", true, hmclLoader);
            Method runLater = platformClass.getMethod("runLater", Runnable.class);
            if (operation.equals("confirm-startup")) {
                runLater.invoke(null, (Runnable) () -> confirmStartupDialog(statusPath, hmclLoader));
            } else if (operation.equals("launch")) {
                runLater.invoke(null, (Runnable) () -> launchSelectedInstance(statusPath, hmclLoader));
            } else if (operation.equals("launch-configured")
                    && (parts.length == 5 || parts.length == 6)) {
                String gameDirectory = decodeArgument(parts[2]);
                String targetVersion = decodeArgument(parts[3]);
                String playerName = decodeArgument(parts[4]);
                String quickPlayWorld = parts.length == 6
                        ? validateQuickPlayWorld(decodeArgument(parts[5]))
                        : "";
                runLater.invoke(
                        null,
                        (Runnable) () -> launchConfiguredInstance(
                                statusPath,
                                hmclLoader,
                                gameDirectory,
                                targetVersion,
                                playerName,
                                quickPlayWorld
                        )
                );
            } else if (operation.equals("launch-state")) {
                runLater.invoke(null, (Runnable) () -> inspectLaunchState(statusPath, hmclLoader));
            } else if (operation.equals("minimize")) {
                runLater.invoke(null, (Runnable) () -> {
                    minimizeLauncher(hmclLoader);
                    writeStatus(statusPath, "MINIMIZED");
                });
            } else {
                writeStatus(statusPath, "ERROR:UNKNOWN_OPERATION");
            }
        } catch (Throwable error) {
            writeStatus(statusPath, "ERROR:PLATFORM_DISPATCH_FAILED:" + error.getClass().getSimpleName());
        }
    }

    private static String decodeArgument(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    static String validateQuickPlayWorld(String worldFolderName) {
        if (worldFolderName == null || worldFolderName.isBlank()) {
            return "";
        }
        if (worldFolderName.length() > 128
                || worldFolderName.equals(".")
                || worldFolderName.equals("..")
                || worldFolderName.indexOf('/') >= 0
                || worldFolderName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid Quick Play world folder");
        }
        for (int index = 0; index < worldFolderName.length(); index++) {
            char character = worldFolderName.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                throw new IllegalArgumentException("Invalid Quick Play world folder");
            }
        }
        return worldFolderName;
    }

    static boolean unblockJavaScan(ClassLoader loader) throws Exception {
        Class<?> javaManager = Class.forName("org.jackhuang.hmcl.java.JavaManager", false, loader);
        Field allJavaField = javaManager.getDeclaredField("allJava");
        allJavaField.setAccessible(true);
        Field latchField = javaManager.getDeclaredField("LATCH");
        latchField.setAccessible(true);
        synchronized (javaManager) {
            boolean changed = allJavaField.get(null) == null;
            if (changed) allJavaField.set(null, Map.of());
            ((CountDownLatch) latchField.get(null)).countDown();
            return changed;
        }
    }

    static String installBridge(
            Path sourceJar,
            Path configuredGameDirectory,
            String configuredVersion,
            String expectedHash
    ) throws Exception {
        if (configuredVersion == null
                || configuredVersion.isBlank()
                || configuredVersion.equals(".")
                || configuredVersion.equals("..")
                || Path.of(configuredVersion).isAbsolute()
                || Path.of(configuredVersion).getNameCount() != 1
                || configuredVersion.indexOf('/') >= 0
                || configuredVersion.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid target version");
        }
        if (expectedHash == null || !expectedHash.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid bridge hash");
        }

        bridgeInstallStage("SOURCE_PATH");
        Path source = sourceJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bridge source is not a direct regular file");
        }
        String sourceName = source.getFileName().toString();
        if (!sourceName.matches("minecraft_codex_bridge-forge-1\\.20\\.1-[A-Za-z0-9._-]+\\.jar")) {
            throw new IllegalArgumentException("Unexpected bridge filename");
        }
        String normalizedExpectedHash = expectedHash.toUpperCase();
        bridgeInstallStage("SOURCE_HASH");
        if (!sha256(source).equals(normalizedExpectedHash)) {
            throw new IllegalArgumentException("Bridge source hash changed");
        }
        bridgeInstallStage("SOURCE_JAR");
        validateBridgeJar(source);

        bridgeInstallStage("GAME_PATH");
        Path gameDirectory = normalizedExistingDirectory(configuredGameDirectory);
        bridgeInstallStage("VERSIONS_PATH");
        Path versions = directExistingDirectory(gameDirectory, "versions");
        Path unresolvedInstance = versions.resolve(configuredVersion).normalize();
        if (!versions.equals(unresolvedInstance.getParent())) {
            throw new IllegalArgumentException("Target version escapes versions directory");
        }
        bridgeInstallStage("INSTANCE_PATH");
        Path instance = directExistingDirectory(versions, configuredVersion);
        bridgeInstallStage("CLONE_MARKER");
        Path marker = instance.resolve("CODEX-CLONE.json");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Target instance is not a Codex clone");
        }

        bridgeInstallStage("TARGET_MODS");
        Path mods = directDirectory(instance, "mods");
        bridgeInstallStage("TARGET_BACKUPS");
        Path backupRoot = directDirectory(instance, "bridge-backups");
        Path backup = backupRoot.resolve(
                "agent-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8)
        );
        Files.createDirectory(backup);
        Path temporary = mods.resolve(".codex-bridge-" + UUID.randomUUID() + ".tmp");
        Path destination = mods.resolve(sourceName);
        List<Path> previousJars;
        bridgeInstallStage("LIST_INSTALLED");
        try (Stream<Path> files = Files.list(mods)) {
            previousJars = files
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().matches("minecraft_codex_bridge-.*\\.jar"))
                    .sorted()
                    .toList();
        }

        bridgeInstallStage("COPY_TEMP");
        Files.copy(source, temporary, StandardCopyOption.COPY_ATTRIBUTES);
        bridgeInstallStage("VERIFY_TEMP");
        if (!sha256(temporary).equals(normalizedExpectedHash)) {
            preserveFailedFile(temporary, backup, "failed-copy.tmp");
            throw new IOException("Copied bridge hash mismatch");
        }

        List<Path[]> movedPreviousJars = new ArrayList<>();
        try {
            bridgeInstallStage("BACKUP_PREVIOUS");
            for (Path previous : previousJars) {
                Path preserved = backup.resolve(previous.getFileName());
                Files.move(previous, preserved);
                movedPreviousJars.add(new Path[]{previous, preserved});
            }
            bridgeInstallStage("INSTALL_MOVE");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            bridgeInstallStage("VERIFY_INSTALLED");
            if (!sha256(destination).equals(normalizedExpectedHash)) {
                throw new IOException("Installed bridge hash mismatch");
            }
            return "BRIDGE_INSTALLED:BACKUPS=" + previousJars.size() + ":SHA256=" + normalizedExpectedHash;
        } catch (Throwable error) {
            preserveFailedFile(destination, backup, "failed-installed.jar");
            preserveFailedFile(temporary, backup, "failed-copy.tmp");
            for (int index = movedPreviousJars.size() - 1; index >= 0; index--) {
                Path[] moved = movedPreviousJars.get(index);
                if (!Files.exists(moved[0], LinkOption.NOFOLLOW_LINKS)
                        && Files.exists(moved[1], LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(moved[1], moved[0]);
                }
            }
            throw error;
        }
    }

    private static void bridgeInstallStage(String stage) {
        BRIDGE_INSTALL_STAGE.set(stage);
    }

    private static Path directExistingDirectory(Path parent, String name) throws IOException {
        Path normalizedParent = normalizedExistingDirectory(parent);
        Path child = normalizedParent.resolve(name).normalize();
        if (!normalizedParent.equals(child.getParent())) {
            throw new IOException("Required directory is not a direct child");
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required directory is unavailable");
        }
        return child;
    }

    private static Path normalizedExistingDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required directory is unavailable or indirect");
        }
        return normalized;
    }

    private static Path directDirectory(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(child);
        }
        return directExistingDirectory(parent, name);
    }

    private static void validateBridgeJar(Path source) throws IOException {
        List<String> requiredEntries = List.of(
                "META-INF/mods.toml",
                "cn/codex/minecraftbridge/client/BridgeClient.class",
                "cn/codex/minecraftbridge/client/BridgeChannel.class",
                "cn/codex/minecraftbridge/client/LoopbackWebSocketClient.class",
                "cn/codex/minecraftbridge/forge/CodexNpcEntity.class",
                "assets/minecraft_codex_bridge/textures/entity/codex_catgirl.png"
        );
        try (JarFile archive = new JarFile(source.toFile(), true)) {
            for (String requiredEntry : requiredEntries) {
                if (archive.getJarEntry(requiredEntry) == null) {
                    throw new IOException("Bridge JAR is incomplete");
                }
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void preserveFailedFile(Path path, Path backup, String fallbackName) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Path target = backup.resolve(fallbackName);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    target = backup.resolve(UUID.randomUUID() + "-" + fallbackName);
                }
                Files.move(path, target);
            }
        } catch (IOException ignored) {
            // Never delete a failed install artifact; leave it in place if preservation cannot move it.
        }
    }

    private static void launchConfiguredInstance(
            String statusPath,
            ClassLoader loader,
            String configuredGameDirectory,
            String configuredVersion,
            String configuredPlayerName,
            String quickPlayWorld
    ) {
        try {
            Class<?> manager = Class.forName(
                    "org.jackhuang.hmcl.setting.GameDirectoryManager",
                    true,
                    loader
            );
            boolean needsInitialization;
            try {
                Field initialized = manager.getDeclaredField("initialized");
                initialized.setAccessible(true);
                needsInitialization = !initialized.getBoolean(null);
            } catch (NoSuchFieldException ignored) {
                Iterable<?> directories = (Iterable<?>) manager.getMethod("getGameDirectories").invoke(null);
                needsInitialization = !directories.iterator().hasNext();
            }
            if (needsInitialization) {
                manager.getMethod("init").invoke(null);
            }

            Class<?> gameDirectoryClass = Class.forName(
                    "org.jackhuang.hmcl.setting.GameDirectory",
                    true,
                    loader
            );
            Class<?> portablePathClass = Class.forName(
                    "org.jackhuang.hmcl.util.PortablePath",
                    true,
                    loader
            );
            Class<?> localizedTextClass = Class.forName(
                    "org.jackhuang.hmcl.util.i18n.LocalizedText",
                    true,
                    loader
            );
            Path expectedPath = Path.of(configuredGameDirectory).toAbsolutePath().normalize();
            Object selectedDirectory = ensureConfiguredGameDirectory(
                    manager,
                    gameDirectoryClass,
                    portablePathClass,
                    localizedTextClass,
                    expectedPath
            );
            manager.getMethod("setSelectedGameDirectory", gameDirectoryClass)
                    .invoke(null, selectedDirectory);
            manager.getMethod("setSelectedInstance", String.class)
                    .invoke(null, configuredVersion);
            if (manager.getMethod("getSelectedGameDirectory").invoke(null) != selectedDirectory
                    || !configuredVersion.equals(manager.getMethod("getSelectedInstance").invoke(null))) {
                writeStatus(statusPath, "ERROR:CONFIGURED_SELECTION_NOT_APPLIED");
                return;
            }

            Object repository = manager.getMethod("getSelectedRepository").invoke(null);
            boolean repositoryLoaded = (boolean) repository.getClass()
                    .getMethod("isLoaded")
                    .invoke(repository);
            if (!repositoryLoaded) {
                writeStatus(statusPath, "ERROR:CONFIGURED_REPOSITORY_NOT_LOADED");
                return;
            }
            boolean versionAvailable = (boolean) repository.getClass()
                    .getMethod("hasVersion", String.class)
                    .invoke(repository, configuredVersion);
            if (!versionAvailable) {
                writeStatus(statusPath, "ERROR:CONFIGURED_VERSION_NOT_FOUND");
                return;
            }

            Object selectedAccount = ensureSelectedOfflineAccount(loader, configuredPlayerName);
            if (launchHeadless(
                    loader,
                    repository,
                    configuredVersion,
                    selectedAccount,
                    quickPlayWorld
            )) {
                writeStatus(statusPath, "LAUNCH_REQUESTED");
                return;
            }
            if (launchWithVersionsApi(loader, repository, configuredVersion, quickPlayWorld)) {
                writeStatus(statusPath, "LAUNCH_REQUESTED");
                return;
            }
            launchSelectedInstance(statusPath, loader);
        } catch (Throwable error) {
            writeStatus(
                    statusPath,
                    "ERROR:CONFIGURED_LAUNCH_FAILED:" + describeThrowable(rootCause(error))
            );
        }
    }

    static Object ensureConfiguredGameDirectory(
            Class<?> manager,
            Class<?> gameDirectoryClass,
            Class<?> portablePathClass,
            Class<?> localizedTextClass,
            Path expectedPath
    ) throws Exception {
        Object selectedDirectory = findConfiguredGameDirectory(
                (Iterable<?>) manager.getMethod("getGameDirectories").invoke(null),
                expectedPath
        );
        if (selectedDirectory != null) {
            return selectedDirectory;
        }

        Object directoryId = manager.getMethod("newGameDirectoryId").invoke(null);
        Object displayName = localizedTextClass.getMethod("plain", String.class)
                .invoke(null, "Minecraft Codex Companion");
        Object portablePath = portablePathClass.getMethod("fromPath", Path.class)
                .invoke(null, expectedPath);
        Constructor<?> directoryConstructor = null;
        for (Constructor<?> candidate : gameDirectoryClass.getConstructors()) {
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length == 3
                    && parameterTypes[0].isInstance(directoryId)
                    && parameterTypes[1].isInstance(displayName)
                    && parameterTypes[2].isInstance(portablePath)) {
                directoryConstructor = candidate;
                break;
            }
        }
        if (directoryConstructor == null) {
            throw new NoSuchMethodException("Compatible GameDirectory constructor not found");
        }

        Object registeredDirectory = directoryConstructor.newInstance(
                directoryId,
                displayName,
                portablePath
        );
        manager.getMethod("addUserGameDirectory", gameDirectoryClass)
                .invoke(null, registeredDirectory);

        selectedDirectory = findConfiguredGameDirectory(
                (Iterable<?>) manager.getMethod("getGameDirectories").invoke(null),
                expectedPath
        );
        if (selectedDirectory == null) {
            throw new IllegalStateException("Configured game directory registration was not applied");
        }
        return selectedDirectory;
    }

    private static Object findConfiguredGameDirectory(
            Iterable<?> directories,
            Path expectedPath
    ) throws Exception {
        String expected = expectedPath.toAbsolutePath().normalize().toString();
        for (Object candidate : directories) {
            Object portablePath = candidate.getClass().getMethod("getPath").invoke(candidate);
            Path candidatePath = (Path) portablePath.getClass().getMethod("toPath")
                    .invoke(portablePath);
            if (candidatePath.toAbsolutePath().normalize().toString().equalsIgnoreCase(expected)) {
                return candidate;
            }
        }
        return null;
    }

    private static void inspectLaunchState(String statusPath, ClassLoader loader) {
        Object helper = lastLauncherHelper;
        if (helper == null) {
            writeStatus(statusPath, "LAUNCH_TASK:PENDING_HELPER");
            return;
        }
        try {
            Field paneField = helper.getClass().getDeclaredField("launchingStepsPane");
            paneField.setAccessible(true);
            Object pane = paneField.get(helper);
            Field executorField = pane.getClass().getDeclaredField("executor");
            executorField.setAccessible(true);
            Object executor = executorField.get(pane);
            if (executor == null) {
                writeStatus(statusPath, "LAUNCH_TASK:PENDING_EXECUTOR");
                return;
            }
            Class<?> executorClass = Class.forName("org.jackhuang.hmcl.task.TaskExecutor", true, loader);
            Exception exception = (Exception) executorClass.getMethod("getException").invoke(executor);
            if (exception != null) {
                writeStatus(
                        statusPath,
                        "ERROR:LAUNCH_TASK_FAILED:" + describeThrowable(rootCause(exception))
                );
                return;
            }
            boolean cancelled = (boolean) executorClass.getMethod("isCancelled").invoke(executor);
            writeStatus(statusPath, "LAUNCH_TASK:ACTIVE:cancelled=" + cancelled);
        } catch (Throwable error) {
            writeStatus(statusPath, "ERROR:LAUNCH_STATE_FAILED:" + describeThrowable(rootCause(error)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object ensureSelectedOfflineAccount(ClassLoader loader, String playerName) throws Exception {
        Class<?> accountsClass = Class.forName("org.jackhuang.hmcl.setting.Accounts", true, loader);
        Object current = accountsClass.getMethod("getSelectedAccount").invoke(null);
        if (current != null) {
            return current;
        }

        Class<?> accountClass = Class.forName("org.jackhuang.hmcl.auth.Account", true, loader);
        List<Object> accounts = (List<Object>) accountsClass.getMethod("getAccounts").invoke(null);
        Object selected = null;
        for (Object account : accounts) {
            if (account.getClass().getName().equals("org.jackhuang.hmcl.auth.offline.OfflineAccount")
                    && playerName.equals(accountClass.getMethod("getProfileName").invoke(account))) {
                selected = account;
                break;
            }
        }

        if (selected == null) {
            Object factory = accountsClass.getMethod("getAccountFactory", String.class)
                    .invoke(null, "offline");
            UUID uuid = (UUID) factory.getClass()
                    .getMethod("getUUIDFromUserName", String.class)
                    .invoke(null, playerName);
            selected = factory.getClass()
                    .getMethod("create", String.class, UUID.class)
                    .invoke(factory, playerName, uuid);
            accounts.add(selected);
        }
        accountsClass.getMethod("setSelectedAccount", accountClass).invoke(null, selected);
        return selected;
    }

    private static boolean launchHeadless(
            ClassLoader loader,
            Object repository,
            String selectedInstance,
            Object account,
            String quickPlayWorld
    ) throws Exception {
        Class<?> helperClass;
        try {
            helperClass = Class.forName("org.jackhuang.hmcl.game.LauncherHelper", true, loader);
        } catch (ClassNotFoundException ignored) {
            return false;
        }

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : helperClass.getConstructors()) {
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length == 3
                    && parameterTypes[0].isInstance(repository)
                    && parameterTypes[1].isInstance(account)
                    && parameterTypes[2] == String.class) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) {
            return false;
        }

        Method launchMethod;
        try {
            // launch() first opens HMCL's dialog layer. The background launcher
            // deliberately enters the same task graph below that presentation
            // step so it still works when RootPage failed to initialize.
            launchMethod = helperClass.getDeclaredMethod("launch0");
        } catch (NoSuchMethodException ignored) {
            return false;
        }
        launchMethod.setAccessible(true);
        Object helper = constructor.newInstance(repository, account, selectedInstance);
        lastLauncherHelper = helper;
        configureQuickPlay(loader, helper, quickPlayWorld);
        launchMethod.invoke(helper);
        return true;
    }

    private static void configureQuickPlay(
            ClassLoader loader,
            Object launcherHelper,
            String worldFolderName
    ) throws Exception {
        String validatedWorld = validateQuickPlayWorld(worldFolderName);
        if (validatedWorld.isEmpty()) {
            return;
        }
        Class<?> optionClass = Class.forName(
                "org.jackhuang.hmcl.game.QuickPlayOption",
                true,
                loader
        );
        Class<?> singlePlayerClass = Class.forName(
                "org.jackhuang.hmcl.game.QuickPlayOption$SinglePlayer",
                true,
                loader
        );
        Object singlePlayer = singlePlayerClass.getConstructor(String.class)
                .newInstance(validatedWorld);
        launcherHelper.getClass()
                .getMethod("setQuickPlayOption", optionClass)
                .invoke(launcherHelper, singlePlayer);
    }

    private static ClassLoader findHmclClassLoader(Instrumentation instrumentation) {
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass.getName().equals("org.jackhuang.hmcl.Launcher")) {
                return loadedClass.getClassLoader();
            }
        }
        return null;
    }

    private static void confirmStartupDialog(String statusPath, ClassLoader loader) {
        try {
            Class<?> windowClass = Class.forName("javafx.stage.Window", true, loader);
            Object windows = windowClass.getMethod("getWindows").invoke(null);
            Class<?> buttonTypeClass = Class.forName("javafx.scene.control.ButtonType", true, loader);
            Object yesButtonType = buttonTypeClass.getField("YES").get(null);
            StringBuilder diagnostics = new StringBuilder();

            for (Object window : (Iterable<?>) windows) {
                if (diagnostics.length() > 0) {
                    diagnostics.append(',');
                }
                diagnostics.append(window.getClass().getName());
                Object scene = window.getClass().getMethod("getScene").invoke(window);
                if (scene == null) {
                    diagnostics.append("[no-scene]");
                    continue;
                }
                Object root = scene.getClass().getMethod("getRoot").invoke(scene);
                diagnostics.append('[')
                        .append(root == null ? "no-root" : root.getClass().getName())
                        .append(']');
                Object dialogPane = findDialogPane(root, loader);
                if (dialogPane == null) {
                    continue;
                }
                Object buttonTypes = dialogPane.getClass().getMethod("getButtonTypes").invoke(dialogPane);
                diagnostics.append("{buttons=");
                boolean firstButton = true;
                int buttonCount = 0;
                Object onlyOkButtonType = null;
                for (Object buttonType : (Iterable<?>) buttonTypes) {
                    if (!firstButton) {
                        diagnostics.append('+');
                    }
                    firstButton = false;
                    buttonCount++;
                    Object buttonData = buttonType.getClass().getMethod("getButtonData").invoke(buttonType);
                    diagnostics.append(buttonData);
                    if (String.valueOf(buttonData).equals("OK_DONE")) {
                        onlyOkButtonType = buttonType;
                    }
                }
                diagnostics.append('}');
                Object yesButton = dialogPane.getClass()
                        .getMethod("lookupButton", buttonTypeClass)
                        .invoke(dialogPane, yesButtonType);
                if (yesButton == null && buttonCount == 1 && onlyOkButtonType != null) {
                    yesButton = dialogPane.getClass()
                            .getMethod("lookupButton", buttonTypeClass)
                            .invoke(dialogPane, onlyOkButtonType);
                }
                if (yesButton == null) {
                    continue;
                }
                yesButton.getClass().getMethod("fire").invoke(yesButton);
                writeStatus(statusPath, "STARTUP_DIALOG_CONFIRMED");
                return;
            }
            writeStatus(statusPath, "ERROR:STARTUP_DIALOG_NOT_FOUND:" + diagnostics);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            writeStatus(statusPath, "ERROR:STARTUP_DIALOG_FAILED:" + cause.getClass().getSimpleName());
        }
    }

    private static Object findDialogPane(Object node, ClassLoader loader) throws Exception {
        if (node == null) {
            return null;
        }
        if (node.getClass().getName().equals("javafx.scene.control.DialogPane")) {
            return node;
        }

        Class<?> parentClass = Class.forName("javafx.scene.Parent", true, loader);
        if (!parentClass.isInstance(node)) {
            return null;
        }
        Object children = parentClass.getMethod("getChildrenUnmodifiable").invoke(node);
        for (Object child : (Iterable<?>) children) {
            Object result = findDialogPane(child, loader);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static void launchSelectedInstance(String statusPath, ClassLoader loader) {
        try {
            // Invoke the launch action on the already loaded JavaFX main page
            // first. This follows the exact code path of HMCL's launch button
            // without focusing or synthesizing input, and avoids eagerly
            // loading version-specific internal facade classes.
            try {
                if (launchFromMainPage(loader)) {
                    minimizeLauncher(loader);
                    writeStatus(statusPath, "LAUNCH_REQUESTED");
                    return;
                }
            } catch (Throwable error) {
                writeStatus(
                        statusPath,
                        "ERROR:MAIN_PAGE_LAUNCH_FAILED:" + describeThrowable(rootCause(error))
                );
                return;
            }

            Class<?> directoryManager = Class.forName(
                    "org.jackhuang.hmcl.setting.GameDirectoryManager",
                    true,
                    loader
            );
            Object repository = directoryManager.getMethod("getSelectedRepository").invoke(null);
            Object selectedInstance = repository.getClass().getMethod("getSelectedInstance").invoke(repository);
            if (selectedInstance == null) {
                writeStatus(statusPath, "ERROR:NO_SELECTED_INSTANCE");
                return;
            }

            // HMCL 3.17 moved the public launch entry point from
            // ui.instances.Instances to ui.versions.Versions. Prefer the
            // current API while retaining the old path for existing installs.
            if (launchWithVersionsApi(loader, repository, selectedInstance, "")) {
                minimizeLauncher(loader);
                writeStatus(statusPath, "LAUNCH_REQUESTED");
                return;
            }

            Class<?> instancesClass = Class.forName(
                    "org.jackhuang.hmcl.ui.instances.Instances",
                    true,
                    loader
            );
            Method launchMethod = null;
            for (Method method : instancesClass.getMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (method.getName().equals("launch")
                        && parameterTypes.length == 2
                        && parameterTypes[0].isInstance(repository)
                        && parameterTypes[1].isInstance(selectedInstance)) {
                    launchMethod = method;
                    break;
                }
            }
            if (launchMethod == null) {
                writeStatus(statusPath, "ERROR:LAUNCH_METHOD_NOT_FOUND");
                return;
            }

            launchMethod.invoke(null, repository, selectedInstance);
            minimizeLauncher(loader);
            writeStatus(statusPath, "LAUNCH_REQUESTED");
        } catch (Throwable error) {
            Throwable cause = rootCause(error);
            writeStatus(statusPath, "ERROR:LAUNCH_FAILED:" + describeThrowable(cause));
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 12 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean launchFromMainPage(ClassLoader loader) throws Exception {
        try {
            Class<?> controllersClass = Class.forName(
                    "org.jackhuang.hmcl.ui.Controllers",
                    true,
                    loader
            );
            Object rootPage = controllersClass.getMethod("getRootPage").invoke(null);
            if (rootPage != null) {
                Object mainPage = rootPage.getClass().getMethod("getMainPage").invoke(rootPage);
                if (mainPage != null) {
                    invokeMainPageLaunch(mainPage);
                    return true;
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Older HMCL builds fall back to the visible scene graph below.
        }

        Class<?> windowClass = Class.forName("javafx.stage.Window", true, loader);
        Object windows = windowClass.getMethod("getWindows").invoke(null);
        for (Object window : (Iterable<?>) windows) {
            Object scene = window.getClass().getMethod("getScene").invoke(window);
            if (scene == null) {
                continue;
            }
            Object root = scene.getClass().getMethod("getRoot").invoke(scene);
            Object mainPage = findNodeByClassName(
                    root,
                    "org.jackhuang.hmcl.ui.main.MainPage",
                    loader
            );
            if (mainPage == null) {
                continue;
            }
            invokeMainPageLaunch(mainPage);
            return true;
        }
        return false;
    }

    private static void invokeMainPageLaunch(Object mainPage) throws Exception {
        Method launchMethod = mainPage.getClass().getDeclaredMethod("launch");
        launchMethod.setAccessible(true);
        launchMethod.invoke(mainPage);
    }

    private static Object findNodeByClassName(
            Object node,
            String className,
            ClassLoader loader
    ) throws Exception {
        if (node == null) {
            return null;
        }
        if (node.getClass().getName().equals(className)) {
            return node;
        }
        Class<?> parentClass = Class.forName("javafx.scene.Parent", true, loader);
        if (!parentClass.isInstance(node)) {
            return null;
        }
        Object children = parentClass.getMethod("getChildrenUnmodifiable").invoke(node);
        for (Object child : (Iterable<?>) children) {
            Object found = findNodeByClassName(child, className, loader);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean launchWithVersionsApi(
            ClassLoader loader,
            Object repository,
            Object selectedInstance,
            String quickPlayWorld
    ) throws Exception {
        Class<?> versionsClass;
        try {
            // Inspect the already loaded facade without forcing its static
            // initializer; invocation below will initialize it through HMCL's
            // own method call and expose the real root cause if that fails.
            versionsClass = Class.forName("org.jackhuang.hmcl.ui.versions.Versions", false, loader);
        } catch (ClassNotFoundException ignored) {
            return false;
        }

        StringBuilder launchSignatures = new StringBuilder();
        for (Method method : versionsClass.getMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (method.getName().equals("launch")) {
                if (launchSignatures.length() > 0) {
                    launchSignatures.append(';');
                }
                launchSignatures.append('(');
                for (int index = 0; index < parameterTypes.length; index++) {
                    if (index > 0) {
                        launchSignatures.append(',');
                    }
                    launchSignatures.append(parameterTypes[index].getName());
                }
                launchSignatures.append(')');
            }
            if (!method.getName().equals("launch")
                    || parameterTypes.length != 3
                    || !parameterTypes[0].isInstance(repository)
                    || !parameterTypes[1].isInstance(selectedInstance)
                    || !parameterTypes[2].isArray()) {
                continue;
            }
            lastLauncherHelper = null;
            Object callbacks = Array.newInstance(parameterTypes[2].getComponentType(), 1);
            Array.set(callbacks, 0, (Consumer<Object>) helper -> {
                lastLauncherHelper = helper;
                try {
                    configureQuickPlay(loader, helper, quickPlayWorld);
                } catch (Exception error) {
                    throw new IllegalStateException("Quick Play setup failed", error);
                }
            });
            method.invoke(null, repository, selectedInstance, callbacks);
            return true;
        }
        return false;
    }

    private static String describeThrowable(Throwable error) {
        StringBuilder description = new StringBuilder(error.getClass().getSimpleName());
        if ((error instanceof ClassNotFoundException
                || error instanceof NoClassDefFoundError
                || error instanceof IllegalStateException)
                && error.getMessage() != null) {
            description.append('[')
                    .append(error.getMessage().replaceAll("[^A-Za-z0-9_.$/]", "_"))
                    .append(']');
        }
        StackTraceElement[] stackTrace = error.getStackTrace();
        int limit = Math.min(8, stackTrace.length);
        for (int index = 0; index < limit; index++) {
            StackTraceElement frame = stackTrace[index];
            description.append(':')
                    .append(frame.getClassName())
                    .append('#')
                    .append(frame.getMethodName())
                    .append('@')
                    .append(frame.getLineNumber());
        }
        return description.toString();
    }

    private static void minimizeLauncher(ClassLoader loader) {
        try {
            Class<?> controllersClass = Class.forName("org.jackhuang.hmcl.ui.Controllers", true, loader);
            Object stage = controllersClass.getMethod("getStage").invoke(null);
            if (stage != null) {
                stage.getClass().getMethod("setIconified", boolean.class).invoke(stage, true);
            }
        } catch (Throwable ignored) {
            // Launching the selected instance is independent of launcher minimization.
        }
    }

    private static void writeStatus(String statusPath, String status) {
        if (statusPath == null || statusPath.isBlank()) {
            return;
        }
        try {
            Files.writeString(
                    Path.of(statusPath),
                    status,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Throwable ignored) {
            // The attach caller also validates the spawned Minecraft/bridge state.
        }
    }
}
