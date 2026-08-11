package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FoodSurvivalLiveFixtureTest {
    @Test
    void deduplicatesWallCornersAndAllowsOnlyLegacyMissingBlocks() {
        BlockPos origin = new BlockPos(0, 96, 0);
        assertEquals(522, FoodSurvivalLiveFixture.fixtureBlocks(origin, origin.offset(-7, 0, 0)).size());
        assertFalse(FoodSurvivalLiveFixture.trackedBlockConflict(true, true, false));
        assertTrue(FoodSurvivalLiveFixture.trackedBlockConflict(false, true, false));
        assertTrue(FoodSurvivalLiveFixture.trackedBlockConflict(true, false, false));
        assertFalse(FoodSurvivalLiveFixture.trackedBlockConflict(false, false, true));
    }

    @Test
    void encodesBoundedNpcOnlyEvidence() {
        FoodSurvivalLiveFixture.Evidence evidence = completeEvidence();
        assertEquals(
            "food-survival:a=8,k=2,r=5,i=1,l=1,o=1,w=5,g=1,u=1,x=1,s=4,p=3,v=0,d=4,t=1,q=4,h=6",
            FoodSurvivalLiveFixture.inspectionStatus(evidence)
        );
        assertTrue(FoodSurvivalLiveFixture.inspectionStatus(evidence).length() <= 120);
        assertTrue(FoodSurvivalLiveFixture.completeEvidence(evidence));
    }

    @Test
    void rejectsProtectedLossAndMissingRestartEvidence() {
        FoodSurvivalLiveFixture.Evidence valid = completeEvidence();
        assertFalse(FoodSurvivalLiveFixture.completeEvidence(new FoodSurvivalLiveFixture.Evidence(
            valid.attacks(), valid.kills(), valid.rawDrops(), valid.inputObserved(), valid.litObserved(),
            valid.outputObserved(), valid.withdrawn(), valid.guardObserved(), valid.resumeObserved(), false,
            valid.survivingAdults(), valid.protectedAlive(), valid.violations(), valid.physicalDelivered(),
            valid.sameTaskObserved(), valid.targetCount(), valid.huntableCount()
        )));
        assertFalse(FoodSurvivalLiveFixture.completeEvidence(new FoodSurvivalLiveFixture.Evidence(
            valid.attacks(), 4, valid.rawDrops(), valid.inputObserved(), valid.litObserved(),
            valid.outputObserved(), valid.withdrawn(), valid.guardObserved(), valid.resumeObserved(),
            valid.restartObserved(), 1, 2, 1, valid.physicalDelivered(), valid.sameTaskObserved(),
            valid.targetCount(), valid.huntableCount()
        )));
    }

    @Test
    void supportsTheStrictSixteenItemLedgerAndEighteenReachableTargets() {
        BlockPos origin = new BlockPos(10, 100, -20);
        assertEquals(18, FoodSurvivalLiveFixture.ordinaryCowPositions(origin, 18).size());
        assertEquals(18, FoodSurvivalLiveFixture.ordinaryCowPositions(origin, 18).stream().distinct().count());
        assertTrue(FoodSurvivalLiveFixture.completeEvidence(new FoodSurvivalLiveFixture.Evidence(
            24, 8, 17, true, true, true, 16, true, true, true,
            10, 3, 0, 16, true, 16, 18
        )));
    }

    @Test
    void exposesStableFailureCodes() {
        assertEquals("world-block-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found world-block-conflict")
        ));
        assertEquals("furnace-content-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-content-conflict")
        ));
        assertEquals("furnace-input-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-input-conflict")
        ));
        assertEquals("furnace-input-kind-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-input-kind-conflict")
        ));
        assertEquals("furnace-input-source-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-input-source-conflict")
        ));
        assertEquals("furnace-fuel-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-fuel-conflict")
        ));
        assertEquals("furnace-fuel-kind-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-fuel-kind-conflict")
        ));
        assertEquals("furnace-fuel-source-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-fuel-source-conflict")
        ));
        assertEquals("furnace-output-kind-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-output-kind-conflict")
        ));
        assertEquals("furnace-output-ledger-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found furnace-output-ledger-conflict")
        ));
        assertEquals("npc-inventory-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("cleanup found npc-inventory-conflict")
        ));
        assertEquals("npc-restore", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("NPC snapshot was not restored")
        ));
        assertEquals("protected-animal", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("protected cow was harmed")
        ));
        assertEquals("task-state", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("task identity changed")
        ));
        assertEquals("furnace-state", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("furnace did not start")
        ));
        assertEquals("site-unavailable", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("No isolated food survival fixture site was found")
        ));
        assertEquals("npc-path-unavailable", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("fixture cow is not reachable by the NPC")
        ));
        assertEquals("block-conflict", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("fixture block placement was incomplete")
        ));
        assertEquals("npc-not-idle", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("fixture requires an idle NPC task scheduler")
        ));
        assertEquals("fixture-failed", FoodSurvivalLiveFixture.failureCode(
            new IllegalStateException("other")
        ));
    }

    @Test
    void acceptsOnlyTaggedInputsAndLedgerBoundedRaceOutputFromTheOwnedEmptyFurnace() {
        FoodSurvivalLiveFixture.FurnaceCleanupEvidence evidence =
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(true, true, true, true, 6, 2, 3, 1);

        assertTrue(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            0, FoodSurvivalLiveFixture.FurnaceContentKind.RAW_BEEF, 3, true, evidence
        ));
        assertTrue(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.FUEL, 1, true, evidence
        ));
        assertTrue(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            2, FoodSurvivalLiveFixture.FurnaceContentKind.COOKED_BEEF, 1, false, evidence
        ));

        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            0, FoodSurvivalLiveFixture.FurnaceContentKind.RAW_BEEF, 3, false, evidence
        ));
        assertTrue(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.FUEL, 1, false, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.FUEL, 2, false, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            0, FoodSurvivalLiveFixture.FurnaceContentKind.OTHER, 1, true, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.RAW_BEEF, 1, true, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.FUEL_RESIDUE, 1, true, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            2, FoodSurvivalLiveFixture.FurnaceContentKind.COOKED_BEEF, 2, false, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            2,
            FoodSurvivalLiveFixture.FurnaceContentKind.COOKED_BEEF,
            1,
            false,
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(false, true, true, true, 6, 2, 3, 1)
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            2,
            FoodSurvivalLiveFixture.FurnaceContentKind.COOKED_BEEF,
            1,
            false,
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(true, true, true, false, 6, 2, 3, 1)
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1,
            FoodSurvivalLiveFixture.FurnaceContentKind.FUEL,
            1,
            false,
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(true, true, false, true, 6, 2, 3, 1)
        ));
        assertFalse(FoodSurvivalLiveFixture.fixtureFurnaceContentAllowed(
            1,
            FoodSurvivalLiveFixture.FurnaceContentKind.FUEL,
            1,
            false,
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(true, true, true, true, 6, 2, 3, 0)
        ));
    }

    @Test
    void reportsOnlyBoundedFurnaceSlotConflictCategories() {
        assertEquals("furnace-input-source-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            0, FoodSurvivalLiveFixture.FurnaceContentKind.RAW_BEEF, false
        ));
        assertEquals("furnace-input-kind-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            0, FoodSurvivalLiveFixture.FurnaceContentKind.OTHER, true
        ));
        assertEquals("furnace-fuel-source-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.FUEL, false
        ));
        assertEquals("furnace-fuel-kind-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.RAW_BEEF, true
        ));
        assertEquals("furnace-fuel-kind-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            1, FoodSurvivalLiveFixture.FurnaceContentKind.FUEL_RESIDUE, true
        ));
        assertEquals("furnace-output-kind-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            2, FoodSurvivalLiveFixture.FurnaceContentKind.OTHER, false
        ));
        assertEquals("furnace-output-ledger-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            2, FoodSurvivalLiveFixture.FurnaceContentKind.COOKED_BEEF, false
        ));
        assertEquals("furnace-content-conflict", FoodSurvivalLiveFixture.furnaceContentConflictCode(
            2, FoodSurvivalLiveFixture.FurnaceContentKind.COOKED_BEEF, true
        ));
    }

    @Test
    void preservesOnlyABoundedFuelSlotConflictFromTheOwnedFurnace() {
        FoodSurvivalLiveFixture.FurnaceCleanupEvidence evidence =
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(true, true, true, true, 6, 2, 3, 1);

        assertTrue(FoodSurvivalLiveFixture.recoverableFuelSlotConflict(
            1, 1, evidence
        ));
        assertTrue(FoodSurvivalLiveFixture.recoverableFuelSlotConflict(
            1, 64, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.recoverableFuelSlotConflict(
            1, 65, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.recoverableFuelSlotConflict(
            0, 1, evidence
        ));
        assertFalse(FoodSurvivalLiveFixture.recoverableFuelSlotConflict(
            1,
            1,
            new FoodSurvivalLiveFixture.FurnaceCleanupEvidence(false, true, false, true, 6, 2, 3, 1)
        ));
    }

    @Test
    void preservesOnlyUntaggedStacksFromAMutatedNpcInventory() {
        assertTrue(FoodSurvivalLiveFixture.shouldPreserveNpcInventoryConflict(true, false, false));
        assertFalse(FoodSurvivalLiveFixture.shouldPreserveNpcInventoryConflict(false, false, false));
        assertFalse(FoodSurvivalLiveFixture.shouldPreserveNpcInventoryConflict(true, true, false));
        assertFalse(FoodSurvivalLiveFixture.shouldPreserveNpcInventoryConflict(true, false, true));
    }

    private static FoodSurvivalLiveFixture.Evidence completeEvidence() {
        return new FoodSurvivalLiveFixture.Evidence(
            8, 2, 5, true, true, true, 5, true, true, true, 4, 3, 0, 4, true, 4, 6
        );
    }
}
