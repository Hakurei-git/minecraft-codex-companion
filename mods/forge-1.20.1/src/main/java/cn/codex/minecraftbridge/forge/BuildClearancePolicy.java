package cn.codex.minecraftbridge.forge;

import java.util.Set;

/** Safety gate for blocks occupying a confirmed construction footprint. */
final class BuildClearancePolicy {
    private static final Set<String> PROTECTED_BLOCKS = Set.of(
        "minecraft:bedrock",
        "minecraft:barrier",
        "minecraft:command_block",
        "minecraft:chain_command_block",
        "minecraft:repeating_command_block",
        "minecraft:structure_block",
        "minecraft:jigsaw",
        "minecraft:end_portal_frame",
        "minecraft:end_portal",
        "minecraft:end_gateway",
        "minecraft:nether_portal",
        "minecraft:spawner",
        "minecraft:trial_spawner",
        "minecraft:vault"
    );

    private BuildClearancePolicy() {
    }

    static boolean mayClear(
        String blockId,
        boolean air,
        boolean replaceable,
        boolean fluid,
        boolean hasBlockEntity,
        float destroySpeed
    ) {
        return blockId != null
            && !blockId.isBlank()
            && !air
            && !replaceable
            && !fluid
            && !hasBlockEntity
            && destroySpeed >= 0.0F
            && !PROTECTED_BLOCKS.contains(blockId);
    }
}
