import { describe, expect, it } from "vitest";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { ControlService } from "./control-service.js";
import { SimulatorBackend } from "./simulator-backend.js";

describe("Agent goal journal integration", () => {
  it("persists high-level goals, generated work graphs, and reusable facilities across service restarts", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-agent-goal-"));
    try {
      const first = new ControlService({ stateDirectory });
      first.registerBackend(new SimulatorBackend());
      const goal = first.submitGoal("codex-sim", {
        title: "Craft a diamond pickaxe",
        objective: "Prepare prerequisites, mine diamonds if needed, craft the pickaxe, and deliver it.",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [{
          kind: "craft",
          itemId: "minecraft:diamond_pickaxe",
          count: 1,
          deliverTo: "PlayerOne",
          requestedBy: "PlayerOne",
        }],
        metadata: {},
      }, "codex-driver");
      const plan = first.getPlan(goal.id);
      const facility = first.registerFacility({
        worldId: goal.worldId,
        dimension: "minecraft:overworld",
        type: "workstation",
        name: "Base crafting table",
        position: { x: 4, y: 64, z: 5 },
        tags: ["crafting_table", "base"],
        properties: {},
      });

      expect(goal).toMatchObject({ status: "planning", companionId: "codex-sim" });
      expect(plan.nodes).toHaveLength(2);
      expect(plan.nodes[0]).toMatchObject({
        id: "knowledge_lookup",
        action: { kind: "query-knowledge", query: expect.stringContaining("diamond") },
      });
      expect(plan.nodes[1]).toMatchObject({
        id: "task_1",
        dependsOn: ["knowledge_lookup"],
        action: { kind: "task" },
      });
      expect(facility).toMatchObject({ type: "workstation", worldId: goal.worldId });

      const second = new ControlService({ stateDirectory });
      expect(second.getGoal(goal.id)).toMatchObject({ spec: { title: "Craft a diamond pickaxe" } });
      expect(second.getPlan(goal.id).nodes.map((node) => node.id)).toEqual(["knowledge_lookup", "task_1"]);
      expect(second.listFacilities(goal.worldId).map((entry) => entry.name)).toEqual(["Base crafting table"]);
      expect(second.queryKnowledge("diamond pickaxe", ["crafting"])[0]).toMatchObject({
        id: "minecraft:crafting.diamond_pickaxe",
        outputs: ["minecraft:diamond_pickaxe"],
      });

      const paused = second.pauseGoal(goal.id, "Need player confirmation");
      expect(paused.status).toBe("paused");
      expect(second.resumeGoal(goal.id).status).toBe("planning");
      expect(second.cancelGoal(goal.id, "Test cleanup").status).toBe("cancelled");
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  });

  it("cancels non-terminal goals and work graphs during a global emergency stop", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-agent-stop-"));
    try {
      const service = new ControlService({ stateDirectory });
      service.registerBackend(new SimulatorBackend());
      const goal = service.submitGoal("codex-sim", {
        title: "Prepare a diamond pickaxe",
        objective: "给我做一把钻石镐",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      }, "codex-driver");

      await service.emergencyStop(false);

      expect(service.getGoal(goal.id)).toMatchObject({
        status: "cancelled",
        message: "紧急停止",
        activeWorkNodeId: null,
      });
      expect(service.getPlan(goal.id)).toMatchObject({ status: "cancelled" });
      expect(service.getPlan(goal.id).nodes.every((node) => (
        node.status === "succeeded" || node.status === "failed" || node.status === "skipped"
      ))).toBe(true);
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  });
});
