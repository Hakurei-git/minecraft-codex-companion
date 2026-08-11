package cn.codex.minecraftbridge.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BoundedMessageOutbox<T> {
    private final int capacity;
    private final LinkedHashMap<String, T> messages = new LinkedHashMap<>();

    BoundedMessageOutbox(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized void put(String key, T message) {
        messages.remove(key);
        messages.put(key, message);
        while (messages.size() > capacity) {
            String oldest = messages.keySet().iterator().next();
            messages.remove(oldest);
        }
    }

    synchronized List<T> drain() {
        List<T> result = new ArrayList<>(messages.values());
        messages.clear();
        return result;
    }

    synchronized int size() {
        return messages.size();
    }
}
