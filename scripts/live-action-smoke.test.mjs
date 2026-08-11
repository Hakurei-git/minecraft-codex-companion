import assert from "node:assert/strict";
import test from "node:test";
import {
  buildMaterialChainDraft,
  buildMaterialTaskSpec,
  localBaseUrl,
  taskSpecFor,
} from "./live-action-smoke.mjs";

const companion = {
  id: "codex-forge",
  ownerName: "PlayerOne",
  snapshot: { position: { x: 10.5, y: 70, z: -4.25 }, nearbyEntities: [] },
};

test("live action smoke only accepts loopback control URLs", () => {
  assert.equal(localBaseUrl("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(localBaseUrl("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => localBaseUrl("https://example.com"));
  assert.throws(() => localBaseUrl("http://192.168.1.10:8765"));
});

test("stone pickaxe smoke crafts and delivers to the owner name without hardcoding it", () => {
  assert.deepEqual(taskSpecFor("stone-pickaxe", companion), {
    kind: "craft",
    itemId: "minecraft:stone_pickaxe",
    count: 1,
    deliverTo: "PlayerOne",
    requestedBy: "live-action-smoke",
    note: "实机验证：制作石镐动作链并交付主人",
  });
});

test("walk-log smoke forces local walking mode", () => {
  assert.equal(taskSpecFor("walk-log", companion).movement, "walk");
});

test("smelt iron smoke exercises the recoverable prerequisite chain", () => {
  assert.deepEqual(taskSpecFor("smelt-iron", companion), {
    kind: "smelt",
    itemId: "minecraft:raw_iron",
    count: 3,
    requestedBy: "live-action-smoke",
    note: "实机验证：从自然资源补齐工具、原矿、熔炉与燃料后烧炼 3 个铁锭",
  });
});

test("log delivery smoke uses the audited built-in gather-and-deliver skill", () => {
  const spec = taskSpecFor("log-delivery", companion);
  assert.equal(spec.kind, "macro");
  assert.equal(spec.skillId, "life.gather-and-deliver");
  assert.deepEqual(spec.arguments, { itemId: "#minecraft:logs", count: 1, player: "PlayerOne" });
});

test("expedition smoke uses the persistent remote gather workflow", () => {
  const spec = taskSpecFor("expedition-log", companion);
  assert.equal(spec.kind, "macro");
  assert.equal(spec.skillId, "life.expedition-and-deliver");
  assert.deepEqual(spec.arguments, { itemId: "#minecraft:logs", count: 1, player: "PlayerOne" });
});

test("build material smoke uses a fresh confirmed plan anchored to the live NPC", () => {
  const draft = buildMaterialChainDraft();
  assert.deepEqual(draft.blocks.map((block) => block.blockId), [
    "minecraft:cobblestone",
    "minecraft:oak_planks",
    "minecraft:glass",
    "minecraft:torch",
    "minecraft:glass_pane",
  ]);
  assert.deepEqual(buildMaterialTaskSpec("11111111-1111-1111-1111-111111111111", companion), {
    kind: "build",
    planId: "11111111-1111-1111-1111-111111111111",
    placement: "companion",
    offset: { x: 3, y: 0, z: 3 },
    placementAnchor: { x: 10.5, y: 70, z: -4.25 },
    requestedBy: "live-action-smoke",
    note: "实机验证：仓库、制作、熔炼和安全采集补料后恢复同一建筑任务",
  });
});
