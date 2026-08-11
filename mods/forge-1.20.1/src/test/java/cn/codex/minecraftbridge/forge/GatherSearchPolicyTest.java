package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GatherSearchPolicyTest {
    @Test
    void searchesTheReachableLevelBandBeforeDeepTargets() {
        assertEquals(4, GatherSearchPolicy.preferredVerticalRadius(24));
        assertEquals(3, GatherSearchPolicy.preferredVerticalRadius(3));
        assertEquals(0, GatherSearchPolicy.preferredVerticalRadius(-2));
    }
}
