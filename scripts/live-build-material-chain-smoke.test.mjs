import assert from "node:assert/strict";
import test from "node:test";

import {
  buildPlanDraft,
  buildTaskSpec,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  loopbackBase,
  parseCli,
  parseInspection,
  parseSetupStatus,
  runLiveBuildMaterialChainSmoke,
  taskEvidenceToken,
  taskNeedsFixtureCancellation,
  validateCleanupStatus,
  validateFinal,
  validateInitial,
} from "./live-build-material-chain-smoke.mjs";

const KEYS = [
  "expected", "matching", "wrong",
  "logsRemaining", "stoneRemaining", "sandRemaining", "coalRemaining",
  "logBreaks", "stoneBreaks", "sandBreaks", "coalBreaks",
  "tablePlacements", "furnacePlacements", "litFurnace",
  "sawLogs", "sawPlanks", "sawCobble", "sawSand", "sawGlass", "sawCoal",
  "sawStick", "sawTorch", "sawPane", "sawWoodenPickaxe", "sawActiveBuild",
  "taskIdChanges", "maxDistanceMilli", "finalDistanceMilli",
  "unexpectedBreaks", "unknownWorldEdits", "remoteBreaks", "losViolations",
  "syncViolations", "maxTouchMilli", "sandInFurnace", "furnaceFuel",
  "sawTable", "sawFurnace",
];
const TASK_ID = "11111111-1111-4111-8111-111111111111";

function status(values, taskId = "none") {
  return `bmc:i=${KEYS.map((key) => values[key] ?? 0).join(",")},task=${taskId}`;
}

const INITIAL = {
  expected: 5,
  matching: 0,
  wrong: 5,
  logsRemaining: 15,
  stoneRemaining: 16,
  sandRemaining: 16,
  coalRemaining: 16,
  maxDistanceMilli: 8_500,
  finalDistanceMilli: 8_500,
};

const FINAL = {
  expected: 5,
  matching: 5,
  wrong: 0,
  logsRemaining: 10,
  stoneRemaining: 7,
  sandRemaining: 9,
  coalRemaining: 15,
  logBreaks: 5,
  stoneBreaks: 9,
  sandBreaks: 7,
  coalBreaks: 1,
  tablePlacements: 1,
  furnacePlacements: 1,
  litFurnace: 1,
  sawLogs: 1,
  sawPlanks: 1,
  sawCobble: 1,
  sawSand: 1,
  sawGlass: 1,
  sawCoal: 1,
  sawStick: 1,
  sawTorch: 1,
  sawPane: 1,
  sawWoodenPickaxe: 1,
  sawActiveBuild: 1,
  maxDistanceMilli: 13_000,
  finalDistanceMilli: 3_000,
  maxTouchMilli: 4_400,
  sandInFurnace: 1,
  furnaceFuel: 1,
  sawTable: 1,
  sawFurnace: 1,
};

test("build material smoke is local-only, reversible, and dry-run by default", async () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/health").href, "http://127.0.0.1:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.8:8765"));
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).apply, true);
  assert.throws(() => parseCli(["--wait-seconds=30"]));
  const dry = await runLiveBuildMaterialChainSmoke(parseCli([]));
  assert.equal(dry.localOnly, true);
  assert.equal(dry.reversible, true);
  assert.equal(dry.startsMinecraft, false);
  assert.equal(dry.externalApi, false);
  assert.deepEqual(dry.fixture, {
    suite: "build-material-chain",
    modes: ["setup", "inspect", "cleanup"],
  });
});

test("typed fixture acknowledgement and parsers are exact", () => {
  const snapshot = {
    liveFixtureAck: {
      sequence: 7,
      suite: "build-material-chain",
      mode: "inspect",
      status: status(INITIAL),
    },
  };
  assert.equal(fixtureAcknowledgement(snapshot, 6, "inspect"), status(INITIAL));
  assert.equal(fixtureAcknowledgement(snapshot, 7, "inspect"), null);
  assert.equal(fixtureAcknowledgement(snapshot, 6, "cleanup"), null);
  assert.equal(fixtureExpectedPrefix("setup"), "bmc:setup=");
  assert.equal(fixtureExpectedPrefix("cleanup"), "bmc:cleanup ");
  assert.throws(() => fixtureExpectedPrefix("command"));
  assert.deepEqual(parseSetupStatus("bmc:setup=-10,80,22"), { x: -10, y: 80, z: 22 });
  assert.throws(() => parseSetupStatus("bmc:setup=1.5,80,22"));
  assert.equal(taskEvidenceToken(TASK_ID), "1111111111114111");
  assert.equal(parseInspection(status(FINAL, taskEvidenceToken(TASK_ID))).taskId, taskEvidenceToken(TASK_ID));
  assert.throws(() => parseInspection(`${status(FINAL, taskEvidenceToken(TASK_ID))},extra`));
  assert.throws(() => taskEvidenceToken("not-a-task"));
});

test("the fixed plan covers direct gather, crafting, smelting, torch, and pane chains", () => {
  const draft = buildPlanDraft({ x: 10, y: 70, z: -4 });
  assert.deepEqual(draft.blocks.map(({ blockId, position }) => ({ blockId, position })), [
    { blockId: "minecraft:cobblestone", position: { x: 0, y: 0, z: 0 } },
    { blockId: "minecraft:oak_planks", position: { x: 2, y: 0, z: 0 } },
    { blockId: "minecraft:glass", position: { x: 4, y: 0, z: 0 } },
    { blockId: "minecraft:torch", position: { x: 0, y: 1, z: 0 } },
    { blockId: "minecraft:glass_pane", position: { x: 2, y: 1, z: 0 } },
  ]);
  assert.deepEqual(buildTaskSpec(TASK_ID), {
    kind: "build",
    planId: TASK_ID,
    placement: "plan-origin",
    materialPreference: { source: "nearby", preferredBlockId: "minecraft:oak_planks", allowMixed: false },
    requestedBy: "live-build-material-chain-smoke",
    note: "Local reversible survival material acquisition and locked-origin build acceptance",
  });
});

test("initial and final evidence require every physical material-chain invariant", () => {
  assert.equal(validateInitial(parseInspection(status(INITIAL))).logsRemaining, 15);
  assert.equal(validateFinal(parseInspection(status(FINAL, taskEvidenceToken(TASK_ID))), TASK_ID).matching, 5);

  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, matching: 4, wrong: 1 }, taskEvidenceToken(TASK_ID))), TASK_ID));
  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, coalBreaks: 0, coalRemaining: 16 }, taskEvidenceToken(TASK_ID))), TASK_ID));
  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, litFurnace: 0 }, taskEvidenceToken(TASK_ID))), TASK_ID));
  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, furnacePlacements: 2 }, taskEvidenceToken(TASK_ID))), TASK_ID));
  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, taskIdChanges: 1 }, taskEvidenceToken(TASK_ID))), TASK_ID));
  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, unknownWorldEdits: 1 }, taskEvidenceToken(TASK_ID))), TASK_ID));
  assert.throws(() => validateFinal(parseInspection(status({ ...FINAL, finalDistanceMilli: 9_000 }, taskEvidenceToken(TASK_ID))), TASK_ID));
});

test("cleanup accepts only explicit restoration", () => {
  assert.equal(taskNeedsFixtureCancellation("queued"), true);
  assert.equal(taskNeedsFixtureCancellation("running"), true);
  assert.equal(taskNeedsFixtureCancellation("failed"), true);
  assert.equal(taskNeedsFixtureCancellation("cancelled"), false);
  assert.equal(taskNeedsFixtureCancellation("succeeded"), false);
  assert.equal(validateCleanupStatus("bmc:cleanup restored"), "bmc:cleanup restored");
  assert.equal(validateCleanupStatus("bmc:cleanup none"), "bmc:cleanup none");
  assert.throws(() => validateCleanupStatus("bmc:cleanup conflict,1"));
});
