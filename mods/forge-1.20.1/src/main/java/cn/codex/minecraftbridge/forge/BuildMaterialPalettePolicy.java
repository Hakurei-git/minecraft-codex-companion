package cn.codex.minecraftbridge.forge;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure, testable safety and accounting rules used by the live palette resolver. */
final class BuildMaterialPalettePolicy {
    record Conversion(int outputCount, int inputCount) {
        Conversion {
            if (outputCount <= 0) throw new IllegalArgumentException("outputCount must be positive");
            if (inputCount <= 0) throw new IllegalArgumentException("inputCount must be positive");
        }

        int craftableOutput(int availableInput) {
            if (availableInput <= 0) return 0;
            long batches = availableInput / inputCount;
            return saturate(batches * outputCount);
        }

        int inputForBatches(int batches) {
            return batches <= 0 ? 0 : saturate((long) batches * inputCount);
        }
    }

    record MaterialCandidate(
        String familyId,
        String targetId,
        String baseId,
        Conversion baseToTarget,
        Map<String, Conversion> sourceConversions
    ) {
        MaterialCandidate {
            if (familyId == null || familyId.isBlank()) throw new IllegalArgumentException("familyId is required");
            if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is required");
            if (baseId == null || baseId.isBlank()) throw new IllegalArgumentException("baseId is required");
            sourceConversions = sourceConversions == null ? Map.of() : Map.copyOf(sourceConversions);
        }
    }

    record MaterialSelection(MaterialCandidate candidate, int covered) {}

    private static final Set<String> UNSAFE_EXACT_PATHS = Set.of(
        "bedrock", "barrier", "tnt", "spawner", "dragon_egg",
        "budding_amethyst", "reinforced_deepslate", "end_portal_frame",
        "structure_block", "jigsaw", "respawn_anchor", "powder_snow",
        "wither_rose", "sweet_berry_bush", "cactus", "magma_block",
        "campfire", "soul_campfire"
    );
    private static final Set<String> UNSAFE_SEGMENTS = Set.of(
        "tnt", "bomb", "explosive", "dynamite", "nuke", "landmine",
        "lava", "magma", "fire", "flame", "portal", "spawner",
        "barrier", "bedrock", "cactus", "thorn", "spike", "toxic",
        "acid", "radioactive", "radiation", "trap"
    );
    private static final List<String> UNSAFE_COMPOUNDS = List.of(
        "command_block", "structure_block", "end_portal", "respawn_anchor",
        "powder_snow", "wither_rose", "berry_bush"
    );

    private BuildMaterialPalettePolicy() {}

    static int score(
        Map<String, Integer> counts,
        String baseId,
        Collection<String> componentIds,
        Map<String, Conversion> sourceConversions
    ) {
        Set<String> directIds = new LinkedHashSet<>();
        directIds.add(baseId);
        directIds.addAll(componentIds);
        long total = 0;
        for (String id : directIds) total += Math.max(0, counts.getOrDefault(id, 0));
        for (Map.Entry<String, Conversion> entry : sourceConversions.entrySet()) {
            int direct = Math.max(0, counts.getOrDefault(entry.getKey(), 0));
            int converted = entry.getValue().craftableOutput(direct);
            total += directIds.contains(entry.getKey()) ? Math.max(0, converted - direct) : converted;
        }
        return saturate(total);
    }

    /**
     * Chooses the candidate that can cover the most of one blueprint role and
     * commits only that candidate's real consumption to {@code stock}.
     */
    static MaterialSelection selectAndConsume(
        Collection<MaterialCandidate> candidates,
        int required,
        Map<String, Integer> stock
    ) {
        if (required <= 0 || candidates.isEmpty()) return null;
        MaterialCandidate best = null;
        int bestCoverage = 0;
        for (MaterialCandidate candidate : candidates) {
            int coverage = consume(candidate, required, new HashMap<>(stock));
            if (coverage > bestCoverage
                || (coverage == bestCoverage && coverage > 0 && candidateBefore(candidate, best))) {
                best = candidate;
                bestCoverage = coverage;
            }
        }
        if (best == null || bestCoverage <= 0) return null;
        int committed = consume(best, required, stock);
        return new MaterialSelection(best, committed);
    }

    /** Selects from the first source tier with coverage, then fills only that family from later tiers. */
    static MaterialSelection selectAndConsumeByPriority(
        Collection<MaterialCandidate> candidates,
        int required,
        List<Map<String, Integer>> sourceStocks
    ) {
        if (required <= 0) return null;
        for (int index = 0; index < sourceStocks.size(); index++) {
            MaterialSelection selection = selectAndConsume(candidates, required, sourceStocks.get(index));
            if (selection == null) continue;
            int covered = selection.covered();
            for (int later = index + 1; later < sourceStocks.size() && covered < required; later++) {
                covered = saturate((long) covered + consume(
                    selection.candidate(),
                    required - covered,
                    sourceStocks.get(later)
                ));
            }
            return new MaterialSelection(selection.candidate(), covered);
        }
        return null;
    }

    /**
     * Consumes direct components first, then crafts through the family's base
     * material. Recipe surplus is returned to the stock map for later roles.
     */
    static int consume(MaterialCandidate candidate, int required, Map<String, Integer> stock) {
        if (required <= 0) return 0;
        int covered = take(stock, candidate.targetId(), required);
        int remaining = required - covered;
        Conversion baseToTarget = candidate.baseToTarget();
        if (remaining <= 0 || baseToTarget == null) return covered;

        int neededBatches = divideRoundUp(remaining, baseToTarget.outputCount());
        int neededBase = baseToTarget.inputForBatches(neededBatches);
        int basePool = take(stock, candidate.baseId(), neededBase);
        List<Map.Entry<String, Conversion>> sources = candidate.sourceConversions().entrySet().stream()
            .filter(entry -> !entry.getKey().equals(candidate.baseId()))
            .sorted(BuildMaterialPalettePolicy::compareSourceConversions)
            .toList();
        for (Map.Entry<String, Conversion> source : sources) {
            if (basePool >= neededBase) break;
            int availableInput = Math.max(0, stock.getOrDefault(source.getKey(), 0));
            int availableBatches = availableInput / source.getValue().inputCount();
            int wantedBatches = divideRoundUp(
                neededBase - basePool,
                source.getValue().outputCount()
            );
            int batches = Math.min(availableBatches, wantedBatches);
            if (batches <= 0) continue;
            int consumedInput = source.getValue().inputForBatches(batches);
            stock.put(source.getKey(), availableInput - consumedInput);
            basePool = saturate((long) basePool + source.getValue().craftableOutput(consumedInput));
        }

        int craftBatches = Math.min(
            basePool / baseToTarget.inputCount(),
            neededBatches
        );
        if (craftBatches <= 0) {
            addStock(stock, candidate.baseId(), basePool);
            return covered;
        }
        int consumedBase = baseToTarget.inputForBatches(craftBatches);
        int produced = baseToTarget.craftableOutput(consumedBase);
        int used = Math.min(remaining, produced);
        addStock(stock, candidate.baseId(), basePool - consumedBase);
        addStock(stock, candidate.targetId(), produced - used);
        return saturate((long) covered + used);
    }

    static Map<String, Integer> retainRelevant(Map<String, Integer> counts, Set<String> relevantIds) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 0 && relevantIds.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    static boolean propertiesCompatible(
        Map<String, String> requested,
        Map<String, Set<String>> supported
    ) {
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            Set<String> values = supported.get(entry.getKey());
            if (values == null || !values.contains(entry.getValue())) return false;
        }
        return true;
    }

    static boolean unsafeStructuralId(String value) {
        String path = path(value);
        if (UNSAFE_EXACT_PATHS.contains(path)) return true;
        for (String compound : UNSAFE_COMPOUNDS) if (path.contains(compound)) return true;
        for (String segment : path.split("_")) if (UNSAFE_SEGMENTS.contains(segment)) return true;
        return false;
    }

    static boolean naturalTrunkId(String value) {
        String path = path(value);
        return !path.startsWith("stripped_")
            && (path.endsWith("_log") || path.endsWith("_stem"));
    }

    static String naturalTrunkFamily(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : normalized.substring(0, separator);
        String valuePath = separator < 0 ? normalized : normalized.substring(separator + 1);
        if (!naturalTrunkId(normalized)) return "";
        String suffix = valuePath.endsWith("_log") ? "_log" : "_stem";
        return namespace + ":" + valuePath.substring(0, valuePath.length() - suffix.length());
    }

    static boolean naturalCluster(int logCount, boolean hasVerticalPair, boolean hasCanopy) {
        return logCount >= 3 && hasVerticalPair && hasCanopy;
    }

    static boolean naturalTreeShape(
        int logCount,
        boolean hasVerticalPair,
        boolean hasCanopy,
        int rootedBaseCount,
        int horizontalSpanX,
        int horizontalSpanZ,
        int verticalSpan,
        boolean overflow
    ) {
        return naturalCluster(logCount, hasVerticalPair, hasCanopy)
            && rootedBaseCount >= 1
            && rootedBaseCount <= 4
            && horizontalSpanX <= 12
            && horizontalSpanZ <= 12
            && verticalSpan >= 2
            && verticalSpan <= 48
            && !overflow;
    }

    static boolean naturalLeaf(boolean persistent, int distance) {
        return !persistent && distance >= 1 && distance <= 6;
    }

    static boolean consumesPlacementItem(String blockId, Map<String, String> properties) {
        String blockPath = path(blockId);
        if (blockPath.endsWith("_door") && "upper".equals(properties.get("half"))) return false;
        return !blockPath.endsWith("_bed") || !"head".equals(properties.get("part"));
    }

    static Conversion betterConversion(Conversion current, Conversion candidate) {
        if (current == null) return candidate;
        long currentRate = (long) current.outputCount() * candidate.inputCount();
        long candidateRate = (long) candidate.outputCount() * current.inputCount();
        if (candidateRate != currentRate) return candidateRate > currentRate ? candidate : current;
        if (candidate.inputCount() != current.inputCount()) {
            return candidate.inputCount() < current.inputCount() ? candidate : current;
        }
        return candidate.outputCount() > current.outputCount() ? candidate : current;
    }

    static Conversion composeConversion(Conversion upstream, Conversion downstream) {
        int common = greatestCommonDivisor(upstream.outputCount(), downstream.inputCount());
        int upstreamBatches = downstream.inputCount() / common;
        int downstreamBatches = upstream.outputCount() / common;
        return new Conversion(
            Math.multiplyExact(downstream.outputCount(), downstreamBatches),
            Math.multiplyExact(upstream.inputCount(), upstreamBatches)
        );
    }

    private static boolean candidateBefore(MaterialCandidate candidate, MaterialCandidate current) {
        if (current == null) return true;
        int family = candidate.familyId().compareTo(current.familyId());
        return family < 0 || (family == 0 && candidate.targetId().compareTo(current.targetId()) < 0);
    }

    private static int compareSourceConversions(
        Map.Entry<String, Conversion> left,
        Map.Entry<String, Conversion> right
    ) {
        long leftRate = (long) left.getValue().outputCount() * right.getValue().inputCount();
        long rightRate = (long) right.getValue().outputCount() * left.getValue().inputCount();
        int rate = Long.compare(rightRate, leftRate);
        return rate != 0 ? rate : Comparator.<String>naturalOrder().compare(left.getKey(), right.getKey());
    }

    private static int divideRoundUp(int value, int divisor) {
        if (value <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, ((long) value + divisor - 1L) / divisor);
    }

    private static int take(Map<String, Integer> stock, String id, int requested) {
        if (requested <= 0) return 0;
        int available = Math.max(0, stock.getOrDefault(id, 0));
        int consumed = Math.min(available, requested);
        if (consumed > 0) stock.put(id, available - consumed);
        return consumed;
    }

    private static void addStock(Map<String, Integer> stock, String id, int count) {
        if (count <= 0) return;
        stock.put(id, saturate((long) Math.max(0, stock.getOrDefault(id, 0)) + count));
    }

    private static int greatestCommonDivisor(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return Math.max(1, a);
    }

    private static int saturate(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private static String path(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf(':');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }
}
