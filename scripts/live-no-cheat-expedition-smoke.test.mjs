import assert from "node:assert/strict";
import test from "node:test";

import {
  expeditionTaskSpec,
  failureWithInspection,
  fixtureAcknowledgement,
  loopbackBase,
  parseCleanupStatus,
  parseCli,
  parseInspectionStatus,
  parseSetupStatus,
  runLiveNoCheatExpeditionSmoke,
  validateCleanup,
  validateInspection,
  validateSetup,
} from "./live-no-cheat-expedition-smoke.mjs";

const TASK_ID = "11111111-2222-4333-8444-555555555555";
const DRIFT_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const SETUP_STATUS = "no-cheat-expedition:setup|c=0,o=16,72,-24";
const INSPECTION_STATUS = "no-cheat-expedition:i|1,0,0,1,1,1,70000,900,4,1,4,4,0,0,2500,0,1,0,0,0,0,0,0,0,0,0";
const CLEANUP_STATUS = "no-cheat-expedition:cleanup|r=1,1,1,1,1";

function response(value, status = 200) {
  return new Response(value === null ? null : JSON.stringify(value), {
    status,
    headers: value === null ? undefined : { "content-type": "application/json" },
  });
}

function createFetchScenario(overrides = {}) {
  const calls = [];
  const fixtureStatuses = {
    setup: overrides.setupStatus ?? SETUP_STATUS,
    inspect: overrides.inspectionStatus ?? INSPECTION_STATUS,
    cleanup: overrides.cleanupStatus ?? CLEANUP_STATUS,
  };
  const pollRecords = overrides.pollRecords ?? [
    { id: TASK_ID, status: "running", progress: 0.5 },
    { id: TASK_ID, status: "succeeded", progress: 1, message: "delivered" },
  ];
  let pendingFixtureMode = null;
  let latestAck = null;
  let ackSequence = 0;
  let pollIndex = 0;

  const companion = {
    id: "codex-forge",
    name: "Aster",
    backend: overrides.backend ?? "forge-1.20.1",
    connected: true,
    embodiment: "in-world-npc",
    activeTaskId: null,
    ownerName: "PlayerOne",
    ...overrides.companion,
  };

  const fetch = async (input, init = {}) => {
    const url = input instanceof URL ? input : new URL(input);
    const method = init.method ?? "GET";
    const body = init.body === undefined ? undefined : JSON.parse(init.body);
    calls.push({ method, pathname: url.pathname, body });

    if (method === "GET" && url.pathname === "/api/companions") {
      return response({ companions: [companion] });
    }
    if (method === "POST" && url.pathname === "/api/companions/codex-forge/live-fixtures") {
      pendingFixtureMode = body.mode;
      return response({ ok: true, suite: body.suite, mode: body.mode }, 202);
    }
    if (method === "GET" && url.pathname === "/api/companions/codex-forge/snapshot") {
      if (pendingFixtureMode !== null) {
        ackSequence += 1;
        latestAck = {
          sequence: ackSequence,
          suite: "no-cheat-expedition",
          mode: pendingFixtureMode,
          status: fixtureStatuses[pendingFixtureMode],
        };
        pendingFixtureMode = null;
      }
      const mode = latestAck?.mode;
      return response({
        sequence: ackSequence + 10,
        gameMode: "survival",
        materialMode: "survival",
        nearbyEntities: [{ disposition: "owner", name: "PlayerOne" }],
        liveFixtureAck: latestAck ?? undefined,
        ...(overrides.snapshotByMode?.[mode] ?? {}),
      });
    }
    if (method === "POST" && url.pathname === "/api/companions/codex-forge/tasks") {
      if (overrides.assignmentError) return response({ error: "assignment failed" }, 500);
      return response({ id: TASK_ID, status: "queued", progress: 0, spec: body.spec }, 202);
    }
    if (method === "POST" && url.pathname === `/api/tasks/${TASK_ID}/cancel`) {
      return response({ id: TASK_ID, status: "cancelled", progress: 0 });
    }
    if (method === "GET" && url.pathname === `/api/tasks/${TASK_ID}`) {
      const record = pollRecords[Math.min(pollIndex, pollRecords.length - 1)];
      pollIndex += 1;
      return response(record);
    }
    return response({ error: `unhandled ${method} ${url.pathname}` }, 404);
  };
  return { calls, fetch };
}

async function withScenario(overrides, callback) {
  const scenario = createFetchScenario(overrides);
  const originalFetch = globalThis.fetch;
  globalThis.fetch = scenario.fetch;
  try {
    return await callback(scenario);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

function runOptions() {
  return {
    apply: true,
    base: new URL("http://127.0.0.1:8765/"),
    waitMs: 2_000,
    pollMs: 0,
    fixturePollMs: 0,
    fixtureWaitMs: 2_000,
  };
}

test("no-cheat expedition smoke is loopback-only, dry-run by default, and bounded", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.20:8765"));
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).waitMs, 60_000);
  assert.throws(() => parseCli(["--wait-seconds=5"]));
});

test("typed fixture acknowledgement is monotonic, mode-bound, and status-only", () => {
  const current = {
    status: "ordinary chat status",
    liveFixtureAck: {
      sequence: 8,
      suite: "no-cheat-expedition",
      mode: "inspect",
      status: INSPECTION_STATUS,
    },
  };
  assert.equal(fixtureAcknowledgement(current, 7, "inspect"), INSPECTION_STATUS);
  assert.equal(fixtureAcknowledgement(current, 8, "inspect"), null);
  assert.equal(fixtureAcknowledgement(current, 7, "cleanup"), null);
});

test("normal flow uses setup, one exact macro, same-task polling, inspection, and cleanup", async () => {
  await withScenario({}, async ({ calls }) => {
    const report = await runLiveNoCheatExpeditionSmoke(runOptions());
    assert.equal(report.ok, true);
    assert.equal(report.taskId, TASK_ID);
    assert.equal(report.cleanup.none, false);

    const assignment = calls.find((call) => (
      call.method === "POST" && call.pathname === "/api/companions/codex-forge/tasks"
    ));
    assert.deepEqual(assignment.body, {
      spec: expeditionTaskSpec("minecraft:oak_log", "PlayerOne"),
      owner: "live-no-cheat-expedition-smoke",
    });
    assert.equal(Object.hasOwn(assignment.body.spec, "movement"), false);
    assert.equal(assignment.body.spec.skillId, "life.expedition-and-deliver");
    assert.equal(assignment.body.spec.arguments.count, 4);

    const taskReads = calls.filter((call) => call.method === "GET" && call.pathname.startsWith("/api/tasks/"));
    assert.ok(taskReads.length >= 2);
    assert.ok(taskReads.every((call) => call.pathname === `/api/tasks/${TASK_ID}`));
    const fixtureModes = calls
      .filter((call) => call.method === "POST" && call.pathname.endsWith("/live-fixtures"))
      .map((call) => call.body);
    assert.deepEqual(fixtureModes, [
      { suite: "no-cheat-expedition", mode: "setup" },
      { suite: "no-cheat-expedition", mode: "inspect" },
      { suite: "no-cheat-expedition", mode: "cleanup" },
    ]);
    assert.ok(calls.every((call) => call.body?.command === undefined));
  });
});

test("every missing world-state proof rejects the inspection", () => {
  const current = { gameMode: "survival", materialMode: "survival" };
  const valid = parseInspectionStatus(INSPECTION_STATUS);
  assert.equal(validateInspection(valid, current), valid);
  const insufficient = [
    { complete: 0 },
    { cheatsObserved: 1 },
    { creativeObserved: 1 },
    { sawGather: 0 },
    { sawDeliver: 0 },
    { sawExcursion: 0 },
    { maxDistanceMilli: 54_999 },
    { maxStepMilli: 4_001 },
    { logBreaks: 3 },
    { deliverySpawns: 0 },
    { deliveryItems: 3 },
    { playerItems: 3 },
    { npcItems: 1 },
    { worldItems: 1 },
    { returnDistanceMilli: 3_201 },
    { maxOwnerDriftMilli: 1_501 },
    { taskIdStable: 0 },
    { observationErrors: 1 },
    { breakSyncErrors: 1 },
    { remainingFixtureLogs: 1 },
    { queuedTargets: 1 },
    { skippedTargets: 1 },
    { excursions: 1 },
    { treeCluster: 1 },
    { clusterReached: 1 },
    { targetSelected: 1 },
  ];
  for (const change of insufficient) {
    assert.throws(() => validateInspection({ ...valid, ...change }, current));
  }
  assert.throws(() => validateInspection(valid, { ...current, gameMode: "creative" }));
  assert.throws(() => validateInspection(valid, { ...current, materialMode: "creative" }));
});

test("an assignment exception still invokes typed cleanup", async () => {
  await withScenario({ assignmentError: true }, async ({ calls }) => {
    await assert.rejects(runLiveNoCheatExpeditionSmoke(runOptions()), /HTTP 500/u);
    const fixtureModes = calls
      .filter((call) => call.method === "POST" && call.pathname.endsWith("/live-fixtures"))
      .map((call) => call.body.mode);
    assert.deepEqual(fixtureModes, ["setup", "cleanup"]);
  });
});

test("task ID drift fails and still cleans up the fixture", async () => {
  await withScenario({
    pollRecords: [
      { id: DRIFT_ID, status: "running", progress: 0.25 },
      { id: TASK_ID, status: "succeeded", progress: 1 },
    ],
  }, async ({ calls }) => {
    await assert.rejects(runLiveNoCheatExpeditionSmoke(runOptions()), /task ID drift/u);
    const fixtureModes = calls
      .filter((call) => call.method === "POST" && call.pathname.endsWith("/live-fixtures"))
      .map((call) => call.body.mode);
    assert.deepEqual(fixtureModes, ["setup", "inspect", "cleanup"]);
  });
});

test("task failures retain the last typed inspection without replacing the cause", () => {
  const cause = new Error("task timed out");
  const inspection = parseInspectionStatus(
    "no-cheat-expedition:i|0,0,0,1,0,1,78000,900,1,0,0,0,1,0,69000,0,1,0,0,3,4,0,1,1,1,1",
  );
  const failure = failureWithInspection(cause, inspection);

  assert.equal(failure.cause, cause);
  assert.match(failure.message, /task timed out; last inspection:/u);
  assert.match(failure.message, /"remainingFixtureLogs":3/u);
  assert.match(failure.message, /"queuedTargets":4/u);
});

test("non-Forge, non-survival, and cheat-enabled states are rejected", async () => {
  await withScenario({ backend: "simulator" }, async ({ calls }) => {
    await assert.rejects(runLiveNoCheatExpeditionSmoke(runOptions()), /requires a Forge 1\.20\.1 companion/u);
    assert.equal(calls.some((call) => call.pathname.endsWith("/live-fixtures")), false);
  });
  await withScenario({ snapshotByMode: { setup: { gameMode: "creative" } } }, async ({ calls }) => {
    await assert.rejects(runLiveNoCheatExpeditionSmoke(runOptions()), /requires Forge survival without cheats/u);
    const modes = calls
      .filter((call) => call.method === "POST" && call.pathname.endsWith("/live-fixtures"))
      .map((call) => call.body.mode);
    assert.equal(modes.at(-1), "cleanup");
  });
  await withScenario({ setupStatus: "no-cheat-expedition:setup|c=1,o=16,72,-24" }, async ({ calls }) => {
    await assert.rejects(runLiveNoCheatExpeditionSmoke(runOptions()), /requires Forge survival without cheats/u);
    const modes = calls
      .filter((call) => call.method === "POST" && call.pathname.endsWith("/live-fixtures"))
      .map((call) => call.body.mode);
    assert.equal(modes.at(-1), "cleanup");
  });
});

test("setup and cleanup evidence are strict and reversible", () => {
  const setup = parseSetupStatus(SETUP_STATUS);
  assert.deepEqual(validateSetup(setup, { gameMode: "survival", materialMode: "survival" }), {
    cheatsEnabled: 0,
    origin: { x: 16, y: 72, z: -24 },
    itemId: "minecraft:oak_log",
  });
  assert.throws(() => parseSetupStatus("no-cheat-expedition:setup command=/tp"));
  assert.deepEqual(validateCleanup(parseCleanupStatus(CLEANUP_STATUS)), {
    none: false,
    player: 1,
    npc: 1,
    respawn: 1,
    blocks: 1,
    items: 1,
  });
  assert.throws(() => validateCleanup(parseCleanupStatus("no-cheat-expedition:cleanup|r=1,1,1,0,1")));
  assert.throws(() => validateCleanup(parseCleanupStatus("no-cheat-expedition:cleanup|none")));
  assert.deepEqual(validateCleanup(parseCleanupStatus("no-cheat-expedition:cleanup|none"), true), { none: true });
});
