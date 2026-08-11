import { mkdtemp, readFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { DeclarativeSkillStore } from "./declarative-skill-store.js";

describe("DeclarativeSkillStore", () => {
  it("resolves typed placeholders in built-in life skills", () => {
    const store = new DeclarativeSkillStore();
    const steps = store.resolve("life.mining-run", { itemId: "minecraft:coal", count: 12 });

    expect(steps).toHaveLength(1);
    expect(steps[0]?.task).toMatchObject({ kind: "gather", itemId: "minecraft:coal", count: 12 });
    expect(store.resolve("life.retrieve-and-deliver", {
      itemId: "#minecraft:logs",
      count: 16,
      player: "PlayerOne",
    }).map((step) => step.task)).toEqual([
      expect.objectContaining({ kind: "retrieve", itemId: "#minecraft:logs", count: 16 }),
      expect.objectContaining({ kind: "deliver", itemId: "#minecraft:logs", count: 16, player: "PlayerOne" }),
    ]);
    expect(store.resolve("life.expedition-and-deliver", {
      itemId: "minecraft:raw_iron",
      count: 12,
      player: "PlayerOne",
    }).map((step) => step.task)).toEqual([
      expect.objectContaining({ kind: "gather", itemId: "minecraft:raw_iron", count: 12 }),
      expect.objectContaining({ kind: "deliver", itemId: "minecraft:raw_iron", count: 12, player: "PlayerOne" }),
    ]);
    expect(store.list().some((skill) => skill.id === "dragon.daily-care" && skill.builtIn)).toBe(true);
  });

  it("persists learned routines without allowing executable or nested macro steps", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-declarative-skills-"));
    const store = new DeclarativeSkillStore(stateDirectory);
    const saved = store.save({
      id: "custom.return-home",
      name: "返回据点",
      description: "移动到预先配置的安全位置。",
      parameters: [
        { name: "x", description: "X", type: "number", required: true },
        { name: "y", description: "Y", type: "number", required: true },
        { name: "z", description: "Z", type: "number", required: true },
      ],
      steps: [{ label: "返回", task: { kind: "move", target: { x: "${x}", y: "${y}", z: "${z}" } } }],
    });
    expect(saved.builtIn).toBe(false);
    expect(saved.security.status).toBe("approved");
    expect(saved.security.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect(saved.manifest.permissions).toMatchObject({ tools: ["mc_assign_task"], network: "none" });

    const reloaded = new DeclarativeSkillStore(stateDirectory);
    expect(reloaded.resolve(saved.id, { x: 10, y: 70, z: -4 })[0]?.task).toMatchObject({
      kind: "move",
      target: { x: 10, y: 70, z: -4 },
    });
    expect(() => reloaded.save({
      id: "custom.bad",
      name: "递归技能",
      description: "不应保存。",
      parameters: [],
      steps: [{ label: "递归", task: { kind: "macro", skillId: saved.id, arguments: {} } }],
    })).toThrow(/non-macro|macro/i);
  });

  it("quarantines external skills until an explicit local review", () => {
    const store = new DeclarativeSkillStore();
    const external = store.save({
      id: "public.safe-gather",
      name: "Public gather",
      description: "A public typed routine",
      parameters: [{ name: "itemId", description: "Item", type: "string", required: true }],
      steps: [{ label: "Gather", task: { kind: "gather", itemId: "${itemId}", count: 1 } }],
      manifest: {
        version: "1.2.0",
        source: {
          kind: "external",
          author: "Example Author",
          license: "MIT",
          url: "https://example.test/skills/gather.json",
        },
        permissions: { tools: ["mc_assign_task"], network: "none", allowedHosts: [], fileAccess: "none", systemCommands: false },
      },
    });
    expect(external.security.status).toBe("pending");
    expect(() => store.resolve(external.id, { itemId: "minecraft:oak_log" })).toThrow(/pending|not approved/i);

    const approved = store.review(external.id, true);
    expect(approved.security.status).toBe("approved");
    expect(store.resolve(external.id, { itemId: "minecraft:oak_log" })[0]?.task).toMatchObject({ kind: "gather" });
  });

  it("blocks credentials and absolute local paths before persistence or execution", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-secure-skills-"));
    const store = new DeclarativeSkillStore(stateDirectory);
    const privatePath = ["C:", "Users", "Private", "secret.txt"].join("\\");
    const fakeToken = ["sk", "this-is-a-private-token-value"].join("-");
    expect(() => store.save({
      id: "custom.leak",
      name: "Leak",
      description: `Read ${privatePath}`,
      parameters: [],
      steps: [{ label: "Move", task: { kind: "move", target: { x: 0, y: 64, z: 0 } } }],
    })).toThrow(/sensitive data|absolute local path/i);

    const safe = store.save({
      id: "custom.args",
      name: "Arguments",
      description: "Gather an item",
      parameters: [{ name: "itemId", description: "Item", type: "string", required: true }],
      steps: [{ label: "Gather", task: { kind: "gather", itemId: "${itemId}", count: 1 } }],
    });
    expect(() => store.resolve(safe.id, { itemId: fakeToken })).toThrow(/sensitive data/i);
    const persisted = await readFile(path.join(stateDirectory, "declarative-skills.json"), "utf8");
    expect(persisted).not.toContain("Private\\secret");
    expect(persisted).not.toContain(fakeToken);
  });
});
