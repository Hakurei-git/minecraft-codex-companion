package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class FurnaceRecoveryPolicyTest {
    @Test
    void returnsUnconsumedCoalFuelFromAnEmptyClaimedFurnace() {
        assertEquals(1, FurnaceRecoveryPolicy.recoverableCount(
            FurnaceRecoveryPolicy.FUEL_SLOT,
            "minecraft:coal",
            1,
            "minecraft:raw_iron",
            3,
            "minecraft:iron_ingot",
            1,
            3,
            Map.of("minecraft:coal", 1)
        ));
    }

    @Test
    void boundsInputOutputAndFuelToRecordedTaskContributions() {
        Map<String, Integer> fuel = Map.of("minecraft:coal", 2);
        assertEquals(3, FurnaceRecoveryPolicy.recoverableCount(
            0, "minecraft:raw_iron", 7,
            "minecraft:raw_iron", 3, "minecraft:iron_ingot", 1, 0, fuel
        ));
        assertEquals(2, FurnaceRecoveryPolicy.recoverableCount(
            1, "minecraft:coal", 8,
            "minecraft:raw_iron", 3, "minecraft:iron_ingot", 1, 0, fuel
        ));
        assertEquals(1, FurnaceRecoveryPolicy.recoverableCount(
            2, "minecraft:iron_ingot", 5,
            "minecraft:raw_iron", 3, "minecraft:iron_ingot", 1, 2, fuel
        ));
    }

    @Test
    void neverTakesUnknownOrUnrecordedPlayerContents() {
        Map<String, Integer> fuel = Map.of("minecraft:coal", 1);
        assertEquals(0, FurnaceRecoveryPolicy.recoverableCount(
            0, "minecraft:diamond", 3,
            "minecraft:raw_iron", 3, "minecraft:iron_ingot", 1, 0, fuel
        ));
        assertEquals(0, FurnaceRecoveryPolicy.recoverableCount(
            1, "minecraft:charcoal", 1,
            "minecraft:raw_iron", 3, "minecraft:iron_ingot", 1, 0, fuel
        ));
        assertEquals(0, FurnaceRecoveryPolicy.recoverableCount(
            2, "minecraft:gold_ingot", 1,
            "minecraft:raw_iron", 3, "minecraft:iron_ingot", 1, 0, fuel
        ));
    }

    @Test
    void returnsOnlyTheRemainingOutputBudgetAndLavaBucketResidue() {
        assertEquals(2, FurnaceRecoveryPolicy.remainingOutputBudget(3, 2, 4));
        assertEquals(1, FurnaceRecoveryPolicy.recoverableCount(
            1, "minecraft:bucket", 1,
            "minecraft:raw_iron", 1, "minecraft:iron_ingot", 1, 0,
            Map.of("minecraft:lava_bucket", 1)
        ));
    }
}
