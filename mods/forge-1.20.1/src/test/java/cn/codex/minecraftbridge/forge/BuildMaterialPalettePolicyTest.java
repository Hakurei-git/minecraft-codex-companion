package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BuildMaterialPalettePolicyTest {
    @Test
    void scoresEachFinishedMaterialOnceAndUsesRealRecipeBatches() {
        int score = BuildMaterialPalettePolicy.score(
            Map.of(
                "example:planks", 10,
                "example:slab", 2,
                "example:log", 3
            ),
            "example:planks",
            List.of("example:planks", "example:slab"),
            Map.of("example:log", new BuildMaterialPalettePolicy.Conversion(5, 2))
        );

        // 10 planks + 2 slabs + one complete 2-log -> 5-plank batch.
        assertEquals(17, score);
        assertEquals(0, BuildMaterialPalettePolicy.score(
            Map.of(),
            "example:planks",
            List.of("example:planks"),
            Map.of("example:log", new BuildMaterialPalettePolicy.Conversion(4, 1))
        ));

        assertEquals(4, BuildMaterialPalettePolicy.score(
            Map.of("example:log", 1),
            "example:planks",
            List.of("example:planks", "example:log"),
            Map.of("example:log", new BuildMaterialPalettePolicy.Conversion(4, 1))
        ));
    }

    @Test
    void keepsOnlyTaskRelevantPositiveCounts() {
        assertEquals(
            Map.of("minecraft:oak_log", 3),
            BuildMaterialPalettePolicy.retainRelevant(
                Map.of(
                    "minecraft:oak_log", 3,
                    "minecraft:diamond", 7,
                    "minecraft:oak_planks", 0
                ),
                Set.of("minecraft:oak_log", "minecraft:oak_planks")
            )
        );
    }

    @Test
    void requiresEveryOriginalStatePropertyAndValueToSurvive() {
        Map<String, Set<String>> target = Map.of(
            "axis", Set.of("x", "y", "z"),
            "waterlogged", Set.of("true", "false"),
            "facing", Set.of("north", "south", "east", "west")
        );

        assertTrue(BuildMaterialPalettePolicy.propertiesCompatible(
            Map.of("axis", "x", "waterlogged", "true", "facing", "north"),
            target
        ));
        assertFalse(BuildMaterialPalettePolicy.propertiesCompatible(
            Map.of("waterlogged", "true"),
            Map.of("axis", Set.of("x", "y", "z"))
        ));
        assertFalse(BuildMaterialPalettePolicy.propertiesCompatible(
            Map.of("facing", "up"),
            target
        ));
    }

    @Test
    void rejectsDangerousIdentifiersAndRecognizesOnlyNaturalTrunkForms() {
        for (String id : List.of(
            "minecraft:tnt",
            "example:uranium_bomb_planks",
            "example:acid_trap_slab",
            "minecraft:end_portal_frame",
            "minecraft:magma_block"
        )) {
            assertTrue(BuildMaterialPalettePolicy.unsafeStructuralId(id), id);
        }
        assertFalse(BuildMaterialPalettePolicy.unsafeStructuralId("example:fireproof_oak_planks"));
        assertTrue(BuildMaterialPalettePolicy.naturalTrunkId("example:willow_log"));
        assertTrue(BuildMaterialPalettePolicy.naturalTrunkId("minecraft:crimson_stem"));
        assertFalse(BuildMaterialPalettePolicy.naturalTrunkId("minecraft:oak_wood"));
        assertFalse(BuildMaterialPalettePolicy.naturalTrunkId("minecraft:stripped_oak_log"));
        assertFalse(BuildMaterialPalettePolicy.naturalTrunkId("minecraft:warped_hyphae"));
    }

    @Test
    void classifiesOnlySubstantialVerticalCanopiedLogClustersAsNatural() {
        assertTrue(BuildMaterialPalettePolicy.naturalCluster(4, true, true));
        assertFalse(BuildMaterialPalettePolicy.naturalCluster(2, true, true));
        assertFalse(BuildMaterialPalettePolicy.naturalCluster(8, false, true));
        assertFalse(BuildMaterialPalettePolicy.naturalCluster(8, true, false));
    }

    @Test
    void separatesNaturalTrunkFamiliesAndRejectsUnsafeTreeShapes() {
        assertEquals("minecraft:oak", BuildMaterialPalettePolicy.naturalTrunkFamily("minecraft:oak_log"));
        assertEquals("minecraft:crimson", BuildMaterialPalettePolicy.naturalTrunkFamily("minecraft:crimson_stem"));
        assertEquals("", BuildMaterialPalettePolicy.naturalTrunkFamily("minecraft:oak_wood"));
        assertTrue(BuildMaterialPalettePolicy.naturalTreeShape(8, true, true, 1, 4, 3, 7, false));
        assertFalse(BuildMaterialPalettePolicy.naturalTreeShape(8, true, true, 0, 4, 3, 7, false));
        assertFalse(BuildMaterialPalettePolicy.naturalTreeShape(8, true, true, 5, 4, 3, 7, false));
        assertFalse(BuildMaterialPalettePolicy.naturalTreeShape(8, true, true, 1, 13, 3, 7, false));
        assertFalse(BuildMaterialPalettePolicy.naturalTreeShape(8, true, true, 1, 4, 3, 7, true));
    }

    @Test
    void acceptsOnlyNaturallyGeneratedLeavesNearTheirTrunk() {
        assertTrue(BuildMaterialPalettePolicy.naturalLeaf(false, 1));
        assertTrue(BuildMaterialPalettePolicy.naturalLeaf(false, 6));
        assertFalse(BuildMaterialPalettePolicy.naturalLeaf(true, 1));
        assertFalse(BuildMaterialPalettePolicy.naturalLeaf(false, 7));
    }

    @Test
    void countsOnlyTheItemConsumingHalfOfDoorsAndBeds() {
        assertTrue(BuildMaterialPalettePolicy.consumesPlacementItem(
            "minecraft:oak_door", Map.of("half", "lower")
        ));
        assertFalse(BuildMaterialPalettePolicy.consumesPlacementItem(
            "minecraft:oak_door", Map.of("half", "upper")
        ));
        assertTrue(BuildMaterialPalettePolicy.consumesPlacementItem(
            "minecraft:white_bed", Map.of("part", "foot")
        ));
        assertFalse(BuildMaterialPalettePolicy.consumesPlacementItem(
            "minecraft:white_bed", Map.of("part", "head")
        ));
        assertTrue(BuildMaterialPalettePolicy.consumesPlacementItem(
            "minecraft:oak_stairs", Map.of("half", "top")
        ));
    }

    @Test
    void prefersTheMostProductiveRealConversionWithoutHardcodingFour() {
        var twoForOne = new BuildMaterialPalettePolicy.Conversion(2, 1);
        var fiveForTwo = new BuildMaterialPalettePolicy.Conversion(5, 2);

        assertEquals(
            fiveForTwo,
            BuildMaterialPalettePolicy.betterConversion(twoForOne, fiveForTwo)
        );
        assertEquals(10, fiveForTwo.craftableOutput(5));
    }

    @Test
    void composesTwoStageBambooRecipesWithoutInventingYield() {
        var bambooToBlock = new BuildMaterialPalettePolicy.Conversion(1, 9);
        var blockToPlanks = new BuildMaterialPalettePolicy.Conversion(2, 1);

        assertEquals(
            new BuildMaterialPalettePolicy.Conversion(2, 9),
            BuildMaterialPalettePolicy.composeConversion(bambooToBlock, blockToPlanks)
        );
    }

    @Test
    void mixedRolesCanChooseDifferentWoodFamilies() {
        Map<String, Integer> stock = new HashMap<>(Map.of(
            "minecraft:oak_planks", 4,
            "minecraft:spruce_stairs", 3
        ));
        var base = BuildMaterialPalettePolicy.selectAndConsume(
            List.of(
                candidate("minecraft:oak", "minecraft:oak_planks", "minecraft:oak_planks", 1, 1),
                candidate("minecraft:spruce", "minecraft:spruce_planks", "minecraft:spruce_planks", 1, 1)
            ),
            4,
            stock
        );
        var stairs = BuildMaterialPalettePolicy.selectAndConsume(
            List.of(
                candidate("minecraft:oak", "minecraft:oak_stairs", "minecraft:oak_planks", 4, 6),
                candidate("minecraft:spruce", "minecraft:spruce_stairs", "minecraft:spruce_planks", 4, 6)
            ),
            3,
            stock
        );

        assertEquals("minecraft:oak", base.candidate().familyId());
        assertEquals("minecraft:spruce", stairs.candidate().familyId());
        assertEquals(4, base.covered());
        assertEquals(3, stairs.covered());
    }

    @Test
    void mixedSelectionSwitchesFamiliesAfterOneSourceIsConsumed() {
        Map<String, Integer> inventory = new HashMap<>(Map.of("minecraft:oak_log", 1));
        Map<String, Integer> home = new HashMap<>(Map.of("minecraft:spruce_log", 1));
        List<BuildMaterialPalettePolicy.MaterialCandidate> candidates = List.of(
            candidateWithLog("minecraft:oak", "minecraft:oak_planks", "minecraft:oak_log"),
            candidateWithLog("minecraft:spruce", "minecraft:spruce_planks", "minecraft:spruce_log")
        );

        var first = BuildMaterialPalettePolicy.selectAndConsumeByPriority(
            candidates, 4, List.of(inventory, home)
        );
        var second = BuildMaterialPalettePolicy.selectAndConsumeByPriority(
            candidates, 4, List.of(inventory, home)
        );

        assertEquals("minecraft:oak", first.candidate().familyId());
        assertEquals("minecraft:spruce", second.candidate().familyId());
        assertEquals(0, inventory.get("minecraft:oak_log"));
        assertEquals(0, home.get("minecraft:spruce_log"));
    }

    @Test
    void directComponentsAndLogConversionsShareOneConsumptionLedger() {
        Map<String, Integer> stock = new HashMap<>(Map.of(
            "minecraft:oak_stairs", 2,
            "minecraft:oak_log", 2
        ));
        var stairs = new BuildMaterialPalettePolicy.MaterialCandidate(
            "minecraft:oak",
            "minecraft:oak_stairs",
            "minecraft:oak_planks",
            new BuildMaterialPalettePolicy.Conversion(4, 6),
            Map.of("minecraft:oak_log", new BuildMaterialPalettePolicy.Conversion(4, 1))
        );

        var selection = BuildMaterialPalettePolicy.selectAndConsume(List.of(stairs), 8, stock);

        assertEquals(6, selection.covered());
        assertEquals(0, stock.get("minecraft:oak_stairs"));
        assertEquals(0, stock.get("minecraft:oak_log"));
        assertEquals(2, stock.get("minecraft:oak_planks"));
        assertNull(BuildMaterialPalettePolicy.selectAndConsume(List.of(stairs), 2, stock));
    }

    private static BuildMaterialPalettePolicy.MaterialCandidate candidate(
        String family,
        String target,
        String base,
        int output,
        int input
    ) {
        return new BuildMaterialPalettePolicy.MaterialCandidate(
            family,
            target,
            base,
            new BuildMaterialPalettePolicy.Conversion(output, input),
            Map.of()
        );
    }

    private static BuildMaterialPalettePolicy.MaterialCandidate candidateWithLog(
        String family,
        String target,
        String log
    ) {
        return new BuildMaterialPalettePolicy.MaterialCandidate(
            family,
            target,
            target,
            new BuildMaterialPalettePolicy.Conversion(1, 1),
            Map.of(log, new BuildMaterialPalettePolicy.Conversion(4, 1))
        );
    }
}
