import assert from "node:assert/strict";
import test from "node:test";

import {
  CRAFT_SCENARIOS,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  loopbackBase,
  parseCli,
  parseInspection,
  parseSetupStatus,
  runLiveExplicitCraftingSmoke,
  selectNewCompanionTasks,
  validateCleanupStatus,
  validateCreatedTaskSet,
  validateDelivered,
  validateInitial,
} from "./live-explicit-crafting-smoke.mjs";

const FISHING = CRAFT_SCENARIOS[0];
const TORCHES = CRAFT_SCENARIOS[1];
const status = (values) => `rpc:i=${values.join(",")}`;

test("explicit crafting smoke is reversible, local-only, and dry-run by default", async () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/health").href, "http://127.0.0.1:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.1:8765"));
  assert.equal(parseCli([]).apply, false);
  assert.throws(() => parseCli(["--wait-seconds=10"]));
  const result = await runLiveExplicitCraftingSmoke(parseCli([]));
  assert.equal(result.dryRun, true);
  assert.equal(result.localOnly, true);
  assert.equal(result.reversible, true);
  assert.equal(result.startsMinecraft, false);
  assert.equal(result.externalApi, false);
  assert.equal(result.usedMinecraftTChat, true);
  assert.deepEqual(result.scenarios, CRAFT_SCENARIOS);
});

test("fixed fixture acknowledgements cannot be confused with ordinary status", () => {
  const current = {
    status: "following",
    liveFixtureAck: {
      sequence: 7,
      suite: "resource-priority",
      mode: "inspect-craft",
      status: status([1, 1, 0, 0, 0, 0, 0, 3, 2, 0, 0, 0]),
    },
  };
  assert.equal(fixtureAcknowledgement(current, 6, "inspect-craft"), current.liveFixtureAck.status);
  assert.equal(fixtureAcknowledgement(current, 7, "inspect-craft"), null);
  assert.equal(fixtureAcknowledgement(current, 6, "setup-fishing"), null);
  assert.equal(fixtureExpectedPrefix("setup-fishing"), "rpc:setup=fishing,");
  assert.equal(fixtureExpectedPrefix("setup-torches"), "rpc:setup=torches,");
  assert.throws(() => fixtureExpectedPrefix("command"));
});

test("setup and exact ingredient ledgers are strict", () => {
  assert.deepEqual(parseSetupStatus("rpc:setup=fishing,-4,120,88", FISHING), {
    scenario: "fishing",
    origin: { x: -4, y: 120, z: 88 },
  });
  assert.equal(validateInitial(
    parseInspection(status([1, 1, 0, 0, 0, 0, 0, 3, 2, 0, 0, 0])),
    FISHING,
  ).sticks, 3);
  assert.equal(validateInitial(
    parseInspection(status([2, 1, 0, 0, 0, 0, 0, 16, 16, 0, 0, 0])),
    TORCHES,
  ).secondary, 16);
  assert.throws(() => validateInitial(
    parseInspection(status([2, 1, 0, 0, 0, 0, 0, 16, 15, 0, 0, 0])),
    TORCHES,
  ));
});

test("T chat must create exactly one owner-delivery craft task", () => {
  const task = {
    id: "00000000-0000-0000-0000-000000000001",
    spec: {
      kind: "craft",
      itemId: "minecraft:torch",
      count: 64,
      deliverTo: "Owner",
      requestedBy: "Owner",
    },
  };
  assert.equal(validateCreatedTaskSet([task], TORCHES, "Owner"), task);
  assert.throws(() => validateCreatedTaskSet([], TORCHES, "Owner"));
  assert.throws(() => validateCreatedTaskSet([task, { ...task, id: "duplicate" }], TORCHES, "Owner"));
  assert.throws(() => validateCreatedTaskSet([{
    ...task,
    spec: { ...task.spec, deliverTo: "Other" },
  }], TORCHES, "Owner"));
});

test("physical delivery requires exact outputs, consumed inputs, and the original table", () => {
  const fishing = parseInspection(status([1, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0]));
  const torches = parseInspection(status([2, 1, 0, 1, 0, 64, 0, 0, 0, 1, 0, 0]));
  assert.equal(validateDelivered(fishing, FISHING, {
    status: "succeeded", completedCount: 1, targetCount: 1,
  }).playerOutput, 1);
  assert.equal(validateDelivered(torches, TORCHES, {
    status: "succeeded", completedCount: 64, targetCount: 64,
  }).playerOutput, 64);
  assert.throws(() => validateDelivered(
    parseInspection(status([2, 1, 1, 1, 0, 64, 0, 0, 0, 1, 0, 0])),
    TORCHES,
    { status: "succeeded", completedCount: 64, targetCount: 64 },
  ));
  assert.throws(() => validateDelivered(
    parseInspection(status([2, 1, 0, 1, 0, 63, 0, 0, 0, 1, 0, 0])),
    TORCHES,
    { status: "succeeded", completedCount: 64, targetCount: 64 },
  ));
});

test("task selection and cleanup remain bounded", () => {
  const prior = new Set(["old"]);
  assert.deepEqual(selectNewCompanionTasks({ tasks: [
    { id: "old", companionId: "npc" },
    { id: "new", companionId: "npc" },
    { id: "other", companionId: "other" },
  ] }, "npc", prior).map((entry) => entry.id), ["new"]);
  assert.equal(validateCleanupStatus("rp:cleanup=restored"), "rp:cleanup=restored");
  assert.throws(() => validateCleanupStatus("rp:cleanup=conflict,1,0,0,0"));
});
