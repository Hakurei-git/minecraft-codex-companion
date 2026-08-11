package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

abstract class ReflectiveDragonAdapter implements DragonAdapter {
    private static final String NAV_PREVIOUS_X = "CodexDragonNavPreviousX";
    private static final String NAV_PREVIOUS_Y = "CodexDragonNavPreviousY";
    private static final String NAV_PREVIOUS_Z = "CodexDragonNavPreviousZ";
    private static final String NAV_STUCK_TICKS = "CodexDragonNavStuckTicks";
    private static final Map<Entity, LandingCache> LANDING_CACHE = new WeakHashMap<>();

    private record LandingCache(int tick, BlockPos ownerPosition, Vec3 target) {
    }

    protected record FollowRoute(
        Vec3 target,
        boolean routeObstructed,
        boolean safeToLand,
        boolean collisionAhead,
        boolean teleported,
        double clearance
    ) {
    }

    protected static boolean ownerMountedDragonIsFlying(ServerPlayer owner) {
        Entity vehicle = owner.getVehicle();
        if (vehicle == null) return false;
        DragonAdapter adapter = DragonAdapters.forEntity(vehicle);
        return adapter != null && adapter.isFlying(vehicle);
    }

    @Override
    public boolean isFlying(Entity dragon) {
        return Boolean.TRUE.equals(invoke(dragon, "isFlying"));
    }

    @Override
    public boolean isOwnedBy(Entity dragon, ServerPlayer owner) {
        if (dragon instanceof TamableAnimal tameable) {
            return DragonOwnershipPolicy.isOwnedBy(
                tameable.isTame(), tameable.getOwnerUUID(), owner.getUUID()
            );
        }
        Object ownerId = invoke(dragon, "getOwnerUUID");
        if (!(ownerId instanceof UUID)) ownerId = invoke(dragon, "getOwnerUuid");
        return owner.getUUID().equals(ownerId);
    }

    @Override
    public boolean setFollow(Entity dragon, ServerPlayer owner) {
        return isOwnedBy(dragon, owner) && invokeVoid(dragon, "setCommand", followCommand());
    }

    @Override
    public boolean setStay(Entity dragon, ServerPlayer owner) {
        return isOwnedBy(dragon, owner) && invokeVoid(dragon, "setCommand", stayCommand());
    }

    @Override
    public boolean mount(CodexNpcEntity npc, Entity dragon, ServerPlayer owner) {
        if (!prepareSharedRide(dragon, owner) || !isPlayerRideReady(dragon, owner)) return false;
        invokeVoid(dragon, "setCommand", followCommand());
        return npc.startRiding(dragon, true);
    }

    @Override
    public boolean prepareSharedRide(Entity dragon, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner)) return false;
        invokeVoid(dragon, "setSeatLocked", false);
        invokeVoid(dragon, "prepareForMounting");
        return true;
    }

    @Override
    public boolean isSaddled(Entity dragon) {
        Object value = invoke(dragon, "isSaddled");
        return !(value instanceof Boolean) || Boolean.TRUE.equals(value);
    }

    @Override
    public boolean isSeatLocked(Entity dragon) {
        return Boolean.TRUE.equals(invoke(dragon, "isSeatLocked"));
    }

    @Override
    public boolean isPlayerRideReady(Entity dragon, ServerPlayer owner) {
        return isOwnedBy(dragon, owner) && isSaddled(dragon) && !isSeatLocked(dragon);
    }

    @Override
    public boolean tickMountedFollow(Entity dragon, ServerPlayer owner) {
        if (dragon.level() != owner.level()) return false;
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
        invokeVoid(dragon, "setCommand", followCommand());
        if (dragon instanceof Mob mob) {
            mob.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        }
        return true;
    }

    protected FollowRoute planMountedFollow(Entity dragon, ServerPlayer owner, boolean ownerAerial) {
        Vec3 start = dragon.position();
        double deltaX = owner.getX() - start.x;
        double deltaZ = owner.getZ() - start.z;
        double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double width = Math.max(1.0D, dragon.getBbWidth());
        double height = Math.max(1.0D, dragon.getBbHeight());
        double clearance = DragonTerrainAvoidancePolicy.clearance(width, height);
        Vec3 landingTarget = safeLandingTarget(dragon, owner);
        boolean landingSpaceClear = landingTarget != null;
        double landingHorizontalDistance = landingTarget == null ? Double.MAX_VALUE : Math.sqrt(
            square(landingTarget.x - start.x) + square(landingTarget.z - start.z)
        );
        boolean nearLanding = landingSpaceClear
            && landingHorizontalDistance <= DragonTerrainAvoidancePolicy.landingRadius(width);

        if (!(dragon.level() instanceof ServerLevel level)) {
            Vec3 fallback = owner.position().add(0.0D, ownerAerial ? 4.0D : 1.0D, 0.0D);
            return new FollowRoute(fallback, false, nearLanding, false, false, clearance);
        }

        double requestedAerialY = owner.getY() + Math.max(4.0D, Math.min(12.0D, clearance * 0.8D));
        double highestObstacle = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING,
            (int) Math.floor(start.x),
            (int) Math.floor(start.z)
        );
        int samples = DragonTerrainAvoidancePolicy.routeSamples(horizontalDistance, width);
        boolean checkAerialClearance = ownerAerial
            || isFlying(dragon)
            || horizontalDistance > DragonTerrainAvoidancePolicy.landingRadius(width)
            || !landingSpaceClear;
        boolean routeObstructed = dragon.horizontalCollision;
        boolean collisionAhead = dragon.horizontalCollision;
        Vec3 requested = landingTarget == null
            ? owner.position().add(0.0D, 1.0D, 0.0D)
            : landingTarget;
        AABB originBox = dragon.getBoundingBox()
            .inflate(Math.max(0.25D, width * 0.15D), 0.0D, Math.max(0.25D, width * 0.15D))
            .expandTowards(0.0D, Math.max(0.25D, height * 0.15D), 0.0D);

        for (int sample = 1; sample <= samples; sample++) {
            double alpha = sample / (double) samples;
            double x = lerp(alpha, start.x, requested.x);
            double z = lerp(alpha, start.z, requested.z);
            double terrainTop = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING,
                (int) Math.floor(x),
                (int) Math.floor(z)
            );
            highestObstacle = Math.max(highestObstacle, terrainTop);
            double plannedY = checkAerialClearance
                ? lerp(alpha, start.y, requestedAerialY)
                : lerp(alpha, start.y, requested.y);
            AABB sampledBox = originBox.move(x - start.x, plannedY - start.y, z - start.z);
            boolean sampledCollision = !level.noCollision(dragon, sampledBox);
            collisionAhead |= sampledCollision;
            if (checkAerialClearance && DragonTerrainAvoidancePolicy.blocksAerialRoute(
                terrainTop,
                plannedY,
                clearance,
                sampledCollision
            )) routeObstructed = true;
            if (!checkAerialClearance && sampledCollision) routeObstructed = true;
        }

        double maximumBottomY = level.getMaxBuildHeight() - height - 2.0D;
        double safeY = DragonTerrainAvoidancePolicy.safeAltitude(
            requestedAerialY,
            highestObstacle,
            clearance,
            maximumBottomY
        );
        boolean useDetour = DragonTerrainAvoidancePolicy.shouldUseDetour(
            ownerAerial,
            isFlying(dragon),
            routeObstructed,
            landingSpaceClear,
            horizontalDistance,
            width
        );
        Vec3 target = requested;
        if (useDetour) {
            if (start.y + 1.0D < safeY && horizontalDistance > 0.01D) {
                double initialHorizontalStep = Math.min(4.0D, Math.max(1.0D, width * 0.3D));
                target = new Vec3(
                    start.x + deltaX / horizontalDistance * initialHorizontalStep,
                    safeY,
                    start.z + deltaZ / horizontalDistance * initialHorizontalStep
                );
            } else {
                target = new Vec3(requested.x, safeY, requested.z);
            }
        }

        int stuckTicks = updateStuckTicks(dragon, routeObstructed, owner.distanceTo(dragon));
        boolean teleported = DragonTerrainAvoidancePolicy.shouldTeleportStalledDragon(
            owner.hasPermissions(2),
            routeObstructed,
            owner.distanceTo(dragon),
            stuckTicks
        );
        if (teleported) {
            Vec3 rescue = safeTeleportTarget(level, dragon, owner, clearance);
            dragon.teleportTo(rescue.x, rescue.y, rescue.z);
            dragon.setDeltaMovement(Vec3.ZERO);
            dragon.fallDistance = 0.0F;
            resetStuckTicks(dragon);
            target = rescue;
        }
        return new FollowRoute(target, routeObstructed, nearLanding, collisionAhead, teleported, clearance);
    }

    protected void applyObstacleLift(Entity dragon, FollowRoute route) {
        if (!route.routeObstructed()) return;
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
        invokeVoid(dragon, "setLanding", false);
        invokeVoid(dragon, "setGoingDown", false);
        invokeVoid(dragon, "setGoingUp", true);
        if (route.collisionAhead()) {
            Vec3 movement = dragon.getDeltaMovement();
            dragon.setDeltaMovement(movement.x * 0.75D, Math.max(0.18D, movement.y), movement.z * 0.75D);
            dragon.hasImpulse = true;
        }
    }

    @Override
    public Vec3 safeLandingTarget(Entity dragon, ServerPlayer owner) {
        if (!(dragon.level() instanceof ServerLevel level)) return owner.position();
        BlockPos ownerPosition = owner.blockPosition();
        LandingCache cached = LANDING_CACHE.get(dragon);
        if (cached != null
            && dragon.tickCount - cached.tick() < 10
            && cached.ownerPosition().distSqr(ownerPosition) <= 4.0D) return cached.target();
        double width = Math.max(1.0D, dragon.getBbWidth());
        double height = Math.max(1.0D, dragon.getBbHeight());
        double horizontalMargin = Math.max(0.35D, Math.min(1.5D, width * 0.15D));
        double verticalMargin = Math.max(0.5D, Math.min(2.0D, height * 0.15D));
        Vec3 start = dragon.position();
        Vec3 currentTarget = landingCandidate(
            level, dragon, start, horizontalMargin, verticalMargin,
            start.x, start.z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
        );
        if (currentTarget != null) {
            LANDING_CACHE.put(dragon, new LandingCache(
                dragon.tickCount, ownerPosition.immutable(), currentTarget
            ));
            return currentTarget;
        }
        for (int ring = 0; ring < DragonTerrainAvoidancePolicy.LANDING_SEARCH_RINGS; ring++) {
            double radius = DragonTerrainAvoidancePolicy.landingSearchRadius(width, ring);
            for (int direction = 0; direction < DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS; direction++) {
                double angle = Math.PI * 2.0D * direction
                    / DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS;
                double x = owner.getX() + Math.cos(angle) * radius;
                double z = owner.getZ() + Math.sin(angle) * radius;
                Vec3 target = landingCandidate(
                    level, dragon, start, horizontalMargin, verticalMargin,
                    x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
                );
                if (target != null) {
                    LANDING_CACHE.put(dragon, new LandingCache(dragon.tickCount, ownerPosition.immutable(), target));
                    return target;
                }
            }
        }

        // A dense forest may have no body-sized clearing at ground level even
        // across the full search radius. In that case a dragon can safely
        // perch on the top collision surface of the canopy instead of hovering
        // forever. The candidate still has to pass the full expanded-body
        // collision check, so this does not place riders inside leaves.
        currentTarget = canopyLandingCandidate(
            level, dragon, start, horizontalMargin, verticalMargin,
            start.x, start.z
        );
        if (currentTarget != null) {
            LANDING_CACHE.put(dragon, new LandingCache(
                dragon.tickCount, ownerPosition.immutable(), currentTarget
            ));
            return currentTarget;
        }
        for (int ring = 0; ring < DragonTerrainAvoidancePolicy.LANDING_SEARCH_RINGS; ring++) {
            double radius = DragonTerrainAvoidancePolicy.landingSearchRadius(width, ring);
            for (int direction = 0; direction < DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS; direction++) {
                double angle = Math.PI * 2.0D * direction
                    / DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS;
                double x = owner.getX() + Math.cos(angle) * radius;
                double z = owner.getZ() + Math.sin(angle) * radius;
                Vec3 target = canopyLandingCandidate(
                    level, dragon, start, horizontalMargin, verticalMargin,
                    x, z
                );
                if (target != null) {
                    LANDING_CACHE.put(dragon, new LandingCache(dragon.tickCount, ownerPosition.immutable(), target));
                    return target;
                }
            }
        }
        LANDING_CACHE.put(dragon, new LandingCache(dragon.tickCount, ownerPosition.immutable(), null));
        return null;
    }

    private Vec3 landingCandidate(
        ServerLevel level,
        Entity dragon,
        Vec3 start,
        double horizontalMargin,
        double verticalMargin,
        double x,
        double z,
        Heightmap.Types heightmap
    ) {
        double y = level.getHeight(
            heightmap,
            (int) Math.floor(x),
            (int) Math.floor(z)
        );
        BlockPos feet = BlockPos.containing(x, y, z);
        BlockPos floor = feet.below();
        if (!level.getFluidState(feet).isEmpty()
            || !level.getFluidState(floor).isEmpty()
            || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) return null;
        AABB candidate = dragon.getBoundingBox()
            .move(x - start.x, y - start.y, z - start.z)
            .inflate(horizontalMargin, 0.0D, horizontalMargin)
            .expandTowards(0.0D, verticalMargin, 0.0D);
        Vec3 target = new Vec3(x, y, z);
        if (!level.noCollision(dragon, candidate)) return null;
        // A body-sized space on the forest floor is not a usable landing site
        // when the vertical descent column is still capped by leaves. Without
        // this check, a co-ridden dragon repeatedly targets the clear floor
        // directly below the canopy, then every downward step collides and the
        // landing task remains motionless until timeout.
        return verticalLandingCorridorClear(
            level, dragon, start, target, horizontalMargin, verticalMargin
        ) ? target : null;
    }

    private boolean verticalLandingCorridorClear(
        ServerLevel level,
        Entity dragon,
        Vec3 start,
        Vec3 target,
        double horizontalMargin,
        double verticalMargin
    ) {
        double topY = Math.max(start.y, target.y);
        double verticalDistance = Math.max(0.0D, topY - target.y);
        int samples = DragonTerrainAvoidancePolicy.landingCorridorSamples(
            verticalDistance, dragon.getBbHeight()
        );
        for (int sample = 1; sample <= samples; sample++) {
            double alpha = sample / (double) samples;
            double y = target.y + verticalDistance * alpha;
            AABB corridorBox = dragon.getBoundingBox()
                .move(target.x - start.x, y - start.y, target.z - start.z)
                .inflate(horizontalMargin, 0.0D, horizontalMargin)
                .expandTowards(0.0D, verticalMargin, 0.0D);
            if (!level.noCollision(dragon, corridorBox)) return false;
        }
        return true;
    }

    private Vec3 canopyLandingCandidate(
        ServerLevel level,
        Entity dragon,
        Vec3 start,
        double horizontalMargin,
        double verticalMargin,
        double x,
        double z
    ) {
        // Use the highest collision surface across the dragon's complete
        // footprint, not just the centre column. This makes uneven treetops a
        // stable perch instead of letting a neighbouring leaf intersect the
        // body-sized landing box.
        double footprintRadius = dragon.getBbWidth() * 0.5D + horizontalMargin + 0.5D;
        int minX = (int) Math.floor(x - footprintRadius);
        int maxX = (int) Math.floor(x + footprintRadius);
        int minZ = (int) Math.floor(z - footprintRadius);
        int maxZ = (int) Math.floor(z + footprintRadius);
        double highestSurface = Double.NEGATIVE_INFINITY;
        for (int columnX = minX; columnX <= maxX; columnX++) {
            for (int columnZ = minZ; columnZ <= maxZ; columnZ++) {
                double surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, columnX, columnZ);
                BlockPos feet = BlockPos.containing(columnX, surfaceY, columnZ);
                BlockPos floor = feet.below();
                if (!level.getFluidState(feet).isEmpty()
                    || !level.getFluidState(floor).isEmpty()
                    || level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) continue;
                highestSurface = Math.max(highestSurface, surfaceY);
            }
        }
        if (!Double.isFinite(highestSurface)) return null;
        AABB candidate = dragon.getBoundingBox()
            .move(x - start.x, highestSurface - start.y, z - start.z)
            .inflate(horizontalMargin, 0.0D, horizontalMargin)
            .expandTowards(0.0D, verticalMargin, 0.0D);
        return level.noCollision(dragon, candidate)
            ? new Vec3(x, highestSurface, z)
            : null;
    }

    private Vec3 safeTeleportTarget(
        ServerLevel level,
        Entity dragon,
        ServerPlayer owner,
        double clearance
    ) {
        Vec3 look = owner.getLookAngle();
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double directionX = horizontal > 0.01D ? -look.x / horizontal : 0.0D;
        double directionZ = horizontal > 0.01D ? -look.z / horizontal : 1.0D;
        double radius = Math.max(8.0D, dragon.getBbWidth() + 4.0D);
        double x = owner.getX() + directionX * radius;
        double z = owner.getZ() + directionZ * radius;
        double terrain = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING,
            (int) Math.floor(x),
            (int) Math.floor(z)
        );
        double maximumBottomY = level.getMaxBuildHeight() - dragon.getBbHeight() - 2.0D;
        double y = Math.min(
            maximumBottomY,
            Math.max(owner.getY() + clearance, terrain + clearance)
        );
        return new Vec3(x, y, z);
    }

    private int updateStuckTicks(Entity dragon, boolean routeObstructed, double distance) {
        CompoundTag data = dragon.getPersistentData();
        boolean initialized = data.contains(NAV_PREVIOUS_X)
            && data.contains(NAV_PREVIOUS_Y)
            && data.contains(NAV_PREVIOUS_Z);
        double movedSquared = initialized ? dragon.position().distanceToSqr(new Vec3(
            data.getDouble(NAV_PREVIOUS_X),
            data.getDouble(NAV_PREVIOUS_Y),
            data.getDouble(NAV_PREVIOUS_Z)
        )) : Double.MAX_VALUE;
        int stuckTicks = data.getInt(NAV_STUCK_TICKS);
        if (routeObstructed && distance > 12.0D && movedSquared < 0.01D) stuckTicks++;
        else stuckTicks = Math.max(0, stuckTicks - 3);
        data.putDouble(NAV_PREVIOUS_X, dragon.getX());
        data.putDouble(NAV_PREVIOUS_Y, dragon.getY());
        data.putDouble(NAV_PREVIOUS_Z, dragon.getZ());
        data.putInt(NAV_STUCK_TICKS, stuckTicks);
        return stuckTicks;
    }

    private void resetStuckTicks(Entity dragon) {
        CompoundTag data = dragon.getPersistentData();
        data.putDouble(NAV_PREVIOUS_X, dragon.getX());
        data.putDouble(NAV_PREVIOUS_Y, dragon.getY());
        data.putDouble(NAV_PREVIOUS_Z, dragon.getZ());
        data.putInt(NAV_STUCK_TICKS, 0);
    }

    private static double lerp(double alpha, double start, double end) {
        return start + (end - start) * alpha;
    }

    private static double square(double value) {
        return value * value;
    }

    @Override
    public boolean flyTo(Entity dragon, Vec3 target, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner) || dragon.level() != owner.level()) return false;
        if (dragon instanceof Mob mob) return mob.getNavigation().moveTo(target.x, target.y, target.z, 1.35);
        return false;
    }

    @Override
    public boolean recall(Entity dragon, ServerPlayer owner, boolean allowTeleport) {
        if (!isOwnedBy(dragon, owner) || dragon.level() != owner.level()) return false;
        setFollow(dragon, owner);
        if (!DragonActionPolicy.shouldTeleportRecall(allowTeleport, dragon.distanceTo(owner))) {
            if (dragon instanceof Mob mob) mob.getNavigation().moveTo(owner, 1.45);
            return true;
        }
        if (!(dragon.level() instanceof ServerLevel level)) return false;
        Vec3 destination = safeRecallTarget(level, dragon, owner);
        if (destination == null) return false;
        dragon.teleportTo(destination.x, destination.y, destination.z);
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
        return true;
    }

    boolean recoverStalledRecall(Entity dragon, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner) || dragon.level() != owner.level()) return false;
        if (!(dragon.level() instanceof ServerLevel level)) return false;
        Vec3 destination = safeRecallTarget(level, dragon, owner);
        if (destination == null) return false;
        setFollow(dragon, owner);
        dragon.teleportTo(destination.x, destination.y, destination.z);
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.hasImpulse = true;
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
        return true;
    }

    private Vec3 safeRecallTarget(ServerLevel level, Entity dragon, ServerPlayer owner) {
        Vec3 start = dragon.position();
        double width = Math.max(1.0D, dragon.getBbWidth());
        double height = Math.max(1.0D, dragon.getBbHeight());
        double horizontalMargin = Math.max(0.35D, Math.min(1.5D, width * 0.15D));
        double verticalMargin = Math.max(0.5D, Math.min(2.0D, height * 0.15D));
        Vec3 look = owner.getLookAngle();
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double directionX = horizontal > 0.01D ? -look.x / horizontal : 0.0D;
        double directionZ = horizontal > 0.01D ? -look.z / horizontal : 1.0D;
        double preferredRadius = Math.max(8.0D, width + 4.0D);
        Vec3 preferred = landingCandidate(
            level,
            dragon,
            start,
            horizontalMargin,
            verticalMargin,
            owner.getX() + directionX * preferredRadius,
            owner.getZ() + directionZ * preferredRadius,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
        );
        if (preferred != null) return preferred;

        for (int ring = 0; ring < DragonTerrainAvoidancePolicy.LANDING_SEARCH_RINGS; ring++) {
            double radius = DragonTerrainAvoidancePolicy.landingSearchRadius(width, ring);
            for (int direction = 0; direction < DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS; direction++) {
                double angle = Math.PI * 2.0D * direction
                    / DragonTerrainAvoidancePolicy.LANDING_SEARCH_DIRECTIONS;
                Vec3 candidate = landingCandidate(
                    level,
                    dragon,
                    start,
                    horizontalMargin,
                    verticalMargin,
                    owner.getX() + Math.cos(angle) * radius,
                    owner.getZ() + Math.sin(angle) * radius,
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
                );
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    @Override
    public boolean assistCombat(Entity dragon, LivingEntity target, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner) || !target.isAlive() || target == owner) return false;
        if (dragon instanceof Mob mob) {
            mob.setTarget(target);
            mob.getLookControl().setLookAt(target, 45.0F, 45.0F);
            double reach = DragonActionPolicy.combatReach(
                dragon.getBbWidth(), dragon.getBbHeight(), target.getBbWidth()
            );
            boolean inReach = dragon.distanceTo(target) <= reach
                || dragon.getBoundingBox().inflate(1.5D).intersects(target.getBoundingBox());
            if (!inReach) {
                if (!dragon.isVehicle()) mob.getNavigation().moveTo(target, 1.4D);
                return true;
            }

            Object primaryAttack = DragonActionPolicy.shouldActivateModCombatAbility(dragon.isVehicle())
                ? invoke(dragon, "getPrimaryAttackAbility")
                : null;
            boolean activatedModAttack = primaryAttack != null
                && invokeVoid(dragon, "tryActivateAbility", primaryAttack);
            if (activatedModAttack && invoke(dragon, "getActiveAbility") == null) {
                activatedModAttack = false;
            }
            if (DragonActionPolicy.shouldUseMeleeFallback(
                activatedModAttack, target.invulnerableTime
            )) safeVanillaMelee(mob, target);
            return true;
        }
        return invokeVoid(dragon, "setTarget", target);
    }

    @Override
    public void haltTravel(Entity dragon, ServerPlayer owner) {
        if (dragon instanceof Mob mob) mob.getNavigation().stop();
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.hasImpulse = true;
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
    }

    protected final boolean releaseTransientGroundContact(Entity dragon) {
        if (!dragon.onGround()) return true;
        double increment = Math.max(0.35D, Math.min(0.75D, dragon.getBbHeight() * 0.08D));
        for (int step = 1; step <= 4; step++) {
            double lift = increment * step;
            if (!dragon.level().noCollision(dragon, dragon.getBoundingBox().move(0.0D, lift, 0.0D))) continue;
            dragon.setPos(dragon.getX(), dragon.getY() + lift, dragon.getZ());
            return true;
        }
        return false;
    }

    private static void safeVanillaMelee(Mob dragon, LivingEntity target) {
        try {
            dragon.doHurtTarget(target);
        } catch (IllegalArgumentException | IllegalStateException missingAttackAttribute) {
            // Some modded dragons intentionally omit generic.attack_damage and
            // route all attacks through abilities. Keep a bounded compatibility
            // hit so an unsupported ability surface cannot stall the task.
            float damage = (float) Math.max(2.0D, Math.min(12.0D, dragon.getBbWidth() * 1.5D));
            target.hurt(dragon.damageSources().mobAttack(dragon), damage);
        }
    }

    @Override
    public boolean land(Entity dragon, ServerPlayer owner) {
        if (!isOwnedBy(dragon, owner)) return false;
        Vec3 target = safeLandingTarget(dragon, owner);
        if (target == null) return false;
        if (dragon instanceof Mob mob) mob.getNavigation().moveTo(target.x, target.y, target.z, 1.0);
        return true;
    }

    protected static Object invoke(Object target, String name, Object... arguments) {
        if (target == null) return null;
        Method method = findMethod(target, name, arguments);
        if (method == null) return null;
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    protected static boolean invokeVoid(Object target, String name, Object... arguments) {
        if (target == null) return false;
        Method method = findMethod(target, name, arguments);
        if (method == null) return false;
        try {
            method.invoke(target, arguments);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    protected static boolean invokeEnumVoid(Object target, String name, String constant) {
        if (target == null || constant == null) return false;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (!parameter.isEnum()) continue;
            try {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object value = Enum.valueOf((Class<? extends Enum>) parameter.asSubclass(Enum.class), constant);
                method.invoke(target, value);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    protected static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (!field.trySetAccessible()) return null;
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Continue through the mod entity's superclass hierarchy.
            }
        }
        return null;
    }

    private static Method findMethod(Object target, String name, Object[] arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;
            for (int index = 0; index < parameters.length; index++) {
                Object value = arguments[index];
                if (value == null) continue;
                Class<?> parameter = wrap(parameters[index]);
                if (!parameter.isInstance(value)) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) return method;
        }
        return null;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
