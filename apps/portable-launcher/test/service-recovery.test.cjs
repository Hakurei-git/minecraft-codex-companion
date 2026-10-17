"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { createHash } = require("node:crypto");
const { once } = require("node:events");
const fs = require("node:fs/promises");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { createServer, getOrCreateInstallationId, saveConfig } = require("../src/launcher.cjs");

test("startup preserves a slow current service and recovers its recorded port", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "mc-service-recovery-"));
  const state = path.join(root, "state");
  const payload = path.join(root, "payload");
  const minecraftRoot = path.join(root, "minecraft");
  const paths = [
    "runtime/node.exe", "runtime/MinecraftCodexClient.exe", "runtime/MinecraftCodexPicker.exe",
    "runtime/MinecraftCodexSecret.exe", "runtime/MinecraftCodexHmclLauncher.exe",
    "runtime/MinecraftCodexWindowFocusAgent.jar", "apps/portable-launcher/src/launcher.cjs",
    "apps/portable-launcher/src/instance-manager.cjs", "apps/control-plane/dist/server.js",
    "apps/control-plane/dist/mcp-stdio.js", "apps/dashboard/dist/index.html", "scripts/mcp-portable-smoke.mjs",
    "mods/forge-1.20.1/build/libs/minecraft_codex_bridge-forge-1.20.1-0.2.3.jar",
    "mods/neoforge-1.21.1/build/libs/minecraft_codex_bridge-neoforge-1.21.1-0.1.0.jar",
    "vendor/baritone/baritone-api-forge-1.20.1-1.0.jar",
  ];
  for (const relative of paths) {
    const target = path.join(payload, relative);
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, "fixture");
  }
  const digest = (value) => createHash("sha256").update(value).digest("hex");
  const manifest = JSON.stringify({ format: 2, packaging: { model: "transparent-multi-file" }, files: paths.map((file) => ({ path: file, sha256: digest("fixture") })) });
  await fs.writeFile(path.join(payload, "portable-manifest.json"), manifest);
  const installationId = await getOrCreateInstallationId(state);
  const token = "0123456789abcdef0123456789abcdef";
  await fs.writeFile(path.join(state, "bridge-token.txt"), token);
  const identity = { serviceProtocolVersion: 2, installationId, buildId: digest(manifest), bridgeTokenFingerprint: digest(token).slice(0, 16) };
  const childScript = path.join(root, "service.cjs");
  await fs.writeFile(childScript, `
    const http = require('node:http');
    const identity = JSON.parse(process.env.MC_TEST_IDENTITY);
    const server = http.createServer((req, res) => {
      req.resume();
      setTimeout(() => {
        res.setHeader('content-type', 'application/json');
        res.end(JSON.stringify(req.url === '/api/health'
          ? { ok: true, service: 'minecraft-codex-companion', ...identity, processId: process.pid }
          : {}));
      }, req.url === '/api/health' ? 1800 : 0);
    }).listen(0, '127.0.0.1', () => process.send({ port: server.address().port }));
  `);
  const child = spawn(process.execPath, [childScript], { windowsHide: true, env: { ...process.env, MC_TEST_IDENTITY: JSON.stringify(identity) }, stdio: ["ignore", "ignore", "ignore", "ipc"] });
  let local;
  t.after(async () => {
    if (local) {
      local.context.closing = true;
      local.context.autoBridgePaused = true;
      await local.context.autoBridgePromise;
      local.server.closeAllConnections();
      await new Promise((resolve) => local.server.close(resolve));
    }
    if (child.exitCode === null) { child.kill(); await once(child, "exit"); }
    await fs.rm(root, { recursive: true, force: true });
  });
  const [{ port }] = await once(child, "message");
  const unused = net.createServer().listen(0, "127.0.0.1");
  await once(unused, "listening");
  const stalePort = unused.address().port;
  await new Promise((resolve) => unused.close(resolve));
  await fs.writeFile(path.join(state, "control-process.json"), JSON.stringify({ pid: child.pid, port, serverScript: path.join(payload, "apps/control-plane/dist/server.js") }));
  await fs.mkdir(path.join(minecraftRoot, "versions/Forge-1.20.1"), { recursive: true });
  const launcherPath = path.join(root, "Launcher.exe");
  await fs.writeFile(launcherPath, "fixture");
  await saveConfig(state, { launcherPath, minecraftRoot, sourceVersion: "Forge-1.20.1", targetVersion: "Forge-1.20.1", playerName: "Player", companionName: "Companion", chatTarget: "active-provider", port: stalePort });
  local = createServer({ payloadRoot: payload, stateDirectory: state, sessionToken: "test-session" });
  local.server.listen(0, "127.0.0.1");
  await once(local.server, "listening");
  const response = await fetch(`http://127.0.0.1:${local.server.address().port}/api/service/start`, { method: "POST", headers: { "x-companion-session": "test-session", "content-type": "application/json" }, body: "{}", signal: AbortSignal.timeout(20_000) });
  const result = await response.json();
  assert.equal(response.status, 200, JSON.stringify(result));
  assert.equal(result.port, port);
  assert.equal(child.exitCode, null, "Healthy recorded service must not be terminated");
  const saved = JSON.parse(await fs.readFile(path.join(state, "launcher-config.json"), "utf8"));
  assert.equal(saved.port, port, "Recover saved port instead of spawning a second service");
});
