package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherCandidatePolicyTest {
    @Test
    void rawIronRejectsUnrelatedBlocksBeforeLootEvaluation() {
        assertTrue(GatherCandidatePolicy.mayProduce("minecraft:raw_iron", "minecraft:iron_ore", false));
        assertTrue(GatherCandidatePolicy.mayProduce("minecraft:raw_iron", "minecraft:deepslate_iron_ore", false));
        assertFalse(GatherCandidatePolicy.mayProduce("minecraft:raw_iron", "minecraft:stone", false));
    }

    @Test
    void logTagUsesTheBlockTagFastPath() {
        assertTrue(GatherCandidatePolicy.mayProduce("#minecraft:logs", "modded:red_log", true));
        assertFalse(GatherCandidatePolicy.mayProduce("#minecraft:logs", "minecraft:dirt", false));
    }

    @Test
    void unknownSelectorsRemainCompatibleWithModdedLootTables() {
        assertTrue(GatherCandidatePolicy.mayProduce("modded:crystal", "modded:crystal_ore", false));
    }

    @Test
    void stringGatheringScansCobwebsWithoutTreatingUnrelatedBlocksAsCandidates() {
        assertTrue(GatherCandidatePolicy.mayProduce("minecraft:string", "minecraft:cobweb", false));
        assertFalse(GatherCandidatePolicy.mayProduce("minecraft:string", "minecraft:stone", false));
    }

    @Test
    void utilityIngredientsScanOnlyTheirRealVanillaSourceBlocks() {
        assertTrue(GatherCandidatePolicy.mayProduce("minecraft:flint", "minecraft:gravel", false));
        assertFalse(GatherCandidatePolicy.mayProduce("minecraft:flint", "minecraft:stone", false));
        assertTrue(GatherCandidatePolicy.mayProduce(
            "minecraft:amethyst_shard", "minecraft:amethyst_cluster", false
        ));
        assertFalse(GatherCandidatePolicy.mayProduce(
            "minecraft:amethyst_shard", "minecraft:amethyst_block", false
        ));
        assertTrue(GatherCandidatePolicy.isProbabilisticKnownSource(
            "minecraft:flint", "minecraft:gravel"
        ));
        assertFalse(GatherCandidatePolicy.isProbabilisticKnownSource(
            "minecraft:coal", "minecraft:coal_ore"
        ));
    }

    @Test
    void protectsEveryLogAtAndInsideTheHomeBoundary() {
        assertTrue(GatherCandidatePolicy.protectedHomeResource(true, 0.0, 16, true));
        assertTrue(GatherCandidatePolicy.protectedHomeResource(true, 256.0, 16, true));
        assertFalse(GatherCandidatePolicy.protectedHomeResource(true, 256.01, 16, true));
        assertFalse(GatherCandidatePolicy.protectedHomeResource(false, 1.0, 16, true));
        assertFalse(GatherCandidatePolicy.protectedHomeResource(true, 1.0, 16, false));
    }
}
