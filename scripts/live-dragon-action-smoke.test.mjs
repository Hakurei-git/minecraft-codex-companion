import test from "node:test";
import assert from "node:assert/strict";

import {
  fixtureAcknowledgement,
  fixturePrefix,
  fixtureIsRepeatable,
  loopbackBase,
  parseClearObstacleStatus,
  parseInspection,
  parseMods,
  parseObstacleStatus,
  parseOffset,
  parseSpawnStatus,
  validateBaseline,
  validateCleanupStatus,
  validateLanded,
  validateObstacleArrival,
  validateSharedRide,
  validateUninterruptedCombat,
} from "./live-dragon-action-smoke.mjs";

const BOOK = parseMods("bookofdragons")[0];
const SAINTS = parseMods("saintsdragons")[0];

function inspection(overrides = {}) {
  return {
    modId: "bookofdragons",
    targetId: "11111111-2222-3333-4444-555555555555",
    alive: 1,
    owned: 1,
    npcMounted: 0,
    playerMounted: 0,
    coRiding: 0,
    firstPlayer: 0,
    command: 2,
    flying: 0,
    onGround: 1,
    saddled: 1,
    seatLocked: 0,
    rideReady: 1,
    autopilot: 0,
    rootVehicleDragon: 0,
    beginCalled: 0,
    beginAccepted: 0,
    endCalled: 0,
    invalidated: 0,
    vehiclePacketSeen: 0,
    npcHealth: 20_000,
    npcFall: 0,
    dragonFall: 0,
    ownerDistance: 4_000,
    targetCount: 0,
    obstacleBlocks: 0,
    x: 10_500,
    y: 80_000,
    z: -4_500,
    ...overrides,
  };
}

const FLAG_NAMES = [
  "alive", "owned", "npcMounted", "playerMounted", "coRiding", "firstPlayer",
  "flying", "onGround", "saddled", "seatLocked", "rideReady", "autopilot",
  "rootVehicleDragon", "beginCalled", "beginAccepted", "endCalled", "invalidated",
  "vehiclePacketSeen",
];

function compactInspectionStatus(state) {
  const targetId = state.targetId.replaceAll("-", "");
  const flags = FLAG_NAMES.reduce((mask, name, index) => mask | (state[name] << index), 0);
  const values = [
    state.command, state.npcHealth, state.npcFall, state.dragonFall, state.ownerDistance,
    state.targetCount, state.obstacleBlocks, state.x, state.y, state.z,
  ].map((value) => value.toString(36));
  const mod = state.modId === "bookofdragons" ? "0" : "1";
  const payload = ["dragon:i", mod, targetId, flags.toString(36), ...values].join("|");
  let checksum = 0;
  for (let index = 0; index < payload.length; index += 1) {
    checksum = (checksum * 31 + payload.charCodeAt(index)) % 46_656;
  }
  return `${payload}|${checksum.toString(36).padStart(3, "0")}`;
}

test("dragon smoke is loopback-only and parses bounded options", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api").href, "http://127.0.0.1:8765/");
  assert.throws(() => loopbackBase("https://example.com"));
  assert.deepEqual(parseOffset("1,-2,3.5"), { x: 1, y: -2, z: 3.5 });
  assert.throws(() => parseOffset("1,2"));
  assert.deepEqual(parseMods("bookofdragons,saintsdragons").map((profile) => profile.modId), [
    "bookofdragons",
    "saintsdragons",
  ]);
  assert.throws(() => parseMods("unknownmod"));
});

test("fixture modes are fixed enumerations with scenario-specific acknowledgements", () => {
  assert.equal(fixturePrefix("spawn-book"), "dragon-fixture:spawn mod=bookofdragons,id=");
  assert.equal(fixturePrefix("inspect-saints"), "dragon:i|1|");
  assert.equal(fixturePrefix("stage-obstacle-book"), "dragon-fixture:obstacle mod=bookofdragons,target=");
  assert.equal(fixturePrefix("cleanup"), "dragon-fixture:cleanup restored=1,entities=");
  assert.throws(() => fixturePrefix("execute-command"));
  assert.equal(fixtureIsRepeatable("spawn-book"), true);
  assert.equal(fixtureIsRepeatable("inspect-saints"), true);
  assert.equal(fixtureIsRepeatable("clear-obstacle"), false);
  assert.equal(fixtureIsRepeatable("stage-obstacle-book"), false);
});

test("fixture acknowledgement survives ordinary NPC status replacement", () => {
  const snapshot = {
    status: "跟随待命",
    liveFixtureAck: {
      sequence: 8,
      suite: "dragon",
      mode: "stage-obstacle-book",
      status: "dragon-fixture:obstacle mod=bookofdragons,target=1.0:80.0:2.0,wallMaxX=-6,blocks=162",
    },
  };
  assert.equal(
    fixtureAcknowledgement(snapshot, 7, "stage-obstacle-book"),
    snapshot.liveFixtureAck.status,
  );
  assert.equal(fixtureAcknowledgement(snapshot, 8, "stage-obstacle-book"), null);
  assert.equal(fixtureAcknowledgement(snapshot, 7, "stage-obstacle-saints"), null);
});

test("spawn, obstacle and cleanup statuses are strict and mod-bound", () => {
  assert.deepEqual(parseSpawnStatus(
    "dragon-fixture:spawn mod=bookofdragons,id=11111111-2222-3333-4444-555555555555",
    "bookofdragons",
  ), {
    modId: "bookofdragons",
    targetId: "11111111-2222-3333-4444-555555555555",
  });
  assert.throws(() => parseSpawnStatus(
    "dragon-fixture:spawn mod=bookofdragons,id=11111111-2222-3333-4444-555555555555",
    "saintsdragons",
  ));
  assert.deepEqual(parseObstacleStatus(
    "dragon-fixture:obstacle mod=saintsdragons,target=24.5:96.0:-8.5,wallMaxX=9,blocks=420",
    "saintsdragons",
  ), {
    modId: "saintsdragons",
    target: { x: 24.5, y: 96, z: -8.5 },
    wallMaxX: 9,
    blocks: 420,
  });
  assert.equal(parseClearObstacleStatus("dragon-fixture:obstacle cleared=420"), 420);
  assert.deepEqual(validateCleanupStatus(
    "dragon-fixture:cleanup restored=1,entities=3,blocks=420",
  ), { entities: 3, blocks: 420 });
  assert.throws(() => validateCleanupStatus("dragon-fixture:cleanup entities=3"));
});

test("world-state inspection parser preserves every acceptance invariant", () => {
  const expected = inspection({
    npcMounted: 1,
    playerMounted: 1,
    coRiding: 1,
    firstPlayer: 1,
    rootVehicleDragon: 1,
    beginCalled: 1,
    beginAccepted: 1,
    endCalled: 1,
    invalidated: 0,
    vehiclePacketSeen: 1,
    flying: 1,
    onGround: 0,
    ownerDistance: 0,
    obstacleBlocks: 420,
    x: 24_500,
    y: 96_000,
    z: -8_500,
  });
  const status = compactInspectionStatus(expected);
  assert.ok(status.length <= 120, `inspection status length ${status.length} exceeded 120`);
  assert.deepEqual(parseInspection(status), expected);
  assert.throws(() => parseInspection(`${status},extra=1`));
  assert.throws(() => parseInspection(status.slice(0, -1)));
  const fields = status.split("|");
  fields[3] = (2 ** FLAG_NAMES.length).toString(36);
  assert.throws(() => parseInspection(fields.join("|")));
});

test("baseline, shared ride, terrain escape and landing reject false success", () => {
  assert.equal(validateBaseline(inspection(), BOOK).owned, 1);
  assert.throws(() => validateBaseline(inspection({ rideReady: 0 }), BOOK));

  const shared = inspection({
    npcMounted: 1,
    playerMounted: 1,
    coRiding: 1,
    firstPlayer: 1,
    rootVehicleDragon: 1,
    onGround: 0,
  });
  assert.equal(validateSharedRide(shared, BOOK).coRiding, 1);
  assert.equal(validateSharedRide({ ...shared, dragonFall: 171 }, BOOK).dragonFall, 171);
  assert.throws(() => validateSharedRide({ ...shared, npcFall: 1 }, BOOK));
  assert.throws(() => validateSharedRide({ ...shared, dragonFall: 1_501 }, BOOK));

  const obstacle = {
    modId: "bookofdragons",
    target: { x: 24.5, y: 96, z: -8.5 },
    wallMaxX: 9,
    blocks: 420,
  };
  const arrived = {
    ...shared,
    beginCalled: 1,
    beginAccepted: 1,
    endCalled: 1,
    vehiclePacketSeen: 1,
    flying: 1,
    onGround: 0,
    x: 24_500,
    y: 96_000,
    z: -8_500,
    obstacleBlocks: 420,
  };
  assert.equal(validateObstacleArrival(arrived, obstacle, BOOK).targetDistance, 0);
  assert.throws(() => validateObstacleArrival({ ...arrived, x: 8_000 }, obstacle, BOOK));
  assert.throws(() => validateObstacleArrival({ ...arrived, obstacleBlocks: 419 }, obstacle, BOOK));
  assert.throws(() => validateObstacleArrival({ ...arrived, beginAccepted: 0 }, obstacle, BOOK));
  assert.throws(() => validateObstacleArrival({ ...arrived, invalidated: 1 }, obstacle, BOOK));
  assert.throws(() => validateObstacleArrival({ ...arrived, vehiclePacketSeen: 0 }, obstacle, BOOK));
  assert.throws(() => validateObstacleArrival({ ...arrived, flying: 0, onGround: 1 }, obstacle, BOOK));

  const landed = {
    ...shared,
    beginCalled: 1,
    beginAccepted: 1,
    endCalled: 1,
    vehiclePacketSeen: 1,
    flying: 0,
    onGround: 1,
  };
  assert.equal(validateLanded(landed, BOOK).onGround, 1);
  assert.throws(() => validateLanded({ ...landed, flying: 1 }, BOOK));
  assert.throws(() => validateLanded({ ...landed, endCalled: 0 }, BOOK));
});

test("the two supported mod profiles retain different command semantics", () => {
  assert.equal(BOOK.followCommand, 2);
  assert.equal(BOOK.wanderCommand, 0);
  assert.equal(SAINTS.followCommand, 0);
  assert.equal(SAINTS.wanderCommand, 2);
});

test("combat acceptance rejects a hidden downed interruption", () => {
  const succeeded = { status: "succeeded", events: [{ status: "running" }, { status: "succeeded" }] };
  assert.equal(validateUninterruptedCombat(succeeded, SAINTS), succeeded);
  assert.throws(() => validateUninterruptedCombat({
    status: "succeeded",
    events: [{ status: "paused", message: "任务因 NPC 倒地而暂停，恢复后继续" }],
  }, SAINTS));
});
