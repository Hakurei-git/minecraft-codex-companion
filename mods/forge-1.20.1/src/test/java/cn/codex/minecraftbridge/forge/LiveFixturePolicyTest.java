package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFixturePolicyTest {
    @Test
    void allowsOnlyReversibleStorageFixturesWithoutCheats() {
        assertFalse(LiveFixturePolicy.requiresCheats(request("storage", "setup-restart")));
        assertFalse(LiveFixturePolicy.requiresCheats(request("no-cheat-expedition", "setup")));
        assertFalse(LiveFixturePolicy.requiresCheats(request("save-and-quit", "arm")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("dragon", "spawn-book")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("ranch", "setup-establish")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("build-palette", "setup-mixed")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("build-material-chain", "setup")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("build-resume", "setup")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("natural-tree", "setup")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("player-state", "setup")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("eating-action", "setup-rotten")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("fishing-action", "setup")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("farm-action", "setup-work")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("guard-resume", "arm")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("craft-chain", "checkpoint")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("resource-priority", "setup")));
        assertTrue(LiveFixturePolicy.requiresCheats(request("food-survival", "setup")));
    }

    @Test
    void acceptsEveryEnumeratedFixedFixture() {
        Map<String, List<String>> fixtures = Map.ofEntries(
            Map.entry("combat", List.of("spawn-husk", "hit-owner", "cleanup", "set-normal", "set-peaceful")),
            Map.entry("damage", List.of("owner-melee", "owner-projectile", "environment", "cleanup")),
            Map.entry("dragon", List.of(
                "spawn-book", "spawn-saints", "move-book-far", "move-saints-far", "raise-book", "raise-saints",
                "set-book-wander", "set-saints-wander", "spawn-combat-target", "arm-combat-target",
                "prepare-book-feed", "inspect-book-needs", "inspect-book-tame", "drop-book-food",
                "co-ride-book", "co-ride-saints", "dismount-all",
                "inspect-book", "inspect-saints", "stage-obstacle-book", "stage-obstacle-saints",
                "clear-obstacle",
                "cleanup-combat", "cleanup", "set-creative", "set-survival"
            )),
            Map.entry("dragon-care", List.of(
                "setup-book", "setup-saints",
                "stage-feed", "inspect-feed", "stage-heal", "inspect-heal",
                "stage-tame", "inspect-tame", "stage-egg", "inspect-egg", "cleanup"
            )),
            Map.entry("follow", List.of(
                "setup", "move-ground", "inspect-ground", "take-off", "inspect-air", "land",
                "inspect-land", "far-recall", "inspect-recall", "cleanup", "reset-survival"
            )),
            Map.entry("life-skill", List.of("fishing", "sleep", "bed-chain", "bed-cleanup")),
            Map.entry("farm-patch", List.of("create-3x3", "mature-existing-wheat")),
            Map.entry("ranch", List.of("setup-establish", "supply-breed", "setup-cull", "inspect", "cleanup")),
            Map.entry("food-delivery", List.of(
                "setup-player", "inspect-player", "setup-home", "inspect-home", "cleanup"
            )),
            Map.entry("food-survival", List.of(
                "setup", "setup-16", "inspect", "arm-guard", "release-guard", "checkpoint", "verify-restart",
                "recover-cleanup", "cleanup"
            )),
            Map.entry("storage", List.of(
                "setup-retrieve", "inspect-retrieve", "setup-organize", "inspect-organize",
                "setup-expand", "inspect-expand", "setup-restart", "inspect-restart", "cleanup"
            )),
            Map.entry("no-cheat-expedition", List.of("setup", "inspect", "cleanup")),
            Map.entry("build-palette", List.of(
                "setup-mixed", "inspect-mixed", "setup-chain", "inspect-chain", "catalog",
                "catalog-0", "catalog-9999", "setup-family-0", "setup-family-9999",
                "inspect-family-0", "inspect-family-9999", "cleanup"
            )),
            Map.entry("build-material-chain", List.of("setup", "inspect", "cleanup")),
            Map.entry("build-resume", List.of(
                "setup", "inspect-failed", "release", "inspect-complete", "cleanup"
            )),
            Map.entry("natural-tree", List.of("setup", "inspect", "cleanup")),
            Map.entry("player-state", List.of("setup", "inspect", "cleanup")),
            Map.entry("eating-action", List.of(
                "setup-rotten", "setup-melon", "setup-full", "inspect", "cleanup"
            )),
            Map.entry("fishing-action", List.of("setup", "inspect", "cleanup")),
            Map.entry("farm-action", List.of("setup-work", "setup-empty", "inspect", "cleanup")),
            Map.entry("guard-resume", List.of("setup", "arm", "release", "inspect", "cleanup")),
            Map.entry("craft-chain", List.of("setup", "inspect", "checkpoint", "cleanup")),
            Map.entry("resource-priority", List.of(
                "setup", "setup-fishing", "setup-torches", "inspect", "inspect-craft", "cleanup"
            )),
            Map.entry("deep-mining", List.of("setup", "inspect", "cleanup")),
            Map.entry("save-and-quit", List.of("arm")),
            Map.entry("view-npc", List.of("npc", "fishing", "sleep"))
        );
        fixtures.forEach((suite, modes) -> modes.forEach(mode ->
            assertFalse(LiveFixturePolicy.commands(request(suite, mode)).isEmpty(), suite + "/" + mode)
        ));
    }

    @Test
    void rejectsUnknownOrMismatchedFixedFixtures() {
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("dragon", "fishing")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("dragon-care", "run-command")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-palette", "setup")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-palette", "catalog-")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-palette", "catalog-10000")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-palette", "setup-family--1")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-palette", "inspect-family-1;kill")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-material-chain", "run-command")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("build-resume", "inspect")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("natural-tree", "setup-mixed")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("player-state", "arm")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("eating-action", "setup-beef")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("farm-action", "setup")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("guard-resume", "summon")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("craft-chain", "run-command")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("resource-priority", "setblock")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("deep-mining", "run-command")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("food-survival", "spawn-cow")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("no-cheat-expedition", "teleport")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("save-and-quit", "release")));
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(request("unknown", "cleanup")));
    }

    @Test
    void boundsParameterizedItemDropsAndRejectsCommandInjection() {
        JsonObject valid = request("drop-to-npc", "drop");
        valid.addProperty("itemId", "minecraft:oak_log");
        valid.addProperty("count", 64);
        List<String> commands = LiveFixturePolicy.commands(valid);
        assertTrue(commands.get(0).contains("minecraft:oak_log"));
        assertTrue(commands.get(0).contains("Count:64b"));

        JsonObject injected = request("drop-to-npc", "drop");
        injected.addProperty("itemId", "minecraft:oak_log; op @a");
        injected.addProperty("count", 1);
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(injected));

        JsonObject oversized = request("drop-to-npc", "drop");
        oversized.addProperty("itemId", "minecraft:oak_log");
        oversized.addProperty("count", 65);
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(oversized));
    }

    @Test
    void boundsNpcStateAndSaturation() {
        JsonObject valid = request("npc-state", "set");
        valid.addProperty("food", 10);
        valid.addProperty("saturation", 5.5D);
        valid.addProperty("health", 12.0D);
        assertTrue(LiveFixturePolicy.commands(valid).get(0).contains("CodexFood:10"));

        JsonObject invalid = request("npc-state", "set");
        invalid.addProperty("food", 4);
        invalid.addProperty("saturation", 5.0D);
        invalid.addProperty("health", 20.0D);
        assertThrows(IllegalArgumentException.class, () -> LiveFixturePolicy.commands(invalid));
    }

    @Test
    void cleanupTargetsOnlyAcceptanceTaggedEntities() {
        List<String> commands = LiveFixturePolicy.commands(request("dragon", "cleanup"));
        assertTrue(commands.stream().filter(command -> command.startsWith("kill ")).allMatch(command -> command.contains("tag=Codex")));
        List<String> bedCleanup = LiveFixturePolicy.commands(request("life-skill", "bed-cleanup"));
        assertTrue(bedCleanup.stream().allMatch(command -> command.contains("tag=CodexAcceptanceBed")));
    }

    @Test
    void dragonMovementFixturesUseJavaRollbackInsteadOfGlobalWorldCommands() {
        for (String mode : List.of(
            "spawn-book", "spawn-saints", "move-book-far", "move-saints-far",
            "raise-book", "raise-saints", "set-book-wander", "set-saints-wander",
            "spawn-combat-target", "arm-combat-target", "co-ride-book", "co-ride-saints",
            "dismount-all", "inspect-book", "inspect-saints", "stage-obstacle-book",
            "stage-obstacle-saints", "clear-obstacle", "cleanup-combat", "cleanup"
        )) {
            List<String> commands = LiveFixturePolicy.commands(request("dragon", mode));
            assertTrue(commands.stream().allMatch("execute if entity @s"::equals), mode);
            assertTrue(commands.stream().noneMatch(command -> command.startsWith("difficulty ")), mode);
            assertTrue(commands.stream().noneMatch(command -> command.startsWith("time ")), mode);
            assertTrue(commands.stream().noneMatch(command -> command.startsWith("kill ")), mode);
        }
    }

    @Test
    void followAndDamageFixturesTargetTheSelectedNpcInJava() {
        for (String mode : List.of(
            "setup", "move-ground", "inspect-ground", "take-off", "inspect-air", "land",
            "inspect-land", "far-recall", "inspect-recall", "cleanup", "reset-survival"
        )) {
            assertTrue(LiveFixturePolicy.commands(request("follow", mode)).stream()
                .allMatch("execute if entity @s"::equals), mode);
        }
        for (String mode : List.of("owner-melee", "owner-projectile", "environment", "cleanup")) {
            assertTrue(LiveFixturePolicy.commands(request("damage", mode)).stream()
                .allMatch("execute if entity @s"::equals), mode);
        }
    }

    private static JsonObject request(String suite, String mode) {
        JsonObject request = new JsonObject();
        request.addProperty("suite", suite);
        request.addProperty("mode", mode);
        return request;
    }
}
