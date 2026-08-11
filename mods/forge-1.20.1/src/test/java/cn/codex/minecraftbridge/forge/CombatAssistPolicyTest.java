package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CombatAssistPolicyTest {
    @Test
    void assistsAgainstLivingNonAlliedMobs() {
        assertTrue(CombatAssistPolicy.shouldAssist(true, false, false, false, false, false, false));
    }

    @Test
    void excludesOwnerCompanionsAndAllies() {
        assertFalse(CombatAssistPolicy.shouldAssist(true, true, false, false, false, false, false));
        assertFalse(CombatAssistPolicy.shouldAssist(true, false, true, false, false, false, false));
        assertFalse(CombatAssistPolicy.shouldAssist(true, false, false, true, false, false, false));
        assertFalse(CombatAssistPolicy.shouldAssist(true, false, false, false, true, false, false));
        assertFalse(CombatAssistPolicy.shouldAssist(false, false, false, false, false, false, false));
    }

    @Test
    void onlyAssistsAgainstPlayersWhenPvpIsAllowed() {
        assertFalse(CombatAssistPolicy.shouldAssist(true, false, false, false, false, true, false));
        assertTrue(CombatAssistPolicy.shouldAssist(true, false, false, false, false, true, true));
    }

    @Test
    void repeatedDamageEventsDoNotRenewTheSameStalledTarget() {
        int initial = CombatAssistPolicy.updateDeadline(100, 0, 600, true, false);
        assertEquals(700, initial);
        assertEquals(initial, CombatAssistPolicy.updateDeadline(400, initial, 600, false, false));
        assertFalse(CombatAssistPolicy.leaseExpired(700, initial));
        assertTrue(CombatAssistPolicy.leaseExpired(701, initial));
    }

    @Test
    void meaningfulMovementOrDamageRenewsTheLease() {
        assertTrue(CombatAssistPolicy.madeProgress(8.0D, 9.0D, 20.0F, 20.0F));
        assertTrue(CombatAssistPolicy.madeProgress(9.0D, 9.0D, 18.0F, 20.0F));
        assertFalse(CombatAssistPolicy.madeProgress(8.75D, 9.0D, 20.0F, 20.0F));
        assertEquals(1_000, CombatAssistPolicy.updateDeadline(400, 700, 600, false, true));
    }

    @Test
    void stalledTargetIsSuppressedOnlyUntilItsRetryWindowEnds() {
        assertTrue(CombatAssistPolicy.retrySuppressed(true, 800, 900));
        assertFalse(CombatAssistPolicy.retrySuppressed(true, 900, 900));
        assertFalse(CombatAssistPolicy.retrySuppressed(false, 800, 900));
    }
}
