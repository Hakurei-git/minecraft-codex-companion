import { spawn } from "node:child_process";
import path from "node:path";
import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const projectRoot = path.resolve(import.meta.dirname, "..");
const BED_CHAT_MESSAGE = "制作一张床";
const INSPECTION_KEYS = [
  "iron", "logs", "planks", "shears", "wool", "tableItem", "bedItem",
  "logBreaks", "sheepSheared", "tablePlacements", "bedPlacements", "bedPair",
  "homeDistanceSq", "sawPlanks", "sawShears", "sawWool", "sawTable", "sawBed",
  "sawSleeping", "sleeping", "leftBed", "day", "recipes", "errors",
];

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live bed sleep smoke only connects to a loopback HTTP service");
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
    throw new Error(`${method} ${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 1_000)}`);
  }
  return response.status === 204 ? null : response.json();
}

function runBackgroundChat(message) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  const script = path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1");
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-File", script,
      "-MessageUtf8Base64", encoded,
      "-RespawnIfDead",
    ], {
      cwd: projectRoot,
      windowsHide: true,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve(stdout);
      else reject(new Error(`Background Minecraft chat failed (${code}): ${stderr.slice(-1_000)}`));
    });
  });
}

async function connectedCompanion(base) {
  const response = await request(base, "/api/companions");
  const companion = response.companions?.find((candidate) => (
    candidate.connected === true && candidate.embodiment === "in-world-npc"
  ));
  if (!companion?.id) throw new Error("No connected Forge in-world NPC was found");
  return companion;
}

async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

export function fixturePrefix(mode) {
  if (mode === "setup") return "bed-sleep:setup|";
  if (mode === "inspect") return "bed-sleep:i|";
  if (mode === "prepare-night") return "bed-sleep:night|";
  if (mode === "wake-day") return "bed-sleep:day|";
  if (mode === "cleanup") return "bed-sleep:cleanup|";
  throw new Error(`Unsupported bed sleep fixture mode ${mode}`);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (!acknowledgement
    || Number(acknowledgement.sequence) <= Number(beforeSequence)
    || acknowledgement.suite !== "bed-sleep"
    || acknowledgement.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

function fixtureRetryable(mode) {
  return mode === "inspect";
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const pathname = `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`;
  const body = { suite: "bed-sleep", mode };
  await request(base, pathname, { method: "POST", body });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 300;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ") || status.startsWith("live-fixture:failed ")) {
        throw new Error(`Minecraft rejected bed sleep fixture ${mode}: ${status}`);
      }
      if (!status.startsWith(fixturePrefix(mode))) {
        throw new Error(`Unexpected bed sleep fixture acknowledgement: ${status}`);
      }
      return status;
    }
    if (fixtureRetryable(mode) && Date.now() >= nextRetry) {
      await request(base, pathname, { method: "POST", body });
      nextRetry = Date.now() + 300;
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`Minecraft did not acknowledge bed sleep fixture ${mode}`);
}

export function taskSpecs() {
  return {
    bed: {
      kind: "macro",
      skillId: "life.craft-and-place-bed",
      arguments: {},
      materialMode: "survival",
      requestedBy: "live-bed-sleep-smoke",
      note: "Reversible missing wood and wool dependency chain with physical home bed placement",
    },
    sleep: {
      kind: "sleep",
      radius: 32,
      requestedBy: "live-bed-sleep-smoke",
      note: "Reversible physical sleep and leave-bed acceptance",
    },
  };
}

export function parseSetupStatus(status) {
  const match = /^bed-sleep:setup\|home=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected bed sleep setup status: ${JSON.stringify(status)}`);
  return { home: { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) } };
}

export function parseInspection(status) {
  const match = /^bed-sleep:i\|([^|]+)\|bed=(none|-?\d+,-?\d+,-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected bed sleep inspection: ${JSON.stringify(status)}`);
  const values = match[1].split(",");
  if (values.length !== INSPECTION_KEYS.length || values.some((value) => !/^-?\d+$/u.test(value))) {
    throw new Error(`Bed sleep inspection fields are invalid: ${JSON.stringify(status)}`);
  }
  const result = Object.fromEntries(INSPECTION_KEYS.map((key, index) => [key, Number(values[index])]));
  result.bed = match[2] === "none"
    ? null
    : Object.fromEntries(["x", "y", "z"].map((key, index) => [key, Number(match[2].split(",")[index])]));
  return result;
}

export function validateInitialInspection(value) {
  if (value.iron !== 2 || value.recipes !== 1 || value.homeDistanceSq !== -1 || value.bed !== null
    || value.day < 0 || value.day > 1 || value.errors !== 0) {
    throw new Error(`Initial bed sleep fixture state is invalid: ${JSON.stringify(value)}`);
  }
  for (const key of [
    "logs", "planks", "shears", "wool", "tableItem", "bedItem", "logBreaks", "sheepSheared",
    "tablePlacements", "bedPlacements", "bedPair", "sawPlanks", "sawShears", "sawWool",
    "sawTable", "sawBed", "sawSleeping", "sleeping", "leftBed",
  ]) {
    if (value[key] !== 0) throw new Error(`Initial bed sleep field ${key} must be zero: ${JSON.stringify(value)}`);
  }
  return value;
}

export function validateCraftedInspection(value) {
  for (const key of ["sawPlanks", "sawShears", "sawWool", "sawTable", "sawBed", "bedPair", "recipes"]) {
    if (value[key] !== 1) throw new Error(`Bed dependency proof ${key} is missing: ${JSON.stringify(value)}`);
  }
  if (value.logBreaks < 2 || value.sheepSheared < 1 || value.tablePlacements < 1 || value.bedPlacements < 1
    || value.homeDistanceSq < 0 || value.homeDistanceSq > 200 || value.bed === null || value.errors !== 0) {
    throw new Error(`Crafted bed invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function validateNightInspection(value) {
  validateCraftedInspection(value);
  if (value.day !== 0 || value.sleeping !== 0) {
    throw new Error(`Prepared night invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function validateSleepingInspection(value) {
  validateCraftedInspection(value);
  if (value.day !== 0 || value.sawSleeping !== 1 || value.sleeping !== 1) {
    throw new Error(`Sleeping invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function validateAwakeInspection(value) {
  validateCraftedInspection(value);
  if (value.day !== 1 || value.sawSleeping !== 1 || value.sleeping !== 0 || value.leftBed !== 1) {
    throw new Error(`Leave-bed invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function parseCleanupStatus(status) {
  if (status === "bed-sleep:cleanup|none") return { none: true };
  const match = /^bed-sleep:cleanup\|r=([01]),([01]),([01]),([01]),([01]),([01]),([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected bed sleep cleanup status: ${JSON.stringify(status)}`);
  const [player, npc, time, weather, respawn, blocks, entities] = match.slice(1).map(Number);
  return { none: false, player, npc, time, weather, respawn, blocks, entities };
}

export function validateCleanup(value, allowNone = false) {
  if (value.none) {
    if (!allowNone) throw new Error("Bed sleep fixture disappeared before restoration evidence was recorded");
    return value;
  }
  for (const key of ["player", "npc", "time", "weather", "respawn", "blocks", "entities"]) {
    if (value[key] !== 1) throw new Error(`Bed sleep cleanup proof ${key} is missing: ${JSON.stringify(value)}`);
  }
  return value;
}

async function assign(base, companionId, spec) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec, owner: "live-bed-sleep-smoke" },
  });
}

export function isNewBedChatTask(task, companionId, existingTaskIds) {
  return !existingTaskIds.has(task?.id)
    && task?.companionId === companionId
    && task?.spec?.kind === "macro"
    && task.spec.skillId === "life.craft-and-place-bed";
}

async function assignViaTChat(base, companionId) {
  const before = await request(base, "/api/tasks");
  const existingTaskIds = new Set((before.tasks ?? []).map((task) => task.id));
  await runBackgroundChat(BED_CHAT_MESSAGE);
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const current = await request(base, "/api/tasks");
    const task = (current.tasks ?? []).find((candidate) => (
      isNewBedChatTask(candidate, companionId, existingTaskIds)
    ));
    if (task) return task;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Minecraft T chat did not create the bed macro task");
}

async function taskRecord(base, task) {
  return request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
}

async function waitTerminal(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 200));
    current = await taskRecord(base, task);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Task ${task.id} timed out: ${JSON.stringify(current)}`);
  return current;
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await taskRecord(base, task);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live bed sleep fixture cleanup" },
    });
  }
  return waitTerminal(base, current, waitMs);
}

async function inspectUntil(base, companionId, validator, waitMs) {
  const deadline = Date.now() + waitMs;
  let last = null;
  let failure = null;
  while (Date.now() < deadline) {
    last = parseInspection(await fixture(base, companionId, "inspect"));
    try {
      return validator(last);
    } catch (caught) {
      failure = caught;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw failure ?? new Error(`Bed sleep inspection did not converge: ${JSON.stringify(last)}`);
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 300;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) {
    throw new Error("--wait-seconds must be between 30 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    triggerViaChat: argv.includes("--trigger-via-chat"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveBedSleepSmoke(options) {
  const specs = taskSpecs();
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      startsMinecraft: false,
      triggerViaChat: options.triggerViaChat === true,
      specs,
      fixture: { suite: "bed-sleep", modes: ["setup", "inspect", "prepare-night", "wake-day", "cleanup"] },
    };
  }

  const companion = await connectedCompanion(options.base);
  const before = await snapshot(options.base, companion.id);
  if (before.activeTaskId || Number(before.pausedTaskCount ?? 0) > 0 || before.taskSchedulerLifecycle !== "idle") {
    throw new Error(`Companion must be completely idle before bed sleep acceptance: ${JSON.stringify({
      activeTaskId: before.activeTaskId,
      pausedTaskCount: before.pausedTaskCount,
      lifecycle: before.taskSchedulerLifecycle,
    })}`);
  }

  let activeTask = null;
  let setupComplete = false;
  let result = null;
  let cleanupEvidence = null;
  try {
    validateCleanup(parseCleanupStatus(await fixture(options.base, companion.id, "cleanup")), true);
    const setup = parseSetupStatus(await fixture(options.base, companion.id, "setup"));
    setupComplete = true;
    const initial = validateInitialInspection(parseInspection(await fixture(options.base, companion.id, "inspect")));

    activeTask = options.triggerViaChat
      ? await assignViaTChat(options.base, companion.id)
      : await assign(options.base, companion.id, specs.bed);
    const bedTask = await waitTerminal(options.base, activeTask, options.waitMs);
    if (bedTask.status !== "succeeded") {
      const failureInspection = parseInspection(await fixture(options.base, companion.id, "inspect"));
      throw new Error(`Bed macro failed: ${bedTask.error?.code ?? bedTask.message ?? bedTask.status}; fixture=${JSON.stringify(failureInspection)}`);
    }
    activeTask = null;
    const crafted = validateCraftedInspection(parseInspection(await fixture(options.base, companion.id, "inspect")));

    await fixture(options.base, companion.id, "prepare-night");
    const night = await inspectUntil(
      options.base,
      companion.id,
      validateNightInspection,
      Math.min(options.waitMs, 15_000),
    );

    activeTask = await assign(options.base, companion.id, specs.sleep);
    const sleeping = await inspectUntil(
      options.base,
      companion.id,
      validateSleepingInspection,
      Math.min(options.waitMs, 30_000),
    );
    await fixture(options.base, companion.id, "wake-day");
    const sleepTask = await waitTerminal(options.base, activeTask, options.waitMs);
    if (sleepTask.status !== "succeeded") {
      throw new Error(`Sleep task failed: ${sleepTask.error?.code ?? sleepTask.message ?? sleepTask.status}`);
    }
    activeTask = null;
    const awake = await inspectUntil(
      options.base,
      companion.id,
      validateAwakeInspection,
      Math.min(options.waitMs, 15_000),
    );

    result = {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      triggeredViaTChat: options.triggerViaChat === true,
      companionId: companion.id,
      setup,
      initial,
      bedTask: { id: bedTask.id, status: bedTask.status, message: bedTask.message },
      crafted,
      night,
      sleeping,
      sleepTask: { id: sleepTask.id, status: sleepTask.status, message: sleepTask.message },
      awake,
    };
  } finally {
    await cancelAndWait(options.base, activeTask, Math.min(options.waitMs, 30_000));
    cleanupEvidence = validateCleanup(
      parseCleanupStatus(await fixture(options.base, companion.id, "cleanup")),
      !setupComplete,
    );
  }
  return { ...result, cleanup: cleanupEvidence };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveBedSleepSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live bed sleep smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
