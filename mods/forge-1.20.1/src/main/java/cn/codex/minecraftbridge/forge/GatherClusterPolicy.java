package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.List;

/** Data-only rules for exhausting a local ore vein before remote recovery. */
final class GatherClusterPolicy {
    record Offset(int x, int y, int z) {
    }

    private static final List<Offset> NEIGHBORS = neighbors();

    private GatherClusterPolicy() {
    }

    static List<Offset> connectedNeighbors() {
        return NEIGHBORS;
    }

    static boolean reconsiderSkippedAfterBreak(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared <= 64.0D;
    }

    private static List<Offset> neighbors() {
        List<Offset> result = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) result.add(new Offset(x, y, z));
                }
            }
        }
        return List.copyOf(result);
    }
}
