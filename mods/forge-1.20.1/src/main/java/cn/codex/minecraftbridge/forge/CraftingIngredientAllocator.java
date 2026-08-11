package cn.codex.minecraftbridge.forge;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

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
