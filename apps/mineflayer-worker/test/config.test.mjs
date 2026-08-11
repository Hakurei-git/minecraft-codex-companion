import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { loadWorkerConfig } from "../src/config.mjs";

const managedKeys = [
  "MC_BOTS_CONFIG",
  "MC_BOT_NAMES",
  "MC_COMPANION_URL",
  "MC_BRIDGE_TOKEN",
  "MC_SERVER_HOST",
  "MC_SERVER_PORT",
  "MC_SERVER_VERSION",
  "MC_BOT_AUTH",
  "MC_OWNER_NAME",
  "MC_BOT_PROFILES_DIR",
];
const originalEnvironment = new Map(managedKeys.map((key) => [key, process.env[key]]));

function resetEnvironment() {
  for (const key of managedKeys) delete process.env[key];
  process.env.MC_BRIDGE_TOKEN = "0123456789abcdef0123456789abcdef";
}

afterEach(() => {
  for (const [key, value] of originalEnvironment) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
});

test("creates one offline chat leader from environment defaults", () => {
  resetEnvironment();
  const config = loadWorkerConfig();
  assert.equal(config.bots.length, 1);
  assert.equal(config.bots[0].id, "worker-1");
  assert.equal(config.bots[0].username, "CodexWorker1");
  assert.equal(config.bots[0].chatLeader, true);
  assert.equal(config.server.version, "1.21.1");
});

test("caps environment workers at three and assigns one chat leader", () => {
  resetEnvironment();
  process.env.MC_BOT_NAMES = "Builder,Guard,Farmer,Ignored";
  const config = loadWorkerConfig();
  assert.deepEqual(config.bots.map((bot) => bot.username), ["Builder", "Guard", "Farmer"]);
  assert.deepEqual(config.bots.map((bot) => bot.chatLeader), [true, false, false]);
});

test("rejects duplicate worker usernames", () => {
  resetEnvironment();
  process.env.MC_BOT_NAMES = "Worker,worker";
  assert.throws(() => loadWorkerConfig(), /must be unique/);
});
