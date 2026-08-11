package cn.codex.minecraftbridge.forge;

final class WeaponSelectionPolicy {
    private static final double BASE_ATTACK_DAMAGE = 1.0D;
    private static final double BASE_ATTACK_SPEED = 4.0D;

    private WeaponSelectionPolicy() {}

    static int score(double damageModifier, double speedModifier, int enchantmentScore, double durability) {
        double damage = Math.max(0.1D, BASE_ATTACK_DAMAGE + damageModifier);
        double attacksPerSecond = Math.max(0.1D, BASE_ATTACK_SPEED + speedModifier);
        double dps = damage * attacksPerSecond;
        return (int) Math.round(dps * 20.0D + enchantmentScore * 3.0D + clamp01(durability) * 5.0D);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
