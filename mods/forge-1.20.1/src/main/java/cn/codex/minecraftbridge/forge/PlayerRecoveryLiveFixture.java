package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Test-only player recovery used after a real acceptance-test respawn. */
final class PlayerRecoveryLiveFixture {
    private static final int[][] OFFSETS = {
        {0, 5}, {5, 0}, {0, -5}, {-5, 0},
        {4, 4}, {4, -4}, {-4, 4}, {-4, -4}
    };

    private PlayerRecoveryLiveFixture() {
    }

    static void moveNearNpc(ServerPlayer player, CodexNpcEntity npc) {
        if (npc == null || npc.isRemoved() || !npc.isAlive()) {
            throw new IllegalStateException("Test player recovery requires a living in-world NPC");
        }
        if (npc.level() != player.level()) {
            throw new IllegalStateException("Test player recovery requires player and NPC in the same dimension");
        }
        Vec3 destination = safeDestination(player, npc);
        player.connection.teleport(
            destination.x,
            destination.y,
            destination.z,
            player.getYRot(),
            player.getXRot()
        );
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        int distanceMilli = (int) Math.round(player.distanceTo(npc) * 1_000.0D);
        npc.setNextLiveFixtureAckStatus("view-npc:npc applied distanceMilli=" + distanceMilli);
    }

    private static Vec3 safeDestination(ServerPlayer player, CodexNpcEntity npc) {
        for (int[] offset : OFFSETS) {
            BlockPos anchor = npc.blockPosition().offset(offset[0], 0, offset[1]);
            Vec3 candidate = NpcManager.recallPosition(NpcManager.safePosition(player.serverLevel(), anchor));
            double distance = candidate.distanceTo(npc.position());
            if (distance < 3.0D || distance > 8.0D) continue;
            AABB moved = player.getBoundingBox().move(candidate.subtract(player.position()));
            if (player.serverLevel().noCollision(player, moved)) return candidate;
        }
        throw new IllegalStateException("No safe test-only player recovery position was found near the NPC");
    }
}
