package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NaturalTreeScannerTest {
    @Test
    void safeSeedIsFirstAndEveryOtherTreeLogIsPreserved() {
        BlockPos seed = new BlockPos(10, 65, 10);
        BlockPos lowerNearby = new BlockPos(11, 64, 10);
        BlockPos lowerFarther = new BlockPos(12, 64, 10);
        BlockPos upper = new BlockPos(10, 66, 10);
        NaturalTreeScanner.Cluster cluster = new NaturalTreeScanner.Cluster(
            Set.of(upper, lowerFarther, seed, lowerNearby),
            true
        );

        assertEquals(
            List.of(seed, lowerNearby, lowerFarther, upper),
            NaturalTreeScanner.orderedTargets(cluster, seed)
        );
    }

    @Test
    void rejectsInvalidOrNonNaturalClusters() {
        BlockPos seed = new BlockPos(10, 65, 10);

        assertTrue(NaturalTreeScanner.orderedTargets(
            new NaturalTreeScanner.Cluster(Set.of(seed), false),
            seed
        ).isEmpty());
        assertTrue(NaturalTreeScanner.orderedTargets(
            new NaturalTreeScanner.Cluster(Set.of(seed.above()), true),
            seed
        ).isEmpty());
    }
}
