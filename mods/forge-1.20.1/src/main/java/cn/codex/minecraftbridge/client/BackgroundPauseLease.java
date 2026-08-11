package cn.codex.minecraftbridge.client;

import java.util.concurrent.TimeUnit;

final class BackgroundPauseLease {
    static final long MIN_LEASE_MILLIS = 1_000L;
    static final long MAX_LEASE_MILLIS = 30_000L;

    private boolean armed;
    private long expiresAtNanos;

    void arm(long nowNanos, long requestedMillis) {
        long boundedMillis = Math.max(MIN_LEASE_MILLIS, Math.min(MAX_LEASE_MILLIS, requestedMillis));
        expiresAtNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(boundedMillis);
        armed = true;
    }

    boolean isActive(long nowNanos) {
        if (!armed) return false;
        if (nowNanos - expiresAtNanos < 0L) return true;
        clear();
        return false;
    }

    void clear() {
        armed = false;
        expiresAtNanos = 0L;
    }
}
