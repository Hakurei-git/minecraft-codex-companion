package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DragonInspectionCodecTest {
    private static final UUID DRAGON_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void encodesEveryAcceptanceInvariantWithoutStatusTruncation() {
        int flags = DragonInspectionCodec.flags(
            true, true, true, true, true, true,
            true, false, true, false, true, false, true,
            true, true, false, false, true
        );
        String status = DragonInspectionCodec.encode(
            "bookofdragons", DRAGON_ID, flags, 2,
            20_000, 0, 0, 0, 0, 420,
            24_500, 96_000, -8_500
        );

        assertTrue(status.startsWith("dragon:i|0|11111111222233334444555555555555|"));
        assertFalse(status.contains("-2222-"));
        assertTrue(status.length() <= DragonInspectionCodec.MAX_STATUS_LENGTH);
        assertEquals(14, status.chars().filter(character -> character == '|').count());
    }

    @Test
    void extremeWorldValuesRemainBoundedAndParseable() {
        String status = DragonInspectionCodec.encode(
            "saintsdragons", DRAGON_ID, (1 << DragonInspectionCodec.FLAG_COUNT) - 1,
            Integer.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE
        );

        assertTrue(status.startsWith("dragon:i|1|"));
        assertTrue(status.length() <= DragonInspectionCodec.MAX_STATUS_LENGTH);
        assertEquals(15, status.split("\\|", -1).length);
    }

    @Test
    void rejectsProtocolShapeDrift() {
        assertThrows(IllegalArgumentException.class, () -> DragonInspectionCodec.flags(true));
        assertThrows(IllegalArgumentException.class, () -> DragonInspectionCodec.encode(
            "unknown", DRAGON_ID, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> DragonInspectionCodec.encode(
            "bookofdragons", DRAGON_ID, 1 << DragonInspectionCodec.FLAG_COUNT,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        ));
    }
}
