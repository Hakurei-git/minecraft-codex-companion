package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildStatePolicyTest {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "axis", Set.of("x", "y", "z"),
        "lit", Set.of("true", "false")
    );
    private static final BuildStatePolicy.StateAccess<Map<String, String>> ACCESS = new BuildStatePolicy.StateAccess<>() {
        @Override
        public String value(Map<String, String> state, String propertyName) {
            return state.get(propertyName);
        }

        @Override
        public Map<String, String> withValue(Map<String, String> state, String propertyName, String value) {
            Set<String> values = ALLOWED.get(propertyName);
            if (values == null) throw new IllegalArgumentException("unknown property " + propertyName);
            if (!values.contains(value)) throw new IllegalArgumentException("invalid value " + value);
            Map<String, String> updated = new HashMap<>(state);
            updated.put(propertyName, value);
            return updated;
        }
    };

    @Test
    void appliesAndMatchesTypedBlockProperties() {
        Map<String, String> original = Map.of("axis", "y", "lit", "false");
        Map<String, String> properties = Map.of("axis", "x", "lit", "true");

        Map<String, String> applied = BuildStatePolicy.apply(original, properties, ACCESS);

        assertEquals("x", applied.get("axis"));
        assertEquals("true", applied.get("lit"));
        assertTrue(BuildStatePolicy.matches(applied, properties, ACCESS));
        assertFalse(BuildStatePolicy.matches(original, properties, ACCESS));
    }

    @Test
    void rejectsUnknownPropertiesAndValues() {
        Map<String, String> state = Map.of("axis", "y", "lit", "false");

        assertThrows(IllegalArgumentException.class, () -> BuildStatePolicy.apply(state, Map.of("facing", "north"), ACCESS));
        assertThrows(IllegalArgumentException.class, () -> BuildStatePolicy.apply(state, Map.of("axis", "diagonal"), ACCESS));
    }
}
