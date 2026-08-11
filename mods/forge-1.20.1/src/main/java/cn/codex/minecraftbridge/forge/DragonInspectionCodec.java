package cn.codex.minecraftbridge.forge;

import java.util.UUID;

/** Compact, loss-bounded fixture state that fits the NPC's 120-character status field. */
final class DragonInspectionCodec {
    static final int FLAG_COUNT = 18;
    static final int MAX_STATUS_LENGTH = 120;

    private static final long BASE36_FOUR_DIGITS = 1_679_615L;
    private static final long BASE36_FIVE_DIGITS = 60_466_175L;
    private static final long BASE36_SEVEN_DIGITS = 78_364_164_095L;

    private DragonInspectionCodec() {
    }

    static int flags(boolean... values) {
        if (values.length != FLAG_COUNT) {
            throw new IllegalArgumentException("Dragon inspection requires exactly " + FLAG_COUNT + " flags");
        }
        int result = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index]) result |= 1 << index;
        }
        return result;
    }

    static String encode(
        String modId,
        UUID dragonId,
        int flags,
        int command,
        long npcHealth,
        long npcFall,
        long dragonFall,
        long ownerDistance,
        long targetCount,
        long obstacleBlocks,
        long x,
        long y,
        long z
    ) {
        if (flags < 0 || flags >= 1 << FLAG_COUNT) {
            throw new IllegalArgumentException("Dragon inspection flag mask is out of range");
        }
        String payload = String.join("|",
            "dragon:i",
            compactMod(modId),
            dragonId.toString().replace("-", ""),
            Integer.toString(flags, 36),
            bounded(command, -35, 35),
            bounded(npcHealth, 0, BASE36_FIVE_DIGITS),
            bounded(npcFall, 0, BASE36_FOUR_DIGITS),
            bounded(dragonFall, 0, BASE36_FOUR_DIGITS),
            bounded(ownerDistance, 0, BASE36_SEVEN_DIGITS),
            bounded(targetCount, 0, BASE36_FOUR_DIGITS),
            bounded(obstacleBlocks, 0, BASE36_FOUR_DIGITS),
            bounded(x, -BASE36_SEVEN_DIGITS, BASE36_SEVEN_DIGITS),
            bounded(y, -BASE36_FIVE_DIGITS, BASE36_FIVE_DIGITS),
            bounded(z, -BASE36_SEVEN_DIGITS, BASE36_SEVEN_DIGITS)
        );
        String status = payload + "|" + checksum(payload);
        if (status.length() > MAX_STATUS_LENGTH) {
            throw new IllegalStateException("Dragon inspection status exceeds " + MAX_STATUS_LENGTH + " characters");
        }
        return status;
    }

    private static String compactMod(String modId) {
        return switch (modId) {
            case "bookofdragons" -> "0";
            case "saintsdragons" -> "1";
            default -> throw new IllegalArgumentException("Unsupported dragon fixture mod: " + modId);
        };
    }

    private static String bounded(long value, long minimum, long maximum) {
        return Long.toString(Math.max(minimum, Math.min(maximum, value)), 36);
    }

    private static String checksum(String payload) {
        int value = 0;
        for (int index = 0; index < payload.length(); index++) {
            value = (value * 31 + payload.charAt(index)) % 46_656;
        }
        return String.format("%3s", Integer.toString(value, 36)).replace(' ', '0');
    }
}
