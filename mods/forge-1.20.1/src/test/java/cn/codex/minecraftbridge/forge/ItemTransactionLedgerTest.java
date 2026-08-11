package cn.codex.minecraftbridge.forge;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ItemTransactionLedgerTest {
    @Test
    void firstObservationEstablishesABaselineWithoutInventingHistory() {
        ItemTransactionLedger ledger = new ItemTransactionLedger();

        ledger.observe(10, "task-1", "gather", Map.of("minecraft:coal", 12));

        assertTrue(ledger.recent().isEmpty());
    }

    @Test
    void recordsAcquisitionAndConsumptionWithResultingBalances() {
        ItemTransactionLedger ledger = new ItemTransactionLedger();
        ledger.observe(10, "", "idle", Map.of());
        ledger.observe(11, "gather-1", "gather", Map.of("minecraft:coal", 26));
        ledger.observe(12, "craft-1", "craft", Map.of("minecraft:coal", 10, "minecraft:torch", 64));

        assertEquals(3, ledger.recent().size());
        assertEquals(new ItemTransactionLedger.Entry(
            1, 11, "gather-1", "gather", "minecraft:coal", 26, 26
        ), ledger.recent().get(0));
        assertEquals(new ItemTransactionLedger.Entry(
            2, 12, "craft-1", "craft", "minecraft:coal", -16, 10
        ), ledger.recent().get(1));
        assertEquals(new ItemTransactionLedger.Entry(
            3, 12, "craft-1", "craft", "minecraft:torch", 64, 64
        ), ledger.recent().get(2));
    }

    @Test
    void keepsDistinctActionsThatOccurInTheSameGameTick() {
        ItemTransactionLedger ledger = new ItemTransactionLedger();
        ledger.observe(20, "", "idle", Map.of("minecraft:raw_iron", 3, "minecraft:coal", 2));
        ledger.observe(21, "craft-iron", "furnace-input", Map.of("minecraft:coal", 2));
        ledger.observe(21, "craft-iron", "furnace-fuel", Map.of("minecraft:coal", 1));

        assertEquals(2, ledger.recent().size());
        assertEquals("furnace-input", ledger.recent().get(0).action());
        assertEquals("minecraft:raw_iron", ledger.recent().get(0).itemId());
        assertEquals(-3, ledger.recent().get(0).delta());
        assertEquals("furnace-fuel", ledger.recent().get(1).action());
        assertEquals("minecraft:coal", ledger.recent().get(1).itemId());
        assertEquals(-1, ledger.recent().get(1).delta());
    }

    @Test
    void boundsHistoryAndPersistsItAcrossNpcReloads() {
        ItemTransactionLedger ledger = new ItemTransactionLedger();
        ledger.observe(1, "", "idle", Map.of());
        for (int index = 1; index <= ItemTransactionLedger.MAX_ENTRIES + 10; index++) {
            ledger.observe(index + 1L, "task", "gather", Map.of("minecraft:coal", index));
        }

        CompoundTag saved = ledger.save();
        ItemTransactionLedger restored = new ItemTransactionLedger();
        restored.load(saved);

        assertEquals(ItemTransactionLedger.MAX_ENTRIES, restored.recent().size());
        assertEquals(11, restored.recent().get(0).sequence());
        assertEquals(ItemTransactionLedger.MAX_ENTRIES + 10, restored.recent().get(ItemTransactionLedger.MAX_ENTRIES - 1).balanceAfter());
    }
}
