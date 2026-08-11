import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MinecraftBridgeDiagnosticAgent {
    private static final String STATUS_PREFIX = "minecraft-bridge-state-";

    private MinecraftBridgeDiagnosticAgent() {
    }

    public static void agentmain(String argument, Instrumentation instrumentation) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("format", "1");
        state.put("eventsClassLoaded", "false");
        state.put("clientPresent", "false");
        state.put("configReady", "false");
        state.put("autoReconnect", "false");
        state.put("sessionActive", "false");
        state.put("connecting", "false");
        state.put("socketPresent", "false");
        state.put("announcedSocketPresent", "false");
        state.put("ticksPositive", "false");
        state.put("errorType", "none");

        Path statusPath = null;
        try {
            statusPath = validateStatusPath(argument);
            Class<?> eventsClass = findLoadedClass(
                instrumentation,
                "cn.codex.minecraftbridge.client.ClientBridgeEvents"
            );
            if (eventsClass != null) {
                state.put("eventsClassLoaded", "true");
                Field clientField = eventsClass.getDeclaredField("CLIENT");
                clientField.setAccessible(true);
                Object client = clientField.get(null);
                if (client != null) {
                    state.put("clientPresent", "true");
                    Class<?> clientClass = client.getClass();
                    Object config = field(clientClass, client, "config");
                    if (config != null) {
                        Method isReady = config.getClass().getMethod("isReady");
                        state.put("configReady", booleanText(isReady.invoke(config)));
                        state.put("autoReconnect", booleanText(field(config.getClass(), config, "autoReconnect")));
                    }
                    state.put("sessionActive", booleanText(field(clientClass, client, "sessionActive")));
                    state.put("connecting", booleanText(field(clientClass, client, "connecting")));
                    state.put("socketPresent", booleanText(field(clientClass, client, "socket") != null));
                    state.put(
                        "announcedSocketPresent",
                        booleanText(field(clientClass, client, "announcedSocket") != null)
                    );
                    Object ticks = field(clientClass, client, "ticks");
                    state.put("ticksPositive", booleanText(ticks instanceof Number && ((Number) ticks).longValue() > 0));
                }
            }
        } catch (Throwable error) {
            state.put("errorType", safeType(error));
        }

        if (statusPath != null) {
            try {
                StringBuilder output = new StringBuilder();
                state.forEach((key, value) -> output.append(key).append('=').append(value).append('\n'));
                Files.writeString(
                    statusPath,
                    output.toString(),
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                );
            } catch (Throwable ignored) {
                // The caller times out without receiving a status file.
            }
        }
    }

    private static Path validateStatusPath(String argument) throws Exception {
        Path path = Path.of(argument).toAbsolutePath().normalize();
        String fileName = path.getFileName().toString();
        if (!fileName.startsWith(STATUS_PREFIX) || !fileName.endsWith(".status")
            || !fileName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid-status-name");
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("invalid-status-parent");
        }
        return path;
    }

    private static Class<?> findLoadedClass(Instrumentation instrumentation, String name) {
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (candidate.getName().equals(name)) return candidate;
        }
        return null;
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String booleanText(Object value) {
        return Boolean.TRUE.equals(value) ? "true" : "false";
    }

    private static String safeType(Throwable error) {
        String name = error.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9_$]+") ? name : "UnknownError";
    }
}
