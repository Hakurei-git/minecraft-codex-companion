import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const INSPECTION_KEYS = [
  "tables", "newTables", "atExistingTable", "npcPick", "playerPick", "worldPick",
  "localRemaining", "remoteRemaining", "localBreaks", "remoteBreaks", "orderViolations",
  "npcCoal", "playerCoal", "worldCoal", "coalDelivered", "unexpectedBreaks",
];

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live resource priority smoke only connects to a loopback HTTP service");
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

export function fixtureExpectedPrefix(mode) {
  if (mode === "setup") return "rp:setup=";
  if (mode === "inspect") return "rp:i=";
  if (mode === "cleanup") return "rp:cleanup=";
  throw new Error(`Unsupported resource priority fixture mode ${mode}`);
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
      throw new Error(`Minecraft rejected resource priority fixture ${mode}: ${status}`);
    }
    if (!status.startsWith(fixtureExpectedPrefix(mode))) {
      throw new Error(`Unexpected resource priority fixture acknowledgement: ${status}`);
    }
    return { ...current, status };
  }
  throw new Error(`Minecraft did not acknowledge resource priority fixture ${mode}`);
}

export function workstationTaskSpec() {
  return {
    kind: "craft",
    itemId: "minecraft:diamond_pickaxe",
    count: 1,
    deliverTo: "owner",
    requestedBy: "live-resource-priority-smoke",
    note: "Use the fixed nearby existing crafting table; never make or place another",
  };
}

export function gatherTaskSpec() {
  return {
    kind: "gather",
    itemId: "minecraft:coal",
    count: 12,
    movement: "walk",
    requestedBy: "live-resource-priority-smoke",
    note: "Exhaust the fixed local 26-neighbor coal vein before the separated vein",
  };
}

export function deliveryTaskSpec() {
  return {
    kind: "deliver",
    itemId: "minecraft:coal",
    count: 12,
    player: "owner",
    requestedBy: "live-resource-priority-smoke",
    note: "Deliver exactly the twelve retained fixture coal items",
  };
}

export function parseSetupStatus(status) {
  const match = /^rp:setup=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected resource priority setup status: ${JSON.stringify(status)}`);
  return { origin: { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) } };
}

export function parseInspection(status) {
  const match = /^rp:i=([0-9]+(?:,[0-9]+){15})$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected resource priority inspection status: ${JSON.stringify(status)}`);
  const values = match[1].split(",").map(Number);
  return Object.fromEntries(INSPECTION_KEYS.map((key, index) => [key, values[index]]));
}

function invariant(condition, message, value) {
  if (!condition) throw new Error(`${message}: ${JSON.stringify(value)}`);
}

export function validateInitial(value) {
  invariant(value.tables === 1 && value.newTables === 0 && value.atExistingTable === 0,
    "Initial workstation fixture is invalid", value);
  invariant(value.npcPick + value.playerPick + value.worldPick === 0,
    "Initial fixture already contains a diamond pickaxe", value);
  invariant(value.localRemaining === 8 && value.remoteRemaining === 8
    && value.localBreaks === 0 && value.remoteBreaks === 0 && value.orderViolations === 0,
  "Initial coal veins are invalid", value);
  invariant(value.npcCoal + value.playerCoal + value.worldCoal === 0
    && value.coalDelivered === 0 && value.unexpectedBreaks === 0,
  "Initial retained-item fixture is invalid", value);
  return value;
}

export function validateWorkstation(value) {
  invariant(value.tables === 1 && value.newTables === 0 && value.atExistingTable === 1,
    "NPC did not exclusively use the nearby existing crafting table", value);
  invariant(value.npcPick + value.playerPick + value.worldPick === 1,
    "Diamond pickaxe was not preserved through owner delivery", value);
  invariant(value.localRemaining === 8 && value.remoteRemaining === 8
    && value.localBreaks === 0 && value.remoteBreaks === 0,
  "Workstation phase unexpectedly modified coal", value);
  invariant(value.unexpectedBreaks === 0, "Workstation phase broke an unrelated block", value);
  return value;
}

export function validateRetainedGather(value, task) {
  invariant(value.localRemaining === 0 && value.remoteRemaining === 4,
    "Gather did not exhaust local vein before taking four remote blocks", value);
  invariant(value.localBreaks === 8 && value.remoteBreaks === 4
    && value.orderViolations === 0 && value.unexpectedBreaks === 0,
  "Coal break ordering proof failed", value);
  invariant(value.npcCoal === 12 && value.playerCoal === 0 && value.worldCoal === 0,
    "The twelve gathered coal items were not retained by the NPC", value);
  invariant(task.completedCount === 12 && task.targetCount === 12 && task.retainedCount === 12,
    "Structured gather counts are not exact", task);
  return value;
}

export function validateDelivered(value) {
  invariant(value.npcCoal === 0 && value.playerCoal + value.worldCoal === 12
    && value.coalDelivered === 1,
  "Exactly twelve retained coal items were not delivered to the owner", value);
  invariant(value.localBreaks + value.remoteBreaks === 12 && value.orderViolations === 0,
    "Coal delivery does not match physical break evidence", value);
  return value;
}

export function validateCleanupStatus(status) {
  if (!["rp:cleanup=restored", "rp:cleanup=none"].includes(status)) {
    throw new Error(`Resource priority cleanup refused unknown content: ${JSON.stringify(status)}`);
  }
  return status;
}

async function createTask(base, companionId, spec) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec, owner: "live-resource-priority-smoke" },
  });
}

export async function waitForTerminal(base, task, waitMs, requireSuccess = true) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Resource priority task ${task.id} timed out`);
  if (requireSuccess && current.status !== "succeeded") {
    throw new Error(`Resource priority task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return current;
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live resource priority fixture cleanup" },
    });
  }
  return waitForTerminal(base, current, waitMs, false);
}

async function cleanupAndConfirm(base, companionId) {
  return validateCleanupStatus(String((await fixture(base, companionId, "cleanup")).status ?? ""));
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

export async function runLiveResourcePrioritySmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      startsMinecraft: false,
      externalApi: false,
      tasks: [workstationTaskSpec(), gatherTaskSpec(), deliveryTaskSpec()],
      fixture: { suite: "resource-priority", modes: ["setup", "inspect", "cleanup"] },
    };
  }
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  let currentTask = null;
  try {
    await cleanupAndConfirm(options.base, companion.id);
    const setupSnapshot = await fixture(options.base, companion.id, "setup");
    if (setupSnapshot.materialMode !== "survival") {
      throw new Error(`Resource priority fixture requires survival mode, received ${setupSnapshot.materialMode}`);
    }
    const setup = parseSetupStatus(setupSnapshot.status);
    const initial = validateInitial(parseInspection((await fixture(options.base, companion.id, "inspect")).status));

    currentTask = await createTask(options.base, companion.id, workstationTaskSpec());
    const workstationTask = await waitForTerminal(options.base, currentTask, options.waitMs);
    currentTask = null;
    const workstation = validateWorkstation(parseInspection(
      (await fixture(options.base, companion.id, "inspect")).status,
    ));

    currentTask = await createTask(options.base, companion.id, gatherTaskSpec());
    const gatherTask = await waitForTerminal(options.base, currentTask, options.waitMs);
    currentTask = null;
    const retained = validateRetainedGather(parseInspection(
      (await fixture(options.base, companion.id, "inspect")).status,
    ), gatherTask);

    currentTask = await createTask(options.base, companion.id, deliveryTaskSpec());
    const deliveryTask = await waitForTerminal(options.base, currentTask, options.waitMs);
    currentTask = null;
    const delivered = validateDelivered(parseInspection(
      (await fixture(options.base, companion.id, "inspect")).status,
    ));
    return {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      companionId: companion.id,
      setup,
      initial,
      workstation,
      retained,
      delivered,
      tasks: [workstationTask, gatherTask, deliveryTask].map((task) => ({
        id: task.id,
        status: task.status,
        completedCount: task.completedCount,
        targetCount: task.targetCount,
        retainedCount: task.retainedCount,
      })),
    };
  } finally {
    await cancelAndWait(options.base, currentTask, Math.min(options.waitMs, 30_000));
    await cleanupAndConfirm(options.base, companion.id);
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveResourcePrioritySmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live resource priority smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
