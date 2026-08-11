package cn.codex.minecraftbridge.forge;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Clears Book of Dragons rider state that survives a zero-velocity stop. */
public final class BookDragonInputReset {
    private BookDragonInputReset() {
    }

    public static void reset(Object dragon) {
        if (dragon == null) return;
        ReflectiveDragonAdapter.invokeVoid(dragon, "setGoingUp", false);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setGoingDown", false);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setFreeLooking", false);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setThrottleLevel", 0.0F);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setAccelerating", false);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setDecelerating", false);

        Object registry = ReflectiveDragonAdapter.readField(dragon, "componentRegistry");
        for (Object component : components(registry)) {
            String simpleName = component.getClass().getSimpleName();
            if ("RiderInputComponent".equals(simpleName)) resetRiderInput(component);
            else if ("ThrottleComponent".equals(simpleName)) resetThrottle(component);
        }
    }

    private static Set<Object> components(Object registry) {
        Set<Object> result = new LinkedHashSet<>();
        Object components = ReflectiveDragonAdapter.readField(registry, "components");
        if (components instanceof Iterable<?> iterable) {
            for (Object component : iterable) {
                if (component != null) result.add(component);
            }
        }
        Object componentMap = ReflectiveDragonAdapter.readField(registry, "componentMap");
        if (componentMap instanceof Map<?, ?> map) {
            for (Object component : map.values()) {
                if (component != null) result.add(component);
            }
        }
        return result;
    }

    private static void resetRiderInput(Object riderInput) {
        ReflectiveDragonAdapter.invokeVoid(riderInput, "setCruiseRequested", false);
        ReflectiveDragonAdapter.invokeVoid(riderInput, "setSprinting", false);
        ReflectiveDragonAdapter.invokeVoid(riderInput, "setAscending", false);
        ReflectiveDragonAdapter.invokeVoid(riderInput, "setDescending", false);
        ReflectiveDragonAdapter.invokeVoid(riderInput, "setFreeLooking", false);
        writeField(riderInput, "forward", 0.0F);
        writeField(riderInput, "strafe", 0.0F);
        writeField(riderInput, "sprinting", false);
        writeField(riderInput, "wasForwardActive", false);
        writeField(riderInput, "ticksSinceForwardRelease", 100);
        writeField(riderInput, "cruiseRequested", false);
    }

    private static void resetThrottle(Object throttle) {
        writeField(throttle, "poweredTicks", 0);
        writeField(throttle, "boostTicks", 0);
        writeField(throttle, "cruiseAccelerating", false);
        writeField(throttle, "cruiseHoldTicks", 0);
    }

    private static boolean writeField(Object target, String name, Object value) {
        if (target == null) return false;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.trySetAccessible()) return false;
                field.set(target, value);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Continue through the component hierarchy for version compatibility.
            }
        }
        return false;
    }
}
