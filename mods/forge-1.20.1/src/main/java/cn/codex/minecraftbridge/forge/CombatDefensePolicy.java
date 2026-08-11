package cn.codex.minecraftbridge.forge;

final class CombatDefensePolicy {
    private CombatDefensePolicy() {}

    static boolean shouldRaiseShield(boolean hasUsableShield, boolean targetAlive, double distance, int attackCooldown) {
        return hasUsableShield && targetAlive && (distance > 2.8D || attackCooldown > 0);
    }
}
