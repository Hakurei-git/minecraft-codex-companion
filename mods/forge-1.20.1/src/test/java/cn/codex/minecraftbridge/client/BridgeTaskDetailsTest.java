package cn.codex.minecraftbridge.client;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BridgeTaskDetailsTest {
    @Test
    void preservesEveryProtocolDetailAndDropsUnrelatedPayloadFields() {
        JsonObject source = new JsonObject();
        source.addProperty("taskId", "not-forwarded");
        source.addProperty("currentStepIndex", 2);
        source.addProperty("currentStepKind", "gather");
        source.addProperty("stepProgress", 0.75D);
        source.addProperty("completedCount", 12);
        source.addProperty("targetCount", 12);
        source.addProperty("retainedCount", 12);
        JsonObject anchor = new JsonObject();
        anchor.addProperty("x", -87);
        anchor.addProperty("y", 73);
        anchor.addProperty("z", -138);
        source.add("resolvedPlacementAnchor", anchor);
        source.addProperty("secret", "must-not-cross-the-bridge");

        BridgeTaskDetails details = BridgeTaskDetails.from(source);
        JsonObject forwarded = new JsonObject();
        details.appendTo(forwarded);

        assertFalse(details.isEmpty());
        assertEquals(2, forwarded.get("currentStepIndex").getAsInt());
        assertEquals("gather", forwarded.get("currentStepKind").getAsString());
        assertEquals(0.75D, forwarded.get("stepProgress").getAsDouble());
        assertEquals(12, forwarded.get("completedCount").getAsInt());
        assertEquals(12, forwarded.get("targetCount").getAsInt());
        assertEquals(12, forwarded.get("retainedCount").getAsInt());
        assertEquals(-87, forwarded.getAsJsonObject("resolvedPlacementAnchor").get("x").getAsInt());
        assertEquals(73, forwarded.getAsJsonObject("resolvedPlacementAnchor").get("y").getAsInt());
        assertEquals(-138, forwarded.getAsJsonObject("resolvedPlacementAnchor").get("z").getAsInt());
        assertFalse(forwarded.has("taskId"));
        assertFalse(forwarded.has("secret"));
    }

    @Test
    void emptyDetailsAppendNothing() {
        JsonObject forwarded = new JsonObject();

        BridgeTaskDetails.empty().appendTo(forwarded);

        assertTrue(BridgeTaskDetails.from(null).isEmpty());
        assertTrue(forwarded.entrySet().isEmpty());
    }
}
