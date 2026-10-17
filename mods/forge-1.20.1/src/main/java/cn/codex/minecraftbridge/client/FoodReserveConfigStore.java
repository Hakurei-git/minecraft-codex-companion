package cn.codex.minecraftbridge.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Updates only the requested setting and preserves all other local fields. */
final class FoodReserveConfigStore {
    private FoodReserveConfigStore() { }

    static void update(Path file, int count) throws IOException {
        if (count < 0 || count > 64) throw new IllegalArgumentException("Food reserve must be between 0 and 64");
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("npcFoodReserveCount", count);
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "food-reserve-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
