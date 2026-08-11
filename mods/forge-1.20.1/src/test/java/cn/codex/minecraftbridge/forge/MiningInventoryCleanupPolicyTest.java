package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MiningInventoryCleanupPolicyTest {
    @Test
    void waitsUntilTheBackpackIsNearlyFull() {
        List<MiningInventoryCleanupPolicy.InventorySlot> inventory = List.of(
            slot(0, "minecraft:tuff", 64),
            slot(1, "minecraft:tuff", 64)
        );

        assertTrue(MiningInventoryCleanupPolicy.plan(5, inventory, Set.of()).isEmpty());
    }

    @Test
    void freesTwoWholeSlotsAndKeepsTheUsefulStoneReserve() {
        List<MiningInventoryCleanupPolicy.InventorySlot> inventory = new ArrayList<>();
        inventory.add(slot(0, "minecraft:diamond", 3));
        inventory.add(slot(1, "minecraft:torch", 32));
        inventory.add(slot(2, "minecraft:iron_pickaxe", 1));
        inventory.add(slot(3, "minecraft:cooked_beef", 16));
        inventory.add(slot(4, "minecraft:ladder", 32));
        for (int index = 5; index < 27; index++) {
            String itemId = index < 8 ? "minecraft:tuff" : "minecraft:cobblestone";
            inventory.add(slot(index, itemId, 64));
        }

        List<MiningInventoryCleanupPolicy.Drop> drops = MiningInventoryCleanupPolicy.plan(
            27,
            inventory,
            Set.of("minecraft:diamond")
        );

        assertEquals(List.of(
            new MiningInventoryCleanupPolicy.Drop(5, "minecraft:tuff", 64),
            new MiningInventoryCleanupPolicy.Drop(6, "minecraft:tuff", 64)
        ), drops);
        int retainedStone = inventory.stream()
            .filter(slot -> slot.itemId().equals("minecraft:tuff")
                || slot.itemId().equals("minecraft:cobblestone"))
            .mapToInt(MiningInventoryCleanupPolicy.InventorySlot::count)
            .sum() - drops.stream().mapToInt(MiningInventoryCleanupPolicy.Drop::count).sum();
        assertTrue(retainedStone >= MiningInventoryCleanupPolicy.RETAINED_STONE_ITEMS);
    }

    @Test
    void freesOnlyOneSlotWhenOneIsAlreadyEmpty() {
        List<MiningInventoryCleanupPolicy.InventorySlot> inventory = List.of(
            slot(0, "minecraft:cobblestone", 64),
            slot(1, "minecraft:stone", 64),
            slot(2, "minecraft:tuff", 64),
            slot(3, "minecraft:diamond", 1)
        );

        assertEquals(List.of(
            new MiningInventoryCleanupPolicy.Drop(2, "minecraft:tuff", 64)
        ), MiningInventoryCleanupPolicy.plan(5, inventory, Set.of("minecraft:diamond")));
    }

    @Test
    void neverDropsGoalsValuablesSuppliesOrTheLastStoneReserve() {
        List<MiningInventoryCleanupPolicy.InventorySlot> inventory = List.of(
            slot(0, "minecraft:cobblestone", 64),
            slot(1, "minecraft:diamond", 64),
            slot(2, "minecraft:coal", 64),
            slot(3, "minecraft:torch", 64),
            slot(4, "minecraft:iron_pickaxe", 1)
        );
        assertTrue(MiningInventoryCleanupPolicy.plan(
            5,
            inventory,
            Set.of("minecraft:cobblestone", "minecraft:diamond")
        ).isEmpty());
    }

    @Test
    void protectsStoneRecipeInputsWhileDiscardingOtherStone() {
        Set<String> protectedItems = MiningInventoryCleanupPolicy.protectedItems(
            List.of("minecraft:stone_pickaxe", "minecraft:cobblestone"),
            List.of(
                List.of("minecraft:cobblestone", "minecraft:cobbled_deepslate"),
                List.of("minecraft:stick")
            )
        );
        List<MiningInventoryCleanupPolicy.InventorySlot> inventory = List.of(
            slot(0, "minecraft:cobblestone", 64),
            slot(1, "minecraft:cobbled_deepslate", 64),
            slot(2, "minecraft:tuff", 64),
            slot(3, "minecraft:granite", 64),
            slot(4, "minecraft:andesite", 64),
            slot(5, "minecraft:coal", 64)
        );

        assertEquals(List.of(
            new MiningInventoryCleanupPolicy.Drop(2, "minecraft:tuff", 64),
            new MiningInventoryCleanupPolicy.Drop(3, "minecraft:granite", 64)
        ), MiningInventoryCleanupPolicy.plan(6, inventory, protectedItems));
        assertTrue(protectedItems.contains("minecraft:ladder"));
        assertTrue(protectedItems.contains("minecraft:torch"));
        assertTrue(protectedItems.contains("minecraft:crafting_table"));
        assertTrue(protectedItems.contains("minecraft:chest"));
    }

    @Test
    void discardedItemsRemainExcludedFromTheSameNpc() {
        UUID npc = UUID.randomUUID();
        assertTrue(MiningInventoryCleanupPolicy.isDiscardedBy(npc, npc));
        assertFalse(MiningInventoryCleanupPolicy.isDiscardedBy(npc, UUID.randomUUID()));
        assertFalse(MiningInventoryCleanupPolicy.isDiscardedBy(npc, null));
    }

    private static MiningInventoryCleanupPolicy.InventorySlot slot(int index, String itemId, int count) {
        return new MiningInventoryCleanupPolicy.InventorySlot(index, itemId, count);
    }
}
