package cn.codex.minecraftbridge.client;

final class WebSocketTextAccumulator {
    static final int DEFAULT_MAX_CHARS = 64 * 1024 * 1024;

    private final int maxChars;
    private final StringBuilder buffer = new StringBuilder();

    WebSocketTextAccumulator() {
        this(DEFAULT_MAX_CHARS);
    }

    WebSocketTextAccumulator(int maxChars) {
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be positive");
        this.maxChars = maxChars;
    }

    String append(CharSequence fragment, boolean last) {
        if (fragment.length() > maxChars - buffer.length()) {
            reset();
            throw new IllegalArgumentException("WebSocket text message is too large");
        }
        buffer.append(fragment);
        if (!last) return null;

        String complete = buffer.toString();
        reset();
        return complete;
    }

    void reset() {
        buffer.setLength(0);
    }
}
