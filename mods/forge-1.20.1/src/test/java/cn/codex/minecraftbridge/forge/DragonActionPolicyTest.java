package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonActionPolicyTest {
    @Test
    void movementCompletesOnlyAfterTheDragonActuallyArrives() {
        assertEquals(3.5D, DragonActionPolicy.FLIGHT_REACH);
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.movement(
            20, DragonActionPolicy.RECALL_REACH, 0, 0, 20, DragonActionPolicy.RECALL_TIMEOUT_TICKS
        ));
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.movement(
            7.9, DragonActionPolicy.RECALL_REACH, 2, 0, 20, DragonActionPolicy.RECALL_TIMEOUT_TICKS
        ));
        assertEquals(DragonActionPolicy.Decision.COMPLETE, DragonActionPolicy.movement(
            7.9, DragonActionPolicy.RECALL_REACH, 3, DragonActionPolicy.MOVEMENT_STALL_TICKS, 20,
            DragonActionPolicy.RECALL_TIMEOUT_TICKS
        ));
    }

    @Test
    void movementReportsStallAndTimeoutInsteadOfFalseSuccess() {
        assertEquals(DragonActionPolicy.Decision.STALLED, DragonActionPolicy.movement(
            20, DragonActionPolicy.RECALL_REACH, 0, DragonActionPolicy.MOVEMENT_STALL_TICKS, 200,
            DragonActionPolicy.RECALL_TIMEOUT_TICKS
        ));
        assertEquals(DragonActionPolicy.Decision.TIMED_OUT, DragonActionPolicy.movement(
            20, DragonActionPolicy.RECALL_REACH, 0, 0, DragonActionPolicy.RECALL_TIMEOUT_TICKS,
            DragonActionPolicy.RECALL_TIMEOUT_TICKS
        ));
    }

    @Test
    void movementStopsSteeringDuringTheArrivalStabilityWindow() {
        assertTrue(DragonActionPolicy.shouldSteerMovement(5.01D, 5.0D));
        assertFalse(DragonActionPolicy.shouldSteerMovement(5.0D, 5.0D));
        assertFalse(DragonActionPolicy.shouldSteerMovement(4.0D, 5.0D));
    }

    @Test
    void landingRequiresBothFlightStateAndPhysicalGroundContact() {
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.landing(false, false, 0, 20));
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.landing(true, true, 0, 20));
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.landing(false, true, 4, 20));
        assertEquals(DragonActionPolicy.Decision.COMPLETE, DragonActionPolicy.landing(false, true, 5, 20));
        assertEquals(DragonActionPolicy.Decision.TIMED_OUT, DragonActionPolicy.landing(
            true, false, 0, DragonActionPolicy.LAND_TIMEOUT_TICKS
        ));
        assertTrue(DragonActionPolicy.shouldCommitGroundContact(true, false));
        assertFalse(DragonActionPolicy.shouldCommitGroundContact(false, false));
        assertFalse(DragonActionPolicy.shouldCommitGroundContact(true, true));
    }

    @Test
    void commandRetriesAreBoundedAndProgressIsMonotonic() {
        assertTrue(DragonActionPolicy.shouldIssueCommand(false, 1, 0));
        assertFalse(DragonActionPolicy.shouldIssueCommand(true, 19, 0));
        assertTrue(DragonActionPolicy.shouldIssueCommand(true, 20, 0));
        assertFalse(DragonActionPolicy.commandFailed(2));
        assertTrue(DragonActionPolicy.commandFailed(3));
        assertTrue(DragonActionPolicy.progress(100, 40, 5) > DragonActionPolicy.progress(100, 80, 5));
    }

    @Test
    void ownedDragonCommandsDoNotRequireTheNpcToPathBesideTheDragonFirst() {
        assertFalse(DragonActionPolicy.requiresApproach("follow"));
        assertFalse(DragonActionPolicy.requiresApproach("stay"));
        assertFalse(DragonActionPolicy.requiresApproach("recall"));
        assertFalse(DragonActionPolicy.requiresApproach("mount"));
        assertTrue(DragonActionPolicy.requiresApproach("feed"));
        assertTrue(DragonActionPolicy.requiresApproach("tame"));
    }

    @Test
    void finalLandingNeverReissuesTheFlightPathItJustCleared() {
        assertFalse(DragonActionPolicy.shouldIssueLandingCommand(true, false, 1, 0));
        assertFalse(DragonActionPolicy.shouldIssueLandingCommand(true, true, 40, 0));
        assertTrue(DragonActionPolicy.shouldIssueLandingCommand(false, false, 1, 0));
        assertTrue(DragonActionPolicy.shouldIssueLandingCommand(false, true, 40, 0));
    }

    @Test
    void commandsCompleteOnlyAfterTheRequestedStateIsStable() {
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.command(false, 0, 20));
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.command(true, 2, 20));
        assertEquals(DragonActionPolicy.Decision.COMPLETE, DragonActionPolicy.command(true, 3, 20));
        assertEquals(DragonActionPolicy.Decision.TIMED_OUT, DragonActionPolicy.command(
            false, 0, DragonActionPolicy.COMMAND_TIMEOUT_TICKS
        ));
    }

    @Test
    void mountingAndCombatRequirePhysicalWorldState() {
        assertEquals(8.0D, DragonActionPolicy.MOUNT_REACH);
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.mounting(false, 20));
        assertEquals(DragonActionPolicy.Decision.COMPLETE, DragonActionPolicy.mounting(true, 20));
        assertEquals(DragonActionPolicy.Decision.TIMED_OUT, DragonActionPolicy.mounting(
            false, DragonActionPolicy.MOUNT_TIMEOUT_TICKS
        ));
        assertEquals(DragonActionPolicy.Decision.CONTINUE, DragonActionPolicy.combat(true, 20));
        assertEquals(DragonActionPolicy.Decision.COMPLETE, DragonActionPolicy.combat(false, 20));
        assertEquals(DragonActionPolicy.Decision.TIMED_OUT, DragonActionPolicy.combat(
            true, DragonActionPolicy.COMBAT_TIMEOUT_TICKS
        ));
        assertTrue(DragonActionPolicy.combatComplete(true, false, false));
        assertTrue(DragonActionPolicy.combatComplete(false, true, false));
        assertFalse(DragonActionPolicy.combatComplete(false, false, false));
        assertFalse(DragonActionPolicy.combatComplete(false, true, true));
    }

    @Test
    void mountApproachRepathsAndSmallOscillationsCannotHideAStall() {
        assertTrue(DragonActionPolicy.shouldRepathMountApproach(true, 1));
        assertFalse(DragonActionPolicy.shouldRepathMountApproach(false, 9));
        assertTrue(DragonActionPolicy.shouldRepathMountApproach(false, 10));

        DragonActionPolicy.MountApproachSample sample =
            DragonActionPolicy.sampleMountApproach(0, -1.0D, 13.0D);
        for (double distance : new double[] { 12.9D, 13.1D, 12.8D, 13.0D }) {
            sample = DragonActionPolicy.sampleMountApproach(
                sample.stalledSamples(), sample.bestDistance(), distance
            );
        }
        assertEquals(4, sample.stalledSamples());
        assertEquals(13.0D, sample.bestDistance());

        sample = DragonActionPolicy.sampleMountApproach(
            sample.stalledSamples(), sample.bestDistance(), 12.4D
        );
        assertEquals(0, sample.stalledSamples());
        assertEquals(12.4D, sample.bestDistance());
    }

    @Test
    void riddenCombatRetriesQuicklyAndAccountsForDragonBodySize() {
        assertTrue(DragonActionPolicy.shouldIssueCombatCommand(false, 1, 0));
        assertFalse(DragonActionPolicy.shouldIssueCombatCommand(true, 9, 0));
        assertTrue(DragonActionPolicy.shouldIssueCombatCommand(true, 10, 0));

        assertEquals(5.0D, DragonActionPolicy.combatReach(1.0D, 1.0D, 0.6D));
        double giantReach = DragonActionPolicy.combatReach(8.0D, 10.0D, 0.6D);
        assertTrue(giantReach > 9.0D);
        assertTrue(DragonActionPolicy.shouldSteerRiddenCombat(true, giantReach + 1.0D, giantReach));
        assertFalse(DragonActionPolicy.shouldSteerRiddenCombat(true, giantReach, giantReach));
        assertFalse(DragonActionPolicy.shouldSteerRiddenCombat(false, giantReach + 1.0D, giantReach));
    }

    @Test
    void combatFallsBackWhenAnActivatedModAbilityDoesNotProduceAHurtCooldown() {
        assertTrue(DragonActionPolicy.shouldUseMeleeFallback(false, 0));
        assertTrue(DragonActionPolicy.shouldUseMeleeFallback(true, 0));
        assertFalse(DragonActionPolicy.shouldUseMeleeFallback(true, 5));
        assertTrue(DragonActionPolicy.shouldActivateModCombatAbility(false));
        assertFalse(DragonActionPolicy.shouldActivateModCombatAbility(true));
    }

    @Test
    void airborneStopSurvivesTransientTerrainContactButLandingStaysGrounded() {
        assertTrue(DragonActionPolicy.shouldPreserveAirborneStop(true, false, true));
        assertTrue(DragonActionPolicy.shouldPreserveAirborneStop(false, true, true));
        assertTrue(DragonActionPolicy.shouldPreserveAirborneStop(false, false, false));
        assertFalse(DragonActionPolicy.shouldPreserveAirborneStop(false, false, true));
    }

    @Test
    void recallDistanceLeavesRoomForLargeDragonBodies() {
        assertEquals(DragonActionPolicy.RECALL_REACH, DragonActionPolicy.recallReach(1.0D));
        assertEquals(10.0D, DragonActionPolicy.recallReach(4.0D));
        assertEquals(24.0D, DragonActionPolicy.recallReach(40.0D));
        assertEquals(24.0D, DragonActionPolicy.recallReach(1.0D, true));
        assertEquals(10.0D, DragonActionPolicy.recallReach(4.0D, false));
    }

    @Test
    void onlyCheatEnabledLongRangeRecallUsesTheSafeTeleportCompletionRadius() {
        assertFalse(DragonActionPolicy.shouldTeleportRecall(false, 96.0D));
        assertFalse(DragonActionPolicy.shouldTeleportRecall(true, 64.0D));
        assertTrue(DragonActionPolicy.shouldTeleportRecall(true, 64.001D));
    }

    @Test
    void onlyNpcControlledTravelActionsClearModWaypoints() {
        assertTrue(DragonActionPolicy.shouldHaltTravel("fly-to"));
        assertTrue(DragonActionPolicy.shouldHaltTravel("recall"));
        assertTrue(DragonActionPolicy.shouldHaltTravel("assist-combat"));
        assertTrue(DragonActionPolicy.shouldHaltTravel("land"));
        assertFalse(DragonActionPolicy.shouldHaltTravel("follow"));
        assertFalse(DragonActionPolicy.shouldHaltTravel("stay"));
        assertFalse(DragonActionPolicy.shouldHaltTravel("mount"));
    }
}
