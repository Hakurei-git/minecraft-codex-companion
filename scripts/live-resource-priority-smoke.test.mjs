import assert from "node:assert/strict";
import test from "node:test";

import {
  deliveryTaskSpec,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  gatherTaskSpec,
  loopbackBase,
  parseCli,
  parseInspection,
  parseSetupStatus,
  runLiveResourcePrioritySmoke,
  validateCleanupStatus,
  validateDelivered,
  validateInitial,
  validateRetainedGather,
  validateWorkstation,
  workstationTaskSpec,
} from "./live-resource-priority-smoke.mjs";

const status = (values) => `rp:i=${values.join(",")}`;
const INITIAL = [1, 0, 0, 0, 0, 0, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0];
const WORKSTATION = [1, 0, 1, 0, 1, 0, 8, 8, 0, 0, 0, 0, 0, 0, 0, 0];
const RETAINED = [1, 0, 1, 0, 1, 0, 0, 4, 8, 4, 0, 12, 0, 0, 0, 0];
const DELIVERED = [1, 0, 1, 0, 1, 0, 0, 4, 8, 4, 0, 0, 12, 0, 1, 0];

test("resource priority smoke is fixed, dry-run by default, and loopback-only", async () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/health").href, "http://127.0.0.1:8765/");
  assert.equal(parseCli([]).apply, false);
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.8:8765"));
  assert.throws(() => parseCli(["--wait-seconds=10"]));
  const dry = await runLiveResourcePrioritySmoke(parseCli([]));
  assert.deepEqual(dry.fixture, {
    suite: "resource-priority",
    modes: ["setup", "inspect", "cleanup"],
  });
  assert.equal(dry.dryRun, true);
  assert.equal(dry.localOnly, true);
  assert.equal(dry.startsMinecraft, false);
  assert.equal(dry.externalApi, false);
});

test("typed acknowledgement cannot be confused with ordinary NPC status", () => {
  const current = {
    status: "following",
    liveFixtureAck: { sequence: 9, suite: "resource-priority", mode: "inspect", status: status(INITIAL) },
  };
  assert.equal(fixtureAcknowledgement(current, 8, "inspect"), status(INITIAL));
  assert.equal(fixtureAcknowledgement(current, 9, "inspect"), null);
  assert.equal(fixtureAcknowledgement(current, 8, "setup"), null);
  assert.equal(fixtureExpectedPrefix("setup"), "rp:setup=");
  assert.equal(fixtureExpectedPrefix("cleanup"), "rp:cleanup=");
  assert.throws(() => fixtureExpectedPrefix("command"));
});

test("task sequence is exact workstation, walk-only gather, then owner delivery", () => {
  assert.deepEqual(workstationTaskSpec(), {
    kind: "craft",
    itemId: "minecraft:diamond_pickaxe",
    count: 1,
    deliverTo: "owner",
    requestedBy: "live-resource-priority-smoke",
    note: "Use the fixed nearby existing crafting table; never make or place another",
  });
  assert.equal(gatherTaskSpec().movement, "walk");
  assert.equal(gatherTaskSpec().count, 12);
  assert.deepEqual(deliveryTaskSpec(), {
    kind: "deliver",
    itemId: "minecraft:coal",
    count: 12,
    player: "owner",
    requestedBy: "live-resource-priority-smoke",
    note: "Deliver exactly the twelve retained fixture coal items",
  });
});

test("setup and all three physical evidence checkpoints are strict", () => {
  assert.deepEqual(parseSetupStatus("rp:setup=-4,120,88"), {
    origin: { x: -4, y: 120, z: 88 },
  });
  assert.equal(validateInitial(parseInspection(status(INITIAL))).localRemaining, 8);
  assert.equal(validateWorkstation(parseInspection(status(WORKSTATION))).atExistingTable, 1);
  assert.equal(validateRetainedGather(parseInspection(status(RETAINED)), {
    completedCount: 12,
    targetCount: 12,
    retainedCount: 12,
  }).npcCoal, 12);
  assert.equal(validateDelivered(parseInspection(status(DELIVERED))).playerCoal, 12);

  const newTable = [...WORKSTATION];
  newTable[1] = 1;
  assert.throws(() => validateWorkstation(parseInspection(status(newTable))));
  const remoteEarly = [...RETAINED];
  remoteEarly[10] = 1;
  assert.throws(() => validateRetainedGather(parseInspection(status(remoteEarly)), {
    completedCount: 12, targetCount: 12, retainedCount: 12,
  }));
  assert.throws(() => validateRetainedGather(parseInspection(status(RETAINED)), {
    completedCount: 12, targetCount: 12, retainedCount: 11,
  }));
  const lost = [...DELIVERED];
  lost[12] = 11;
  assert.throws(() => validateDelivered(parseInspection(status(lost))));
});

test("cleanup accepts restoration only and surfaces conflict refusal", () => {
  assert.equal(validateCleanupStatus("rp:cleanup=restored"), "rp:cleanup=restored");
  assert.equal(validateCleanupStatus("rp:cleanup=none"), "rp:cleanup=none");
  assert.throws(() => validateCleanupStatus("rp:cleanup=conflict,1,0,0,0"));
  assert.throws(() => parseInspection(`${status(INITIAL)},0`));
});
