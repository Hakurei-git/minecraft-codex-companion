package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a build template's safe structural materials from the live Forge
 * registry and recipe graph.  It intentionally never changes geometry or
 * block state values; it only maps compatible structural roles (planks,
 * slabs, logs, fences, masonry, and their recipe-derived siblings).
 */
@SuppressWarnings("deprecation")
final class BuildMaterialPaletteResolver {
    private static final int RESOURCE_SCAN_RADIUS = 12;
    private static final int RESOURCE_SCAN_MIN_Y = -4;
    private static final int RESOURCE_SCAN_MAX_Y = 16;
    private static final int RESOURCE_SCAN_MAX_LOGS = 256;
    private static final int NATURAL_TREE_MAX_LOGS = 256;
    private static final int HOME_BUILD_PROTECTION_RADIUS = 16;
    private static RecipeManager cachedAuditManager;
    private static int cachedAuditRecipeCount = -1;
    private static int cachedAuditItemCount = -1;
    private static List<AuditFamily> cachedAuditFamilies = List.of();

    private enum Category { WOOD, MASONRY }

    private enum Role {
        LOG, BASE, SLAB, STAIRS, FENCE, GATE, DOOR, TRAPDOOR,
        BUTTON, PRESSURE_PLATE, WALL, OTHER
    }

    private record Preference(String source, String preferredBlockId, boolean allowMixed) {
        static Preference from(JsonObject spec) {
            if (!spec.has("materialPreference") || !spec.get("materialPreference").isJsonObject()) {
                return new Preference("auto", "", false);
            }
            JsonObject value = spec.getAsJsonObject("materialPreference");
            String source = value.has("source") ? value.get("source").getAsString() : "auto";
            if (!Set.of("auto", "inventory", "home", "nearby").contains(source)) source = "auto";
            String preferred = value.has("preferredBlockId") ? value.get("preferredBlockId").getAsString().trim() : "";
            return new Preference(source, preferred, value.has("allowMixed") && value.get("allowMixed").getAsBoolean());
        }
    }

    private record Family(
        String baseId,
        Category category,
        Map<Role, String> components,
        Map<String, BuildMaterialPalettePolicy.Conversion> sourceConversions,
        Map<String, BuildMaterialPalettePolicy.Conversion> componentConversions
    ) {
        String component(Role role) {
            String exact = components.get(role);
            if (exact != null) return exact;
            if (category == Category.MASONRY && role == Role.LOG) return components.get(Role.BASE);
            if (role == Role.FENCE) return components.get(Role.WALL);
            return null;
        }

        boolean matches(String id) {
            return baseId.equals(id) || components.containsValue(id) || sourceConversions.containsKey(id);
        }
    }

    static record Result(JsonArray blocks, JsonObject metadata, String summary, String error) {
        boolean changed() {
            return metadata != null && metadata.has("replacements") && metadata.getAsJsonObject("replacements").size() > 0;
        }
    }

    static record CachedResult(JsonObject metadata, String error) {}

    /** A registry-derived family exposed only to the loopback live fixture. */
    static record AuditFamily(
        String category,
        String baseId,
        String sourceId,
        List<String> blockIds,
        boolean supported,
        String skipReason
    ) {}

    private record Counts(Map<String, Integer> inventory, Map<String, Integer> home, Map<String, Integer> nearby) {
        Map<String, Integer> selected(String source) {
            return switch (source) {
                case "inventory" -> inventory;
                case "home" -> home;
                case "nearby" -> nearby;
                default -> inventory;
            };
        }
    }

    private record RecipeIndex(
        Collection<Recipe<?>> all,
        Map<String, List<Recipe<?>>> byOutput,
        Map<String, List<Recipe<?>>> byIngredient
    ) {
        static RecipeIndex create(ServerLevel level) {
            Collection<Recipe<?>> recipes = level.getRecipeManager().getRecipes();
            Map<String, LinkedHashSet<Recipe<?>>> outputs = new LinkedHashMap<>();
            Map<String, LinkedHashSet<Recipe<?>>> inputs = new LinkedHashMap<>();
            for (Recipe<?> recipe : recipes) {
                ItemStack output = recipe.getResultItem(level.registryAccess());
                if (!output.isEmpty()) outputs.computeIfAbsent(id(output), ignored -> new LinkedHashSet<>()).add(recipe);
                for (Ingredient ingredient : recipe.getIngredients()) {
                    for (ItemStack input : ingredient.getItems()) {
                        if (!input.isEmpty()) {
                            inputs.computeIfAbsent(id(input), ignored -> new LinkedHashSet<>()).add(recipe);
                        }
                    }
                }
            }
            return new RecipeIndex(
                List.copyOf(recipes),
                immutableRecipeIndex(outputs),
                immutableRecipeIndex(inputs)
            );
        }

        List<Recipe<?>> producing(String itemId) {
            return byOutput.getOrDefault(itemId, List.of());
        }

        List<Recipe<?>> using(String itemId) {
            return byIngredient.getOrDefault(itemId, List.of());
        }
    }

    private BuildMaterialPaletteResolver() {}

    static synchronized List<AuditFamily> auditFamilies(ServerLevel level) {
        RecipeManager manager = level.getRecipeManager();
        int recipeCount = manager.getRecipes().size();
        int itemCount = BuiltInRegistries.ITEM.size();
        if (manager == cachedAuditManager
            && recipeCount == cachedAuditRecipeCount
            && itemCount == cachedAuditItemCount
            && !cachedAuditFamilies.isEmpty()) return cachedAuditFamilies;
        RecipeIndex recipes = RecipeIndex.create(level);
        Map<String, AuditFamily> audited = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (!stack.is(ItemTags.PLANKS)) continue;
            String baseId = id(stack);
            Family family = buildFamily(level, recipes, baseId, Category.WOOD);
            audited.put("wood:" + baseId, auditFamily(baseId, Category.WOOD, family));
        }
        for (Recipe<?> recipe : recipes.all()) {
            if (recipe.getType() != RecipeType.CRAFTING) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            Role role = roleFor(level, id(output));
            if (role != Role.SLAB && role != Role.STAIRS && role != Role.WALL) continue;
            for (String baseId : ingredientBlockCandidates(recipe.getIngredients())) {
                if (new ItemStack(item(baseId)).is(ItemTags.PLANKS) || isLogId(baseId)) continue;
                String key = "masonry:" + baseId;
                if (audited.containsKey(key)) continue;
                Family family = buildFamily(level, recipes, baseId, Category.MASONRY);
                if (family != null) audited.put(key, auditFamily(baseId, Category.MASONRY, family));
            }
        }
        List<AuditFamily> result = audited.values().stream()
            .sorted(Comparator.comparing(AuditFamily::category).thenComparing(AuditFamily::baseId))
            .toList();
        cachedAuditManager = manager;
        cachedAuditRecipeCount = recipeCount;
        cachedAuditItemCount = itemCount;
        cachedAuditFamilies = result;
        return result;
    }

    private static AuditFamily auditFamily(String baseId, Category category, Family family) {
        List<Role> required = category == Category.WOOD
            ? List.of(Role.BASE, Role.STAIRS, Role.SLAB, Role.FENCE, Role.TRAPDOOR, Role.PRESSURE_PLATE)
            : List.of(Role.BASE, Role.STAIRS, Role.SLAB);
        if (family == null) {
            return new AuditFamily(
                category.name().toLowerCase(Locale.ROOT),
                baseId,
                "",
                List.of(),
                false,
                "missing-safe-base-or-slab"
            );
        }
        List<String> missing = required.stream()
            .filter(role -> family.component(role) == null)
            .map(role -> role.name().toLowerCase(Locale.ROOT))
            .toList();
        String sourceId = category == Category.WOOD ? preferredAuditSource(family) : family.baseId();
        if (sourceId == null || sourceId.isBlank()) {
            missing = new java.util.ArrayList<>(missing);
            missing.add("natural-source");
        }
        boolean supported = missing.isEmpty();
        List<String> blocks = supported
            ? required.stream().map(family::component).toList()
            : List.of();
        return new AuditFamily(
            category.name().toLowerCase(Locale.ROOT),
            family.baseId(),
            sourceId == null ? "" : sourceId,
            blocks,
            supported,
            supported ? "" : "missing-" + String.join("+", missing)
        );
    }

    private static String preferredAuditSource(Family family) {
        return family.sourceConversions().entrySet().stream()
            .sorted(Comparator
                .<Map.Entry<String, BuildMaterialPalettePolicy.Conversion>>comparingDouble(entry ->
                    -(double) entry.getValue().outputCount() / entry.getValue().inputCount()
                )
                .thenComparingInt(entry -> naturalSourceRank(entry.getKey()))
                .thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    static Result resolve(CodexNpcEntity npc, JsonObject spec, JsonArray originalBlocks, BlockPos origin) {
        Preference preference = Preference.from(spec);
        if (originalBlocks == null || originalBlocks.isEmpty()) {
            return new Result(originalBlocks == null ? new JsonArray() : originalBlocks.deepCopy(), new JsonObject(), "没有需要调色的结构方块", "");
        }
        if (!(npc.level() instanceof ServerLevel level)) {
            return new Result(originalBlocks.deepCopy(), new JsonObject(), "当前世界不是服务端世界，保留原始材料", "");
        }
        List<Family> families = discoverFamilies(level);
        if (families.isEmpty()) {
            return new Result(originalBlocks.deepCopy(), new JsonObject(), "没有发现可安全解析的材料族，保留原始蓝图", "");
        }

        Set<String> ids = new LinkedHashSet<>();
        for (var element : originalBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (block.has("blockId")) ids.add(block.get("blockId").getAsString());
        }
        Set<String> relevantIds = new HashSet<>();
        Set<String> naturalSources = new HashSet<>();
        for (Family family : families) {
            relevantIds.add(family.baseId());
            relevantIds.addAll(family.components().values());
            relevantIds.addAll(family.sourceConversions().keySet());
            naturalSources.addAll(family.sourceConversions().keySet());
        }
        Counts counts = collectCounts(npc, level, origin, relevantIds, naturalSources);
        Map<String, String> replacements = new LinkedHashMap<>();
        Map<String, String> fallbacks = new LinkedHashMap<>();
        Set<String> selectedLabels = new LinkedHashSet<>();
        Set<Family> selectedFamilies = new LinkedHashSet<>();
        Map<String, Map<String, Integer>> remainingBySource = new LinkedHashMap<>();
        remainingBySource.put("inventory", new HashMap<>(counts.inventory()));
        remainingBySource.put("home", new HashMap<>(counts.home()));
        remainingBySource.put("nearby", new HashMap<>(counts.nearby()));
        List<Family> preferredFamilies = preference.preferredBlockId().isBlank()
            ? List.of()
            : families.stream().filter(family -> family.matches(preference.preferredBlockId())).toList();
        if (!preference.preferredBlockId().isBlank() && preferredFamilies.isEmpty()) {
            return new Result(
                originalBlocks.deepCopy(),
                new JsonObject(),
                "",
                "找不到指定建筑材料 " + preference.preferredBlockId()
            );
        }

        for (Category category : Category.values()) {
            Set<Role> required = requiredRoles(level, ids, category);
            if (required.isEmpty()) continue;
            List<Family> categoryFamilies = families.stream()
                .filter(family -> family.category() == category)
                .toList();
            boolean preferredCategory = preferredFamilies.stream().anyMatch(family -> family.category() == category);
            if (preference.preferredBlockId().isBlank() && preference.allowMixed()) {
                selectMixedMaterials(
                    categoryFamilies,
                    preference,
                    remainingBySource,
                    ids,
                    originalBlocks,
                    level,
                    category,
                    replacements,
                    selectedLabels,
                    selectedFamilies
                );
                continue;
            }
            List<Family> candidates = preferredCategory
                ? preferredFamilies.stream().filter(family -> family.category() == category).toList()
                : categoryFamilies.stream()
                    .filter(family -> compatible(family, required, originalBlocks, level, category))
                    .toList();
            Preference categoryPreference = preferredCategory
                ? preference
                : new Preference(preference.source(), "", preference.allowMixed());
            Family selected = chooseFamily(
                candidates,
                categoryPreference,
                counts,
                ids,
                originalBlocks,
                level,
                category
            );
            if (selected == null) {
                continue;
            }
            selectedFamilies.add(selected);
            selectedLabels.add(selected.baseId());
            for (String originalId : ids) {
                Role role = roleFor(level, originalId);
                if (role == Role.OTHER || categoryFor(level, originalId) != category) continue;
                String target = selected.component(role);
                if (role == Role.LOG && selected.matches(originalId)) target = originalId;
                if (target == null) {
                    if (preferredCategory) fallbacks.put(originalId, "指定材料族缺少 " + role.name().toLowerCase(Locale.ROOT));
                    continue;
                }
                if (!isSafeStructuralBlock(level, target)) {
                    if (preferredCategory) fallbacks.put(originalId, "目标构件未通过结构安全检查");
                    continue;
                }
                if (!propertiesCompatible(originalBlocks, originalId, target)) {
                    if (preferredCategory) fallbacks.put(originalId, "目标构件不支持原蓝图状态属性");
                    continue;
                }
                if (!target.equals(originalId)) replacements.put(originalId, target);
            }
        }

        JsonArray transformed = new JsonArray();
        for (var element : originalBlocks) {
            if (!element.isJsonObject()) {
                transformed.add(element.deepCopy());
                continue;
            }
            JsonObject copy = element.getAsJsonObject().deepCopy();
            String originalId = copy.has("blockId") ? copy.get("blockId").getAsString() : "";
            String targetId = replacements.get(originalId);
            if (targetId != null && !targetId.equals(originalId)) {
                copy.addProperty("blockId", targetId);
            }
            transformed.add(copy);
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("version", 2);
        metadata.addProperty("source", preference.source());
        metadata.addProperty("allowMixed", preference.allowMixed());
        if (!preference.preferredBlockId().isBlank()) metadata.addProperty("preferredBlockId", preference.preferredBlockId());
        JsonObject replacementJson = new JsonObject();
        replacements.forEach(replacementJson::addProperty);
        metadata.add("replacements", replacementJson);
        JsonObject fallbackJson = new JsonObject();
        fallbacks.forEach(fallbackJson::addProperty);
        metadata.add("fallbacks", fallbackJson);
        Map<String, Integer> requiredByItem = new HashMap<>();
        for (var element : transformed) {
            if (!element.isJsonObject()) continue;
            JsonObject value = element.getAsJsonObject();
            String blockId = value.has("blockId") ? value.get("blockId").getAsString() : "";
            if (roleFor(level, blockId) != Role.OTHER
                && BuildMaterialPalettePolicy.consumesPlacementItem(blockId, stateProperties(value))) {
                add(requiredByItem, BuildPlacementPolicy.materialItemId(blockId), 1);
            }
        }
        Set<String> reportRelevant = new LinkedHashSet<>(requiredByItem.keySet());
        for (Family family : selectedFamilies) {
            reportRelevant.add(family.baseId());
            reportRelevant.addAll(family.components().values());
            reportRelevant.addAll(family.sourceConversions().keySet());
        }
        Map<String, Integer> reportInventory = BuildMaterialPalettePolicy.retainRelevant(counts.inventory(), reportRelevant);
        Map<String, Integer> reportHome = BuildMaterialPalettePolicy.retainRelevant(counts.home(), reportRelevant);
        Map<String, Integer> reportNearby = BuildMaterialPalettePolicy.retainRelevant(counts.nearby(), reportRelevant);
        metadata.add("inventory", countsToJson(reportInventory));
        metadata.add("home", countsToJson(reportHome));
        metadata.add("nearby", countsToJson(reportNearby));
        metadata.add("requiredByItem", countsToJson(requiredByItem));
        int requiredCount = requiredByItem.values().stream().mapToInt(Integer::intValue).sum();
        int availableCount = estimateAvailable(requiredByItem, selectedFamilies, reportInventory, reportHome);
        int missingCount = Math.max(0, requiredCount - availableCount);
        metadata.addProperty("requiredCount", requiredCount);
        metadata.addProperty("availableCount", availableCount);
        metadata.addProperty("missingCount", missingCount);
        String mappingSummary = replacements.entrySet().stream()
            .limit(4)
            .map(entry -> entry.getKey() + "->" + entry.getValue())
            .reduce((left, right) -> left + "、" + right)
            .orElse("");
        String summary = replacements.isEmpty()
            ? "未替换结构材料，使用原始蓝图"
            : "采用材料族 " + String.join("、", selectedLabels) + "；主要替换 " + mappingSummary;
        summary += "；已有主要建材 " + availableCount + "/" + requiredCount + "，仍缺 " + missingCount;
        if (!fallbacks.isEmpty()) summary += "；" + fallbacks.size() + " 种不兼容构件保留原蓝图";
        summary += "，已锁定调色板";
        metadata.addProperty("summary", summary);
        return new Result(transformed, metadata, summary, "");
    }

    static JsonObject metadataFromPlan(JsonObject plan) {
        return plan.has("_codexMaterialPalette") && plan.get("_codexMaterialPalette").isJsonObject()
            ? plan.getAsJsonObject("_codexMaterialPalette") : null;
    }

    static CachedResult validateCachedMetadata(ServerLevel level, JsonObject plan) {
        if (!plan.has("_codexMaterialPalette")) return new CachedResult(null, "");
        String structureError = cachedMetadataStructureError(plan);
        if (!structureError.isBlank()) return new CachedResult(null, structureError);
        JsonObject metadata = plan.getAsJsonObject("_codexMaterialPalette");
        JsonObject replacements = metadata.getAsJsonObject("replacements");
        Set<String> replacementTargets = new HashSet<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : replacements.entrySet()) {
            String source = entry.getKey();
            String target = entry.getValue().getAsString();
            if (!validResourceLocation(source) || !validResourceLocation(target) || source.equals(target)) {
                return new CachedResult(null, "建筑调色板缓存包含无效替换项");
            }
            if (replacements.has(target)) {
                return new CachedResult(null, "建筑调色板缓存包含链式替换，无法安全恢复");
            }
            if (!isSafeStructuralBlock(level, target)) {
                return new CachedResult(null, "建筑调色板缓存指向不安全或不存在的材料：" + target);
            }
            replacementTargets.add(target);
        }

        JsonArray blocks = plan.getAsJsonArray("blocks");
        Set<String> currentIds = new HashSet<>();
        Map<String, Integer> requiredByItem = new HashMap<>();
        for (var element : blocks) {
            if (!element.isJsonObject()) return new CachedResult(null, "建筑调色板缓存对应的蓝图方块格式无效");
            JsonObject block = element.getAsJsonObject();
            if (!block.has("blockId") || !block.get("blockId").isJsonPrimitive()) {
                return new CachedResult(null, "建筑调色板缓存对应的蓝图缺少方块标识");
            }
            String blockId = block.get("blockId").getAsString();
            if (!validResourceLocation(blockId)) return new CachedResult(null, "建筑蓝图包含无效方块标识：" + blockId);
            currentIds.add(blockId);
            if (replacementTargets.contains(blockId) && !propertiesCompatible(block, blockId)) {
                return new CachedResult(null, "缓存替换材料不支持蓝图状态属性：" + blockId);
            }
            if (roleFor(level, blockId) != Role.OTHER
                && BuildMaterialPalettePolicy.consumesPlacementItem(blockId, stateProperties(block))) {
                add(requiredByItem, BuildPlacementPolicy.materialItemId(blockId), 1);
            }
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : replacements.entrySet()) {
            String source = entry.getKey();
            String target = entry.getValue().getAsString();
            if (!currentIds.contains(target) || currentIds.contains(source)) {
                return new CachedResult(null, "建筑调色板缓存与已变换蓝图不一致，已拒绝盲目重算");
            }
        }
        if (!countMap(metadata.getAsJsonObject("requiredByItem")).equals(requiredByItem)) {
            return new CachedResult(null, "建筑调色板缓存的材料账本与蓝图不一致");
        }
        int requiredCount = requiredByItem.values().stream().mapToInt(Integer::intValue).sum();
        int cachedRequired = metadata.get("requiredCount").getAsInt();
        int available = metadata.get("availableCount").getAsInt();
        int missing = metadata.get("missingCount").getAsInt();
        if (cachedRequired != requiredCount || available > requiredCount || missing != requiredCount - available) {
            return new CachedResult(null, "建筑调色板缓存的材料总数校验失败");
        }
        return new CachedResult(metadata, "");
    }

    static String cachedMetadataStructureError(JsonObject plan) {
        if (!plan.has("_codexMaterialPalette")) return "";
        if (!plan.get("_codexMaterialPalette").isJsonObject()) return "建筑调色板缓存格式无效";
        if (!plan.has("blocks") || !plan.get("blocks").isJsonArray()) return "建筑调色板缓存缺少对应蓝图";
        JsonObject metadata = plan.getAsJsonObject("_codexMaterialPalette");
        if (!isInteger(metadata, "version") || metadata.get("version").getAsInt() != 2) {
            return "建筑调色板缓存版本过旧或无效；没有原始蓝图时无法安全重算";
        }
        if (!isString(metadata, "source")
            || !Set.of("auto", "inventory", "home", "nearby").contains(metadata.get("source").getAsString())) {
            return "建筑调色板缓存的来源设置无效";
        }
        if (!metadata.has("allowMixed")
            || !metadata.get("allowMixed").isJsonPrimitive()
            || !metadata.getAsJsonPrimitive("allowMixed").isBoolean()) {
            return "建筑调色板缓存的混合材料设置无效";
        }
        for (String key : List.of("replacements", "fallbacks", "inventory", "home", "nearby", "requiredByItem")) {
            if (!metadata.has(key) || !metadata.get(key).isJsonObject()) return "建筑调色板缓存缺少对象字段：" + key;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry
            : metadata.getAsJsonObject("replacements").entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                return "建筑调色板缓存替换项格式无效";
            }
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry
            : metadata.getAsJsonObject("fallbacks").entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                return "建筑调色板缓存回退项格式无效";
            }
        }
        for (String key : List.of("inventory", "home", "nearby", "requiredByItem")) {
            if (!validCountMap(metadata.getAsJsonObject(key))) return "建筑调色板缓存计数字段无效：" + key;
        }
        for (String key : List.of("requiredCount", "availableCount", "missingCount")) {
            if (!isInteger(metadata, key) || metadata.get(key).getAsInt() < 0) {
                return "建筑调色板缓存总数字段无效：" + key;
            }
        }
        return "";
    }

    private static void selectMixedMaterials(
        List<Family> families,
        Preference preference,
        Map<String, Map<String, Integer>> remainingBySource,
        Set<String> templateIds,
        JsonArray originalBlocks,
        ServerLevel level,
        Category category,
        Map<String, String> replacements,
        Set<String> selectedLabels,
        Set<Family> selectedFamilies
    ) {
        List<String> sourceOrder = "auto".equals(preference.source())
            ? List.of("inventory", "home", "nearby")
            : List.of(preference.source());
        for (String originalId : templateIds) {
            Role role = roleFor(level, originalId);
            if (role == Role.OTHER || categoryFor(level, originalId) != category) continue;
            Map<String, Family> familyById = new LinkedHashMap<>();
            List<BuildMaterialPalettePolicy.MaterialCandidate> candidates = new java.util.ArrayList<>();
            for (Family family : families) {
                String target = targetFor(family, role, originalId);
                if (target == null
                    || !isSafeStructuralBlock(level, target)
                    || !propertiesCompatible(originalBlocks, originalId, target)) continue;
                familyById.put(family.baseId(), family);
                candidates.add(new BuildMaterialPalettePolicy.MaterialCandidate(
                    family.baseId(),
                    BuildPlacementPolicy.materialItemId(target),
                    BuildPlacementPolicy.materialItemId(family.baseId()),
                    family.componentConversions().get(target),
                    family.sourceConversions()
                ));
            }
            if (candidates.isEmpty()) continue;

            int required = placementCount(originalBlocks, originalId);
            List<Map<String, Integer>> sourceStocks = sourceOrder.stream()
                .map(remainingBySource::get)
                .toList();
            BuildMaterialPalettePolicy.MaterialSelection selection =
                BuildMaterialPalettePolicy.selectAndConsumeByPriority(candidates, required, sourceStocks);

            Family selected = selection == null ? families.stream()
                .filter(family -> family.matches(originalId))
                .filter(family -> familyById.containsKey(family.baseId()))
                .findFirst()
                .orElse(null) : familyById.get(selection.candidate().familyId());
            if (selected == null) continue;

            String target = targetFor(selected, role, originalId);
            selectedFamilies.add(selected);
            selectedLabels.add(selected.baseId());
            if (target != null && !target.equals(originalId)) replacements.put(originalId, target);
        }
    }

    private static String targetFor(Family family, Role role, String originalId) {
        String target = family.component(role);
        return role == Role.LOG && family.matches(originalId) ? originalId : target;
    }

    private static int placementCount(JsonArray blocks, String originalId) {
        int result = 0;
        for (var element : blocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (block.has("blockId")
                && originalId.equals(block.get("blockId").getAsString())
                && BuildMaterialPalettePolicy.consumesPlacementItem(originalId, stateProperties(block))) {
                result = saturatedAdd(result, 1);
            }
        }
        return result;
    }

    private static Family chooseFamily(
        List<Family> candidates,
        Preference preference,
        Counts counts,
        Set<String> templateIds,
        JsonArray originalBlocks,
        ServerLevel level,
        Category category
    ) {
        if (candidates.isEmpty()) return null;
        if (!preference.preferredBlockId().isBlank()) {
            return candidates.stream()
                .filter(family -> family.matches(preference.preferredBlockId()))
                .max(Comparator.comparingInt(family -> familyCoverage(
                    counts, preference.source(), family, originalBlocks, level, category
                )))
                .orElse(null);
        }
        Family original = candidates.stream()
            .filter(family -> templateIds.stream().anyMatch(family::matches))
            .findFirst().orElse(null);
        if (!"auto".equals(preference.source())) {
            Family selected = candidates.stream()
                .max(Comparator.comparingInt((Family family) -> familyCoverage(
                        counts, preference.source(), family, originalBlocks, level, category
                    ))
                    .thenComparing(family -> family.baseId()))
                .orElse(null);
            return selected != null && familyCoverage(
                counts, preference.source(), selected, originalBlocks, level, category
            ) > 0 ? selected : original;
        }
        for (String source : List.of("inventory", "home", "nearby")) {
            List<Family> available = candidates.stream().filter(family -> familyCoverage(
                counts, source, family, originalBlocks, level, category
            ) > 0).toList();
            if (!available.isEmpty()) {
                return available.stream()
                    .max(Comparator.comparingInt((Family family) -> familyCoverage(
                            counts, source, family, originalBlocks, level, category
                        ))
                        .thenComparing(family -> family.baseId()))
                    .orElse(null);
            }
        }
        return original;
    }

    private static int familyCoverage(
        Counts counts,
        String source,
        Family family,
        JsonArray originalBlocks,
        ServerLevel level,
        Category category
    ) {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (var element : originalBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String originalId = entry.has("blockId") ? entry.get("blockId").getAsString() : "";
            Role role = roleFor(level, originalId);
            if (role == Role.OTHER
                || categoryFor(level, originalId) != category
                || !BuildMaterialPalettePolicy.consumesPlacementItem(originalId, stateProperties(entry))) continue;
            String target = family.component(role);
            if (role == Role.LOG && family.matches(originalId)) target = originalId;
            if (target != null) add(required, BuildPlacementPolicy.materialItemId(target), 1);
        }
        return estimateAvailable(required, List.of(family), counts.selected(source), Map.of());
    }

    private static boolean compatible(
        Family family,
        Set<Role> roles,
        JsonArray originalBlocks,
        ServerLevel level,
        Category category
    ) {
        for (Role role : roles) {
            String target = family.component(role);
            if (target == null || !isSafeStructuralBlock(level, target)) return false;
        }
        for (var element : originalBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            String originalId = entry.has("blockId") ? entry.get("blockId").getAsString() : "";
            Role role = roleFor(level, originalId);
            if (role == Role.OTHER || categoryFor(level, originalId) != category) continue;
            String target = family.component(role);
            if (target == null || !propertiesCompatible(entry, target)) return false;
        }
        return true;
    }

    private static Set<Role> requiredRoles(ServerLevel level, Set<String> ids, Category category) {
        Set<Role> roles = new LinkedHashSet<>();
        for (String id : ids) if (categoryFor(level, id) == category) {
            Role role = roleFor(level, id);
            if (role != Role.OTHER) roles.add(role);
        }
        return roles;
    }

    private static Category categoryFor(ServerLevel level, String id) {
        Block block = block(id);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) return Category.MASONRY;
        BlockState state = block.defaultBlockState();
        if (state.is(net.minecraft.tags.BlockTags.LOGS)
            || new ItemStack(block.asItem()).is(ItemTags.LOGS)
            || new ItemStack(block.asItem()).is(ItemTags.PLANKS)
            || hasTag(state, "wooden_slabs")
            || hasTag(state, "wooden_stairs")
            || hasTag(state, "wooden_fences")
            || hasTag(state, "fence_gates")
            || hasTag(state, "wooden_doors")
            || hasTag(state, "wooden_trapdoors")
            || hasTag(state, "wooden_buttons")
            || hasTag(state, "wooden_pressure_plates")) return Category.WOOD;
        return Category.MASONRY;
    }

    private static Role roleFor(ServerLevel level, String id) {
        Block block = block(id);
        if (block == null) return Role.OTHER;
        BlockState state = block.defaultBlockState();
        ItemStack stack = new ItemStack(block.asItem());
        String path = path(id);
        if (state.is(net.minecraft.tags.BlockTags.LOGS) || stack.is(ItemTags.LOGS)
            || path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")) return Role.LOG;
        if (stack.is(ItemTags.PLANKS) || path.endsWith("_planks")) return Role.BASE;
        if (hasTag(state, "wooden_slabs") || hasTag(state, "slabs") || path.endsWith("_slab")) return Role.SLAB;
        if (hasTag(state, "wooden_stairs") || hasTag(state, "stairs") || path.endsWith("_stairs")) return Role.STAIRS;
        if (hasTag(state, "fence_gates") || path.endsWith("_fence_gate")) return Role.GATE;
        if (hasTag(state, "wooden_fences") || hasTag(state, "fences") || path.endsWith("_fence")) return Role.FENCE;
        if (hasTag(state, "wooden_doors") || path.endsWith("_door")) return Role.DOOR;
        if (hasTag(state, "wooden_trapdoors") || path.endsWith("_trapdoor")) return Role.TRAPDOOR;
        if (hasTag(state, "wooden_buttons") || path.endsWith("_button")) return Role.BUTTON;
        if (hasTag(state, "wooden_pressure_plates") || path.endsWith("_pressure_plate")) return Role.PRESSURE_PLATE;
        if (hasTag(state, "walls") || path.endsWith("_wall")) return Role.WALL;
        if (path.endsWith("_bricks") || path.equals("bricks") || path.equals("stone") || path.equals("cobblestone")) return Role.BASE;
        return Role.OTHER;
    }

    private static List<Family> discoverFamilies(ServerLevel level) {
        RecipeIndex recipes = RecipeIndex.create(level);
        Map<String, Family> families = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack stack = new ItemStack(item);
            if (stack.is(ItemTags.PLANKS)) {
                Family family = buildFamily(level, recipes, id(stack), Category.WOOD);
                if (family != null) families.putIfAbsent(family.baseId(), family);
            }
        }
        for (Recipe<?> recipe : recipes.all()) {
            if (recipe.getType() != RecipeType.CRAFTING) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            Role role = roleFor(level, id(output));
            if (role != Role.SLAB && role != Role.STAIRS && role != Role.WALL) continue;
            for (String baseId : ingredientBlockCandidates(recipe.getIngredients())) {
                if (new ItemStack(item(baseId)).is(ItemTags.PLANKS) || isLogId(baseId)) continue;
                Family family = buildFamily(level, recipes, baseId, Category.MASONRY);
                if (family != null) families.putIfAbsent(family.baseId(), family);
            }
        }
        return List.copyOf(families.values());
    }

    private static Family buildFamily(ServerLevel level, RecipeIndex recipes, String baseId, Category category) {
        if (!isSafeStructuralBlock(level, baseId)) return null;
        Map<Role, String> components = new EnumMap<>(Role.class);
        components.put(Role.BASE, baseId);
        Map<String, BuildMaterialPalettePolicy.Conversion> sources = new LinkedHashMap<>();
        Map<String, BuildMaterialPalettePolicy.Conversion> componentConversions = new LinkedHashMap<>();
        componentConversions.put(baseId, new BuildMaterialPalettePolicy.Conversion(1, 1));
        for (Recipe<?> recipe : recipes.producing(baseId)) {
            if (recipe.getType() != RecipeType.CRAFTING) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            String outputId = id(output);
            if (!outputId.equals(baseId)) continue;
            for (String candidate : ingredientBlockCandidates(recipe.getIngredients())) {
                if (!isNaturalFamilySource(candidate) || !isSafeSourceBlock(level, candidate)) continue;
                BuildMaterialPalettePolicy.Conversion conversion = singleInputRecipeConversion(
                    recipe.getIngredients(), output.getCount(), candidate
                );
                if (conversion != null) mergeConversion(sources, candidate, conversion);
            }
        }
        for (Recipe<?> recipe : recipes.using(baseId)) {
            if (recipe.getType() != RecipeType.CRAFTING) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (output.isEmpty()) continue;
            String outputId = id(output);
            if (outputId.equals(baseId)) continue;
            Role role = roleFor(level, outputId);
            if (role == Role.OTHER || !usesExactOrExclusiveBase(recipe.getIngredients(), baseId)) continue;
            if (category == Category.WOOD && !familyRecipeAffinity(baseId, outputId)) continue;
            if (!isSafeStructuralBlock(level, outputId)) continue;
            BuildMaterialPalettePolicy.Conversion conversion = recipeConversion(
                recipe.getIngredients(), output.getCount(), baseId
            );
            if (conversion == null) continue;
            components.putIfAbsent(role, outputId);
            if (usesOnlyBaseIngredients(recipe.getIngredients(), baseId)) {
                mergeConversion(componentConversions, outputId, conversion);
            }
        }
        if (category == Category.WOOD && sources.isEmpty()) {
            // A few modded plank recipes expose only a tag ingredient. Inspect
            // the actual ingredient stacks rather than inventing a path.
            mergeConversions(sources, findTaggedLogSources(level, recipes.producing(baseId), baseId));
        }
        if (category == Category.WOOD && !sources.isEmpty()) {
            mergeConversions(sources, findTwoStepNaturalSources(level, recipes, baseId, sources));
        }
        if (category == Category.WOOD && !sources.isEmpty()) {
            String log = sources.keySet().stream()
                .sorted(Comparator.comparingInt(BuildMaterialPaletteResolver::naturalSourceRank).thenComparing(id -> id))
                .findFirst().orElse(null);
            if (log != null && isSafeSourceBlock(level, log)) components.put(Role.LOG, log);
        }
        if (category == Category.MASONRY && sources.isEmpty()) {
            sources.put(baseId, new BuildMaterialPalettePolicy.Conversion(1, 1));
        }
        if (!components.containsKey(Role.BASE) || !components.containsKey(Role.SLAB)) return null;
        return new Family(
            baseId,
            category,
            Map.copyOf(components),
            Map.copyOf(sources),
            Map.copyOf(componentConversions)
        );
    }

    private static Map<String, BuildMaterialPalettePolicy.Conversion> findTaggedLogSources(
        ServerLevel level,
        Collection<Recipe<?>> recipes,
        String baseId
    ) {
        Map<String, BuildMaterialPalettePolicy.Conversion> sources = new LinkedHashMap<>();
        for (Recipe<?> recipe : recipes) {
            if (recipe.getType() != RecipeType.CRAFTING) continue;
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (output.isEmpty() || !id(output).equals(baseId)) continue;
            for (Ingredient ingredient : recipe.getIngredients()) {
                for (ItemStack candidate : ingredient.getItems()) {
                    String candidateId = id(candidate);
                    if (!isNaturalFamilySource(candidateId) || !isSafeSourceBlock(level, candidateId)) continue;
                    BuildMaterialPalettePolicy.Conversion conversion = singleInputRecipeConversion(
                        recipe.getIngredients(), output.getCount(), candidateId
                    );
                    if (conversion != null) mergeConversion(sources, candidateId, conversion);
                }
            }
        }
        return Map.copyOf(sources);
    }

    private static Map<String, BuildMaterialPalettePolicy.Conversion> findTwoStepNaturalSources(
        ServerLevel level,
        RecipeIndex recipes,
        String baseId,
        Map<String, BuildMaterialPalettePolicy.Conversion> directSources
    ) {
        Map<String, BuildMaterialPalettePolicy.Conversion> result = new LinkedHashMap<>();
        for (Map.Entry<String, BuildMaterialPalettePolicy.Conversion> direct
            : List.copyOf(directSources.entrySet())) {
            String intermediateId = direct.getKey();
            for (Recipe<?> recipe : recipes.producing(intermediateId)) {
                if (recipe.getType() != RecipeType.CRAFTING) continue;
                ItemStack output = recipe.getResultItem(level.registryAccess());
                if (output.isEmpty() || !id(output).equals(intermediateId)) continue;
                for (String sourceId : ingredientBlockCandidates(recipe.getIngredients())) {
                    if (sourceId.equals(baseId)
                        || sourceId.equals(intermediateId)
                        || !isNaturalFamilySource(sourceId)
                        || !isSafeSourceBlock(level, sourceId)) continue;
                    BuildMaterialPalettePolicy.Conversion upstream = singleInputRecipeConversion(
                        recipe.getIngredients(), output.getCount(), sourceId
                    );
                    if (upstream == null) continue;
                    try {
                        mergeConversion(
                            result,
                            sourceId,
                            BuildMaterialPalettePolicy.composeConversion(upstream, direct.getValue())
                        );
                    } catch (ArithmeticException ignored) {
                        // Ignore pathological mod recipes whose composed batch would overflow an integer.
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static boolean isNaturalFamilySource(String id) {
        if (id.equals("minecraft:bamboo") || id.equals("minecraft:bamboo_block")) return true;
        Item item = item(id);
        if (item == Items.AIR) return false;
        ItemStack stack = new ItemStack(item);
        return stack.is(ItemTags.LOGS) || isLogId(id);
    }

    private static boolean isLogId(String id) {
        String path = path(id);
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae");
    }

    private static int naturalSourceRank(String id) {
        String path = path(id);
        int stripped = path.startsWith("stripped_") ? 4 : 0;
        int form = path.endsWith("_log") || path.endsWith("_stem") ? 0 : 1;
        return stripped + form;
    }

    private static boolean familyRecipeAffinity(String baseId, String outputId) {
        String basePath = path(baseId);
        String outputPath = path(outputId);
        String stem = basePath.endsWith("_planks") ? basePath.substring(0, basePath.length() - 7) : basePath;
        return outputPath.startsWith(stem + "_") || outputPath.equals(stem);
    }

    private static boolean usesExactOrExclusiveBase(List<Ingredient> ingredients, String baseId) {
        boolean used = false;
        for (Ingredient ingredient : ingredients) {
            ItemStack[] choices = ingredient.getItems();
            if (choices.length == 0) continue;
            boolean acceptsBase = ingredient.test(new ItemStack(item(baseId)));
            if (acceptsBase) {
                used = true;
                continue;
            }
            // Sticks, dyes and other recipe auxiliaries are allowed; this
            // method only rejects an ingredient that is itself a different
            // candidate structural block.
            if (choices.length == 1 && choices[0].getItem() instanceof BlockItem) return false;
        }
        return used;
    }

    private static boolean usesOnlyBaseIngredients(List<Ingredient> ingredients, String baseId) {
        ItemStack base = new ItemStack(item(baseId));
        boolean used = false;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.getItems().length == 0) continue;
            if (!ingredient.test(base)) return false;
            used = true;
        }
        return used;
    }

    private static Set<String> ingredientBlockCandidates(List<Ingredient> ingredients) {
        Set<String> result = new LinkedHashSet<>();
        for (Ingredient ingredient : ingredients) {
            for (ItemStack stack : ingredient.getItems()) {
                if (stack.getItem() instanceof BlockItem) result.add(id(stack));
            }
        }
        return result;
    }

    private static BuildMaterialPalettePolicy.Conversion recipeConversion(
        List<Ingredient> ingredients,
        int outputCount,
        String inputId
    ) {
        Item input = item(inputId);
        if (input == Items.AIR || outputCount <= 0) return null;
        ItemStack candidate = new ItemStack(input);
        int inputCount = 0;
        for (Ingredient ingredient : ingredients) if (ingredient.test(candidate)) inputCount++;
        return inputCount <= 0 ? null : new BuildMaterialPalettePolicy.Conversion(outputCount, inputCount);
    }

    private static BuildMaterialPalettePolicy.Conversion singleInputRecipeConversion(
        List<Ingredient> ingredients,
        int outputCount,
        String inputId
    ) {
        Item input = item(inputId);
        if (input == Items.AIR || outputCount <= 0) return null;
        ItemStack candidate = new ItemStack(input);
        int inputCount = 0;
        for (Ingredient ingredient : ingredients) {
            if (ingredient.getItems().length == 0) continue;
            if (!ingredient.test(candidate)) return null;
            inputCount++;
        }
        return inputCount <= 0 ? null : new BuildMaterialPalettePolicy.Conversion(outputCount, inputCount);
    }

    private static Map<String, List<Recipe<?>>> immutableRecipeIndex(
        Map<String, LinkedHashSet<Recipe<?>>> source
    ) {
        Map<String, List<Recipe<?>>> result = new LinkedHashMap<>();
        source.forEach((itemId, recipes) -> result.put(itemId, List.copyOf(recipes)));
        return Map.copyOf(result);
    }

    private static void mergeConversion(
        Map<String, BuildMaterialPalettePolicy.Conversion> conversions,
        String id,
        BuildMaterialPalettePolicy.Conversion candidate
    ) {
        conversions.compute(id, (ignored, current) -> BuildMaterialPalettePolicy.betterConversion(current, candidate));
    }

    private static void mergeConversions(
        Map<String, BuildMaterialPalettePolicy.Conversion> target,
        Map<String, BuildMaterialPalettePolicy.Conversion> source
    ) {
        source.forEach((id, conversion) -> mergeConversion(target, id, conversion));
    }

    private static Counts collectCounts(
        CodexNpcEntity npc,
        ServerLevel level,
        BlockPos origin,
        Set<String> relevant,
        Set<String> naturalSources
    ) {
        Map<String, Integer> inventory = new HashMap<>();
        for (int slot = 0; slot < npc.inventory().getSlots(); slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            String itemId = stack.isEmpty() ? "" : id(stack);
            if (relevant.contains(itemId)) add(inventory, itemId, stack.getCount());
        }
        Map<String, Integer> home = new HashMap<>();
        NpcHomeStorage.Home homeState = null;
        if (npc.owner() != null) {
            homeState = NpcHomeStorage.resolve(npc.owner());
            for (BlockPos position : NpcHomeStorage.findContainers(level, homeState, HomeStoragePolicy.DEFAULT_RADIUS)) {
                BlockEntity entity = level.getBlockEntity(position);
                if (!(entity instanceof Container container) || entity instanceof AbstractFurnaceBlockEntity) continue;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    String itemId = stack.isEmpty() ? "" : id(stack);
                    if (relevant.contains(itemId)) add(home, itemId, stack.getCount());
                }
            }
        }
        Map<String, Integer> nearby = new HashMap<>();
        if (origin != null) {
            Map<BlockPos, String> candidates = new LinkedHashMap<>();
            int minY = Math.max(level.getMinBuildHeight(), origin.getY() + RESOURCE_SCAN_MIN_Y);
            int maxY = Math.min(level.getMaxBuildHeight() - 1, origin.getY() + RESOURCE_SCAN_MAX_Y);
            scan:
            for (int x = origin.getX() - RESOURCE_SCAN_RADIUS; x <= origin.getX() + RESOURCE_SCAN_RADIUS; x++) {
                for (int z = origin.getZ() - RESOURCE_SCAN_RADIUS; z <= origin.getZ() + RESOURCE_SCAN_RADIUS; z++) {
                    BlockPos column = new BlockPos(x, origin.getY(), z);
                    if (!level.hasChunkAt(column)) continue;
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos position = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(position);
                        String blockId = id(state.getBlock());
                        if (!naturalSources.contains(blockId)
                            || !state.is(BlockTags.LOGS)
                            || !BuildMaterialPalettePolicy.naturalTrunkId(blockId)
                            || withinHomeProtection(level, homeState, position)) continue;
                        candidates.put(position.immutable(), blockId);
                        if (candidates.size() >= RESOURCE_SCAN_MAX_LOGS) break scan;
                    }
                }
            }
            Set<BlockPos> inspected = new HashSet<>();
            for (Map.Entry<BlockPos, String> entry : candidates.entrySet()) {
                if (inspected.contains(entry.getKey())) continue;
                NaturalTreeScanner.Cluster cluster = NaturalTreeScanner.inspect(
                    level, entry.getKey(), NATURAL_TREE_MAX_LOGS
                );
                inspected.addAll(cluster.logs());
                if (!cluster.natural()) continue;
                for (BlockPos log : cluster.logs()) {
                    String blockId = candidates.get(log);
                    if (blockId != null) add(nearby, blockId, 1);
                }
            }
        }
        return new Counts(Map.copyOf(inventory), Map.copyOf(home), Map.copyOf(nearby));
    }

    private static boolean withinHomeProtection(
        ServerLevel level,
        NpcHomeStorage.Home home,
        BlockPos position
    ) {
        return home != null
            && home.dimension().equals(level.dimension())
            && position.distSqr(home.position()) <= HOME_BUILD_PROTECTION_RADIUS * HOME_BUILD_PROTECTION_RADIUS;
    }

    private static boolean propertiesCompatible(JsonArray blocks, String originalId, String targetId) {
        for (var element : blocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (!block.has("blockId") || !block.get("blockId").getAsString().equals(originalId)) continue;
            if (!propertiesCompatible(block, targetId)) return false;
        }
        return true;
    }

    private static boolean propertiesCompatible(JsonObject block, String targetId) {
        Block target = block(targetId);
        if (target == null) return false;
        if (!block.has("properties") || !block.get("properties").isJsonObject()) return true;
        Map<String, String> requested = new LinkedHashMap<>();
        JsonObject properties = block.getAsJsonObject("properties");
        for (Map.Entry<String, com.google.gson.JsonElement> entry : properties.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) return false;
            requested.put(entry.getKey(), entry.getValue().getAsString());
        }
        Map<String, Set<String>> supported = new LinkedHashMap<>();
        for (Property<?> property : target.defaultBlockState().getProperties()) {
            supported.put(property.getName(), propertyValues(property));
        }
        return BuildMaterialPalettePolicy.propertiesCompatible(requested, supported);
    }

    private static Map<String, String> stateProperties(JsonObject block) {
        if (!block.has("properties") || !block.get("properties").isJsonObject()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
            : block.getAsJsonObject("properties").entrySet()) {
            if (entry.getValue().isJsonPrimitive()) result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(result);
    }

    private static <T extends Comparable<T>> Set<String> propertyValues(Property<T> property) {
        Set<String> values = new LinkedHashSet<>();
        for (T value : property.getPossibleValues()) values.add(property.getName(value));
        return Set.copyOf(values);
    }

    private static int estimateAvailable(
        Map<String, Integer> required,
        Collection<Family> families,
        Map<String, Integer> inventory,
        Map<String, Integer> home
    ) {
        Map<String, Integer> stock = new HashMap<>();
        inventory.forEach((id, count) -> add(stock, id, count));
        home.forEach((id, count) -> add(stock, id, count));
        Map<String, Integer> remaining = new HashMap<>(required);
        int covered = 0;

        // Exact blocks are always consumed before considering recipe-derived
        // equivalents, so the same plank or log cannot be counted twice.
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int direct = Math.min(entry.getValue(), stock.getOrDefault(entry.getKey(), 0));
            if (direct <= 0) continue;
            covered = saturatedAdd(covered, direct);
            remaining.put(entry.getKey(), entry.getValue() - direct);
            stock.put(entry.getKey(), stock.get(entry.getKey()) - direct);
        }

        for (Family family : families) {
            int basePool = stock.getOrDefault(family.baseId(), 0);
            stock.put(family.baseId(), 0);
            for (Map.Entry<String, BuildMaterialPalettePolicy.Conversion> source
                : family.sourceConversions().entrySet()) {
                if (source.getKey().equals(family.baseId())) continue;
                int input = stock.getOrDefault(source.getKey(), 0);
                stock.put(source.getKey(), 0);
                basePool = saturatedAdd(basePool, source.getValue().craftableOutput(input));
            }

            int baseNeed = remaining.getOrDefault(family.baseId(), 0);
            int baseUsed = Math.min(baseNeed, basePool);
            if (baseUsed > 0) {
                covered = saturatedAdd(covered, baseUsed);
                remaining.put(family.baseId(), baseNeed - baseUsed);
                basePool -= baseUsed;
            }

            for (Role role : Role.values()) {
                if (role == Role.BASE || role == Role.LOG || role == Role.OTHER) continue;
                String componentId = family.components().get(role);
                if (componentId == null) continue;
                int need = remaining.getOrDefault(componentId, 0);
                BuildMaterialPalettePolicy.Conversion conversion = family.componentConversions().get(componentId);
                if (need <= 0 || conversion == null || basePool < conversion.inputCount()) continue;
                int possibleBatches = basePool / conversion.inputCount();
                int neededBatches = (need + conversion.outputCount() - 1) / conversion.outputCount();
                int batches = Math.min(possibleBatches, neededBatches);
                int produced = Math.min(need, conversion.craftableOutput(
                    conversion.inputForBatches(batches)
                ));
                if (produced <= 0) continue;
                covered = saturatedAdd(covered, produced);
                remaining.put(componentId, need - produced);
                basePool -= conversion.inputForBatches(batches);
            }
        }
        int totalRequired = required.values().stream().mapToInt(Integer::intValue).sum();
        return Math.min(totalRequired, covered);
    }

    private static boolean hasTag(BlockState state, String path) {
        return state.is(TagKey.create(Registries.BLOCK, ResourceLocation.parse("minecraft:" + path)));
    }

    private static JsonObject countsToJson(Map<String, Integer> values) {
        JsonObject result = new JsonObject();
        values.forEach(result::addProperty);
        return result;
    }

    private static void add(Map<String, Integer> map, String id, int count) {
        map.merge(id, count, BuildMaterialPaletteResolver::saturatedAdd);
    }

    private static int saturatedAdd(int left, int right) {
        long value = (long) left + right;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private static boolean validCountMap(JsonObject value) {
        for (Map.Entry<String, com.google.gson.JsonElement> entry : value.entrySet()) {
            if (!validResourceLocation(entry.getKey())
                || !entry.getValue().isJsonPrimitive()
                || !entry.getValue().getAsJsonPrimitive().isNumber()) return false;
            try {
                int count = Integer.parseInt(entry.getValue().getAsString());
                if (count < 0) return false;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> countMap(JsonObject value) {
        Map<String, Integer> result = new HashMap<>();
        value.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsInt()));
        return Map.copyOf(result);
    }

    private static boolean isInteger(JsonObject value, String key) {
        if (!value.has(key)
            || !value.get(key).isJsonPrimitive()
            || !value.getAsJsonPrimitive(key).isNumber()) return false;
        try {
            Integer.parseInt(value.get(key).getAsString());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean isString(JsonObject value, String key) {
        return value.has(key)
            && value.get(key).isJsonPrimitive()
            && value.getAsJsonPrimitive(key).isString();
    }

    private static boolean validResourceLocation(String value) {
        return value != null && ResourceLocation.tryParse(value) != null;
    }

    private static String path(String id) {
        int separator = id.indexOf(':');
        return (separator < 0 ? id : id.substring(separator + 1)).toLowerCase(Locale.ROOT);
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static Item item(String id) {
        if (id == null || id.isBlank()) return Items.AIR;
        Item value = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return value == null ? Items.AIR : value;
    }

    private static Block block(String id) {
        if (id == null || id.isBlank()) return null;
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    private static boolean isSafeSourceBlock(ServerLevel level, String id) {
        return isNaturalFamilySource(id) && isSafeStructuralBlock(level, id);
    }

    private static boolean isSafeStructuralBlock(ServerLevel level, String id) {
        Block value = block(id);
        if (value == null
            || value == net.minecraft.world.level.block.Blocks.AIR
            || value.asItem() == Items.AIR
            || value instanceof EntityBlock
            || value instanceof net.minecraft.world.level.block.FallingBlock
            || value instanceof net.minecraft.world.level.block.BaseFireBlock
            || BuildMaterialPalettePolicy.unsafeStructuralId(id)) return false;
        BlockState state = value.defaultBlockState();
        return !state.isAir()
            && state.getFluidState().isEmpty()
            && state.getDestroySpeed(level, BlockPos.ZERO) >= 0.0F;
    }

}
