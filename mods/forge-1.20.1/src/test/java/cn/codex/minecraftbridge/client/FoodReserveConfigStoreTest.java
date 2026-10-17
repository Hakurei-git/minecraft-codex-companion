package cn.codex.minecraftbridge.client;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class FoodReserveConfigStoreTest {
    @TempDir Path directory;

    @Test void persistsTheToggleAndPreservesOtherAndUnknownSettings() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "{\"npcFoodReserveCount\":8,\"name\":\"伙伴\",\"unknownLocalSetting\":{\"enabled\":true}}");
        FoodReserveConfigStore.update(file, 0);
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(0, saved.get("npcFoodReserveCount").getAsInt());
        assertEquals("伙伴", saved.get("name").getAsString());
        assertTrue(saved.getAsJsonObject("unknownLocalSetting").get("enabled").getAsBoolean());
        FoodReserveConfigStore.update(file, 8);
        assertEquals(8, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("npcFoodReserveCount").getAsInt());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void refusesInvalidTargetsOrMalformedFilesWithoutReplacingThem() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, "broken configuration");
        assertThrows(RuntimeException.class, () -> FoodReserveConfigStore.update(file, 0));
        assertEquals("broken configuration", Files.readString(file));
        Files.writeString(file, "{\"npcFoodReserveCount\":8}");
        assertThrows(IllegalArgumentException.class, () -> FoodReserveConfigStore.update(file, 65));
        assertThrows(IllegalArgumentException.class, () -> FoodReserveConfigStore.update(file, -1));
        assertEquals("{\"npcFoodReserveCount\":8}", Files.readString(file));
    }
}
