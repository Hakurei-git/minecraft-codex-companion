package cn.codex.minecraftbridge.forge;

final class HungerPolicy {
    static final int AUTO_EAT_THRESHOLD = 10;
    static final int NATURAL_REGEN_THRESHOLD = 18;
    static final int REGEN_INTERVAL_TICKS = 80;

    private HungerPolicy() {
    }

    static boolean shouldAutoEat(int foodLevel, boolean alreadyEating) {
        return foodLevel < AUTO_EAT_THRESHOLD && !alreadyEating;
    }

    static boolean explicitEatingShouldStop(int foodLevel, int completed, int requestedCount) {
        return foodLevel >= 20 || completed >= Math.max(1, requestedCount);
    }

    static boolean shouldEatToRegenerate(int foodLevel, float health, float maxHealth) {
        return health > 0.0F
            && maxHealth > 0.0F
            && health < maxHealth
            && foodLevel < NATURAL_REGEN_THRESHOLD;
    }

    static boolean canNaturallyRegenerate(int foodLevel, float health, float maxHealth) {
        return foodLevel >= NATURAL_REGEN_THRESHOLD && health > 0.0F && health < maxHealth;
    }
}
