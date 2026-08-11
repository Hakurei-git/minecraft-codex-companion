package cn.codex.minecraftbridge.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DragonCareLiveFixtureTest {
    @Test
    void bookOfDragons131UsesTheRegisteredDragonEggEntityId() {
        assertEquals("bookofdragons:dragon_egg", DragonCareLiveFixture.BOOK_EGG_ENTITY_ID);
    }

    @Test
    void fixtureFailuresExposeOnlyStableDiagnosticCodes() {
        assertEquals("entity-unavailable", DragonCareLiveFixture.failureCode(
            new IllegalStateException("Fixture entity is unavailable: bookofdragons:dragon_egg")
        ));
        assertEquals("npc-not-idle", DragonCareLiveFixture.failureCode(
            new IllegalStateException("Finish NPC tasks before changing the dragon care fixture")
        ));
        assertEquals("fixture-failed", DragonCareLiveFixture.failureCode(
            new IllegalStateException("implementation detail")
        ));
    }

    @Test
    void cleanupRequiresBothActorsInTheirOriginalDimensions() {
        assertEquals("", DragonCareLiveFixture.cleanupDimensionRefusalReason(
            "minecraft:overworld", "minecraft:overworld",
            "minecraft:overworld", "minecraft:overworld"
        ));
        assertEquals(
            "Return owner and NPC to their original dimensions before dragon care cleanup",
            DragonCareLiveFixture.cleanupDimensionRefusalReason(
                "minecraft:overworld", "minecraft:overworld",
                "minecraft:the_nether", "minecraft:overworld"
            )
        );
        assertEquals(
            "Dragon care fixture dimension snapshot is missing",
            DragonCareLiveFixture.cleanupDimensionRefusalReason(
                "", "minecraft:overworld", "minecraft:overworld", "minecraft:overworld"
            )
        );
    }
}
