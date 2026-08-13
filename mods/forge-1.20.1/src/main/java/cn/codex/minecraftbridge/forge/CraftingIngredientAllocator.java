package cn.codex.minecraftbridge.forge;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

final class CraftingIngredientAllocator {
    private CraftingIngredientAllocator() {}

    static List<Integer> allocate(List<Ingredient> ingredients, int slotCount, IntFunction<ItemStack> stackAt) {
        IngredientAllocationPolicy.Result result = allocatePolicy(ingredients, slotCount, stackAt);
        return result.complete() ? result.slots() : null;
    }

    static Ingredient firstMissing(List<Ingredient> ingredients, int slotCount, IntFunction<ItemStack> stackAt) {
        IngredientAllocationPolicy.Result result = allocatePolicy(ingredients, slotCount, stackAt);
        return result.complete() ? null : ingredients.get(result.missingIngredientIndex());
    }

    /**
     * Checks output space against the inventory state after the allocated
     * ingredients have been consumed. A full backpack can still craft safely
     * when a one-count ingredient stack becomes the output slot.
     */
    static boolean canInsertAfterConsumption(
        List<Integer> allocatedSlots,
        int slotCount,
        IntFunction<ItemStack> stackAt,
        ItemStack output
    ) {
        if (output == null || output.isEmpty()) return false;
        int capacity = Math.max(0, slotCount);
        List<CraftingOutputSpacePolicy.Slot> inventory = new ArrayList<>();
        for (int slot = 0; slot < capacity; slot++) {
            ItemStack current = stackAt.apply(slot);
            if (current == null || current.isEmpty()) continue;
            inventory.add(new CraftingOutputSpacePolicy.Slot(
                slot,
                stackKey(current),
                current.getCount(),
                current.getMaxStackSize()
            ));
        }
        return CraftingOutputSpacePolicy.canInsertAfterConsumption(
            capacity,
            inventory,
            allocatedSlots,
            stackKey(output),
            output.getCount(),
            output.getMaxStackSize()
        );
    }

    static String stackKey(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + String.valueOf(stack.getTag());
    }

    private static IngredientAllocationPolicy.Result allocatePolicy(
        List<Ingredient> ingredients,
        int slotCount,
        IntFunction<ItemStack> stackAt
    ) {
        return IngredientAllocationPolicy.allocate(
            ingredients.size(),
            slotCount,
            index -> !ingredients.get(index).isEmpty(),
            (ingredient, slot) -> ingredients.get(ingredient).test(stackAt.apply(slot)),
            slot -> stackAt.apply(slot).getCount()
        );
    }
}
