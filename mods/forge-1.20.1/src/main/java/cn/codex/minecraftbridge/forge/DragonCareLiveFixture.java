package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** Reversible, fixed-state acceptance fixtures for dragon care actions. */
final class DragonCareLiveFixture {
    private static final String STATE_KEY = "CodexDragonCareFixture";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final String OWNED_TAG = "CodexDragonCareOwned";
    private static final String WILD_TAG = "CodexDragonCareWild";
    private static final String EGG_TAG = "CodexDragonCareEgg";
    private static final String BOOK = "bookofdragons";
    private static final String SAINTS = "saintsdragons";
    static final String BOOK_EGG_ENTITY_ID = "bookofdragons:dragon_egg";

    private DragonCareLiveFixture() {
    }

    private static final class FixtureStepFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private FixtureStepFailure(String code, RuntimeException cause) {
            super(code, cause);
            this.code = code;
        }
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Dragon care fixture requires an in-world NPC");
        switch (mode) {
            case "setup-book" -> setup(player, npc, BOOK);
            case "setup-saints" -> setup(player, npc, SAINTS);
            case "stage-feed" -> stage(player, npc, "feed");
            case "inspect-feed" -> inspect(player, npc, "feed");
            case "stage-heal" -> stage(player, npc, "heal");
            case "inspect-heal" -> inspect(player, npc, "heal");
            case "stage-tame" -> stage(player, npc, "tame");
            case "inspect-tame" -> inspect(player, npc, "tame");
            case "stage-egg" -> stage(player, npc, "egg");
            case "inspect-egg" -> inspect(player, npc, "egg");
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown dragon care fixture mode");
        }
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc, String modId) {
        requireIdle(npc);
        cleanup(player, npc, false);
        requireIdle(npc);
        CompoundTag state = backup(player, npc, modId);
        npc.getPersistentData().put(STATE_KEY, state);
        try {
            player.setGameMode(GameType.SURVIVAL);
            if (npc.creativeResources()) {
                throw new IllegalStateException("Dragon care acceptance requires NPC survival material mode");
            }
            clearInventory(npc);
            Entity owned = spawnDragon(player, npc, modId, OWNED_TAG, 4.0D);
            Entity wild = spawnDragon(player, npc, modId, WILD_TAG, 9.0D);
            configureOwned(owned, player, modId);
            configureWild(wild, modId);
            state.putUUID("Owned", owned.getUUID());
            state.putUUID("Wild", wild.getUUID());
            if (BOOK.equals(modId)) {
                Entity egg = spawnEntity(player, npc, BOOK_EGG_ENTITY_ID, EGG_TAG, 14.0D);
                ReflectiveDragonAdapter.invokeVoid(egg, "setTotalHatchTime", 2_000);
                ReflectiveDragonAdapter.invokeVoid(egg, "setCurrentHatchTime", 1_500);
                ReflectiveDragonAdapter.invokeVoid(egg, "setRequirementProgress", 1);
                ReflectiveDragonAdapter.invokeVoid(egg, "setIsActivated", true);
                ReflectiveDragonAdapter.invokeVoid(egg, "setOwnerUUID", player.getUUID());
                state.putUUID("Egg", egg.getUUID());
            } else {
                BlockPos position = findEggSite(player.serverLevel(), npc.blockPosition());
                Block block = requiredBlock("saintsdragons:raevyx_egg");
                if (!player.serverLevel().setBlockAndUpdate(position, block.defaultBlockState())) {
                    throw new IllegalStateException("Saints Dragons egg block could not be placed");
                }
                state.putLong("EggPos", position.asLong());
                npc.getPersistentData().put(STATE_KEY, state);
                BlockEntity egg = player.serverLevel().getBlockEntity(position);
                if (egg == null) throw new IllegalStateException("Saints Dragons egg block entity is unavailable");
                ReflectiveDragonAdapter.invokeVoid(egg, "setHatchProgress", 0.25D);
                ReflectiveDragonAdapter.invokeVoid(egg, "setOwnerUUID", player.getUUID());
                ReflectiveDragonAdapter.invokeVoid(egg, "setHatchAdvancementOwnerUUID", player.getUUID());
                egg.setChanged();
            }
            npc.getPersistentData().put(STATE_KEY, state);
            npc.tasks().setStance(NpcTaskEngine.Stance.STAY);
            moveNpc(npc, owned.blockPosition().offset(-2, 0, 0));
            npc.setStatus("dragon-care:setup|" + modCode(modId));
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static CompoundTag backup(ServerPlayer player, CodexNpcEntity npc, String modId) {
        if (player.level() != npc.level()) {
            throw new IllegalStateException("Dragon care fixture requires owner and NPC in the same dimension");
        }
        if (player.isPassenger() || npc.isPassenger()) {
            throw new IllegalStateException("Dragon care fixture requires owner and NPC to be dismounted");
        }
        CompoundTag state = new CompoundTag();
        state.putInt("Version", 2);
        state.putString("Mod", modId);
        state.putUUID("Owner", player.getUUID());
        state.putString("PlayerDimension", dimensionId(player));
        state.putString("NpcDimension", dimensionId(npc));
        state.putString("GameType", player.gameMode.getGameModeForPlayer().getName());
        CompoundTag abilities = new CompoundTag();
        player.getAbilities().addSaveData(abilities);
        state.put("PlayerAbilities", abilities);
        state.putBoolean("PlayerInvulnerable", player.isInvulnerable());
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        state.putDouble("NpcX", npc.getX());
        state.putDouble("NpcY", npc.getY());
        state.putDouble("NpcZ", npc.getZ());
        state.putFloat("NpcYaw", npc.getYRot());
        state.putFloat("NpcPitch", npc.getXRot());
        state.putByte("NpcStance", npc.stance().id());
        state.put("Inventory", npc.inventory().serializeNBT());
        if (npc.boundDragonUuid() != null) state.putUUID("BoundDragon", npc.boundDragonUuid());
        if (!npc.boundDragonDimension().isBlank()) {
            state.putString("BoundDragonDimension", npc.boundDragonDimension());
        }
        if (npc.boundDragonPosition() != null) {
            state.putLong("BoundDragonPosition", npc.boundDragonPosition().asLong());
        }
        return state;
    }

    private static void stage(ServerPlayer player, CodexNpcEntity npc, String action) {
        requireIdle(npc);
        CompoundTag state = fixtureStep("stage-state", () -> requireState(player, npc));
        if (npc.creativeResources()) {
            throw new IllegalStateException("Dragon care acceptance requires NPC survival material mode");
        }
        String modId = state.getString("Mod");
        Entity target;
        String targetId;
        clearInventory(npc);
        if (action.equals("egg")) {
            if (BOOK.equals(modId)) {
                target = fixtureStep(
                    "stage-book-egg-target",
                    () -> requiredFixtureEntity(player, state, "Egg", EGG_TAG)
                );
                Entity bookEgg = target;
                fixtureStep("stage-book-egg-api", () -> {
                    boolean configured = ReflectiveDragonAdapter.invokeVoid(bookEgg, "setTotalHatchTime", 2_000)
                        && ReflectiveDragonAdapter.invokeVoid(bookEgg, "setCurrentHatchTime", 1_500)
                        && ReflectiveDragonAdapter.invokeVoid(bookEgg, "setRequirementProgress", 1)
                        && ReflectiveDragonAdapter.invokeVoid(bookEgg, "setIsActivated", true);
                    if (!configured) throw new IllegalStateException("Book dragon egg API is unavailable");
                });
                targetId = target.getUUID().toString();
                fixtureStep(
                    "stage-book-egg-position",
                    () -> moveNpc(npc, bookEgg.blockPosition().offset(-2, 0, 0))
                );
            } else {
                BlockPos position = BlockPos.of(state.getLong("EggPos"));
                BlockEntity egg = player.serverLevel().getBlockEntity(position);
                if (egg == null || !blockId(player.serverLevel(), position).equals("saintsdragons:raevyx_egg")) {
                    throw new IllegalStateException("Saints Dragons egg fixture is unavailable");
                }
                ReflectiveDragonAdapter.invokeVoid(egg, "setHatchProgress", 0.25D);
                ReflectiveDragonAdapter.invokeVoid(egg, "setOwnerUUID", player.getUUID());
                egg.setChanged();
                target = null;
                targetId = "saintsdragons:raevyx_egg";
                moveNpc(npc, position.offset(-2, 0, 0));
            }
        } else {
            boolean tame = action.equals("tame");
            target = requiredFixtureEntity(
                player,
                state,
                tame ? "Wild" : "Owned",
                tame ? WILD_TAG : OWNED_TAG
            );
            if (tame) configureWild(target, modId);
            if (action.equals("feed")) prepareFeed(target, modId);
            if (action.equals("heal")) prepareHeal(target, modId);
            Item food = BOOK.equals(modId)
                ? Items.CHICKEN
                : action.equals("heal")
                    ? requiredItem("saintsdragons:hearty_dragon_meal")
                    : Items.SALMON;
            insert(npc, new ItemStack(food, 4));
            targetId = target.getUUID().toString();
            moveNpc(npc, target.blockPosition().offset(-2, 0, 0));
        }
        state.putString("Action", action);
        state.putString("Target", targetId);
        Entity baselineTarget = target;
        fixtureStep(
            "stage-baseline",
            () -> captureBaseline(player, npc, state, action, baselineTarget)
        );
        String stagedTargetId = targetId;
        fixtureStep("stage-commit", () -> {
            npc.getPersistentData().put(STATE_KEY, state);
            npc.setStatus("dragon-care:s|" + modCode(modId) + "|" + actionCode(action) + "|" + stagedTargetId);
        });
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc, String action) {
        CompoundTag state = requireState(player, npc);
        if (!action.equals(state.getString("Action"))) {
            throw new IllegalStateException("Dragon care inspection does not match the staged action");
        }
        String modId = state.getString("Mod");
        Entity target = null;
        String identity;
        boolean present;
        double eggProgress = -1.0D;
        if (action.equals("egg") && SAINTS.equals(modId)) {
            BlockPos position = BlockPos.of(state.getLong("EggPos"));
            BlockEntity egg = player.serverLevel().getBlockEntity(position);
            identity = blockId(player.serverLevel(), position) + "@" + position.asLong();
            present = egg != null && blockId(player.serverLevel(), position).equals("saintsdragons:raevyx_egg");
            eggProgress = eggProgress(egg);
        } else {
            String key = action.equals("tame") ? "Wild" : action.equals("egg") ? "Egg" : "Owned";
            String tag = action.equals("tame") ? WILD_TAG : action.equals("egg") ? EGG_TAG : OWNED_TAG;
            target = findEntity(player, state.getUUID(key), tag);
            identity = target == null ? "missing" : target.getUUID().toString();
            present = target != null && target.isAlive();
            if (action.equals("egg")) eggProgress = eggProgress(target);
        }
        int items = countItems(npc);
        double health = health(target);
        double food = food(target);
        double happiness = happiness(target);
        boolean owned = target != null && ownedBy(target, player);
        int consumed = Math.max(0, state.getInt("BeforeItems") - items);
        int healthDelta = milliDelta(state.getDouble("BeforeHealth"), health);
        int foodDelta = milliDelta(state.getDouble("BeforeFood"), food);
        int happinessDelta = milliDelta(state.getDouble("BeforeHappiness"), happiness);
        int eggDelta = milliDelta(state.getDouble("BeforeEgg"), eggProgress);
        int same = identity.equals(state.getString("BeforeIdentity")) ? 1 : 0;
        int ownershipChanged = !state.getBoolean("BeforeOwned") && owned ? 1 : 0;
        npc.setStatus("dragon-care:i|" + modCode(modId)
            + "|" + actionCode(action)
            + "|" + consumed
            + "|" + healthDelta
            + "|" + foodDelta
            + "|" + happinessDelta
            + "|" + (owned ? 1 : 0)
            + "|" + (present ? 1 : 0)
            + "|" + eggDelta
            + "|" + same
            + "|" + ownershipChanged);
    }

    private static void captureBaseline(
        ServerPlayer player,
        CodexNpcEntity npc,
        CompoundTag state,
        String action,
        Entity target
    ) {
        state.putInt("BeforeItems", countItems(npc));
        state.putDouble("BeforeHealth", health(target));
        state.putDouble("BeforeFood", food(target));
        state.putDouble("BeforeHappiness", happiness(target));
        state.putBoolean("BeforeOwned", target != null && ownedBy(target, player));
        if (action.equals("egg") && SAINTS.equals(state.getString("Mod"))) {
            BlockPos position = BlockPos.of(state.getLong("EggPos"));
            BlockEntity egg = player.serverLevel().getBlockEntity(position);
            state.putString("BeforeIdentity", blockId(player.serverLevel(), position) + "@" + position.asLong());
            state.putDouble("BeforeEgg", eggProgress(egg));
        } else {
            state.putString("BeforeIdentity", target == null ? "missing" : target.getUUID().toString());
            state.putDouble("BeforeEgg", action.equals("egg") ? eggProgress(target) : -1.0D);
        }
    }

    private static void prepareFeed(Entity dragon, String modId) {
        if (BOOK.equals(modId)) {
            Object needs = ReflectiveDragonAdapter.invoke(dragon, "getNeedsSystem");
            ReflectiveDragonAdapter.invokeVoid(needs, "setFoodLevel", 20);
            ReflectiveDragonAdapter.invokeVoid(needs, "setSaturationLevel", 0.0F);
        } else {
            ReflectiveDragonAdapter.invokeVoid(dragon, "setHunger", 20);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setHappiness", 20);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setFeedingCooldown", 0);
        }
        if (dragon instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
    }

    private static void prepareHeal(Entity dragon, String modId) {
        prepareFeed(dragon, modId);
        if (BOOK.equals(modId)) {
            Object needs = ReflectiveDragonAdapter.invoke(dragon, "getNeedsSystem");
            ReflectiveDragonAdapter.invokeVoid(needs, "setFoodLevel", 65);
            ReflectiveDragonAdapter.invokeVoid(needs, "setSaturationLevel", 0.0F);
        }
        if (dragon instanceof LivingEntity living) {
            living.setHealth(Math.max(1.0F, living.getMaxHealth() * 0.4F));
        }
    }

    private static Entity spawnDragon(
        ServerPlayer player,
        CodexNpcEntity npc,
        String modId,
        String tag,
        double offset
    ) {
        String entityId = BOOK.equals(modId) ? "bookofdragons:deadlynadder" : "saintsdragons:raevyx";
        Entity dragon = spawnEntity(player, npc, entityId, tag, offset);
        if (dragon instanceof AgeableMob ageable) ageable.setAge(0);
        if (dragon instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
        return dragon;
    }

    private static Entity spawnEntity(
        ServerPlayer player,
        CodexNpcEntity npc,
        String entityId,
        String tag,
        double offset
    ) {
        EntityType<?> type = EntityType.byString(entityId).orElse(null);
        if (type == null) throw new IllegalStateException("Fixture entity is unavailable: " + entityId);
        Entity entity = type.create(player.serverLevel());
        if (entity == null) throw new IllegalStateException("Fixture entity could not be created: " + entityId);
        entity.moveTo(npc.getX() + offset, npc.getY(), npc.getZ(), npc.getYRot(), 0.0F);
        entity.addTag(tag);
        if (entity instanceof Mob mob) {
            ForgeEventFactory.onFinalizeSpawn(
                mob,
                player.serverLevel(),
                player.serverLevel().getCurrentDifficultyAt(entity.blockPosition()),
                MobSpawnType.COMMAND,
                null,
                null
            );
            mob.setPersistenceRequired();
        }
        if (!player.serverLevel().addFreshEntity(entity)) {
            throw new IllegalStateException("Fixture entity was rejected by the world: " + entityId);
        }
        return entity;
    }

    private static void configureOwned(Entity dragon, ServerPlayer player, String modId) {
        if (dragon instanceof TamableAnimal tameable) {
            tameable.setOwnerUUID(player.getUUID());
            tameable.setTame(true);
            tameable.setOrderedToSit(false);
        }
        ReflectiveDragonAdapter.invokeVoid(dragon, "setOwnerUUID", player.getUUID());
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTame", true);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTamed", true);
        if (BOOK.equals(modId)) {
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualCompleted", true);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setAwaitingTamingRitual", false);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualTimer", 0);
        }
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        if (adapter == null || !adapter.isOwnedBy(dragon, player)) {
            throw new IllegalStateException("Fixture dragon ownership could not be established");
        }
    }

    private static void configureWild(Entity dragon, String modId) {
        if (dragon instanceof TamableAnimal tameable) {
            tameable.setOrderedToSit(false);
            tameable.setOwnerUUID(null);
            tameable.setTame(false);
        }
        ReflectiveDragonAdapter.invokeVoid(dragon, "setOwnerUUID", (Object) null);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTame", false);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTamed", false);
        if (BOOK.equals(modId)) {
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualCompleted", false);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setAwaitingTamingRitual", false);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualTimer", 0);
        }
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY).copy();
        if (state.isEmpty()) {
            discardTagged(player);
            if (report) npc.setNextLiveFixtureAckStatus("dragon-care:cleanup|none");
            return;
        }
        if (!state.hasUUID("Owner") || !state.getUUID("Owner").equals(player.getUUID())) {
            throw new IllegalStateException("Dragon care fixture owner changed before cleanup");
        }
        requireIdle(npc);
        requireOriginalDimensions(player, npc, state);
        if (state.contains("EggPos")) {
            BlockPos position = BlockPos.of(state.getLong("EggPos"));
            String eggBlock = blockId(player.serverLevel(), position);
            if (!eggBlock.equals("saintsdragons:raevyx_egg")
                && !player.serverLevel().getBlockState(position).isAir()) {
                throw new IllegalStateException("Dragon care egg site changed before cleanup");
            }
        }
        discardTagged(player);
        if (state.contains("EggPos")) {
            BlockPos position = BlockPos.of(state.getLong("EggPos"));
            if (blockId(player.serverLevel(), position).equals("saintsdragons:raevyx_egg")) {
                player.serverLevel().setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            }
        }
        GameType gameType = GameType.byName(state.getString("GameType"), GameType.SURVIVAL);
        player.gameMode.changeGameModeForPlayer(gameType == null ? GameType.SURVIVAL : gameType);
        if (state.contains("PlayerAbilities", Tag.TAG_COMPOUND)) {
            player.getAbilities().loadSaveData(state.getCompound("PlayerAbilities"));
        }
        player.onUpdateAbilities();
        player.setInvulnerable(state.getBoolean("PlayerInvulnerable"));
        if (state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) {
            npc.load(state.getCompound(SAVED_NPC_KEY).copy());
            npc.getNavigation().stop();
        } else {
            clearInventory(npc);
            npc.inventory().deserializeNBT(state.getCompound("Inventory"));
            npc.teleportTo(state.getDouble("NpcX"), state.getDouble("NpcY"), state.getDouble("NpcZ"));
            npc.setYRot(state.getFloat("NpcYaw"));
            npc.setXRot(state.getFloat("NpcPitch"));
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.tasks().setStance(NpcTaskEngine.Stance.fromId(state.getByte("NpcStance")));
            restoreBinding(npc, state);
            npc.getPersistentData().remove(STATE_KEY);
        }
        if (report) npc.setNextLiveFixtureAckStatus(cleanupEvidence(player, npc, state));
    }

    private static String cleanupEvidence(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        CompoundTag abilities = new CompoundTag();
        player.getAbilities().addSaveData(abilities);
        boolean dimensions = state.getString("PlayerDimension").equals(dimensionId(player))
            && state.getString("NpcDimension").equals(dimensionId(npc));
        boolean gameMode = state.getString("GameType")
            .equals(player.gameMode.getGameModeForPlayer().getName());
        boolean abilityState = !state.contains("PlayerAbilities", Tag.TAG_COMPOUND)
            || abilities.equals(state.getCompound("PlayerAbilities"));
        boolean invulnerability = player.isInvulnerable() == state.getBoolean("PlayerInvulnerable");
        boolean npcState = !state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)
            || npc.saveWithoutId(new CompoundTag()).equals(state.getCompound(SAVED_NPC_KEY));
        return "dragon-care:cleanup|restored"
            + "|dim=" + bit(dimensions)
            + "|gm=" + bit(gameMode)
            + "|ability=" + bit(abilityState)
            + "|inv=" + bit(invulnerability)
            + "|npc=" + bit(npcState);
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
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

    private static CompoundTag requireState(ServerPlayer player, CodexNpcEntity npc) {
        if (!npc.getPersistentData().contains(STATE_KEY)) {
            throw new IllegalStateException("Dragon care fixture has not been set up");
        }
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY).copy();
        if (!state.hasUUID("Owner") || !state.getUUID("Owner").equals(player.getUUID())) {
            throw new IllegalStateException("Dragon care fixture owner changed");
        }
        return state;
    }

    private static void requireIdle(CodexNpcEntity npc) {
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank())
            || npc.tasks().pausedTaskCount() > 0
            || !"idle".equals(npc.tasks().schedulerLifecycle())
            || npc.tasks().observableTaskQueue().size() > 0
            || npc.isManagedEating()) {
            throw new IllegalStateException("Finish NPC tasks before changing the dragon care fixture");
        }
    }

    static String cleanupDimensionRefusalReason(
        String playerDimension,
        String npcDimension,
        String currentPlayerDimension,
        String currentNpcDimension
    ) {
        if (playerDimension == null || playerDimension.isBlank()
            || npcDimension == null || npcDimension.isBlank()) {
            return "Dragon care fixture dimension snapshot is missing";
        }
        if (!playerDimension.equals(currentPlayerDimension)
            || !npcDimension.equals(currentNpcDimension)) {
            return "Return owner and NPC to their original dimensions before dragon care cleanup";
        }
        return "";
    }

    static String failureCode(Throwable error) {
        if (error instanceof FixtureStepFailure stepFailure) return stepFailure.code;
        String message = error == null || error.getMessage() == null
            ? ""
            : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("entity is unavailable")) return "entity-unavailable";
        if (message.contains("survival material mode")) return "survival-mode-required";
        if (message.contains("ownership could not be established")) return "ownership-failed";
        if (message.contains("egg site") || message.contains("egg block")) return "egg-site-failed";
        if (message.contains("finish npc tasks")) return "npc-not-idle";
        if (message.contains("same dimension") || message.contains("original dimensions")) {
            return "dimension-mismatch";
        }
        if (message.contains("dismounted")) return "passenger-active";
        return "fixture-failed";
    }

    private static <T> T fixtureStep(String code, Supplier<T> action) {
        try {
            return action.get();
        } catch (FixtureStepFailure error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FixtureStepFailure(code, error);
        }
    }

    private static void fixtureStep(String code, Runnable action) {
        fixtureStep(code, () -> {
            action.run();
            return null;
        });
    }

    private static void requireOriginalDimensions(ServerPlayer player, CodexNpcEntity npc, CompoundTag state) {
        String refusal = cleanupDimensionRefusalReason(
            state.getString("PlayerDimension"),
            state.getString("NpcDimension"),
            dimensionId(player),
            dimensionId(npc)
        );
        if (!refusal.isEmpty()) throw new IllegalStateException(refusal);
    }

    private static String dimensionId(Entity entity) {
        return entity.level().dimension().location().toString();
    }

    private static BlockPos findEggSite(ServerLevel level, BlockPos near) {
        for (int radius = 14; radius <= 28; radius += 2) {
            for (int direction = 0; direction < 16; direction++) {
                double angle = Math.PI * 2.0D * direction / 16.0D;
                int x = near.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = near.getZ() + (int) Math.round(Math.sin(angle) * radius);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos position = new BlockPos(x, y, z);
                if (level.getWorldBorder().isWithinBounds(position)
                    && level.getBlockState(position).isAir()
                    && level.getBlockState(position.above()).isAir()
                    && level.getFluidState(position).isEmpty()) return position;
            }
        }
        throw new IllegalStateException("No reversible Saints Dragons egg site was found");
    }

    private static Entity requiredFixtureEntity(
        ServerPlayer player,
        CompoundTag state,
        String stateKey,
        String tag
    ) {
        UUID id = state.hasUUID(stateKey) ? state.getUUID(stateKey) : null;
        Entity entity = findEntity(player, id, tag);
        if (entity == null) entity = findUniqueTaggedEntity(player, tag);
        if (entity == null) throw new IllegalStateException("Dragon care fixture entity is unavailable: " + tag);
        state.putUUID(stateKey, entity.getUUID());
        return entity;
    }

    private static Entity findUniqueTaggedEntity(ServerPlayer player, String tag) {
        Entity found = null;
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!entity.isAlive() || !entity.getTags().contains(tag)) continue;
                if (found != null && found != entity) {
                    throw new IllegalStateException("Dragon care fixture entity tag is ambiguous: " + tag);
                }
                found = entity;
            }
        }
        return found;
    }

    private static Entity findEntity(ServerPlayer player, UUID id, String tag) {
        if (id == null) return null;
        for (ServerLevel level : player.server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive() && entity.getTags().contains(tag)) return entity;
        }
        return null;
    }

    private static int discardTagged(ServerPlayer player) {
        List<Entity> entities = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains(OWNED_TAG)
                    || entity.getTags().contains(WILD_TAG)
                    || entity.getTags().contains(EGG_TAG)) entities.add(entity);
            }
        }
        for (Entity entity : entities) entity.discard();
        return entities.size();
    }

    private static Block requiredBlock(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        Block block = id == null ? null : ForgeRegistries.BLOCKS.getValue(id);
        if (block == null || block == Blocks.AIR) throw new IllegalStateException("Fixture block is unavailable: " + value);
        return block;
    }

    private static Item requiredItem(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) throw new IllegalStateException("Fixture item is unavailable: " + value);
        return item;
    }

    private static void clearInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void insert(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = npc.insert(stack.copy());
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC inventory rejected dragon care fixture items");
    }

    private static int countItems(CodexNpcEntity npc) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            count += npc.inventory().getStackInSlot(slot).getCount();
        }
        return count;
    }

    private static void moveNpc(CodexNpcEntity npc, BlockPos position) {
        npc.teleportTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D);
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
    }

    private static boolean ownedBy(Entity dragon, ServerPlayer player) {
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        return adapter != null && adapter.isOwnedBy(dragon, player);
    }

    private static double health(Entity dragon) {
        return dragon instanceof LivingEntity living ? living.getHealth() : -1.0D;
    }

    private static double food(Entity dragon) {
        if (dragon == null) return -1.0D;
        double hunger = number(ReflectiveDragonAdapter.invoke(dragon, "getHunger"));
        if (hunger >= 0.0D) return hunger;
        Object needs = ReflectiveDragonAdapter.invoke(dragon, "getNeedsSystem");
        double level = number(ReflectiveDragonAdapter.invoke(needs, "getFoodLevel"));
        double saturation = number(ReflectiveDragonAdapter.invoke(needs, "getSaturationLevel"));
        return level >= 0.0D && saturation >= 0.0D ? level + saturation : level;
    }

    private static double happiness(Entity dragon) {
        return dragon == null ? -1.0D : number(ReflectiveDragonAdapter.invoke(dragon, "getHappiness"));
    }

    private static double eggProgress(Object egg) {
        if (egg == null) return -1.0D;
        double current = number(ReflectiveDragonAdapter.invoke(egg, "getCurrentHatchTime"));
        double total = number(ReflectiveDragonAdapter.invoke(egg, "getTotalHatchTime"));
        if (current >= 0.0D && total >= 0.0D) return Math.max(0.0D, total - current);
        return number(ReflectiveDragonAdapter.invoke(egg, "getHatchProgress"));
    }

    private static double number(Object value) {
        if (!(value instanceof Number number)) return -1.0D;
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : -1.0D;
    }

    private static int milliDelta(double before, double after) {
        if (!Double.isFinite(before) || !Double.isFinite(after) || before < 0.0D || after < 0.0D) return 0;
        return (int) Math.round((after - before) * 1_000.0D);
    }

    private static String blockId(ServerLevel level, BlockPos position) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.getBlockState(position).getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static String modCode(String modId) {
        return BOOK.equals(modId) ? "b" : "s";
    }

    private static String actionCode(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "feed" -> "f";
            case "heal" -> "h";
            case "tame" -> "t";
            case "egg" -> "e";
            default -> throw new IllegalArgumentException("Unknown dragon care action");
        };
    }
}
