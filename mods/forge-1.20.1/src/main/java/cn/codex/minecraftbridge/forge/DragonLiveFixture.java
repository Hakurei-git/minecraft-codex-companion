package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Fixed, local-only and reversible world arrangements for dragon acceptance tests. */
final class DragonLiveFixture {
    private static final String STATE_KEY = "CodexDragonLiveFixture";
    private static final String BOOK_TAG = "CodexBookDragon";
    private static final String SAINTS_TAG = "CodexSaintsDragon";
    private static final String COMBAT_TAG = "CodexDragonCombatTarget";
    private static final String FOOD_TAG = "CodexDragonFixtureFood";
    private static final String OBSTACLE_POSITIONS = "ObstaclePositions";

    private DragonLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        switch (mode) {
            case "spawn-book" -> spawn(player, npc, "bookofdragons:deadlynadder", BOOK_TAG, false);
            case "spawn-saints" -> spawnSaints(player, npc);
            case "move-book-far" -> moveFar(player, npc, BOOK_TAG, "bookofdragons");
            case "move-saints-far" -> moveFar(player, npc, SAINTS_TAG, "saintsdragons");
            case "raise-book" -> raise(player, npc, BOOK_TAG, "bookofdragons");
            case "raise-saints" -> raise(player, npc, SAINTS_TAG, "saintsdragons");
            case "set-book-wander" -> setWander(npc, player, BOOK_TAG, "bookofdragons", 0);
            case "set-saints-wander" -> setWander(npc, player, SAINTS_TAG, "saintsdragons", 2);
            case "spawn-combat-target" -> spawnCombatTarget(player, npc);
            case "arm-combat-target" -> armCombatTarget(player, npc);
            case "co-ride-book" -> coRide(player, npc, BOOK_TAG, "bookofdragons");
            case "co-ride-saints" -> coRide(player, npc, SAINTS_TAG, "saintsdragons");
            case "dismount-all" -> dismountAll(player, npc);
            case "inspect-book" -> inspect(player, npc, BOOK_TAG, "bookofdragons");
            case "inspect-saints" -> inspect(player, npc, SAINTS_TAG, "saintsdragons");
            case "stage-obstacle-book" -> stageObstacle(player, npc, BOOK_TAG, "bookofdragons");
            case "stage-obstacle-saints" -> stageObstacle(player, npc, SAINTS_TAG, "saintsdragons");
            case "clear-obstacle" -> {
                int cleared = clearObstacle(player, npc);
                npc.setStatus("dragon-fixture:obstacle cleared=" + cleared);
            }
            case "prepare-book-feed", "inspect-book-needs", "inspect-book-tame",
                "drop-book-food", "set-creative", "set-survival" ->
                npc.setStatus("dragon-fixture:" + mode + " ok");
            case "cleanup-combat" -> cleanupCombat(player, npc);
            case "cleanup" -> cleanup(player, npc);
            default -> throw new IllegalArgumentException("Unknown dragon fixture mode");
        }
    }

    private static void begin(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag persistent = npc.getPersistentData();
        if (persistent.contains(STATE_KEY)) return;
        if (!player.isAlive()) {
            throw new IllegalStateException("Dragon fixture requires a living player");
        }
        if (player.isPassenger() || npc.isPassenger()) {
            throw new IllegalStateException("Dragon fixture must start while player and NPC are dismounted");
        }
        CompoundTag state = new CompoundTag();
        state.putUUID("Owner", player.getUUID());
        state.putBoolean("PlayerInvulnerable", player.isInvulnerable());
        putPosition(state, "Player", player);
        putPosition(state, "Npc", npc);
        state.putString("NpcStatus", npc.status());
        state.putFloat("NpcHealth", npc.getHealth());
        state.putInt("NpcFood", npc.foodLevel());
        state.putFloat("NpcSaturation", npc.saturationLevel());
        state.putFloat("NpcExhaustion", npc.exhaustionLevel());
        if (npc.boundDragonUuid() != null) state.putUUID("BoundDragon", npc.boundDragonUuid());
        if (!npc.boundDragonDimension().isBlank()) {
            state.putString("BoundDragonDimension", npc.boundDragonDimension());
        }
        if (npc.boundDragonPosition() != null) {
            state.putLong("BoundDragonPosition", npc.boundDragonPosition().asLong());
        }
        persistent.put(STATE_KEY, state);
        player.setInvulnerable(true);
        player.fallDistance = 0.0F;
    }

    private static void spawn(
        ServerPlayer player,
        CodexNpcEntity npc,
        String entityId,
        String fixtureTag,
        boolean maximizeHappiness
    ) {
        String stage = "begin";
        try {
            begin(player, npc);
            DragonAutopilotControl.resetDiagnostics(player);
            stage = "discard";
            discardTagged(player, fixtureTag);
            stage = "lookup";
            EntityType<?> type = EntityType.byString(entityId).orElse(null);
            if (type == null) throw new IllegalArgumentException("Fixture dragon type is unavailable: " + entityId);
            stage = "create";
            Entity dragon = type.create(player.serverLevel());
            if (dragon == null) throw new IllegalStateException("Fixture dragon could not be created");
            // The fixture owns this temporary entity and discards it during cleanup.
            // Keep ambient damage from mutating a mod command while a phase is inspected.
            dragon.setInvulnerable(true);
            stage = "position";
            dragon.moveTo(npc.getX() + 4.0D, npc.getY(), npc.getZ(), npc.getYRot(), 0.0F);
            dragon.addTag(fixtureTag);
            stage = "finalize";
            if (dragon instanceof Mob mob) {
                ForgeEventFactory.onFinalizeSpawn(
                    mob,
                    player.serverLevel(),
                    player.serverLevel().getCurrentDifficultyAt(dragon.blockPosition()),
                    MobSpawnType.COMMAND,
                    null,
                    null
                );
                mob.setPersistenceRequired();
            }
            stage = "world-add";
            if (!player.serverLevel().addFreshEntity(dragon)) {
                throw new IllegalStateException("Fixture dragon was rejected by the world");
            }
            stage = "age";
            if (dragon instanceof AgeableMob ageable) {
                if (entityId.startsWith("bookofdragons:")) {
                    ageable.setAge(0);
                } else if (ageable.isBaby()) {
                    dragon.discard();
                    throw new IllegalStateException("Saints fixture candidate spawned as a baby");
                }
            }
            stage = "ownership-write";
            if (dragon instanceof TamableAnimal tameable) {
                tameable.setOwnerUUID(player.getUUID());
                tameable.setTame(true);
                tameable.setOrderedToSit(false);
            }
            ReflectiveDragonAdapter.invokeVoid(dragon, "setOwnerUUID", player.getUUID());
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTame", true);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamed", true);
            stage = "mod-prepare";
            if (entityId.startsWith("bookofdragons:")) prepareBookFixture(dragon, player);
            if (maximizeHappiness) ReflectiveDragonAdapter.invokeVoid(dragon, "setHappiness", 100);
            stage = "ownership-verify";
            DragonAdapter adapter = DragonAdapters.forEntity(dragon);
            if (adapter == null || !adapter.isOwnedBy(dragon, player)) {
                dragon.discard();
                throw new IllegalStateException("Fixture dragon ownership initialization failed");
            }
            stage = "safe-position";
            Vec3 safePosition = adapter.safeLandingTarget(dragon, player);
            if (safePosition == null) {
                dragon.discard();
                throw new IllegalStateException("Fixture dragon has no collision-free staging position");
            }
            dragon.moveTo(
                safePosition.x,
                safePosition.y,
                safePosition.z,
                dragon.getYRot(),
                dragon.getXRot()
            );
            dragon.setDeltaMovement(Vec3.ZERO);
            dragon.fallDistance = 0.0F;
            stage = "ride-prepare";
            adapter.prepareSharedRide(dragon, player);
            if (dragon instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
            npc.setStatus("dragon-fixture:spawn mod=" + adapter.modId() + ",id=" + dragon.getUUID());
        } catch (RuntimeException failure) {
            if (failure instanceof FixtureFailure) throw failure;
            throw new FixtureFailure("spawn-" + stage, failure);
        }
    }

    private static void spawnSaints(ServerPlayer player, CodexNpcEntity npc) {
        RuntimeException lastFailure = null;
        for (String entityId : List.of(
            "saintsdragons:raevyx",
            "saintsdragons:cindervane",
            "saintsdragons:volitans",
            "saintsdragons:ignivorus"
        )) {
            try {
                spawn(player, npc, entityId, SAINTS_TAG, true);
                return;
            } catch (RuntimeException failure) {
                discardTagged(player, SAINTS_TAG);
                lastFailure = failure;
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IllegalStateException("No supported Saints Dragons fixture entity could be initialized");
    }

    static String failureCode(RuntimeException failure) {
        return failure instanceof FixtureFailure diagnosed ? diagnosed.code : "";
    }

    private static final class FixtureFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private FixtureFailure(String code, RuntimeException cause) {
            super(code, cause);
            this.code = code;
        }
    }

    private static void prepareBookFixture(Entity dragon, ServerPlayer player) {
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualCompleted", true);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setAwaitingTamingRitual", false);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualTimer", 0);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setAffection", player.getUUID(), 1000);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setGrowthStage", 2);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setGrowthProgress", 0);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setCommand", 2);
        Object inventory = ReflectiveDragonAdapter.invoke(dragon, "getInventory");
        if (inventory instanceof Container container && container.getContainerSize() > 0) {
            container.setItem(0, new ItemStack(Items.SADDLE));
            ReflectiveDragonAdapter.invokeVoid(dragon, "updateEquipment");
        }
        ReflectiveDragonAdapter.invokeVoid(dragon, "setSaddled", true);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setSeatLocked", false);
    }

    private static void moveFar(
        ServerPlayer player,
        CodexNpcEntity npc,
        String fixtureTag,
        String modId
    ) {
        Entity dragon = requiredDragon(player, npc, fixtureTag);
        if (dragon.isVehicle()) throw new IllegalStateException("Dismount fixture riders before far recall staging");
        ServerLevel level = player.serverLevel();
        int x = npc.blockPosition().getX() + 96;
        int z = npc.blockPosition().getZ();
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        level.getChunkAt(new BlockPos(x, ground, z));
        dragon.moveTo(x + 0.5D, ground + 12.0D, z + 0.5D, dragon.getYRot(), dragon.getXRot());
        dragon.setDeltaMovement(Vec3.ZERO);
        resetFallDistance(player, npc, dragon);
        npc.setStatus("dragon-fixture:far mod=" + modId + ",distanceMilli="
            + milli(dragon.distanceTo(player)));
    }

    private static void raise(
        ServerPlayer player,
        CodexNpcEntity npc,
        String fixtureTag,
        String modId
    ) {
        clearObstacle(player, npc);
        Entity dragon = requiredDragon(player, npc, fixtureTag);
        ServerLevel level = player.serverLevel();
        int x = (int) Math.floor(dragon.getX()) + 12;
        int z = (int) Math.floor(dragon.getZ()) + 12;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        level.getChunkAt(new BlockPos(x, ground, z));
        dragon.moveTo(x + 0.5D, ground + 20.0D, z + 0.5D, dragon.getYRot(), dragon.getXRot());
        dragon.setDeltaMovement(Vec3.ZERO);
        resetFallDistance(player, npc, dragon);
        npc.setStatus("dragon-fixture:raise mod=" + modId + ",yMilli=" + milli(dragon.getY()));
    }

    private static void setWander(
        CodexNpcEntity npc,
        ServerPlayer player,
        String fixtureTag,
        String modId,
        int command
    ) {
        Entity dragon = requiredDragon(player, npc, fixtureTag);
        if (!ReflectiveDragonAdapter.invokeVoid(dragon, "setCommand", command)) {
            throw new IllegalStateException("Fixture dragon command could not be staged");
        }
        npc.setStatus("dragon-fixture:wander mod=" + modId + ",command=" + command);
    }

    private static void coRide(
        ServerPlayer player,
        CodexNpcEntity npc,
        String fixtureTag,
        String modId
    ) {
        Entity dragon = requiredDragon(player, npc, fixtureTag);
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        // Each shared-ride phase validates its own lease lifecycle. An intentional
        // dismount in an earlier phase must not contaminate the next task's proof.
        DragonAutopilotControl.resetDiagnostics(player);
        DragonSharedRide.MountResult result = DragonSharedRide.mountTogether(player, npc, dragon, adapter);
        if (!result.successful()) throw new IllegalStateException("Fixture shared ride failed: " + result);
        resetFallDistance(player, npc, dragon);
        npc.setStatus("dragon-fixture:co-ride mod=" + modId + ",result="
            + result.name().toLowerCase(Locale.ROOT));
    }

    private static void dismountAll(ServerPlayer player, CodexNpcEntity npc) {
        for (Entity dragon : fixtureDragons(player)) safeDismount(player, npc, dragon);
        if (player.isPassenger() && isFixtureDragon(player.getVehicle())) player.stopRiding();
        if (npc.isPassenger() && isFixtureDragon(npc.getVehicle())) npc.stopRiding();
        player.fallDistance = 0.0F;
        npc.fallDistance = 0.0F;
        npc.setStatus("dragon-fixture:dismount player=0,npc=0");
    }

    private static void spawnCombatTarget(ServerPlayer player, CodexNpcEntity npc) {
        discardTagged(player, COMBAT_TAG);
        Pig target = EntityType.PIG.create(player.serverLevel());
        if (target == null) throw new IllegalStateException("Fixture combat target could not be created");
        Entity dragon = fixtureDragons(player).stream()
            .min(Comparator.comparingDouble(npc::distanceToSqr))
            .orElseThrow(() -> new IllegalStateException("Fixture dragon is unavailable"));
        Vec3 targetPosition = findCombatTargetPosition(player.serverLevel(), dragon, target);
        target.moveTo(targetPosition.x, targetPosition.y, targetPosition.z, 0.0F, 0.0F);
        target.addTag(COMBAT_TAG);
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPersistenceRequired();
        target.setInvulnerable(true);
        target.setHealth(Math.min(8.0F, target.getMaxHealth()));
        if (!player.serverLevel().addFreshEntity(target)) {
            throw new IllegalStateException("Fixture combat target was rejected by the world");
        }
        npc.setStatus("dragon-fixture:combat spawned=1,id=" + target.getUUID());
    }

    private static Vec3 findCombatTargetPosition(ServerLevel level, Entity dragon, LivingEntity target) {
        double reach = DragonActionPolicy.combatReach(
            dragon.getBbWidth(), dragon.getBbHeight(), target.getBbWidth()
        );
        double radius = Math.max(14.0D, reach + 4.0D);
        double baseLift = Math.max(4.0D, DragonTerrainAvoidancePolicy.clearance(
            dragon.getBbWidth(), dragon.getBbHeight()
        ));
        Vec3 start = dragon.position();
        for (int liftStep = 0; liftStep < 8; liftStep++) {
            double lift = baseLift + liftStep * 4.0D;
            for (int direction = 0; direction < 16; direction++) {
                double angle = Math.PI * 2.0D * direction / 16.0D;
                Vec3 candidate = new Vec3(
                    start.x + Math.cos(angle) * radius,
                    start.y + lift,
                    start.z + Math.sin(angle) * radius
                );
                BlockPos block = BlockPos.containing(candidate);
                if (!level.getWorldBorder().isWithinBounds(block)
                    || candidate.y < level.getMinBuildHeight() + 1.0D
                    || candidate.y + target.getBbHeight() >= level.getMaxBuildHeight() - 1.0D) continue;

                target.moveTo(candidate.x, candidate.y, candidate.z, 0.0F, 0.0F);
                if (!level.noCollision(target, target.getBoundingBox())) continue;

                Vec3 offset = candidate.subtract(start);
                double distance = offset.length();
                double travel = Math.max(0.0D, distance - reach * 0.80D);
                Vec3 directionVector = distance < 0.01D ? Vec3.ZERO : offset.scale(1.0D / distance);
                Vec3 attackPosition = directionVector.scale(travel);
                if (level.noCollision(dragon, dragon.getBoundingBox().move(attackPosition))) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Fixture combat target has no collision-free approach corridor");
    }

    private static void armCombatTarget(ServerPlayer player, CodexNpcEntity npc) {
        LivingEntity target = fixtureCombatTargets(player).stream()
            .min(Comparator.comparingDouble(player::distanceToSqr))
            .orElseThrow(() -> new IllegalStateException("Fixture combat target is unavailable"));
        target.setInvulnerable(false);
        target.setHealth(Math.min(8.0F, target.getMaxHealth()));
        player.setLastHurtMob(target);
        target.setLastHurtByMob(player);
        npc.setStatus("dragon-fixture:combat armed=1,id=" + target.getUUID());
    }

    private static void cleanupCombat(ServerPlayer player, CodexNpcEntity npc) {
        int removed = discardTagged(player, COMBAT_TAG) + discardTagged(player, FOOD_TAG);
        npc.setStatus("dragon-fixture:combat cleanup=" + removed);
    }

    private static void stageObstacle(
        ServerPlayer player,
        CodexNpcEntity npc,
        String fixtureTag,
        String modId
    ) {
        Entity dragon = requiredDragon(player, npc, fixtureTag);
        if (!DragonSharedRide.isCoRiding(player, npc, dragon)) {
            throw new IllegalStateException("Terrain fixture requires player and NPC shared riding");
        }
        clearObstacle(player, npc);
        CompoundTag state = requiredState(npc);
        ServerLevel level = player.serverLevel();
        int startX = (int) Math.floor(dragon.getX());
        int startZ = (int) Math.floor(dragon.getZ());
        int halfWidth = Math.max(3, Math.min(12, (int) Math.ceil(dragon.getBbWidth()) + 2));
        int wallHeight = Math.max(5, Math.min(20, (int) Math.ceil(dragon.getBbHeight()) + 4));
        int highest = level.getMinBuildHeight();
        for (int x = startX - 2; x <= startX + 28; x++) {
            for (int z = startZ - halfWidth - 1; z <= startZ + halfWidth + 1; z++) {
                highest = Math.max(highest,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        int startY = Math.max(highest + 12, (int) Math.floor(dragon.getY()) + 4);
        int maximumY = level.getMaxBuildHeight() - wallHeight - 4;
        while (startY <= maximumY && !corridorIsAir(
            level, startX, startY, startZ, halfWidth, wallHeight
        )) startY += 8;
        if (startY > maximumY) throw new IllegalStateException("No reversible air corridor is available");

        int wallMinX = startX + 8;
        int wallMaxX = startX + 9;
        List<Long> positions = new ArrayList<>();
        for (int x = wallMinX; x <= wallMaxX; x++) {
            for (int y = startY - 1; y <= startY + wallHeight; y++) {
                for (int z = startZ - halfWidth; z <= startZ + halfWidth; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (!level.getBlockState(position).isAir()) {
                        throw new IllegalStateException("Fixture obstacle corridor changed during staging");
                    }
                    positions.add(position.asLong());
                }
            }
        }
        var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (long packed : positions) level.setBlock(BlockPos.of(packed), leaves, 3);
        long[] packed = positions.stream().mapToLong(Long::longValue).toArray();
        state.putLongArray(OBSTACLE_POSITIONS, packed);
        state.putString("ObstacleDimension", level.dimension().location().toString());
        state.putInt("ObstacleWallMaxX", wallMaxX);
        state.putDouble("ObstacleTargetX", startX + 24.5D);
        state.putDouble("ObstacleTargetY", startY);
        state.putDouble("ObstacleTargetZ", startZ + 0.5D);
        dragon.moveTo(startX + 0.5D, startY, startZ + 0.5D, -90.0F, 0.0F);
        dragon.setDeltaMovement(Vec3.ZERO);
        resetFallDistance(player, npc, dragon);
        npc.setStatus("dragon-fixture:obstacle mod=" + modId
            + ",target=" + decimal(state.getDouble("ObstacleTargetX"))
            + ":" + decimal(state.getDouble("ObstacleTargetY"))
            + ":" + decimal(state.getDouble("ObstacleTargetZ"))
            + ",wallMaxX=" + wallMaxX + ",blocks=" + positions.size());
    }

    private static boolean corridorIsAir(
        ServerLevel level,
        int startX,
        int startY,
        int startZ,
        int halfWidth,
        int wallHeight
    ) {
        for (int x = startX - 2; x <= startX + 28; x++) {
            for (int y = startY - 2; y <= startY + wallHeight + 2; y++) {
                for (int z = startZ - halfWidth - 1; z <= startZ + halfWidth + 1; z++) {
                    BlockPos position = new BlockPos(x, y, z);
                    level.getChunkAt(position);
                    if (!level.getBlockState(position).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static int clearObstacle(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY);
        long[] positions = state.getLongArray(OBSTACLE_POSITIONS);
        if (positions.length == 0) return 0;
        ServerLevel level = player.serverLevel();
        int cleared = 0;
        for (long packed : positions) {
            BlockPos position = BlockPos.of(packed);
            if (level.getBlockState(position).is(Blocks.OAK_LEAVES)) {
                level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
                cleared++;
            }
        }
        state.remove(OBSTACLE_POSITIONS);
        state.remove("ObstacleDimension");
        state.remove("ObstacleWallMaxX");
        state.remove("ObstacleTargetX");
        state.remove("ObstacleTargetY");
        state.remove("ObstacleTargetZ");
        return cleared;
    }

    private static void inspect(
        ServerPlayer player,
        CodexNpcEntity npc,
        String fixtureTag,
        String expectedMod
    ) {
        Entity dragon = requiredDragon(player, npc, fixtureTag);
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        if (adapter == null || !expectedMod.equals(adapter.modId())) {
            throw new IllegalStateException("Fixture dragon adapter mismatch");
        }
        Object commandValue = ReflectiveDragonAdapter.invoke(dragon, "getCommand");
        int command = commandValue instanceof Number number ? number.intValue() : -1;
        boolean npcMounted = npc.getVehicle() == dragon;
        boolean playerMounted = player.getVehicle() == dragon;
        boolean coRiding = npcMounted && playerMounted;
        boolean firstPlayer = dragon.getFirstPassenger() == player;
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY);
        int obstacleBlocks = 0;
        for (long packed : state.getLongArray(OBSTACLE_POSITIONS)) {
            if (player.serverLevel().getBlockState(BlockPos.of(packed)).is(Blocks.OAK_LEAVES)) {
                obstacleBlocks++;
            }
        }
        DragonAutopilotControl.Diagnostics diagnostics = DragonAutopilotControl.diagnostics(player);
        int flags = DragonInspectionCodec.flags(
            dragon.isAlive(),
            adapter.isOwnedBy(dragon, player),
            npcMounted,
            playerMounted,
            coRiding,
            firstPlayer,
            adapter.isFlying(dragon),
            dragon.onGround(),
            adapter.isSaddled(dragon),
            adapter.isSeatLocked(dragon),
            adapter.isPlayerRideReady(dragon, player),
            DragonAutopilotControl.isActive(dragon, player),
            player.getRootVehicle() == dragon,
            diagnostics.beginCalled(),
            diagnostics.beginAccepted(),
            diagnostics.endCalled(),
            diagnostics.invalidated(),
            diagnostics.vehiclePacketSeen()
        );
        npc.setStatus(DragonInspectionCodec.encode(
            adapter.modId(),
            dragon.getUUID(),
            flags,
            command,
            milli(npc.getHealth()),
            milli(npc.fallDistance),
            milli(dragon.fallDistance),
            milli(dragon.distanceTo(player)),
            fixtureCombatTargets(player).size(),
            obstacleBlocks,
            milli(dragon.getX()),
            milli(dragon.getY()),
            milli(dragon.getZ())
        ));
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY).copy();
        int cleared = clearObstacle(player, npc);
        for (Entity dragon : fixtureDragons(player)) safeDismount(player, npc, dragon);
        if (player.isPassenger()) player.stopRiding();
        if (npc.isPassenger()) npc.stopRiding();
        int removed = discardTagged(player, BOOK_TAG)
            + discardTagged(player, SAINTS_TAG)
            + discardTagged(player, COMBAT_TAG)
            + discardTagged(player, FOOD_TAG);
        if (player.isPassenger()) player.stopRiding();
        if (npc.isPassenger()) npc.stopRiding();
        if (!state.isEmpty() && (!state.hasUUID("Owner") || state.getUUID("Owner").equals(player.getUUID()))) {
            restorePosition(player, state, "Player");
            restorePosition(npc, state, "Npc");
            restoreBinding(npc, state);
            if (state.contains("NpcHealth")) npc.setHealth(state.getFloat("NpcHealth"));
            if (state.contains("NpcFood")) npc.setFoodLevel(state.getInt("NpcFood"));
            if (state.contains("NpcSaturation")) npc.setSaturationLevel(state.getFloat("NpcSaturation"));
            if (state.contains("NpcExhaustion")) npc.setExhaustionLevel(state.getFloat("NpcExhaustion"));
            if (state.contains("PlayerInvulnerable")) {
                player.setInvulnerable(state.getBoolean("PlayerInvulnerable"));
            }
        }
        npc.getPersistentData().remove(STATE_KEY);
        npc.fallDistance = 0.0F;
        player.fallDistance = 0.0F;
        npc.setStatus("dragon-fixture:cleanup restored=1,entities=" + removed + ",blocks=" + cleared);
    }

    private static void restoreBinding(CodexNpcEntity npc, CompoundTag state) {
        CompoundTag current = new CompoundTag();
        npc.addAdditionalSaveData(current);
        current.remove("CodexBoundDragon");
        current.remove("CodexBoundDragonDimension");
        current.remove("CodexBoundDragonPosition");
        if (state.hasUUID("BoundDragon")) current.putUUID("CodexBoundDragon", state.getUUID("BoundDragon"));
        if (state.contains("BoundDragonDimension")) {
            current.putString("CodexBoundDragonDimension", state.getString("BoundDragonDimension"));
        }
        if (state.contains("BoundDragonPosition")) {
            current.putLong("CodexBoundDragonPosition", state.getLong("BoundDragonPosition"));
        }
        npc.readAdditionalSaveData(current);
    }

    private static void safeDismount(ServerPlayer player, CodexNpcEntity npc, Entity dragon) {
        if (player.getVehicle() != dragon && npc.getVehicle() != dragon) return;
        boolean playerMounted = player.getVehicle() == dragon;
        boolean npcMounted = npc.getVehicle() == dragon;
        DragonAutopilotControl.end(dragon, player);
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        Vec3 safe = adapter == null ? null : adapter.safeLandingTarget(dragon, player);
        if (safe == null && dragon.level() instanceof ServerLevel level) {
            int ground = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(dragon.getX()),
                (int) Math.floor(dragon.getZ())
            );
            safe = new Vec3(dragon.getX(), ground, dragon.getZ());
        }
        if (safe != null) dragon.moveTo(safe.x, safe.y, safe.z, dragon.getYRot(), 0.0F);
        resetFallDistance(player, npc, dragon);
        if (playerMounted) DragonDismountSafety.dismountPlayer(player, dragon);
        if (npcMounted) {
            if (adapter != null) adapter.dismount(npc);
            else DragonDismountSafety.dismountCompanion(npc);
        }
        DragonAutopilotControl.finishDismount(dragon, player);
        resetFallDistance(player, npc, dragon);
    }

    private static Entity requiredDragon(ServerPlayer player, CodexNpcEntity npc, String fixtureTag) {
        return taggedEntities(player, fixtureTag).stream()
            .filter(Entity::isAlive)
            .min(Comparator.comparingDouble(npc::distanceToSqr))
            .orElseThrow(() -> new IllegalStateException("Fixture dragon is unavailable: " + fixtureTag));
    }

    private static List<Entity> fixtureDragons(ServerPlayer player) {
        List<Entity> result = new ArrayList<>();
        result.addAll(taggedEntities(player, BOOK_TAG));
        result.addAll(taggedEntities(player, SAINTS_TAG));
        return result;
    }

    private static List<LivingEntity> fixtureCombatTargets(ServerPlayer player) {
        return taggedEntities(player, COMBAT_TAG).stream()
            .filter(LivingEntity.class::isInstance)
            .map(LivingEntity.class::cast)
            .filter(Entity::isAlive)
            .toList();
    }

    private static List<Entity> taggedEntities(ServerPlayer player, String tag) {
        List<Entity> result = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains(tag)) result.add(entity);
            }
        }
        return result;
    }

    private static int discardTagged(ServerPlayer player, String tag) {
        List<Entity> entities = taggedEntities(player, tag);
        for (Entity entity : entities) entity.discard();
        return entities.size();
    }

    private static boolean isFixtureDragon(Entity entity) {
        return entity != null && (entity.getTags().contains(BOOK_TAG) || entity.getTags().contains(SAINTS_TAG));
    }

    private static CompoundTag requiredState(CodexNpcEntity npc) {
        if (!npc.getPersistentData().contains(STATE_KEY)) {
            throw new IllegalStateException("Dragon fixture backup is unavailable");
        }
        return npc.getPersistentData().getCompound(STATE_KEY);
    }

    private static void putPosition(CompoundTag state, String prefix, Entity entity) {
        state.putString(prefix + "Dimension", entity.level().dimension().location().toString());
        state.putDouble(prefix + "X", entity.getX());
        state.putDouble(prefix + "Y", entity.getY());
        state.putDouble(prefix + "Z", entity.getZ());
        state.putFloat(prefix + "Yaw", entity.getYRot());
        state.putFloat(prefix + "Pitch", entity.getXRot());
    }

    private static void restorePosition(Entity entity, CompoundTag state, String prefix) {
        if (!state.contains(prefix + "X")) return;
        entity.moveTo(
            state.getDouble(prefix + "X"),
            state.getDouble(prefix + "Y"),
            state.getDouble(prefix + "Z"),
            state.getFloat(prefix + "Yaw"),
            state.getFloat(prefix + "Pitch")
        );
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;
    }

    private static void resetFallDistance(Entity player, Entity npc, Entity dragon) {
        player.fallDistance = 0.0F;
        npc.fallDistance = 0.0F;
        dragon.fallDistance = 0.0F;
    }

    private static long milli(double value) {
        return Math.round(value * 1_000.0D);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
