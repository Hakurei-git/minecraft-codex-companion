package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Immutable deep-mining state suspended by a nested material goal. */
record DeepMiningCheckpoint(
    String phase,
    String itemId,
    int targetY,
    Direction direction,
    int phaseStartedTick,
    int staircaseStep,
    int branchIndex,
    int branchProgress,
    int regionIndex,
    int lastTorchProgress,
    int brokenBlocks,
    int placedTorches,
    int blockedTurns,
    int markerStage,
    int entrySearchIndex,
    int entryTargetStartedTick,
    boolean preflightComplete,
    boolean excavationTarget,
    int resourceTargetStartedTick,
    int resourceChaseStartedTick,
    BlockPos entrance,
    BlockPos landing,
    BlockPos lastSafeStand,
    BlockPos caveTarget,
    BlockPos targetBlock,
    BlockPos resourceTimedTarget
) {
    JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("phase", phase);
        value.addProperty("itemId", itemId);
        value.addProperty("targetY", targetY);
        value.addProperty("direction", direction.getName());
        value.addProperty("phaseStartedTick", phaseStartedTick);
        value.addProperty("staircaseStep", staircaseStep);
        value.addProperty("branchIndex", branchIndex);
        value.addProperty("branchProgress", branchProgress);
        value.addProperty("regionIndex", regionIndex);
        value.addProperty("lastTorchProgress", lastTorchProgress);
        value.addProperty("brokenBlocks", brokenBlocks);
        value.addProperty("placedTorches", placedTorches);
        value.addProperty("blockedTurns", blockedTurns);
        value.addProperty("markerStage", markerStage);
        value.addProperty("entrySearchIndex", entrySearchIndex);
        value.addProperty("entryTargetStartedTick", entryTargetStartedTick);
        value.addProperty("preflightComplete", preflightComplete);
        value.addProperty("excavationTarget", excavationTarget);
        value.addProperty("resourceTargetStartedTick", resourceTargetStartedTick);
        value.addProperty("resourceChaseStartedTick", resourceChaseStartedTick);
        putBlock(value, "entrance", entrance);
        putBlock(value, "landing", landing);
        putBlock(value, "lastSafeStand", lastSafeStand);
        putBlock(value, "caveTarget", caveTarget);
        putBlock(value, "targetBlock", targetBlock);
        putBlock(value, "resourceTimedTarget", resourceTimedTarget);
        return value;
    }

    static DeepMiningCheckpoint fromJson(JsonObject value) {
        if (value == null) return null;
        String phase = string(value, "phase", "");
        String itemId = string(value, "itemId", "");
        if (phase.isBlank() || itemId.isBlank()) return null;
        Direction direction = Direction.byName(string(value, "direction", "north"));
        if (direction == null || !direction.getAxis().isHorizontal()) direction = Direction.NORTH;
        return new DeepMiningCheckpoint(
            phase,
            itemId,
            integer(value, "targetY", Integer.MAX_VALUE),
            direction,
            Math.max(0, integer(value, "phaseStartedTick", 0)),
            Math.max(0, integer(value, "staircaseStep", 0)),
            Math.max(0, integer(value, "branchIndex", 0)),
            Math.max(0, integer(value, "branchProgress", 0)),
            Math.max(0, integer(value, "regionIndex", 0)),
            Math.max(0, integer(value, "lastTorchProgress", 0)),
            Math.max(0, integer(value, "brokenBlocks", 0)),
            Math.max(0, integer(value, "placedTorches", 0)),
            Math.max(0, integer(value, "blockedTurns", 0)),
            Math.max(0, Math.min(3, integer(value, "markerStage", 0))),
            Math.max(0, integer(value, "entrySearchIndex", 0)),
            Math.max(0, integer(value, "entryTargetStartedTick", 0)),
            bool(value, "preflightComplete"),
            bool(value, "excavationTarget"),
            Math.max(0, integer(value, "resourceTargetStartedTick", 0)),
            Math.max(0, integer(value, "resourceChaseStartedTick", 0)),
            readBlock(value, "entrance"),
            readBlock(value, "landing"),
            readBlock(value, "lastSafeStand"),
            readBlock(value, "caveTarget"),
            readBlock(value, "targetBlock"),
            readBlock(value, "resourceTimedTarget")
        );
    }

    private static boolean bool(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean();
    }

    private static int integer(JsonObject value, String key, int fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : fallback;
    }

    private static String string(JsonObject value, String key, String fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback;
    }

    private static void putBlock(JsonObject value, String key, BlockPos position) {
        if (position == null) return;
        JsonObject block = new JsonObject();
        block.addProperty("x", position.getX());
        block.addProperty("y", position.getY());
        block.addProperty("z", position.getZ());
        value.add(key, block);
    }

    private static BlockPos readBlock(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) return null;
        JsonObject block = value.getAsJsonObject(key);
        return new BlockPos(
            integer(block, "x", 0),
            integer(block, "y", 0),
            integer(block, "z", 0)
        );
    }
}
