import { describe, expect, it } from "vitest";
import { taskSpecSchema } from "@mc/protocol";
import { positionBuildPlanForTask } from "./bridge-backend.js";
import { BuildPlanStore, buildContentHash } from "./build-plan-store.js";
import { assertBuildBlocksSafe } from "./build-safety.js";
import { BUILTIN_BUILD_IDS } from "./builtin-content.js";
import { DeclarativeSkillStore } from "./declarative-skill-store.js";

const FORBIDDEN_IDS = new Set([
  "minecraft:command_block",
  "minecraft:chain_command_block",
  "minecraft:repeating_command_block",
  "minecraft:structure_block",
  "minecraft:jigsaw",
  "minecraft:spawner",
]);

describe("offline built-in content catalog", () => {
  it("registers trusted, confirmed and hash-verifiable data-only build templates", () => {
    const store = new BuildPlanStore();
    const plans = store.list().filter((plan) => plan.builtIn);
    expect(new Set(plans.map((plan) => plan.id))).toEqual(new Set(Object.values(BUILTIN_BUILD_IDS)));
    expect(plans.map((plan) => plan.name)).toEqual(expect.arrayContaining([
      expect.stringContaining("刷石机"),
      expect.stringContaining("住宅"),
      expect.stringContaining("农田"),
      expect.stringContaining("仓库"),
      expect.stringContaining("石砖小屋"),
      expect.stringContaining("动物围栏"),
      expect.stringContaining("瞭望塔"),
      expect.stringContaining("黑暗刷怪塔"),
      expect.stringContaining("树场"),
    ]));
    for (const plan of plans) {
      expect(plan.confirmed).toBe(true);
      expect(plan.manifest).toMatchObject({
        version: "1.0.0",
        source: { kind: "built-in", license: "CC0-1.0" },
        permissions: {
          network: "none",
          fileAccess: "none",
          systemCommands: false,
          commandBlocks: false,
          blockEntityNbt: false,
        },
      });
      expect(plan.manifest.sha256).toBe(buildContentHash({
        name: plan.name,
        source: plan.source,
        origin: plan.origin,
        blocks: plan.blocks,
      }));
      expect(plan.blocks.some((entry) => FORBIDDEN_IDS.has(entry.blockId))).toBe(false);
      expect(new Set(plan.blocks.map((entry) => (
        `${entry.position.x},${entry.position.y},${entry.position.z}`
      ))).size).toBe(plan.blocks.length);
      expect(() => assertBuildBlocksSafe(plan.blocks)).not.toThrow();
    }
  });

  it("also applies the build safety gate to direct API previews", () => {
    const store = new BuildPlanStore();
    expect(() => store.preview({
      name: "unsafe",
      source: "json",
      origin: { x: 0, y: 64, z: 0 },
      blocks: [{ position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:command_block", properties: {} }],
    })).toThrow(/禁止|forbidden|command_block/i);
  });

  it("keeps survival preparation budgets aligned with built-in material lists", () => {
    const store = new BuildPlanStore();
    const generator = store.get(BUILTIN_BUILD_IDS.cobblestoneGenerator);
    expect(generator.requiredItems).toMatchObject({
      "minecraft:cobblestone": 57,
      "minecraft:water_bucket": 1,
      "minecraft:lava_bucket": 1,
    });
    expect(generator.requiredItems).not.toHaveProperty("minecraft:obsidian");
    expect(generator.requiredItems).not.toHaveProperty("minecraft:water");
    expect(generator.requiredItems).not.toHaveProperty("minecraft:lava");
    expect(generator.blocks).toEqual(expect.arrayContaining([
      expect.objectContaining({ position: { x: 1, y: 2, z: 1 }, blockId: "minecraft:water", properties: { level: "0" } }),
      expect.objectContaining({ position: { x: 4, y: 2, z: 1 }, blockId: "minecraft:lava", properties: { level: "0" } }),
    ]));
    expect(generator.blocks).not.toEqual(expect.arrayContaining([
      expect.objectContaining({ position: { x: 2, y: 1, z: 1 } }),
    ]));
    expect(store.get(BUILTIN_BUILD_IDS.basicShelter).requiredItems).toEqual({
      "minecraft:oak_planks": 116,
      "minecraft:glass": 3,
      "minecraft:oak_slab": 49,
    });
    expect(store.get(BUILTIN_BUILD_IDS.cropFarm).requiredItems).toEqual({
      "minecraft:oak_planks": 32,
      "minecraft:dirt": 42,
      "minecraft:oak_slab": 7,
    });
    expect(store.get(BUILTIN_BUILD_IDS.storageRoom).requiredItems).toEqual({
      "minecraft:cobblestone": 35,
      "minecraft:oak_planks": 58,
      "minecraft:oak_slab": 35,
      "minecraft:barrel": 6,
    });

    const shelterSteps = new DeclarativeSkillStore().resolve("build.basic-shelter", {});
    expect(shelterSteps.map((step) => step.task)).toEqual([
      expect.objectContaining({ kind: "build", planId: BUILTIN_BUILD_IDS.basicShelter }),
    ]);

    const storageSteps = new DeclarativeSkillStore().resolve("build.storage-room", {});
    expect(storageSteps.map((step) => step.task)).toEqual([
      expect.objectContaining({ kind: "build", planId: BUILTIN_BUILD_IDS.storageRoom }),
      expect.objectContaining({ kind: "organize-storage" }),
    ]);
  });

  it("places reusable templates beside the current companion without mutating the catalog", () => {
    const plan = new BuildPlanStore().get(BUILTIN_BUILD_IDS.basicShelter);
    const spec = taskSpecSchema.parse({
      kind: "build",
      planId: plan.id,
      placement: "companion",
      offset: { x: 3, y: 0, z: 3 },
      placementAnchor: { x: 40.9, y: 70.2, z: 9.7 },
    });
    if (spec.kind !== "build") throw new Error("expected build task");
    const positioned = positionBuildPlanForTask(plan, spec, {
      sequence: 1,
      capturedAt: "2026-08-02T00:00:00.000Z",
      worldId: "test",
      dimension: "minecraft:overworld",
      position: { x: 10.8, y: 64.9, z: -4.2 },
      yaw: 0,
      pitch: 0,
      health: 20,
      maxHealth: 20,
      food: 20,
      air: 300,
      gameMode: "survival",
      timeOfDay: 0,
      weather: "clear",
      inventory: [],
      nearbyEntities: [],
      status: "idle",
    });
    expect(positioned.origin).toEqual({ x: 43, y: 70, z: 12 });
    expect(plan.origin).toEqual({ x: 0, y: 0, z: 0 });
    expect(positioned.manifest.sha256).toBe(buildContentHash({
      name: positioned.name,
      source: positioned.source,
      origin: positioned.origin,
      blocks: positioned.blocks,
    }));
    expect(positioned.manifest.sha256).not.toBe(plan.manifest.sha256);
  });

  it("keeps explicit plan origins unchanged", () => {
    const plan = new BuildPlanStore().get(BUILTIN_BUILD_IDS.basicShelter);
    const spec = taskSpecSchema.parse({ kind: "build", planId: plan.id, placement: "plan-origin" });
    if (spec.kind !== "build") throw new Error("expected build task");
    const positioned = positionBuildPlanForTask(plan, spec, {
      sequence: 1,
      capturedAt: "2026-08-02T00:00:00.000Z",
      worldId: "test",
      dimension: "minecraft:overworld",
      position: { x: 100, y: 80, z: 100 },
      yaw: 0,
      pitch: 0,
      health: 20,
      maxHealth: 20,
      food: 20,
      air: 300,
      gameMode: "survival",
      timeOfDay: 0,
      weather: "clear",
      inventory: [],
      nearbyEntities: [],
      status: "idle",
    });
    expect(positioned).toBe(plan);
    expect(positioned.origin).toEqual({ x: 0, y: 0, z: 0 });
  });

  it("publishes offline-only building, crafting, equipment and both-dragon-mod skills", () => {
    const skills = new DeclarativeSkillStore().list();
    const required = [
      "life.expedition-and-deliver",
      "life.craft-and-place-bed",
      "build.cobblestone-generator",
      "build.basic-shelter",
      "build.crop-farm",
      "build.storage-room",
      "build.stone-cottage",
      "build.animal-pen",
      "life.establish-ranch",
      "build.watchtower",
      "build.mob-farm",
      "build.tree-farm",
      "craft.starter-tools",
      "craft.iron-equipment",
      "craft.building-materials",
      "dragon.bookofdragons-field-kit",
      "dragon.egg-care",
      "dragon.heal-and-follow",
      "dragon.shared-ride",
      "dragon.saintsdragons-care-kit",
      "dragon.saintsdragons-binder",
    ];
    for (const id of required) {
      const skill = skills.find((candidate) => candidate.id === id);
      expect(skill, id).toBeDefined();
      expect(skill).toMatchObject({
        builtIn: true,
        manifest: {
          source: { kind: "built-in", license: "CC0-1.0" },
          permissions: {
            tools: ["mc_assign_task"],
            network: "none",
            allowedHosts: [],
            fileAccess: "none",
            systemCommands: false,
          },
        },
        security: { status: "trusted", findings: [] },
      });
      expect(skill?.security.sha256).toMatch(/^[0-9a-f]{64}$/);
    }
    expect(skills.find((skill) => skill.id === "dragon.bookofdragons-field-kit")?.steps)
      .toEqual(expect.arrayContaining([expect.objectContaining({ task: expect.objectContaining({ itemId: "bookofdragons:dragon_whistle" }) })]));
    expect(skills.find((skill) => skill.id === "dragon.saintsdragons-care-kit")?.steps)
      .toEqual(expect.arrayContaining([expect.objectContaining({ task: expect.objectContaining({ itemId: "saintsdragons:hearty_dragon_meal" }) })]));
    expect(skills.find((skill) => skill.id === "dragon.shared-ride")?.steps)
      .toEqual(expect.arrayContaining([expect.objectContaining({ task: expect.objectContaining({ kind: "dragon", action: "share-ride" }) })]));
    expect(skills.find((skill) => skill.id === "build.basic-shelter")?.steps)
      .toEqual(expect.arrayContaining([
        expect.objectContaining({ task: expect.objectContaining({ kind: "build" }) }),
      ]));
  });

  it("assigns every automatic built-in build to its bounded home-compound ring", () => {
    const skills = new DeclarativeSkillStore().list();
    const expectedZones = new Map<string, "residential" | "production" | "industrial">([
      ["build.basic-shelter", "residential"],
      ["build.storage-room", "residential"],
      ["build.stone-cottage", "residential"],
      ["build.crop-farm", "production"],
      ["build.animal-pen", "production"],
      ["life.establish-ranch", "production"],
      ["build.watchtower", "production"],
      ["build.tree-farm", "production"],
      ["build.cobblestone-generator", "industrial"],
      ["build.mob-farm", "industrial"],
    ]);
    const expectedRanges = {
      residential: [8, 24],
      production: [16, 40],
      industrial: [40, 64],
    } as const;

    for (const [skillId, zone] of expectedZones) {
      const buildSteps = skills.find((skill) => skill.id === skillId)?.steps
        .filter((step) => step.task.kind === "build") ?? [];
      expect(buildSteps.length, skillId).toBeGreaterThan(0);
      for (const step of buildSteps) {
        expect(step.task, `${skillId} must not shift the checked compound origin`).not.toHaveProperty("offset");
        expect(taskSpecSchema.parse(step.task), skillId).toMatchObject({
          kind: "build",
          sitePolicy: "home-compound",
          compoundPlacement: {
            zone,
            minDistance: expectedRanges[zone][0],
            maxDistance: expectedRanges[zone][1],
            facilityClearance: 12,
            terrainPreparation: "light",
          },
        });
      }
    }
  });
});
