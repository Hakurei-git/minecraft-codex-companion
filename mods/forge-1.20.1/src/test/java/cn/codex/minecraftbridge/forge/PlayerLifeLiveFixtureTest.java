package cn.codex.minecraftbridge.forge;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLifeLiveFixtureTest {
    @Test
    void emitsStrictSpecifiedEatingEvidence() {
        assertEquals(
            "eat-fixture:c=rotten,f=20,e=3,r=0,m=2,s=3,x=3,si=rotten,fi=rotten,v=0,mg=0,u=0",
            PlayerLifeLiveFixture.eatingInspectionStatus(
                "rotten", 20, 3, 0, 2, 3, 3, "rotten", "rotten", 0, false, false
            )
        );
        assertEquals(
            "eat-fixture:c=melon,f=20,e=2,r=3,m=0,s=2,x=2,si=melon,fi=melon,v=0,mg=0,u=0",
            PlayerLifeLiveFixture.eatingInspectionStatus(
                "melon", 20, 2, 3, 0, 2, 2, "melon", "melon", 0, false, false
            )
        );
    }

    @Test
    void emitsFullHungerZeroConsumptionEvidence() {
        assertEquals(
            "eat-fixture:c=full,f=20,e=0,r=3,m=2,s=0,x=0,si=none,fi=none,v=0,mg=0,u=0",
            PlayerLifeLiveFixture.eatingInspectionStatus(
                "full", 20, 0, 3, 2, 0, 0, "none", "none", 0, false, false
            )
        );
    }

    @Test
    void restorationChecksEveryNpcLifeAndTaskField() {
        String[] keys = {
            "UUID", "CustomName", "CustomNameVisible", "Health", "AbsorptionAmount", "ActiveEffects",
            "Motion", "FallDistance", "OnGround", "Invulnerable", "NoAI", "Silent", "Glowing",
            "HandItems", "ArmorItems", "CodexOwner", "CodexStance", "CodexDowned",
            "CodexRecoveryTicks", "CodexFood", "CodexSaturation", "CodexExhaustion",
            "CodexNaturalRegenerationTicks", "CodexEatingCompletionSequence", "CodexLastEatenName",
            "CodexAutomaticEatingUntilFull", "CodexStatus", "CodexInventory", "CodexTaskSchedulerV2",
            "CodexBoundDragon", "CodexBoundDragonDimension", "CodexBoundDragonPosition"
        };
        CompoundTag expected = new CompoundTag();
        CompoundTag actual = new CompoundTag();
        for (String key : keys) {
            expected.putString(key, "saved");
            actual.putString(key, "saved");
        }
        assertTrue(PlayerLifeLiveFixture.stableNpcFieldsMatch(expected, actual));
        for (String key : keys) {
            actual.putString(key, "changed");
            assertFalse(PlayerLifeLiveFixture.stableNpcFieldsMatch(expected, actual), key);
            actual.putString(key, "saved");
        }
    }

    @Test
    void restorationRejectsAnEffectThatWasMergedIntoAnEffectFreeBaseline() {
        CompoundTag expected = new CompoundTag();
        CompoundTag withFixtureHunger = new CompoundTag();
        withFixtureHunger.putString("ActiveEffects", "fixture-hunger");
        assertFalse(PlayerLifeLiveFixture.stableNpcFieldsMatch(expected, withFixtureHunger));
        withFixtureHunger.remove("ActiveEffects");
        assertTrue(PlayerLifeLiveFixture.stableNpcFieldsMatch(expected, withFixtureHunger));
    }

    @Test
    void failureCodesDoNotExposeFixtureState() {
        assertEquals("npc-restore", PlayerLifeLiveFixture.failureCode(
            new IllegalStateException("Player life fixture NPC snapshot was not restored exactly")
        ));
        assertEquals("npc-not-idle", PlayerLifeLiveFixture.failureCode(
            new IllegalStateException("Finish NPC tasks before changing a player life fixture")
        ));
        assertEquals("npc-effects", PlayerLifeLiveFixture.failureCode(
            new IllegalStateException("Player life fixture NPC effects could not be cleared before restore")
        ));
        assertEquals("fixture-failed", PlayerLifeLiveFixture.failureCode(new IllegalStateException("other")));
    }
}
