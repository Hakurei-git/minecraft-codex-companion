package cn.codex.minecraftbridge.forge;

final class DragonActionPolicy {
    static final double RECALL_REACH = 8.0D;
    static final double RECALL_TELEPORT_DISTANCE = 64.0D;
    static final double RECALL_TELEPORT_COMPLETION_REACH = 24.0D;
    static final double FLIGHT_REACH = 3.5D;
    static final double MOUNT_REACH = 8.0D;
    static final int COMMAND_INTERVAL_TICKS = 20;
    static final int COMBAT_COMMAND_INTERVAL_TICKS = 10;
    static final int MAX_COMMAND_FAILURES = 3;
    static final int MOVEMENT_STABLE_TICKS = 3;
    static final int LAND_STABLE_TICKS = 5;
    static final int COMMAND_STABLE_TICKS = 3;
    static final int MOUNT_REPATH_INTERVAL_TICKS = 10;
    static final double MOUNT_MEANINGFUL_PROGRESS = 0.5D;
    static final int MOVEMENT_STALL_TICKS = 20 * 30;
    static final int RECALL_TIMEOUT_TICKS = 20 * 180;
    static final int FLIGHT_TIMEOUT_TICKS = 20 * 300;
    static final int LAND_TIMEOUT_TICKS = 20 * 90;
    static final int COMMAND_TIMEOUT_TICKS = 20 * 15;
    static final int MOUNT_TIMEOUT_TICKS = 20 * 60;
    static final int COMBAT_TIMEOUT_TICKS = 20 * 300;

    enum Decision { COMPLETE, CONTINUE, STALLED, TIMED_OUT }

    record MountApproachSample(int stalledSamples, double bestDistance) {
    }

    private DragonActionPolicy() {}

    static Decision movement(
        double distance,
        double reach,
        int stableTicks,
        int stalledTicks,
        int elapsedTicks,
        int timeoutTicks
    ) {
        if (distance <= reach && stableTicks >= MOVEMENT_STABLE_TICKS) return Decision.COMPLETE;
        if (elapsedTicks >= timeoutTicks) return Decision.TIMED_OUT;
        if (stalledTicks >= MOVEMENT_STALL_TICKS) return Decision.STALLED;
        return Decision.CONTINUE;
    }

    static Decision landing(boolean flying, boolean onGround, int stableTicks, int elapsedTicks) {
        if (!flying && onGround && stableTicks >= LAND_STABLE_TICKS) return Decision.COMPLETE;
        if (elapsedTicks >= LAND_TIMEOUT_TICKS) return Decision.TIMED_OUT;
        return Decision.CONTINUE;
    }

    static boolean shouldCommitGroundContact(boolean collisionBelow, boolean feetInFluid) {
        return collisionBelow && !feetInFluid;
    }

    static Decision command(boolean desiredStateObserved, int stableTicks, int elapsedTicks) {
        if (desiredStateObserved && stableTicks >= COMMAND_STABLE_TICKS) return Decision.COMPLETE;
        if (elapsedTicks >= COMMAND_TIMEOUT_TICKS) return Decision.TIMED_OUT;
        return Decision.CONTINUE;
    }

    static Decision mounting(boolean mounted, int elapsedTicks) {
        if (mounted) return Decision.COMPLETE;
        if (elapsedTicks >= MOUNT_TIMEOUT_TICKS) return Decision.TIMED_OUT;
        return Decision.CONTINUE;
    }

    static boolean shouldRepathMountApproach(boolean navigationDone, int ticks) {
        return navigationDone || ticks % MOUNT_REPATH_INTERVAL_TICKS == 0;
    }

    static MountApproachSample sampleMountApproach(
        int stalledSamples,
        double bestDistance,
        double currentDistance
    ) {
        if (!Double.isFinite(currentDistance) || currentDistance < 0.0D) {
            return new MountApproachSample(stalledSamples, bestDistance);
        }
        if (!Double.isFinite(bestDistance) || bestDistance < 0.0D) {
            return new MountApproachSample(0, currentDistance);
        }
        if (currentDistance <= bestDistance - MOUNT_MEANINGFUL_PROGRESS) {
            return new MountApproachSample(0, currentDistance);
        }
        return new MountApproachSample(stalledSamples + 1, bestDistance);
    }

    static Decision combat(boolean targetAlive, int elapsedTicks) {
        if (!targetAlive) return Decision.COMPLETE;
        if (elapsedTicks >= COMBAT_TIMEOUT_TICKS) return Decision.TIMED_OUT;
        return Decision.CONTINUE;
    }

    static boolean combatComplete(boolean defeatObserved, boolean targetPresent, boolean targetAlive) {
        return defeatObserved || targetPresent && !targetAlive;
    }

    static boolean shouldIssueCommand(boolean initialized, int ticks, int lastActionTick) {
        return !initialized || ticks - lastActionTick >= COMMAND_INTERVAL_TICKS;
    }

    static boolean requiresApproach(String action) {
        return switch (action) {
            case "follow", "stay", "mount", "share-ride", "recall", "assist-combat", "land", "fly-to" -> false;
            default -> true;
        };
    }

    static boolean shouldIssueLandingCommand(
        boolean finalizingLanding,
        boolean initialized,
        int ticks,
        int lastActionTick
    ) {
        // A mounted Book of Dragons entity keeps an AI flight path in the
        // FOLLOWING state because its movement component does not tick while
        // it has passengers. Reissuing land after the final path is cleared
        // therefore changes the state evaluator straight back to AIRBORNE.
        return !finalizingLanding && shouldIssueCommand(initialized, ticks, lastActionTick);
    }

    static boolean shouldIssueCombatCommand(boolean initialized, int ticks, int lastActionTick) {
        return !initialized || ticks - lastActionTick >= COMBAT_COMMAND_INTERVAL_TICKS;
    }

    static double combatReach(double dragonWidth, double dragonHeight, double targetWidth) {
        double bodyReach = Math.max(0.0D, dragonWidth) * 0.75D
            + Math.max(0.0D, dragonHeight) * 0.35D
            + Math.max(0.0D, targetWidth) * 0.5D;
        return Math.max(5.0D, bodyReach);
    }

    static double recallReach(double dragonWidth) {
        return Math.max(RECALL_REACH, Math.min(24.0D, Math.ceil(Math.max(0.0D, dragonWidth) + 6.0D)));
    }

    static double recallReach(double dragonWidth, boolean longRangeTeleportStaged) {
        double physicalReach = recallReach(dragonWidth);
        return longRangeTeleportStaged
            ? Math.max(physicalReach, RECALL_TELEPORT_COMPLETION_REACH)
            : physicalReach;
    }

    static boolean shouldTeleportRecall(boolean allowTeleport, double distance) {
        return allowTeleport && distance > RECALL_TELEPORT_DISTANCE;
    }

    static boolean shouldSteerRiddenCombat(boolean ridden, double distance, double reach) {
        return ridden && distance > Math.max(1.0D, reach);
    }

    static boolean shouldSteerMovement(double distance, double reach) {
        return distance > Math.max(0.0D, reach);
    }

    static boolean shouldUseMeleeFallback(boolean modAbilityActivated, int targetInvulnerableTicks) {
        return !modAbilityActivated || targetInvulnerableTicks <= 0;
    }

    static boolean shouldActivateModCombatAbility(boolean carryingPassengers) {
        return !carryingPassengers;
    }

    static boolean shouldPreserveAirborneStop(
        boolean wasFlying,
        boolean wasMaintainingFlight,
        boolean onGround
    ) {
        return wasFlying || wasMaintainingFlight || !onGround;
    }

    static boolean shouldHaltTravel(String action) {
        return switch (action) {
            case "fly-to", "recall", "assist-combat", "land" -> true;
            default -> false;
        };
    }

    static boolean commandFailed(int consecutiveFailures) {
        return consecutiveFailures >= MAX_COMMAND_FAILURES;
    }

    static double progress(double startDistance, double currentDistance, double reach) {
        if (currentDistance <= reach) return 1.0D;
        double span = Math.max(1.0D, startDistance - reach);
        return Math.max(0.0D, Math.min(0.95D, 1.0D - (currentDistance - reach) / span));
    }
}
