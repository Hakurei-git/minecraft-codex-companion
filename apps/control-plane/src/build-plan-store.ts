import { createHash, randomUUID } from "node:crypto";
import {
  buildPlanDraftSchema,
  buildPlanSchema,
  type BuildPlan,
  type BuildPlanDraft,
  type Vec3,
} from "@mc/protocol";
import { assertBuildBlocksSafe } from "./build-safety.js";
import {
  BUILTIN_BUILD_TEMPLATES,
  BUILTIN_CONTENT_AUTHOR,
  BUILTIN_CONTENT_LICENSE,
  BUILTIN_CONTENT_VERSION,
} from "./builtin-content.js";
import { ControlError } from "./errors.js";

const BUILTIN_AT = "2026-07-30T00:00:00.000Z";
const DATA_ONLY_PERMISSIONS = {
  network: "none" as const,
  fileAccess: "none" as const,
  systemCommands: false as const,
  commandBlocks: false as const,
  blockEntityNbt: false as const,
};

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => [key, canonicalize(child)]));
  }
  return value;
}

export function buildContentHash(draft: BuildPlanDraft): string {
  return createHash("sha256").update(JSON.stringify(canonicalize({
    name: draft.name,
    source: draft.source,
    origin: draft.origin,
    blocks: draft.blocks,
  })), "utf8").digest("hex");
}

function measureBlocks(blocks: BuildPlanDraft["blocks"]): Vec3 {
  const xs = blocks.map((block) => block.position.x);
  const ys = blocks.map((block) => block.position.y);
  const zs = blocks.map((block) => block.position.z);
  return {
    x: Math.max(...xs) - Math.min(...xs) + 1,
    y: Math.max(...ys) - Math.min(...ys) + 1,
    z: Math.max(...zs) - Math.min(...zs) + 1,
  };
}

function countRequiredItems(blocks: BuildPlanDraft["blocks"]): Record<string, number> {
  const requiredItems: Record<string, number> = {};
  for (const block of blocks) {
    if (block.blockId === "minecraft:air") continue;
    const itemId = block.blockId === "minecraft:water"
      ? "minecraft:water_bucket"
      : block.blockId === "minecraft:lava"
        ? "minecraft:lava_bucket"
        : block.blockId;
    requiredItems[itemId] = (requiredItems[itemId] ?? 0) + 1;
  }
  return requiredItems;
}

export class BuildPlanStore {
  readonly #plans = new Map<string, BuildPlan>();

  constructor() {
    for (const template of BUILTIN_BUILD_TEMPLATES) {
      const draft = buildPlanDraftSchema.parse(template.draft);
      assertBuildBlocksSafe(draft.blocks);
      const plan = buildPlanSchema.parse({
        id: template.id,
        name: draft.name,
        source: draft.source,
        origin: draft.origin,
        size: measureBlocks(draft.blocks),
        blocks: draft.blocks,
        requiredItems: countRequiredItems(draft.blocks),
        confirmed: true,
        builtIn: true,
        manifest: {
          version: BUILTIN_CONTENT_VERSION,
          source: { kind: "built-in", author: BUILTIN_CONTENT_AUTHOR, license: BUILTIN_CONTENT_LICENSE },
          permissions: DATA_ONLY_PERMISSIONS,
          sha256: buildContentHash(draft),
        },
        createdAt: BUILTIN_AT,
      });
      this.#plans.set(plan.id, plan);
    }
  }

  preview(input: BuildPlanDraft): BuildPlan {
    const draft = buildPlanDraftSchema.parse(input);
    assertBuildBlocksSafe(draft.blocks);
    const plan = buildPlanSchema.parse({
      id: randomUUID(),
      name: draft.name,
      source: draft.source,
      origin: draft.origin,
      size: measureBlocks(draft.blocks),
      blocks: draft.blocks,
      requiredItems: countRequiredItems(draft.blocks),
      confirmed: false,
      builtIn: false,
      manifest: {
        version: "1.0.0",
        source: { kind: "learned", author: "Local user", license: "LicenseRef-Private-Use" },
        permissions: DATA_ONLY_PERMISSIONS,
        sha256: buildContentHash(draft),
      },
      createdAt: new Date().toISOString(),
    });
    this.#plans.set(plan.id, plan);
    return buildPlanSchema.parse(plan);
  }

  list(): BuildPlan[] {
    return [...this.#plans.values()]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((plan) => buildPlanSchema.parse(plan));
  }

  get(id: string): BuildPlan {
    const plan = this.#plans.get(id);
    if (!plan) {
      throw new ControlError({
        code: "BUILD_PLAN_NOT_FOUND",
        message: `找不到建筑计划 ${id}`,
        statusCode: 404,
      });
    }
    return buildPlanSchema.parse(plan);
  }

  confirm(id: string): BuildPlan {
    const plan = this.#plans.get(id);
    if (!plan) return this.get(id);
    plan.confirmed = true;
    return buildPlanSchema.parse(plan);
  }
}
