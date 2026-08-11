package cn.codex.minecraftbridge.forge;

import java.util.function.Predicate;

final class ResourceSelectorPolicy {
    private ResourceSelectorPolicy() {
    }

    record Parsed(boolean tag, String resourceId) {
    }

    static Parsed parse(String value) {
        String normalized = value == null ? "" : value.trim();
        boolean tag = normalized.startsWith("#");
        if (tag) normalized = normalized.substring(1).trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("资源选择器不能为空");
        if (!normalized.contains(":")) normalized = "minecraft:" + normalized;
        return new Parsed(tag, normalized);
    }

    static boolean matches(Parsed selector, String candidateId, Predicate<String> tagContains) {
        return selector.tag()
            ? tagContains.test(selector.resourceId())
            : selector.resourceId().equals(candidateId);
    }
}
