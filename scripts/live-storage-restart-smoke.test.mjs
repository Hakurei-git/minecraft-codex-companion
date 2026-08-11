import assert from "node:assert/strict";
import test from "node:test";

import {
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  gracefulCloseArguments,
  loopbackBase,
  parseGracefulCloseEvidence,
  parseCli,
  parseInspection,
  requireWorldId,
  validateCleanupAcknowledgement,
  validateInspection,
  validateStartingSnapshot,
  worldEntryArguments,
} from "./live-storage-restart-smoke.mjs";

test("storage restart smoke requires explicit setup and inspection acknowledgements", () => {
  assert.equal(fixtureExpectedPrefix("setup-restart"), "storage-fixture:setup scenario=restart");
  assert.equal(fixtureExpectedPrefix("inspect-restart"), "storage-fixture:restart ");
  assert.equal(fixtureExpectedPrefix("cleanup"), "storage-fixture:cleanup ");
  assert.throws(() => fixtureExpectedPrefix("unknown"));
});

test("storage restart smoke accepts only a fresh matching fixture ACK", () => {
  const current = {
    liveFixtureAck: {
      sequence: 8,
      suite: "storage",
      mode: "cleanup",
      status: "storage-fixture:cleanup restored",
    },
  };
  const acknowledgement = fixtureAcknowledgement(current, 7, "cleanup");
  assert.deepEqual(validateCleanupAcknowledgement(acknowledgement), {
    ...current.liveFixtureAck,
    restored: true,
  });
  assert.equal(fixtureAcknowledgement(current, 8, "cleanup"), null);
  assert.equal(fixtureAcknowledgement(current, 7, "setup-restart"), null);
  assert.throws(() => validateCleanupAcknowledgement({
    sequence: 9,
    suite: "storage",
    mode: "cleanup",
    status: "storage-fixture:cleanup none",
  }));
  assert.equal(validateCleanupAcknowledgement({
    sequence: 9,
    suite: "storage",
    mode: "cleanup",
    status: "storage-fixture:cleanup none",
  }, false).restored, false);
  assert.throws(() => validateCleanupAcknowledgement({
    sequence: 10,
    suite: "dragon",
    mode: "cleanup",
    status: "storage-fixture:cleanup restored",
  }));
  assert.throws(() => validateCleanupAcknowledgement({
    sequence: 10,
    suite: "storage",
    mode: "setup-restart",
    status: "storage-fixture:cleanup restored",
  }));
});

test("storage restart smoke accepts loopback control URLs only", () => {
  assert.equal(loopbackBase("http://localhost:8765/api/tasks").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://example.com"));
});

test("storage restart requires audited Save and Quit evidence", () => {
  const accepted = parseGracefulCloseEvidence(JSON.stringify({
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
  assert.equal(accepted.backgroundPauseLeaseArmed, true);
  assert.deepEqual(gracefulCloseArguments("http://localhost:9876/path", "npc-id"), [
    "-WaitSeconds", "90",
    "-ControlBaseUri", "http://localhost:9876",
    "-CompanionId", "npc-id",
    "-AsJson",
  ]);
  assert.throws(() => gracefulCloseArguments("https://example.com", "npc-id"));
  assert.throws(() => parseGracefulCloseEvidence(JSON.stringify({
    SavedAndClosed: true,
    LeftWorldBeforeWindowClose: true,
    PauseMenuConfirmed: true,
    ForcedTerminationUsed: false,
    MouseOrKeyboardInputUsed: false,
    ClipboardUsed: false,
    ScreenshotUsed: false,
  })));
});

test("storage restart smoke parses and validates item conservation", () => {
  const initial = parseInspection(
    "storage-fixture:restart home=96,npc=0,player=0,world=0,near=0,containers=96",
  );
  assert.deepEqual(validateInspection(initial, "initial"), {
    home: 96, npc: 0, player: 0, world: 0, near: 0, containers: 96,
  });
  assert.doesNotThrow(() => validateInspection(
    { home: 0, npc: 0, player: 64, world: 32, near: 32, containers: 0 },
    "final",
  ));
  assert.throws(() => validateInspection(
    { home: 1, npc: 0, player: 95, world: 0, near: 0, containers: 1 },
    "final",
  ));
});

test("storage restart smoke requires an explicit long acceptance window", () => {
  assert.equal(parseCli([]).waitMs, 300_000);
  assert.throws(() => parseCli(["--wait-seconds=30"]));
});

test("storage restart smoke never inspects Minecraft logs while re-entering", () => {
  assert.deepEqual(worldEntryArguments("Codex-Restart-Acceptance"), [
    "-WorldId",
    "Codex-Restart-Acceptance",
    "-SkipLogInspection",
    "-NoLogMenuGraceSeconds",
    "75",
    "-WaitSeconds",
    "300",
  ]);
  assert.equal(requireWorldId("Codex-Restart-Acceptance"), "Codex-Restart-Acceptance");
  assert.equal(requireWorldId(" Test World "), " Test World ");
  assert.throws(() => worldEntryArguments(""));
  assert.throws(() => worldEntryArguments("bad\nworld"));
});

test("storage restart smoke refuses every non-idle starting state", () => {
  const idle = {
    worldId: "Codex-Restart-Acceptance",
    dimension: "minecraft:overworld",
    npcDowned: false,
    activeTaskId: "",
    pausedTaskCount: 0,
    taskSchedulerLifecycle: "idle",
    taskQueue: [],
    stance: "follow",
    status: "ready",
  };
  assert.deepEqual(validateStartingSnapshot(idle), {
    worldId: idle.worldId,
    dimension: idle.dimension,
    stance: idle.stance,
    status: idle.status,
  });
  assert.throws(() => validateStartingSnapshot({ ...idle, npcDowned: true }));
  assert.throws(() => validateStartingSnapshot({ ...idle, activeTaskId: "task-1" }));
  assert.throws(() => validateStartingSnapshot({ ...idle, pausedTaskCount: 1 }));
  assert.throws(() => validateStartingSnapshot({ ...idle, taskQueue: [{ id: "queued" }] }));
  assert.throws(() => validateStartingSnapshot({ ...idle, taskSchedulerLifecycle: "running" }));
});
