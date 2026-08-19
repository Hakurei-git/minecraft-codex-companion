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
    // Building the field and then gathering probabilistic seeds are one
    // recoverable work chain. Keep a bounded window long enough for the
    // survival search to leave the home area without failing mid-cycle.
    // Random seed drops plus a remembered outdoor farm can require several
    // remote excursions. Keep the task bounded, but do not expire a healthy
    // survival chain while it is still making physical gathering progress.
    static final int MAX_FARM_TICKS = 20 * 60 * 20;
    static final int FARM_SEARCH_INTERVAL_TICKS = 10;
    static final int MAX_FARM_EMPTY_SEARCH_TICKS = 120;
    static final int MAX_FARM_PLANT_REJECTIONS = 16;
    // A farm command's radius is a search boundary, not an instruction to
    // obtain that many random grass drops in one blocking prerequisite. A
    // bounded starter pass gives the field real crops quickly; later commands
    // can reuse the recorded facility and continue tending it.
    static final int MAX_FARM_ACTIONS_PER_PASS = 8;
    // Recorded anchors can be on a house floor, path, or irrigation edge while
    // the outdoor field sits several blocks above/below it.
    static final int FARM_VERTICAL_SEARCH_RADIUS = 8;

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
        // A maintenance cycle must stay within already prepared farmland. It
        // used to expand onto every nearby dirt/path cell, wearing out a hoe
        // while treating unrelated outdoor ground as part of the facility.
        // Explicit planting (including the post-blueprint planting step) may
        // still prepare new ground.
        return "plant".equalsIgnoreCase(normalized);
    }

    static boolean farmMayReportSuccess(int completedActions) {
        return completedActions > 0;
    }

    static boolean farmTimedOut(int taskTicks) {
        return taskTicks > MAX_FARM_TICKS;
    }

    /**
     * A remembered farm is the return destination only after an active
     * prerequisite excursion has finished. Otherwise the farm tick would
     * continuously recall the NPC while it was walking out to collect seeds.
     */
    static boolean shouldReturnToFarmAnchor(
        boolean gatheringPrerequisite,
        double distance,
        int requestedRadius
    ) {
        // The anchor marks the facility, not a leash point. Allow normal work
        // anywhere inside the requested farm search area and only recall after
        // a genuine excursion outside that area.
        // Block scans use square rings (Chebyshev distance), whose corners are
        // farther away in Euclidean navigation distance. A 1.5 multiplier
        // covers those corners without turning the facility anchor into an
        // unbounded leash.
        double workingRadius = Math.max(8.0D, Math.min(96.0D, requestedRadius) * 1.5D);
        return !gatheringPrerequisite && distance > workingRadius;
    }

    /** Collect a useful batch instead of making one remote round trip per seed. */
    static int farmSeedBatchSize(int requestedActions, int completedActions) {
        int remaining = Math.max(1, requestedActions - Math.max(0, completedActions));
        return Math.min(MAX_FARM_ACTIONS_PER_PASS, remaining);
    }

    static int farmActionTarget(int requestedRadius) {
        return Math.max(1, Math.min(MAX_FARM_ACTIONS_PER_PASS, requestedRadius));
    }

    static boolean isWheatSeedSurfaceGather(String itemId) {
        return "minecraft:wheat_seeds".equals(itemId);
    }

    static boolean isSurfacePlantSource(int blockY, int surfaceY) {
        return Math.abs(blockY - surfaceY) <= 2;
    }

    static boolean needsSurfaceRecovery(int npcBlockY, int surfaceY) {
        return npcBlockY < surfaceY - 6;
    }

    static boolean mayTeleportToSurface(boolean ownerCanCheat, int npcBlockY, int surfaceY) {
        return ownerCanCheat && needsSurfaceRecovery(npcBlockY, surfaceY);
    }

    static boolean farmPlantRejectionsExhausted(int consecutiveRejections) {
        return consecutiveRejections >= MAX_FARM_PLANT_REJECTIONS;
    }

    /**
     * Search nearby first, then expand outside houses in bounded stages. A
     * remembered facility anchor handles truly remote farms; this local scan
     * intentionally stops at 96 blocks to avoid an unbounded world walk.
     */
    static int farmSearchRadius(int requestedRadius, int emptySearchTicks) {
        int requested = Math.max(1, Math.min(96, requestedRadius));
        if (emptySearchTicks >= 100) return 96;
        if (emptySearchTicks >= 80) return 80;
        if (emptySearchTicks >= 60) return 64;
        if (emptySearchTicks >= 40) return 48;
        if (emptySearchTicks >= 20) return 32;
        return Math.min(requested, 16);
    }

    static boolean shouldScanFarmNow(int emptySearchTicks) {
        return emptySearchTicks == 0 || emptySearchTicks % FARM_SEARCH_INTERVAL_TICKS == 0;
    }

    static boolean farmLocalSearchExhausted(int emptySearchTicks) {
        return emptySearchTicks >= MAX_FARM_EMPTY_SEARCH_TICKS;
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
