package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SmeltingRecipeSelectionPolicyTest {
    private static final List<SmeltingRecipeSelectionPolicy.Candidate> IRON_RECIPES = List.of(
        new SmeltingRecipeSelectionPolicy.Candidate(List.of("minecraft:iron_ore")),
        new SmeltingRecipeSelectionPolicy.Candidate(List.of("minecraft:deepslate_iron_ore")),
        new SmeltingRecipeSelectionPolicy.Candidate(List.of("minecraft:raw_iron"))
    );

    @Test
    void prefersTheSafePrerequisiteInputWhenTheOutputHasSeveralRecipes() {
        assertEquals(2, SmeltingRecipeSelectionPolicy.choose(
            IRON_RECIPES,
            Set.of(),
            List.of("minecraft:raw_iron")
        ));
    }

    @Test
    void reusesAnInputAlreadyInInventoryBeforeGatheringThePreferredInput() {
        assertEquals(0, SmeltingRecipeSelectionPolicy.choose(
            IRON_RECIPES,
            Set.of("minecraft:iron_ore"),
            List.of("minecraft:raw_iron")
        ));
    }

    @Test
    void fallsBackDeterministicallyWhenNoCandidateIsPreferred() {
        assertEquals(0, SmeltingRecipeSelectionPolicy.choose(
            IRON_RECIPES,
            Set.of(),
            List.of("minecraft:raw_gold")
        ));
        assertEquals(-1, SmeltingRecipeSelectionPolicy.choose(List.of(), Set.of(), List.of()));
    }
}
