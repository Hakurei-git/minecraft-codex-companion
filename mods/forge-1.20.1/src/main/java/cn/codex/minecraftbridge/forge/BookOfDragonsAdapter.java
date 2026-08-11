package cn.codex.minecraftbridge.forge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("deprecation")
final class BookOfDragonsAdapter extends ReflectiveDragonAdapter {
    @Override
    public String modId() {
        return "bookofdragons";
    }

    @Override
    public boolean supports(Entity entity) {
        return modId().equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace());
    }

    @Override
    public boolean tickMountedFollow(Entity dragon, ServerPlayer owner) {
        if (!super.tickMountedFollow(dragon, owner)) return false;
        boolean dragonFlying = Boolean.TRUE.equals(invoke(dragon, "isFlying"));
        boolean mountedDragonFlying = ownerMountedDragonIsFlying(owner);
        double heightGap = owner.getY() - dragon.getY();
        double distance = dragon.distanceTo(owner);
        boolean ownerAerial = DragonFollowPolicy.useAerialTarget(
            owner.getAbilities().flying,
            owner.isFallFlying(),
            mountedDragonFlying,
            heightGap
        );
        FollowRoute route = planMountedFollow(dragon, owner, ownerAerial);
        DragonFollowPolicy.Decision decision = DragonFollowPolicy.decide(
            dragonFlying,
            owner.getAbilities().flying,
            owner.isFallFlying(),
            mountedDragonFlying,
            owner.onGround(),
            heightGap,
            distance,
            route.routeObstructed(),
            route.safeToLand()
        );
        if (decision == DragonFollowPolicy.Decision.LAND) {
            land(dragon, owner);
            return true;
        }
        Vec3 target = route.target();
        if (decision == DragonFollowPolicy.Decision.TAKE_OFF || route.routeObstructed()) {
            invokeVoid(dragon, "executeTakeoff", 0.85F);
        }
        applyObstacleLift(dragon, route);
        Object movement = invoke(dragon, "getAIMovement");
        if (movement != null) invokeVoid(movement, "setWaypoint", target, 1.35D);
        return true;
    }

    @Override
    public boolean isPlayerRideReady(Entity dragon, ServerPlayer owner) {
        Object growth = invoke(dragon, "getGrowthStage");
        boolean adult = !(growth instanceof Number number) || number.intValue() >= 2;
        return super.isPlayerRideReady(dragon, owner)
            && adult
            && Boolean.TRUE.equals(invoke(dragon, "isTamingRitualCompleted"));
    }

    @Override
    public boolean land(Entity dragon, ServerPlayer owner) {
        if (!super.land(dragon, owner)) return false;
        Vec3 target = safeLandingTarget(dragon, owner);
        if (target == null) return false;
        invokeVoid(dragon, "setGoingUp", false);
        invokeVoid(dragon, "setGoingDown", true);
        Object movement = invoke(dragon, "getAIMovement");
        if (movement != null) invokeVoid(movement, "setWaypoint", target, 0.9D);
        return true;
    }

    @Override
    public boolean flyTo(Entity dragon, Vec3 target, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner) || dragon.level() != owner.level()) return false;
        if (!Boolean.TRUE.equals(invoke(dragon, "isFlying"))) invokeVoid(dragon, "executeTakeoff", 0.85F);
        Object movement = invoke(dragon, "getAIMovement");
        return movement != null && invokeVoid(movement, "setWaypoint", target, 1.35D);
    }

    @Override
    public void haltTravel(Entity dragon, ServerPlayer owner) {
        Object movement = invoke(dragon, "getAIMovement");
        boolean wasMaintainingFlight = movement != null
            && Boolean.TRUE.equals(invoke(movement, "isMaintainingFlight"));
        boolean wasFlying = Boolean.TRUE.equals(invoke(dragon, "isFlying"));
        boolean preserveAirborne = DragonActionPolicy.shouldPreserveAirborneStop(
            wasFlying, wasMaintainingFlight, dragon.onGround()
        );
        if (movement != null) invokeVoid(movement, "clearAllWaypoints");
        BookDragonInputReset.reset(dragon);
        super.haltTravel(dragon, owner);
        if (preserveAirborne && releaseTransientGroundContact(dragon)) {
            dragon.setOnGround(false);
            invokeVoid(dragon, "setIsOnSolidGround", false);
            invokeEnumVoid(dragon, "setTransportMode", "AIRBORNE");
            invokeEnumVoid(dragon, "setAirStance", "HOVERING");
            Object stateContext = invoke(dragon, "getStateContext");
            invokeEnumVoid(stateContext, "setTransportMode", "AIRBORNE");
            invokeEnumVoid(stateContext, "setAirStance", "HOVERING");
            dragon.setDeltaMovement(0.0D, 0.02D, 0.0D);
            dragon.hasImpulse = true;
        }
        if (owner != null) CodexNetwork.sendBookDragonInputReset(owner, dragon);
    }
}
