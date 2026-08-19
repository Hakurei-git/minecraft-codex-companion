package cn.codex.minecraftbridge.forge;

final class FoodProvisionPolicy {
    static final int DEFAULT_RESERVE_COUNT = 8;
    static final int LOCAL_SEARCH_RADIUS = 48;
    static final int MAX_EXCURSIONS = 64;
    static final int BREEDING_RESERVE = 2;

    private FoodProvisionPolicy() {}

    /**
     * Keeps the requested food category intact from counting through delivery.
     * A hunt request must never be completed with melons or other forage, and
     * a forage request must not silently consume the NPC's stored meat.
     */
    static boolean acceptsSourceItem(String source, String itemId) {
        String normalizedSource = source == null ? "auto" : source.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedItem = itemId == null ? "" : itemId.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalizedSource) {
            case "hunt" -> isMeat(normalizedItem);
            case "forage" -> !isMeat(normalizedItem);
            default -> true;
        };
    }

    static boolean isMeat(String itemId) {
        return switch (itemId) {
            case "minecraft:beef", "minecraft:cooked_beef",
                "minecraft:porkchop", "minecraft:cooked_porkchop",
                "minecraft:mutton", "minecraft:cooked_mutton",
                "minecraft:chicken", "minecraft:cooked_chicken",
                "minecraft:rabbit", "minecraft:cooked_rabbit" -> true;
            default -> false;
        };
    }

    static boolean isRawMeat(String itemId) {
        return switch (itemId) {
            case "minecraft:beef", "minecraft:porkchop", "minecraft:mutton",
                "minecraft:chicken", "minecraft:rabbit" -> true;
            default -> false;
        };
    }

    static String goalLabel(String source) {
        return switch (source == null ? "auto" : source.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "hunt" -> "肉类";
            case "forage" -> "采集食物";
            default -> "安全口粮";
        };
    }

    static boolean shouldComplete(int foodItems, int requestedReserve) {
        return foodItems >= Math.max(1, requestedReserve);
    }

    static boolean mayHunt(
        boolean adult,
        boolean named,
        boolean tamed,
        boolean leashed,
        boolean nearHome,
        int nearbyAdults
    ) {
        return mayHunt(adult, named, tamed, leashed, nearHome, nearbyAdults, false);
    }

    static boolean mayHunt(
        boolean adult,
        boolean named,
        boolean tamed,
        boolean leashed,
        boolean nearHome,
        int nearbyAdults,
        boolean survivalFallback
    ) {
        if (!adult || named || tamed || leashed || nearHome) return false;
        return nearbyAdults > (survivalFallback ? 0 : BREEDING_RESERVE);
    }

    static int nextSearchRadius(int current) {
        return Math.min(LOCAL_SEARCH_RADIUS, Math.max(16, current) + 16);
    }
}
