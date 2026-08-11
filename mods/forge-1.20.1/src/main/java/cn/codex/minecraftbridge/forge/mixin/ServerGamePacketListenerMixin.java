package cn.codex.minecraftbridge.forge.mixin;

import cn.codex.minecraftbridge.forge.DragonAutopilotControl;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps client vehicle packets from undoing an active NPC flight step. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow public ServerPlayer player;
    @Shadow private double vehicleFirstGoodX;
    @Shadow private double vehicleFirstGoodY;
    @Shadow private double vehicleFirstGoodZ;
    @Shadow private double vehicleLastGoodX;
    @Shadow private double vehicleLastGoodY;
    @Shadow private double vehicleLastGoodZ;

    @Inject(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void codex$suppressPlayerVehicleInput(
        ServerboundMoveVehiclePacket packet,
        CallbackInfo callback
    ) {
        if (DragonAutopilotControl.shouldSuppressVehicleMove(
            this.player, packet.getX(), packet.getY(), packet.getZ()
        )) callback.cancel();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void codex$maintainVehicleMovementBaseline(CallbackInfo callback) {
        Entity vehicle = DragonAutopilotControl.activeVehicle(this.player);
        if (vehicle == null) return;
        this.vehicleFirstGoodX = vehicle.getX();
        this.vehicleFirstGoodY = vehicle.getY();
        this.vehicleFirstGoodZ = vehicle.getZ();
        this.vehicleLastGoodX = vehicle.getX();
        this.vehicleLastGoodY = vehicle.getY();
        this.vehicleLastGoodZ = vehicle.getZ();
    }
}
