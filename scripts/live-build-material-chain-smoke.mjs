import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const FIXTURE_BLOCKS = [
  ["minecraft:cobblestone", 0, 0, 0],
  ["minecraft:oak_planks", 2, 0, 0],
  ["minecraft:glass", 4, 0, 0],
  ["minecraft:torch", 0, 1, 0],
  ["minecraft:glass_pane", 2, 1, 0],
];
const INSPECTION_KEYS = [
  "expected", "matching", "wrong",
  "logsRemaining", "stoneRemaining", "sandRemaining", "coalRemaining",
  "logBreaks", "stoneBreaks", "sandBreaks", "coalBreaks",
  "tablePlacements", "furnacePlacements", "litFurnace",
  "sawLogs", "sawPlanks", "sawCobble", "sawSand", "sawGlass", "sawCoal",
  "sawStick", "sawTorch", "sawPane", "sawWoodenPickaxe", "sawActiveBuild",
  "taskIdChanges", "maxDistanceMilli", "finalDistanceMilli",
  "unexpectedBreaks", "unknownWorldEdits", "remoteBreaks", "losViolations",
  "syncViolations", "maxTouchMilli", "sandInFurnace", "furnaceFuel",
  "sawTable", "sawFurnace",
];

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live build material chain smoke only connects to a loopback HTTP service");
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
  if (mode === "setup") return "bmc:setup=";
  if (mode === "inspect") return "bmc:i=";
  if (mode === "cleanup") return "bmc:cleanup ";
  throw new Error(`Unsupported build material fixture mode ${mode}`);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "build-material-chain"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

function fixtureRetryable(mode) {
  return mode === "inspect" || mode === "cleanup";
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const expected = fixtureExpectedPrefix(mode);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "build-material-chain", mode },
  });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 250;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 20));
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ")) {
        throw new Error(`Minecraft denied build material fixture ${mode}: ${status}`);
      }
      if (status.startsWith("live-fixture:failed ") && !fixtureRetryable(mode)) {
        throw new Error(`Minecraft build material fixture ${mode} failed: ${status}`);
      }
      if (status.startsWith(expected)) return { ...current, status };
      if (!fixtureRetryable(mode)) {
        throw new Error(`Unexpected build material fixture acknowledgement: ${status}`);
      }
    }
    if (fixtureRetryable(mode) && Date.now() >= nextRetry) {
      await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
        method: "POST",
        body: { suite: "build-material-chain", mode },
      });
      nextRetry = Date.now() + 250;
    }
  }
  throw new Error(`Minecraft did not acknowledge build material fixture ${mode}`);
}

export function parseSetupStatus(status) {
  const match = /^bmc:setup=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected build material setup status: ${JSON.stringify(status)}`);
  return { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) };
}

export function parseInspection(status) {
  const match = /^bmc:i=([0-9]+(?:,[0-9]+){37}),task=(none|invalid|[0-9a-f]{16})$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected build material inspection status: ${JSON.stringify(status)}`);
  const values = match[1].split(",").map(Number);
  return {
    ...Object.fromEntries(INSPECTION_KEYS.map((key, index) => [key, values[index]])),
    taskId: match[2],
  };
}

export function taskEvidenceToken(taskId) {
  const compact = String(taskId ?? "").replaceAll("-", "").toLowerCase();
  if (!/^[0-9a-f]{32}$/u.test(compact)) throw new Error(`Invalid task ID for evidence: ${JSON.stringify(taskId)}`);
  return compact.slice(0, 16);
}

function invariant(condition, message, value) {
  if (!condition) throw new Error(`${message}: ${JSON.stringify(value)}`);
}

export function validateInitial(value) {
  invariant(value.expected === 5 && value.matching === 0 && value.wrong === 5,
    "Build material target was not initially empty", value);
  invariant(value.logsRemaining === 15 && value.stoneRemaining === 16
    && value.sandRemaining === 16 && value.coalRemaining === 16,
  "Build material fixture resources are incomplete", value);
  invariant(value.logBreaks === 0 && value.stoneBreaks === 0
    && value.sandBreaks === 0 && value.coalBreaks === 0,
  "Build material fixture was modified before assignment", value);
  invariant(value.tablePlacements === 0 && value.furnacePlacements === 0
    && value.sawActiveBuild === 0 && value.taskId === "none",
  "Build material fixture already observed task work", value);
  for (const key of [
    "sawLogs", "sawPlanks", "sawCobble", "sawSand", "sawGlass", "sawCoal",
    "sawStick", "sawTorch", "sawPane", "sawWoodenPickaxe", "sawTable", "sawFurnace",
  ]) invariant(value[key] === 0, `Build material fixture retained initial ${key}`, value);
  invariant(value.unexpectedBreaks === 0 && value.unknownWorldEdits === 0
    && value.remoteBreaks === 0 && value.losViolations === 0 && value.syncViolations === 0,
  "Build material fixture has invalid initial evidence", value);
  return value;
}

export function validateFinal(value, taskId) {
  invariant(value.expected === 5 && value.matching === 5 && value.wrong === 0,
    "Build material task did not place all five target blocks", value);
  invariant(value.logBreaks === 15 - value.logsRemaining && value.logBreaks >= 2,
    "Build material task did not physically gather fixture logs", value);
  invariant(value.stoneBreaks === 16 - value.stoneRemaining && value.stoneBreaks >= 9,
    "Build material task did not gather enough stone for cobblestone and a furnace", value);
  invariant(value.sandBreaks === 16 - value.sandRemaining && value.sandBreaks >= 7,
    "Build material task did not gather enough sand for glass and panes", value);
  invariant(value.coalBreaks === 16 - value.coalRemaining && value.coalBreaks >= 1,
    "Build material task did not mine fixture coal", value);
  invariant(value.tablePlacements === 1 && value.furnacePlacements === 1
    && value.sawTable === 1 && value.sawFurnace === 1,
  "Build material task did not place and reuse exactly one set of workstations", value);
  invariant(value.litFurnace === 1 && value.sandInFurnace === 1 && value.furnaceFuel === 1,
    "Build material task did not physically smelt sand with fuel", value);
  for (const key of [
    "sawLogs", "sawPlanks", "sawCobble", "sawSand", "sawGlass", "sawCoal",
    "sawStick", "sawTorch", "sawPane", "sawWoodenPickaxe", "sawActiveBuild",
  ]) invariant(value[key] === 1, `Build material evidence is missing ${key}`, value);
  invariant(value.taskId === taskEvidenceToken(taskId) && value.taskIdChanges === 0,
    "Build material work did not remain on the assigned task", value);
  invariant(value.maxDistanceMilli >= 12_000 && value.finalDistanceMilli <= 8_000,
    "NPC did not leave for resources and return to the locked build origin", value);
  invariant(value.unexpectedBreaks === 0 && value.unknownWorldEdits === 0
    && value.remoteBreaks === 0 && value.losViolations === 0 && value.syncViolations === 0
    && value.maxTouchMilli <= 4_500,
  "Build material task violated physical interaction boundaries", value);
  return value;
}

export function validateCleanupStatus(status) {
  if (!["bmc:cleanup restored", "bmc:cleanup none"].includes(status)) {
    throw new Error(`Build material fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

export function buildPlanDraft(origin) {
  if (![origin?.x, origin?.y, origin?.z].every(Number.isInteger)) {
    throw new Error("Build material fixture origin must contain integer coordinates");
  }
  return {
    name: "Live survival build material chain",
    source: "demo",
    origin: { x: origin.x, y: origin.y, z: origin.z },
    blocks: FIXTURE_BLOCKS.map(([blockId, x, y, z]) => ({
      position: { x, y, z },
      blockId,
      properties: {},
    })),
  };
}

export function buildTaskSpec(planId) {
  return {
    kind: "build",
    planId,
    placement: "plan-origin",
    materialPreference: { source: "nearby", preferredBlockId: "minecraft:oak_planks", allowMixed: false },
    requestedBy: "live-build-material-chain-smoke",
    note: "Local reversible survival material acquisition and locked-origin build acceptance",
  };
}

async function createConfirmedPlan(base, origin) {
  const draft = buildPlanDraft(origin);
  const preview = await request(base, "/api/build-plans/preview", { method: "POST", body: draft });
  if (!preview?.id || preview.confirmed !== false
    || JSON.stringify(preview.origin) !== JSON.stringify(draft.origin)
    || JSON.stringify(preview.blocks) !== JSON.stringify(draft.blocks)) {
    throw new Error(`Control plane returned an invalid build material preview: ${JSON.stringify(preview)}`);
  }
  const confirmed = await request(base, `/api/build-plans/${encodeURIComponent(preview.id)}/confirm`, {
    method: "POST",
  });
  if (confirmed?.id !== preview.id || confirmed.confirmed !== true) {
    throw new Error(`Build material preview ${preview.id} was not confirmed intact`);
  }
  return confirmed;
}

async function assignBuild(base, companionId, planId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec: buildTaskSpec(planId), owner: "live-build-material-chain-smoke" },
  });
}

async function waitForTerminal(base, task, waitMs, requireSuccess = true, onSample = null) {
  const deadline = Date.now() + waitMs;
  let current = task;
  if (onSample) await onSample(current);
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
    if (onSample) await onSample(current);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Build material task ${task.id} timed out`);
  if (requireSuccess && current.status !== "succeeded") {
    throw new Error(`Build material task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return current;
}

export function taskNeedsFixtureCancellation(status) {
  return status === "failed" || !TERMINAL.has(status);
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (taskNeedsFixtureCancellation(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live build material fixture cleanup" },
    });
  }
  return waitForTerminal(base, current, waitMs, false);
}

async function cleanupAndConfirm(base, companionId) {
  return validateCleanupStatus(String((await fixture(base, companionId, "cleanup")).status ?? ""));
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 900;
  if (!Number.isFinite(seconds) || seconds < 60 || seconds > 900) {
    throw new Error("--wait-seconds must be between 60 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveBuildMaterialChainSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      startsMinecraft: false,
      externalApi: false,
      fixture: { suite: "build-material-chain", modes: ["setup", "inspect", "cleanup"] },
      plan: buildPlanDraft({ x: 0, y: 0, z: 0 }),
    };
  }

  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  let task = null;
  try {
    await cleanupAndConfirm(options.base, companion.id);
    const setupSnapshot = await fixture(options.base, companion.id, "setup");
    if (setupSnapshot.materialMode !== "survival") {
      throw new Error(`Build material fixture requires survival mode, received ${setupSnapshot.materialMode}`);
    }
    const origin = parseSetupStatus(setupSnapshot.status);
    const initial = validateInitial(parseInspection((await fixture(options.base, companion.id, "inspect")).status));
    const plan = await createConfirmedPlan(options.base, origin);
    task = await assignBuild(options.base, companion.id, plan.id);
    const trace = [];
    let previousTrace = "";
    const sampleTask = async (current) => {
      const currentSnapshot = await snapshot(options.base, companion.id);
      const relevantInventory = {};
      for (const stack of currentSnapshot?.inventory ?? []) {
        const itemId = String(stack?.id ?? "");
        if (![
          "minecraft:oak_log", "minecraft:oak_planks", "minecraft:stick",
          "minecraft:wooden_pickaxe", "minecraft:cobblestone", "minecraft:sand",
          "minecraft:coal", "minecraft:crafting_table", "minecraft:furnace",
          "minecraft:glass", "minecraft:torch", "minecraft:glass_pane",
        ].includes(itemId)) continue;
        relevantInventory[itemId] = (relevantInventory[itemId] ?? 0) + Number(stack?.count ?? 0);
      }
      const sample = {
        taskStatus: String(current?.status ?? ""),
        taskMessage: String(current?.message ?? "").slice(0, 256),
        npcStatus: String(currentSnapshot?.status ?? "").slice(0, 256),
        activeKind: String(currentSnapshot?.activeTaskKind ?? ""),
        activeProgress: Number(currentSnapshot?.activeTaskProgress ?? 0),
        inventory: relevantInventory,
      };
      const encoded = JSON.stringify(sample);
      if (encoded !== previousTrace) {
        previousTrace = encoded;
        trace.push(sample);
        if (trace.length > 64) trace.shift();
      }
    };
    let finished;
    try {
      finished = await waitForTerminal(options.base, task, options.waitMs, true, sampleTask);
    } catch (error) {
      let evidence = null;
      let evidenceError = null;
      try {
        evidence = parseInspection((await fixture(options.base, companion.id, "inspect")).status);
      } catch (inspectionError) {
        evidenceError = inspectionError instanceof Error ? inspectionError.message : String(inspectionError);
      }
      const message = error instanceof Error ? error.message : String(error);
      const diagnostic = evidence
        ? `evidence=${JSON.stringify(evidence)}`
        : `evidence-unavailable=${JSON.stringify(evidenceError)}`;
      throw new Error(`${message}; ${diagnostic}; trace=${JSON.stringify(trace)}`, { cause: error });
    }
    task = null;
    const final = validateFinal(
      parseInspection((await fixture(options.base, companion.id, "inspect")).status),
      finished.id,
    );
    return {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      companionId: companion.id,
      origin,
      planId: plan.id,
      task: { id: finished.id, status: finished.status, progress: finished.progress },
      initial,
      final,
    };
  } finally {
    await cancelAndWait(options.base, task, Math.min(options.waitMs, 30_000));
    await cleanupAndConfirm(options.base, companion.id);
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveBuildMaterialChainSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live build material chain smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
