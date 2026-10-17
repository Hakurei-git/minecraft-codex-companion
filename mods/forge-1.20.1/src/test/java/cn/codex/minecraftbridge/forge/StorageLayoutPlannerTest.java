package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
import static cn.codex.minecraftbridge.forge.HomeStoragePolicy.Category.*;

final class StorageLayoutPlannerTest {
    private StorageLayoutPlanner.Stack<String> stack(String key, int count, int max, HomeStoragePolicy.Category category) {
        return new StorageLayoutPlanner.Stack<>(key, count, max, category);
    }
    private StorageLayoutPlanner.Plan<String> plan(List<StorageLayoutPlanner.Stack<String>> source, Integer... slots) {
        return StorageLayoutPlanner.plan(source, List.of(slots), String::equals);
    }
    private List<StorageLayoutPlanner.Stack<String>> slots(int count, HomeStoragePolicy.Category category) {
        List<StorageLayoutPlanner.Stack<String>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(stack(category + ":" + i, 1, 64, category));
        return result;
    }

    @Test void compactsMatchingItemsBeforeEstimatingChestCapacity() {
        var result = plan(List.of(stack("oak", 32, 64, WOOD), stack("oak", 32, 64, WOOD)), 1);
        assertTrue(result.fits());
        assertEquals(List.of(stack("oak", 64, 64, WOOD)), result.containers().get(0));
    }
    @Test void preservesDifferentNbtAndDurabilityInsteadOfMergingTools() {
        var input = List.of(stack("pickaxe:damage=1", 1, 1, TOOLS), stack("pickaxe:damage=8", 1, 1, TOOLS));
        assertEquals(input, plan(input, 27).containers().get(0));
    }
    @Test void splitsOversizedInputsWithoutLosingCounts() {
        var result = plan(List.of(stack("oak", 130, 64, WOOD)), 27);
        assertEquals(List.of(64, 64, 2), result.containers().get(0).stream().map(StorageLayoutPlanner.Stack::count).toList());
    }
    @Test void respectsSixteenItemLimitsForSnowballs() {
        var result = plan(List.of(stack("snowball", 36, 16, MISC)), 27);
        assertEquals(List.of(16, 16, 4), result.containers().get(0).stream().map(StorageLayoutPlanner.Stack::count).toList());
    }
    @Test void reservesASeparateChestForEachCategoryEvenWhenThereAreEmptySlots() {
        var result = plan(List.of(stack("oak", 1, 64, WOOD), stack("dirt", 1, 64, BUILDING)), 27);
        assertFalse(result.fits());
        assertEquals(1, result.missingSingleChests());
    }
    @Test void makingADoubleChestDoesNotSolveAMissingCategoryChest() {
        var input = List.of(stack("oak", 1, 64, WOOD), stack("dirt", 1, 64, BUILDING));
        assertFalse(plan(input, 54).fits());
        assertTrue(plan(input, 27, 27).fits());
    }
    @Test void doubleChestSolvesSameCategoryOverflow() {
        var input = slots(40, WOOD);
        assertEquals(1, plan(input, 27).missingSingleChests());
        assertTrue(plan(input, 54).fits());
    }
    @Test void keepsAWholeCategoryTogetherWhenALargerChestIsAvailable() {
        var result = plan(slots(40, WOOD), 27, 54, 27);
        assertTrue(result.fits());
        assertTrue(result.containers().get(0).isEmpty());
        assertEquals(40, result.containers().get(1).size());
    }
    @Test void assignsSmallCategoriesToSingleChestsAndLargeOnesToDoubleChests() {
        var input = new ArrayList<>(slots(20, WOOD));
        input.addAll(slots(50, BUILDING));
        var result = plan(input, 27, 54);
        assertTrue(result.fits());
        assertEquals(WOOD, result.containers().get(0).get(0).category());
        assertEquals(BUILDING, result.containers().get(1).get(0).category());
    }
    @Test void aVeryLargeCategoryCanOverflowIntoAnotherBoxOfTheSameCategory() {
        var result = plan(slots(80, BUILDING), 54, 27);
        assertTrue(result.fits());
        assertEquals(List.of(54, 26), result.containers().stream().map(List::size).toList());
    }
    @Test void reportsEnoughAdditionalBoxesForMultipleMissingCategories() {
        var input = new ArrayList<>(slots(60, BUILDING));
        input.addAll(slots(3, TOOLS));
        assertEquals(4, plan(input).missingSingleChests());
    }
    @Test void noItemsDoesNotRequireBuildingAnEmptyChest() {
        assertTrue(plan(List.of()).fits());
    }
    @Test void failedPlansDoNotModifyTheSource() {
        var source = List.of(stack("oak", 65, 64, WOOD), stack("dirt", 2, 64, BUILDING));
        var before = List.copyOf(source);
        assertFalse(plan(source, 1).fits());
        assertEquals(before, source);
    }
    @Test void randomizedPlansConserveEveryItemAndKeepCategoriesSeparate() {
        Random random = new Random(20261017);
        for (int iteration = 0; iteration < 200; iteration++) {
            List<StorageLayoutPlanner.Stack<String>> input = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int identity = random.nextInt(20);
                input.add(stack("item:" + identity, random.nextInt(100) + 1, identity % 3 == 0 ? 16 : 64,
                    HomeStoragePolicy.Category.values()[identity % 4]));
            }
            var result = plan(input, 54, 54, 54, 54, 54, 54, 54, 54, 54, 54, 54, 54);
            assertTrue(result.fits());
            Map<String, Integer> expected = new HashMap<>();
            Map<String, Integer> actual = new HashMap<>();
            input.forEach(s -> expected.merge(s.item(), s.count(), Integer::sum));
            for (var contents : result.containers()) {
                assertTrue(contents.size() <= 54);
                assertTrue(contents.stream().map(StorageLayoutPlanner.Stack::category).distinct().count() <= 1);
                for (var item : contents) {
                    assertTrue(item.count() > 0 && item.count() <= item.limit());
                    actual.merge(item.item(), item.count(), Integer::sum);
                }
            }
            assertEquals(expected, actual);
            assertEquals(result, plan(input, 54, 54, 54, 54, 54, 54, 54, 54, 54, 54, 54, 54));
        }
    }
}
