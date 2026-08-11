package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonFollowPolicyTest {
    @Test
    void creativeFlightAndMountedDragonFlightTriggerTakeoff() {
        assertEquals(DragonFollowPolicy.Decision.TAKE_OFF,
            DragonFollowPolicy.decide(false, true, false, false, false, 0, 3));
        assertEquals(DragonFollowPolicy.Decision.TAKE_OFF,
            DragonFollowPolicy.decide(false, false, false, true, false, 0, 3));
    }

    @Test
    void verticalSeparationAndLongCatchupTriggerTakeoff() {
        assertEquals(DragonFollowPolicy.Decision.TAKE_OFF,
            DragonFollowPolicy.decide(false, false, false, false, false, 5, 5));
        assertEquals(DragonFollowPolicy.Decision.TAKE_OFF,
            DragonFollowPolicy.decide(false, false, false, false, true, 0, 13));
    }

    @Test
    void ownerLandingRequestsLandingOnlyWhenNearby() {
        assertEquals(DragonFollowPolicy.Decision.LAND,
            DragonFollowPolicy.decide(true, false, false, false, true, 0, 8));
        assertEquals(DragonFollowPolicy.Decision.CRUISE,
            DragonFollowPolicy.decide(true, false, false, false, true, 0, 30));
    }

    @Test
    void terrainDetourForcesTakeoffAndPreventsPrematureLanding() {
        assertEquals(DragonFollowPolicy.Decision.TAKE_OFF,
            DragonFollowPolicy.decide(false, false, false, false, true, 0, 6, true, false));
        assertEquals(DragonFollowPolicy.Decision.CRUISE,
            DragonFollowPolicy.decide(true, false, false, false, true, 0, 6, true, false));
        assertEquals(DragonFollowPolicy.Decision.CRUISE,
            DragonFollowPolicy.decide(true, false, false, false, true, 0, 6, false, false));
        assertEquals(DragonFollowPolicy.Decision.LAND,
            DragonFollowPolicy.decide(true, false, false, false, true, 0, 6, false, true));
    }

    @Test
    void aerialTargetReflectsEverySupportedFlightSignal() {
        assertTrue(DragonFollowPolicy.useAerialTarget(true, false, false, 0));
        assertTrue(DragonFollowPolicy.useAerialTarget(false, true, false, 0));
        assertTrue(DragonFollowPolicy.useAerialTarget(false, false, true, 0));
        assertTrue(DragonFollowPolicy.useAerialTarget(false, false, false, 5));
        assertFalse(DragonFollowPolicy.useAerialTarget(false, false, false, 2));
    }
}
