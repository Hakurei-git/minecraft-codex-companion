package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceSelectorPolicyTest {
    @Test
    void parsesExactIdsAndTags() {
        assertEquals(
            new ResourceSelectorPolicy.Parsed(false, "minecraft:oak_log"),
            ResourceSelectorPolicy.parse(" minecraft:oak_log ")
        );
        assertEquals(
            new ResourceSelectorPolicy.Parsed(true, "minecraft:logs"),
            ResourceSelectorPolicy.parse(" #minecraft:logs ")
        );
        assertEquals(
            new ResourceSelectorPolicy.Parsed(false, "minecraft:oak_log"),
            ResourceSelectorPolicy.parse("oak_log")
        );
    }

    @Test
    void rejectsEmptySelectors() {
        assertThrows(IllegalArgumentException.class, () -> ResourceSelectorPolicy.parse(""));
        assertThrows(IllegalArgumentException.class, () -> ResourceSelectorPolicy.parse("#"));
    }

    @Test
    void exactSelectorsMatchOnlyTheRequestedId() {
        ResourceSelectorPolicy.Parsed selector = ResourceSelectorPolicy.parse("minecraft:oak_log");
        assertTrue(ResourceSelectorPolicy.matches(selector, "minecraft:oak_log", ignored -> false));
        assertFalse(ResourceSelectorPolicy.matches(selector, "minecraft:spruce_log", ignored -> true));
    }

    @Test
    void tagSelectorsDelegateMembershipToTheRegistryAdapter() {
        ResourceSelectorPolicy.Parsed selector = ResourceSelectorPolicy.parse("#minecraft:logs");
        assertTrue(ResourceSelectorPolicy.matches(selector, "minecraft:oak_log", tag -> tag.equals("minecraft:logs")));
        assertFalse(ResourceSelectorPolicy.matches(selector, "minecraft:stone", ignored -> false));
    }
}
