package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CraftChainLiveFixtureTest {
    @Test
    void recordsOnlyRealFuelInsertionIntoTheOwnedFixtureFurnace() {
        assertTrue(CraftChainLiveFixture.shouldRecordFuelSupply(true, true, true));
        assertFalse(CraftChainLiveFixture.shouldRecordFuelSupply(false, true, true));
        assertFalse(CraftChainLiveFixture.shouldRecordFuelSupply(true, false, true));
        assertFalse(CraftChainLiveFixture.shouldRecordFuelSupply(true, true, false));
    }
}
