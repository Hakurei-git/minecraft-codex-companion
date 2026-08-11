package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class BookDragonInputResetTest {
    @Test
    void clearsPersistentRiderAndThrottleState() {
        RiderInputComponent rider = new RiderInputComponent();
        ThrottleComponent throttle = new ThrottleComponent();
        FakeDragon dragon = new FakeDragon(rider, throttle);

        BookDragonInputReset.reset(dragon);

        assertFalse(dragon.goingUp);
        assertFalse(dragon.goingDown);
        assertFalse(dragon.freeLooking);
        assertEquals(0.0F, dragon.throttleLevel);
        assertFalse(dragon.accelerating);
        assertFalse(dragon.decelerating);
        assertEquals(0.0F, rider.forward);
        assertEquals(0.0F, rider.strafe);
        assertFalse(rider.sprinting);
        assertFalse(rider.wasForwardActive);
        assertEquals(100, rider.ticksSinceForwardRelease);
        assertFalse(rider.cruiseRequested);
        assertEquals(0, throttle.poweredTicks);
        assertEquals(0, throttle.boostTicks);
        assertFalse(throttle.cruiseAccelerating);
        assertEquals(0, throttle.cruiseHoldTicks);
    }

    private static final class FakeDragon {
        private final DragonComponentRegistry componentRegistry;
        private boolean goingUp = true;
        private boolean goingDown = true;
        private boolean freeLooking = true;
        private float throttleLevel = 1.4F;
        private boolean accelerating = true;
        private boolean decelerating = true;

        private FakeDragon(Object... components) {
            componentRegistry = new DragonComponentRegistry(components);
        }

        public void setGoingUp(boolean value) {
            goingUp = value;
        }

        public void setGoingDown(boolean value) {
            goingDown = value;
        }

        public void setFreeLooking(boolean value) {
            freeLooking = value;
        }

        public void setThrottleLevel(float value) {
            throttleLevel = value;
        }

        public void setAccelerating(boolean value) {
            accelerating = value;
        }

        public void setDecelerating(boolean value) {
            decelerating = value;
        }
    }

    private static final class DragonComponentRegistry {
        private final List<Object> components;
        private final Map<Class<?>, Object> componentMap = new LinkedHashMap<>();

        private DragonComponentRegistry(Object... values) {
            components = List.of(values);
            for (Object value : values) componentMap.put(value.getClass(), value);
        }
    }

    private static final class RiderInputComponent {
        private float forward = 1.0F;
        private float strafe = -1.0F;
        private boolean sprinting = true;
        private boolean wasForwardActive = true;
        private int ticksSinceForwardRelease;
        private boolean cruiseRequested = true;

        public void setCruiseRequested(boolean value) {
            cruiseRequested = value;
        }

        public void setSprinting(boolean value) {
            sprinting = value;
        }

        public void setAscending(boolean value) {
        }

        public void setDescending(boolean value) {
        }

        public void setFreeLooking(boolean value) {
        }
    }

    private static final class ThrottleComponent {
        private int poweredTicks = 20;
        private int boostTicks = 10;
        private boolean cruiseAccelerating = true;
        private int cruiseHoldTicks = 60;
    }
}
