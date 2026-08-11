import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";

const projectRoot = path.resolve(import.meta.dirname, "..");
const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-codex-offline-acceptance-"));

function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

function sanitizedEnvironment(overrides = {}) {
  const environment = { ...process.env, ...overrides };
  for (const name of [
    "ANTHROPIC_API_KEY",
    "CLAUDE_API_KEY",
    "CODEX_API_KEY",
    "MC_ANTIGRAVITY_HOME",
    "MC_ANTIGRAVITY_LOG_PATH",
    "MC_BRIDGE_TOKEN",
    "MC_COMPANION_SECRET_HELPER",
    "MC_MCP_ALLOW_HOSTS",
    "OPENAI_API_KEY",
  ]) delete environment[name];
  return environment;
}

function runNodeScript(script, environment) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [script], {
      cwd: projectRoot,
      env: environment,
      shell: false,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { if (stdout.length < 2_000_000) stdout += chunk; });
    child.stderr.on("data", (chunk) => { if (stderr.length < 200_000) stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve(stdout);
      else reject(new Error(`${path.basename(script)} failed with ${code}: ${stderr.slice(-4_000)}`));
    });
  });
}

async function waitForHealth(baseUrl, child, stderr) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (child.exitCode !== null) throw new Error(`Simulator service exited early: ${stderr().slice(-4_000)}`);
    try {
      const response = await fetch(`${baseUrl}/api/health`, { signal: AbortSignal.timeout(800) });
      const health = response.ok ? await response.json() : null;
      if (health?.ok && health.companions === 1) return;
    } catch {
      // Startup races are expected while the local server is binding.
    }
    await new Promise((resolve) => setTimeout(resolve, 125));
  }
  throw new Error("Simulator service did not become healthy");
}

const port = await freePort();
const baseUrl = `http://127.0.0.1:${port}`;
const environment = sanitizedEnvironment({
  PORT: String(port),
  MC_COMPANION_STATE_DIR: stateDirectory,
  MC_COMPANION_URL: baseUrl,
  MC_ENABLE_SIMULATOR: "1",
  MC_MCP_URL: `${baseUrl}/mcp`,
});
let service;
let serviceStderr = "";

try {
  service = spawn(process.execPath, [path.join(projectRoot, "apps", "control-plane", "dist", "server.js")], {
    cwd: projectRoot,
    env: environment,
    shell: false,
    windowsHide: true,
    stdio: ["ignore", "ignore", "pipe"],
  });
  service.stderr.on("data", (chunk) => { if (serviceStderr.length < 200_000) serviceStderr += chunk; });
  await waitForHealth(baseUrl, service, () => serviceStderr);

  const capabilitiesOutput = await runNodeScript(path.join(projectRoot, "scripts", "capability-smoke.mjs"), environment);
  const mcpOutput = await runNodeScript(path.join(projectRoot, "scripts", "mcp-smoke.mjs"), environment);
  const capabilities = JSON.parse(capabilitiesOutput);
  const mcp = JSON.parse(mcpOutput);
  if (capabilities.tasks.length !== 20) throw new Error(`Expected 20 task kinds, received ${capabilities.tasks.length}`);
  if (!capabilities.tasks.every((task) => task.status === "succeeded")) throw new Error("At least one task did not succeed");
  if (!Array.isArray(mcp.tools) || mcp.tools.length < 18) throw new Error("MCP tool contract is incomplete");

  process.stdout.write(`${JSON.stringify({
    ok: true,
    localOnly: true,
    isolatedState: true,
    taskKinds: capabilities.tasks.length,
    mcpTools: mcp.tools.length,
    companionBackend: mcp.companion.backend,
  }, null, 2)}\n`);
} finally {
  if (service && service.exitCode === null) {
    service.kill("SIGTERM");
    await Promise.race([
      new Promise((resolve) => service.once("exit", resolve)),
      new Promise((resolve) => setTimeout(resolve, 2_000)),
    ]);
  }
  await rm(stateDirectory, { recursive: true, force: true });
}
