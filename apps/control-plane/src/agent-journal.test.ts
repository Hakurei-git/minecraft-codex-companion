import { describe, expect, it } from "vitest";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { AGENT_PROTOCOL_VERSION } from "@mc/protocol";
import { AgentJournal, emptyAgentJournalState, type AgentJournalState } from "./agent-journal.js";

function sampleState(): AgentJournalState {
  return {
    version: AGENT_PROTOCOL_VERSION,
    goals: [{
      id: "00000000-0000-4000-8000-000000000301",
      worldId: "world-one",
      companionId: "codex-sim",
      version: AGENT_PROTOCOL_VERSION,
      spec: {
        title: "Craft a diamond pickaxe",
        objective: "Prepare prerequisites, mine diamonds, craft the pickaxe, and deliver it.",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: ["Use local world evidence before claiming success"],
        taskHints: [{
          kind: "craft",
          itemId: "minecraft:diamond_pickaxe",
          count: 1,
          deliverTo: "PlayerOne",
          requestedBy: "PlayerOne",
        }],
        metadata: {},
      },
      status: "planning",
      activeWorkNodeId: null,
      progress: 0,
      message: "Planning prerequisites",
      createdAt: "2026-08-16T00:00:00.000Z",
      plannedAt: null,
      startedAt: null,
      finishedAt: null,
      error: null,
    }],
    workGraphs: [{
      id: "00000000-0000-4000-8000-000000000302",
      goalId: "00000000-0000-4000-8000-000000000301",
      version: AGENT_PROTOCOL_VERSION,
      status: "ready",
      nodes: [{
        id: "observe",
        label: "Observe inventory and facilities",
        action: { kind: "query-knowledge", query: "diamond pickaxe prerequisites", topics: ["crafting", "mining"] },
        dependsOn: [],
        status: "pending",
        attempts: 0,
        progress: 0,
        checkpoint: {},
      }, {
        id: "craft_pickaxe",
        label: "Craft and deliver the pickaxe",
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:diamond_pickaxe",
            count: 1,
            deliverTo: "PlayerOne",
            requestedBy: "PlayerOne",
          },
        },
        dependsOn: ["observe"],
        status: "pending",
        attempts: 0,
        progress: 0,
        checkpoint: {},
      }],
      edges: [{ from: "observe", to: "craft_pickaxe" }],
      createdAt: "2026-08-16T00:00:01.000Z",
      updatedAt: "2026-08-16T00:00:01.000Z",
    }],
    facilities: [{
      id: "00000000-0000-4000-8000-000000000303",
      worldId: "world-one",
      dimension: "minecraft:overworld",
      type: "workstation",
      name: "Base crafting table",
      position: { x: 4, y: 64, z: 5 },
      tags: ["crafting_table", "base"],
      properties: {},
      createdAt: "2026-08-16T00:00:00.000Z",
      updatedAt: "2026-08-16T00:00:00.000Z",
      lastUsedAt: null,
    }],
    reservations: [{
      id: "00000000-0000-4000-8000-000000000304",
      goalId: "00000000-0000-4000-8000-000000000301",
      nodeId: "craft_pickaxe",
      itemId: "minecraft:diamond",
      count: 3,
      source: "home-storage",
      status: "planned",
      createdAt: "2026-08-16T00:00:00.000Z",
      updatedAt: "2026-08-16T00:00:00.000Z",
    }],
    knowledge: [{
      id: "minecraft:crafting.diamond_pickaxe",
      version: "1.0.0",
      gameVersion: "1.20.1",
      source: "vanilla-registry",
      topic: "crafting",
      inputs: ["minecraft:diamond", "minecraft:stick"],
      outputs: ["minecraft:diamond_pickaxe"],
      tags: ["tool", "pickaxe"],
      summary: "A diamond pickaxe requires diamonds and sticks at a crafting table.",
      facts: { workstation: "minecraft:crafting_table" },
      confidence: "authoritative",
      updatedAt: "2026-08-16T00:00:00.000Z",
    }],
    evidence: [{
      id: "00000000-0000-4000-8000-000000000305",
      nodeId: "observe",
      kind: "manual",
      at: "2026-08-16T00:00:02.000Z",
      summary: "Fixture evidence with sensitive-looking values should be redacted before persistence.",
      data: {
        apiKey: "fixture-secret-value",
        localPath: "Z:\\redaction-fixture\\secret.txt",
        upstream: "https://redaction.invalid/v1",
      },
      verified: false,
    }],
  };
}

describe("AgentJournal", () => {
  it("persists Agent v2 state locally while redacting secrets, URLs, and absolute paths", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-agent-journal-"));
    try {
      const journal = new AgentJournal(stateDirectory);
      journal.save(sampleState());

      const persisted = await readFile(path.join(stateDirectory, "agent-journal.json"), "utf8");
      expect(persisted).not.toContain("fixture-secret-value");
      expect(persisted).not.toContain("redaction.invalid");
      expect(persisted).not.toContain("Z:\\redaction-fixture");
      expect(persisted).toContain("[REDACTED]");
      expect(persisted).toContain("[LOCAL_PATH]");

      const loaded = new AgentJournal(stateDirectory).load();
      expect(loaded.goals[0]).toMatchObject({ status: "planning", companionId: "codex-sim" });
      expect(loaded.workGraphs[0]?.nodes.map((node) => node.id)).toEqual(["observe", "craft_pickaxe"]);
      expect(loaded.evidence[0]?.data).toMatchObject({
        apiKey: "[REDACTED]",
        localPath: "[LOCAL_PATH]",
        upstream: "[REDACTED_URL]",
      });
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  });

  it("is a no-op without a state directory and ignores unreadable journal payloads", async () => {
    const withoutDirectory = new AgentJournal();
    withoutDirectory.save(sampleState());
    expect(withoutDirectory.load()).toEqual(emptyAgentJournalState());

    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-agent-journal-bad-"));
    try {
      await writeFile(path.join(stateDirectory, "agent-journal.json"), "{ not json", "utf8");
      expect(new AgentJournal(stateDirectory).load()).toEqual(emptyAgentJournalState());
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  });
});
