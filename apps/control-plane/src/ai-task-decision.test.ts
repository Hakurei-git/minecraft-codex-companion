import { aiTaskDecisionSchema } from "@mc/protocol";
import { describe, expect, it } from "vitest";
import { normalizeAiTaskDecisionInput } from "./ai-task-decision.js";

describe("normalizeAiTaskDecisionInput", () => {
  it("accepts provider skill parameters as the documented arguments alias", () => {
    const normalized = aiTaskDecisionSchema.parse(normalizeAiTaskDecisionInput({
      type: "skill",
      skillId: "life.gather-and-deliver",
      parameters: { itemId: "minecraft:oak_log", count: 4 },
      reply: "开始",
    }));

    expect(normalized).toMatchObject({
      type: "skill",
      arguments: { itemId: "minecraft:oak_log", count: 4 },
    });
  });

  it("accepts provider task parameters when spec is omitted", () => {
    const normalized = aiTaskDecisionSchema.parse(normalizeAiTaskDecisionInput({
      type: "task",
      parameters: {
        kind: "move",
        target: { x: 1, y: 64, z: 2 },
        requestedBy: "PlayerOne",
      },
      reply: "出发",
    }));

    expect(normalized).toMatchObject({
      type: "task",
      spec: { kind: "move", target: { x: 1, y: 64, z: 2 } },
    });
  });

  it("keeps the canonical task spec and removes redundant provider aliases", () => {
    const normalized = aiTaskDecisionSchema.parse(normalizeAiTaskDecisionInput({
      type: "task",
      spec: { kind: "move", target: { x: 1, y: 64, z: 2 } },
      task: { kind: "explore", radius: 32 },
      reply: "出发",
    }));

    expect(normalized).toMatchObject({
      type: "task",
      spec: { kind: "move", target: { x: 1, y: 64, z: 2 } },
    });
  });

  it("turns an exact no-teleport walk into a bounded absolute move", () => {
    const normalized = aiTaskDecisionSchema.parse(normalizeAiTaskDecisionInput({
      type: "task",
      spec: { kind: "explore", radius: 8, requestedBy: "PlayerOne" },
      reply: "走过去",
    }, {
      message: "向前走5格，不要传送",
      requester: "PlayerOne",
      snapshot: { position: { x: 10, y: 70, z: -3 }, yaw: 90 },
    }));

    expect(normalized).toMatchObject({
      type: "task",
      spec: {
        kind: "move",
        target: { x: 5, y: 70, z: -3 },
        arrivalTolerance: 0.5,
        arrivalMetric: "horizontal",
        requestedBy: "PlayerOne",
      },
    });
  });

  it("does not rewrite an ordinary exploration request", () => {
    const input = {
      type: "task",
      spec: { kind: "explore", radius: 8, requestedBy: "PlayerOne" },
      reply: "探索一下",
    } as const;

    expect(normalizeAiTaskDecisionInput(input, {
      message: "探索附近",
      requester: "PlayerOne",
      snapshot: { position: { x: 0, y: 64, z: 0 }, yaw: 0 },
    })).toEqual(input);
  });
});
