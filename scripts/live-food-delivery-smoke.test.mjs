import assert from "node:assert/strict";
import test from "node:test";

import {
  loopbackBase,
  parseCli,
  parseInspection,
  validateInitialInspection,
} from "./live-food-delivery-smoke.mjs";

test("food delivery smoke accepts loopback control URLs only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://example.com"));
  assert.throws(() => loopbackBase("http://192.0.2.10:8765"));
});

test("food delivery smoke parses physical player delivery inspection", () => {
  assert.deepEqual(parseInspection("food-fixture:player=3,npc=0,world=5,near=5", "player"), {
    player: 3,
    npc: 0,
    world: 5,
    near: 5,
  });
});

test("food delivery smoke parses home storage inspection", () => {
  assert.deepEqual(parseInspection("food-fixture:home=8,npc=0,containers=1", "home-storage"), {
    home: 8,
    npc: 0,
    containers: 1,
  });
  assert.throws(() => parseInspection("food-fixture:player=8,npc=0,world=0,near=0", "home-storage"));
});

test("food delivery smoke exposes only the two audited destinations", () => {
  assert.equal(parseCli([]).destination, "player");
  assert.equal(parseCli(["--destination=home-storage", "--wait-seconds=30"]).destination, "home-storage");
  assert.throws(() => parseCli(["--destination=arbitrary"]), /player or home-storage/u);
});

test("food delivery smoke requires an exact reversible fixture baseline", () => {
  assert.deepEqual(validateInitialInspection({ home: 0, npc: 8, containers: 0 }, "home-storage"), {
    home: 0,
    npc: 8,
    containers: 0,
  });
  assert.deepEqual(validateInitialInspection({ player: 0, npc: 8, world: 0, near: 0 }, "player"), {
    player: 0,
    npc: 8,
    world: 0,
    near: 0,
  });
  assert.throws(() => validateInitialInspection({ home: 1, npc: 7, containers: 1 }, "home-storage"));
  assert.throws(() => validateInitialInspection({ player: 0, npc: 7, world: 1, near: 1 }, "player"));
});
