import assert from "node:assert/strict";
import test from "node:test";

import {
  captureRestorationBaseline,
  combineCareRunErrors,
  fixtureAcknowledgement,
  fixturePrefix,
  finalizeCareRun,
  isNpcNotIdleFixtureError,
  loopbackBase,
  parseCli,
  parseInspection,
  parseStage,
  requireIdleCompanion,
  isIdleCompanionSnapshot,
  restorationDifferences,
  taskSpec,
  validateInspection,
  waitForIdleCompanion,
  waitForRestoration,
} from "./live-dragon-care-smoke.mjs";

test("dragon care smoke is loopback-only and dry-run by default", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api").href, "http://127.0.0.1:8765/");
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).waitMs, 60_000);
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.1:8765"));
  assert.throws(() => parseCli(["--wait-seconds=10"]));
});

test("fixture modes and acknowledgements are fixed and structured", () => {
  assert.equal(fixturePrefix("setup-book"), "dragon-care:setup|b");
  assert.equal(fixturePrefix("inspect-heal"), "dragon-care:i|");
  assert.equal(fixturePrefix("cleanup"), "dragon-care:cleanup|");
  assert.throws(() => fixturePrefix("execute-command"));
  const snapshot = {
    liveFixtureAck: {
      sequence: 8,
      suite: "dragon-care",
      mode: "stage-feed",
      status: "dragon-care:s|b|f|11111111-2222-3333-4444-555555555555",
    },
  };
  assert.equal(fixtureAcknowledgement(snapshot, 7, "stage-feed"), snapshot.liveFixtureAck.status);
  assert.equal(fixtureAcknowledgement(snapshot, 8, "stage-feed"), null);
  assert.equal(fixtureAcknowledgement(snapshot, 7, "stage-heal"), null);
});

test("only the stable NPC-not-idle fixture code is retryable", () => {
  assert.equal(isNpcNotIdleFixtureError(new Error(
    "live-fixture:failed suite=dragon-care mode=stage-heal code=npc-not-idle",
  )), true);
  assert.equal(isNpcNotIdleFixtureError(new Error(
    "live-fixture:failed suite=dragon-care mode=stage-heal code=fixture-failed",
  )), false);
  assert.equal(isNpcNotIdleFixtureError("npc-not-idle"), false);
});

test("stage parser binds the expected mod, action and target kind", () => {
  assert.deepEqual(parseStage(
    "dragon-care:s|b|f|11111111-2222-3333-4444-555555555555",
    "bookofdragons",
    "feed",
  ), {
    modId: "bookofdragons",
    action: "feed",
    targetId: "11111111-2222-3333-4444-555555555555",
  });
  assert.equal(parseStage(
    "dragon-care:s|s|e|saintsdragons:raevyx_egg",
    "saintsdragons",
    "egg",
  ).targetId, "saintsdragons:raevyx_egg");
  assert.throws(() => parseStage(
    "dragon-care:s|b|f|saintsdragons:raevyx_egg",
    "bookofdragons",
    "feed",
  ));
  assert.throws(() => parseStage(
    "dragon-care:s|s|f|11111111-2222-3333-4444-555555555555",
    "bookofdragons",
    "feed",
  ));
});

test("inspection validation requires real state change for every action", () => {
  validateInspection(parseInspection("dragon-care:i|b|f|1|0|10000|0|1|1|0|1|0"), "bookofdragons", "feed");
  validateInspection(parseInspection("dragon-care:i|s|f|1|0|0|4000|1|1|0|1|0"), "saintsdragons", "feed");
  validateInspection(parseInspection("dragon-care:i|b|h|1|4000|10000|0|1|1|0|1|0"), "bookofdragons", "heal");
  validateInspection(parseInspection("dragon-care:i|s|t|1|0|0|0|1|1|0|1|1"), "saintsdragons", "tame");
  validateInspection(parseInspection("dragon-care:i|s|e|0|0|0|0|0|1|1|1|0"), "saintsdragons", "egg");

  assert.throws(() => validateInspection(
    parseInspection("dragon-care:i|b|f|0|0|10000|0|1|1|0|1|0"),
    "bookofdragons",
    "feed",
  ));
  assert.throws(() => validateInspection(
    parseInspection("dragon-care:i|b|h|1|0|10000|0|1|1|0|1|0"),
    "bookofdragons",
    "heal",
  ));
  assert.throws(() => validateInspection(
    parseInspection("dragon-care:i|s|t|0|0|0|0|1|1|0|1|1"),
    "saintsdragons",
    "tame",
  ));
  assert.throws(() => validateInspection(
    parseInspection("dragon-care:i|s|e|0|0|0|0|0|1|0|1|0"),
    "saintsdragons",
    "egg",
  ));
  assert.throws(() => validateInspection(
    parseInspection("dragon-care:i|s|e|0|0|0|0|0|0|1|1|0"),
    "saintsdragons",
    "egg",
  ));
  assert.throws(() => validateInspection(
    parseInspection("dragon-care:i|s|t|1|0|0|0|1|1|0|1|0"),
    "saintsdragons",
    "tame",
  ));
});

test("task specs cannot smuggle arbitrary commands", () => {
  assert.deepEqual(taskSpec("heal", "11111111-2222-3333-4444-555555555555"), {
    kind: "dragon",
    action: "heal",
    targetId: "11111111-2222-3333-4444-555555555555",
    requestedBy: "live-dragon-care-smoke",
    note: "Fixed reversible dragon care acceptance",
  });
  assert.equal(taskSpec("egg", "saintsdragons:raevyx_egg").action, "care-for-egg");
  assert.throws(() => taskSpec("/op @a", "target"));
});

test("apply preflight rejects downed, queued, paused and non-idle NPC state", () => {
  const companion = { id: "npc-1" };
  assert.equal(requireIdleCompanion(companion, {
    npcDowned: false,
    pausedTaskCount: 0,
    taskQueue: [],
    taskSchedulerLifecycle: "idle",
  }), companion);
  assert.throws(() => requireIdleCompanion(companion, { npcDowned: true }));
  assert.throws(() => requireIdleCompanion(companion, { pausedTaskCount: 1 }));
  assert.throws(() => requireIdleCompanion(companion, { taskQueue: [{ id: "queued" }] }));
  assert.throws(() => requireIdleCompanion(companion, { activeTaskId: "active" }));
  assert.throws(() => requireIdleCompanion(companion, { taskSchedulerLifecycle: "running" }));
});

test("idle polling waits for the Minecraft scheduler after a terminal task acknowledgement", async () => {
  assert.equal(isIdleCompanionSnapshot({
    taskSchedulerLifecycle: "idle",
    activeTaskId: "",
    pausedTaskCount: 0,
    taskQueue: [],
    automaticEating: false,
  }), true);
  assert.equal(isIdleCompanionSnapshot({
    taskSchedulerLifecycle: "idle",
    activeTaskId: "just-finished",
    pausedTaskCount: 0,
    taskQueue: [],
  }), false);

  let reads = 0;
  const idle = await waitForIdleCompanion(async () => {
    reads++;
    return {
      taskSchedulerLifecycle: reads === 1 ? "running" : "idle",
      activeTaskId: reads === 1 ? "just-finished" : "",
      pausedTaskCount: 0,
      taskQueue: [],
      automaticEating: false,
    };
  }, { timeoutMs: 100, intervalMs: 0 });
  assert.equal(idle.taskSchedulerLifecycle, "idle");
  assert.equal(reads, 2);
});

test("finalizer always cleans up and aggregates cancellation plus cleanup failures", async () => {
  const calls = [];
  const cancelError = new Error("cancel failed");
  await assert.rejects(
    finalizeCareRun(
      async () => { calls.push("cancel"); throw cancelError; },
      async () => { calls.push("cleanup"); },
    ),
    cancelError,
  );
  assert.deepEqual(calls, ["cancel", "cleanup"]);

  const cleanupError = new Error("cleanup failed");
  await assert.rejects(
    finalizeCareRun(
      async () => { throw cancelError; },
      async () => { throw cleanupError; },
    ),
    (error) => error instanceof AggregateError
      && error.errors[0] === cancelError
      && error.errors[1] === cleanupError,
  );
});

test("a primary dragon action error is retained when restoration also fails", () => {
  const primary = new Error("action failed");
  const finalization = new Error("cleanup failed");
  assert.equal(combineCareRunErrors(primary, null), primary);
  assert.equal(combineCareRunErrors(null, finalization), finalization);
  const combined = combineCareRunErrors(primary, finalization);
  assert(combined instanceof AggregateError);
  assert.deepEqual(combined.errors, [primary, finalization]);
});

function restorableSnapshot(overrides = {}) {
  return {
    npcEntityUuid: "11111111-2222-3333-4444-555555555555",
    dimension: "minecraft:overworld",
    position: { x: 10.5, y: 64, z: -2.5 },
    yaw: 15,
    pitch: 5,
    health: 17,
    maxHealth: 20,
    food: 11,
    saturation: 2.5,
    exhaustion: 0.25,
    materialMode: "survival",
    gameMode: "creative",
    status: "original status",
    stance: "guard",
    npcDowned: false,
    inventory: [{ id: "minecraft:iron_sword", count: 1, slot: 18, slotType: "main_hand", damage: 3 }],
    dragonState: {
      modId: "bookofdragons",
      entityId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      mounted: false,
    },
    ...overrides,
  };
}

test("cleanup baseline compares visible NPC and owner state without fixture acknowledgement coupling", () => {
  const original = restorableSnapshot();
  const baseline = captureRestorationBaseline(original);
  assert.deepEqual(restorationDifferences(baseline, {
    ...original,
    liveFixtureAck: {
      sequence: 12,
      suite: "dragon-care",
      mode: "cleanup",
      status: "dragon-care:cleanup|restored",
    },
  }), []);
  assert.deepEqual(restorationDifferences(baseline, restorableSnapshot({
    dimension: "minecraft:the_nether",
    gameMode: "survival",
    status: "dragon-care:cleanup|restored",
    health: 20,
    food: 20,
    stance: "stay",
    position: { x: 14, y: 70, z: 2 },
    inventory: [],
  })), [
    "dimension", "gameMode", "stance", "status", "health", "food",
    "position.x", "position.y", "position.z", "inventory",
  ]);
});

test("cleanup restoration polling waits for the original state and reports persistent drift", async () => {
  const original = restorableSnapshot();
  const baseline = captureRestorationBaseline(original);
  let reads = 0;
  const restored = await waitForRestoration(async () => {
    reads++;
    return reads === 1 ? restorableSnapshot({ status: "dragon-care:cleanup|restored" }) : original;
  }, baseline, { timeoutMs: 100, intervalMs: 0 });
  assert.equal(restored.status, "original status");
  assert.equal(reads, 2);

  await assert.rejects(
    waitForRestoration(
      async () => restorableSnapshot({ food: 20 }),
      baseline,
      { timeoutMs: 5, intervalMs: 0 },
    ),
    /food/u,
  );

  assert.deepEqual(restorationDifferences(
    baseline,
    restorableSnapshot({
      position: { x: 20, y: 65, z: 4 },
      yaw: 90,
      pitch: -10,
      exhaustion: 1.5,
    }),
    { allowMotion: true },
  ), []);
});
