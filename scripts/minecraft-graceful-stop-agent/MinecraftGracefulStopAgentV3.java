import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executor;

public final class MinecraftGracefulStopAgentV3 {
    private MinecraftGracefulStopAgentV3() {
    }

    public static void agentmain(String rawStatusPath, Instrumentation instrumentation) {
        Path statusPath = null;
        try {
            statusPath = validateStatusPath(rawStatusPath);
            Path finalStatusPath = statusPath;
            Class<?> minecraftClass = findLoaded(instrumentation, "net.minecraft.client.Minecraft");
            if (minecraftClass == null) {
                write(statusPath, "ERROR:MINECRAFT_CLASS_NOT_LOADED");
                return;
            }
            Object minecraft = findInstance(minecraftClass);
            Method stop = findStopMethod(minecraftClass);
            stop.setAccessible(true);
            if (!(minecraft instanceof Executor executor)) {
                write(statusPath, "ERROR:MINECRAFT_EXECUTOR_UNAVAILABLE");
                return;
            }
            executor.execute(() -> {
                try {
                    write(finalStatusPath, "STOP_REQUESTED");
                    stop.invoke(minecraft);
                } catch (Throwable error) {
                    write(finalStatusPath, "ERROR:STOP_FAILED:" + safeType(error));
                }
            });
            write(statusPath, "STOP_SCHEDULED");
        } catch (Throwable error) {
            if (statusPath != null) {
                write(statusPath, "ERROR:AGENT_FAILED:" + safeType(error));
            }
        }
    }

    private static Path validateStatusPath(String rawStatusPath) throws Exception {
        Path path = Path.of(rawStatusPath).toAbsolutePath().normalize();
        String name = path.getFileName().toString();
        if (!name.matches("minecraft-graceful-stop-[0-9]+\\.status")) {
            throw new IllegalArgumentException("invalid-status-name");
        }
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent) || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("invalid-status-parent");
        }
        return path;
    }

    private static Class<?> findLoaded(Instrumentation instrumentation, String name) {
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (candidate.getName().equals(name)) return candidate;
        }
        return null;
    }

    private static Object findInstance(Class<?> minecraftClass) throws Exception {
        for (Method method : minecraftClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == minecraftClass) {
                method.setAccessible(true);
                Object value = method.invoke(null);
                if (value != null) return value;
            }
        }
        throw new IllegalStateException("minecraft-singleton-unavailable");
    }

    private static Method findStopMethod(Class<?> minecraftClass) throws Exception {
        for (String name : new String[] { "stop", "m_91395_" }) {
            for (Method method : minecraftClass.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class) {
                    return method;
                }
            }
        }
        throw new NoSuchMethodException("minecraft-stop-unavailable");
    }

    private static void write(Path path, String value) {
        try {
            Files.writeString(
                    path,
                    value,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Throwable ignored) {
        }
    }

    private static String safeType(Throwable error) {
        String name = error.getClass().getSimpleName();
        return name.matches("[A-Za-z0-9_$]+") ? name : "UnknownError";
    }
}
