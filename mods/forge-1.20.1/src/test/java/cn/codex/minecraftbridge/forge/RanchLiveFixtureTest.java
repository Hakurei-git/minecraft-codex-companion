package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RanchLiveFixtureTest {
    @Test
    void matchesTheBuiltinNineByNineAnimalPen() {
        BlockPos origin = new BlockPos(40, 80, -20);
        List<BlockPos> positions = RanchLiveFixture.expectedPenPositions(origin);
        assertEquals(RanchLiveFixture.PEN_BLOCK_COUNT, positions.size());
        assertEquals(positions.size(), new HashSet<>(positions).size());

        BlockPos gate = origin.offset(4, 0, 0);
        int fences = 0;
        int gates = 0;
        for (BlockPos position : positions) {
            if (position.equals(gate)) {
                gates++;
            } else fences++;
        }
        assertEquals(31, fences);
        assertEquals(1, gates);
        assertTrue(RanchLiveFixture.expectedGateContract("south", false));
        assertFalse(RanchLiveFixture.expectedGateContract("north", false));
        assertFalse(RanchLiveFixture.expectedGateContract("south", true));
    }

    @Test
    void emitsTheExactUnbuiltAndNpcBuiltEvidenceContracts() {
        assertEquals(
            "ranch-fixture:adults=2,babies=0,inside=0,outside=2,built=0,blocks=0,placements=0,gate=missing",
            RanchLiveFixture.inspectionStatus(2, 0, 0, 2, 0, 0, 0, "missing")
        );
        assertEquals(
            "ranch-fixture:adults=2,babies=1,inside=3,outside=0,built=1,blocks=32,placements=32,gate=closed",
            RanchLiveFixture.inspectionStatus(2, 1, 3, 0, 32, 32, 0, "closed")
        );
    }

    @Test
    void refusesPartialOpenDuplicateWrongOrUnsynchronisedPlacementEvidence() {
        assertTrue(RanchLiveFixture.builtEvidence(32, 32, 0, "closed"));
        assertFalse(RanchLiveFixture.builtEvidence(31, 32, 0, "closed"));
        assertFalse(RanchLiveFixture.builtEvidence(32, 31, 0, "closed"));
        assertFalse(RanchLiveFixture.builtEvidence(32, 33, 0, "closed"));
        assertFalse(RanchLiveFixture.builtEvidence(32, 32, 1, "closed"));
        assertFalse(RanchLiveFixture.builtEvidence(32, 32, 0, "open"));
        assertFalse(RanchLiveFixture.builtEvidence(32, 32, 0, "missing"));
    }
}
