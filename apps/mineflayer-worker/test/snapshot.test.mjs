import assert from "node:assert/strict";
import { test } from "node:test";
import { worldSnapshotSchema } from "@mc/protocol";
import { createCompanion, createSnapshot } from "../src/snapshot.mjs";

function position(x, y, z) {
  return {
    x,
    y,
    z,
    distanceTo(other) {
      return Math.hypot(x - other.x, y - other.y, z - other.z);
    },
  };
}

function fakeBot() {
  const origin = position(10, 64, -4);
  const entities = Object.fromEntries(Array.from({ length: 70 }, (_, index) => [index + 2, {
    id: index + 2,
    uuid: `entity-${index + 2}`,
    type: index === 0 ? "player" : "mob",
    username: index === 0 ? "PlayerOne" : undefined,
    name: index === 0 ? "player" : "zombie",
    kind: index === 0 ? "" : "Hostile mobs",
    position: position(11 + index * 0.1, 64, -4),
    health: 20,
  }]));
  const bot = {
    username: "CodexWorker1",
    version: "1.21.1",
    entity: { position: origin, yaw: 1, pitch: 0, attributes: {} },
    entities,
    inventory: {
      slots: [{ name: "oak_log", displayName: "Oak Log", count: 8 }],
    },
    game: { gameMode: "survival", dimension: "minecraft:overworld" },
    health: 18,
    food: 17,
    oxygenLevel: 20,
    time: { timeOfDay: 6000 },
    thunderState: 0,
    isRaining: false,
  };
  entities[1] = bot.entity;
  return bot;
}

test("creates a protocol-valid bounded snapshot", () => {
  const bot = fakeBot();
  const config = {
    ownerName: "PlayerOne",
    observeRadius: 32,
    server: { host: "127.0.0.1", port: 25565, version: "1.21.1" },
  };
  const snapshot = createSnapshot(bot, config, "待命", 7);
  assert.equal(worldSnapshotSchema.parse(snapshot).sequence, 7);
  assert.equal(snapshot.nearbyEntities.length, 64);
  assert.equal(snapshot.nearbyEntities[0].disposition, "owner");
  assert.equal(snapshot.inventory[0].id, "minecraft:oak_log");

  const companion = createCompanion(bot, { id: "worker-1", username: bot.username }, config, snapshot);
  assert.equal(companion.backend, "mineflayer");
  assert.ok(companion.capabilities.includes("multi-bot"));
});
