package cn.codex.minecraftbridge.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** Test-only recovery that stays inside Minecraft's client thread. */
final class ClientRecoveryLiveFixture {
    private ClientRecoveryLiveFixture() {
    }

    static boolean matchesRespawn(JsonObject request) {
        return "client-recovery".equals(stringValue(request, "suite"))
            && "respawn".equals(stringValue(request, "mode"));
    }

    static boolean respawnIfDead(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || !minecraft.player.isDeadOrDying()) return false;
        minecraft.player.respawn();
        return true;
    }

    private static String stringValue(JsonObject request, String key) {
        if (request == null) return null;
        JsonElement value = request.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        return value.getAsString();
    }
}
