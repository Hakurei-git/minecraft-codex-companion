package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InventoryCompactionPolicyTest {
    @Test
    void mergesFragmentedStacksWithoutCrossingItemOrNbtKeys() {
        List<InventoryCompactionPolicy.Transfer> transfers = InventoryCompactionPolicy.plan(27, List.of(
            new InventoryCompactionPolicy.Slot(3, "dirt|null", 20, 64),
            new InventoryCompactionPolicy.Slot(4, "copper|null", 36, 64),
            new InventoryCompactionPolicy.Slot(10, "dirt|null", 27, 64),
            new InventoryCompactionPolicy.Slot(11, "dirt|null", 20, 64),
            new InventoryCompactionPolicy.Slot(12, "copper|null", 4, 64),
            new InventoryCompactionPolicy.Slot(14, "copper|null", 12, 64),
            new InventoryCompactionPolicy.Slot(21, "redstone|tag-a", 14, 64),
            new InventoryCompactionPolicy.Slot(24, "redstone|tag-b", 5, 64)
        ));

        assertEquals(List.of(
            new InventoryCompactionPolicy.Transfer(10, 3, 27),
            new InventoryCompactionPolicy.Transfer(11, 3, 17),
            new InventoryCompactionPolicy.Transfer(12, 4, 4),
            new InventoryCompactionPolicy.Transfer(14, 4, 12)
        ), transfers);
    }

    @Test
    void ignoresUnstackableItems() {
        assertTrue(InventoryCompactionPolicy.plan(27, List.of(
            new InventoryCompactionPolicy.Slot(0, "pickaxe|null", 1, 1),
            new InventoryCompactionPolicy.Slot(1, "pickaxe|null", 1, 1)
        )).isEmpty());
    }
}
