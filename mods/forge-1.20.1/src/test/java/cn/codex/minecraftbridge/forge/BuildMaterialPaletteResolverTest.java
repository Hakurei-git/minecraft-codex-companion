package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildMaterialPaletteResolverTest {
    @Test
    void acceptsOnlyCompleteVersionTwoCachedMetadata() {
        JsonObject plan = validPlan();

        assertTrue(BuildMaterialPaletteResolver.cachedMetadataStructureError(plan).isBlank());

        plan.getAsJsonObject("_codexMaterialPalette").addProperty("version", 1);
        assertFalse(BuildMaterialPaletteResolver.cachedMetadataStructureError(plan).isBlank());
    }

    @Test
    void rejectsDamagedReplacementAndCountMaps() {
        JsonObject plan = validPlan();
        plan.getAsJsonObject("_codexMaterialPalette")
            .getAsJsonObject("replacements")
            .add("minecraft:oak_planks", new JsonObject());
        assertFalse(BuildMaterialPaletteResolver.cachedMetadataStructureError(plan).isBlank());

        plan = validPlan();
        plan.getAsJsonObject("_codexMaterialPalette")
            .getAsJsonObject("requiredByItem")
            .addProperty("minecraft:oak_planks", -1);
        assertFalse(BuildMaterialPaletteResolver.cachedMetadataStructureError(plan).isBlank());
    }

    private static JsonObject validPlan() {
        JsonObject plan = new JsonObject();
        plan.add("blocks", new JsonArray());
        JsonObject metadata = new JsonObject();
        metadata.addProperty("version", 2);
        metadata.addProperty("source", "auto");
        metadata.addProperty("allowMixed", false);
        metadata.add("replacements", new JsonObject());
        metadata.add("fallbacks", new JsonObject());
        metadata.add("inventory", new JsonObject());
        metadata.add("home", new JsonObject());
        metadata.add("nearby", new JsonObject());
        metadata.add("requiredByItem", new JsonObject());
        metadata.addProperty("requiredCount", 0);
        metadata.addProperty("availableCount", 0);
        metadata.addProperty("missingCount", 0);
        plan.add("_codexMaterialPalette", metadata);
        return plan;
    }
}
