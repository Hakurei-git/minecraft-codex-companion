package cn.codex.minecraftbridge.forge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Runtime-only compatibility surface for the two supported dragon mods. */
public interface DragonAdapter {
    String modId();

    boolean supports(Entity entity);

    boolean isFlying(Entity dragon);

    boolean isOwnedBy(Entity dragon, ServerPlayer owner);

    /** Numeric command used by the concrete dragon mod for follow mode. */
    default int followCommand() {
        return 2;
    }

    /** Numeric command used by both supported mods for stay/sit mode. */
    default int stayCommand() {
        return 1;
    }

    boolean setFollow(Entity dragon, ServerPlayer owner);

    boolean setStay(Entity dragon, ServerPlayer owner);

    boolean mount(CodexNpcEntity npc, Entity dragon, ServerPlayer owner);

    /** Prepare a player-owned dragon so the owner and their Codex NPC may take turns using its seat. */
    boolean prepareSharedRide(Entity dragon, ServerPlayer owner);

    boolean isSaddled(Entity dragon);

    boolean isSeatLocked(Entity dragon);

    boolean isPlayerRideReady(Entity dragon, ServerPlayer owner);

    boolean tickMountedFollow(Entity dragon, ServerPlayer owner);

    /** Keep mod-specific flight state active while direct mounted steering owns movement. */
    default void maintainMountedAirborneState(Entity dragon, ServerPlayer owner) {
    }

    boolean flyTo(Entity dragon, Vec3 target, ServerPlayer owner);

    boolean recall(Entity dragon, ServerPlayer owner, boolean allowTeleport);

    boolean assistCombat(Entity dragon, LivingEntity target, ServerPlayer owner);

    /** Release mod-specific travel targets and residual motion after an NPC-controlled action. */
    void haltTravel(Entity dragon, ServerPlayer owner);

    /** Stop at an aerial destination while preserving a stable hover state. */
    default void haltAirborneTravel(Entity dragon, ServerPlayer owner) {
        haltTravel(dragon, owner);
    }

    Vec3 safeLandingTarget(Entity dragon, ServerPlayer owner);

    boolean land(Entity dragon, ServerPlayer owner);

    default void dismount(CodexNpcEntity npc) {
        DragonDismountSafety.dismountCompanion(npc);
    }
}
