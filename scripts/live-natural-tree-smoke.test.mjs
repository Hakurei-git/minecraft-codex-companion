import assert from "node:assert/strict";
import test from "node:test";

import {
  fixtureExpectedPrefix,
  fixtureRetryable,
  gatherTaskSpec,
  loopbackBase,
  parseCli,
  parseInspection,
  validateCleanupStatus,
  validateInspection,
  validateSetupInspection,
} from "./live-natural-tree-smoke.mjs";

test("natural tree smoke accepts loopback HTTP URLs only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://198.51.100.20:8765"));
});

test("natural tree smoke is dry-run by default and bounds its wait", () => {
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).apply, true);
  assert.equal(parseCli(["--wait-seconds=60"]).waitMs, 60_000);
  assert.throws(() => parseCli(["--wait-seconds=1"]));
});

test("natural tree smoke assigns the exact survival walking gather task", () => {
  assert.deepEqual(gatherTaskSpec(), {
    kind: "gather",
    itemId: "#minecraft:logs",
    count: 10,
    movement: "walk",
    requestedBy: "live-natural-tree-smoke",
    note: "Local reversible natural-tree reach and home-protection acceptance",
  });
});

test("natural tree fixture uses explicit acknowledgement prefixes", () => {
  assert.equal(fixtureExpectedPrefix("setup"), "tree-fixture:setup");
  assert.equal(fixtureExpectedPrefix("inspect"), "tree-fixture:protected=");
  assert.equal(fixtureExpectedPrefix("cleanup"), "tree-fixture:cleanup ");
  assert.equal(fixtureRetryable("inspect"), true);
  assert.equal(fixtureRetryable("cleanup"), true);
  assert.equal(fixtureRetryable("setup"), false);
  assert.throws(() => fixtureExpectedPrefix("spawn"));
});

test("natural tree inspection and cleanup require exact reversible invariants", () => {
  const successfulStatus = "tree-fixture:protected=4,remote=0,npcLogs=10,boundaryProtected=2,artificial=4,breaks=10,rb=0,los=0,max=4500,sync=0";
  assert.ok(successfulStatus.length <= 120);
  assert.deepEqual(validateInspection(parseInspection(successfulStatus)), {
    protected: 4,
    remote: 0,
    npcLogs: 10,
    boundaryProtected: 2,
    artificial: 4,
    breaks: 10,
    remoteBreaks: 0,
    losViolations: 0,
    maxTouchMilli: 4500,
    syncViolations: 0,
  });
  assert.deepEqual(validateSetupInspection(parseInspection(
    "tree-fixture:protected=4,remote=4,npcLogs=0,boundaryProtected=2,artificial=4,breaks=0,rb=0,los=0,max=0,sync=0",
  )), {
    protected: 4,
    remote: 4,
    npcLogs: 0,
    boundaryProtected: 2,
    artificial: 4,
    breaks: 0,
    remoteBreaks: 0,
    losViolations: 0,
    maxTouchMilli: 0,
    syncViolations: 0,
  });
  assert.throws(() => validateSetupInspection(parseInspection(
    "tree-fixture:protected=4,remote=4,npcLogs=0,boundaryProtected=1,artificial=4,breaks=0,rb=0,los=0,max=0,sync=0",
  )));
  assert.throws(() => validateInspection(parseInspection(
    "tree-fixture:protected=4,remote=0,npcLogs=10,boundaryProtected=2,artificial=4,breaks=10,rb=1,los=0,max=4000,sync=0",
  )));
  assert.throws(() => validateInspection(parseInspection(
    "tree-fixture:protected=4,remote=0,npcLogs=10,boundaryProtected=2,artificial=4,breaks=10,rb=0,los=0,max=4501,sync=0",
  )));
  assert.throws(() => validateInspection(parseInspection(
    "tree-fixture:protected=4,remote=0,npcLogs=10,boundaryProtected=2,artificial=4,breaks=10,rb=0,los=0,max=4000,sync=1",
  )));
  assert.throws(() => parseInspection(
    "tree-fixture:protected=4,remote=0,npcLogs=10,boundaryProtected=2,artificial=4,breaks=10,rb=0,los=0,max=4000,sync=0 extra",
  ));
  assert.equal(validateCleanupStatus("tree-fixture:cleanup restored"), "tree-fixture:cleanup restored");
  assert.equal(validateCleanupStatus("tree-fixture:cleanup none"), "tree-fixture:cleanup none");
  assert.throws(() => validateCleanupStatus("following"));
});
