package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CraftRecipeSelectionPolicyTest {
    private static final List<String> ALL_PLANKS = List.of(
        "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
        "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:bamboo_planks",
        "minecraft:crimson_planks", "minecraft:warped_planks"
    );

    private static final List<String> STONE_MATERIALS = List.of(
        "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:blackstone"
    );

    @Test
    void selectsCanonicalDiamondPickaxeInsteadOfModdedNuggetRecipe() {
        List<CraftRecipeSelectionPolicy.Candidate> candidates = List.of(
            candidate(
                "dragonmod:cheap_diamond_pickaxe",
                true,
                false,
                slots("minecraft:iron_ingot", "minecraft:iron_nugget", "minecraft:stick")
            ),
            candidate(
                "minecraft:diamond_pickaxe",
                false,
                false,
                slots(
                    "minecraft:diamond", "minecraft:diamond", "minecraft:diamond",
                    "minecraft:stick", "minecraft:stick"
                )
            )
        );

        assertEquals(1, CraftRecipeSelectionPolicy.choose("minecraft:diamond_pickaxe", candidates));
    }

    @Test
    void rejectsModRecipeEvenWhenItCopiesTheCanonicalIngredientCounts() {
        var copied = candidate(
            "example:diamond_pickaxe_from_vanilla_counts",
            true,
            true,
            slots(
                "minecraft:diamond", "minecraft:diamond", "minecraft:diamond",
                "minecraft:stick", "minecraft:stick"
            )
        );
        assertFalse(CraftRecipeSelectionPolicy.candidateAllowed("minecraft:diamond_pickaxe", copied));
        assertEquals(-1, CraftRecipeSelectionPolicy.choose("minecraft:diamond_pickaxe", List.of(copied)));
    }

    @Test
    void validatesEveryCraftingTableToolAndSwordByCanonicalCounts() {
        for (String tier : List.of("wooden", "stone", "golden", "iron", "diamond")) {
            for (String kind : List.of("sword", "pickaxe", "axe", "shovel", "hoe")) {
                int heads = switch (kind) {
                    case "sword", "hoe" -> 2;
                    case "shovel" -> 1;
                    default -> 3;
                };
                int sticks = switch (kind) {
                    case "sword", "shovel" -> 1;
                    default -> 2;
                };
                List<List<String>> ingredients = new ArrayList<>();
                List<String> materialOptions = switch (tier) {
                    case "wooden" -> ALL_PLANKS;
                    case "stone" -> STONE_MATERIALS;
                    case "golden" -> List.of("minecraft:gold_ingot");
                    case "iron" -> List.of("minecraft:iron_ingot");
                    default -> List.of("minecraft:diamond");
                };
                for (int index = 0; index < heads; index++) ingredients.add(materialOptions);
                for (int index = 0; index < sticks; index++) ingredients.add(List.of("minecraft:stick"));
                String itemId = "minecraft:" + tier + "_" + kind;
                assertCanonical(itemId, ingredients);
            }
        }
    }

    @Test
    void validatesAllCraftableVanillaArmorFamiliesAndRejectsSmithingOrLootArmor() {
        Map<String, Integer> armorCounts = Map.of(
            "helmet", 5,
            "chestplate", 8,
            "leggings", 7,
            "boots", 4
        );
        for (String tier : List.of("leather", "golden", "iron", "diamond")) {
            String material = switch (tier) {
                case "leather" -> "minecraft:leather";
                case "golden" -> "minecraft:gold_ingot";
                case "iron" -> "minecraft:iron_ingot";
                default -> "minecraft:diamond";
            };
            for (Map.Entry<String, Integer> entry : armorCounts.entrySet()) {
                String itemId = "minecraft:" + tier + "_" + entry.getKey();
                assertCanonical(itemId, repeated(entry.getValue(), List.of(material)));
            }
        }

        assertCanonical("minecraft:turtle_helmet", slots(
            "minecraft:scute", "minecraft:scute", "minecraft:scute",
            "minecraft:scute", "minecraft:scute"
        ));
        for (String itemId : List.of(
            "minecraft:chainmail_chestplate",
            "minecraft:netherite_helmet",
            "minecraft:netherite_pickaxe",
            "minecraft:trident"
        )) {
            assertTrue(CraftRecipeSelectionPolicy.isStrictVanillaTarget(itemId), itemId);
            assertFalse(CraftRecipeSelectionPolicy.isCanonicalVanillaRecipe(
                itemId,
                candidate(itemId, true, true, slots("minecraft:iron_ingot"))
            ), itemId);
        }
    }

    @Test
    void validatesWeaponsAmmunitionAndShield() {
        assertCanonical("minecraft:fishing_rod", slots(
            "minecraft:stick", "minecraft:stick", "minecraft:stick",
            "minecraft:string", "minecraft:string"
        ));
        assertCanonical("minecraft:bow", slots(
            "minecraft:stick", "minecraft:stick", "minecraft:stick",
            "minecraft:string", "minecraft:string", "minecraft:string"
        ));
        assertCanonical("minecraft:crossbow", slots(
            "minecraft:stick", "minecraft:stick", "minecraft:stick",
            "minecraft:string", "minecraft:string", "minecraft:iron_ingot",
            "minecraft:tripwire_hook"
        ));
        assertCanonical("minecraft:arrow", slots(
            "minecraft:flint", "minecraft:stick", "minecraft:feather"
        ));
        assertCanonical("minecraft:shield", concat(
            repeated(6, ALL_PLANKS),
            slots("minecraft:iron_ingot")
        ));
    }

    @Test
    void validatesCoreWorkstationsStorageAndRedstoneContainers() {
        assertCanonical("minecraft:crafting_table", repeated(4, ALL_PLANKS));
        assertCanonical("minecraft:chest", repeated(8, ALL_PLANKS));
        assertCanonical("minecraft:barrel", concat(
            repeated(6, ALL_PLANKS),
            repeated(2, List.of("minecraft:oak_slab", "minecraft:spruce_slab"))
        ));
        assertCanonical("minecraft:furnace", repeated(8, STONE_MATERIALS));
        assertCanonical("minecraft:hopper", concat(
            repeated(5, List.of("minecraft:iron_ingot")), slots("minecraft:chest")
        ));
        assertCanonical("minecraft:dispenser", concat(
            repeated(7, List.of("minecraft:cobblestone")), slots("minecraft:bow", "minecraft:redstone")
        ));
        assertCanonical("minecraft:dropper", concat(
            repeated(7, List.of("minecraft:cobblestone")), slots("minecraft:redstone")
        ));
    }

    @Test
    void validatesTorchesAndEveryRequestedCoreUtility() {
        Map<String, List<List<String>>> signatures = new LinkedHashMap<>();
        signatures.put("minecraft:torch", List.of(
            List.of("minecraft:coal", "minecraft:charcoal"),
            List.of("minecraft:stick")
        ));
        signatures.put("minecraft:soul_torch", List.of(
            List.of("minecraft:coal", "minecraft:charcoal"),
            List.of("minecraft:stick"),
            List.of("minecraft:soul_sand", "minecraft:soul_soil")
        ));
        signatures.put("minecraft:shears", slots("minecraft:iron_ingot", "minecraft:iron_ingot"));
        signatures.put("minecraft:bucket", slots(
            "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot"
        ));
        signatures.put("minecraft:flint_and_steel", slots("minecraft:iron_ingot", "minecraft:flint"));
        signatures.put("minecraft:compass", slots(
            "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot",
            "minecraft:iron_ingot", "minecraft:redstone"
        ));
        signatures.put("minecraft:clock", slots(
            "minecraft:gold_ingot", "minecraft:gold_ingot", "minecraft:gold_ingot",
            "minecraft:gold_ingot", "minecraft:redstone"
        ));
        signatures.put("minecraft:spyglass", slots(
            "minecraft:copper_ingot", "minecraft:copper_ingot", "minecraft:amethyst_shard"
        ));
        signatures.put("minecraft:brush", slots(
            "minecraft:feather", "minecraft:copper_ingot", "minecraft:stick"
        ));
        signatures.put("minecraft:lead", slots(
            "minecraft:string", "minecraft:string", "minecraft:string", "minecraft:string",
            "minecraft:slime_ball"
        ));
        signatures.put("minecraft:item_frame", concat(
            repeated(8, List.of("minecraft:stick")), slots("minecraft:leather")
        ));
        signatures.put("minecraft:painting", concat(
            repeated(8, List.of("minecraft:stick")), List.of(List.of("minecraft:white_wool", "minecraft:red_wool"))
        ));
        signatures.put("minecraft:ladder", repeated(7, List.of("minecraft:stick")));
        signatures.put("minecraft:tripwire_hook", concat(
            slots("minecraft:iron_ingot", "minecraft:stick"), List.of(ALL_PLANKS)
        ));
        signatures.put("minecraft:campfire", concat(
            repeated(3, List.of("minecraft:stick")),
            List.of(List.of("minecraft:coal", "minecraft:charcoal")),
            repeated(3, List.of("minecraft:oak_log", "minecraft:spruce_log"))
        ));

        signatures.forEach(this::assertCanonical);
    }

    @Test
    void validatesAllSixteenBedColorsWithoutMixingWool() {
        for (String color : List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
        )) {
            String itemId = "minecraft:" + color + "_bed";
            assertCanonical(itemId, concat(
                repeated(3, List.of("minecraft:" + color + "_wool")),
                repeated(3, ALL_PLANKS)
            ));
            assertFalse(CraftRecipeSelectionPolicy.isCanonicalVanillaRecipe(
                itemId,
                candidate(itemId, true, true, concat(
                    slots("minecraft:white_wool", "minecraft:red_wool", "minecraft:" + color + "_wool"),
                    repeated(3, ALL_PLANKS)
                ))
            ), color);
        }
    }

    @Test
    void rejectsIronIngotNuggetOscillationButAllowsReadyLanternConversion() {
        List<List<String>> brokenParent = slots(
            "minecraft:iron_ingot", "minecraft:iron_nugget", "minecraft:stick"
        );
        assertTrue(CraftRecipeSelectionPolicy.unsafePrerequisite(
            "minecraft:diamond_pickaxe",
            brokenParent,
            "minecraft:iron_nugget",
            slots("minecraft:iron_ingot")
        ));
        assertTrue(CraftRecipeSelectionPolicy.unsafePrerequisite(
            "minecraft:diamond_pickaxe",
            brokenParent,
            "minecraft:iron_ingot",
            repeated(9, List.of("minecraft:iron_nugget"))
        ));

        assertFalse(CraftRecipeSelectionPolicy.unsafePrerequisite(
            "minecraft:lantern",
            repeated(8, List.of("minecraft:iron_nugget")),
            "minecraft:iron_nugget",
            slots("minecraft:iron_ingot")
        ));
        // Once that ingot is absent, the dependency walker must not recurse
        // into the inverse nugget -> ingot packing recipe.
        assertTrue(CraftRecipeSelectionPolicy.unsafeRecursivePrerequisite(
            "minecraft:iron_nugget", slots("minecraft:iron_ingot")
        ));
        assertTrue(CraftRecipeSelectionPolicy.unsafeRecursivePrerequisite(
            "minecraft:iron_ingot", repeated(9, List.of("minecraft:iron_nugget"))
        ));
        assertFalse(CraftRecipeSelectionPolicy.unsafeRecursivePrerequisite(
            "minecraft:stick", repeated(2, ALL_PLANKS)
        ));
    }

    @Test
    void rejectsPrerequisiteThatConsumesFinalTargetOrItsOwnOutput() {
        assertTrue(CraftRecipeSelectionPolicy.unsafePrerequisite(
            "minecraft:diamond_pickaxe",
            slots("mod:component"),
            "mod:component",
            slots("minecraft:diamond_pickaxe")
        ));
        assertTrue(CraftRecipeSelectionPolicy.unsafePrerequisite(
            "minecraft:chest",
            slots("minecraft:oak_planks"),
            "minecraft:oak_planks",
            slots("minecraft:oak_planks")
        ));
        assertTrue(CraftRecipeSelectionPolicy.unsafePrerequisite(
            "minecraft:chest", slots("minecraft:oak_planks"), "", List.of()
        ));
    }

    @Test
    void unknownModOutputsRetainStableInventoryAwareFallback() {
        List<CraftRecipeSelectionPolicy.Candidate> candidates = List.of(
            candidate("zmod:widget", false, false, slots("minecraft:iron_ingot")),
            candidate("amod:widget", true, true, slots("minecraft:copper_ingot", "minecraft:stick"))
        );
        assertFalse(CraftRecipeSelectionPolicy.isStrictVanillaTarget("example:widget"));
        assertEquals(1, CraftRecipeSelectionPolicy.choose("example:widget", candidates));
        assertEquals(-1, CraftRecipeSelectionPolicy.choose("example:widget", List.of()));
    }

    private void assertCanonical(String itemId, List<List<String>> ingredients) {
        CraftRecipeSelectionPolicy.Candidate official = candidate(itemId, false, false, ingredients);
        assertTrue(CraftRecipeSelectionPolicy.isStrictVanillaTarget(itemId), itemId);
        assertTrue(CraftRecipeSelectionPolicy.isCanonicalVanillaRecipe(itemId, official), itemId);
        assertTrue(CraftRecipeSelectionPolicy.candidateAllowed(itemId, official), itemId);
        assertEquals(0, CraftRecipeSelectionPolicy.choose(itemId, List.of(official)), itemId);

        CraftRecipeSelectionPolicy.Candidate modCopy = candidate(
            "example:" + itemId.substring(itemId.indexOf(':') + 1),
            true,
            true,
            ingredients
        );
        assertFalse(CraftRecipeSelectionPolicy.candidateAllowed(itemId, modCopy), itemId);
    }

    private static CraftRecipeSelectionPolicy.Candidate candidate(
        String recipeId,
        boolean inventoryReady,
        boolean inventoryCraftable,
        List<List<String>> ingredients
    ) {
        return new CraftRecipeSelectionPolicy.Candidate(
            recipeId,
            inventoryReady,
            inventoryCraftable,
            ingredients
        );
    }

    private static List<List<String>> slots(String... itemIds) {
        List<List<String>> result = new ArrayList<>();
        for (String itemId : itemIds) result.add(List.of(itemId));
        return result;
    }

    private static List<List<String>> repeated(int count, List<String> options) {
        return new ArrayList<>(Collections.nCopies(count, options));
    }

    @SafeVarargs
    private static List<List<String>> concat(List<List<String>>... groups) {
        List<List<String>> result = new ArrayList<>();
        for (List<List<String>> group : groups) result.addAll(group);
        return result;
    }
}
