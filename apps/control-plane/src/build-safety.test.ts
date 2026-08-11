import { describe, expect, it } from "vitest";
import { buildBlockSchema, taskSpecSchema } from "@mc/protocol";
import { assertBuildBlocksSafe } from "./build-safety.js";

describe("build import safety", () => {
  it("allows ordinary survival blocks", () => {
    expect(() => assertBuildBlocksSafe([
      { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:oak_planks", properties: {} },
      { position: { x: 1, y: 0, z: 0 }, blockId: "minecraft:chest", properties: { facing: "north" } },
    ])).not.toThrow();
  });

  it.each([
    "minecraft:command_block",
    "minecraft:chain_command_block",
    "minecraft:repeating_command_block",
    "minecraft:structure_block",
    "minecraft:jigsaw",
    "minecraft:spawner",
  ])("rejects privileged block %s", (blockId) => {
    expect(() => assertBuildBlocksSafe([
      { position: { x: 0, y: 0, z: 0 }, blockId, properties: {} },
    ])).toThrow(/禁止导入/u);
  });

  it("rejects command-like properties even on an unknown namespace", () => {
    expect(() => assertBuildBlocksSafe([
      { position: { x: 0, y: 0, z: 0 }, blockId: "example:programmable_block", properties: { Command: "say secret" } },
    ])).toThrow(/Command/u);
  });

  it("bounds resource identifiers and block-state properties before execution", () => {
    expect(buildBlockSchema.safeParse({
      position: { x: 0, y: 0, z: 0 },
      blockId: `minecraft:${"x".repeat(300)}`,
      properties: {},
    }).success).toBe(false);
    expect(buildBlockSchema.safeParse({
      position: { x: 0, y: 0, z: 0 },
      blockId: "minecraft:stone",
      properties: Object.fromEntries(Array.from({ length: 33 }, (_, index) => [`p${index}`, "v"])),
    }).success).toBe(false);
    expect(taskSpecSchema.safeParse({
      kind: "gather",
      itemId: "not a resource id",
      count: 1,
      requestedBy: "test",
    }).success).toBe(false);
  });
});
