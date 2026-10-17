package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HomeStorageAreaPolicyTest {
    private final HomeStorageAreaPolicy.Area house = new HomeStorageAreaPolicy.Area(
        new HomeStorageAreaPolicy.Cell(-164, 75, -72), new HomeStorageAreaPolicy.Cell(-155, 84, -58), 76);
    private boolean allowed(int x, int y, int z, boolean indoor, boolean outside) {
        return HomeStorageAreaPolicy.allows(house, new HomeStorageAreaPolicy.Cell(x, y, z), true, true, indoor, outside);
    }
    @Test void acceptsRecognizedIndoorCells() { assertTrue(allowed(-160, 76, -65, true, false)); }
    @Test void boundingBoxAloneDoesNotProveAnIrregularHouseCornerIsIndoors() {
        assertFalse(allowed(-164, 76, -72, false, false));
    }
    @Test void outsideStorageRequiresTheFallbackPhase() {
        assertFalse(allowed(-165, 76, -65, false, false));
        assertTrue(allowed(-165, 76, -65, false, true));
    }
    @Test void measuresTenBlocksFromHouseWallsRatherThanTheBed() {
        assertTrue(allowed(-174, 76, -65, false, true));
        assertFalse(allowed(-175, 76, -65, false, true));
        assertTrue(allowed(-145, 76, -65, false, true));
        assertFalse(allowed(-144, 76, -65, false, true));
    }
    @Test void diagonalCornersCannotExceedTenBlocks() {
        assertTrue(allowed(-170, 76, -80, false, true));
        assertFalse(allowed(-174, 76, -82, false, true));
    }
    @Test void rejectsOutdoorRoofsAndDeepUndergroundPositions() {
        assertFalse(allowed(-165, 84, -65, false, true));
        assertFalse(allowed(-165, 72, -65, false, true));
        assertTrue(allowed(-165, 79, -65, false, true));
    }
    @Test void anUnverifiedRadiusFallbackNeverAuthorizesStorageExpansion() {
        var position = new HomeStorageAreaPolicy.Cell(-160, 76, -65);
        assertFalse(HomeStorageAreaPolicy.allows(house, position, false, true, true, true));
        assertFalse(HomeStorageAreaPolicy.allows(null, position, true, true, true, true));
    }
    @Test void homeInAnotherDimensionOrNewLocationInvalidatesTheOldTarget() {
        var position = new HomeStorageAreaPolicy.Cell(-160, 76, -65);
        assertFalse(HomeStorageAreaPolicy.allows(house, position, true, false, true, true));
        var moved = new HomeStorageAreaPolicy.Area(new HomeStorageAreaPolicy.Cell(0, 75, 0), new HomeStorageAreaPolicy.Cell(8, 84, 8), 76);
        assertFalse(HomeStorageAreaPolicy.allows(moved, position, true, true, false, true));
    }
}
