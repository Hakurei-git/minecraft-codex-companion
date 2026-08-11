package cn.codex.minecraftbridge.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Reversible world-state evidence for follow, flight and owner-damage rules. */
final class FollowResilienceLiveFixture {
    private static final String STATE_KEY = "CodexFollowResilienceFixture";
    private static final String CLEANUP_PENDING = "CleanupPending";
    private static final String LANDING_X = "LandingX";
    private static final String LANDING_Y = "LandingY";
    private static final String LANDING_Z = "LandingZ";
    private static final double FAR_RECALL_MARGIN = 16.0D;
    private static final double RESTORE_EPSILON = 1.0E-4D;

    private FollowResilienceLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "move-ground", "take-off", "land", "far-recall" -> stage(player, npc, mode);
            case "inspect-ground" -> inspect(player, npc, "ground");
            case "inspect-air" -> inspect(player, npc, "air");
            case "inspect-land" -> inspect(player, npc, "land");
            case "inspect-recall" -> inspect(player, npc, "recall");
            case "owner-melee" -> damage(player, npc, "melee");
            case "owner-projectile" -> damage(player, npc, "projectile");
            case "environment" -> damage(player, npc, "environment");
            case "damage-cleanup" -> {
                requiredState(player, npc);
                npc.setStatus("follow-fixture:damage-cleanup");
            }
            case "reset-survival" -> {
                requiredState(player, npc);
                setGameMode(player, GameType.SURVIVAL, false);
                npc.setStatus("follow-fixture:reset-survival");
            }
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown follow resilience fixture mode");
        }
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        if (npc.getPersistentData().contains(STATE_KEY)) cleanup(player, npc, false);
        if (!player.isAlive() || npc.isDowned()) {
            throw new IllegalStateException("Follow fixture requires a living player and active NPC");
        }
        if (player.level() != npc.level()) {
            throw new IllegalStateException("Follow fixture requires player and NPC in the same dimension");
        }
        if (!"idle".equals(npc.tasks().schedulerLifecycle()) || npc.tasks().pausedTaskCount() > 0
            || npc.isManagedEating()) {
            throw new IllegalStateException("Follow fixture requires an idle task scheduler");
        }
        if (player.isPassenger() || npc.isPassenger() || !player.onGround() || !npc.onGround()) {
            throw new IllegalStateException("Follow fixture requires player and NPC to be dismounted on the ground");
        }

        CompoundTag state = new CompoundTag();
        state.putUUID("Owner", player.getUUID());
        state.putString("PlayerDimension", dimensionId(player));
        state.putString("NpcDimension", dimensionId(npc));
        putPosition(state, "Player", player);
        putPosition(state, "Npc", npc);
        putMotion(state, "Player", player);
        putMotion(state, "Npc", npc);
        state.putString("PlayerGameMode", player.gameMode.getGameModeForPlayer().getName());
        state.putBoolean("PlayerFlying", player.getAbilities().flying);
        state.putBoolean("PlayerInvulnerable", player.isInvulnerable());
        CompoundTag playerAbilities = new CompoundTag();
        player.getAbilities().addSaveData(playerAbilities);
        state.put("PlayerAbilities", playerAbilities);
        state.putFloat("NpcHealth", npc.getHealth());
        state.putFloat("TestHealth", npc.getMaxHealth());
        state.putBoolean("NpcNoGravity", npc.isNoGravity());
        state.putInt("NpcInvulnerableTime", npc.invulnerableTime);
        state.putByte("NpcStance", npc.stance().id());
        state.putString("NpcStatus", npc.status());
        CompoundTag npcData = new CompoundTag();
        npc.addAdditionalSaveData(npcData);
        state.put("NpcData", npcData);
        npc.getPersistentData().put(STATE_KEY, state);

        player.setInvulnerable(true);
        player.fallDistance = 0.0F;
        npc.fallDistance = 0.0F;
        npc.setHealth(npc.getMaxHealth());
        npc.tasks().followOwner();
        npc.setStatus("follow-fixture:setup");
    }

    private static void stage(ServerPlayer player, CodexNpcEntity npc, String mode) {
        CompoundTag state = requiredState(player, npc);
        ServerLevel level = player.serverLevel();
        switch (mode) {
            case "move-ground" -> {
                setGameMode(player, GameType.SURVIVAL, false);
                Vec3 destination = reachableGroundDestination(level, npc, 12, 10, 8);
                movePlayer(player, destination.x, destination.y, destination.z);
            }
            case "take-off" -> {
                setGameMode(player, GameType.CREATIVE, true);
                Vec3 landing = loadedSafeGroundDestination(level, npc, 12.0D);
                state.putDouble(LANDING_X, landing.x);
                state.putDouble(LANDING_Y, landing.y);
                state.putDouble(LANDING_Z, landing.z);
                movePlayer(player, landing.x, landing.y + 18.0D, landing.z);
            }
            case "land" -> {
                setGameMode(player, GameType.SURVIVAL, false);
                Vec3 landing = state.contains(LANDING_X, Tag.TAG_DOUBLE)
                    && state.contains(LANDING_Y, Tag.TAG_DOUBLE)
                    && state.contains(LANDING_Z, Tag.TAG_DOUBLE)
                    ? new Vec3(
                        state.getDouble(LANDING_X),
                        state.getDouble(LANDING_Y),
                        state.getDouble(LANDING_Z)
                    )
                    : loadedSafeGroundDestination(level, npc, 4.0D);
                movePlayer(player, landing.x, landing.y, landing.z);
            }
            case "far-recall" -> {
                setGameMode(player, GameType.SURVIVAL, false);
                if (player.hasPermissions(2)) {
                    double minimumDistance = npc.tasks().recallDistanceForFixture() + FAR_RECALL_MARGIN;
                    Vec3 destination = loadedSafeGroundDestination(level, npc, minimumDistance);
                    movePlayer(player, destination.x, destination.y, destination.z);
                } else {
                    Vec3 destination = reachableGroundDestination(level, npc, 40, 36, 32, 28, 24);
                    movePlayer(player, destination.x, destination.y, destination.z);
                }
            }
            default -> throw new IllegalArgumentException("Unknown follow stage " + mode);
        }
        player.fallDistance = 0.0F;
        npc.fallDistance = 0.0F;
        npc.tasks().followOwner();
        npc.setStatus("follow-fixture:stage=" + mode);
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc, String phase) {
        requiredState(player, npc);
        npc.setStatus(String.format(Locale.ROOT,
            "follow-fixture:p=%s,d=%d,v=%d,ny=%d,oy=%d,g=%d,of=%d,og=%d,ng=%d,s=%d,op=%d,grav=%d,st=%d,fm=%d,rd=%d,aw=%d",
            phase,
            milli(npc.distanceTo(player)),
            milli(Math.abs(npc.getY() - player.getY())),
            milli(npc.getY()),
            milli(player.getY()),
            player.gameMode.getGameModeForPlayer().getId(),
            player.getAbilities().flying ? 1 : 0,
            player.onGround() ? 1 : 0,
            npc.onGround() ? 1 : 0,
            npc.stance().id(),
            player.hasPermissions(2) ? 1 : 0,
            npc.isNoGravity() ? 1 : 0,
            npc.tasks().followStalledTicksForFixture(),
            npc.tasks().followModeForFixture(),
            npc.tasks().recallDistanceForFixture(),
            npc.tasks().hasActiveWorkForFixture() ? 1 : 0
        ));
    }

    private static void damage(ServerPlayer player, CodexNpcEntity npc, String type) {
        requiredState(player, npc);
        npc.setHealth(npc.getMaxHealth());
        int before = milli(npc.getHealth());
        boolean accepted = switch (type) {
            case "melee" -> npc.hurt(npc.damageSources().playerAttack(player), 5.0F);
            case "projectile" -> hurtWithOwnedArrow(player, npc);
            case "environment" -> {
                npc.invulnerableTime = 0;
                yield npc.hurt(npc.damageSources().inFire(), 3.0F);
            }
            default -> throw new IllegalArgumentException("Unknown damage fixture type " + type);
        };
        npc.setStatus("follow-fixture:x=" + type
            + ",b=" + before
            + ",a=" + milli(npc.getHealth())
            + ",ok=" + (accepted ? 1 : 0)
            + ",down=" + (npc.isDowned() ? 1 : 0));
    }

    private static boolean hurtWithOwnedArrow(ServerPlayer player, CodexNpcEntity npc) {
        Arrow arrow = new Arrow(player.serverLevel(), player);
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(Vec3.ZERO);
        arrow.moveTo(npc.getX(), npc.getEyeY(), npc.getZ());
        if (!player.serverLevel().addFreshEntity(arrow)) {
            throw new IllegalStateException("Follow fixture arrow could not be added");
        }
        try {
            return npc.hurt(npc.damageSources().arrow(arrow, player), 5.0F);
        } finally {
            arrow.discard();
        }
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        CompoundTag persistent = npc.getPersistentData();
        if (!persistent.contains(STATE_KEY)) {
            if (report) npc.setStatus("follow-fixture:cleanup none");
            return;
        }
        CompoundTag state = persistent.getCompound(STATE_KEY).copy();
        if (!state.hasUUID("Owner") || !state.getUUID("Owner").equals(player.getUUID())) {
            throw new IllegalStateException("Follow fixture owner changed before cleanup");
        }
        boolean finalizePendingCleanup = state.getBoolean(CLEANUP_PENDING);
        if (finalizePendingCleanup) report = false;
        requireOriginalDimensions(player, npc, state);

        GameType gameType = GameType.byName(state.getString("PlayerGameMode"), GameType.SURVIVAL);
        player.gameMode.changeGameModeForPlayer(gameType);
        if (state.contains("PlayerAbilities", Tag.TAG_COMPOUND)) {
            player.getAbilities().loadSaveData(state.getCompound("PlayerAbilities"));
        } else {
            player.getAbilities().flying = state.getBoolean("PlayerFlying") && player.getAbilities().mayfly;
        }
        player.onUpdateAbilities();
        player.setInvulnerable(state.getBoolean("PlayerInvulnerable"));
        if (state.contains("NpcData", Tag.TAG_COMPOUND)) {
            npc.readAdditionalSaveData(state.getCompound("NpcData").copy());
        }
        npc.tasks().resetFollowMovementForFixture();
        restorePosition(player, state, "Player");
        restorePosition(npc, state, "Npc");
        restoreMotion(player, state, "Player");
        restoreMotion(npc, state, "Npc");
        npc.setNoGravity(state.getBoolean("NpcNoGravity"));
        npc.invulnerableTime = state.getInt("NpcInvulnerableTime");
        npc.getNavigation().stop();
        if (!state.contains("NpcData", Tag.TAG_COMPOUND)) {
            npc.setHealth(Math.min(npc.getMaxHealth(), Math.max(1.0F, state.getFloat("NpcHealth"))));
            npc.setStance(NpcTaskEngine.Stance.fromId(state.getByte("NpcStance")));
        }
        String cleanupEvidence = cleanupEvidence(player, npc, state);
        if (report) {
            state.putBoolean(CLEANUP_PENDING, true);
            persistent.put(STATE_KEY, state);
            npc.setStatus(cleanupEvidence);
            scheduleCleanupFinalization(player, npc);
        } else {
            persistent.remove(STATE_KEY);
            npc.setStatus(state.getString("NpcStatus"));
        }
    }

    private static CompoundTag requiredState(ServerPlayer player, CodexNpcEntity npc) {
        if (player == null || !npc.getPersistentData().contains(STATE_KEY)) {
            throw new IllegalStateException("Follow fixture backup is unavailable");
        }
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY);
        if (!state.hasUUID("Owner") || !state.getUUID("Owner").equals(player.getUUID())) {
            throw new IllegalStateException("Follow fixture owner does not match");
        }
        if (state.getBoolean(CLEANUP_PENDING)) {
            throw new IllegalStateException("Follow fixture cleanup requires finalization");
        }
        requireOriginalDimensions(player, npc, state);
        return state;
    }

    private static void putPosition(CompoundTag state, String prefix, Entity entity) {
        state.putDouble(prefix + "X", entity.getX());
        state.putDouble(prefix + "Y", entity.getY());
        state.putDouble(prefix + "Z", entity.getZ());
        state.putFloat(prefix + "Yaw", entity.getYRot());
        state.putFloat(prefix + "Pitch", entity.getXRot());
        state.putBoolean(prefix + "OnGround", entity.onGround());
    }

    private static void restorePosition(Entity entity, CompoundTag state, String prefix) {
        double x = state.getDouble(prefix + "X");
        double y = state.getDouble(prefix + "Y");
        double z = state.getDouble(prefix + "Z");
        float yaw = state.getFloat(prefix + "Yaw");
        float pitch = state.getFloat(prefix + "Pitch");
        if (entity instanceof ServerPlayer player) player.connection.teleport(x, y, z, yaw, pitch);
        else entity.moveTo(x, y, z, yaw, pitch);
        entity.setOnGround(state.getBoolean(prefix + "OnGround"));
    }

    private static void requireOriginalDimensions(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        if (!state.contains("PlayerDimension", Tag.TAG_STRING)
            || !state.contains("NpcDimension", Tag.TAG_STRING)) {
            throw new IllegalStateException("Follow fixture backup dimension is unavailable");
        }
        if (!state.getString("PlayerDimension").equals(dimensionId(player))
            || !state.getString("NpcDimension").equals(dimensionId(npc))
            || player.level() != npc.level()) {
            throw new IllegalStateException("Return player and NPC to the original dimension before cleanup");
        }
    }

    private static String dimensionId(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    private static String cleanupEvidence(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        CompoundTag abilities = new CompoundTag();
        player.getAbilities().addSaveData(abilities);
        boolean dimensions = state.getString("PlayerDimension").equals(dimensionId(player))
            && state.getString("NpcDimension").equals(dimensionId(npc));
        boolean gameMode = state.getString("PlayerGameMode")
            .equals(player.gameMode.getGameModeForPlayer().getName());
        boolean positions = samePosition(player, state, "Player") && samePosition(npc, state, "Npc");
        boolean stance = npc.stance().id() == state.getByte("NpcStance");
        boolean health = Math.abs(npc.getHealth() - state.getFloat("NpcHealth")) <= RESTORE_EPSILON;
        boolean gravity = npc.isNoGravity() == state.getBoolean("NpcNoGravity");
        boolean abilityState = !state.contains("PlayerAbilities", Tag.TAG_COMPOUND)
            || abilities.equals(state.getCompound("PlayerAbilities"));
        boolean invulnerability = player.isInvulnerable() == state.getBoolean("PlayerInvulnerable")
            && npc.invulnerableTime == state.getInt("NpcInvulnerableTime");
        boolean onGround = player.onGround() == state.getBoolean("PlayerOnGround")
            && npc.onGround() == state.getBoolean("NpcOnGround");
        boolean status = npc.status().equals(state.getString("NpcStatus"));
        return "follow-fixture:cleanup restored"
            + ",dim=" + bit(dimensions)
            + ",gm=" + bit(gameMode)
            + ",pos=" + bit(positions)
            + ",stance=" + bit(stance)
            + ",health=" + bit(health)
            + ",grav=" + bit(gravity)
            + ",ability=" + bit(abilityState)
            + ",inv=" + bit(invulnerability)
            + ",ground=" + bit(onGround)
            + ",status=" + bit(status);
    }

    private static void scheduleCleanupFinalization(ServerPlayer player, CodexNpcEntity npc) {
        var server = player.getServer();
        if (server == null) return;
        server.tell(new TickTask(server.getTickCount() + 1, () -> {
            if (player.isRemoved()) return;
            CodexNpcEntity current = npc.isRemoved() ? NpcManager.find(player) : npc;
            if (current == null || !current.getPersistentData().contains(STATE_KEY)) return;
            CompoundTag state = current.getPersistentData().getCompound(STATE_KEY);
            if (!state.getBoolean(CLEANUP_PENDING)) return;
            try {
                cleanup(player, current, false);
            } catch (RuntimeException ignored) {
                // Keep the pending backup intact so setup or a later cleanup can retry safely.
            }
        }));
    }

    private static boolean samePosition(Entity entity, CompoundTag state, String prefix) {
        return Math.abs(entity.getX() - state.getDouble(prefix + "X")) <= RESTORE_EPSILON
            && Math.abs(entity.getY() - state.getDouble(prefix + "Y")) <= RESTORE_EPSILON
            && Math.abs(entity.getZ() - state.getDouble(prefix + "Z")) <= RESTORE_EPSILON;
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static void putMotion(CompoundTag state, String prefix, Entity entity) {
        Vec3 motion = entity.getDeltaMovement();
        state.putDouble(prefix + "MotionX", motion.x);
        state.putDouble(prefix + "MotionY", motion.y);
        state.putDouble(prefix + "MotionZ", motion.z);
        state.putFloat(prefix + "FallDistance", entity.fallDistance);
    }

    private static void restoreMotion(Entity entity, CompoundTag state, String prefix) {
        entity.setDeltaMovement(
            state.getDouble(prefix + "MotionX"),
            state.getDouble(prefix + "MotionY"),
            state.getDouble(prefix + "MotionZ")
        );
        entity.fallDistance = state.getFloat(prefix + "FallDistance");
    }

    private static void setGameMode(ServerPlayer player, GameType gameType, boolean flying) {
        player.gameMode.changeGameModeForPlayer(gameType);
        player.getAbilities().flying = flying;
        player.onUpdateAbilities();
    }

    private static void movePlayer(ServerPlayer player, double x, double y, double z) {
        player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static double surfaceY(ServerLevel level, double x, double z) {
        return level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (int) Math.floor(x),
            (int) Math.floor(z)
        );
    }

    private static Vec3 reachableGroundDestination(ServerLevel level, CodexNpcEntity npc, int... radii) {
        for (int radius : radii) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = direction * Math.PI / 8.0D;
                int x = (int) Math.floor(npc.getX() + Math.cos(angle) * radius);
                int z = (int) Math.floor(npc.getZ() + Math.sin(angle) * radius);
                int y = (int) surfaceY(level, x, z);
                BlockPos destination = new BlockPos(x, y, z);
                Path path = npc.getNavigation().createPath(destination, 0);
                if (path != null && path.canReach()) {
                    return new Vec3(x + 0.5D, y, z + 0.5D);
                }
            }
        }
        throw new IllegalStateException("Follow fixture could not find a reachable ground destination");
    }

    private static Vec3 loadedSafeGroundDestination(
        ServerLevel level,
        CodexNpcEntity npc,
        double minimumDistance
    ) {
        int firstRadius = (int) Math.ceil(minimumDistance);
        for (int radius = firstRadius; radius <= firstRadius + 48; radius += 4) {
            for (int direction = 0; direction < 32; direction++) {
                double angle = direction * Math.PI / 16.0D;
                int x = (int) Math.floor(npc.getX() + Math.cos(angle) * radius);
                int z = (int) Math.floor(npc.getZ() + Math.sin(angle) * radius);
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y = (int) surfaceY(level, x, z);
                BlockPos destination = new BlockPos(x, y, z);
                BlockPos floor = destination.below();
                if (!level.getWorldBorder().isWithinBounds(destination)
                    || !level.getBlockState(floor).isSolidRender(level, floor)
                    || !level.getBlockState(destination).getCollisionShape(level, destination).isEmpty()
                    || !level.getBlockState(destination.above()).getCollisionShape(level, destination.above()).isEmpty()
                    || !level.getFluidState(destination).isEmpty()
                    || !level.getFluidState(destination.above()).isEmpty()) {
                    continue;
                }
                return new Vec3(x + 0.5D, y, z + 0.5D);
            }
        }
        throw new IllegalStateException("Follow fixture could not find a loaded safe recall destination");
    }

    private static int milli(double value) {
        return (int) Math.round(value * 1_000.0D);
    }
}
