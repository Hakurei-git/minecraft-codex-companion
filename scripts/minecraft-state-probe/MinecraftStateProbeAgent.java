import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class MinecraftStateProbeAgent {
    private MinecraftStateProbeAgent() {
    }

    public static void agentmain(String statusPath, Instrumentation instrumentation) {
        try {
            Class<?> minecraftClass = findLoaded(instrumentation, "net.minecraft.client.Minecraft");
            Class<?> screenClass = findLoaded(instrumentation, "net.minecraft.client.gui.screens.Screen");
            Class<?> levelClass = findLoaded(instrumentation, "net.minecraft.client.multiplayer.ClientLevel");
            Class<?> playerClass = findLoaded(instrumentation, "net.minecraft.client.player.LocalPlayer");
            if (minecraftClass == null) {
                write(statusPath, "ERROR:MINECRAFT_CLASS_NOT_LOADED");
                return;
            }
            Object minecraft = findInstance(minecraftClass);
            Object screen = findFieldValue(minecraftClass, minecraft, screenClass);
            Object level = findFieldValue(minecraftClass, minecraft, levelClass);
            Object player = findFieldValue(minecraftClass, minecraft, playerClass);
            write(
                    statusPath,
                    "STATE:LEVEL=" + (level != null)
                            + ":PLAYER=" + (player != null)
                            + ":SCREEN=" + (screen == null ? "none" : screen.getClass().getName())
            );
        } catch (Throwable error) {
            write(statusPath, "ERROR:STATE_PROBE_FAILED:" + error.getClass().getSimpleName());
        }
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
        throw new IllegalStateException("Minecraft singleton accessor was not found");
    }

    private static Object findFieldValue(Class<?> owner, Object instance, Class<?> expectedType) throws Exception {
        if (expectedType == null) return null;
        for (Field field : owner.getDeclaredFields()) {
            if (!expectedType.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            return field.get(instance);
        }
        return null;
    }

    private static void write(String statusPath, String value) {
        try {
            Files.writeString(
                    Path.of(statusPath),
                    value,
                    StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Throwable ignored) {
        }
    }
}
