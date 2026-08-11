import assert from "node:assert/strict";
import test from "node:test";

import {
  craftTaskSpec,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  fixtureRetryable,
  loopbackBase,
  parseCheckpointStatus,
  parseCli,
  parseInspection,
  parseSetupStatus,
  validateCheckpoint,
  validateCleanupStatus,
  validateFinalInspection,
  validateInitialInspection,
} from "./live-craft-chain-smoke.mjs";

test("craft chain fixture acknowledgement uses its typed monotonic channel", () => {
  const current = {
    status: "正在采集依赖材料",
    liveFixtureAck: {
      sequence: 42,
      suite: "craft-chain",
      mode: "checkpoint",
      status: "craft-chain-fixture:checkpoint same=1,depth=2,bytes=481",
    },
  };
  assert.equal(
    fixtureAcknowledgement(current, 41, "checkpoint"),
    "craft-chain-fixture:checkpoint same=1,depth=2,bytes=481",
  );
  assert.equal(fixtureAcknowledgement(current, 42, "checkpoint"), null);
  assert.equal(fixtureAcknowledgement(current, 41, "inspect"), null);
});

const inspectionStatus = (values) => `craft-chain-fixture:i=${values.join(",")}`;
const INITIAL_VALUES = [3, ...Array(25).fill(0), 1, 1, 0];
const FINAL_VALUES = [0, 1, 2, 2, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 4, 8, 1, 1, 1, 1, 1, 2, 1, 1, 1, 0];
const INITIAL = inspectionStatus(INITIAL_VALUES);
const FINAL = inspectionStatus(FINAL_VALUES);

test("craft chain smoke is dry-run by default and loopback-only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.10:8765"));
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).waitMs, 60_000);
  assert.throws(() => parseCli(["--wait-seconds=10"]));
});

test("craft chain assigns one ordinary iron pickaxe craft with owner delivery", () => {
  assert.deepEqual(craftTaskSpec(), {
    kind: "craft",
    itemId: "minecraft:iron_pickaxe",
    count: 1,
    deliverTo: "owner",
    requestedBy: "live-craft-chain-smoke",
    note: "Reversible survival dependency chain and persistent checkpoint acceptance",
  });
});

test("fixture modes are fixed and cannot carry command text", () => {
  assert.equal(fixtureExpectedPrefix("setup"), "craft-chain-fixture:setup raw=3 origin=");
  assert.equal(fixtureExpectedPrefix("inspect"), "craft-chain-fixture:i=");
  assert.equal(fixtureExpectedPrefix("checkpoint"), "craft-chain-fixture:checkpoint same=");
  assert.equal(fixtureExpectedPrefix("cleanup"), "craft-chain-fixture:cleanup ");
  assert.equal(fixtureRetryable("inspect"), true);
  assert.equal(fixtureRetryable("checkpoint"), true);
  assert.equal(fixtureRetryable("cleanup"), true);
  assert.equal(fixtureRetryable("setup"), false);
  assert.throws(() => fixtureExpectedPrefix("execute"));
});

test("setup and initial inspection prove all ingredients except raw iron are absent", () => {
  assert.deepEqual(parseSetupStatus("craft-chain-fixture:setup raw=3 origin=-10,96,24"), {
    raw: 3,
    origin: { x: -10, y: 96, z: 24 },
  });
  assert.equal(validateInitialInspection(parseInspection(INITIAL)).raw, 3);
  const withLogs = [...INITIAL_VALUES];
  withLogs[1] = 1;
  assert.throws(() => validateInitialInspection(parseInspection(inspectionStatus(withLogs))));
  assert.throws(() => parseSetupStatus("craft-chain-fixture:setup raw=4 origin=-10,96,24"));
});

test("checkpoint parsing requires a non-empty exact scheduler round trip", () => {
  assert.deepEqual(validateCheckpoint(parseCheckpointStatus(
    "craft-chain-fixture:checkpoint same=1,depth=2,bytes=481",
  )), { same: 1, depth: 2, bytes: 481 });
  assert.throws(() => validateCheckpoint(parseCheckpointStatus(
    "craft-chain-fixture:checkpoint same=0,depth=2,bytes=481",
  )));
  assert.throws(() => parseCheckpointStatus(
    "craft-chain-fixture:checkpoint same=1,depth=2,bytes=481 extra",
  ));
});

test("final inspection requires every physical and persistence proof", () => {
  const parsed = validateFinalInspection(parseInspection(FINAL));
  assert.equal(parsed.stoneBreaks, 8);
  assert.equal(parsed.delivered, 1);
  assert.equal(parsed.gold, 1);
  assert.equal(parsed.diamond, 1);
  const withoutLit = [...FINAL_VALUES];
  withoutLit[10] = 0;
  assert.throws(() => validateFinalInspection(parseInspection(inspectionStatus(withoutLit))));
  const withoutRoundtrip = [...FINAL_VALUES];
  withoutRoundtrip[22] = 0;
  assert.throws(() => validateFinalInspection(parseInspection(inspectionStatus(withoutRoundtrip))));
  assert.throws(() => parseInspection(`${FINAL},1`));
});

test("cleanup accepts only explicit restored or already-clean acknowledgements", () => {
  assert.equal(validateCleanupStatus("craft-chain-fixture:cleanup restored"), "craft-chain-fixture:cleanup restored");
  assert.equal(validateCleanupStatus("craft-chain-fixture:cleanup none"), "craft-chain-fixture:cleanup none");
  assert.throws(() => validateCleanupStatus("following"));
});
