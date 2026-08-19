package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;

import java.util.List;

/** Structured task facts that are safe to forward across the client bridge. */
public final class BridgeTaskDetails {
    private static final List<String> ALLOWED_KEYS = List.of(
        "currentStepIndex",
        "currentStepKind",
        "stepProgress",
        "completedCount",
        "targetCount",
        "retainedCount",
        "resolvedPlacementAnchor"
    );
    private static final BridgeTaskDetails EMPTY = new BridgeTaskDetails(new JsonObject());

    private final JsonObject values;

    private BridgeTaskDetails(JsonObject values) {
        this.values = values;
    }

    public static BridgeTaskDetails empty() {
        return EMPTY;
    }

    public static BridgeTaskDetails from(JsonObject source) {
        JsonObject values = new JsonObject();
        if (source != null) {
            for (String key : ALLOWED_KEYS) {
                if (source.has(key) && !source.get(key).isJsonNull()) {
                    values.add(key, source.get(key).deepCopy());
                }
            }
        }
        return values.entrySet().isEmpty() ? EMPTY : new BridgeTaskDetails(values);
    }

    public void appendTo(JsonObject destination) {
        for (String key : ALLOWED_KEYS) {
            if (values.has(key)) destination.add(key, values.get(key).deepCopy());
        }
    }

    public boolean isEmpty() {
        return values.entrySet().isEmpty();
    }
}
