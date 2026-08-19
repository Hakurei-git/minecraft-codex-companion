package cn.codex.minecraftbridge.forge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Data-only acquisition policy for materials referenced by a build plan.
 *
 * <p>The policy deliberately does not inspect blocks or recipes at runtime. It
 * only describes a conservative vanilla 1.20.1 material chain that a task
 * engine may execute later. Unknown materials, liquid containers, valuable
 * non-renewable blocks and containers that may hold player items are never
 * classified as safe direct-gather targets.</p>
 */
final class BuildMaterialPrerequisitePolicy {
    enum Action {
        GATHER,
        CRAFT,
        SMELT,
        TILL,
        REJECT
    }

    enum Decision {
        AVAILABLE,
        GATHER_DIRECTLY,
        CRAFT,
        NEEDS_UPSTREAM,
        UNSUPPORTED
    }

    enum Process {
        NONE,
        CRAFTING,
        SMELTING,
        TILLING
    }

    /** Stable recipe-independent description used by the task target stack. */
    record MaterialPlan(
        Action action,
        String materialItemId,
        String gatherSelector,
        int outputPerBatch,
        List<Requirement> upstreamRequirements,
        String refusalReason
    ) {
        MaterialPlan {
            Objects.requireNonNull(action, "action");
            materialItemId = normalizeId(materialItemId);
            gatherSelector = normalizeId(gatherSelector);
            if (outputPerBatch <= 0) throw new IllegalArgumentException("outputPerBatch must be positive");
            upstreamRequirements = List.copyOf(upstreamRequirements);
            refusalReason = refusalReason == null ? "" : refusalReason;
        }
    }

    record Requirement(String selector, int count) {
        Requirement {
            selector = normalizeId(selector);
            if (selector.isBlank()) throw new IllegalArgumentException("selector must not be blank");
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
        }
    }

    record Resolution(
        Decision decision,
        String materialItemId,
        int requestedCount,
        int availableCount,
        int missingCount,
        Process process,
        List<Requirement> upstreamRequirements,
        List<Requirement> missingUpstream,
        String reason
    ) {
        Resolution {
            Objects.requireNonNull(decision, "decision");
            materialItemId = normalizeId(materialItemId);
            Objects.requireNonNull(process, "process");
            upstreamRequirements = List.copyOf(upstreamRequirements);
            missingUpstream = List.copyOf(missingUpstream);
            reason = reason == null ? "" : reason;
        }
    }

    private enum RuleKind { DIRECT_GATHER, TRANSFORM }

    private record Rule(RuleKind kind, Process process, int outputPerBatch, List<Requirement> ingredients) {
        private Rule {
            ingredients = List.copyOf(ingredients);
        }

        static Rule directGather() {
            return new Rule(RuleKind.DIRECT_GATHER, Process.NONE, 1, List.of());
        }

        static Rule transform(Process process, int outputPerBatch, Requirement... ingredients) {
            return new Rule(RuleKind.TRANSFORM, process, outputPerBatch, List.of(ingredients));
        }
    }

    private static final List<String> WOOD_FAMILIES = List.of(
        "dark_oak", "mangrove", "crimson", "warped", "cherry",
        "acacia", "jungle", "spruce", "birch", "bamboo", "oak"
    );

    /** Raw ingredients that need an entity/life-skill route, not a block scan. */
    private static final Set<String> ENTITY_ACQUISITION_ITEMS = Set.of(
        "minecraft:leather",
        "minecraft:feather",
        "minecraft:slime_ball",
        "minecraft:scute"
    );

    private static final Set<String> WOOL_COLORS = Set.of(
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    );

    private BuildMaterialPrerequisitePolicy() {
    }

    /**
     * Returns the conservative acquisition template for one material. Runtime
     * recipe discovery may refine CRAFT plans, but must not widen GATHER or
     * REJECT decisions without another explicit safety rule.
     */
    static MaterialPlan plan(String materialItemId) {
        String itemId = normalizeId(materialItemId);
        if (itemId.isBlank()) throw new IllegalArgumentException("materialItemId must not be blank");
        Rule rule = ruleFor(itemId);
        if (rule == null) {
            return new MaterialPlan(
                Action.REJECT, itemId, "", 1, List.of(), unsupportedReason(itemId)
            );
        }
        if (rule.kind() == RuleKind.DIRECT_GATHER) {
            return new MaterialPlan(Action.GATHER, itemId, itemId, 1, List.of(), "");
        }
        Action action = switch (rule.process()) {
            case CRAFTING -> Action.CRAFT;
            case SMELTING -> Action.SMELT;
            case TILLING -> Action.TILL;
            case NONE -> throw new IllegalStateException("Transform rule must declare a process");
        };
        return new MaterialPlan(action, itemId, "", rule.outputPerBatch(), rule.ingredients(), "");
    }

    static boolean canGatherDirectly(String materialItemId) {
        return plan(materialItemId).action() == Action.GATHER;
    }

    /**
     * Signals that the deterministic block-gather route is the wrong executor.
     * The task engine must hand these materials to its hunting/shearing/slime
     * life-skill route instead of scanning arbitrary blocks forever.
     */
    static boolean requiresEntityAcquisition(String materialItemId) {
        String itemId = normalizeId(materialItemId);
        if (ENTITY_ACQUISITION_ITEMS.contains(itemId)) return true;
        if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_wool")) return false;
        String color = itemId.substring("minecraft:".length(), itemId.length() - "_wool".length());
        return WOOL_COLORS.contains(color);
    }

    /**
     * A confirmed build may use a real runtime recipe for an otherwise unknown
     * block. This never authorizes direct gathering and still excludes fluid,
     * privileged, explosive and non-renewable targets.
     */
    static boolean canTransformWithRuntimeRecipe(String materialItemId) {
        String itemId = normalizeId(materialItemId);
        return !itemId.isBlank() && !isLiquid(itemId) && !isDangerousOrNonRenewable(itemId);
    }

    /**
     * Resolves one exact build material against an inventory count map.
     *
     * <p>The map may contain exact item IDs or aggregate selectors such as
     * {@code #minecraft:planks}. When an aggregate selector is absent, this
     * class derives the count from exact vanilla item IDs in the map.</p>
     */
    static Resolution decide(String materialItemId, int requestedCount, Map<String, Integer> availableItems) {
        String itemId = normalizeId(materialItemId);
        if (itemId.isBlank()) throw new IllegalArgumentException("materialItemId must not be blank");
        if (requestedCount <= 0) throw new IllegalArgumentException("requestedCount must be positive");

        Map<String, Integer> inventory = normalizeInventory(availableItems);
        int available = countAvailable(itemId, inventory);
        int missing = Math.max(0, requestedCount - available);
        if (missing == 0) {
            return resolution(
                Decision.AVAILABLE, itemId, requestedCount, available, 0,
                Process.NONE, List.of(), List.of(), "背包中已有足量建筑材料"
            );
        }

        Rule rule = ruleFor(itemId);
        if (rule == null) {
            return resolution(
                Decision.UNSUPPORTED, itemId, requestedCount, available, missing,
                Process.NONE, List.of(), List.of(), unsupportedReason(itemId)
            );
        }
        if (rule.kind() == RuleKind.DIRECT_GATHER) {
            return resolution(
                Decision.GATHER_DIRECTLY, itemId, requestedCount, available, missing,
                Process.NONE, List.of(), List.of(), "允许在自然资源区直接采集缺失材料"
            );
        }

        int batches = ceilDiv(missing, rule.outputPerBatch());
        List<Requirement> requirements = new ArrayList<>();
        List<Requirement> missingRequirements = new ArrayList<>();
        for (Requirement ingredient : rule.ingredients()) {
            int required = saturatedMultiply(ingredient.count(), batches);
            Requirement scaled = new Requirement(ingredient.selector(), required);
            requirements.add(scaled);
            int upstreamAvailable = countAvailable(ingredient.selector(), inventory);
            if (upstreamAvailable < required) {
                missingRequirements.add(new Requirement(ingredient.selector(), required - upstreamAvailable));
            }
        }
        Decision decision = missingRequirements.isEmpty() ? Decision.CRAFT : Decision.NEEDS_UPSTREAM;
        String reason = decision == Decision.CRAFT
            ? "上游材料已齐，可按受支持的原版流程加工"
            : "需要先取得缺失的上游材料";
        return resolution(
            decision, itemId, requestedCount, available, missing,
            rule.process(), requirements, missingRequirements, reason
        );
    }

    private static Resolution resolution(
        Decision decision,
        String itemId,
        int requested,
        int available,
        int missing,
        Process process,
        List<Requirement> requirements,
        List<Requirement> missingRequirements,
        String reason
    ) {
        return new Resolution(
            decision, itemId, requested, available, missing,
            process, requirements, missingRequirements, reason
        );
    }

    private static Rule ruleFor(String itemId) {
        if (isDirectGatherMaterial(itemId)) return Rule.directGather();

        Rule wooden = woodenRule(itemId);
        if (wooden != null) return wooden;

        Rule equipment = vanillaEquipmentRule(itemId);
        if (equipment != null) return equipment;

        Rule bed = bedRule(itemId);
        if (bed != null) return bed;

        return switch (itemId) {
            case "minecraft:stick" -> Rule.transform(
                Process.CRAFTING, 4, new Requirement("#minecraft:planks", 2)
            );
            case "minecraft:crafting_table" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("#minecraft:planks", 4)
            );
            case "minecraft:chest" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("#minecraft:planks", 8)
            );
            case "minecraft:barrel" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("#minecraft:planks", 6),
                new Requirement("#minecraft:wooden_slabs", 2)
            );
            case "minecraft:furnace" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("#minecraft:stone_crafting_materials", 8)
            );
            case "minecraft:torch" -> Rule.transform(
                Process.CRAFTING, 4,
                new Requirement("#minecraft:coals", 1),
                new Requirement("minecraft:stick", 1)
            );
            case "minecraft:soul_torch" -> Rule.transform(
                Process.CRAFTING, 4,
                new Requirement("#minecraft:coals", 1),
                new Requirement("minecraft:stick", 1),
                new Requirement("minecraft:soul_sand", 1)
            );
            case "minecraft:fishing_rod" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:stick", 3),
                new Requirement("minecraft:string", 2)
            );
            case "minecraft:bow" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:stick", 3),
                new Requirement("minecraft:string", 3)
            );
            case "minecraft:crossbow" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:stick", 3),
                new Requirement("minecraft:string", 2),
                new Requirement("minecraft:iron_ingot", 1),
                new Requirement("minecraft:tripwire_hook", 1)
            );
            case "minecraft:arrow" -> Rule.transform(
                Process.CRAFTING, 4,
                new Requirement("minecraft:flint", 1),
                new Requirement("minecraft:stick", 1),
                new Requirement("minecraft:feather", 1)
            );
            case "minecraft:shield" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("#minecraft:planks", 6),
                new Requirement("minecraft:iron_ingot", 1)
            );
            case "minecraft:shears" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("minecraft:iron_ingot", 2)
            );
            case "minecraft:bucket" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("minecraft:iron_ingot", 3)
            );
            case "minecraft:flint_and_steel" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:iron_ingot", 1),
                new Requirement("minecraft:flint", 1)
            );
            case "minecraft:tripwire_hook" -> Rule.transform(
                Process.CRAFTING, 2,
                new Requirement("minecraft:iron_ingot", 1),
                new Requirement("minecraft:stick", 1),
                new Requirement("#minecraft:planks", 1)
            );
            case "minecraft:hopper" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:iron_ingot", 5),
                new Requirement("minecraft:chest", 1)
            );
            case "minecraft:dispenser" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:cobblestone", 7),
                new Requirement("minecraft:bow", 1),
                new Requirement("minecraft:redstone", 1)
            );
            case "minecraft:dropper" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:cobblestone", 7),
                new Requirement("minecraft:redstone", 1)
            );
            case "minecraft:compass" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:iron_ingot", 4),
                new Requirement("minecraft:redstone", 1)
            );
            case "minecraft:clock" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:gold_ingot", 4),
                new Requirement("minecraft:redstone", 1)
            );
            case "minecraft:spyglass" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:copper_ingot", 2),
                new Requirement("minecraft:amethyst_shard", 1)
            );
            case "minecraft:brush" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:feather", 1),
                new Requirement("minecraft:copper_ingot", 1),
                new Requirement("minecraft:stick", 1)
            );
            case "minecraft:lead" -> Rule.transform(
                Process.CRAFTING, 2,
                new Requirement("minecraft:string", 4),
                new Requirement("minecraft:slime_ball", 1)
            );
            case "minecraft:item_frame" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:stick", 8),
                new Requirement("minecraft:leather", 1)
            );
            case "minecraft:painting" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:stick", 8),
                new Requirement("#minecraft:wool", 1)
            );
            case "minecraft:ladder" -> Rule.transform(
                Process.CRAFTING, 3, new Requirement("minecraft:stick", 7)
            );
            case "minecraft:campfire" -> Rule.transform(
                Process.CRAFTING, 1,
                new Requirement("minecraft:stick", 3),
                new Requirement("#minecraft:coals", 1),
                new Requirement("#minecraft:logs", 3)
            );
            case "minecraft:iron_ingot" -> Rule.transform(
                Process.SMELTING, 1, new Requirement("minecraft:raw_iron", 1)
            );
            case "minecraft:copper_ingot" -> Rule.transform(
                Process.SMELTING, 1, new Requirement("minecraft:raw_copper", 1)
            );
            case "minecraft:gold_ingot" -> Rule.transform(
                Process.SMELTING, 1, new Requirement("minecraft:raw_gold", 1)
            );
            case "minecraft:stone" -> Rule.transform(
                Process.SMELTING, 1, new Requirement("minecraft:cobblestone", 1)
            );
            case "minecraft:stone_bricks" -> Rule.transform(
                Process.CRAFTING, 4, new Requirement("minecraft:stone", 4)
            );
            case "minecraft:glass" -> Rule.transform(
                Process.SMELTING, 1, new Requirement("minecraft:sand", 1)
            );
            case "minecraft:glass_pane" -> Rule.transform(
                Process.CRAFTING, 16, new Requirement("minecraft:glass", 6)
            );
            case "minecraft:clay" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("minecraft:clay_ball", 4)
            );
            case "minecraft:farmland" -> Rule.transform(
                Process.TILLING, 1, new Requirement("minecraft:dirt", 1)
            );
            case "minecraft:pumpkin_seeds" -> Rule.transform(
                Process.CRAFTING, 4, new Requirement("minecraft:pumpkin", 1)
            );
            case "minecraft:melon_seeds" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("minecraft:melon_slice", 1)
            );
            case "minecraft:bamboo_block" -> Rule.transform(
                Process.CRAFTING, 1, new Requirement("minecraft:bamboo", 9)
            );
            default -> null;
        };
    }

    private static Rule vanillaEquipmentRule(String itemId) {
        if (!itemId.startsWith("minecraft:")) return null;
        String path = itemId.substring("minecraft:".length());

        if (path.equals("turtle_helmet")) {
            return Rule.transform(
                Process.CRAFTING,
                1,
                new Requirement("minecraft:scute", 5)
            );
        }

        for (String kind : List.of("pickaxe", "sword", "shovel", "axe", "hoe")) {
            String suffix = "_" + kind;
            if (!path.endsWith(suffix)) continue;
            String tier = path.substring(0, path.length() - suffix.length());
            String material = switch (tier) {
                case "wooden" -> "#minecraft:planks";
                case "stone" -> "#minecraft:stone_tool_materials";
                case "golden" -> "minecraft:gold_ingot";
                case "iron" -> "minecraft:iron_ingot";
                case "diamond" -> "minecraft:diamond";
                default -> "";
            };
            if (material.isBlank()) return null;
            int materialCount = switch (kind) {
                case "sword", "hoe" -> 2;
                case "shovel" -> 1;
                default -> 3;
            };
            int stickCount = switch (kind) {
                case "sword", "shovel" -> 1;
                default -> 2;
            };
            return Rule.transform(
                Process.CRAFTING,
                1,
                new Requirement(material, materialCount),
                new Requirement("minecraft:stick", stickCount)
            );
        }

        for (String kind : List.of("chestplate", "leggings", "helmet", "boots")) {
            String suffix = "_" + kind;
            if (!path.endsWith(suffix)) continue;
            String tier = path.substring(0, path.length() - suffix.length());
            String material = switch (tier) {
                case "leather" -> "minecraft:leather";
                case "golden" -> "minecraft:gold_ingot";
                case "iron" -> "minecraft:iron_ingot";
                case "diamond" -> "minecraft:diamond";
                default -> "";
            };
            if (material.isBlank()) return null;
            int materialCount = switch (kind) {
                case "helmet" -> 5;
                case "chestplate" -> 8;
                case "leggings" -> 7;
                case "boots" -> 4;
                default -> throw new IllegalStateException("unknown armor kind");
            };
            return Rule.transform(
                Process.CRAFTING,
                1,
                new Requirement(material, materialCount)
            );
        }

        return null;
    }

    private static Rule bedRule(String itemId) {
        if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_bed")) return null;
        String color = itemId.substring("minecraft:".length(), itemId.length() - "_bed".length());
        if (!WOOL_COLORS.contains(color)) return null;
        return Rule.transform(
            Process.CRAFTING,
            1,
            new Requirement("minecraft:" + color + "_wool", 3),
            new Requirement("#minecraft:planks", 3)
        );
    }

    private static Rule woodenRule(String itemId) {
        if (!itemId.startsWith("minecraft:")) return null;
        String path = itemId.substring("minecraft:".length());
        String family = woodFamily(path);
        if (family == null) return null;

        String planks = "minecraft:" + family + "_planks";
        if (path.equals(family + "_planks")) {
            if (family.equals("bamboo")) {
                return Rule.transform(
                    Process.CRAFTING, 2, new Requirement("minecraft:bamboo_block", 1)
                );
            }
            String logs = switch (family) {
                case "crimson", "warped" -> "#minecraft:" + family + "_stems";
                default -> "#minecraft:" + family + "_logs";
            };
            return Rule.transform(Process.CRAFTING, 4, new Requirement(logs, 1));
        }
        if (path.equals(family + "_slab")) {
            return Rule.transform(Process.CRAFTING, 6, new Requirement(planks, 3));
        }
        if (path.equals(family + "_stairs")) {
            return Rule.transform(Process.CRAFTING, 4, new Requirement(planks, 6));
        }
        if (path.equals(family + "_fence")) {
            return Rule.transform(
                Process.CRAFTING, 3,
                new Requirement(planks, 4),
                new Requirement("minecraft:stick", 2)
            );
        }
        if (path.equals(family + "_fence_gate")) {
            return Rule.transform(
                Process.CRAFTING, 1,
                new Requirement(planks, 2),
                new Requirement("minecraft:stick", 4)
            );
        }
        if (path.equals(family + "_door")) {
            return Rule.transform(Process.CRAFTING, 3, new Requirement(planks, 6));
        }
        if (path.equals(family + "_trapdoor")) {
            return Rule.transform(Process.CRAFTING, 2, new Requirement(planks, 6));
        }
        if (path.equals(family + "_button")) {
            return Rule.transform(Process.CRAFTING, 1, new Requirement(planks, 1));
        }
        if (path.equals(family + "_pressure_plate")) {
            return Rule.transform(Process.CRAFTING, 1, new Requirement(planks, 2));
        }
        return null;
    }

    static String preferredWoodGatherSelector(String materialItemId) {
        String family = woodFamilyId(materialItemId);
        if (family.isBlank()) return "";
        return switch (family) {
            case "bamboo" -> "minecraft:bamboo";
            case "crimson", "warped" -> "#minecraft:" + family + "_stems";
            default -> "#minecraft:" + family + "_logs";
        };
    }

    static boolean ingredientPreservesWoodFamily(
        String materialItemId,
        String candidateItemId,
        List<String> acceptedItemIds
    ) {
        String targetFamily = woodFamilyId(materialItemId);
        if (targetFamily.isBlank()) return true;

        boolean familySensitive = acceptedItemIds.stream()
            .map(BuildMaterialPrerequisitePolicy::woodFamilyId)
            .anyMatch(family -> !family.isBlank());
        if (!familySensitive) return true;

        String candidateFamily = woodFamilyId(candidateItemId);
        return candidateFamily.isBlank() || candidateFamily.equals(targetFamily);
    }

    static String preferredWoodFamilyCandidate(String materialItemId, List<String> acceptedItemIds) {
        String targetFamily = woodFamilyId(materialItemId);
        if (targetFamily.isBlank()) return "";
        return acceptedItemIds.stream()
            .map(BuildMaterialPrerequisitePolicy::normalizeId)
            .filter(candidate -> woodFamilyId(candidate).equals(targetFamily))
            .findFirst()
            .orElse("");
    }

    static boolean recipePreservesWoodFamily(
        String materialItemId,
        List<List<String>> ingredientOptions
    ) {
        String targetFamily = woodFamilyId(materialItemId);
        if (targetFamily.isBlank()) return true;

        for (List<String> options : ingredientOptions) {
            boolean hasWoodFamily = false;
            boolean hasCompatibleOption = false;
            for (String option : options) {
                String optionFamily = woodFamilyId(option);
                if (optionFamily.isBlank()) {
                    hasCompatibleOption = true;
                } else {
                    hasWoodFamily = true;
                    if (optionFamily.equals(targetFamily)) hasCompatibleOption = true;
                }
            }
            if (hasWoodFamily && !hasCompatibleOption) return false;
        }
        return true;
    }

    private static String woodFamilyId(String itemId) {
        String normalized = normalizeId(itemId);
        if (!normalized.startsWith("minecraft:")) return "";
        String family = woodFamily(normalized.substring("minecraft:".length()));
        return family == null ? "" : family;
    }

    private static boolean isDirectGatherMaterial(String itemId) {
        if (itemId.equals("minecraft:cobblestone")
            || itemId.equals("minecraft:cobbled_deepslate")
            || itemId.equals("minecraft:blackstone")
            || itemId.equals("minecraft:dirt")
            || itemId.equals("minecraft:sand")
            || itemId.equals("minecraft:red_sand")
            || itemId.equals("minecraft:gravel")
            || itemId.equals("minecraft:clay_ball")
            || itemId.equals("minecraft:coal")
            || itemId.equals("minecraft:raw_iron")
            || itemId.equals("minecraft:raw_copper")
            || itemId.equals("minecraft:raw_gold")
            || itemId.equals("minecraft:lapis_lazuli")
            || itemId.equals("minecraft:redstone")
            || itemId.equals("minecraft:diamond")
            || itemId.equals("minecraft:emerald")
            || itemId.equals("minecraft:nether_quartz")
            || itemId.equals("minecraft:amethyst_shard")
            || itemId.equals("minecraft:flint")
            || itemId.equals("minecraft:string")
            || itemId.equals("minecraft:pumpkin")
            || itemId.equals("minecraft:melon_slice")
            || itemId.equals("minecraft:bamboo")
            || itemId.equals("minecraft:sugar_cane")
            || itemId.equals("minecraft:soul_sand")
            || itemId.equals("minecraft:soul_soil")) return true;

        if (itemId.startsWith("minecraft:")
            && !itemId.substring("minecraft:".length()).startsWith("stripped_")
            && (itemId.endsWith("_log") || itemId.endsWith("_stem"))) return true;

        return switch (itemId) {
            case "minecraft:wheat_seeds",
                "minecraft:beetroot_seeds",
                "minecraft:carrot",
                "minecraft:potato",
                "minecraft:cocoa_beans",
                "minecraft:nether_wart" -> true;
            default -> false;
        };
    }

    private static String unsupportedReason(String itemId) {
        if (requiresEntityAcquisition(itemId)) {
            return "该材料需要狩猎、剪毛或生物掉落动作链，不能作为方块直接采集";
        }
        if (isLiquid(itemId)) return "液体源和装液容器必须由显式、安全的桶交互提供";
        if (isContainer(itemId)) return "不会盲采可能包含玩家物品的容器";
        if (isDangerousOrNonRenewable(itemId)) return "危险、受保护或不可再生材料不允许自动盲采";
        return "没有受支持且可审计的建筑材料获取规则";
    }

    private static boolean isLiquid(String itemId) {
        return itemId.equals("minecraft:water")
            || itemId.equals("minecraft:lava")
            || itemId.equals("minecraft:water_bucket")
            || itemId.equals("minecraft:lava_bucket")
            || itemId.endsWith("_fluid_bucket");
    }

    private static boolean isContainer(String itemId) {
        if (itemId.equals("minecraft:chest")
            || itemId.equals("minecraft:barrel")
            || itemId.equals("minecraft:furnace")
            || itemId.equals("minecraft:crafting_table")) return false;
        return itemId.endsWith("_chest")
            || itemId.endsWith("_barrel")
            || itemId.endsWith("_shulker_box")
            || itemId.equals("minecraft:hopper")
            || itemId.equals("minecraft:dispenser")
            || itemId.equals("minecraft:dropper");
    }

    private static boolean isDangerousOrNonRenewable(String itemId) {
        return itemId.equals("minecraft:tnt")
            || itemId.equals("minecraft:obsidian")
            || itemId.equals("minecraft:crying_obsidian")
            || itemId.equals("minecraft:bedrock")
            || itemId.equals("minecraft:dragon_egg")
            || itemId.equals("minecraft:budding_amethyst")
            || itemId.equals("minecraft:reinforced_deepslate")
            || itemId.equals("minecraft:spawner")
            || itemId.equals("minecraft:end_portal_frame")
            || itemId.contains("command_block")
            || itemId.equals("minecraft:structure_block")
            || itemId.equals("minecraft:jigsaw")
            || itemId.endsWith("_ore")
            || itemId.equals("minecraft:ancient_debris");
    }

    private static String woodFamily(String path) {
        if (path.equals("bamboo")) return "bamboo";
        for (String family : WOOD_FAMILIES) {
            if (path.startsWith(family + "_")) return family;
        }
        return null;
    }

    private static int countAvailable(String selector, Map<String, Integer> inventory) {
        Integer aggregate = inventory.get(selector);
        if (aggregate != null) return aggregate;
        if (!selector.startsWith("#")) return 0;

        int total = 0;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            if (entry.getKey().startsWith("#") || !matchesSelector(selector, entry.getKey())) continue;
            total = saturatedAdd(total, entry.getValue());
        }
        return total;
    }

    private static boolean matchesSelector(String selector, String itemId) {
        if (selector.equals("#minecraft:planks")) {
            return itemId.startsWith("minecraft:") && itemId.endsWith("_planks");
        }
        if (selector.equals("#minecraft:wooden_slabs")) {
            if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_slab")) return false;
            return woodFamily(itemId.substring("minecraft:".length())) != null;
        }
        if (selector.equals("#minecraft:stone_crafting_materials")) {
            return itemId.equals("minecraft:cobblestone")
                || itemId.equals("minecraft:cobbled_deepslate")
                || itemId.equals("minecraft:blackstone");
        }
        if (selector.equals("#minecraft:stone_tool_materials")) {
            return itemId.equals("minecraft:cobblestone")
                || itemId.equals("minecraft:cobbled_deepslate")
                || itemId.equals("minecraft:blackstone");
        }
        if (selector.equals("#minecraft:coals")) {
            return itemId.equals("minecraft:coal") || itemId.equals("minecraft:charcoal");
        }
        if (selector.equals("#minecraft:wool")) {
            if (!itemId.startsWith("minecraft:") || !itemId.endsWith("_wool")) return false;
            String color = itemId.substring("minecraft:".length(), itemId.length() - "_wool".length());
            return WOOL_COLORS.contains(color);
        }
        if (selector.equals("#minecraft:logs")) {
            if (!itemId.startsWith("minecraft:")) return false;
            String path = itemId.substring("minecraft:".length());
            return path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae");
        }
        if (!selector.startsWith("#minecraft:")) return false;
        String tag = selector.substring("#minecraft:".length());
        if (tag.endsWith("_logs")) {
            String family = tag.substring(0, tag.length() - "_logs".length());
            String path = itemId.startsWith("minecraft:")
                ? itemId.substring("minecraft:".length())
                : "";
            return path.equals(family + "_log")
                || path.equals(family + "_wood")
                || path.equals("stripped_" + family + "_log")
                || path.equals("stripped_" + family + "_wood");
        }
        if (tag.equals("crimson_stems") || tag.equals("warped_stems")) {
            String family = tag.substring(0, tag.length() - "_stems".length());
            String path = itemId.startsWith("minecraft:")
                ? itemId.substring("minecraft:".length())
                : "";
            return path.equals(family + "_stem")
                || path.equals(family + "_hyphae")
                || path.equals("stripped_" + family + "_stem")
                || path.equals("stripped_" + family + "_hyphae");
        }
        return false;
    }

    static String preferredCoalAcquisition(
        String selector,
        String materialContextId,
        int deficit,
        int availableLogs
    ) {
        if (!normalizeId(selector).equals("#minecraft:coals")) return "";
        String context = normalizeId(materialContextId);
        if (!context.equals("minecraft:torch") && !context.equals("minecraft:soul_torch")) return "";
        int outputNeeded = Math.max(1, deficit);
        int fuelLogs = ceilDiv(saturatedMultiply(outputNeeded, 2), 3);
        return availableLogs >= saturatedAdd(outputNeeded, fuelLogs)
            ? "minecraft:charcoal"
            : "";
    }

    private static Map<String, Integer> normalizeInventory(Map<String, Integer> availableItems) {
        if (availableItems == null || availableItems.isEmpty()) return Map.of();
        Map<String, Integer> normalized = new HashMap<>();
        for (Map.Entry<String, Integer> entry : availableItems.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            String key = normalizeId(entry.getKey());
            if (key.isBlank()) continue;
            normalized.merge(key, entry.getValue(), BuildMaterialPrerequisitePolicy::saturatedAdd);
        }
        return Map.copyOf(normalized);
    }

    private static int ceilDiv(int value, int divisor) {
        return (int) (((long) value + divisor - 1L) / divisor);
    }

    private static int saturatedMultiply(int left, int right) {
        long product = (long) left * right;
        return product >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
