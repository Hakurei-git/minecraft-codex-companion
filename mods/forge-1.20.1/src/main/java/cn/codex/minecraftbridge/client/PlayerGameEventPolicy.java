package cn.codex.minecraftbridge.client;

/** Matches structured vanilla broadcasts, never arbitrary chat text. */
final class PlayerGameEventPolicy {
    private Boolean wasAlive;
    private boolean deathAnnounced;
    private int deadTicks;

    static String classify(String key, String subject, String player) {
        if (key == null || subject == null || player == null
            || player.isBlank() || !subject.trim().equalsIgnoreCase(player.trim())) return null;
        if (key.startsWith("death.attack.") || key.startsWith("death.fell.")) return "death";
        return switch (key) {
            case "chat.type.advancement.task", "chat.type.advancement.goal", "chat.type.advancement.challenge" -> "advancement";
            default -> null;
        };
    }

    boolean deathBroadcastReceived() {
        if (deathAnnounced) return false;
        deathAnnounced = true;
        return true;
    }

    String tick(boolean alive) {
        String event = null;
        if (!alive) {
            // Give the detailed death broadcast time to arrive before using a fallback.
            if (++deadTicks >= 10 && !deathAnnounced) {
                deathAnnounced = true;
                event = "death";
            }
        } else if (Boolean.FALSE.equals(wasAlive)) {
            event = "respawn";
            deadTicks = 0;
            deathAnnounced = false;
        }
        wasAlive = alive;
        return event;
    }

    void reset() { wasAlive = null; deathAnnounced = false; deadTicks = 0; }
}
