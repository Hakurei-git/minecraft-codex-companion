package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildMaterialPrerequisitePolicyTest {
    @Test
    void exposesStableRecipeIndependentPlansForTheTaskStack() {
        var logs = BuildMaterialPrerequisitePolicy.plan("minecraft:oak_log");
        assertEquals(BuildMaterialPrerequisitePolicy.Action.GATHER, logs.action());
        assertEquals("minecraft:oak_log", logs.gatherSelector());
        assertTrue(BuildMaterialPrerequisitePolicy.canGatherDirectly("minecraft:oak_log"));

        var planks = BuildMaterialPrerequisitePolicy.plan("minecraft:oak_planks");
        assertEquals(BuildMaterialPrerequisitePolicy.Action.CRAFT, planks.action());
        assertEquals(4, planks.outputPerBatch());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:oak_logs", 1),
            planks.upstreamRequirements().get(0)
        );

        var glass = BuildMaterialPrerequisitePolicy.plan("minecraft:glass");
        assertEquals(BuildMaterialPrerequisitePolicy.Action.SMELT, glass.action());
        assertEquals("minecraft:sand", glass.upstreamRequirements().get(0).selector());

        var farmland = BuildMaterialPrerequisitePolicy.plan("minecraft:farmland");
        assertEquals(BuildMaterialPrerequisitePolicy.Action.TILL, farmland.action());

        var lava = BuildMaterialPrerequisitePolicy.plan("minecraft:lava_bucket");
        assertEquals(BuildMaterialPrerequisitePolicy.Action.REJECT, lava.action());
        assertTrue(!lava.refusalReason().isBlank());
    }

    @Test
    void existingInventoryAlwaysWinsWithoutAcquisition() {
        var result = decide("minecraft:water_bucket", 1, "minecraft:water_bucket", 1);

        assertEquals(BuildMaterialPrerequisitePolicy.Decision.AVAILABLE, result.decision());
        assertEquals(0, result.missingCount());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.NONE, result.process());
    }

    @Test
    void directlyGathersOnlyAllowlistedNaturalMaterialsAndSeeds() {
        for (String itemId : new String[] {
            "minecraft:oak_log",
            "minecraft:crimson_stem",
            "minecraft:cobblestone",
            "minecraft:dirt",
            "minecraft:sand",
            "minecraft:raw_iron",
            "minecraft:coal",
            "minecraft:clay_ball",
            "minecraft:wheat_seeds",
            "minecraft:beetroot_seeds",
            "minecraft:carrot",
            "minecraft:potato"
        }) {
            var result = BuildMaterialPrerequisitePolicy.decide(itemId, 4, Map.of());
            assertEquals(BuildMaterialPrerequisitePolicy.Decision.GATHER_DIRECTLY, result.decision(), itemId);
            assertEquals(4, result.missingCount(), itemId);
            assertTrue(result.upstreamRequirements().isEmpty(), itemId);
        }
    }

    @Test
    void craftsPlanksWhenFamilyLogsArePresentAndRequestsOnlyTheDeficit() {
        var ready = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:oak_planks",
            8,
            Map.of("minecraft:oak_log", 2)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, ready.decision());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:oak_logs", 2),
            ready.upstreamRequirements().get(0)
        );

        var missingOneLog = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:oak_planks",
            8,
            Map.of("minecraft:oak_log", 1)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, missingOneLog.decision());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:oak_logs", 1),
            missingOneLog.missingUpstream().get(0)
        );

        var partialOutput = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:oak_planks",
            8,
            Map.of("minecraft:oak_planks", 5, "minecraft:oak_log", 1)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, partialOutput.decision());
        assertEquals(3, partialOutput.missingCount());
        assertEquals(1, partialOutput.upstreamRequirements().get(0).count());
    }

    @Test
    void reusesFullBarkStrippedAndNetherWoodVariantsThroughFamilyTags() {
        for (String variant : new String[] {
            "minecraft:oak_wood",
            "minecraft:stripped_oak_log",
            "minecraft:stripped_oak_wood"
        }) {
            var result = BuildMaterialPrerequisitePolicy.decide(
                "minecraft:oak_planks", 4, Map.of(variant, 1)
            );
            assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, result.decision(), variant);
            assertTrue(result.missingUpstream().isEmpty(), variant);
        }

        for (String variant : new String[] {
            "minecraft:crimson_hyphae",
            "minecraft:stripped_crimson_stem",
            "minecraft:stripped_crimson_hyphae"
        }) {
            var result = BuildMaterialPrerequisitePolicy.decide(
                "minecraft:crimson_planks", 4, Map.of(variant, 1)
            );
            assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, result.decision(), variant);
            assertTrue(result.missingUpstream().isEmpty(), variant);
        }
    }

    @Test
    void modelsWoodSlabStairFenceGateAndDoorBatchRecipes() {
        assertCrafts(
            "minecraft:oak_slab", 12,
            Map.of("minecraft:oak_planks", 6),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:oak_planks", 6)
        );
        var stairs = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:oak_stairs", 4, Map.of("minecraft:oak_planks", 5)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, stairs.decision());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:oak_planks", 1),
            stairs.missingUpstream().get(0)
        );
        var fence = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:oak_fence", 3,
            Map.of("minecraft:oak_planks", 4, "minecraft:stick", 2)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, fence.decision());
        assertEquals(2, fence.upstreamRequirements().size());
        assertCrafts(
            "minecraft:oak_fence_gate", 1,
            Map.of("minecraft:oak_planks", 2, "minecraft:stick", 4),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:oak_planks", 2)
        );
        assertCrafts(
            "minecraft:oak_door", 3,
            Map.of("minecraft:oak_planks", 6),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:oak_planks", 6)
        );
    }

    @Test
    void keepsEveryPaletteWoodButtonAndPressurePlateInItsOwnFamily() {
        for (String family : new String[] {
            "dark_oak", "mangrove", "crimson", "warped", "cherry",
            "acacia", "jungle", "spruce", "birch", "bamboo", "oak"
        }) {
            String planks = "minecraft:" + family + "_planks";
            var button = BuildMaterialPrerequisitePolicy.plan("minecraft:" + family + "_button");
            assertEquals(BuildMaterialPrerequisitePolicy.Action.CRAFT, button.action(), family);
            assertEquals(1, button.outputPerBatch(), family);
            assertEquals(
                new BuildMaterialPrerequisitePolicy.Requirement(planks, 1),
                button.upstreamRequirements().get(0),
                family
            );

            var pressurePlate = BuildMaterialPrerequisitePolicy.plan(
                "minecraft:" + family + "_pressure_plate"
            );
            assertEquals(BuildMaterialPrerequisitePolicy.Action.CRAFT, pressurePlate.action(), family);
            assertEquals(1, pressurePlate.outputPerBatch(), family);
            assertEquals(
                new BuildMaterialPrerequisitePolicy.Requirement(planks, 2),
                pressurePlate.upstreamRequirements().get(0),
                family
            );
        }
    }

    @Test
    void rejectsCrossFamilyCandidatesFromGenericWoodIngredients() {
        var genericPlanks = java.util.List.of(
            "minecraft:bamboo_planks",
            "minecraft:dark_oak_planks",
            "minecraft:oak_planks"
        );
        assertTrue(BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
            "minecraft:dark_oak_pressure_plate",
            "minecraft:dark_oak_planks",
            genericPlanks
        ));
        assertFalse(BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
            "minecraft:dark_oak_pressure_plate",
            "minecraft:bamboo_planks",
            genericPlanks
        ));
        assertFalse(BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
            "minecraft:dark_oak_fence",
            "minecraft:bamboo",
            java.util.List.of("minecraft:bamboo")
        ));
        assertTrue(BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
            "minecraft:dark_oak_fence",
            "minecraft:stick",
            java.util.List.of("minecraft:stick")
        ));
        assertTrue(BuildMaterialPrerequisitePolicy.ingredientPreservesWoodFamily(
            "minecraft:chest",
            "minecraft:bamboo_planks",
            genericPlanks
        ));
        assertEquals(
            "minecraft:dark_oak_planks",
            BuildMaterialPrerequisitePolicy.preferredWoodFamilyCandidate(
                "minecraft:dark_oak_fence",
                genericPlanks
            )
        );
        assertEquals(
            "",
            BuildMaterialPrerequisitePolicy.preferredWoodFamilyCandidate("minecraft:stick", genericPlanks)
        );
    }

    @Test
    void acceptsOnlyRecipesThatCanPreserveTheTargetWoodFamily() {
        assertTrue(BuildMaterialPrerequisitePolicy.recipePreservesWoodFamily(
            "minecraft:dark_oak_pressure_plate",
            java.util.List.of(java.util.List.of(
                "minecraft:bamboo_planks",
                "minecraft:dark_oak_planks",
                "minecraft:oak_planks"
            ))
        ));
        assertFalse(BuildMaterialPrerequisitePolicy.recipePreservesWoodFamily(
            "minecraft:dark_oak_pressure_plate",
            java.util.List.of(java.util.List.of("minecraft:bamboo_planks", "minecraft:oak_planks"))
        ));
        assertFalse(BuildMaterialPrerequisitePolicy.recipePreservesWoodFamily(
            "minecraft:dark_oak_fence",
            java.util.List.of(java.util.List.of("minecraft:bamboo"))
        ));
        assertTrue(BuildMaterialPrerequisitePolicy.recipePreservesWoodFamily(
            "minecraft:dark_oak_fence",
            java.util.List.of(
                java.util.List.of("minecraft:dark_oak_planks"),
                java.util.List.of("minecraft:stick")
            )
        ));
    }

    @Test
    void gathersTheTargetFamilyInsteadOfGenericLogs() {
        assertEquals(
            "#minecraft:dark_oak_logs",
            BuildMaterialPrerequisitePolicy.preferredWoodGatherSelector("minecraft:dark_oak_pressure_plate")
        );
        assertEquals(
            "#minecraft:crimson_stems",
            BuildMaterialPrerequisitePolicy.preferredWoodGatherSelector("minecraft:crimson_button")
        );
        assertEquals(
            "minecraft:bamboo",
            BuildMaterialPrerequisitePolicy.preferredWoodGatherSelector("minecraft:bamboo_trapdoor")
        );
        assertEquals("", BuildMaterialPrerequisitePolicy.preferredWoodGatherSelector("minecraft:chest"));
    }

    @Test
    void modelsStoneBrickAndGlassUpstreamChains() {
        var stoneBricks = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:stone_bricks", 8, Map.of("minecraft:stone", 8)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, stoneBricks.decision());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.CRAFTING, stoneBricks.process());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stone", 8),
            stoneBricks.upstreamRequirements().get(0)
        );

        var stoneNeedsCobble = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:stone", 8, Map.of("minecraft:cobblestone", 3)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, stoneNeedsCobble.decision());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.SMELTING, stoneNeedsCobble.process());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:cobblestone", 5),
            stoneNeedsCobble.missingUpstream().get(0)
        );

        var glass = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:glass", 4, Map.of("minecraft:sand", 4)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, glass.decision());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.SMELTING, glass.process());

        var clay = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:clay", 2, Map.of("minecraft:clay_ball", 8)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, clay.decision());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.CRAFTING, clay.process());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:clay_ball", 8),
            clay.upstreamRequirements().get(0)
        );
        assertTrue(!BuildMaterialPrerequisitePolicy.canGatherDirectly("minecraft:clay"));
    }

    @Test
    void modelsMetalIngotsAsSmeltingChainsForToolsArmorAndEquipment() {
        var iron = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:iron_ingot", 3, Map.of("minecraft:raw_iron", 3)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, iron.decision());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.SMELTING, iron.process());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:raw_iron", 3),
            iron.upstreamRequirements().get(0)
        );

        var missingGold = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:gold_ingot", 8, Map.of("minecraft:raw_gold", 5)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, missingGold.decision());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:raw_gold", 3),
            missingGold.missingUpstream().get(0)
        );

        var copper = BuildMaterialPrerequisitePolicy.plan("minecraft:copper_ingot");
        assertEquals(BuildMaterialPrerequisitePolicy.Action.SMELT, copper.action());
        assertEquals("minecraft:raw_copper", copper.upstreamRequirements().get(0).selector());
    }

    @Test
    void supportsCoreWorkstationsAndCraftedStorageWithoutGatheringContainers() {
        assertCrafts(
            "minecraft:crafting_table", 1,
            Map.of("minecraft:oak_planks", 4),
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:planks", 4)
        );

        var chest = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:chest", 1, Map.of("minecraft:oak_planks", 7)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, chest.decision());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:planks", 1),
            chest.missingUpstream().get(0)
        );

        assertCrafts(
            "minecraft:furnace", 1,
            Map.of("minecraft:cobblestone", 8),
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:stone_crafting_materials", 8)
        );
        assertCrafts(
            "minecraft:barrel", 1,
            Map.of("minecraft:oak_planks", 6, "minecraft:oak_slab", 2),
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:planks", 6)
        );
    }

    @Test
    void supportsFarmlandPreparationAndFarmSeedSources() {
        var farmland = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:farmland", 9, Map.of("minecraft:dirt", 9)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, farmland.decision());
        assertEquals(BuildMaterialPrerequisitePolicy.Process.TILLING, farmland.process());

        var missingDirt = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:farmland", 9, Map.of("minecraft:dirt", 4)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, missingDirt.decision());
        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:dirt", 5),
            missingDirt.missingUpstream().get(0)
        );

        var pumpkinSeeds = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:pumpkin_seeds", 8, Map.of("minecraft:pumpkin", 2)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, pumpkinSeeds.decision());
    }

    @Test
    void modelsEveryVanillaToolWeaponAndArmorMaterialCount() {
        for (String tier : new String[] {"wooden", "stone", "golden", "iron", "diamond"}) {
            String material = switch (tier) {
                case "wooden" -> "#minecraft:planks";
                case "stone" -> "#minecraft:stone_tool_materials";
                case "golden" -> "minecraft:gold_ingot";
                case "iron" -> "minecraft:iron_ingot";
                default -> "minecraft:diamond";
            };
            for (String kind : new String[] {"sword", "pickaxe", "axe", "shovel", "hoe"}) {
                int heads = switch (kind) {
                    case "sword", "hoe" -> 2;
                    case "shovel" -> 1;
                    default -> 3;
                };
                int sticks = switch (kind) {
                    case "sword", "shovel" -> 1;
                    default -> 2;
                };
                var plan = BuildMaterialPrerequisitePolicy.plan(
                    "minecraft:" + tier + "_" + kind
                );
                assertEquals(BuildMaterialPrerequisitePolicy.Action.CRAFT, plan.action(), tier + kind);
                assertEquals(1, plan.outputPerBatch());
                assertEquals(
                    new BuildMaterialPrerequisitePolicy.Requirement(material, heads),
                    plan.upstreamRequirements().get(0)
                );
                assertEquals(
                    new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stick", sticks),
                    plan.upstreamRequirements().get(1)
                );
            }
        }

        for (String tier : new String[] {"leather", "golden", "iron", "diamond"}) {
            String material = switch (tier) {
                case "leather" -> "minecraft:leather";
                case "golden" -> "minecraft:gold_ingot";
                case "iron" -> "minecraft:iron_ingot";
                default -> "minecraft:diamond";
            };
            for (Map.Entry<String, Integer> armor : Map.of(
                "helmet", 5,
                "chestplate", 8,
                "leggings", 7,
                "boots", 4
            ).entrySet()) {
                var plan = BuildMaterialPrerequisitePolicy.plan(
                    "minecraft:" + tier + "_" + armor.getKey()
                );
                assertEquals(BuildMaterialPrerequisitePolicy.Action.CRAFT, plan.action());
                assertEquals(
                    new BuildMaterialPrerequisitePolicy.Requirement(material, armor.getValue()),
                    plan.upstreamRequirements().get(0)
                );
            }
        }

        assertEquals(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:scute", 5),
            BuildMaterialPrerequisitePolicy.plan("minecraft:turtle_helmet")
                .upstreamRequirements().get(0)
        );
        assertEquals(
            BuildMaterialPrerequisitePolicy.Action.REJECT,
            BuildMaterialPrerequisitePolicy.plan("minecraft:netherite_pickaxe").action()
        );
        assertEquals(
            BuildMaterialPrerequisitePolicy.Action.REJECT,
            BuildMaterialPrerequisitePolicy.plan("minecraft:chainmail_chestplate").action()
        );
    }

    @Test
    void modelsCoreUtilityAndCombatRecipeDependencies() {
        assertPlan(
            "minecraft:fishing_rod", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stick", 3),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:string", 2)
        );
        assertPlan(
            "minecraft:bow", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stick", 3),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:string", 3)
        );
        assertPlan(
            "minecraft:arrow", 4,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:flint", 1),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stick", 1),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:feather", 1)
        );
        assertPlan(
            "minecraft:shield", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:planks", 6),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:iron_ingot", 1)
        );
        assertPlan(
            "minecraft:torch", 4,
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:coals", 1),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stick", 1)
        );
        assertPlan(
            "minecraft:shears", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:iron_ingot", 2)
        );
        assertPlan(
            "minecraft:bucket", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:iron_ingot", 3)
        );
        assertPlan(
            "minecraft:flint_and_steel", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:iron_ingot", 1),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:flint", 1)
        );
        assertPlan(
            "minecraft:hopper", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:iron_ingot", 5),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:chest", 1)
        );
        assertPlan(
            "minecraft:dispenser", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:cobblestone", 7),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:bow", 1),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:redstone", 1)
        );
        assertPlan(
            "minecraft:dropper", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:cobblestone", 7),
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:redstone", 1)
        );
    }

    @Test
    void scalesMultiOutputTorchArrowLeadAndLadderBatchesExactly() {
        var torches = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:torch",
            64,
            Map.of("minecraft:coal", 16, "minecraft:stick", 16)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, torches.decision());
        assertTrue(torches.upstreamRequirements().contains(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:coals", 16)
        ));
        assertTrue(torches.upstreamRequirements().contains(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:stick", 16)
        ));

        var missingTorchCoal = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:torch",
            64,
            Map.of("minecraft:coal", 15, "minecraft:stick", 16)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, missingTorchCoal.decision());
        assertTrue(missingTorchCoal.missingUpstream().contains(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:coals", 1)
        ));

        var arrows = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:arrow",
            64,
            Map.of("minecraft:flint", 16, "minecraft:stick", 16, "minecraft:feather", 16)
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, arrows.decision());
        assertTrue(arrows.upstreamRequirements().contains(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:feather", 16)
        ));

        assertEquals(2, BuildMaterialPrerequisitePolicy.plan("minecraft:lead").outputPerBatch());
        assertEquals(3, BuildMaterialPrerequisitePolicy.plan("minecraft:ladder").outputPerBatch());
    }

    @Test
    void distinguishesBlockGatherInputsFromEntityLifeSkillInputs() {
        for (String itemId : new String[] {
            "minecraft:coal",
            "minecraft:diamond",
            "minecraft:flint",
            "minecraft:string",
            "minecraft:amethyst_shard"
        }) {
            assertEquals(BuildMaterialPrerequisitePolicy.Action.GATHER,
                BuildMaterialPrerequisitePolicy.plan(itemId).action(), itemId);
            assertFalse(BuildMaterialPrerequisitePolicy.requiresEntityAcquisition(itemId), itemId);
        }

        for (String itemId : new String[] {
            "minecraft:leather",
            "minecraft:feather",
            "minecraft:slime_ball",
            "minecraft:scute",
            "minecraft:white_wool",
            "minecraft:red_wool"
        }) {
            assertTrue(BuildMaterialPrerequisitePolicy.requiresEntityAcquisition(itemId), itemId);
            assertEquals(BuildMaterialPrerequisitePolicy.Action.REJECT,
                BuildMaterialPrerequisitePolicy.plan(itemId).action(), itemId);
        }

        assertPlan(
            "minecraft:red_bed", 1,
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:red_wool", 3),
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:planks", 3)
        );
    }

    @Test
    void saturatesHugeBatchRequirementsInsteadOfOverflowingOrTurningNegative() {
        var shields = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:shield",
            Integer.MAX_VALUE,
            Map.of()
        );
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.NEEDS_UPSTREAM, shields.decision());
        assertTrue(shields.upstreamRequirements().contains(
            new BuildMaterialPrerequisitePolicy.Requirement("#minecraft:planks", Integer.MAX_VALUE)
        ));
        assertTrue(shields.upstreamRequirements().contains(
            new BuildMaterialPrerequisitePolicy.Requirement("minecraft:iron_ingot", Integer.MAX_VALUE)
        ));

        var panes = BuildMaterialPrerequisitePolicy.decide(
            "minecraft:glass_pane",
            Integer.MAX_VALUE,
            Map.of()
        );
        assertTrue(panes.upstreamRequirements().get(0).count() > 0);
    }

    @Test
    void refusesBlindGatherForLiquidsContainersDangerousAndUnknownMaterials() {
        for (String itemId : new String[] {
            "minecraft:water_bucket",
            "minecraft:lava_bucket",
            "minecraft:shulker_box",
            "minecraft:tnt",
            "minecraft:obsidian",
            "minecraft:dragon_egg",
            "minecraft:diamond_ore",
            "example:unknown_block"
        }) {
            var result = BuildMaterialPrerequisitePolicy.decide(itemId, 1, Map.of());
            assertEquals(BuildMaterialPrerequisitePolicy.Decision.UNSUPPORTED, result.decision(), itemId);
            assertEquals(BuildMaterialPrerequisitePolicy.Process.NONE, result.process(), itemId);
        }
    }

    @Test
    void permitsOnlyNonDangerousTargetsToUseRealRuntimeTransformRecipes() {
        assertTrue(BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("example:decorative_block"));
        assertTrue(BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("minecraft:hopper"));
        assertTrue(!BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("minecraft:tnt"));
        assertTrue(!BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("minecraft:water_bucket"));
        assertTrue(!BuildMaterialPrerequisitePolicy.canTransformWithRuntimeRecipe("minecraft:diamond_ore"));
    }

    @Test
    void rejectsInvalidRequests() {
        assertThrows(IllegalArgumentException.class,
            () -> BuildMaterialPrerequisitePolicy.decide("", 1, Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> BuildMaterialPrerequisitePolicy.decide("minecraft:dirt", 0, Map.of()));
    }

    private static BuildMaterialPrerequisitePolicy.Resolution decide(
        String itemId,
        int requested,
        String availableId,
        int available
    ) {
        return BuildMaterialPrerequisitePolicy.decide(itemId, requested, Map.of(availableId, available));
    }

    private static void assertCrafts(
        String itemId,
        int requested,
        Map<String, Integer> inventory,
        BuildMaterialPrerequisitePolicy.Requirement expectedRequirement
    ) {
        var result = BuildMaterialPrerequisitePolicy.decide(itemId, requested, inventory);
        assertEquals(BuildMaterialPrerequisitePolicy.Decision.CRAFT, result.decision(), itemId);
        assertTrue(result.upstreamRequirements().contains(expectedRequirement), itemId);
        assertTrue(result.missingUpstream().isEmpty(), itemId);
    }

    private static void assertPlan(
        String itemId,
        int outputPerBatch,
        BuildMaterialPrerequisitePolicy.Requirement... requirements
    ) {
        var plan = BuildMaterialPrerequisitePolicy.plan(itemId);
        assertEquals(BuildMaterialPrerequisitePolicy.Action.CRAFT, plan.action(), itemId);
        assertEquals(outputPerBatch, plan.outputPerBatch(), itemId);
        assertEquals(java.util.List.of(requirements), plan.upstreamRequirements(), itemId);
    }
}
