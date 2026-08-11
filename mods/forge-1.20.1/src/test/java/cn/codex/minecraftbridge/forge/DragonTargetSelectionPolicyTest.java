package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonTargetSelectionPolicyTest {
    @Test
    void movementAndCombatActionsRequireTheOwnersDragon() {
        for (String action : new String[] {
            "follow", "stay", "mount", "recall", "assist-combat", "land", "fly-to"
        }) {
            assertTrue(DragonTargetSelectionPolicy.requiresOwner(action), action);
        }
        assertFalse(DragonTargetSelectionPolicy.requiresOwner("observe"));
        assertFalse(DragonTargetSelectionPolicy.requiresOwner("tame"));
        assertFalse(DragonTargetSelectionPolicy.requiresOwner("feed"));
        assertFalse(DragonTargetSelectionPolicy.requiresOwner("care-for-egg"));
    }

    @Test
    void rememberedOwnedDragonRanksAheadOfOtherOwnedAndWildDragons() {
        int rememberedOwned = DragonTargetSelectionPolicy.rank(true, true);
        int otherOwned = DragonTargetSelectionPolicy.rank(false, true);
        int rememberedWild = DragonTargetSelectionPolicy.rank(true, false);
        int otherWild = DragonTargetSelectionPolicy.rank(false, false);

        assertTrue(rememberedOwned < otherOwned);
        assertTrue(otherOwned < rememberedWild);
        assertTrue(rememberedWild < otherWild);
    }
}
