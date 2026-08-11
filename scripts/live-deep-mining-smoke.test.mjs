import assert from "node:assert/strict";
import test from "node:test";

import {
  deepMiningTaskSpec,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  gracefulCloseArguments,
  loopbackBase,
  parseCli,
  parseGracefulCloseEvidence,
  parseInspection,
  validateCheckpoint,
  validateCleanupAcknowledgement,
  validateInspection,
  validateStartingSnapshot,
  worldEntryArguments,
} from "./live-deep-mining-smoke.mjs";

test("deep-mining smoke accepts only fresh matching fixture acknowledgements", () => {
  assert.equal(fixtureExpectedPrefix("setup"), "deep-mining:setup|o=");
  assert.equal(fixtureExpectedPrefix("inspect"), "deep-mining:i|");
  assert.equal(fixtureExpectedPrefix("cleanup"), "deep-mining:cleanup|");
  assert.throws(() => fixtureExpectedPrefix("command"));

  const current = {
    liveFixtureAck: {
      sequence: 4,
      suite: "deep-mining",
      mode: "cleanup",
      status: "deep-mining:cleanup|r=1,1,1,1",
    },
  };
  assert.deepEqual(validateCleanupAcknowledgement(
    fixtureAcknowledgement(current, 3, "cleanup"),
  ), { ...current.liveFixtureAck, restored: true });
  assert.equal(fixtureAcknowledgement(current, 4, "cleanup"), null);
  assert.equal(fixtureAcknowledgement(current, 3, "inspect"), null);
  assert.throws(() => validateCleanupAcknowledgement({
    ...current.liveFixtureAck,
    status: "deep-mining:cleanup|r=1,1,1,0",
  }));
});

test("deep-mining smoke validates the physical restart checkpoint", () => {
  const miningState = {
    phase: "branching",
    itemId: "minecraft:diamond",
    targetY: -58,
    staircaseStep: 4,
    branchProgress: 4,
    brokenBlocks: 20,
  };
  const checkpoint = validateCheckpoint(
    { sequence: 90, position: { x: 1, y: -58, z: 2 }, miningState },
    { id: "task-id", status: "running" },
  );
  assert.equal(checkpoint.taskId, "task-id");
  assert.equal(checkpoint.miningState.branchProgress, 4);
  assert.throws(() => validateCheckpoint(
    { sequence: 91, miningState: { ...miningState, staircaseStep: 3 } },
    { id: "task-id", status: "running" },
  ));
  assert.throws(() => validateCheckpoint(
    { sequence: 91, miningState },
    { id: "task-id", status: "succeeded" },
  ));
});

test("deep-mining smoke parses complete persisted fixture evidence", () => {
  const inspection = parseInspection(
    "deep-mining:i|ok=1,l=33,t=32,p=2,d=1,b=1,s=4,r=9,x=1,k=34,o=3,g=1,v=1,w=3,j=192,n=1,e=0",
  );
  assert.deepEqual(validateInspection(inspection), {
    ok: 1,
    ladders: 33,
    torches: 32,
    usableIronPickaxes: 2,
    sawDescending: 1,
    sawBranching: 1,
    staircaseStep: 4,
    branchProgress: 9,
    placedTorches: 1,
    brokenBlocks: 34,
    diamonds: 3,
    playerDiamondPickaxes: 1,
    deliverySeen: 1,
    discardedStoneStacks: 3,
    discardedStoneItems: 192,
    stoneDropLedgerSeen: 1,
    observationErrors: 0,
  });
  assert.throws(() => validateInspection({ ...inspection, placedTorches: 0 }));
  assert.throws(() => validateInspection({ ...inspection, stoneDropLedgerSeen: 0 }));
  assert.throws(() => parseInspection("deep-mining:i|ok=1,e=0"));
});

test("deep-mining smoke binds one exact owner-delivery task", () => {
  assert.deepEqual(deepMiningTaskSpec("PlayerOne"), {
    kind: "craft",
    itemId: "minecraft:diamond_pickaxe",
    count: 1,
    deliverTo: "PlayerOne",
    requestedBy: "PlayerOne",
    note: "Reversible deep-mining restart acceptance",
  });
  assert.throws(() => deepMiningTaskSpec(""));
});

test("deep-mining smoke accepts loopback only and never inspects logs", () => {
  assert.equal(loopbackBase("http://localhost:8765/api/tasks").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://provider.example.test/v1"));
  assert.deepEqual(worldEntryArguments("Codex-Test"), [
    "-WorldId", "Codex-Test",
    "-SkipLogInspection",
    "-NoLogMenuGraceSeconds", "75",
    "-WaitSeconds", "300",
  ]);
  assert.throws(() => worldEntryArguments("bad\nworld"));
});

test("deep-mining smoke requires audited background Save and Quit", () => {
  const evidence = parseGracefulCloseEvidence(JSON.stringify({
    SavedAndClosed: true,
    LeftWorldBeforeWindowClose: true,
    PauseMenuConfirmed: true,
    BackgroundPauseLeaseArmed: true,
    CursorCaptureReleased: true,
    ForcedTerminationUsed: false,
    MouseOrKeyboardInputUsed: false,
    ClipboardUsed: false,
    ScreenshotUsed: false,
  }));
  assert.equal(evidence.savedAndClosed, true);
  assert.deepEqual(gracefulCloseArguments("http://127.0.0.1:8765/path", "codex-forge"), [
    "-WaitSeconds", "90",
    "-ControlBaseUri", "http://127.0.0.1:8765",
    "-CompanionId", "codex-forge",
    "-AsJson",
  ]);
  assert.throws(() => gracefulCloseArguments("https://provider.example.test/v1", "codex-forge"));
});

test("deep-mining smoke refuses non-idle or non-survival starts", () => {
  const idle = {
    worldId: "Codex-Test",
    dimension: "minecraft:overworld",
    materialMode: "survival",
    npcDowned: false,
    activeTaskId: "",
    pausedTaskCount: 0,
    taskSchedulerLifecycle: "idle",
    taskQueue: [],
  };
  assert.deepEqual(validateStartingSnapshot(idle), {
    worldId: "Codex-Test",
    dimension: "minecraft:overworld",
    materialMode: "survival",
  });
  assert.throws(() => validateStartingSnapshot({ ...idle, activeTaskId: "task" }));
  assert.throws(() => validateStartingSnapshot({ ...idle, materialMode: "creative" }));
  assert.throws(() => validateStartingSnapshot({ ...idle, pausedTaskCount: 1 }));
});

test("deep-mining smoke requires explicit apply and a long wait window", () => {
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=420"]).waitMs, 420_000);
  assert.throws(() => parseCli(["--wait-seconds=30"]));
});
