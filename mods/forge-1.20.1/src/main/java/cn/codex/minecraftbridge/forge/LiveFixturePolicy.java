package cn.codex.minecraftbridge.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Fixed, test-only world operations. Requests can never supply command text. */
final class LiveFixturePolicy {
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9/._-]+$");
    private static final String NPC = "@e[type=minecraft_codex_bridge:codex_npc,sort=nearest,limit=1]";
    private static final String BOOK = "@e[type=bookofdragons:deadlynadder,tag=CodexBookDragon,sort=nearest,limit=1]";
    private static final String SAINTS = "@e[type=saintsdragons:raevyx,tag=CodexSaintsDragon,sort=nearest,limit=1]";

    private LiveFixturePolicy() {
    }

    static boolean requiresCheats(JsonObject request) {
        return !Set.of("storage", "no-cheat-expedition", "save-and-quit")
            .contains(requiredString(request, "suite"));
    }

    static List<String> commands(JsonObject request) {
        String suite = requiredString(request, "suite");
        String mode = requiredString(request, "mode");
        return switch (suite) {
            case "combat" -> combat(mode);
            case "damage" -> damage(mode);
            case "dragon" -> dragon(mode);
            case "dragon-care" -> dragonCare(mode);
            case "follow" -> follow(mode);
            case "life-skill" -> lifeSkill(mode);
            case "farm-patch" -> farmPatch(mode);
            case "ranch" -> ranch(mode);
            case "food-delivery" -> foodDelivery(mode);
            case "food-survival" -> foodSurvival(mode);
            case "storage" -> storage(mode);
            case "no-cheat-expedition" -> noCheatExpedition(mode);
            case "build-palette", "natural-tree", "build-material-chain" -> buildGather(suite, mode);
            case "build-resume" -> buildResume(mode);
            case "player-state", "eating-action", "fishing-action", "farm-action", "guard-resume" ->
                playerLife(suite, mode);
            case "craft-chain" -> craftChain(mode);
            case "resource-priority" -> resourcePriority(mode);
            case "bed-sleep" -> bedSleep(mode);
            case "deep-mining" -> deepMining(mode);
            case "save-and-quit" -> saveAndQuit(mode);
            case "view-npc" -> viewNpc(mode);
            case "drop-to-npc" -> dropToNpc(mode, request);
            case "npc-state" -> npcState(mode, request);
            default -> throw new IllegalArgumentException("Unknown live fixture suite");
        };
    }

    private static List<String> saveAndQuit(String mode) {
        return switch (mode) {
            case "arm" -> List.of("execute if entity @s");
            default -> throw new IllegalArgumentException("Unknown save-and-quit fixture mode");
        };
    }

    private static List<String> combat(String mode) {
        return switch (mode) {
            case "spawn-husk" -> List.of(
                "kill @e[tag=CodexAcceptanceHostile]",
                "difficulty normal",
                "time set day",
                "execute at @s run summon minecraft:husk ~1 ~ ~ {Tags:[\"CodexAcceptanceHostile\"],PersistenceRequired:1b,Invulnerable:1b,NoAI:1b,Silent:1b,Health:20.0f}"
            );
            case "hit-owner" -> List.of(
                "damage @s 2 minecraft:mob_attack by @e[tag=CodexAcceptanceHostile,sort=nearest,limit=1]"
            );
            case "cleanup" -> List.of("kill @e[tag=CodexAcceptanceHostile]");
            case "set-normal" -> List.of("difficulty normal");
            case "set-peaceful" -> List.of("difficulty peaceful");
            default -> throw new IllegalArgumentException("Unknown combat fixture mode");
        };
    }

    private static List<String> damage(String mode) {
        return switch (mode) {
            case "owner-melee", "owner-projectile", "environment", "cleanup" ->
                List.of("execute if entity @s");
            default -> throw new IllegalArgumentException("Unknown damage fixture mode");
        };
    }

    private static List<String> dragon(String mode) {
        return switch (mode) {
            case "spawn-book", "spawn-saints", "move-book-far", "move-saints-far",
                "raise-book", "raise-saints", "set-book-wander", "set-saints-wander",
                "spawn-combat-target", "arm-combat-target", "co-ride-book", "co-ride-saints",
                "dismount-all", "inspect-book", "inspect-saints", "stage-obstacle-book",
                "stage-obstacle-saints", "clear-obstacle", "cleanup-combat", "cleanup" ->
                List.of("execute if entity @s");
            case "prepare-book-feed" -> List.of(
                "data merge entity " + BOOK + " {dragonNeeds:{foodLevel:35,saturationLevel:0.0f,exhaustionLevel:0.0f,tickTimer:0}}"
            );
            case "inspect-book-needs" -> List.of("data get entity " + BOOK + " dragonNeeds");
            case "inspect-book-tame" -> List.of(
                "data get entity " + BOOK + " Owner",
                "data get entity " + BOOK + " Sitting"
            );
            case "drop-book-food" -> List.of(
                "execute at " + NPC + " run summon minecraft:item ~ ~0.5 ~ {PickupDelay:0s,Item:{id:\"minecraft:chicken\",Count:3b}}"
            );
            case "set-creative" -> List.of("gamemode creative @s");
            case "set-survival" -> List.of("gamemode survival @s", "difficulty peaceful");
            default -> throw new IllegalArgumentException("Unknown dragon fixture mode");
        };
    }

    private static List<String> dragonCare(String mode) {
        if (!Set.of(
            "setup-book", "setup-saints",
            "stage-feed", "inspect-feed",
            "stage-heal", "inspect-heal",
            "stage-tame", "inspect-tame",
            "stage-egg", "inspect-egg",
            "cleanup"
        ).contains(mode)) throw new IllegalArgumentException("Unknown dragon care fixture mode");
        return List.of("execute if entity @s");
    }

    private static List<String> follow(String mode) {
        return switch (mode) {
            case "setup", "move-ground", "inspect-ground", "take-off", "inspect-air", "land",
                "inspect-land", "far-recall", "inspect-recall", "cleanup", "reset-survival" ->
                List.of("execute if entity @s");
            default -> throw new IllegalArgumentException("Unknown follow fixture mode");
        };
    }

    private static List<String> lifeSkill(String mode) {
        return switch (mode) {
            case "fishing" -> List.of(
                "execute at " + NPC + " run fill ~5 ~-1 ~5 ~9 ~-1 ~9 minecraft:stone",
                "execute at " + NPC + " run fill ~5 ~ ~5 ~9 ~ ~9 minecraft:water",
                "execute at " + NPC + " run fill ~5 ~1 ~5 ~9 ~3 ~9 minecraft:air"
            );
            case "sleep" -> List.of(
                "execute at " + NPC + " run fill ~4 ~-1 ~-5 ~4 ~-1 ~-4 minecraft:stone",
                "execute at " + NPC + " run fill ~4 ~ ~-5 ~4 ~2 ~-4 minecraft:air",
                "execute at " + NPC + " run setblock ~4 ~ ~-5 minecraft:red_bed[part=foot,facing=south,occupied=false]",
                "execute at " + NPC + " run setblock ~4 ~ ~-4 minecraft:red_bed[part=head,facing=south,occupied=false]",
                "time set 14000",
                "weather clear"
            );
            case "bed-chain" -> List.of(
                "kill @e[tag=CodexAcceptanceBedSheep]",
                "difficulty peaceful",
                "time set day",
                "weather clear",
                "gamemode survival @s",
                "spawnpoint @s ~ ~ ~",
                "execute at " + NPC + " positioned ~8 ~ ~ positioned over motion_blocking_no_leaves run summon minecraft:sheep ~ ~ ~ {Tags:[\"CodexAcceptanceBedSheep\"],PersistenceRequired:1b,NoAI:1b,Age:0,Color:0b,Sheared:0b}",
                "execute at " + NPC + " positioned ~10 ~ ~2 positioned over motion_blocking_no_leaves run summon minecraft:sheep ~ ~ ~ {Tags:[\"CodexAcceptanceBedSheep\"],PersistenceRequired:1b,NoAI:1b,Age:0,Color:0b,Sheared:0b}",
                "execute at " + NPC + " positioned ~12 ~ ~-2 positioned over motion_blocking_no_leaves run summon minecraft:sheep ~ ~ ~ {Tags:[\"CodexAcceptanceBedSheep\"],PersistenceRequired:1b,NoAI:1b,Age:0,Color:0b,Sheared:0b}",
                "execute at " + NPC + " run summon minecraft:item ~ ~1 ~ {Tags:[\"CodexAcceptanceBedMaterial\"],PickupDelay:0s,Item:{id:\"minecraft:iron_ingot\",Count:2b}}"
            );
            case "bed-cleanup" -> List.of(
                "kill @e[tag=CodexAcceptanceBedSheep]",
                "kill @e[type=minecraft:item,tag=CodexAcceptanceBedMaterial]"
            );
            default -> throw new IllegalArgumentException("Unknown life-skill fixture mode");
        };
    }

    private static List<String> farmPatch(String mode) {
        return switch (mode) {
            case "create-3x3" -> List.of(
                "execute at " + NPC + " run fill ~2 ~ ~2 ~4 ~1 ~4 minecraft:air",
                "execute at " + NPC + " run fill ~2 ~-1 ~2 ~4 ~-1 ~4 minecraft:dirt"
            );
            case "mature-existing-wheat" -> List.of(
                "execute at " + NPC + " run fill ~-8 ~-2 ~-8 ~8 ~2 ~8 minecraft:wheat[age=7] replace minecraft:wheat"
            );
            default -> throw new IllegalArgumentException("Unknown farm-patch fixture mode");
        };
    }

    private static List<String> ranch(String mode) {
        return switch (mode) {
            case "setup-establish", "supply-breed", "setup-cull", "inspect", "cleanup" ->
                List.of("execute if entity @s run data get entity @s Air");
            default -> throw new IllegalArgumentException("Unknown ranch fixture mode");
        };
    }

    private static List<String> foodDelivery(String mode) {
        return switch (mode) {
            case "setup-player", "inspect-player", "setup-home", "inspect-home", "cleanup" ->
                List.of("execute if entity @s run data get entity @s Air");
            default -> throw new IllegalArgumentException("Unknown food delivery fixture mode");
        };
    }

    private static List<String> foodSurvival(String mode) {
        if (!Set.of(
            "setup", "setup-16", "inspect", "arm-guard", "release-guard", "checkpoint", "verify-restart",
            "recover-cleanup", "cleanup"
        ).contains(mode)) {
            throw new IllegalArgumentException("Unknown food survival fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> storage(String mode) {
        return switch (mode) {
            case "setup-retrieve", "inspect-retrieve", "setup-organize", "inspect-organize",
                 "setup-expand", "inspect-expand", "setup-restart", "inspect-restart", "cleanup" ->
                List.of("execute if entity @s run data get entity @s Air");
            default -> throw new IllegalArgumentException("Unknown storage fixture mode");
        };
    }

    private static List<String> noCheatExpedition(String mode) {
        if (!Set.of("setup", "inspect", "cleanup").contains(mode)) {
            throw new IllegalArgumentException("Unknown no-cheat expedition fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> buildGather(String suite, String mode) {
        boolean allowed = switch (suite) {
            case "build-palette" -> Set.of(
                "setup-mixed", "inspect-mixed", "setup-chain", "inspect-chain", "catalog", "cleanup"
            ).contains(mode) || parameterizedBuildPaletteMode(mode);
            case "natural-tree" -> Set.of("setup", "inspect", "cleanup").contains(mode);
            case "build-material-chain" -> Set.of("setup", "inspect", "cleanup").contains(mode);
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Unknown build/gather fixture mode");
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static boolean parameterizedBuildPaletteMode(String mode) {
        return mode.matches("(?:catalog|setup-family|inspect-family)-[0-9]{1,4}");
    }

    private static List<String> buildResume(String mode) {
        if (!Set.of("setup", "inspect-failed", "release", "inspect-complete", "cleanup").contains(mode)) {
            throw new IllegalArgumentException("Unknown build resume fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> playerLife(String suite, String mode) {
        boolean allowed = switch (suite) {
            case "player-state", "fishing-action" -> Set.of("setup", "inspect", "cleanup").contains(mode);
            case "eating-action" -> Set.of(
                "setup-rotten", "setup-melon", "setup-full", "inspect", "cleanup"
            ).contains(mode);
            case "farm-action" -> Set.of("setup-work", "setup-empty", "inspect", "cleanup").contains(mode);
            case "guard-resume" -> Set.of("setup", "arm", "release", "inspect", "cleanup").contains(mode);
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Unknown player life fixture mode");
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> craftChain(String mode) {
        if (!Set.of("setup", "inspect", "checkpoint", "cleanup").contains(mode)) {
            throw new IllegalArgumentException("Unknown craft chain fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> resourcePriority(String mode) {
        if (!Set.of(
            "setup", "setup-fishing", "setup-torches", "inspect", "inspect-craft", "cleanup"
        ).contains(mode)) {
            throw new IllegalArgumentException("Unknown resource priority fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> bedSleep(String mode) {
        if (!Set.of("setup", "inspect", "prepare-night", "wake-day", "cleanup").contains(mode)) {
            throw new IllegalArgumentException("Unknown bed sleep fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> deepMining(String mode) {
        if (!Set.of("setup", "inspect", "cleanup").contains(mode)) {
            throw new IllegalArgumentException("Unknown deep mining fixture mode");
        }
        return List.of("execute if entity @s run data get entity @s Air");
    }

    private static List<String> viewNpc(String mode) {
        return switch (mode) {
            case "npc" -> List.of("execute at " + NPC + " run tp @s ~ ~1 ~5 180 10");
            case "fishing" -> List.of("execute at " + NPC + " run tp @s ~12 ~4 ~12 facing entity " + NPC + " eyes");
            case "sleep" -> List.of("execute at " + NPC + " run tp @s ~10 ~4 ~-10 facing entity " + NPC + " eyes");
            default -> throw new IllegalArgumentException("Unknown view-npc fixture mode");
        };
    }

    private static List<String> dropToNpc(String mode, JsonObject request) {
        if (!mode.equals("drop")) throw new IllegalArgumentException("Unknown drop-to-npc fixture mode");
        String itemId = requiredString(request, "itemId");
        if (!RESOURCE_LOCATION.matcher(itemId).matches()) throw new IllegalArgumentException("Invalid item identifier");
        int count = requiredInt(request, "count", 1, 64);
        return List.of("execute at " + NPC + " run summon minecraft:item ~ ~1 ~ {Item:{id:\""
            + itemId + "\",Count:" + count + "b},PickupDelay:0s}");
    }

    private static List<String> npcState(String mode, JsonObject request) {
        if (!mode.equals("set")) throw new IllegalArgumentException("Unknown npc-state fixture mode");
        int food = requiredInt(request, "food", 0, 20);
        double saturation = requiredDouble(request, "saturation", 0.0D, Math.min(20.0D, food));
        double health = requiredDouble(request, "health", 1.0D, 20.0D);
        return List.of(String.format(
            Locale.ROOT,
            "data merge entity %s {CodexFood:%d,CodexSaturation:%.3ff,Health:%.3ff}",
            NPC,
            food,
            saturation,
            health
        ));
    }

    private static String requiredString(JsonObject request, String key) {
        if (request == null) throw new IllegalArgumentException("Missing live fixture request");
        JsonElement value = request.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing live fixture " + key);
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > 128) throw new IllegalArgumentException("Invalid live fixture " + key);
        return text;
    }

    private static int requiredInt(JsonObject request, String key, int minimum, int maximum) {
        JsonElement value = request.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing live fixture " + key);
        }
        int parsed;
        try {
            parsed = value.getAsInt();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid live fixture " + key, error);
        }
        if (parsed < minimum || parsed > maximum || value.getAsDouble() != parsed) {
            throw new IllegalArgumentException("Out-of-range live fixture " + key);
        }
        return parsed;
    }

    private static double requiredDouble(JsonObject request, String key, double minimum, double maximum) {
        JsonElement value = request.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing live fixture " + key);
        }
        double parsed;
        try {
            parsed = value.getAsDouble();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid live fixture " + key, error);
        }
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("Out-of-range live fixture " + key);
        }
        return parsed;
    }
}
