import assert from "node:assert/strict";
import test from "node:test";

import {
  buildPlanDraft,
  buildTaskSpec,
  fixtureAcknowledgement,
  fixtureExpectedPrefix,
  fixtureRetryable,
  loopbackBase,
  parseCli,
  parseCatalogEntry,
  parseCatalogSummary,
  parseInspection,
  parseSetupStatus,
  validateCleanupStatus,
  validateInspection,
} from "./live-build-palette-smoke.mjs";

test("build palette smoke accepts loopback HTTP URLs only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.10:8765"));
});

test("build palette smoke defaults to a dry run of both audited scenarios", () => {
  assert.deepEqual(parseCli([]).scenarios, ["mixed", "chain", "matrix"]);
  assert.equal(parseCli([]).apply, false);
  assert.deepEqual(parseCli(["--apply", "--scenario=chain", "--wait-seconds=60"]).scenarios, ["chain"]);
  assert.deepEqual(parseCli(["--scenario=matrix"]).scenarios, ["matrix"]);
  assert.equal(parseCli(["--apply"]).apply, true);
  assert.throws(() => parseCli(["--scenario=other"]), /mixed, chain, matrix/u);
});

test("registry family catalog parsing is strict and loopback-only", () => {
  assert.deepEqual(parseCatalogSummary("build-fixture:catalog count=17,supported=15"), {
    count: 17,
    supported: 15,
  });
  assert.deepEqual(parseCatalogEntry(
    "build-fixture:catalog index=3,count=17,category=wood,base=minecraft:dark_oak_planks,source=minecraft:dark_oak_log,supported=1,reason=none,blocks=minecraft:dark_oak_planks|minecraft:dark_oak_stairs|minecraft:dark_oak_slab|minecraft:dark_oak_fence|minecraft:dark_oak_trapdoor|minecraft:dark_oak_pressure_plate",
    3,
    17,
  ), {
    index: 3,
    count: 17,
    category: "wood",
    baseId: "minecraft:dark_oak_planks",
    sourceId: "minecraft:dark_oak_log",
    supported: true,
    skipReason: "",
    blockIds: [
      "minecraft:dark_oak_planks",
      "minecraft:dark_oak_stairs",
      "minecraft:dark_oak_slab",
      "minecraft:dark_oak_fence",
      "minecraft:dark_oak_trapdoor",
      "minecraft:dark_oak_pressure_plate",
    ],
  });
  assert.throws(() => parseCatalogEntry(
    "build-fixture:catalog index=3,count=17,category=wood,base=evil:payload,source=none,supported=1,reason=none,blocks=evil:payload|bad injection",
    3,
    17,
  ));
  assert.throws(() => parseCatalogSummary("build-fixture:catalog count=2,supported=3"));
});

test("build fixture acknowledgements are scenario-bound and strictly parsed", () => {
  assert.equal(fixtureExpectedPrefix("setup-mixed"), "build-fixture:setup scenario=mixed origin=");
  assert.equal(fixtureExpectedPrefix("inspect-chain"), "build-fixture:chain ");
  assert.equal(fixtureRetryable("inspect-mixed"), true);
  assert.equal(fixtureRetryable("cleanup"), true);
  assert.equal(fixtureRetryable("setup-chain"), false);
  assert.deepEqual(parseSetupStatus("build-fixture:setup scenario=mixed origin=-10,64,8", "mixed"), {
    x: -10,
    y: 64,
    z: 8,
  });
  assert.throws(() => parseSetupStatus("build-fixture:setup scenario=chain origin=-10,64,8", "mixed"));
  assert.throws(() => parseSetupStatus("build-fixture:setup scenario=mixed origin=1.5,64,8", "mixed"));
});

test("build fixture acknowledgement is isolated from mutable NPC status", () => {
  const snapshot = {
    status: "following",
    liveFixtureAck: {
      sequence: 9,
      suite: "build-palette",
      mode: "inspect-chain",
      status: "build-fixture:chain expected=6,matching=6,wrong=0",
    },
  };
  assert.equal(
    fixtureAcknowledgement(snapshot, 8, "inspect-chain"),
    "build-fixture:chain expected=6,matching=6,wrong=0",
  );
  assert.equal(fixtureAcknowledgement(snapshot, 9, "inspect-chain"), null);
  assert.equal(fixtureAcknowledgement(snapshot, 8, "inspect-mixed"), null);
  assert.equal(fixtureAcknowledgement({
    ...snapshot,
    liveFixtureAck: { ...snapshot.liveFixtureAck, suite: "other" },
  }, 8, "inspect-chain"), null);
});

test("build plan contains the six fixed component variants at fixed offsets", () => {
  const draft = buildPlanDraft({ x: 100, y: 70, z: -20 }, "mixed");
  assert.deepEqual(draft.origin, { x: 100, y: 70, z: -20 });
  assert.deepEqual(draft.blocks.map(({ blockId, position }) => ({ blockId, position })), [
    { blockId: "minecraft:oak_planks", position: { x: 0, y: 0, z: 0 } },
    { blockId: "minecraft:oak_stairs", position: { x: 2, y: 0, z: 0 } },
    { blockId: "minecraft:oak_slab", position: { x: 4, y: 0, z: 0 } },
    { blockId: "minecraft:oak_fence", position: { x: 0, y: 0, z: 2 } },
    { blockId: "minecraft:oak_trapdoor", position: { x: 2, y: 0, z: 2 } },
    { blockId: "minecraft:oak_pressure_plate", position: { x: 4, y: 0, z: 2 } },
  ]);
});

test("build tasks lock the requested inventory palette policy", () => {
  assert.deepEqual(buildTaskSpec("11111111-1111-1111-1111-111111111111", "mixed").materialPreference, {
    source: "inventory",
    allowMixed: true,
  });
  assert.deepEqual(buildTaskSpec("11111111-1111-1111-1111-111111111111", "chain").materialPreference, {
    source: "inventory",
    preferredBlockId: "minecraft:dark_oak_planks",
    allowMixed: false,
  });
  const family = {
    category: "masonry",
    baseId: "minecraft:deepslate_bricks",
    blockIds: [
      "minecraft:deepslate_bricks",
      "minecraft:deepslate_brick_stairs",
      "minecraft:deepslate_brick_slab",
    ],
  };
  assert.deepEqual(
    buildTaskSpec("11111111-1111-1111-1111-111111111111", "family-9", family).materialPreference,
    { source: "inventory", preferredBlockId: "minecraft:deepslate_bricks", allowMixed: false },
  );
  assert.equal(buildPlanDraft({ x: 1, y: 2, z: 3 }, "family-9", family).blocks.length, 3);
});

test("build inspection and cleanup require exact reversible invariants", () => {
  assert.deepEqual(validateInspection(parseInspection(
    "build-fixture:mixed expected=6,matching=6,wrong=0",
    "mixed",
  )), { expected: 6, matching: 6, wrong: 0 });
  assert.throws(() => validateInspection(parseInspection(
    "build-fixture:chain expected=6,matching=5,wrong=1",
    "chain",
  )));
  assert.throws(() => parseInspection("build-fixture:mixed expected=6,matching=6,wrong=0 extra", "mixed"));
  assert.equal(validateCleanupStatus("build-fixture:cleanup restored"), "build-fixture:cleanup restored");
  assert.equal(validateCleanupStatus("build-fixture:cleanup none"), "build-fixture:cleanup none");
  assert.throws(() => validateCleanupStatus("following"));
});
