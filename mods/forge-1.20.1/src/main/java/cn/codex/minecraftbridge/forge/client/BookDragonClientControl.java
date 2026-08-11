package cn.codex.minecraftbridge.forge.client;

import cn.codex.minecraftbridge.forge.BookDragonInputReset;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Applies the final Book of Dragons input reset to the controlling client. */
public final class BookDragonClientControl {
    private BookDragonClientControl() {
    }

    @SuppressWarnings("deprecation")
    public static void reset(JsonObject payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
            || !payload.has("entityId") || !payload.has("dragonId")) return;
        Entity dragon = minecraft.level.getEntity(payload.get("entityId").getAsInt());
        if (dragon == null || dragon != minecraft.player.getRootVehicle()
            || !dragon.getUUID().toString().equals(payload.get("dragonId").getAsString())
            || !"bookofdragons".equals(
            BuiltInRegistries.ENTITY_TYPE.getKey(dragon.getType()).getNamespace()
        )) return;
        BookDragonInputReset.reset(dragon);
        dragon.setDeltaMovement(Vec3.ZERO);
        dragon.hasImpulse = true;
        dragon.fallDistance = 0.0F;
        for (Entity passenger : dragon.getPassengers()) passenger.fallDistance = 0.0F;
    }
}
