package cn.codex.minecraftbridge.forge;

import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Ephemeral control leases used while an NPC drives a player-mounted dragon.
 * Nothing is persisted. A short position handshake drops stale client packets
 * before normal player vehicle input resumes after NPC control ends.
 */
public final class DragonAutopilotControl {
    private static final double RELEASE_ACK_DISTANCE = 0.05D;
    private static final int BEGIN_CALLED = 1;
    private static final int BEGIN_ACCEPTED = 1 << 1;
    private static final int END_CALLED = 1 << 2;
    private static final int INVALIDATED = 1 << 3;
    private static final int VEHICLE_PACKET_SEEN = 1 << 4;
    private static final ConcurrentMap<UUID, Lease> LEASES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Integer> DIAGNOSTICS = new ConcurrentHashMap<>();

    private DragonAutopilotControl() {
    }

    static boolean begin(Entity dragon, ServerPlayer owner) {
        if (owner == null) return false;
        mark(owner, BEGIN_CALLED);
        if (dragon == null) return false;
        UUID ownerId = owner.getUUID();
        UUID dragonId = dragon.getUUID();
        Lease current = LEASES.get(ownerId);
        if (current != null && current.dragonId().equals(dragonId)
            && owner.getRootVehicle() == dragon && dragon.isAlive() && owner.isAlive()) {
            if (current.releasing()) LEASES.put(ownerId, Lease.active(ownerId, dragonId));
            mark(owner, BEGIN_ACCEPTED);
            return true;
        }

        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        boolean owned = adapter != null && adapter.isOwnedBy(dragon, owner);
        if (!DragonAutopilotPolicy.canBegin(
            dragon.isAlive(), owner.isAlive(), owner.getRootVehicle() == dragon, owned
        )) return false;

        LEASES.put(ownerId, Lease.active(ownerId, dragonId));
        mark(owner, BEGIN_ACCEPTED);
        return true;
    }

    static void end(Entity dragon, ServerPlayer owner) {
        if (owner == null) return;
        mark(owner, END_CALLED);
        UUID ownerId = owner.getUUID();
        if (dragon == null) {
            LEASES.remove(ownerId);
            return;
        }
        UUID dragonId = dragon.getUUID();
        LEASES.computeIfPresent(ownerId, (ignored, lease) ->
            lease.dragonId().equals(dragonId)
                ? Lease.releasing(
                    ownerId,
                    dragonId,
                    owner.tickCount + DragonAutopilotPolicy.RELEASE_STABILIZE_TICKS,
                    owner.tickCount + DragonAutopilotPolicy.RELEASE_TIMEOUT_TICKS,
                    dragon
                )
                : lease
        );
    }

    /** Completes an intentional dismount without recording a stale-root invalidation. */
    static void finishDismount(Entity dragon, ServerPlayer owner) {
        if (owner == null) return;
        UUID ownerId = owner.getUUID();
        if (dragon == null) {
            LEASES.remove(ownerId);
            return;
        }
        Lease lease = LEASES.get(ownerId);
        if (lease != null && lease.dragonId().equals(dragon.getUUID())) LEASES.remove(ownerId, lease);
    }

    public static boolean isActive(Entity dragon, ServerPlayer owner) {
        if (dragon == null || owner == null) return false;
        Lease lease = matchingLease(owner, dragon);
        return lease != null && !lease.releasing();
    }

    /** Called before vanilla accepts a client-authored vehicle position. */
    public static boolean shouldSuppressVehicleMove(
        ServerPlayer owner,
        double packetX,
        double packetY,
        double packetZ
    ) {
        if (owner == null) return false;
        Lease lease = LEASES.get(owner.getUUID());
        if (lease == null) return false;
        Entity root = owner.getRootVehicle();
        if (root == owner || !matches(lease, owner, root)) {
            invalidate(owner, lease);
            return false;
        }
        if (!root.isAlive() || !owner.isAlive()) {
            invalidate(owner, lease);
            return false;
        }
        mark(owner, VEHICLE_PACKET_SEEN);
        if (!lease.releasing()) return true;

        boolean acknowledged = DragonAutopilotPolicy.releaseAcknowledged(
            lease.expectedX(), lease.expectedY(), lease.expectedZ(),
            packetX, packetY, packetZ, RELEASE_ACK_DISTANCE
        );
        if (DragonAutopilotPolicy.releaseReady(
            acknowledged,
            owner.tickCount,
            lease.releaseNotBeforeTick(),
            lease.releaseAfterTick()
        )) {
            DragonAdapter adapter = DragonAdapters.forEntity(root);
            if (adapter != null) adapter.haltTravel(root, owner);
            root.setPos(lease.expectedX(), lease.expectedY(), lease.expectedZ());
            root.setDeltaMovement(0.0D, 0.0D, 0.0D);
            root.hasImpulse = true;
            owner.connection.send(new ClientboundMoveVehiclePacket(root));
            // Suppress the packet that completes the handshake as well. The
            // next client packet is the first one allowed to resume manual control.
            LEASES.remove(owner.getUUID(), lease);
        }
        return true;
    }

    private static boolean shouldMaintainVehicleBaseline(ServerPlayer owner) {
        if (owner == null) return false;
        Lease lease = LEASES.get(owner.getUUID());
        if (lease == null) return false;
        Entity root = owner.getRootVehicle();
        if (root == owner || !matches(lease, owner, root) || !root.isAlive() || !owner.isAlive()) {
            invalidate(owner, lease);
            return false;
        }
        return true;
    }

    /** Returns the leased root vehicle for server-thread baseline maintenance. */
    public static Entity activeVehicle(ServerPlayer owner) {
        if (!shouldMaintainVehicleBaseline(owner)) return null;
        Entity vehicle = owner.getRootVehicle();
        Lease lease = LEASES.get(owner.getUUID());
        if (lease != null && lease.releasing()) {
            DragonAdapter adapter = DragonAdapters.forEntity(vehicle);
            if (adapter != null) adapter.haltTravel(vehicle, owner);
            vehicle.setPos(lease.expectedX(), lease.expectedY(), lease.expectedZ());
            vehicle.setDeltaMovement(0.0D, 0.0D, 0.0D);
            vehicle.hasImpulse = true;
            vehicle.fallDistance = 0.0F;
            for (Entity passenger : vehicle.getPassengers()) passenger.fallDistance = 0.0F;
            owner.connection.send(new ClientboundMoveVehiclePacket(vehicle));
            // A fully reset client may stop emitting vehicle packets. Complete
            // the release from the server after the stabilization window so a
            // later dismount cannot invalidate an otherwise healthy lease.
            if (DragonAutopilotPolicy.stabilizationComplete(
                owner.tickCount, lease.releaseNotBeforeTick()
            )) LEASES.remove(owner.getUUID(), lease);
        }
        return vehicle;
    }

    /** Corrects the controlling client's vehicle after each server movement step. */
    static void sync(Entity dragon, ServerPlayer owner) {
        if (matchingLease(owner, dragon) == null) return;
        owner.connection.send(new ClientboundMoveVehiclePacket(dragon));
    }

    static int activeLeaseCount() {
        return LEASES.size();
    }

    static void resetDiagnostics(ServerPlayer owner) {
        if (owner != null) DIAGNOSTICS.remove(owner.getUUID());
    }

    static Diagnostics diagnostics(ServerPlayer owner) {
        int flags = owner == null ? 0 : DIAGNOSTICS.getOrDefault(owner.getUUID(), 0);
        return new Diagnostics(
            (flags & BEGIN_CALLED) != 0,
            (flags & BEGIN_ACCEPTED) != 0,
            (flags & END_CALLED) != 0,
            (flags & INVALIDATED) != 0,
            (flags & VEHICLE_PACKET_SEEN) != 0
        );
    }

    private static Lease matchingLease(ServerPlayer owner, Entity dragon) {
        Lease lease = LEASES.get(owner.getUUID());
        if (lease == null) return null;
        Entity root = owner.getRootVehicle();
        if (!matches(lease, owner, root)) {
            invalidate(owner, lease);
            return null;
        }
        if (root != dragon) return null;
        if (!dragon.isAlive() || !owner.isAlive()) {
            invalidate(owner, lease);
            return null;
        }
        return lease;
    }

    private static void mark(ServerPlayer owner, int flag) {
        DIAGNOSTICS.merge(owner.getUUID(), flag, (current, added) -> current | added);
    }

    private static void invalidate(ServerPlayer owner, Lease lease) {
        if (LEASES.remove(owner.getUUID(), lease)) mark(owner, INVALIDATED);
    }

    private static boolean matches(Lease lease, ServerPlayer owner, Entity root) {
        return DragonAutopilotPolicy.matches(
            lease.ownerId(), owner.getUUID(), lease.dragonId(), root.getUUID()
        );
    }

    record Diagnostics(
        boolean beginCalled,
        boolean beginAccepted,
        boolean endCalled,
        boolean invalidated,
        boolean vehiclePacketSeen
    ) {
    }

    private record Lease(
        UUID ownerId,
        UUID dragonId,
        boolean releasing,
        int releaseNotBeforeTick,
        int releaseAfterTick,
        double expectedX,
        double expectedY,
        double expectedZ
    ) {
        private static Lease active(UUID ownerId, UUID dragonId) {
            return new Lease(
                ownerId, dragonId, false, Integer.MAX_VALUE, Integer.MAX_VALUE,
                0.0D, 0.0D, 0.0D
            );
        }

        private static Lease releasing(
            UUID ownerId,
            UUID dragonId,
            int releaseNotBeforeTick,
            int releaseAfterTick,
            Entity dragon
        ) {
            return new Lease(
                ownerId, dragonId, true, releaseNotBeforeTick, releaseAfterTick,
                dragon.getX(), dragon.getY(), dragon.getZ()
            );
        }
    }
}
