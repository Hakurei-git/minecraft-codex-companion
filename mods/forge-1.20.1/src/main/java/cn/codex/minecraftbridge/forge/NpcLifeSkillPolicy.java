package cn.codex.minecraftbridge.forge;

import java.util.Locale;

/**
 * Pure decisions shared by the stateful life-skill executor. Keeping these
 * rules independent from a loaded Minecraft world makes the edge cases easy
 * to regression-test.
 */
final class NpcLifeSkillPolicy {
    static final int MIN_FISHING_WAIT_TICKS = 20 * 8;
    static final int MAX_FISHING_WAIT_TICKS = 20 * 24;
    static final int MAX_FISHING_CATCHES = 64;
    static final int MIN_SINGLE_PLAYER_REST_TICKS = 20 * 5;
    static final int SLEEP_PROGRESS_INTERVAL_TICKS = 20 * 10;
    static final int MAX_SLEEP_TICKS = 20 * 90;
    static final int MAX_FARM_TICKS = 20 * 60 * 3;

    enum SleepDecision {
        SLEEP,
        ALREADY_DAY,
        BED_MISSING,
        DANGER_NEARBY
    }

    private NpcLifeSkillPolicy() {}

    static int fishingWaitTicks(long worldSeed, int completedCatches) {
        long mixed = worldSeed ^ (0x9E3779B97F4A7C15L * Math.max(1, completedCatches + 1L));
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        int span = MAX_FISHING_WAIT_TICKS - MIN_FISHING_WAIT_TICKS + 1;
        return MIN_FISHING_WAIT_TICKS + Math.floorMod(mixed, span);
    }

    static int clampFishingCatches(int requested) {
        return Math.max(1, Math.min(MAX_FISHING_CATCHES, requested));
    }

    static SleepDecision sleepDecision(boolean daytime, boolean bedAvailable, boolean dangerNearby) {
        if (daytime) return SleepDecision.ALREADY_DAY;
        if (!bedAvailable) return SleepDecision.BED_MISSING;
        if (dangerNearby) return SleepDecision.DANGER_NEARBY;
        return SleepDecision.SLEEP;
    }

    static boolean shouldSkipSinglePlayerNight(int onlinePlayers, boolean naturalDimension, int restedTicks) {
        return onlinePlayers == 1 && naturalDimension && restedTicks >= MIN_SINGLE_PLAYER_REST_TICKS;
    }

    static boolean shouldReportSleepProgress(int restedTicks) {
        return restedTicks > 0 && restedTicks % SLEEP_PROGRESS_INTERVAL_TICKS == 0;
    }

    static boolean sleepTimedOut(int restedTicks) {
        return restedTicks >= MAX_SLEEP_TICKS;
    }

    static String seedItemId(String cropId) {
        String value = cropId == null ? "" : cropId.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "minecraft:wheat" -> "minecraft:wheat_seeds";
            case "minecraft:beetroots", "minecraft:beetroot" -> "minecraft:beetroot_seeds";
            case "minecraft:carrots", "minecraft:carrot" -> "minecraft:carrot";
            case "minecraft:potatoes", "minecraft:potato" -> "minecraft:potato";
            case "minecraft:nether_wart" -> "minecraft:nether_wart";
            case "minecraft:sweet_berry_bush", "minecraft:sweet_berries" -> "minecraft:sweet_berries";
            case "minecraft:sugar_cane" -> "minecraft:sugar_cane";
            case "minecraft:cocoa" -> "minecraft:cocoa_beans";
            default -> value;
        };
    }

    static String cropBlockId(String cropId) {
        String value = cropId == null ? "" : cropId.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "minecraft:wheat", "minecraft:wheat_seeds" -> "minecraft:wheat";
            case "minecraft:beetroots", "minecraft:beetroot", "minecraft:beetroot_seeds" -> "minecraft:beetroots";
            case "minecraft:carrots", "minecraft:carrot" -> "minecraft:carrots";
            case "minecraft:potatoes", "minecraft:potato" -> "minecraft:potatoes";
            default -> value;
        };
    }

    static boolean isTillableGround(String blockId) {
        return switch (blockId == null ? "" : blockId.trim().toLowerCase(Locale.ROOT)) {
            case "minecraft:dirt", "minecraft:grass_block", "minecraft:dirt_path", "minecraft:coarse_dirt" -> true;
            default -> false;
        };
    }

    static boolean mayTillNewGround(String action) {
        String normalized = action == null ? "" : action.trim();
        return "plant".equalsIgnoreCase(normalized) || "cycle".equalsIgnoreCase(normalized);
    }

    static boolean farmMayReportSuccess(int completedActions) {
        return completedActions > 0;
    }

    static boolean farmTimedOut(int taskTicks) {
        return taskTicks > MAX_FARM_TICKS;
    }

    /**
     * A cycle task may extend a prepared dirt field, but it must not turn every
     * surrounding grass block into farmland just because it is inside the
     * search radius. Explicit planting tasks retain the broader tilling rule.
     */
    static boolean isPreparedFarmGround(String blockId) {
        return switch (blockId == null ? "" : blockId.trim().toLowerCase(Locale.ROOT)) {
            case "minecraft:dirt", "minecraft:dirt_path", "minecraft:coarse_dirt" -> true;
            default -> false;
        };
    }

    static int stackCountForDrop(int itemCount, int maxStackSize) {
        if (itemCount <= 0) return 0;
        int stackSize = Math.max(1, maxStackSize);
        return (itemCount + stackSize - 1) / stackSize;
    }
}
