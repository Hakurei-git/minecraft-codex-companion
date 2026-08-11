import assert from "node:assert/strict";
import test from "node:test";
import {
  REQUIRED_FORGE_CAPABILITIES,
  validateAntigravityBinding,
  validateLiveCompanion,
} from "./live-preflight.mjs";

function fixture() {
  return {
    connected: true,
    backend: "forge-npc",
    embodiment: "in-world-npc",
    capabilities: [...REQUIRED_FORGE_CAPABILITIES],
    snapshot: {
      health: 18,
      maxHealth: 20,
      food: 12,
      maxFood: 20,
      saturation: 3,
      exhaustion: 0.5,
      air: 300,
      maxAir: 300,
      armor: 6,
      absorption: 0,
      position: { x: 1, y: 64, z: 2 },
      npcEntityUuid: "123e4567-e89b-42d3-a456-426614174000",
      materialMode: "survival",
      naturalRegenerationEnabled: true,
      canNaturalRegenerate: true,
      automaticEating: false,
      stance: "follow",
      taskSchedulerLifecycle: "idle",
      taskQueue: [],
      inventory: [{
        id: "minecraft:bread",
        displayName: "Bread",
        count: 2,
        slot: 0,
        slotType: "backpack",
      }],
      effects: [],
      nearbyEntities: [],
      status: "following",
      homeState: {
        dimension: "minecraft:overworld",
        position: { x: 0, y: 64, z: 0 },
        temporary: false,
      },
    },
  };
}

test("accepts a complete Forge NPC live snapshot", () => {
  assert.deepEqual(validateLiveCompanion(fixture()), []);
});

test("reports missing player-equivalent state and capabilities", () => {
  const companion = fixture();
  companion.capabilities = companion.capabilities.filter((entry) => entry !== "dragon-care");
  delete companion.snapshot.homeState;
  delete companion.snapshot.automaticEating;
  const issues = validateLiveCompanion(companion);
  assert(issues.some((issue) => issue.includes("dragon-care")));
  assert(issues.some((issue) => issue.includes("home/respawn")));
  assert(issues.some((issue) => issue.includes("eating or regeneration")));
});

test("rejects simulator and worker companions for final live acceptance", () => {
  const companion = fixture();
  companion.embodiment = "remote-worker";
  assert(validateLiveCompanion(companion).some((issue) => issue.includes("Forge in-world NPC")));
});

test("accepts the Antigravity driver's configured bound conversation without hardcoding its title", () => {
  const status = {
    available: true,
    connected: true,
    conversationId: "local-conversation-id",
    conversationTitle: "User configured Minecraft conversation",
  };
  assert.equal(validateAntigravityBinding(status), true);
  assert.equal(validateAntigravityBinding({ ...status, conversationTitle: "Another task" }), true);
  assert.equal(validateAntigravityBinding({ ...status, conversationTitle: "   " }), false);
  assert.equal(validateAntigravityBinding({ ...status, conversationTitle: "Invalid\u0000title" }), false);
  assert.equal(validateAntigravityBinding({ ...status, conversationId: "" }), false);
  assert.equal(validateAntigravityBinding({ ...status, connected: false }), false);
});
