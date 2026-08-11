import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const INSPECTION_KEYS = [
  "raw", "logs", "planks", "sticks", "cobble", "wood", "table", "furnace",
  "rawIn", "fuel", "lit", "ingot", "crafted", "delivered", "npcPick", "playerPick",
  "worldPick", "logBreaks", "stoneBreaks", "tablePlace", "furnacePlace", "persist",
  "roundtrip", "same", "depth", "ironGoal", "gold", "diamond", "errors",
];

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live craft chain smoke only connects to a loopback HTTP service");
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
  if (mode === "setup") return "craft-chain-fixture:setup raw=3 origin=";
  if (mode === "inspect") return "craft-chain-fixture:i=";
  if (mode === "checkpoint") return "craft-chain-fixture:checkpoint same=";
  if (mode === "cleanup") return "craft-chain-fixture:cleanup ";
  throw new Error(`Unsupported craft chain fixture mode ${mode}`);
}

export function fixtureRetryable(mode) {
  return mode === "inspect" || mode === "checkpoint" || mode === "cleanup";
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "craft-chain"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const expected = fixtureExpectedPrefix(mode);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "craft-chain", mode },
  });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 250;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ")) {
        throw new Error(`Minecraft rejected craft chain fixture ${mode}: ${status}`);
      }
      if (status.startsWith("live-fixture:failed ") && !fixtureRetryable(mode)) {
        throw new Error(`Minecraft craft chain fixture ${mode} failed: ${status}`);
      }
      if (status.startsWith(expected)) return { ...current, status };
      if (!fixtureRetryable(mode)) {
        throw new Error(`Minecraft craft chain fixture ${mode} returned an unexpected acknowledgement: ${status}`);
      }
    }
    if (fixtureRetryable(mode) && Date.now() >= nextRetry) {
      await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
        method: "POST",
        body: { suite: "craft-chain", mode },
      });
      nextRetry = Date.now() + 250;
    }
  }
  throw new Error(`Minecraft did not acknowledge craft chain fixture ${mode}`);
}

export function craftTaskSpec() {
  return {
    kind: "craft",
    itemId: "minecraft:iron_pickaxe",
    count: 1,
    deliverTo: "owner",
    requestedBy: "live-craft-chain-smoke",
    note: "Reversible survival dependency chain and persistent checkpoint acceptance",
  };
}

export function parseSetupStatus(status) {
  const match = /^craft-chain-fixture:setup raw=3 origin=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected craft chain setup status: ${JSON.stringify(status)}`);
  return { raw: 3, origin: { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) } };
}

export function parseInspection(status) {
  const prefix = "craft-chain-fixture:i=";
  if (typeof status !== "string" || !status.startsWith(prefix)) {
    throw new Error(`Unexpected craft chain inspection status: ${JSON.stringify(status)}`);
  }
  const entries = status.slice(prefix.length).split(",");
  if (entries.length !== INSPECTION_KEYS.length) {
    throw new Error(`Craft chain inspection field count is invalid: ${JSON.stringify(status)}`);
  }
  const result = {};
  for (let index = 0; index < INSPECTION_KEYS.length; index += 1) {
    const raw = entries[index];
    if (!/^-?\d+$/u.test(raw)) {
      throw new Error(`Craft chain inspection field ${index} is invalid: ${JSON.stringify(entries[index])}`);
    }
    result[INSPECTION_KEYS[index]] = Number(raw);
  }
  return result;
}

export function validateInitialInspection(value) {
  for (const key of [
    "logs", "planks", "sticks", "cobble", "wood", "table", "furnace", "rawIn", "fuel", "lit",
    "ingot", "crafted", "delivered", "npcPick", "playerPick", "worldPick", "logBreaks", "stoneBreaks",
    "tablePlace", "furnacePlace", "persist", "roundtrip", "same", "depth", "ironGoal", "errors",
  ]) {
    if (value[key] !== 0) throw new Error(`Initial craft chain field ${key} must be zero: ${JSON.stringify(value)}`);
  }
  if (value.raw !== 3 || value.gold !== 1 || value.diamond !== 1) {
    throw new Error(`Initial craft chain prerequisites are invalid: ${JSON.stringify(value)}`);
  }
  return value;
}

export function validateFinalInspection(value) {
  for (const key of [
    "wood", "table", "furnace", "rawIn", "fuel", "lit", "ingot", "crafted", "delivered",
    "persist", "same", "ironGoal", "gold", "diamond",
  ]) {
    if (value[key] !== 1) throw new Error(`Final craft chain proof ${key} is missing: ${JSON.stringify(value)}`);
  }
  if (value.raw !== 0
    || value.npcPick !== 0
    || value.playerPick + value.worldPick !== 1
    || value.logBreaks < 1
    || value.stoneBreaks < 8
    || value.tablePlace < 1
    || value.furnacePlace < 1
    || value.roundtrip < 1
    || value.depth < 1
    || value.errors !== 0) {
    throw new Error(`Final craft chain invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function parseCheckpointStatus(status) {
  const match = /^craft-chain-fixture:checkpoint same=(0|1),depth=(\d+),bytes=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected craft chain checkpoint status: ${JSON.stringify(status)}`);
  return { same: Number(match[1]), depth: Number(match[2]), bytes: Number(match[3]) };
}

export function validateCheckpoint(value) {
  if (value.same !== 1 || value.depth < 1 || value.bytes < 1) {
    throw new Error(`Craft chain checkpoint did not survive a round trip: ${JSON.stringify(value)}`);
  }
  return value;
}

export function validateCleanupStatus(status) {
  if (!["craft-chain-fixture:cleanup restored", "craft-chain-fixture:cleanup none"].includes(status)) {
    throw new Error(`Craft chain cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

async function waitForTerminalWithCheckpoint(base, companionId, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  let checkpoint = null;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    if (checkpoint === null) {
      const inspection = parseInspection(String((await fixture(base, companionId, "inspect")).status ?? ""));
      if (inspection.persist === 1 && inspection.ironGoal === 1) {
        checkpoint = validateCheckpoint(parseCheckpointStatus(
          String((await fixture(base, companionId, "checkpoint")).status ?? ""),
        ));
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Craft chain task ${task.id} timed out`);
  if (current.status !== "succeeded") {
    throw new Error(`Craft chain task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  if (checkpoint === null) throw new Error("Craft chain task finished without a persistent material checkpoint");
  return { task: current, checkpoint };
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live craft chain fixture cleanup" },
    });
  }
  const deadline = Date.now() + waitMs;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Craft chain task ${task.id} did not cancel before cleanup`);
  return current;
}

async function cleanupAndConfirm(base, companionId) {
  return validateCleanupStatus(String((await fixture(base, companionId, "cleanup")).status ?? ""));
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 420;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) {
    throw new Error("--wait-seconds must be between 30 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveCraftChainSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      startsMinecraft: false,
      spec: craftTaskSpec(),
      fixture: { suite: "craft-chain", modes: ["setup", "inspect", "checkpoint", "cleanup"] },
    };
  }
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  let task = null;
  try {
    await cleanupAndConfirm(options.base, companion.id);
    const setupSnapshot = await fixture(options.base, companion.id, "setup");
    if (setupSnapshot.materialMode !== "survival") {
      throw new Error(`Craft chain fixture requires survival material mode, received ${JSON.stringify(setupSnapshot.materialMode)}`);
    }
    const setup = parseSetupStatus(String(setupSnapshot.status ?? ""));
    const initial = validateInitialInspection(parseInspection(
      String((await fixture(options.base, companion.id, "inspect")).status ?? ""),
    ));
    task = await request(options.base, `/api/companions/${encodeURIComponent(companion.id)}/tasks`, {
      method: "POST",
      body: { spec: craftTaskSpec(), owner: "live-craft-chain-smoke" },
    });
    const finished = await waitForTerminalWithCheckpoint(options.base, companion.id, task, options.waitMs);
    const final = validateFinalInspection(parseInspection(
      String((await fixture(options.base, companion.id, "inspect")).status ?? ""),
    ));
    return {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      companionId: companion.id,
      setup,
      initial,
      checkpoint: finished.checkpoint,
      task: {
        id: finished.task.id,
        status: finished.task.status,
        message: finished.task.message,
      },
      final,
    };
  } finally {
    await cancelAndWait(options.base, task, Math.min(options.waitMs, 30_000));
    await cleanupAndConfirm(options.base, companion.id);
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveCraftChainSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live craft chain smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
