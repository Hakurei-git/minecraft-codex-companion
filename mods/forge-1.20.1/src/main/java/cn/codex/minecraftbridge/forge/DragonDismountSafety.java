package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Moves a rider to solid ground after leaving a large or airborne dragon. */
final class DragonDismountSafety {
    private DragonDismountSafety() {
    }

    static void dismountCompanion(CodexNpcEntity companion) {
        Entity dragon = companion.getVehicle();
        companion.fallDistance = 0.0F;
        companion.stopRiding();
        placeOnGround(companion, dragon, -1);
    }

    static void dismountPlayer(ServerPlayer player, Entity dragon) {
        player.fallDistance = 0.0F;
        if (player.getVehicle() == dragon) player.stopRiding();
        placeOnGround(player, dragon, 1);
    }

    private static void placeOnGround(Entity rider, Entity dragon, int side) {
        if (dragon != null && dragon.level() instanceof ServerLevel level) {
            DragonDismountPolicy.Offset offset = DragonDismountPolicy.sideOffset(
                dragon.getYRot(), dragon.getBbWidth(), side
            );
            BlockPos anchor = BlockPos.containing(
                dragon.getX() + offset.x(), dragon.getY(), dragon.getZ() + offset.z()
            );
            BlockPos safe = NpcManager.safePosition(level, anchor);
            double x = safe.getX() + 0.5D;
            double y = safe.getY();
            double z = safe.getZ() + 0.5D;
            if (rider instanceof ServerPlayer player) {
                player.connection.teleport(x, y, z, rider.getYRot(), rider.getXRot());
            } else {
                rider.teleportTo(x, y, z);
            }
        }
        rider.setDeltaMovement(Vec3.ZERO);
        rider.fallDistance = 0.0F;
        rider.setOnGround(true);
        rider.hasImpulse = true;
        if (rider instanceof CodexNpcEntity companion) {
            companion.getNavigation().stop();
            companion.setTarget(null);
            companion.setNoGravity(false);
            companion.protectAfterDragonDismount();
        }
    }
}
