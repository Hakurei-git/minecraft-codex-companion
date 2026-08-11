package cn.codex.minecraftbridge.forge;

/** Pure acceptance rules for dragon care actions. */
final class DragonCareAcceptancePolicy {
    private static final double EPSILON = 0.000_001D;
    private static final double BOOK_REGEN_FOOD_LEVEL = 75.0D;
    private static final int BOOK_REGEN_CONFIRM_TICKS = 100;
    private static final int STANDARD_CONFIRM_TICKS = 40;
    private static final int REFEED_INTERVAL_TICKS = 8;

    record State(
        String identity,
        boolean present,
        int itemCount,
        double health,
        double food,
        double happiness,
        boolean owned,
        double eggProgress
    ) {
    }

    private DragonCareAcceptancePolicy() {
    }

    static boolean fed(State before, State after) {
        return fed(before, after, true);
    }

    static boolean fed(State before, State after, boolean requireItemConsumption) {
        return sameTarget(before, after)
            && consumptionAccepted(before, after, requireItemConsumption)
            && (increased(before.food(), after.food())
                || increased(before.happiness(), after.happiness()));
    }

    static boolean healed(State before, State after) {
        return healed(before, after, true);
    }

    static boolean healed(State before, State after, boolean requireItemConsumption) {
        return sameTarget(before, after)
            && consumptionAccepted(before, after, requireItemConsumption)
            && increased(before.health(), after.health());
    }

    static boolean tamed(State before, State after) {
        return tamed(before, after, true);
    }

    static boolean tamed(State before, State after, boolean requireItemConsumption) {
        return sameTarget(before, after)
            && consumptionAccepted(before, after, requireItemConsumption)
            && !before.owned()
            && after.owned();
    }

    static boolean eggAdvanced(State before, State after) {
        return sameTarget(before, after)
            && finiteNonNegative(before.eggProgress())
            && finiteNonNegative(after.eggProgress())
            && increased(before.eggProgress(), after.eggProgress());
    }

    static boolean sameTarget(State before, State after) {
        return before != null
            && after != null
            && before.present()
            && after.present()
            && before.identity() != null
            && !before.identity().isBlank()
            && before.identity().equals(after.identity());
    }

    static boolean shouldRefillForHealing(
        String modId,
        double foodLevel,
        boolean foodAvailable,
        int ticksSinceLastFeed
    ) {
        return "bookofdragons".equals(modId)
            && Double.isFinite(foodLevel)
            && foodLevel >= 0.0D
            && foodLevel < BOOK_REGEN_FOOD_LEVEL
            && foodAvailable
            && ticksSinceLastFeed >= REFEED_INTERVAL_TICKS;
    }

    static int healingConfirmationTicks(String modId) {
        return "bookofdragons".equals(modId)
            ? BOOK_REGEN_CONFIRM_TICKS
            : STANDARD_CONFIRM_TICKS;
    }

    private static boolean consumedItem(State before, State after) {
        return before.itemCount() > after.itemCount() && after.itemCount() >= 0;
    }

    private static boolean consumptionAccepted(
        State before,
        State after,
        boolean requireItemConsumption
    ) {
        return !requireItemConsumption || consumedItem(before, after);
    }

    private static boolean increased(double before, double after) {
        return Double.isFinite(before) && Double.isFinite(after) && after > before + EPSILON;
    }

    private static boolean finiteNonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }
}
