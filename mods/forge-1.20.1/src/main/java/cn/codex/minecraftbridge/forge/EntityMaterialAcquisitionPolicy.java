package cn.codex.minecraftbridge.forge;

import java.util.Locale;

/**
 * Deterministic routing for recipe ingredients that come from living entities.
 *
 * <p>This policy is deliberately narrow.  It only selects an executor and
 * applies the same ownership protections used by the food/ranch skills; world
 * interaction and navigation remain in {@link NpcTaskEngine}.</p>
 */
final class EntityMaterialAcquisitionPolicy {
    enum Route {
        SHEAR_WHITE_SHEEP,
        HUNT_COW,
        HUNT_CHICKEN,
        HUNT_SLIME,
        UNSUPPORTED
    }

    private EntityMaterialAcquisitionPolicy() {
    }

    static Route routeFor(String itemId) {
        String normalized = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "minecraft:white_wool" -> Route.SHEAR_WHITE_SHEEP;
            case "minecraft:leather" -> Route.HUNT_COW;
            case "minecraft:feather" -> Route.HUNT_CHICKEN;
            case "minecraft:slime_ball" -> Route.HUNT_SLIME;
            default -> Route.UNSUPPORTED;
        };
    }

    static String entityType(Route route) {
        return switch (route) {
            case SHEAR_WHITE_SHEEP -> "minecraft:sheep";
            case HUNT_COW -> "minecraft:cow";
            case HUNT_CHICKEN -> "minecraft:chicken";
            case HUNT_SLIME -> "minecraft:slime";
            case UNSUPPORTED -> "";
        };
    }

    static boolean isHunt(Route route) {
        return route == Route.HUNT_COW || route == Route.HUNT_CHICKEN || route == Route.HUNT_SLIME;
    }

    static boolean mayUsePassiveAnimal(
        boolean alive,
        boolean adult,
        boolean customNamed,
        boolean tame,
        boolean leashed,
        boolean nearHome,
        int nearbyAdults,
        boolean shearing
    ) {
        if (!alive || !adult || customNamed || tame || leashed || nearHome) return false;
        // Shearing is non-lethal and therefore does not need to reserve a
        // breeding pair.  Hunting keeps the same minimum herd rule as food.
        return shearing || nearbyAdults >= 3;
    }

    static boolean mayUseSlime(boolean alive, boolean customNamed, boolean nearHome) {
        return alive && !customNamed && !nearHome;
    }
}
