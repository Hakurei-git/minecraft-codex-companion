import type { BuildBlock } from "@mc/protocol";
import { ControlError } from "./errors.js";

const FORBIDDEN_BLOCKS = new Set([
  "minecraft:command_block",
  "minecraft:chain_command_block",
  "minecraft:repeating_command_block",
  "minecraft:structure_block",
  "minecraft:jigsaw",
  "minecraft:spawner",
  "minecraft:end_portal",
  "minecraft:end_gateway",
]);

const FORBIDDEN_PROPERTIES = new Set(["Command", "command", "auto", "powered"]);

/**
 * Imported builds are data-only plans. They cannot smuggle commands, entities,
 * block-entity NBT, or privileged world-generation blocks into the Forge actor.
 */
export function assertBuildBlocksSafe(blocks: BuildBlock[]): void {
  const forbidden = new Set<string>();
  for (const block of blocks) {
    if (FORBIDDEN_BLOCKS.has(block.blockId)) forbidden.add(block.blockId);
    for (const property of Object.keys(block.properties ?? {})) {
      if (FORBIDDEN_PROPERTIES.has(property)) forbidden.add(`${block.blockId}[${property}]`);
    }
  }
  if (forbidden.size === 0) return;
  throw new ControlError({
    code: "BUILD_CONTENT_FORBIDDEN",
    message: `建筑包含禁止导入的高权限方块或属性：${[...forbidden].sort().join(", ")}`,
    statusCode: 400,
  });
}
