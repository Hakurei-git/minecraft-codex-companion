package cn.codex.minecraftbridge.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("deprecation")
public final class SnapshotFactory {
    private long sequence;

    public JsonObject capture(Minecraft minecraft, BridgeConfig config, String status) {
        Player player = minecraft.player;
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("sequence", ++sequence);
        snapshot.addProperty("capturedAt", Instant.now().toString());
        snapshot.addProperty("worldId", minecraft.getCurrentServer() == null ? "singleplayer" : minecraft.getCurrentServer().ip);
        snapshot.addProperty("dimension", player.level().dimension().location().toString());
        snapshot.add("position", vector(player.getX(), player.getY(), player.getZ()));
        snapshot.addProperty("yaw", player.getYRot());
        snapshot.addProperty("pitch", player.getXRot());
        snapshot.addProperty("health", player.getHealth());
        snapshot.addProperty("maxHealth", player.getMaxHealth());
        snapshot.addProperty("food", player.getFoodData().getFoodLevel());
        snapshot.addProperty("air", Math.max(0, player.getAirSupply()));
        snapshot.addProperty("gameMode", minecraft.gameMode == null ? "survival" : minecraft.gameMode.getPlayerMode().getName());
        snapshot.addProperty("timeOfDay", Math.max(0, player.level().getDayTime()));
        snapshot.addProperty("weather", player.level().isThundering() ? "thunder" : player.level().isRaining() ? "rain" : "clear");
        snapshot.add("inventory", inventory(player));
        snapshot.add("nearbyEntities", nearbyEntities(player, config));
        snapshot.addProperty("status", status);
        return snapshot;
    }

    private JsonArray inventory(Player player) {
        JsonArray result = new JsonArray();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject item = new JsonObject();
            item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.addProperty("displayName", stack.getHoverName().getString());
            item.addProperty("count", stack.getCount());
            item.addProperty("slot", slot);
            result.add(item);
        }
        return result;
    }

    private JsonArray nearbyEntities(Player player, BridgeConfig config) {
        JsonArray result = new JsonArray();
        List<Entity> entities = player.level().getEntities(
            player,
            player.getBoundingBox().inflate(config.observeRadius),
            entity -> entity.isAlive()
        );
        entities.stream()
            .sorted(Comparator.comparingDouble(player::distanceToSqr))
            .limit(64)
            .forEach(entity -> result.add(entity(entity, player, config)));
        return result;
    }

    private JsonObject entity(Entity entity, Player player, BridgeConfig config) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        JsonObject result = new JsonObject();
        result.addProperty("id", entity.getUUID().toString());
        result.addProperty("type", type);
        result.addProperty("name", entity.getDisplayName().getString());
        result.add("position", vector(entity.getX(), entity.getY(), entity.getZ()));
        result.addProperty("distance", player.distanceTo(entity));
        if (entity instanceof LivingEntity living) result.addProperty("health", Math.max(0, living.getHealth()));
        else result.add("health", null);
        result.addProperty("disposition", disposition(entity, type, config));
        return result;
    }

    private String disposition(Entity entity, String type, BridgeConfig config) {
        if (entity instanceof Player player) {
            return player.getGameProfile().getName().equalsIgnoreCase(config.ownerName) ? "owner" : "neutral";
        }
        if (entity instanceof TamableAnimal tamable && tamable.isTame()) return "ally";
        if (entity instanceof Monster) return "hostile";
        if (config.hostileEntityAllowlist.stream().anyMatch(type::equalsIgnoreCase)) return "hostile";
        if (entity instanceof Animal || entity instanceof Mob && type.startsWith("minecraft:")) return "neutral";
        return "unknown";
    }

    private JsonObject vector(double x, double y, double z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        return result;
    }
}
