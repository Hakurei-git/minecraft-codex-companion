package cn.codex.minecraftbridge.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Adds a player-controlled front seat while keeping their companion safely mounted behind them. */
final class DragonSharedRide {
    enum MountResult {
        MOUNTED,
        ALREADY_MOUNTED,
        NOT_READY,
        FAILED;

        boolean successful() {
            return this == MOUNTED || this == ALREADY_MOUNTED;
        }
    }

    private DragonSharedRide() {
    }

    static MountResult mountTogether(
        ServerPlayer owner,
        CodexNpcEntity companion,
        Entity dragon,
        DragonAdapter adapter
    ) {
        if (owner == null || companion == null || dragon == null || adapter == null
            || !companion.isOwnedBy(owner) || !adapter.isOwnedBy(dragon, owner)) {
            return MountResult.FAILED;
        }
        if (isCoRiding(owner, companion, dragon) && dragon.getFirstPassenger() == owner) {
            resetFallDistance(owner, companion, dragon);
            return MountResult.ALREADY_MOUNTED;
        }
        if (!adapter.prepareSharedRide(dragon, owner) || !adapter.isPlayerRideReady(dragon, owner)) {
            return MountResult.NOT_READY;
        }
        boolean ownerWasRiding = owner.getVehicle() == dragon;
        boolean companionWasRiding = companion.getVehicle() == dragon;

        // Adding a ServerPlayer to an NPC-occupied vehicle puts the player at
        // index zero in vanilla. Force mode is required for dragon mods whose
        // capacity/type check only accepts their original single rider.
        if (owner.getVehicle() != dragon && !owner.startRiding(dragon, true)) {
            return MountResult.FAILED;
        }
        if (companion.getVehicle() != dragon && !companion.startRiding(dragon, true)) {
            restoreRideState(owner, companion, dragon, ownerWasRiding, companionWasRiding);
            return MountResult.FAILED;
        }

        // Defensive fallback for vehicles that changed passenger ordering.
        // Reattachment happens in the same server tick, so the NPC never has
        // an unmounted physics tick in which it could fall.
        if (dragon.getFirstPassenger() != owner) {
            companion.fallDistance = 0.0F;
            companion.stopRiding();
            if (owner.getVehicle() != dragon && !owner.startRiding(dragon, true)) {
                restoreRideState(owner, companion, dragon, ownerWasRiding, companionWasRiding);
                return MountResult.FAILED;
            }
            if (!companion.startRiding(dragon, true)) {
                restoreRideState(owner, companion, dragon, ownerWasRiding, companionWasRiding);
                return MountResult.FAILED;
            }
        }

        if (!isCoRiding(owner, companion, dragon) || dragon.getFirstPassenger() != owner) {
            restoreRideState(owner, companion, dragon, ownerWasRiding, companionWasRiding);
            return MountResult.FAILED;
        }
        resetFallDistance(owner, companion, dragon);
        companion.rememberDragon(dragon);
        return MountResult.MOUNTED;
    }

    static boolean isCoRiding(Player owner, CodexNpcEntity companion, Entity dragon) {
        return owner != null && companion != null && dragon != null
            && owner.getVehicle() == dragon && companion.getVehicle() == dragon;
    }

    static boolean positionRearSeat(CodexNpcEntity companion) {
        Entity dragon = companion.getVehicle();
        if (dragon == null || DragonAdapters.forEntity(dragon) == null) return false;
        Player ownerPassenger = dragon.getPassengers().stream()
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .filter(companion::isOwnedBy)
            .findFirst()
            .orElse(null);
        if (ownerPassenger == null || dragon.getFirstPassenger() != ownerPassenger) return false;

        DragonSeatSharingPolicy.SeatOffset offset = DragonSeatSharingPolicy.rearSeatOffset(
            dragon.getYRot(), dragon.getBbWidth(), dragon.getBbHeight()
        );
        companion.setPos(
            ownerPassenger.getX() + offset.x(),
            ownerPassenger.getY() + offset.y(),
            ownerPassenger.getZ() + offset.z()
        );
        companion.setYRot(dragon.getYRot());
        companion.yBodyRot = dragon.getYRot();
        resetFallDistance(ownerPassenger, companion, dragon);
        return true;
    }

    private static void resetFallDistance(Entity owner, Entity companion, Entity dragon) {
        owner.fallDistance = 0.0F;
        companion.fallDistance = 0.0F;
        dragon.fallDistance = 0.0F;
    }

    private static void restoreRideState(
        ServerPlayer owner,
        CodexNpcEntity companion,
        Entity dragon,
        boolean ownerWasRiding,
        boolean companionWasRiding
    ) {
        if (!ownerWasRiding && owner.getVehicle() == dragon) owner.stopRiding();
        if (!companionWasRiding && companion.getVehicle() == dragon) companion.stopRiding();
        if (ownerWasRiding && owner.getVehicle() != dragon) owner.startRiding(dragon, true);
        if (companionWasRiding && companion.getVehicle() != dragon) companion.startRiding(dragon, true);
        resetFallDistance(owner, companion, dragon);
    }
}
