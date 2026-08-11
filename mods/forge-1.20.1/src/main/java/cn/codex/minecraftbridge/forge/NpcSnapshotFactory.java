package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.client.BridgeConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class NpcSnapshotFactory {
    private long sequence;

    public JsonObject capture(CodexNpcEntity npc, ServerPlayer owner, BridgeConfig config) {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("sequence", ++sequence);
        snapshot.addProperty("capturedAt", Instant.now().toString());
        snapshot.addProperty("worldId", owner.getServer() == null ? "singleplayer" : owner.getServer().getWorldData().getLevelName());
        snapshot.addProperty("dimension", npc.level().dimension().location().toString());
        snapshot.add("position", vector(npc.getX(), npc.getY(), npc.getZ()));
        snapshot.add("ownerPosition", vector(owner.getX(), owner.getY(), owner.getZ()));
        snapshot.addProperty("ownerDistance", npc.distanceTo(owner));
        snapshot.addProperty("yaw", npc.getYRot());
        snapshot.addProperty("pitch", npc.getXRot());
        snapshot.addProperty("health", Math.max(0, npc.getHealth()));
        snapshot.addProperty("maxHealth", npc.getMaxHealth());
        snapshot.addProperty("food", npc.foodLevel());
        snapshot.addProperty("maxFood", 20);
        snapshot.addProperty("saturation", npc.saturationLevel());
        snapshot.addProperty("exhaustion", npc.exhaustionLevel());
        snapshot.addProperty("materialMode", npc.creativeResources() ? "creative" : "survival");
        snapshot.addProperty("naturalRegenerationEnabled", npc.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION));
        snapshot.addProperty("canNaturalRegenerate", npc.level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION)
            && HungerPolicy.canNaturallyRegenerate(npc.foodLevel(), npc.getHealth(), npc.getMaxHealth()));
        snapshot.addProperty("automaticEating", npc.isManagedEating() && !npc.tasks().isExplicitEating());
        snapshot.addProperty("managedEating", npc.isManagedEating());
        snapshot.addProperty("usingItem", npc.isUsingItem());
        snapshot.addProperty("air", Math.max(0, npc.getAirSupply()));
        snapshot.addProperty("maxAir", npc.getMaxAirSupply());
        snapshot.addProperty("armor", npc.getArmorValue());
        snapshot.addProperty("absorption", npc.getAbsorptionAmount());
        snapshot.addProperty("gameMode", owner.gameMode.getGameModeForPlayer().getName());
        snapshot.addProperty("timeOfDay", Math.max(0, npc.level().getDayTime()));
        snapshot.addProperty("weather", npc.level().isThundering() ? "thunder" : npc.level().isRaining() ? "rain" : "clear");
        snapshot.add("inventory", inventory(npc));
        snapshot.add("recentItemTransactions", itemTransactions(npc));
        snapshot.add("effects", effects(npc));
        snapshot.add("nearbyEntities", nearbyEntities(npc, owner, config));
        snapshot.addProperty("status", npc.status());
        if (npc.liveFixtureSequence() > 0) {
            JsonObject acknowledgement = new JsonObject();
            acknowledgement.addProperty("sequence", npc.liveFixtureSequence());
            acknowledgement.addProperty("suite", npc.liveFixtureSuite());
            acknowledgement.addProperty("mode", npc.liveFixtureMode());
            acknowledgement.addProperty("status", npc.liveFixtureStatus());
            snapshot.add("liveFixtureAck", acknowledgement);
        }
        snapshot.addProperty("npcEntityUuid", npc.getUUID().toString());
        snapshot.addProperty("npcDowned", npc.isDowned());
        snapshot.addProperty("stance", npc.stance().name().toLowerCase());
        snapshot.addProperty("activeTaskId", npc.tasks().activeTaskId());
        snapshot.addProperty("activeTaskKind", npc.tasks().activeTaskKind());
        snapshot.addProperty("activeTaskProgress", npc.tasks().activeTaskProgress());
        snapshot.addProperty("pausedTaskCount", npc.tasks().pausedTaskCount());
        snapshot.addProperty("activeTaskPriority", npc.tasks().activeTaskPriority());
        snapshot.addProperty("taskSchedulerLifecycle", npc.tasks().schedulerLifecycle());
        snapshot.add("taskQueue", npc.tasks().observableTaskQueue());
        JsonObject miningState = miningState(npc);
        if (miningState != null) snapshot.add("miningState", miningState);
        snapshot.add("homeState", homeState(owner));
        JsonObject dragonState = dragonState(npc, owner);
        if (dragonState != null) snapshot.add("dragonState", dragonState);
        return snapshot;
    }

    private JsonObject miningState(CodexNpcEntity npc) {
        NpcTaskEngine.DeepMiningDiagnostics diagnostics = npc.tasks().deepMiningDiagnostics();
        if (diagnostics.phase().isBlank()) return null;
        JsonObject result = new JsonObject();
        result.addProperty("phase", diagnostics.phase());
        result.addProperty("itemId", diagnostics.itemId());
        result.addProperty("targetY", diagnostics.targetY());
        result.addProperty("staircaseStep", diagnostics.staircaseStep());
        result.addProperty("branchIndex", diagnostics.branchIndex());
        result.addProperty("branchProgress", diagnostics.branchProgress());
        result.addProperty("regionIndex", diagnostics.regionIndex());
        result.addProperty("brokenBlocks", diagnostics.brokenBlocks());
        result.addProperty("placedTorches", diagnostics.placedTorches());
        if (diagnostics.entrance() != null) {
            result.add("entrance", vector(
                diagnostics.entrance().getX(),
                diagnostics.entrance().getY(),
                diagnostics.entrance().getZ()
            ));
        }
        if (diagnostics.lastSafeStand() != null) {
            result.add("lastSafeStand", vector(
                diagnostics.lastSafeStand().getX(),
                diagnostics.lastSafeStand().getY(),
                diagnostics.lastSafeStand().getZ()
            ));
        }
        return result;
    }

    private JsonObject homeState(ServerPlayer owner) {
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(owner);
        JsonObject result = new JsonObject();
        result.addProperty("dimension", home.dimension().location().toString());
        result.add("position", vector(home.position().getX(), home.position().getY(), home.position().getZ()));
        result.addProperty("temporary", home.temporary());
        return result;
    }

    private JsonObject dragonState(CodexNpcEntity npc, ServerPlayer owner) {
        Entity dragon = npc.getVehicle();
        boolean mounted = dragon != null;
        if (dragon == null) {
            UUID bound = npc.boundDragonUuid();
            if (bound != null && npc.level().getServer() != null) {
                for (net.minecraft.server.level.ServerLevel level : npc.level().getServer().getAllLevels()) {
                    dragon = level.getEntity(bound);
                    if (dragon != null) break;
                }
            }
        }
        if (dragon == null) return null;
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        if (adapter == null) return null;
        JsonObject result = new JsonObject();
        result.addProperty("modId", adapter.modId());
        result.addProperty("entityId", dragon.getUUID().toString());
        result.addProperty("name", dragon.getDisplayName().getString());
        result.addProperty("mounted", mounted);
        result.addProperty("playerMounted", owner.getVehicle() == dragon);
        result.addProperty("coRiding", mounted && owner.getVehicle() == dragon);
        boolean autopilot = DragonAutopilotControl.isActive(dragon, owner);
        result.addProperty("autopilot", autopilot);
        result.addProperty("playerInputLocked", autopilot);
        result.addProperty("controlMode", autopilot
            ? "npc-autopilot"
            : owner.getVehicle() == dragon ? "player" : "dragon-ai");
        result.addProperty("ownedByPlayer", adapter.isOwnedBy(dragon, owner));
        result.addProperty("flying", adapter.isFlying(dragon));
        result.addProperty("saddled", adapter.isSaddled(dragon));
        result.addProperty("seatLocked", adapter.isSeatLocked(dragon));
        result.addProperty("playerRideReady", adapter.isPlayerRideReady(dragon, owner));
        result.addProperty("sharedRideEnabled", adapter.isOwnedBy(dragon, owner)
            && npc.boundDragonUuid() != null
            && npc.boundDragonUuid().equals(dragon.getUUID()));
        if (dragon instanceof LivingEntity living) {
            result.addProperty("health", Math.max(0, living.getHealth()));
            result.addProperty("maxHealth", living.getMaxHealth());
        }
        return result;
    }

    private JsonArray inventory(CodexNpcEntity npc) {
        JsonArray result = new JsonArray();
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.addProperty("displayName", stack.getHoverName().getString());
            item.addProperty("count", stack.getCount());
            item.addProperty("slot", slot);
            item.addProperty("slotType", slotType(slot));
            item.addProperty("damage", stack.getDamageValue());
            item.addProperty("maxDamage", stack.getMaxDamage());
            item.addProperty("remainingDurability", stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : 0);
            item.addProperty("customName", stack.hasCustomHoverName());
            item.addProperty("foil", stack.hasFoil());
            item.addProperty("enchantable", stack.isEnchantable());
            JsonArray tags = new JsonArray();
            stack.getTags().limit(64).forEach(tag -> tags.add(tag.location().toString()));
            item.add("tags", tags);
            JsonArray enchantments = new JsonArray();
            EnchantmentHelper.getEnchantments(stack).forEach((enchantment, level) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", BuiltInRegistries.ENCHANTMENT.getKey(enchantment).toString());
                entry.addProperty("level", level);
                entry.addProperty("curse", enchantment.isCurse());
                enchantments.add(entry);
            });
            item.add("enchantments", enchantments);
            result.add(item);
        }
        return result;
    }

    private JsonArray itemTransactions(CodexNpcEntity npc) {
        JsonArray result = new JsonArray();
        for (ItemTransactionLedger.Entry entry : npc.recentItemTransactions()) {
            JsonObject value = new JsonObject();
            value.addProperty("sequence", entry.sequence());
            value.addProperty("gameTime", entry.gameTime());
            if (!entry.taskId().isBlank()) value.addProperty("taskId", entry.taskId());
            value.addProperty("action", entry.action());
            value.addProperty("itemId", entry.itemId());
            value.addProperty("delta", entry.delta());
            value.addProperty("balanceAfter", entry.balanceAfter());
            result.add(value);
        }
        return result;
    }

    private JsonArray effects(CodexNpcEntity npc) {
        JsonArray result = new JsonArray();
        for (MobEffectInstance effect : npc.getActiveEffects()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString());
            value.addProperty("amplifier", effect.getAmplifier());
            value.addProperty("duration", effect.getDuration());
            value.addProperty("ambient", effect.isAmbient());
            result.add(value);
        }
        return result;
    }

    private String slotType(int slot) {
        return switch (slot) {
            case CodexNpcEntity.MAIN_HAND_SLOT -> "main_hand";
            case CodexNpcEntity.OFF_HAND_SLOT -> "off_hand";
            case CodexNpcEntity.HEAD_SLOT -> "head";
            case CodexNpcEntity.CHEST_SLOT -> "chest";
            case CodexNpcEntity.LEGS_SLOT -> "legs";
            case CodexNpcEntity.FEET_SLOT -> "feet";
            default -> "backpack";
        };
    }

    private JsonArray nearbyEntities(CodexNpcEntity npc, ServerPlayer owner, BridgeConfig config) {
        JsonArray result = new JsonArray();
        npc.level().getEntities(npc, npc.getBoundingBox().inflate(config.observeRadius), Entity::isAlive).stream()
            .sorted(Comparator.comparingDouble(npc::distanceToSqr))
            .limit(64)
            .forEach(entity -> result.add(entity(entity, npc, owner, config)));
        return result;
    }

    private JsonObject entity(Entity entity, CodexNpcEntity npc, ServerPlayer owner, BridgeConfig config) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        JsonObject result = new JsonObject();
        result.addProperty("id", entity.getUUID().toString());
        result.addProperty("type", type);
        result.addProperty("name", entity.getDisplayName().getString());
        result.add("position", vector(entity.getX(), entity.getY(), entity.getZ()));
        result.addProperty("distance", npc.distanceTo(entity));
        if (entity instanceof LivingEntity living) result.addProperty("health", Math.max(0, living.getHealth()));
        else result.add("health", null);
        result.addProperty("disposition", disposition(entity, owner, type, config));
        return result;
    }

    private String disposition(Entity entity, ServerPlayer owner, String type, BridgeConfig config) {
        if (entity.getUUID().equals(owner.getUUID())) return "owner";
        if (entity instanceof Player) return config.allowPvp ? "hostile" : "neutral";
        if (entity instanceof Monster) return "hostile";
        if (config.hostileEntityAllowlist.stream().anyMatch(type::equalsIgnoreCase)) return "hostile";
        if (entity instanceof Animal || entity instanceof Mob && type.startsWith("minecraft:")) return "neutral";
        return "unknown";
    }

    private JsonObject vector(double x, double y, double z) {
        JsonObject value = new JsonObject();
        value.addProperty("x", x);
        value.addProperty("y", y);
        value.addProperty("z", z);
        return value;
    }
}
