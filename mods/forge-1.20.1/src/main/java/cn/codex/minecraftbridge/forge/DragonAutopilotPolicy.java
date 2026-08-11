package cn.codex.minecraftbridge.forge;

import java.util.UUID;

/** Pure eligibility and lease matching rules for server-authoritative dragon travel. */
final class DragonAutopilotPolicy {
    static final int RELEASE_STABILIZE_TICKS = 40;
    static final int RELEASE_TIMEOUT_TICKS = 80;

    private DragonAutopilotPolicy() {
    }

    static boolean canBegin(
        boolean dragonAlive,
        boolean ownerAlive,
        boolean ownerMountedOnDragon,
        boolean ownedByOwner
    ) {
        return dragonAlive && ownerAlive && ownerMountedOnDragon && ownedByOwner;
    }

    static boolean matches(
        UUID leasedOwnerId,
        UUID actualOwnerId,
        UUID leasedDragonId,
        UUID actualRootVehicleId
    ) {
        return leasedOwnerId != null
            && leasedOwnerId.equals(actualOwnerId)
            && leasedDragonId != null
            && leasedDragonId.equals(actualRootVehicleId);
    }

    static boolean releaseAcknowledged(
        double expectedX,
        double expectedY,
        double expectedZ,
        double packetX,
        double packetY,
        double packetZ,
        double tolerance
    ) {
        double boundedTolerance = Math.max(0.0D, tolerance);
        double x = packetX - expectedX;
        double y = packetY - expectedY;
        double z = packetZ - expectedZ;
        return x * x + y * y + z * z <= boundedTolerance * boundedTolerance;
    }

    static boolean releaseExpired(int currentTick, int releaseAfterTick) {
        return currentTick >= releaseAfterTick;
    }

    static boolean stabilizationComplete(int currentTick, int releaseNotBeforeTick) {
        return currentTick >= releaseNotBeforeTick;
    }

    static boolean releaseReady(
        boolean acknowledged,
        int currentTick,
        int releaseNotBeforeTick,
        int releaseAfterTick
    ) {
        return acknowledged && currentTick >= releaseNotBeforeTick
            || releaseExpired(currentTick, releaseAfterTick);
    }
}
