package cn.codex.minecraftbridge.client;

import java.lang.reflect.Method;

public final class BaritoneAdapter {
    private Object baritone;
    private Object commandManager;
    private Method execute;
    private Method isPathing;
    private Method cancelEverything;
    private boolean initialized;

    public boolean available() {
        initialize();
        return baritone != null && commandManager != null && execute != null;
    }

    public boolean execute(String command) {
        if (!available()) return false;
        try {
            execute.invoke(commandManager, command);
            return true;
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    public boolean isPathing() {
        if (!available() || isPathing == null) return false;
        try {
            Object behavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            return Boolean.TRUE.equals(isPathing.invoke(behavior));
        } catch (ReflectiveOperationException error) {
            return false;
        }
    }

    public void cancel() {
        if (!available()) return;
        try {
            Object behavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            if (cancelEverything != null) cancelEverything.invoke(behavior);
            else execute("stop");
        } catch (ReflectiveOperationException ignored) {
            execute("stop");
        }
    }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            Object provider = api.getMethod("getProvider").invoke(null);
            baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            commandManager = baritone.getClass().getMethod("getCommandManager").invoke(baritone);
            execute = findMethod(commandManager.getClass(), "execute", String.class);
            Object behavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            isPathing = findMethod(behavior.getClass(), "isPathing");
            cancelEverything = findMethod(behavior.getClass(), "cancelEverything");
        } catch (ReflectiveOperationException | LinkageError error) {
            baritone = null;
            commandManager = null;
            execute = null;
        }
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }
}
