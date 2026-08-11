package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReflectiveDragonAdapterTest {
    @Test
    void optionalModSubsystemsMayBeAbsent() {
        assertNull(ReflectiveDragonAdapter.invoke(null, "getNeedsSystem"));
        assertFalse(ReflectiveDragonAdapter.invokeVoid(null, "setFoodLevel", 20));
    }

    @Test
    void invokesOptionalEnumSettersWithoutLoadingTheModAtCompileTime() {
        EnumTarget target = new EnumTarget();
        assertTrue(ReflectiveDragonAdapter.invokeEnumVoid(target, "setMode", "AIRBORNE"));
        assertEquals(Mode.AIRBORNE, target.mode);
        assertFalse(ReflectiveDragonAdapter.invokeEnumVoid(target, "setMode", "MISSING"));
        assertFalse(ReflectiveDragonAdapter.invokeEnumVoid(target, "missing", "AIRBORNE"));
    }

    private enum Mode { GROUNDED, AIRBORNE }

    private static final class EnumTarget {
        private Mode mode = Mode.GROUNDED;

        public void setMode(Mode mode) {
            this.mode = mode;
        }
    }
}
