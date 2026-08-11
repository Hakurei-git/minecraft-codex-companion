package cn.codex.minecraftbridge.forge;

final class EquipmentPolicy {
    private EquipmentPolicy() {
    }

    static double score(double armor, double toughness, int enchantmentScore, double durabilityRatio) {
        double durability = Math.max(0.0D, Math.min(1.0D, durabilityRatio));
        return armor * 100.0D + toughness * 20.0D + enchantmentScore * 3.0D + durability * 5.0D;
    }

    static double offhandScore(boolean totem, boolean shield, double healthRatio, double durabilityRatio) {
        double health = Math.max(0.0D, Math.min(1.0D, healthRatio));
        double durability = Math.max(0.0D, Math.min(1.0D, durabilityRatio));
        if (totem) return health <= 0.4D ? 1_000.0D : 450.0D + (1.0D - health) * 100.0D;
        if (shield) return 400.0D + durability * 300.0D;
        return Double.NEGATIVE_INFINITY;
    }
}
