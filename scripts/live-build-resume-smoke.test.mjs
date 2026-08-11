import assert from "node:assert/strict";
import test from "node:test";

import {
  buildPlanDraft,
  buildTaskSpec,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  fixtureIsRepeatable,
  loopbackBase,
  parseCli,
  parseCompleteInspection,
  parseFailedInspection,
  parseReleaseStatus,
  parseSetupStatus,
  validateCleanupStatus,
  validateCompleteInspection,
  validateFailedInspection,
  validateFailedTask,
  validateRecoverableQueue,
  validateRelease,
} from "./live-build-resume-smoke.mjs";

const TASK_ID = "11111111-1111-1111-1111-111111111111";

test("build resume smoke accepts loopback HTTP URLs only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.10:8765"));
});

test("build resume smoke defaults to a reversible dry run", () => {
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).apply, true);
  assert.equal(parseCli(["--wait-seconds=60"]).waitMs, 60_000);
  assert.throws(() => parseCli(["--wait-seconds=5"]), /between 15 and 900/u);
});

test("fixture acknowledgement modes are fixed and scenario-specific", () => {
  assert.equal(fixtureExpectedPrefix("setup"), "build-resume:setup origin=");
  assert.equal(fixtureExpectedPrefix("inspect-failed"), "build-resume:f=");
  assert.equal(fixtureExpectedPrefix("inspect-complete"), "build-resume:complete expected=");
  assert.deepEqual(parseSetupStatus("build-resume:setup origin=-10,64,8"), { x: -10, y: 64, z: 8 });
  assert.throws(() => fixtureExpectedPrefix("inspect"));
  assert.throws(() => parseSetupStatus("build-resume:setup origin=1.5,64,8"));
  assert.equal(fixtureIsRepeatable("setup"), false);
  assert.equal(fixtureIsRepeatable("cleanup"), true);
  assert.equal(fixtureIsRepeatable("inspect-failed"), true);
});

test("fixture acknowledgement uses the dedicated live fixture sequence and identity", () => {
  const current = {
    sequence: 999,
    status: "following",
    liveFixtureAck: {
      sequence: 8,
      suite: "build-resume",
      mode: "cleanup",
      status: "build-resume:cleanup none",
    },
  };
  assert.equal(fixtureAcknowledgement(current, 7, "cleanup"), "build-resume:cleanup none");
  assert.equal(fixtureAcknowledgement(current, 8, "cleanup"), null);
  assert.equal(fixtureAcknowledgement(current, 7, "setup"), null);
  assert.equal(fixtureAcknowledgement({
    ...current,
    liveFixtureAck: { ...current.liveFixtureAck, suite: "build-palette" },
  }, 7, "cleanup"), null);
});

test("build plan fixes six ordered blocks and the blocker checkpoint at index three", () => {
  const draft = buildPlanDraft({ x: 100, y: 70, z: -20 });
  assert.deepEqual(draft.origin, { x: 100, y: 70, z: -20 });
  assert.deepEqual(draft.blocks.map(({ blockId, position }) => ({ blockId, position })), [
    { blockId: "minecraft:birch_planks", position: { x: 0, y: 0, z: 0 } },
    { blockId: "minecraft:spruce_stairs", position: { x: 2, y: 0, z: 0 } },
    { blockId: "minecraft:jungle_slab", position: { x: 4, y: 0, z: 0 } },
    { blockId: "minecraft:acacia_fence", position: { x: 0, y: 0, z: 2 } },
    { blockId: "minecraft:dark_oak_trapdoor", position: { x: 2, y: 0, z: 2 } },
    { blockId: "minecraft:mangrove_pressure_plate", position: { x: 4, y: 0, z: 2 } },
  ]);
  assert.deepEqual(buildTaskSpec(TASK_ID).materialPreference, { source: "inventory", allowMixed: true });
  assert.equal(buildTaskSpec(TASK_ID).placement, "plan-origin");
});

test("failed inspection proves exact retained failure point and untouched tail", () => {
  const status = `build-resume:f=${TASK_ID},3,6,BLOCK_BREAK_DENIED,3,1,0,3,0`;
  assert.ok(status.length <= 120);
  assert.deepEqual(validateFailedInspection(parseFailedInspection(status), TASK_ID), {
    taskId: TASK_ID,
    index: 3,
    total: 6,
    code: "BLOCK_BREAK_DENIED",
    prefix: 3,
    blocker: 1,
    tail: 0,
    denied: 3,
    releasedBreaks: 0,
  });
  assert.throws(() => validateFailedInspection(parseFailedInspection(status.replace(",3,1,0,3,0", ",3,1,1,3,0")), TASK_ID));
  assert.throws(() => parseFailedInspection(`${status},extra=1`));
});

test("failed task and snapshot must expose the same midpoint checkpoint", () => {
  const task = {
    id: TASK_ID,
    status: "failed",
    progress: 0.5,
    error: { code: "BLOCK_BREAK_DENIED", retryable: true },
  };
  assert.equal(validateFailedTask(task), task);
  const entry = {
    id: TASK_ID,
    kind: "build",
    phase: "paused",
    progress: 0.5,
    pauseReason: "retry: BLOCK_BREAK_DENIED",
  };
  assert.equal(validateRecoverableQueue({ taskQueue: [entry] }, TASK_ID), entry);
  assert.throws(() => validateFailedTask({ ...task, progress: 0 }));
  assert.throws(() => validateRecoverableQueue({ taskQueue: [{ ...entry, phase: "active" }] }, TASK_ID));
});

test("release and completion preserve identity, clear the blocker, and clear recovery state", () => {
  assert.deepEqual(validateRelease(parseReleaseStatus(
    `build-resume:release task=${TASK_ID},index=3`,
  ), TASK_ID), { taskId: TASK_ID, index: 3 });
  assert.deepEqual(validateCompleteInspection(parseCompleteInspection(
    "build-resume:complete expected=6,matching=6,wrong=0,denied=3,releasedBreaks=1,recoverable=0",
  )), { expected: 6, matching: 6, wrong: 0, denied: 3, releasedBreaks: 1, recoverable: 0 });
  assert.throws(() => validateCompleteInspection(parseCompleteInspection(
    "build-resume:complete expected=6,matching=6,wrong=0,denied=3,releasedBreaks=0,recoverable=0",
  )));
});

test("cleanup acknowledgement is exact", () => {
  assert.equal(validateCleanupStatus("build-resume:cleanup restored"), "build-resume:cleanup restored");
  assert.equal(validateCleanupStatus("build-resume:cleanup none"), "build-resume:cleanup none");
  assert.throws(() => validateCleanupStatus("build-resume:cleanup conflicts=1"));
});
