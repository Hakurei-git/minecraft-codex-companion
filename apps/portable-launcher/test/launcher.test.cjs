"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const {
  beginAutomaticBridge,
  bindConfiguredAntigravity,
  bridgeTokenFingerprint,
  classifyMinecraftBridge,
  classifyServiceHealth,
  companionPrompt,
  configureBridge,
  createApiContext,
  defaultConfig,
  discoverAntigravityConfigPath,
  discoverHmclLauncherPath,
  discoverMinecraftRoot,
  getOrCreateInstallationId,
  installAntigravity,
  isPathInside,
  listInstances,
  loadConfig,
  mergeAntigravityConfig,
  mergeAntigravityPermissions,
  normalizeConfig,
  normalizePersona,
  resolveAntigravityPermissionConfigPath,
  recordedServiceIsManaged,
  saveConfig,
  splitArguments,
  validateCompanionName,
  validateNpcSkin,
  validateRuntimeConfig,
  validateAntigravityConversationTitle,
  validateVersionName,
  withBridgeStartupLock,
} = require("../src/launcher.cjs");
const { installClone, parseJavaTasklist, updateClone } = require("../src/instance-manager.cjs");

async function fixture() {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), "mc-codex-portable-"));
  const minecraftRoot = path.join(root, ".minecraft");
  const launcherPath = path.join(root, "Launcher.exe");
  await fsp.mkdir(path.join(minecraftRoot, "versions"), { recursive: true });
  await fsp.writeFile(launcherPath, "fixture", "utf8");
  return { root, minecraftRoot, launcherPath };
}

async function removeFixture(root) {
  await fsp.rm(root, { recursive: true, force: true, maxRetries: 8, retryDelay: 150 });
}

async function waitFor(check, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() >= deadline) throw new Error("Timed out waiting for launcher state");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

test("defaults are derived from the target environment", () => {
  const config = defaultConfig({ APPDATA: "R:\\Profile\\Roaming", USERPROFILE: "R:\\Profile" });
  assert.equal(config.minecraftRoot, path.join("R:\\Profile\\Roaming", ".minecraft"));
  assert.equal(config.launcherPath, "");
  assert.equal(config.playerName, "");
  assert.equal(config.companionName, "Codex");
  assert.equal(config.freeChatEnabled, false);
  assert.equal(config.actionMode, "stable");
  assert.equal(config.tokenBudget, 512);
  assert.equal(config.persona.mode, "inherit");
  assert.equal(config.npcSkinMode, "default");
  assert.equal(config.antigravityConversationTitle, "Execute Minecraft Woodcutting Task");
});

test("one-click preparation binds the configured Antigravity conversation without replacing inherited persona", async () => {
  const calls = [];
  const config = normalizeConfig({
    port: 8765,
    chatTarget: "antigravity-mcp",
    antigravityConversationTitle: "Existing Local Persona",
    persona: { mode: "inherit" },
  });
  const result = await bindConfiguredAntigravity(config, async (url, options) => {
    calls.push({ url, options });
    return { connected: true, conversationTitle: "Existing Local Persona [MC-4]" };
  });

  assert.deepEqual(calls, [{
    url: "http://127.0.0.1:8765/api/antigravity/bind",
    options: {
      method: "POST",
      body: { title: "Existing Local Persona" },
      timeout: 20_000,
    },
  }]);
  assert.deepEqual(result, {
    required: true,
    connected: true,
    conversationTitle: "Existing Local Persona [MC-4]",
    personaMode: "inherit",
  });
});

test("one-click preparation leaves non-Antigravity chat targets untouched", async () => {
  let called = false;
  const result = await bindConfiguredAntigravity(normalizeConfig({
    chatTarget: "active-provider",
  }), async () => {
    called = true;
    return {};
  });

  assert.equal(called, false);
  assert.deepEqual(result, { required: false, connected: false, skipped: true });

  const forced = await bindConfiguredAntigravity(normalizeConfig({
    chatTarget: "active-provider",
    antigravityConversationTitle: "Manual Binding",
  }), async () => {
    called = true;
    return { connected: true, conversationTitle: "Manual Binding" };
  }, true);
  assert.equal(called, true);
  assert.equal(forced.connected, true);
});

test("portable defaults discover HMCL, Minecraft, and Antigravity from bounded user locations", async (t) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), "mc-codex-discovery-"));
  t.after(() => removeFixture(root));
  const desktop = path.join(root, "Desktop");
  const appData = path.join(root, "AppData", "Roaming");
  const launcher = path.join(desktop, "HMCL-Portable.exe");
  const adjacentMinecraft = path.join(desktop, ".minecraft");
  const antigravity = path.join(root, ".gemini", "antigravity", "mcp_config.json");
  await fsp.mkdir(path.join(adjacentMinecraft, "versions"), { recursive: true });
  await fsp.mkdir(path.dirname(antigravity), { recursive: true });
  await fsp.writeFile(launcher, "fixture", "utf8");
  await fsp.writeFile(antigravity, "{}", "utf8");
  const environment = { USERPROFILE: root, APPDATA: appData };
  const config = defaultConfig(environment);

  assert.equal(discoverHmclLauncherPath(environment), launcher);
  assert.equal(discoverMinecraftRoot(environment, launcher), adjacentMinecraft);
  assert.equal(discoverAntigravityConfigPath(environment), antigravity);
  assert.equal(config.launcherPath, launcher);
  assert.equal(config.minecraftRoot, adjacentMinecraft);
  assert.equal(config.antigravityConfigPath, antigravity);
});

test("explicit discovery environment paths override inferred defaults", async (t) => {
  const root = await fsp.mkdtemp(path.join(os.tmpdir(), "mc-codex-explicit-discovery-"));
  t.after(() => removeFixture(root));
  const launcher = path.join(root, "selected-HMCL.jar");
  const minecraftRoot = path.join(root, "game-root");
  const antigravity = path.join(root, "agent", "mcp_config.json");
  await fsp.mkdir(path.join(minecraftRoot, "versions"), { recursive: true });
  await fsp.mkdir(path.dirname(antigravity), { recursive: true });
  await fsp.writeFile(launcher, "fixture", "utf8");
  await fsp.writeFile(antigravity, "{}", "utf8");
  const environment = {
    USERPROFILE: root,
    APPDATA: path.join(root, "roaming"),
    MC_HMCL_PATH: launcher,
    MC_MINECRAFT_ROOT: minecraftRoot,
    MC_ANTIGRAVITY_CONFIG_PATH: antigravity,
  };

  assert.equal(discoverHmclLauncherPath(environment), launcher);
  assert.equal(discoverMinecraftRoot(environment, launcher), minecraftRoot);
  assert.equal(discoverAntigravityConfigPath(environment), antigravity);
});

test("normalization ignores secrets and unknown fields", () => {
  const normalized = normalizeConfig({
    launcherPath: " launcher.exe ",
    apiKey: "must-not-persist",
    bridgeToken: "must-not-persist",
  });
  assert.equal(normalized.launcherPath, "launcher.exe");
  assert.equal(Object.hasOwn(normalized, "apiKey"), false);
  assert.equal(Object.hasOwn(normalized, "bridgeToken"), false);
  assert.equal(normalized.actionMode, "stable");
  assert.equal(normalized.tokenBudget, 512);
  assert.deepEqual(
    { actionMode: normalizeConfig({ actionMode: "smart", tokenBudget: 99999 }).actionMode,
      tokenBudget: normalizeConfig({ actionMode: "smart", tokenBudget: 99999 }).tokenBudget },
    { actionMode: "smart", tokenBudget: 4096 },
  );
  assert.equal(normalizeConfig({ actionMode: "hybrid" }).actionMode, "smart");
});

test("service identity rejects every legacy or mismatched control service", () => {
  const expected = {
    serviceProtocolVersion: 2,
    installationId: "11111111-1111-4111-8111-111111111111",
    buildId: "build-current",
    bridgeTokenFingerprint: "0123456789abcdef",
  };
  const exact = {
    ok: true,
    service: "minecraft-codex-companion",
    ...expected,
    processId: 42,
    processInstanceId: "22222222-2222-4222-8222-222222222222",
    companions: 1,
    connectedCompanions: 1,
  };

  assert.equal(classifyServiceHealth(exact, expected).running, true);
  assert.equal(classifyServiceHealth(exact, expected).identityVerified, true);
  assert.equal(classifyServiceHealth({ companions: 1, connectedCompanions: 1 }, expected).running, false);
  assert.equal(classifyServiceHealth({ ...exact, buildId: "build-old" }, expected).running, false);
  assert.equal(classifyServiceHealth({ ...exact, installationId: "33333333-3333-4333-8333-333333333333" }, expected).running, false);
  assert.equal(classifyServiceHealth({ ...exact, bridgeTokenFingerprint: "fedcba9876543210" }, expected).running, false);
});

test("Minecraft readiness requires the packaged bridge version and a real T delivery acknowledgement", () => {
  const connection = {
    companionId: "codex-forge",
    backend: "forge-1.20.1",
    bridgeVersion: "0.2.3",
    connected: true,
    tRoundTripVerified: true,
    lastIncomingChatAt: "2026-08-15T01:00:00.000Z",
    lastDeliveredChatAt: "2026-08-15T01:00:01.000Z",
    lastRoundTripAt: "2026-08-15T01:00:01.000Z",
  };
  const current = classifyMinecraftBridge({
    minecraftBridge: { bridgeVersions: ["0.2.3"], connections: [connection] },
  });
  assert.equal(current.connected, true);
  assert.equal(current.tRoundTripVerified, true);
  assert.equal(current.lastRoundTripAt, "2026-08-15T01:00:01.000Z");

  const old = classifyMinecraftBridge({
    minecraftBridge: {
      bridgeVersions: ["0.2.2"],
      connections: [{ ...connection, bridgeVersion: "0.2.2" }],
    },
  });
  assert.equal(old.connected, false);
  assert.equal(old.tRoundTripVerified, false);

  const unacknowledged = classifyMinecraftBridge({
    minecraftBridge: {
      bridgeVersions: ["0.2.3"],
      connections: [{ ...connection, tRoundTripVerified: false }],
    },
  });
  assert.equal(unacknowledged.connected, true);
  assert.equal(unacknowledged.tRoundTripVerified, false);
});

test("installation identity persists while health exposes only a token fingerprint", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");

  const first = await getOrCreateInstallationId(state);
  const second = await getOrCreateInstallationId(state);
  const token = "0123456789abcdef0123456789abcdef";

  assert.equal(second, first);
  assert.match(first, /^[0-9a-f-]{36}$/u);
  assert.match(bridgeTokenFingerprint(token), /^[0-9a-f]{16}$/u);
  assert.notEqual(bridgeTokenFingerprint(token), token);
});

test("recorded service ownership is limited to the payload or installed release directory", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  const payload = path.join(files.root, "payload");
  const inPayload = path.join(payload, "apps", "control-plane", "dist", "server.js");
  const inRelease = path.join(state, "Application", "releases", "release-id", "apps", "control-plane", "dist", "server.js");
  const outside = path.join(files.root, "unrelated", "server.js");
  const metadata = (serverScript) => ({ pid: 123, port: 8765, serverScript });

  assert.equal(recordedServiceIsManaged(metadata(inPayload), payload, state), true);
  assert.equal(recordedServiceIsManaged(metadata(inRelease), payload, state), true);
  assert.equal(recordedServiceIsManaged(metadata(outside), payload, state), false);
  assert.equal(recordedServiceIsManaged({ pid: 123, port: 8765, serverScript: "other.js" }, payload, state), false);
});

test("bridge startup lock serializes concurrent EXE startup reconciliation", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  let active = 0;
  let maximum = 0;
  let completed = 0;

  await Promise.all([0, 1, 2].map(() => withBridgeStartupLock(state, async () => {
    active += 1;
    maximum = Math.max(maximum, active);
    await new Promise((resolve) => setTimeout(resolve, 30));
    active -= 1;
    completed += 1;
  })));

  assert.equal(maximum, 1);
  assert.equal(completed, 3);
  assert.equal(fs.existsSync(path.join(state, "bridge-startup.lock")), false);
});

test("automatic bridge survives setup-required state and monitors without duplicate reconciliation", async (t) => {
  const files = await fixture();
  const state = path.join(files.root, "state");
  t.after(() => removeFixture(files.root));
  let reconciliations = 0;
  let healthChecks = 0;
  const context = createApiContext({
    payloadRoot: files.root,
    stateDirectory: state,
    autoBridgeMonitorMs: 25,
    reconcileBridge: async (config) => {
      reconciliations += 1;
      return {
        port: config.port,
        service: { running: true, identityVerified: true },
        antigravity: { connected: true },
      };
    },
    bridgeHealthy: async () => {
      healthChecks += 1;
      return true;
    },
  });
  const automatic = beginAutomaticBridge(context);
  await waitFor(() => context.autoBridge.state === "setup-required");

  await saveConfig(state, {
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion: "Forge-1.20.1-Codex",
    playerName: "LocalPlayer",
    companionName: "Companion",
    port: 18765,
    chatTarget: "active-provider",
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
    antigravityConversationTitle: "Existing Conversation",
  });
  await waitFor(() => context.autoBridge.state === "ready" && healthChecks > 0, 3_000);
  assert.equal(reconciliations, 1);
  assert.ok(healthChecks > 0);

  context.autoBridgePaused = true;
  await automatic;
  assert.equal(context.autoBridgePromise, null);
});

test("Antigravity MCP installation is idempotent and does not create repeat backups", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const configPath = path.join(files.root, "agent", "mcp_config.json");
  const config = normalizeConfig({
    port: 18765,
    antigravityConfigPath: configPath,
  });
  const payloadRoot = path.resolve(__dirname, "../../..");
  const fixturePaths = () => ({ node: "runtime-node.exe", mcpStdio: "mcp-stdio.js" });

  const first = await installAntigravity(config, payloadRoot, fixturePaths);
  const second = await installAntigravity(config, payloadRoot, fixturePaths);

  assert.equal(first.configChanged, true);
  assert.equal(first.backupCreated, false);
  assert.equal(second.configChanged, false);
  assert.equal(second.backupCreated, false);
  assert.deepEqual((await fsp.readdir(path.dirname(configPath))).filter((name) => name.endsWith(".bak")), []);
});

test("persona normalization keeps bounded public fields only", () => {
  const normalized = normalizePersona({
    mode: "custom",
    displayName: " Luna ",
    personality: " curious ",
    apiKey: "must-not-persist",
  });
  assert.deepEqual(normalized, {
    mode: "custom",
    displayName: "Luna",
    personality: "curious",
    speakingStyle: "",
    memoryNotes: "",
  });
  assert.equal(Object.hasOwn(normalized, "apiKey"), false);
});

test("version names cannot escape the versions directory", () => {
  assert.equal(validateVersionName("Forge 1.20.1", "实例"), "Forge 1.20.1");
  assert.throws(() => validateVersionName("..", "实例"));
  assert.throws(() => validateVersionName("..\\private", "实例"));
  assert.equal(isPathInside("C:\\mc\\versions", "C:\\mc\\versions\\Forge"), true);
  assert.equal(isPathInside("C:\\mc\\versions", "C:\\mc\\private"), false);
});

test("runtime config requires target-machine choices", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const config = validateRuntimeConfig({
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion: "Forge-1.20.1-Codex",
    playerName: "LocalPlayer",
    port: 18765,
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
  });
  assert.equal(config.playerName, "LocalPlayer");
  assert.equal(config.companionName, "Codex");
  assert.throws(() => validateRuntimeConfig({ ...config, playerName: "" }));
  assert.throws(() => validateCompanionName("\u0000"));
  assert.equal(validateAntigravityConversationTitle(" Execute Minecraft Woodcutting Task "), "Execute Minecraft Woodcutting Task");
  assert.throws(() => validateAntigravityConversationTitle(""));
  assert.throws(() => validateRuntimeConfig({ ...config, targetVersion: "..\\outside" }));
});

test("NPC skin validation accepts only the model's 128x64 PNG layout", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const valid = path.join(files.root, "valid.png");
  const invalid = path.join(files.root, "invalid.png");
  const reference = await fsp.readFile(path.resolve(__dirname, "../../../assets/third_party/queen-cats-dogs/humanoid_cat_white.png"));
  await fsp.writeFile(valid, reference);
  const wrongDimensions = Buffer.from(reference);
  wrongDimensions.writeUInt32BE(64, 16);
  await fsp.writeFile(invalid, wrongDimensions);
  assert.deepEqual(validateNpcSkin(valid), { width: 128, height: 64, size: reference.length });
  assert.throws(() => validateNpcSkin(invalid), /128×64/u);
});

test("saved launcher configuration never serializes keys or bridge tokens", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  await saveConfig(state, {
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion: "Forge-1.20.1-Codex",
    playerName: "LocalPlayer",
    port: 18765,
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
    apiKey: "not-allowed",
    bridgeToken: "not-allowed",
  });
  const persisted = await fsp.readFile(path.join(state, "launcher-config.json"), "utf8");
  assert.doesNotMatch(persisted, /not-allowed/u);
  assert.doesNotMatch(persisted, /apiKey|bridgeToken/u);
});

test("launcher configuration round-trips Chinese as BOM-free UTF-8", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  const input = {
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion: "Forge-1.20.1-Codex",
    playerName: "LocalPlayer",
    companionName: "Codex",
    port: 18765,
    persona: {
      mode: "custom",
      displayName: "小白",
      personality: "冷静、可靠，也会自由聊天。",
      speakingStyle: "简洁自然。",
      memoryNotes: "保护玩家的家。",
    },
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
  };
  await saveConfig(state, { ...input, companionName: "第一次保存" });
  await saveConfig(state, input);
  const configPath = path.join(state, "launcher-config.json");
  const bytes = await fsp.readFile(configPath);
  assert.notDeepEqual([...bytes.subarray(0, 3)], [0xef, 0xbb, 0xbf]);
  assert.match(bytes.toString("utf8"), /Codex/u);
  assert.equal(bytes.includes(0), false);
  const loaded = await loadConfig(state);
  assert.equal(loaded.companionName, "Codex");
  assert.equal(loaded.persona.personality, "冷静、可靠，也会自由聊天。");
  assert.deepEqual((await fsp.readdir(state)).filter((name) => name.endsWith(".tmp")), []);
});

test("launcher repairs a legacy replacement-character NPC name as Codex", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  await fsp.mkdir(state, { recursive: true });
  await fsp.writeFile(
    path.join(state, "launcher-config.json"),
    Buffer.from(JSON.stringify({ companionName: "?" }), "utf8"),
  );

  const loaded = await loadConfig(state, { APPDATA: path.join(files.root, "roaming") });

  assert.equal(loaded.companionName, "Codex");
});

test("launcher accepts a UTF-8 BOM without corrupting Chinese configuration", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  await fsp.mkdir(state, { recursive: true });
  const document = {
    ...defaultConfig({ APPDATA: path.join(files.root, "roaming") }),
    companionName: "红瞳猫娘",
  };
  await fsp.writeFile(
    path.join(state, "launcher-config.json"),
    Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(JSON.stringify(document), "utf8")]),
  );
  const loaded = await loadConfig(state, { APPDATA: path.join(files.root, "roaming") });
  assert.equal(loaded.companionName, "红瞳猫娘");
  assert.equal((await fsp.readdir(state)).some((name) => name.includes(".corrupt-")), false);
});

test("malformed launcher JSON is quarantined and replaced with UTF-8 defaults", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  const configPath = path.join(state, "launcher-config.json");
  const malformed = Buffer.from('{"companionName":"白发猫娘",', "utf8");
  await fsp.mkdir(state, { recursive: true });
  await fsp.writeFile(configPath, malformed);
  const environment = { APPDATA: path.join(files.root, "roaming") };

  const loaded = await loadConfig(state, environment);

  assert.deepEqual(loaded, defaultConfig(environment));
  const entries = await fsp.readdir(state);
  const quarantined = entries.filter((name) => /^launcher-config\.corrupt-\d+-[0-9a-f-]+\.json$/u.test(name));
  assert.equal(quarantined.length, 1);
  assert.deepEqual(await fsp.readFile(path.join(state, quarantined[0])), malformed);
  const recovered = await fsp.readFile(configPath);
  assert.notDeepEqual([...recovered.subarray(0, 3)], [0xef, 0xbb, 0xbf]);
  assert.deepEqual(JSON.parse(recovered.toString("utf8")), defaultConfig(environment));
  assert.deepEqual(await loadConfig(state, environment), defaultConfig(environment));
  assert.equal((await fsp.readdir(state)).filter((name) => name.includes(".corrupt-")).length, 1);
});

test("invalid UTF-8 launcher configuration is quarantined instead of keeping replacement characters", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  const configPath = path.join(state, "launcher-config.json");
  const invalidUtf8 = Buffer.concat([
    Buffer.from('{"companionName":"', "utf8"),
    Buffer.from([0xff]),
    Buffer.from('"}', "utf8"),
  ]);
  await fsp.mkdir(state, { recursive: true });
  await fsp.writeFile(configPath, invalidUtf8);

  const loaded = await loadConfig(state, { APPDATA: path.join(files.root, "roaming") });

  assert.equal(loaded.companionName, "Codex");
  const quarantined = (await fsp.readdir(state)).find((name) => name.includes(".corrupt-"));
  assert.ok(quarantined);
  assert.deepEqual(await fsp.readFile(path.join(state, quarantined)), invalidUtf8);
  assert.doesNotMatch(await fsp.readFile(configPath, "utf8"), /\uFFFD/u);
});

test("launcher loads the latest persona stored by the control service", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  await saveConfig(state, {
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion: "Forge-1.20.1-Codex",
    playerName: "LocalPlayer",
    companionName: "Luna",
    port: 18765,
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
  });
  await fsp.writeFile(path.join(state, "chat-settings.json"), JSON.stringify({
    version: 1,
    freeChatEnabled: true,
    playerName: "LocalPlayer",
    target: "antigravity-mcp",
    persona: {
      mode: "custom",
      displayName: "Luna",
      personality: "Calm and curious.",
      speakingStyle: "Warm and concise.",
      memoryNotes: "Protect the player's builds.",
    },
    updatedAt: new Date().toISOString(),
  }), "utf8");
  const loaded = await loadConfig(state);
  assert.equal(loaded.persona.mode, "custom");
  assert.equal(loaded.persona.displayName, "Luna");
  assert.equal(loaded.persona.memoryNotes, "Protect the player's builds.");
});

test("launcher loads the persona matching the configured NPC from a multi-profile store", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  await saveConfig(state, {
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion: "Forge-1.20.1-Codex",
    playerName: "LocalPlayer",
    companionName: "Codex",
    port: 18765,
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
  });
  const persona = (displayName, personality) => ({
    mode: "custom",
    displayName,
    personality,
    speakingStyle: "",
    memoryNotes: "",
  });
  const updatedAt = new Date().toISOString();
  await fsp.writeFile(path.join(state, "chat-settings.json"), JSON.stringify({
    version: 2,
    selectedCompanionName: "Luna",
    profiles: [
      { freeChatEnabled: true, playerName: "LocalPlayer", companionName: "Luna", target: "active-provider", persona: persona("Luna", "活泼"), updatedAt },
      { freeChatEnabled: true, playerName: "LocalPlayer", companionName: "Codex", target: "antigravity-mcp", persona: persona("Codex", "安静"), updatedAt },
    ],
  }), "utf8");

  const loaded = await loadConfig(state);

  assert.equal(loaded.persona.displayName, "Codex");
  assert.equal(loaded.persona.personality, "安静");
});

test("bridge configuration applies the NPC name and imported skin", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const state = path.join(files.root, "state");
  const targetVersion = "Forge-1.20.1-Codex";
  const target = path.join(files.minecraftRoot, "versions", targetVersion);
  await fsp.mkdir(path.join(target, "config"), { recursive: true });
  await fsp.mkdir(path.join(state, "assets"), { recursive: true });
  const reference = path.resolve(__dirname, "../../../assets/third_party/queen-cats-dogs/humanoid_cat_white.png");
  await fsp.copyFile(reference, path.join(state, "assets", "npc-skin.png"));
  const config = validateRuntimeConfig({
    launcherPath: files.launcherPath,
    minecraftRoot: files.minecraftRoot,
    sourceVersion: "Forge-1.20.1",
    targetVersion,
    playerName: "LocalPlayer",
    companionName: "Luna",
    npcSkinMode: "custom",
    port: 18765,
    antigravityConfigPath: path.join(files.root, "antigravity.json"),
  });
  const bridgePath = await configureBridge(config, state);
  const bridge = JSON.parse(await fsp.readFile(bridgePath, "utf8"));
  assert.equal(bridge.name, "Luna");
  assert.equal(bridge.npcSkinPath, "config/minecraft-codex-companion-skin.png");
  const installedSkin = path.join(target, "config", "minecraft-codex-companion-skin.png");
  assert.deepEqual(await fsp.readFile(installedSkin), await fsp.readFile(reference));
  await configureBridge({ ...config, npcSkinMode: "default" }, state);
  assert.equal(fs.existsSync(installedSkin), false);
});

test("instance discovery distinguishes isolated Codex clones", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  for (const name of ["Source", "Source-Codex"]) {
    const directory = path.join(files.minecraftRoot, "versions", name);
    await fsp.mkdir(directory, { recursive: true });
    await fsp.writeFile(path.join(directory, `${name}.json`), "{}", "utf8");
    await fsp.writeFile(path.join(directory, `${name}.jar`), "fixture", "utf8");
  }
  await fsp.writeFile(
    path.join(files.minecraftRoot, "versions", "Source-Codex", "CODEX-CLONE.json"),
    JSON.stringify({ sourceVersion: "Source" }),
    "utf8",
  );
  const instances = await listInstances(files.minecraftRoot);
  assert.deepEqual(instances.map((item) => [item.name, item.isCompanionClone]), [
    ["Source", false],
    ["Source-Codex", true],
  ]);
});

test("Antigravity merge preserves unrelated settings", () => {
  const merged = mergeAntigravityConfig({
    theme: "dark",
    mcpServers: { existing: { command: "existing-tool", env: { PRIVATE: "unchanged" } } },
  }, { command: "runtime-node", args: ["mcp-stdio.js"], env: { MC_COMPANION_URL: "http://127.0.0.1:8765" } });
  assert.equal(merged.theme, "dark");
  assert.equal(merged.mcpServers.existing.env.PRIVATE, "unchanged");
  assert.equal(merged.mcpServers.minecraft_codex_companion.command, "runtime-node");
});

test("Antigravity permissions add only the two companion tools and preserve policy", () => {
  const merged = mergeAntigravityPermissions({
    userSettings: {
      themeMode: "dark",
      globalPermissionGrants: {
        allow: ["mcp(existing/read_only)"],
        ask: ["mcp(existing/ask)"],
        deny: ["mcp(existing/dangerous)"],
      },
    },
    unrelated: true,
  });
  assert.deepEqual(merged.userSettings.globalPermissionGrants.allow, [
    "mcp(existing/read_only)",
    "mcp(minecraft_codex_companion/mc_submit_ai_decision)",
    "mcp(minecraft_codex_companion/mc_chat)",
  ]);
  assert.deepEqual(merged.userSettings.globalPermissionGrants.ask, ["mcp(existing/ask)"]);
  assert.deepEqual(merged.userSettings.globalPermissionGrants.deny, ["mcp(existing/dangerous)"]);
  assert.equal(merged.userSettings.themeMode, "dark");
  assert.equal(merged.unrelated, true);
  assert.equal(merged.userSettings.globalPermissionGrants.allow.includes("mcp(*)"), false);
  assert.deepEqual(mergeAntigravityPermissions(merged), merged);
  assert.throws(() => mergeAntigravityPermissions({
    userSettings: { globalPermissionGrants: { allow: "mcp(*)" } },
  }));
});

test("Antigravity permission path is derived only from the standard profile layout", () => {
  const profileRoot = os.homedir();
  const standard = path.join(profileRoot, ".gemini", "antigravity", "mcp_config.json");
  assert.equal(
    resolveAntigravityPermissionConfigPath(standard),
    path.join(profileRoot, ".gemini", "config", "config.json"),
  );
  assert.equal(resolveAntigravityPermissionConfigPath(path.join(os.tmpdir(), "custom", "mcp.json")), null);
});

test("companion prompt requires every player-facing reply to use mc_chat", () => {
  const prompt = companionPrompt();
  assert.match(prompt, /mc_chat/u);
  assert.match(prompt, /人格闲聊/u);
  assert.match(prompt, /最终回答/u);
  assert.match(prompt, /只调用一次/u);
});

test("launcher arguments are passed without a command shell", () => {
  assert.deepEqual(splitArguments('--profile "My World" --launch {instance}'), ["--profile", "My World", "--launch", "{instance}"]);
  assert.throws(() => splitArguments('--profile "unfinished'));
});

test("portable runtime source has no PowerShell, command shell, or executable injection", async () => {
  const root = path.resolve(__dirname, "../../..");
  const sources = await Promise.all([
    fsp.readFile(path.join(root, "apps", "portable-launcher", "src", "launcher.cjs"), "utf8"),
    fsp.readFile(path.join(root, "apps", "portable-launcher", "src", "instance-manager.cjs"), "utf8"),
    fsp.readFile(path.join(root, "apps", "control-plane", "src", "secret-protector.ts"), "utf8"),
  ]);
  const runtimeSource = sources.join("\n");
  assert.doesNotMatch(runtimeSource, /powershell\.exe|pwsh\.exe|cmd\.exe|rundll32\.exe|NODE_SEA_BLOB|postject|experimental-sea-config/iu);
  assert.doesNotMatch(runtimeSource, /shell\s*:\s*true/iu);
});

test("native updater recognizes Java processes without reading command lines", () => {
  const output = [
    '"java.exe","120","Console","1","100 K"',
    '"javaw.exe","121","Console","1","200 K"',
    '"node.exe","122","Console","1","300 K"',
  ].join("\r\n");
  assert.deepEqual(parseJavaTasklist(output), ["java.exe", "javaw.exe"]);
  assert.deepEqual(parseJavaTasklist("INFO: No tasks are running"), []);
});

test("native instance manager installs an isolated clone without PowerShell", async (t) => {
  const files = await fixture();
  t.after(() => removeFixture(files.root));
  const sourceVersion = "Fixture-Forge-1.20.1";
  const targetVersion = `${sourceVersion}-Codex`;
  const source = path.join(files.minecraftRoot, "versions", sourceVersion);
  const bridgeJar = path.join(files.root, "minecraft_codex_bridge-forge-1.20.1-test.jar");
  const baritoneJar = path.join(files.root, "baritone-api-forge-1.20.1-test.jar");
  await fsp.mkdir(path.join(source, "mods"), { recursive: true });
  await fsp.mkdir(path.join(source, "saves", "private-world"), { recursive: true });
  await fsp.writeFile(path.join(source, `${sourceVersion}.jar`), "minecraft", "utf8");
  await fsp.writeFile(path.join(source, `${sourceVersion}.json`), JSON.stringify({
    id: sourceVersion,
    jar: sourceVersion,
    libraries: [{ name: "net.minecraftforge:forge:1.20.1-47.4.21" }],
  }), "utf8");
  await fsp.writeFile(path.join(source, "mods", "example.jar"), "mod", "utf8");
  await fsp.writeFile(path.join(source, "saves", "private-world", "level.dat"), "private", "utf8");
  await fsp.writeFile(bridgeJar, "bridge-v1", "utf8");
  await fsp.writeFile(baritoneJar, "baritone", "utf8");

  const target = await installClone({
    minecraftRoot: files.minecraftRoot,
    sourceVersion,
    targetVersion,
    launcherPath: files.launcherPath,
    bridgeJar,
    baritoneJar,
  });
  assert.equal(target, path.join(files.minecraftRoot, "versions", targetVersion));
  assert.equal(fs.existsSync(path.join(target, "saves", "private-world", "level.dat")), false);
  assert.equal(fs.existsSync(path.join(target, "mods", "example.jar")), true);
  assert.equal(fs.existsSync(path.join(target, "mods", path.basename(bridgeJar))), true);
  const installed = JSON.parse(await fsp.readFile(path.join(target, `${targetVersion}.json`), "utf8"));
  assert.equal(installed.id, targetVersion);
  assert.equal(installed.jar, targetVersion);

  const nextBridge = path.join(files.root, "minecraft_codex_bridge-forge-1.20.1-next.jar");
  await fsp.writeFile(nextBridge, "bridge-v2", "utf8");
  await assert.rejects(
    updateClone({
      minecraftRoot: files.minecraftRoot,
      targetVersion,
      bridgeJar: nextBridge,
      inspectJavaProcesses: async () => ["javaw.exe"],
    }),
    /Exit Minecraft and HMCL/u,
  );
  assert.equal(await fsp.readFile(path.join(target, "mods", path.basename(bridgeJar)), "utf8"), "bridge-v1");
  assert.equal(fs.existsSync(path.join(target, "mods", path.basename(nextBridge))), false);
  await updateClone({
    minecraftRoot: files.minecraftRoot,
    targetVersion,
    bridgeJar: nextBridge,
    inspectJavaProcesses: async () => [],
  });
  assert.equal(await fsp.readFile(path.join(target, "mods", path.basename(nextBridge)), "utf8"), "bridge-v2");
  assert.equal(fs.existsSync(path.join(target, "mods", path.basename(bridgeJar))), false);
  assert.equal((await fsp.readdir(path.join(target, "bridge-backups"))).length, 1);
});
