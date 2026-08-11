import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export const CRAFT_SCENARIOS = Object.freeze([
  Object.freeze({
    name: "fishing",
    code: 1,
    setupMode: "setup-fishing",
    prompt: "\u7ed9\u6211\u505a\u4e00\u628a\u9493\u9c7c\u7aff",
    itemId: "minecraft:fishing_rod",
    count: 1,
    sticks: 3,
    secondary: 2,
  }),
  Object.freeze({
    name: "torches",
    code: 2,
    setupMode: "setup-torches",
    prompt: "\u6211\u9700\u898164\u4e2a\u706b\u628a",
    itemId: "minecraft:torch",
    count: 64,
    sticks: 16,
    secondary: 16,
  }),
]);

const INSPECTION_KEYS = [
  "scenario", "tables", "newTables", "atExistingTable", "npcOutput", "playerOutput",
  "worldOutput", "sticks", "secondary", "deliverySeen", "unexpectedBreaks", "unknownWorldEdits",
];

export function loopbackBase(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("live explicit crafting smoke only connects to a loopback HTTP service");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

async function request(base, pathname, { method = "GET", body } = {}) {
  const response = await fetch(new URL(pathname, base), {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    throw new Error(`${method} ${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 500)}`);
  }
  return response.status === 204 ? null : response.json();
}

async function connectedCompanion(base) {
  const response = await request(base, "/api/companions");
  const companions = response.companions?.filter((candidate) => (
    candidate.connected === true && candidate.embodiment === "in-world-npc"
  )) ?? [];
  if (companions.length !== 1) {
    throw new Error(`Expected exactly one connected Forge in-world NPC, found ${companions.length}`);
  }
  if (!companions[0].ownerName) throw new Error("Connected NPC has no bound owner name");
  return companions[0];
}

async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

export function fixtureExpectedPrefix(mode) {
  if (mode === "setup-fishing") return "rpc:setup=fishing,";
  if (mode === "setup-torches") return "rpc:setup=torches,";
  if (mode === "inspect-craft") return "rpc:i=";
  if (mode === "cleanup") return "rp:cleanup=";
  throw new Error(`Unsupported explicit crafting fixture mode ${mode}`);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "resource-priority"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "resource-priority", mode },
  });
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 20));
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status === null) continue;
    if (status.startsWith("live-fixture:denied ") || status.startsWith("live-fixture:failed ")) {
      throw new Error(`Minecraft rejected explicit crafting fixture ${mode}: ${status}`);
    }
    if (!status.startsWith(fixtureExpectedPrefix(mode))) {
      throw new Error(`Unexpected explicit crafting fixture acknowledgement: ${status}`);
    }
    return { snapshot: current, status };
  }
  throw new Error(`Minecraft did not acknowledge explicit crafting fixture ${mode}`);
}

export function parseSetupStatus(status, scenario) {
  const escaped = scenario.name.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  const match = new RegExp(`^rpc:setup=${escaped},(-?\\d+),(-?\\d+),(-?\\d+)$`, "u").exec(status ?? "");
  if (!match) throw new Error(`Unexpected ${scenario.name} setup status: ${JSON.stringify(status)}`);
  return { scenario: scenario.name, origin: { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) } };
}

export function parseInspection(status) {
  const match = /^rpc:i=([0-9]+(?:,[0-9]+){11})$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected explicit crafting inspection: ${JSON.stringify(status)}`);
  const values = match[1].split(",").map(Number);
  return Object.fromEntries(INSPECTION_KEYS.map((key, index) => [key, values[index]]));
}

function invariant(condition, message, value) {
  if (!condition) throw new Error(`${message}: ${JSON.stringify(value)}`);
}

export function validateInitial(value, scenario) {
  invariant(value.scenario === scenario.code, "Fixture scenario code is wrong", value);
  invariant(value.tables === 1 && value.newTables === 0 && value.atExistingTable === 0,
    "Initial workstation fixture is invalid", value);
  invariant(value.npcOutput + value.playerOutput + value.worldOutput === 0,
    "Initial fixture already contains the requested output", value);
  invariant(value.sticks === scenario.sticks && value.secondary === scenario.secondary,
    "Initial recipe ingredients are not exact", value);
  invariant(value.deliverySeen === 0 && value.unexpectedBreaks === 0 && value.unknownWorldEdits === 0,
    "Initial fixture evidence is contaminated", value);
  return value;
}

export function validateTask(task, scenario, ownerName) {
  const spec = task?.spec;
  if (spec?.kind !== "craft" || spec.itemId !== scenario.itemId || spec.count !== scenario.count
    || spec.deliverTo !== ownerName || spec.requestedBy !== ownerName) {
    throw new Error(`Minecraft T chat created the wrong ${scenario.name} task: ${JSON.stringify(spec)}`);
  }
  return task;
}

export function validateCreatedTaskSet(tasks, scenario, ownerName) {
  if (!Array.isArray(tasks) || tasks.length !== 1) {
    throw new Error(`Minecraft T chat created ${Array.isArray(tasks) ? tasks.length : 0} tasks instead of one`);
  }
  return validateTask(tasks[0], scenario, ownerName);
}

export function validateDelivered(value, scenario, task) {
  invariant(value.scenario === scenario.code, "Final fixture scenario code is wrong", value);
  invariant(value.tables === 1 && value.newTables === 0 && value.atExistingTable === 1,
    "NPC did not exclusively use the existing crafting table", value);
  invariant(value.npcOutput === 0 && value.playerOutput + value.worldOutput === scenario.count,
    "Crafted output was not physically preserved for the owner", value);
  invariant(value.sticks === 0 && value.secondary === 0,
    "Recipe ingredients were not consumed exactly", value);
  invariant(value.deliverySeen === 1 && value.unexpectedBreaks === 0 && value.unknownWorldEdits === 0,
    "Physical owner delivery or world-integrity evidence is incomplete", value);
  invariant(task.status === "succeeded", "Craft task did not succeed", task);
  invariant(Number(task.completedCount ?? scenario.count) === scenario.count,
    "Craft completed count is not exact", task);
  invariant(Number(task.targetCount ?? scenario.count) === scenario.count,
    "Craft target count is not exact", task);
  return value;
}

export function validateCleanupStatus(status) {
  if (!["rp:cleanup=restored", "rp:cleanup=none"].includes(status)) {
    throw new Error(`Explicit crafting cleanup refused unknown content: ${JSON.stringify(status)}`);
  }
  return status;
}

async function sendMinecraftTChat(message) {
  await execFileAsync("powershell.exe", [
    "-NoProfile", "-ExecutionPolicy", "Bypass",
    "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
    "-MessageUtf8Base64", Buffer.from(message, "utf8").toString("base64"),
    "-RespawnIfDead",
  ], { cwd: projectRoot, windowsHide: true, timeout: 30_000, maxBuffer: 500_000 });
}

export function selectNewCompanionTasks(response, companionId, priorIds) {
  return response?.tasks?.filter((candidate) => (
    candidate.companionId === companionId && !priorIds.has(candidate.id)
  )) ?? [];
}

async function waitForNewTasks(base, companionId, priorIds) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const tasks = selectNewCompanionTasks(await request(base, "/api/tasks"), companionId, priorIds);
    if (tasks.length > 0) {
      await new Promise((resolve) => setTimeout(resolve, 250));
      return selectNewCompanionTasks(await request(base, "/api/tasks"), companionId, priorIds);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("Minecraft T chat did not create an explicit crafting task");
}

async function waitForTerminal(base, task, waitMs, requireSuccess = true) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Explicit crafting task ${task.id} timed out`);
  if (requireSuccess && current.status !== "succeeded") {
    throw new Error(`Explicit crafting task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return current;
}

async function cancelIfActive(base, task) {
  if (!task?.id) return;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live explicit crafting fixture cleanup" },
    });
  }
  await waitForTerminal(base, current, 30_000, false);
}

async function cleanup(base, companionId) {
  return validateCleanupStatus((await fixture(base, companionId, "cleanup")).status);
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 300;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) {
    throw new Error("--wait-seconds must be between 30 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveExplicitCraftingSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      startsMinecraft: false,
      externalApi: false,
      actor: "ai-npc",
      usedMinecraftTChat: true,
      scenarios: CRAFT_SCENARIOS,
    };
  }
  const companion = await connectedCompanion(options.base);
  const results = [];
  let currentTask = null;
  try {
    if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
    for (const scenario of CRAFT_SCENARIOS) {
      await cleanup(options.base, companion.id);
      const prior = await request(options.base, "/api/tasks");
      const priorIds = new Set(prior.tasks?.map((task) => task.id) ?? []);
      const setupSnapshot = await fixture(options.base, companion.id, scenario.setupMode);
      if (setupSnapshot.snapshot.materialMode !== "survival") {
        throw new Error(`Explicit crafting fixture requires survival mode, received ${setupSnapshot.snapshot.materialMode}`);
      }
      const setup = parseSetupStatus(setupSnapshot.status, scenario);
      const initial = validateInitial(
        parseInspection((await fixture(options.base, companion.id, "inspect-craft")).status),
        scenario,
      );
      await sendMinecraftTChat(scenario.prompt);
      currentTask = validateCreatedTaskSet(
        await waitForNewTasks(options.base, companion.id, priorIds),
        scenario,
        companion.ownerName,
      );
      const terminal = await waitForTerminal(options.base, currentTask, options.waitMs);
      currentTask = null;
      const delivered = validateDelivered(
        parseInspection((await fixture(options.base, companion.id, "inspect-craft")).status),
        scenario,
        terminal,
      );
      results.push({ setup, initial, delivered, task: terminal });
    }
    return {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      actor: "ai-npc",
      usedMinecraftTChat: true,
      companionId: companion.id,
      results,
    };
  } finally {
    await cancelIfActive(options.base, currentTask);
    await cleanup(options.base, companion.id);
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveExplicitCraftingSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live explicit crafting smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
