package cn.codex.minecraftbridge.forge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("deprecation")
final class SaintsDragonsAdapter extends ReflectiveDragonAdapter {
    @Override
    public String modId() {
        return "saintsdragons";
    }

    @Override
    public boolean supports(Entity entity) {
        return modId().equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace());
    }

    @Override
    public int followCommand() {
        // Saints Dragons uses 0=follow, 1=sit, 2=wander. Book of Dragons
        // uses 2=follow, so this cannot inherit the reflective default.
        return 0;
    }

    @Override
    public boolean tickMountedFollow(Entity dragon, ServerPlayer owner) {
        if (!super.tickMountedFollow(dragon, owner)) return false;
        boolean dragonFlying = asBoolean(invoke(dragon, "isFlying"));
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
            invokeVoid(dragon, "startTakeoffSequence", 0.9D, 24);
        }
        applyObstacleLift(dragon, route);
        invokeVoid(dragon, "trackAiFlightTarget", target, 1.35D);
        invokeVoid(dragon, "moveAiFlightTo", target, 1.35D);
        return true;
    }

    @Override
    public boolean prepareSharedRide(Entity dragon, ServerPlayer owner) {
        if (!super.prepareSharedRide(dragon, owner)) return false;
        invokeVoid(dragon, "clearRiderControlLock");
        return true;
    }

    @Override
    public boolean isPlayerRideReady(Entity dragon, ServerPlayer owner) {
        boolean adult = !(dragon instanceof AgeableMob ageable) || !ageable.isBaby();
        Object happiness = invoke(dragon, "getHappiness");
        boolean happy = !(happiness instanceof Number number) || number.intValue() > 30;
        return super.isPlayerRideReady(dragon, owner) && adult && happy;
    }

    @Override
    public void maintainMountedAirborneState(Entity dragon, ServerPlayer owner) {
        if (isOwnedBy(dragon, owner) && dragon.level() == owner.level()) ensureFlightState(dragon);
    }

    @Override
    public boolean land(Entity dragon, ServerPlayer owner) {
        if (!super.land(dragon, owner)) return false;
        Vec3 target = safeLandingTarget(dragon, owner);
        if (target == null) return false;
        invokeVoid(dragon, "setGoingUp", false);
        invokeVoid(dragon, "setGoingDown", true);
        invokeVoid(dragon, "setLanding", true);
        invokeVoid(dragon, "moveAiFlightTo", target, 0.9D);
        return true;
    }

    @Override
    public boolean flyTo(Entity dragon, Vec3 target, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner) || dragon.level() != owner.level()) return false;
        ensureFlightState(dragon);
        return invokeVoid(dragon, "moveAiFlightTo", target, 1.35D)
            || invokeVoid(dragon, "trackAiFlightTarget", target, 1.35D);
    }

    private void ensureFlightState(Entity dragon) {
        if (asBoolean(invoke(dragon, "isFlying"))) return;
        if (dragon.onGround()) {
            invokeVoid(dragon, "startTakeoffSequence", 0.9D, 24);
            return;
        }
        // Saints only accepts its takeoff sequence while grounded. A ridden
        // dragon staged or recovered in mid-air can otherwise keep falling
        // while direct autopilot movement tries to climb, producing a stable
        // vertical deadlock. Its public flight setters are the supported way
        // to resume the already-airborne state.
        dragon.setOnGround(false);
        invokeVoid(dragon, "setLanding", false);
        invokeVoid(dragon, "setTakeoff", false);
        invokeVoid(dragon, "setGoingDown", false);
        invokeVoid(dragon, "setFlying", true);
        invokeVoid(dragon, "setHovering", true);
        invokeVoid(dragon, "switchToAirNavigation");
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
    }

    @Override
    public void haltTravel(Entity dragon, ServerPlayer owner) {
        haltTravel(dragon, owner, false);
    }

    @Override
    public void haltAirborneTravel(Entity dragon, ServerPlayer owner) {
        haltTravel(dragon, owner, true);
    }

    private void haltTravel(Entity dragon, ServerPlayer owner, boolean forceAirborne) {
        boolean wasFlying = asBoolean(invoke(dragon, "isFlying"));
        boolean wasMaintainingFlight = asBoolean(invoke(dragon, "isTakeoff"))
            || asBoolean(invoke(dragon, "isHovering"));
        boolean preserveAirborne = forceAirborne || DragonActionPolicy.shouldPreserveAirborneStop(
            wasFlying, wasMaintainingFlight, dragon.onGround()
        );
        Object controller = readField(dragon, "asyncAirController");
        if (controller != null) invokeVoid(controller, "clearAllWaypoints");
        super.haltTravel(dragon, owner);
        if (preserveAirborne && releaseTransientGroundContact(dragon)) {
            dragon.setOnGround(false);
            invokeVoid(dragon, "setLanding", false);
            invokeVoid(dragon, "setTakeoff", false);
            invokeVoid(dragon, "setGoingUp", false);
            invokeVoid(dragon, "setGoingDown", false);
            invokeVoid(dragon, "setFlying", true);
            invokeVoid(dragon, "setHovering", true);
            dragon.setDeltaMovement(0.0D, 0.02D, 0.0D);
            dragon.hasImpulse = true;
        }
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean bool && bool;
    }
}
