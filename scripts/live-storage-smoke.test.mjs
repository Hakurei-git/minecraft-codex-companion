import assert from "node:assert/strict";
import test from "node:test";

import {
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  loopbackBase,
  parseCli,
  parseInspection,
  validateFinalCleanup,
  validateInspection,
} from "./live-storage-smoke.mjs";

test("storage fixture acknowledgement is suite-, mode-, and sequence-bound", () => {
  const current = {
    status: "原地等待",
    liveFixtureAck: {
      sequence: 12,
      suite: "storage",
      mode: "cleanup",
      status: "storage-fixture:cleanup restored",
    },
  };
  assert.equal(
    fixtureAcknowledgement(current, 11, "cleanup"),
    "storage-fixture:cleanup restored",
  );
  assert.equal(fixtureAcknowledgement(current, 12, "cleanup"), null);
  assert.equal(fixtureAcknowledgement(current, 11, "setup-retrieve"), null);
  assert.equal(fixtureAcknowledgement({
    ...current,
    liveFixtureAck: { ...current.liveFixtureAck, suite: "other" },
  }, 11, "cleanup"), null);
});

test("storage smoke requires explicit setup and inspection acknowledgements", () => {
  assert.equal(fixtureExpectedPrefix("setup-expand"), "storage-fixture:setup scenario=expand");
  assert.equal(fixtureExpectedPrefix("inspect-expand"), "storage-fixture:expand ");
  assert.equal(fixtureExpectedPrefix("setup-craft-expand"), "storage-fixture:setup scenario=craft-expand");
  assert.equal(fixtureExpectedPrefix("inspect-craft-expand"), "storage-fixture:craft-expand|");
  assert.equal(fixtureExpectedPrefix("cleanup"), "");
});

test("storage smoke accepts loopback control URLs only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.throws(() => loopbackBase("https://example.com"));
  assert.throws(() => loopbackBase("http://192.0.2.10:8765"));
});

test("storage smoke parses all fixed inspection formats", () => {
  assert.deepEqual(
    parseInspection("storage-fixture:retrieve home=8,npc=0,player=0,world=0,near=0,containers=2", "retrieve"),
    { home: 8, npc: 0, player: 0, world: 0, near: 0, containers: 2 },
  );
  assert.deepEqual(
    parseInspection("storage-fixture:organize homeSurplus=4,npcSurplus=0,npcFood=4,homeFood=0,containers=1", "organize"),
    { homeSurplus: 4, npcSurplus: 0, npcFood: 4, homeFood: 0, containers: 1 },
  );
  assert.deepEqual(
    parseInspection("storage-fixture:expand homeFiller=1728,homeSurplus=4,npc=0,expanded=1", "expand"),
    { homeFiller: 1728, homeSurplus: 4, npc: 0, expanded: 1 },
  );
  assert.deepEqual(
    parseInspection("storage-fixture:craft-expand|hf=1728,hs=4,nf=0,nl=0,np=0,nt=0,nc=0,e=1,t=1,tp=1,cp=1,d=0,u=0", "craft-expand"),
    {
      homeFiller: 1728, homeSurplus: 4, npcFixture: 0, npcLogs: 0, npcPlanks: 0,
      npcTables: 0, npcChests: 0, expanded: 1, tables: 1, tablePlacements: 1,
      chestPlacements: 1, direct: 0, unknown: 0,
    },
  );
  assert.throws(() => parseInspection("storage-fixture:expand homeFiller=0,homeSurplus=0,npc=0,expanded=0", "unknown"));
});

test("storage smoke validates reversible initial and final invariants", () => {
  assert.doesNotThrow(() => validateInspection(
    { home: 8, npc: 0, player: 0, world: 0, near: 0, containers: 2 },
    "retrieve",
    "initial",
  ));
  assert.doesNotThrow(() => validateInspection(
    { homeFiller: 1728, homeSurplus: 4, npc: 0, expanded: 1 },
    "expand",
    "final",
  ));
  assert.doesNotThrow(() => validateInspection(
    {
      homeFiller: 1728, homeSurplus: 4, npcFixture: 0, npcLogs: 0, npcPlanks: 0,
      npcTables: 0, npcChests: 0, expanded: 1, tables: 1, tablePlacements: 1,
      chestPlacements: 1, direct: 0, unknown: 0,
    },
    "craft-expand",
    "final",
  ));
  assert.throws(() => validateInspection(
    { homeSurplus: 0, npcSurplus: 4, npcFood: 0, homeFood: 4, containers: 1 },
    "organize",
    "final",
  ));
  assert.throws(() => validateInspection({}, "unknown", "initial"));
});

test("storage smoke exposes only audited scenarios", () => {
  assert.deepEqual(parseCli(["--scenario=retrieve", "--wait-seconds=60"]).scenarios, ["retrieve"]);
  assert.deepEqual(parseCli([]).scenarios, ["retrieve", "organize", "expand", "craft-expand"]);
  assert.throws(() => parseCli(["--scenario=arbitrary"]));
});

test("storage smoke requires a restored cleanup only after setup was acknowledged", () => {
  assert.equal(validateFinalCleanup("storage-fixture:cleanup none", false), "storage-fixture:cleanup none");
  assert.equal(validateFinalCleanup("storage-fixture:cleanup restored", false), "storage-fixture:cleanup restored");
  assert.equal(validateFinalCleanup("storage-fixture:cleanup restored", true), "storage-fixture:cleanup restored");
  assert.throws(() => validateFinalCleanup("storage-fixture:cleanup none", true));
});
