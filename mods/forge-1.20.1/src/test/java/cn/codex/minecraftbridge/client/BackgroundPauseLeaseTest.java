package cn.codex.minecraftbridge.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundPauseLeaseTest {
    @Test
    void clampsShortAndLongLeasesAndExpiresAtTheDeadline() {
        BackgroundPauseLease lease = new BackgroundPauseLease();
        long start = 1_000_000L;

        lease.arm(start, 1L);
        assertTrue(lease.isActive(start + TimeUnit.MILLISECONDS.toNanos(999L)));
        assertFalse(lease.isActive(start + TimeUnit.MILLISECONDS.toNanos(1_000L)));

        lease.arm(start, 300_000L);
        assertTrue(lease.isActive(start + TimeUnit.MILLISECONDS.toNanos(29_999L)));
        assertFalse(lease.isActive(start + TimeUnit.MILLISECONDS.toNanos(30_000L)));
    }

    @Test
    void clearAndRearmHaveExplicitLifetimes() {
        BackgroundPauseLease lease = new BackgroundPauseLease();
        long start = 5_000_000L;

        lease.arm(start, 5_000L);
        lease.clear();
        assertFalse(lease.isActive(start + 1L));

        lease.arm(start, 5_000L);
        lease.arm(start + TimeUnit.MILLISECONDS.toNanos(4_000L), 5_000L);
        assertTrue(lease.isActive(start + TimeUnit.MILLISECONDS.toNanos(8_999L)));
        assertFalse(lease.isActive(start + TimeUnit.MILLISECONDS.toNanos(9_000L)));
    }
}
