import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const MODS = Object.freeze({ bookofdragons: "b", saintsdragons: "s" });
const ACTIONS = Object.freeze({ feed: "f", heal: "h", tame: "t", egg: "e" });

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live dragon care smoke only connects to a loopback HTTP service");
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

export function fixturePrefix(mode) {
  if (mode === "setup-book") return "dragon-care:setup|b";
  if (mode === "setup-saints") return "dragon-care:setup|s";
  if (mode.startsWith("stage-")) return "dragon-care:s|";
  if (mode.startsWith("inspect-")) return "dragon-care:i|";
  if (mode === "cleanup") return "dragon-care:cleanup|";
  throw new Error(`Unsupported dragon care fixture mode ${mode}`);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (!acknowledgement
    || Number(acknowledgement.sequence) <= Number(beforeSequence)
    || acknowledgement.suite !== "dragon-care"
    || acknowledgement.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const pathname = `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`;
  const body = { suite: "dragon-care", mode };
  await request(base, pathname, { method: "POST", body });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 300;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ") || status.startsWith("live-fixture:failed ")) {
        throw new Error(`Minecraft rejected dragon care fixture ${mode}: ${status}`);
      }
      if (!status.startsWith(fixturePrefix(mode))) {
        throw new Error(`Unexpected dragon care fixture acknowledgement: ${status}`);
      }
      return status;
    }
    if ((mode.startsWith("inspect-") || mode === "cleanup") && Date.now() >= nextRetry) {
      await request(base, pathname, { method: "POST", body });
      nextRetry = Date.now() + 300;
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`Minecraft did not acknowledge dragon care fixture ${mode}`);
}

export function isNpcNotIdleFixtureError(error) {
  return error instanceof Error && error.message.includes("code=npc-not-idle");
}

async function fixtureWhenIdle(base, companionId, mode, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  do {
    await waitForIdleCompanion(
      () => snapshot(base, companionId),
      { timeoutMs: Math.max(1, deadline - Date.now()) },
    );
    try {
      return await fixture(base, companionId, mode);
    } catch (error) {
      if (!isNpcNotIdleFixtureError(error)) throw error;
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  } while (Date.now() < deadline);
  throw new Error(`Dragon care fixture ${mode} did not acquire an idle scheduler: ${lastError instanceof Error ? lastError.message : String(lastError)}`);
}

export function parseStage(status, expectedMod, expectedAction) {
  const match = /^dragon-care:s\|([bs])\|([fhte])\|([a-z0-9:._-]{1,128})$/u.exec(status ?? "");
  const modCode = MODS[expectedMod];
  const actionCode = ACTIONS[expectedAction];
  if (!match || match[1] !== modCode || match[2] !== actionCode) {
    throw new Error(`Unexpected dragon care stage: ${JSON.stringify(status)}`);
  }
  const targetId = match[3];
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(targetId);
  if (!uuid && !(expectedMod === "saintsdragons" && expectedAction === "egg"
    && targetId === "saintsdragons:raevyx_egg")) {
    throw new Error(`Unexpected dragon care target: ${JSON.stringify(targetId)}`);
  }
  return { modId: expectedMod, action: expectedAction, targetId };
}

export function parseInspection(status) {
  const match = /^dragon-care:i\|([bs])\|([fhte])\|(\d+)\|(-?\d+)\|(-?\d+)\|(-?\d+)\|([01])\|([01])\|(-?\d+)\|([01])\|([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected dragon care inspection: ${JSON.stringify(status)}`);
  return {
    modId: match[1] === "b" ? "bookofdragons" : "saintsdragons",
    action: Object.keys(ACTIONS).find((key) => ACTIONS[key] === match[2]),
    consumed: Number(match[3]),
    healthDeltaMilli: Number(match[4]),
    foodDeltaMilli: Number(match[5]),
    happinessDeltaMilli: Number(match[6]),
    owned: Number(match[7]),
    present: Number(match[8]),
    eggDeltaMilli: Number(match[9]),
    sameTarget: Number(match[10]),
    ownershipChanged: Number(match[11]),
  };
}

export function validateInspection(value, expectedMod, expectedAction) {
  if (value.modId !== expectedMod || value.action !== expectedAction
    || value.present !== 1 || value.sameTarget !== 1) {
    throw new Error(`Dragon care identity invariant failed: ${JSON.stringify(value)}`);
  }
  const valid = expectedAction === "feed"
    ? value.consumed >= 1 && (value.foodDeltaMilli > 0 || value.happinessDeltaMilli > 0)
    : expectedAction === "heal"
      ? value.consumed >= 1 && value.healthDeltaMilli > 0
      : expectedAction === "tame"
        ? value.consumed >= 1 && value.owned === 1 && value.ownershipChanged === 1
        : value.eggDeltaMilli > 0;
  if (!valid) throw new Error(`Dragon care ${expectedAction} invariant failed: ${JSON.stringify(value)}`);
  return value;
}

export function taskSpec(action, targetId) {
  if (!Object.hasOwn(ACTIONS, action)) throw new Error(`Unsupported dragon care action ${action}`);
  return {
    kind: "dragon",
    action: action === "egg" ? "care-for-egg" : action,
    targetId,
    requestedBy: "live-dragon-care-smoke",
    note: "Fixed reversible dragon care acceptance",
  };
}

export function requireIdleCompanion(companion, current = companion?.snapshot ?? {}) {
  if (!companion?.id) throw new Error("Dragon care smoke requires a connected companion");
  if (current.npcDowned === true || current.taskSchedulerLifecycle === "downed") {
    throw new Error("Dragon care smoke requires an active NPC");
  }
  const activeTaskId = current.activeTaskId ?? companion.activeTaskId;
  const queued = Array.isArray(current.taskQueue) ? current.taskQueue.length : 0;
  const paused = Number(current.pausedTaskCount ?? 0);
  const lifecycle = current.taskSchedulerLifecycle;
  if (activeTaskId || queued > 0 || paused > 0 || (lifecycle !== undefined && lifecycle !== "idle")) {
    throw new Error("Dragon care smoke requires an idle NPC with an empty task queue");
  }
  return companion;
}

export function isIdleCompanionSnapshot(current = {}) {
  const queued = Array.isArray(current.taskQueue) ? current.taskQueue.length : 0;
  return current.npcDowned !== true
    && !current.activeTaskId
    && queued === 0
    && Number(current.pausedTaskCount ?? 0) === 0
    && current.taskSchedulerLifecycle === "idle"
    && current.automaticEating !== true;
}

export async function waitForIdleCompanion(readSnapshot, options = {}) {
  const timeoutMs = Number(options.timeoutMs ?? 10_000);
  const intervalMs = Number(options.intervalMs ?? 25);
  const deadline = Date.now() + timeoutMs;
  let current = null;
  do {
    current = await readSnapshot();
    if (isIdleCompanionSnapshot(current)) return current;
    if (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, Math.max(0, intervalMs)));
    }
  } while (Date.now() < deadline);
  throw new Error(`Dragon care NPC did not become idle before cleanup: ${JSON.stringify({
    lifecycle: current?.taskSchedulerLifecycle,
    activeTaskId: current?.activeTaskId,
    pausedTaskCount: current?.pausedTaskCount,
    queued: Array.isArray(current?.taskQueue) ? current.taskQueue.length : 0,
    automaticEating: current?.automaticEating === true,
  })}`);
}

function normalizedInventory(inventory) {
  return (Array.isArray(inventory) ? inventory : []).map((item) => ({
    id: String(item.id ?? ""),
    count: Number(item.count ?? 0),
    slot: Number(item.slot ?? -1),
    slotType: String(item.slotType ?? ""),
    damage: Number(item.damage ?? 0),
  })).sort((left, right) => left.slot - right.slot || left.id.localeCompare(right.id));
}

export function captureRestorationBaseline(current) {
  const position = current?.position ?? {};
  return {
    npcEntityUuid: String(current?.npcEntityUuid ?? ""),
    dimension: String(current?.dimension ?? ""),
    gameMode: String(current?.gameMode ?? ""),
    stance: String(current?.stance ?? ""),
    status: String(current?.status ?? ""),
    health: Number(current?.health),
    maxHealth: Number(current?.maxHealth),
    food: Number(current?.food),
    saturation: Number(current?.saturation),
    exhaustion: Number(current?.exhaustion),
    materialMode: String(current?.materialMode ?? ""),
    npcDowned: current?.npcDowned === true,
    position: {
      x: Number(position.x),
      y: Number(position.y),
      z: Number(position.z),
    },
    yaw: Number(current?.yaw),
    pitch: Number(current?.pitch),
    inventory: normalizedInventory(current?.inventory),
    boundDragon: current?.dragonState ? {
      modId: String(current.dragonState.modId ?? ""),
      entityId: String(current.dragonState.entityId ?? ""),
      mounted: current.dragonState.mounted === true,
    } : null,
  };
}

function closeNumber(left, right, epsilon = 0.001) {
  return Number.isFinite(left) && Number.isFinite(right) && Math.abs(left - right) <= epsilon;
}

export function restorationDifferences(baseline, current, options = {}) {
  const restored = captureRestorationBaseline(current);
  const differences = [];
  for (const key of [
    "npcEntityUuid", "dimension", "gameMode", "stance", "status", "materialMode", "npcDowned",
  ]) {
    if (restored[key] !== baseline[key]) differences.push(key);
  }
  const motionMayResume = options.allowMotion === true;
  const numericKeys = motionMayResume
    ? ["health", "maxHealth", "food", "saturation"]
    : ["health", "maxHealth", "food", "saturation", "exhaustion", "yaw", "pitch"];
  for (const key of numericKeys) {
    if (!closeNumber(restored[key], baseline[key])) differences.push(key);
  }
  if (!motionMayResume) {
    for (const axis of ["x", "y", "z"]) {
      if (!closeNumber(restored.position[axis], baseline.position[axis], 0.125)) {
        differences.push(`position.${axis}`);
      }
    }
  }
  if (JSON.stringify(restored.inventory) !== JSON.stringify(baseline.inventory)) differences.push("inventory");
  if (JSON.stringify(restored.boundDragon) !== JSON.stringify(baseline.boundDragon)) differences.push("boundDragon");
  return differences;
}

export async function waitForRestoration(readSnapshot, baseline, options = {}) {
  const timeoutMs = Number(options.timeoutMs ?? 5_000);
  const intervalMs = Number(options.intervalMs ?? 25);
  const deadline = Date.now() + timeoutMs;
  let current = null;
  let differences = ["snapshot"];
  do {
    current = await readSnapshot();
    differences = restorationDifferences(baseline, current, options);
    if (differences.length === 0) return current;
    if (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, Math.max(0, intervalMs)));
    }
  } while (Date.now() < deadline);
  throw new Error(`Dragon care cleanup did not restore baseline fields: ${differences.join(", ")}`);
}

export async function finalizeCareRun(cancel, clean) {
  let cancelError = null;
  let cleanupError = null;
  try {
    await cancel();
  } catch (error) {
    cancelError = error;
  }
  try {
    await clean();
  } catch (error) {
    cleanupError = error;
  }
  if (cancelError && cleanupError) {
    throw new AggregateError([cancelError, cleanupError], "Dragon care cancellation and cleanup both failed");
  }
  if (cancelError) throw cancelError;
  if (cleanupError) throw cleanupError;
}

export function combineCareRunErrors(primaryError, finalizationError) {
  if (primaryError && finalizationError) {
    return new AggregateError(
      [primaryError, finalizationError],
      "Dragon care action and restoration both failed",
    );
  }
  return primaryError ?? finalizationError ?? null;
}

async function assign(base, companionId, spec) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec, owner: "live-dragon-care-smoke" },
  });
}

async function taskRecord(base, task) {
  return request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
}

async function waitTerminal(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (Date.now() < deadline) {
    current = await taskRecord(base, task);
    if (TERMINAL.has(current.status)) return current;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Dragon care task ${task.id} timed out: ${JSON.stringify(current)}`);
}

async function cancelTask(base, task, waitMs) {
  if (!task?.id) return;
  let current = await taskRecord(base, task);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live dragon care fixture cleanup" },
    });
  }
  if (!TERMINAL.has(current.status)) await waitTerminal(base, current, waitMs);
}

async function cleanup(base, companionId) {
  const status = await fixtureWhenIdle(base, companionId, "cleanup");
  if (status !== "dragon-care:cleanup|none") {
    const match = /^dragon-care:cleanup\|restored\|dim=([01])\|gm=([01])\|ability=([01])\|inv=([01])\|npc=([01])$/u.exec(status);
    if (!match || match.slice(1).some((value) => value !== "1")) {
      throw new Error(`Dragon care cleanup was not confirmed: ${JSON.stringify(status)}`);
    }
  }
  if (!status.startsWith("dragon-care:cleanup|")) {
    throw new Error(`Dragon care cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

async function runMod(base, companionId, modId, waitMs) {
  const result = {};
  let task = null;
  let baseline = null;
  let primaryError = null;
  try {
    await cleanup(base, companionId);
    baseline = captureRestorationBaseline(await snapshot(base, companionId));
    requireIdleCompanion({ id: companionId }, await snapshot(base, companionId));
    await fixtureWhenIdle(
      base,
      companionId,
      modId === "bookofdragons" ? "setup-book" : "setup-saints",
    );
    for (const action of Object.keys(ACTIONS)) {
      const staged = parseStage(
        await fixtureWhenIdle(base, companionId, `stage-${action}`),
        modId,
        action,
      );
      task = await assign(base, companionId, taskSpec(action, staged.targetId));
      const terminal = await waitTerminal(base, task, waitMs);
      if (terminal.status !== "succeeded") {
        throw new Error(`${modId} ${action} failed: ${terminal.error?.code ?? terminal.message}`);
      }
      await waitForIdleCompanion(
        () => snapshot(base, companionId),
        { timeoutMs: Math.min(waitMs, 10_000) },
      );
      const inspection = validateInspection(
        parseInspection(await fixture(base, companionId, `inspect-${action}`)),
        modId,
        action,
      );
      result[action] = { taskId: terminal.id, status: terminal.status, inspection };
      task = null;
    }
  } catch (error) {
    primaryError = error;
  }

  let finalizationError = null;
  try {
    await finalizeCareRun(
      () => cancelTask(base, task, Math.min(waitMs, 30_000)),
      async () => {
        await cleanup(base, companionId);
        if (baseline !== null) {
          await waitForRestoration(
            () => snapshot(base, companionId),
            baseline,
            { allowMotion: baseline.stance === "follow" || baseline.stance === "guard" },
          );
        }
      },
    );
  } catch (error) {
    finalizationError = error;
  }
  const error = combineCareRunErrors(primaryError, finalizationError);
  if (error) throw error;
  return result;
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) {
    throw new Error("--wait-seconds must be between 30 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveDragonCareSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      mods: Object.keys(MODS),
      actions: Object.keys(ACTIONS),
    };
  }
  const companion = await connectedCompanion(options.base);
  requireIdleCompanion(companion, await snapshot(options.base, companion.id));
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    companionId: companion.id,
    bookofdragons: await runMod(options.base, companion.id, "bookofdragons", options.waitMs),
    saintsdragons: await runMod(options.base, companion.id, "saintsdragons", options.waitMs),
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveDragonCareSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live dragon care smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
