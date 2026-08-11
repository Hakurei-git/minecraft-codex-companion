import assert from "node:assert/strict";
import test from "node:test";

import {
  fixtureAcknowledgement,
  fixturePrefix,
  isNewBedChatTask,
  loopbackBase,
  parseCleanupStatus,
  parseCli,
  parseInspection,
  parseSetupStatus,
  taskSpecs,
  validateAwakeInspection,
  validateCleanup,
  validateCraftedInspection,
  validateInitialInspection,
  validateNightInspection,
  validateSleepingInspection,
} from "./live-bed-sleep-smoke.mjs";

const initialValues = [2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0];
const craftedValues = [0, 0, 1, 1, 0, 0, 0, 2, 2, 1, 1, 1, 5, 1, 1, 1, 1, 1, 0, 0, 0, 1, 1, 0];
const inspection = (values, bed = "12,96,-4") => `bed-sleep:i|${values.join(",")}|bed=${bed}`;

test("bed sleep smoke is loopback-only, dry-run by default, and bounded", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.10:8765"));
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).waitMs, 60_000);
  assert.equal(parseCli(["--trigger-via-chat"]).triggerViaChat, true);
  assert.throws(() => parseCli(["--wait-seconds=10"]));
});

test("T-chat mode selects only the newly created bed macro for this companion", () => {
  const existing = new Set(["old-bed"]);
  assert.equal(isNewBedChatTask({
    id: "new-bed",
    companionId: "codex-forge",
    spec: { kind: "macro", skillId: "life.craft-and-place-bed" },
  }, "codex-forge", existing), true);
  assert.equal(isNewBedChatTask({
    id: "old-bed",
    companionId: "codex-forge",
    spec: { kind: "macro", skillId: "life.craft-and-place-bed" },
  }, "codex-forge", existing), false);
  assert.equal(isNewBedChatTask({
    id: "new-bed",
    companionId: "other",
    spec: { kind: "macro", skillId: "life.craft-and-place-bed" },
  }, "codex-forge", existing), false);
});

test("bed sleep smoke assigns the built-in bed macro and an ordinary sleep task", () => {
  assert.deepEqual(taskSpecs(), {
    bed: {
      kind: "macro",
      skillId: "life.craft-and-place-bed",
      arguments: {},
      materialMode: "survival",
      requestedBy: "live-bed-sleep-smoke",
      note: "Reversible missing wood and wool dependency chain with physical home bed placement",
    },
    sleep: {
      kind: "sleep",
      radius: 32,
      requestedBy: "live-bed-sleep-smoke",
      note: "Reversible physical sleep and leave-bed acceptance",
    },
  });
});

test("fixture acknowledgements are exact, monotonic, and suite-bound", () => {
  assert.equal(fixturePrefix("setup"), "bed-sleep:setup|");
  assert.equal(fixturePrefix("inspect"), "bed-sleep:i|");
  assert.equal(fixturePrefix("prepare-night"), "bed-sleep:night|");
  assert.equal(fixturePrefix("wake-day"), "bed-sleep:day|");
  assert.equal(fixturePrefix("cleanup"), "bed-sleep:cleanup|");
  assert.throws(() => fixturePrefix("command"));
  const snapshot = {
    liveFixtureAck: { sequence: 8, suite: "bed-sleep", mode: "inspect", status: inspection(craftedValues) },
  };
  assert.equal(fixtureAcknowledgement(snapshot, 7, "inspect"), snapshot.liveFixtureAck.status);
  assert.equal(fixtureAcknowledgement(snapshot, 8, "inspect"), null);
  assert.equal(fixtureAcknowledgement(snapshot, 7, "cleanup"), null);
});

test("initial inspection proves wood, wool, tools, table, and bed are absent", () => {
  assert.deepEqual(parseSetupStatus("bed-sleep:setup|home=-10,96,24"), {
    home: { x: -10, y: 96, z: 24 },
  });
  const parsed = validateInitialInspection(parseInspection(inspection(initialValues, "none")));
  assert.equal(parsed.iron, 2);
  assert.equal(parsed.bed, null);
  const withWool = [...initialValues];
  withWool[4] = 1;
  assert.throws(() => validateInitialInspection(parseInspection(inspection(withWool, "none"))));
  assert.throws(() => parseInspection("bed-sleep:i|1,2|bed=none"));
});

test("crafted inspection requires physical dependency and home placement proof", () => {
  const parsed = validateCraftedInspection(parseInspection(inspection(craftedValues)));
  assert.equal(parsed.logBreaks, 2);
  assert.equal(parsed.sheepSheared, 2);
  assert.equal(parsed.bedPair, 1);
  const noShears = [...craftedValues];
  noShears[14] = 0;
  assert.throws(() => validateCraftedInspection(parseInspection(inspection(noShears))));
  const farFromHome = [...craftedValues];
  farFromHome[12] = 201;
  assert.throws(() => validateCraftedInspection(parseInspection(inspection(farFromHome))));
});

test("night, sleeping, and leave-bed phases are independently proven", () => {
  const night = [...craftedValues];
  night[21] = 0;
  assert.equal(validateNightInspection(parseInspection(inspection(night))).day, 0);

  const sleeping = [...night];
  sleeping[18] = 1;
  sleeping[19] = 1;
  assert.equal(validateSleepingInspection(parseInspection(inspection(sleeping))).sleeping, 1);

  const awake = [...sleeping];
  awake[19] = 0;
  awake[20] = 1;
  awake[21] = 1;
  assert.equal(validateAwakeInspection(parseInspection(inspection(awake))).leftBed, 1);
  assert.throws(() => validateAwakeInspection(parseInspection(inspection(sleeping))));
});

test("cleanup requires complete actor, world, respawn, block, and entity restoration", () => {
  assert.deepEqual(validateCleanup(parseCleanupStatus("bed-sleep:cleanup|r=1,1,1,1,1,1,1")), {
    none: false,
    player: 1,
    npc: 1,
    time: 1,
    weather: 1,
    respawn: 1,
    blocks: 1,
    entities: 1,
  });
  assert.deepEqual(validateCleanup(parseCleanupStatus("bed-sleep:cleanup|none"), true), { none: true });
  assert.throws(() => validateCleanup(parseCleanupStatus("bed-sleep:cleanup|none")));
  assert.throws(() => validateCleanup(parseCleanupStatus("bed-sleep:cleanup|r=1,1,1,1,1,0,1")));
});
