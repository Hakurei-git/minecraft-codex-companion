import assert from "node:assert/strict";
import test from "node:test";

import {
  FOOD_SURVIVAL_ACTOR_EVIDENCE,
  HUNTABLE_COUNT,
  PROMPT,
  TARGET_COUNT,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  gracefulCloseArguments,
  loopbackBase,
  parseCli,
  parseGracefulCloseEvidence,
  parseInspection,
  selectNewCompanionTasks,
  validateCreatedTaskSet,
  validateFixtureAcknowledgement,
  validateInspection,
  validateStartingSnapshot,
  validateTaskSpec,
  worldEntryArguments,
} from "./live-food-survival-restart-smoke.mjs";

const finalStatus = "food-survival:a=24,k=8,r=17,i=1,l=1,o=1,w=16,g=1,u=1,x=1,s=10,p=3,v=0,d=16,t=1,q=16,h=18";

test("food survival smoke exposes NPC-only actor evidence", () => {
  assert.deepEqual(FOOD_SURVIVAL_ACTOR_EVIDENCE, {
    actor: "ai-npc",
    playerGameplayAssistanceUsed: false,
    usedMinecraftTChat: true,
  });
  assert.equal(TARGET_COUNT, 16);
  assert.equal(HUNTABLE_COUNT, 18);
  assert.equal(PROMPT, "\u7ed9\u621116\u4e2a\u8089");
});

test("food survival smoke parses each strict evidence phase", () => {
  const initial = parseInspection(
    "food-survival:a=0,k=0,r=0,i=0,l=0,o=0,w=0,g=0,u=0,x=0,s=18,p=3,v=0,d=0,t=0,q=16,h=18",
  );
  assert.equal(validateInspection(initial, "initial").survivingAdults, 18);
  assert.equal(validateInspection(parseInspection(finalStatus), "final").physicalDelivered, 16);
  assert.throws(() => validateInspection(parseInspection(finalStatus.replace(",p=3,v=0", ",p=2,v=1")), "final"));
  assert.throws(() => validateInspection(parseInspection(finalStatus.replace(",s=10,", ",s=1,")), "final"));
  assert.throws(() => validateInspection(parseInspection(finalStatus.replace(",q=16,h=18", ",q=4,h=6")), "final"));
});

test("food survival smoke requires the exact T-chat task", () => {
  const task = {
    id: "00000000-0000-0000-0000-000000000001",
    spec: { kind: "provision-food", count: 16, source: "hunt", destination: "player", player: "Owner" },
  };
  assert.equal(validateTaskSpec(task), task);
  assert.equal(validateCreatedTaskSet([task]), task);
  assert.throws(() => validateCreatedTaskSet([]));
  assert.throws(() => validateCreatedTaskSet([task, { ...task, id: "duplicate" }]));
  assert.throws(() => validateTaskSpec({ ...task, spec: { ...task.spec, source: "auto" } }));
  assert.throws(() => validateTaskSpec({ ...task, spec: { ...task.spec, destination: "backpack" } }));
});

test("food survival cleanup captures every task created by T chat", () => {
  const prior = new Set(["old"]);
  const tasks = selectNewCompanionTasks({ tasks: [
    { id: "old", companionId: "npc", spec: { kind: "idle" } },
    { id: "wrong", companionId: "npc", spec: { kind: "gather" } },
    { id: "other", companionId: "other-npc", spec: { kind: "provision-food" } },
  ] }, "npc", prior);
  assert.deepEqual(tasks.map((task) => task.id), ["wrong"]);
});

test("food survival restart requires audited Save and Quit evidence", () => {
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
  assert.equal(accepted.leftWorldBeforeWindowClose, true);
  assert.equal(accepted.pauseMenuConfirmed, true);
  assert.equal(accepted.backgroundPauseLeaseArmed, true);
  assert.deepEqual(gracefulCloseArguments("http://localhost:9876/path", "npc-id"), [
    "-WaitSeconds", "90",
    "-ControlBaseUri", "http://localhost:9876",
    "-CompanionId", "npc-id",
    "-AsJson",
  ]);
  assert.throws(() => gracefulCloseArguments("https://example.com", "npc-id"));
  assert.throws(() => gracefulCloseArguments("http://localhost:8765", ""));
  assert.throws(() => parseGracefulCloseEvidence("not-json"));
  assert.throws(() => parseGracefulCloseEvidence(JSON.stringify({
    SavedAndClosed: true,
    LeftWorldBeforeWindowClose: true,
    ForcedTerminationUsed: false,
    MouseOrKeyboardInputUsed: false,
    ClipboardUsed: false,
    ScreenshotUsed: false,
  })));
  assert.throws(() => parseGracefulCloseEvidence(JSON.stringify({
    SavedAndClosed: true,
    LeftWorldBeforeWindowClose: false,
    ForcedTerminationUsed: false,
    MouseOrKeyboardInputUsed: false,
    ClipboardUsed: false,
    ScreenshotUsed: false,
  })));
});

test("food survival fixture acknowledgements are fresh and exact", () => {
  const current = {
    liveFixtureAck: {
      sequence: 5,
      suite: "food-survival",
      mode: "cleanup",
      status: "food-survival:cleanup restored",
    },
  };
  assert.equal(fixtureExpectedPrefix("verify-restart"), "food-survival:restart same=1,");
  assert.equal(fixtureExpectedPrefix("setup-16"), "food-survival:setup ");
  assert.equal(validateFixtureAcknowledgement(fixtureAcknowledgement(current, 4, "cleanup"), "cleanup").sequence, 5);
  assert.equal(fixtureAcknowledgement(current, 5, "cleanup"), null);
  assert.throws(() => validateFixtureAcknowledgement({
    ...current.liveFixtureAck,
    status: "food-survival:cleanup incomplete",
  }, "cleanup"));
});

test("food survival restart uses loopback only and never requests log inspection", () => {
  assert.equal(loopbackBase("http://localhost:8765/api/tasks").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://example.com"));
  assert.deepEqual(worldEntryArguments("Acceptance World"), [
    "-WorldId", "Acceptance World",
    "-SkipLogInspection",
    "-NoLogMenuGraceSeconds", "75",
    "-WaitSeconds", "300",
  ]);
});

test("food survival smoke refuses non-idle starts and short windows", () => {
  const idle = {
    worldId: "Acceptance World",
    dimension: "minecraft:overworld",
    npcDowned: false,
    activeTaskId: "",
    pausedTaskCount: 0,
    taskSchedulerLifecycle: "idle",
    taskQueue: [],
  };
  assert.deepEqual(validateStartingSnapshot(idle), {
    worldId: idle.worldId,
    dimension: idle.dimension,
  });
  assert.throws(() => validateStartingSnapshot({ ...idle, taskQueue: [{ id: "busy" }] }));
  assert.equal(parseCli([]).waitMs, 300_000);
  assert.equal(parseCli([]).closeAfter, true);
  assert.throws(() => parseCli(["--wait-seconds=30"]));
});
