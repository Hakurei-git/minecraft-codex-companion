import assert from "node:assert/strict";
import test from "node:test";

import {
  fixtureAcknowledgement,
  isNpcBuiltPen,
  isUnbuiltPreparation,
  parseInspection,
  RANCH_ACTOR_EVIDENCE,
  waitForTask,
} from "./live-ranch-smoke.mjs";

test("ranch fixture acknowledgement ignores transient display status", () => {
  const current = {
    status: "跟随待命",
    liveFixtureAck: {
      sequence: 9,
      suite: "ranch",
      mode: "cleanup",
      status: "ranch-fixture:cleanup restored",
    },
  };
  assert.equal(fixtureAcknowledgement(current, 8, "cleanup"), "ranch-fixture:cleanup restored");
  assert.equal(fixtureAcknowledgement(current, 9, "cleanup"), null);
  assert.equal(fixtureAcknowledgement(current, 8, "inspect"), null);
});

test("ranch timeout reports the last task state", async () => {
  await assert.rejects(
    waitForTask(new URL("http://127.0.0.1:8765/"), {
      id: "ranch-timeout",
      status: "running",
      progress: 0.375,
      message: "still moving through gate",
    }, 0),
    /timed out at 0\.375: still moving through gate/u,
  );
});

test("ranch inspection distinguishes an unbuilt fixture from an NPC-built pen", () => {
  const preparation = parseInspection(
    "ranch-fixture:adults=2,babies=0,inside=0,outside=2,built=0,blocks=0,placements=0,gate=missing",
  );
  assert.equal(isUnbuiltPreparation(preparation), true);
  assert.equal(isNpcBuiltPen(preparation), false);

  const completed = parseInspection(
    "ranch-fixture:adults=2,babies=1,inside=3,outside=0,built=1,blocks=32,placements=32,gate=closed",
  );
  assert.equal(isUnbuiltPreparation(completed), false);
  assert.equal(isNpcBuiltPen(completed), true);
  assert.deepEqual(RANCH_ACTOR_EVIDENCE, {
    actor: "ai-npc",
    playerGameplayAssistanceUsed: false,
  });
});

test("ranch inspection rejects incomplete construction evidence", () => {
  const partial = parseInspection(
    "ranch-fixture:adults=2,babies=0,inside=0,outside=2,built=1,blocks=31,placements=31,gate=open",
  );
  assert.equal(isUnbuiltPreparation(partial), false);
  assert.equal(isNpcBuiltPen(partial), false);
  assert.equal(isUnbuiltPreparation(parseInspection(
    "ranch-fixture:adults=2,babies=0,inside=0,outside=2,built=0,blocks=1,placements=0,gate=missing",
  )), false);
  assert.throws(
    () => parseInspection("ranch-fixture:adults=2,babies=0,inside=0,outside=2,gate=closed"),
    /Unexpected ranch inspection status/u,
  );
});
