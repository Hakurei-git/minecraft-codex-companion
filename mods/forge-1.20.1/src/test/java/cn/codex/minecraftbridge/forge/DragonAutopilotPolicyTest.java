package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonAutopilotPolicyTest {
    @Test
    void beginsOnlyForALiveOwnedDragonCurrentlyCarryingTheOwner() {
        assertTrue(DragonAutopilotPolicy.canBegin(true, true, true, true));
        assertFalse(DragonAutopilotPolicy.canBegin(false, true, true, true));
        assertFalse(DragonAutopilotPolicy.canBegin(true, false, true, true));
        assertFalse(DragonAutopilotPolicy.canBegin(true, true, false, true));
        assertFalse(DragonAutopilotPolicy.canBegin(true, true, true, false));
    }

    @Test
    void suppressesOnlyTheLeasedOwnersMatchingRootVehicle() {
        UUID owner = UUID.randomUUID();
        UUID dragon = UUID.randomUUID();
        assertTrue(DragonAutopilotPolicy.matches(owner, owner, dragon, dragon));
        assertFalse(DragonAutopilotPolicy.matches(owner, UUID.randomUUID(), dragon, dragon));
        assertFalse(DragonAutopilotPolicy.matches(owner, owner, dragon, UUID.randomUUID()));
        assertFalse(DragonAutopilotPolicy.matches(null, owner, dragon, dragon));
    }

    @Test
    void releaseHandshakeRejectsStaleVehiclePositionsBeforeRestoringControl() {
        assertTrue(DragonAutopilotPolicy.RELEASE_STABILIZE_TICKS >= 20);
        assertTrue(DragonAutopilotPolicy.RELEASE_TIMEOUT_TICKS
            > DragonAutopilotPolicy.RELEASE_STABILIZE_TICKS);
        assertTrue(DragonAutopilotPolicy.releaseAcknowledged(
            10.0D, 20.0D, 30.0D, 10.03D, 20.0D, 30.0D, 0.05D
        ));
        assertFalse(DragonAutopilotPolicy.releaseAcknowledged(
            10.0D, 20.0D, 30.0D, 10.20D, 20.0D, 30.0D, 0.05D
        ));
        assertFalse(DragonAutopilotPolicy.releaseExpired(139, 140));
        assertTrue(DragonAutopilotPolicy.releaseExpired(140, 140));
        assertFalse(DragonAutopilotPolicy.stabilizationComplete(109, 110));
        assertTrue(DragonAutopilotPolicy.stabilizationComplete(110, 110));
    }

    @Test
    void releaseHandshakeHoldsTheFinalPositionUntilTheStabilizationWindowEnds() {
        assertFalse(DragonAutopilotPolicy.releaseReady(true, 109, 110, 140));
        assertFalse(DragonAutopilotPolicy.releaseReady(false, 120, 110, 140));
        assertTrue(DragonAutopilotPolicy.releaseReady(true, 110, 110, 140));
        assertTrue(DragonAutopilotPolicy.releaseReady(false, 140, 110, 140));
    }
}
