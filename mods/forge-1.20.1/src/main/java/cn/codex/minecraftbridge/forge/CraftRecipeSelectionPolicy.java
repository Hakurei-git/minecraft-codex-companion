package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Deterministic, data-only safety policy for runtime crafting recipes.
 *
 * <p>Forge packs may add multiple recipes for a vanilla output. Selecting the
 * first recipe returned by the recipe manager is unsafe: an alternative can
 * consume unrelated or reversible materials and make the dependency planner
 * oscillate forever. Player-facing vanilla equipment and core utility items
 * therefore accept only their vanilla recipe id and canonical ingredient
 * signature. Unknown/modded outputs retain the stable score-based fallback.</p>
 */
final class CraftRecipeSelectionPolicy {
    record Candidate(
        String recipeId,
        boolean inventoryReady,
        boolean inventoryCraftable,
        List<List<String>> ingredientOptions
    ) {
        Candidate {
            recipeId = normalize(recipeId);
            List<List<String>> normalized = new ArrayList<>();
            if (ingredientOptions != null) {
                for (List<String> options : ingredientOptions) {
                    normalized.add(options == null
                        ? List.of()
                        : options.stream().map(CraftRecipeSelectionPolicy::normalize)
                            .filter(value -> !value.isBlank() && !value.equals("minecraft:air"))
                            .distinct()
                            .toList());
                }
            }
            ingredientOptions = List.copyOf(normalized);
        }
    }

    private record ResourceForm(String family, int rank) {
    }

    private record IngredientRule(int count, Predicate<String> acceptedItem) {
        IngredientRule {
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
        }

        boolean acceptsEvery(List<String> options) {
            return !options.isEmpty() && options.stream().allMatch(acceptedItem);
        }
    }

    private static final Set<String> STONE_CRAFTING_MATERIALS = Set.of(
        "minecraft:cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:blackstone"
    );

    private static final Set<String> WOOL_COLORS = Set.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    private static final Set<String> FIXED_STRICT_TARGETS = Set.of(
        "minecraft:fishing_rod",
        "minecraft:bow",
        "minecraft:crossbow",
        "minecraft:arrow",
        "minecraft:shield",
        "minecraft:torch",
        "minecraft:soul_torch",
        "minecraft:crafting_table",
        "minecraft:chest",
        "minecraft:barrel",
        "minecraft:furnace",
        "minecraft:hopper",
        "minecraft:dispenser",
        "minecraft:dropper",
        "minecraft:shears",
        "minecraft:bucket",
        "minecraft:flint_and_steel",
        "minecraft:compass",
        "minecraft:clock",
        "minecraft:spyglass",
        "minecraft:brush",
        "minecraft:lead",
        "minecraft:item_frame",
        "minecraft:painting",
        "minecraft:ladder",
        "minecraft:tripwire_hook",
        "minecraft:campfire",
        "minecraft:turtle_helmet",
        // These deliberately have no crafting-table signature in 1.20.1.
        "minecraft:trident"
    );

    private static final Map<String, ResourceForm> REVERSIBLE_FORMS = reversibleForms();

    private CraftRecipeSelectionPolicy() {
    }

    static int choose(String outputItemId, List<Candidate> candidates) {
        String outputId = normalize(outputItemId);
        if (candidates == null || candidates.isEmpty()) return -1;
        int selected = -1;
        int selectedScore = Integer.MIN_VALUE;
        String selectedRecipeId = "";
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (candidate == null || !candidateAllowed(outputId, candidate)) continue;
            int score = preferenceScore(outputId, candidate);
            if (selected < 0
                || score > selectedScore
                || score == selectedScore && candidate.recipeId().compareTo(selectedRecipeId) < 0) {
                selected = index;
                selectedScore = score;
                selectedRecipeId = candidate.recipeId();
            }
        }
        return selected;
    }

    static int preferenceScore(String outputItemId, Candidate candidate) {
        if (candidate == null) return Integer.MIN_VALUE;
        String outputId = normalize(outputItemId);
        int score = 0;
        if (isCanonicalVanillaRecipe(outputId, candidate)) score += 1_000_000;
        if (candidate.recipeId().equals(outputId)) score += 100_000;
        if (namespace(candidate.recipeId()).equals(namespace(outputId))) score += 10_000;
        if (candidate.inventoryReady()) score += 1_000;
        if (candidate.inventoryCraftable()) score += 100;
        score -= nonEmptySlots(candidate.ingredientOptions());
        return score;
    }

    static boolean candidateAllowed(String outputItemId, Candidate candidate) {
        String outputId = normalize(outputItemId);
        if (candidate == null) return false;
        if (!isStrictVanillaTarget(outputId)) return true;
        return isCanonicalVanillaRecipe(outputId, candidate);
    }

    /** Returns true for vanilla outputs whose normal recipe must never be replaced by a mod recipe. */
    static boolean isStrictVanillaTarget(String outputItemId) {
        String itemId = normalize(outputItemId);
        return FIXED_STRICT_TARGETS.contains(itemId)
            || vanillaToolParts(itemId) != null
            || vanillaArmorParts(itemId) != null
            || bedColor(itemId) != null;
    }

    /**
     * Verifies the official vanilla recipe id and its material counts. Shape
     * is intentionally left to Minecraft's own recipe implementation; the
     * exact official id prevents a shapeless/modded look-alike from passing.
     */
    static boolean isCanonicalVanillaRecipe(String outputItemId, Candidate candidate) {
        if (candidate == null) return false;
        String itemId = normalize(outputItemId);
        if (!candidate.recipeId().equals(itemId)) return false;
        List<List<String>> slots = nonEmptySlotsList(candidate.ingredientOptions());

        String[] tool = vanillaToolParts(itemId);
        if (tool != null) return canonicalTool(slots, tool[0], tool[1]);

        String[] armor = vanillaArmorParts(itemId);
        if (armor != null) return canonicalArmor(slots, armor[0], armor[1]);

        String color = bedColor(itemId);
        if (color != null) {
            return matches(slots,
                rule(3, id -> id.equals("minecraft:" + color + "_wool")),
                rule(3, CraftRecipeSelectionPolicy::isPlank));
        }

        return switch (itemId) {
            case "minecraft:fishing_rod" -> matches(slots,
                exact(3, "minecraft:stick"), exact(2, "minecraft:string"));
            case "minecraft:bow" -> matches(slots,
                exact(3, "minecraft:stick"), exact(3, "minecraft:string"));
            case "minecraft:crossbow" -> matches(slots,
                exact(3, "minecraft:stick"), exact(2, "minecraft:string"),
                exact(1, "minecraft:iron_ingot"), exact(1, "minecraft:tripwire_hook"));
            case "minecraft:arrow" -> matches(slots,
                exact(1, "minecraft:flint"), exact(1, "minecraft:stick"),
                exact(1, "minecraft:feather"));
            case "minecraft:shield" -> matches(slots,
                rule(6, CraftRecipeSelectionPolicy::isPlank), exact(1, "minecraft:iron_ingot"));
            case "minecraft:torch" -> matches(slots,
                rule(1, CraftRecipeSelectionPolicy::isCoal), exact(1, "minecraft:stick"));
            case "minecraft:soul_torch" -> matches(slots,
                rule(1, CraftRecipeSelectionPolicy::isCoal), exact(1, "minecraft:stick"),
                rule(1, id -> id.equals("minecraft:soul_sand") || id.equals("minecraft:soul_soil")));
            case "minecraft:crafting_table" -> matches(slots,
                rule(4, CraftRecipeSelectionPolicy::isPlank));
            case "minecraft:chest" -> matches(slots,
                rule(8, CraftRecipeSelectionPolicy::isPlank));
            case "minecraft:barrel" -> matches(slots,
                rule(6, CraftRecipeSelectionPolicy::isPlank),
                rule(2, CraftRecipeSelectionPolicy::isWoodenSlab));
            case "minecraft:furnace" -> matches(slots,
                rule(8, STONE_CRAFTING_MATERIALS::contains));
            case "minecraft:hopper" -> matches(slots,
                exact(5, "minecraft:iron_ingot"), exact(1, "minecraft:chest"));
            case "minecraft:dispenser" -> matches(slots,
                exact(7, "minecraft:cobblestone"), exact(1, "minecraft:bow"),
                exact(1, "minecraft:redstone"));
            case "minecraft:dropper" -> matches(slots,
                exact(7, "minecraft:cobblestone"), exact(1, "minecraft:redstone"));
            case "minecraft:shears" -> matches(slots, exact(2, "minecraft:iron_ingot"));
            case "minecraft:bucket" -> matches(slots, exact(3, "minecraft:iron_ingot"));
            case "minecraft:flint_and_steel" -> matches(slots,
                exact(1, "minecraft:iron_ingot"), exact(1, "minecraft:flint"));
            case "minecraft:compass" -> matches(slots,
                exact(4, "minecraft:iron_ingot"), exact(1, "minecraft:redstone"));
            case "minecraft:clock" -> matches(slots,
                exact(4, "minecraft:gold_ingot"), exact(1, "minecraft:redstone"));
            case "minecraft:spyglass" -> matches(slots,
                exact(2, "minecraft:copper_ingot"), exact(1, "minecraft:amethyst_shard"));
            case "minecraft:brush" -> matches(slots,
                exact(1, "minecraft:feather"), exact(1, "minecraft:copper_ingot"),
                exact(1, "minecraft:stick"));
            case "minecraft:lead" -> matches(slots,
                exact(4, "minecraft:string"), exact(1, "minecraft:slime_ball"));
            case "minecraft:item_frame" -> matches(slots,
                exact(8, "minecraft:stick"), exact(1, "minecraft:leather"));
            case "minecraft:painting" -> matches(slots,
                exact(8, "minecraft:stick"), rule(1, CraftRecipeSelectionPolicy::isWool));
            case "minecraft:ladder" -> matches(slots, exact(7, "minecraft:stick"));
            case "minecraft:tripwire_hook" -> matches(slots,
                exact(1, "minecraft:iron_ingot"), exact(1, "minecraft:stick"),
                rule(1, CraftRecipeSelectionPolicy::isPlank));
            case "minecraft:campfire" -> matches(slots,
                exact(3, "minecraft:stick"), rule(1, CraftRecipeSelectionPolicy::isCoal),
                rule(3, CraftRecipeSelectionPolicy::isLogOrStem));
            case "minecraft:turtle_helmet" -> matches(slots, exact(5, "minecraft:scute"));
            // Chainmail/netherite equipment and tridents have no crafting-table
            // recipe in vanilla 1.20.1. Their strict target intentionally fails.
            default -> false;
        };
    }

    /**
     * Rejects prerequisite steps that consume the final target, consume their
     * own output, or exchange two resource forms that the same parent recipe
     * simultaneously requires. The latter is the iron-ingot/iron-nugget loop
     * observed in a modded diamond-pickaxe recipe.
     */
    static boolean unsafePrerequisite(
        String finalTargetItemId,
        List<List<String>> parentIngredientOptions,
        String candidateOutputItemId,
        List<List<String>> candidateIngredientOptions
    ) {
        String targetId = normalize(finalTargetItemId);
        String outputId = normalize(candidateOutputItemId);
        Set<String> parentIds = flatten(parentIngredientOptions);
        Set<String> inputIds = flatten(candidateIngredientOptions);
        if (outputId.isBlank()) return true;
        if (inputIds.contains(outputId)) return true;
        if (!targetId.isBlank() && !targetId.equals(outputId) && inputIds.contains(targetId)) return true;

        ResourceForm outputForm = REVERSIBLE_FORMS.get(outputId);
        if (outputForm == null) return false;
        for (String inputId : inputIds) {
            ResourceForm inputForm = REVERSIBLE_FORMS.get(inputId);
            if (inputForm == null
                || !inputForm.family().equals(outputForm.family())
                || inputForm.rank() == outputForm.rank()) continue;
            if (parentIds.contains(outputId) && parentIds.contains(inputId)) return true;
        }
        return false;
    }

    /**
     * Called only when the conversion recipe is not currently craftable. A
     * missing reversible input must be gathered/smelted/stored, never produced
     * by recursively selecting the inverse packing recipe. Direct conversions
     * such as one existing iron ingot into nine lantern nuggets remain valid.
     */
    static boolean unsafeRecursivePrerequisite(
        String candidateOutputItemId,
        List<List<String>> candidateIngredientOptions
    ) {
        String outputId = normalize(candidateOutputItemId);
        ResourceForm outputForm = REVERSIBLE_FORMS.get(outputId);
        if (outputForm == null) return false;
        Set<String> inputs = flatten(candidateIngredientOptions);
        if (inputs.isEmpty()) return false;
        boolean sawDifferentRank = false;
        for (String inputId : inputs) {
            ResourceForm inputForm = REVERSIBLE_FORMS.get(inputId);
            if (inputForm == null || !inputForm.family().equals(outputForm.family())) return false;
            if (inputForm.rank() != outputForm.rank()) sawDifferentRank = true;
        }
        return sawDifferentRank;
    }

    private static boolean canonicalTool(List<List<String>> slots, String tier, String kind) {
        // Netherite equipment is upgraded at a smithing table, not crafted.
        Predicate<String> headMaterial = headMaterial(tier);
        if (headMaterial == null) return false;
        int headCount = switch (kind) {
            case "sword", "hoe" -> 2;
            case "shovel" -> 1;
            case "pickaxe", "axe" -> 3;
            default -> 0;
        };
        int stickCount = switch (kind) {
            case "sword", "shovel" -> 1;
            case "pickaxe", "axe", "hoe" -> 2;
            default -> 0;
        };
        return headCount > 0 && stickCount > 0
            && matches(slots, rule(headCount, headMaterial), exact(stickCount, "minecraft:stick"));
    }

    private static boolean canonicalArmor(List<List<String>> slots, String tier, String kind) {
        Predicate<String> material = armorMaterial(tier);
        if (material == null) return false;
        int count = switch (kind) {
            case "helmet" -> 5;
            case "chestplate" -> 8;
            case "leggings" -> 7;
            case "boots" -> 4;
            default -> 0;
        };
        return count > 0 && matches(slots, rule(count, material));
    }

    private static Predicate<String> headMaterial(String tier) {
        return switch (tier) {
            case "wooden" -> CraftRecipeSelectionPolicy::isPlank;
            case "stone" -> STONE_CRAFTING_MATERIALS::contains;
            case "golden" -> id -> id.equals("minecraft:gold_ingot");
            case "iron" -> id -> id.equals("minecraft:iron_ingot");
            case "diamond" -> id -> id.equals("minecraft:diamond");
            default -> null;
        };
    }

    private static Predicate<String> armorMaterial(String tier) {
        return switch (tier) {
            case "leather" -> id -> id.equals("minecraft:leather");
            case "golden" -> id -> id.equals("minecraft:gold_ingot");
            case "iron" -> id -> id.equals("minecraft:iron_ingot");
            case "diamond" -> id -> id.equals("minecraft:diamond");
            default -> null;
        };
    }

    private static boolean matches(List<List<String>> slots, IngredientRule... rules) {
        int requiredSlots = 0;
        for (IngredientRule rule : rules) requiredSlots += rule.count();
        if (slots.size() != requiredSlots) return false;
        for (IngredientRule rule : rules) {
            long actual = slots.stream().filter(rule::acceptsEvery).count();
            if (actual != rule.count()) return false;
        }
        return true;
    }

    private static IngredientRule exact(int count, String itemId) {
        String normalized = normalize(itemId);
        return rule(count, id -> id.equals(normalized));
    }

    private static IngredientRule rule(int count, Predicate<String> acceptedItem) {
        return new IngredientRule(count, acceptedItem);
    }

    private static boolean isPlank(String itemId) {
        return itemId.startsWith("minecraft:") && itemId.endsWith("_planks");
    }

    private static boolean isWoodenSlab(String itemId) {
        if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_slab")) return false;
        String path = itemId.substring("minecraft:".length(), itemId.length() - "_slab".length());
        return Set.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "bamboo", "crimson", "warped"
        ).contains(path);
    }

    private static boolean isCoal(String itemId) {
        return itemId.equals("minecraft:coal") || itemId.equals("minecraft:charcoal");
    }

    private static boolean isWool(String itemId) {
        if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_wool")) return false;
        String color = itemId.substring("minecraft:".length(), itemId.length() - "_wool".length());
        return WOOL_COLORS.contains(color);
    }

    private static boolean isLogOrStem(String itemId) {
        if (!itemId.startsWith("minecraft:")) return false;
        String path = itemId.substring("minecraft:".length());
        return path.endsWith("_log") || path.endsWith("_wood")
            || path.endsWith("_stem") || path.endsWith("_hyphae");
    }

    private static String[] vanillaToolParts(String itemId) {
        if (!itemId.startsWith("minecraft:")) return null;
        String path = itemId.substring("minecraft:".length());
        int split = path.indexOf('_');
        if (split <= 0 || split >= path.length() - 1) return null;
        String tier = path.substring(0, split);
        String kind = path.substring(split + 1);
        if (!Set.of("wooden", "stone", "golden", "iron", "diamond", "netherite").contains(tier)) return null;
        if (!Set.of("sword", "pickaxe", "axe", "shovel", "hoe").contains(kind)) return null;
        return new String[] { tier, kind };
    }

    private static String[] vanillaArmorParts(String itemId) {
        if (!itemId.startsWith("minecraft:")) return null;
        String path = itemId.substring("minecraft:".length());
        for (String kind : List.of("chestplate", "leggings", "helmet", "boots")) {
            String suffix = "_" + kind;
            if (!path.endsWith(suffix)) continue;
            String tier = path.substring(0, path.length() - suffix.length());
            if (Set.of("leather", "chainmail", "golden", "iron", "diamond", "netherite").contains(tier)) {
                return new String[] { tier, kind };
            }
        }
        return null;
    }

    private static String bedColor(String itemId) {
        if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_bed")) return null;
        String color = itemId.substring("minecraft:".length(), itemId.length() - "_bed".length());
        return WOOL_COLORS.contains(color) ? color : null;
    }

    private static List<List<String>> nonEmptySlotsList(List<List<String>> options) {
        return options == null ? List.of() : options.stream().filter(slot -> !slot.isEmpty()).toList();
    }

    private static int nonEmptySlots(List<List<String>> options) {
        return nonEmptySlotsList(options).size();
    }

    private static Set<String> flatten(List<List<String>> options) {
        Set<String> result = new HashSet<>();
        if (options == null) return result;
        for (List<String> slot : options) {
            if (slot == null) continue;
            for (String value : slot) {
                String normalized = normalize(value);
                if (!normalized.isBlank() && !normalized.equals("minecraft:air")) result.add(normalized);
            }
        }
        return result;
    }

    private static String namespace(String id) {
        int separator = id.indexOf(':');
        return separator <= 0 ? "" : id.substring(0, separator);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, ResourceForm> reversibleForms() {
        Map<String, ResourceForm> forms = new HashMap<>();
        addForms(forms, "iron", "minecraft:iron_nugget", "minecraft:iron_ingot", "minecraft:iron_block");
        addForms(forms, "gold", "minecraft:gold_nugget", "minecraft:gold_ingot", "minecraft:gold_block");
        addForms(forms, "copper", "minecraft:copper_ingot", "minecraft:copper_block");
        addForms(forms, "diamond", "minecraft:diamond", "minecraft:diamond_block");
        addForms(forms, "emerald", "minecraft:emerald", "minecraft:emerald_block");
        addForms(forms, "coal", "minecraft:coal", "minecraft:coal_block");
        addForms(forms, "lapis", "minecraft:lapis_lazuli", "minecraft:lapis_block");
        addForms(forms, "redstone", "minecraft:redstone", "minecraft:redstone_block");
        addForms(forms, "raw_iron", "minecraft:raw_iron", "minecraft:raw_iron_block");
        addForms(forms, "raw_gold", "minecraft:raw_gold", "minecraft:raw_gold_block");
        addForms(forms, "raw_copper", "minecraft:raw_copper", "minecraft:raw_copper_block");
        return Map.copyOf(forms);
    }

    private static void addForms(Map<String, ResourceForm> forms, String family, String... itemIds) {
        for (int rank = 0; rank < itemIds.length; rank++) {
            forms.put(itemIds[rank], new ResourceForm(family, rank));
        }
    }
}
