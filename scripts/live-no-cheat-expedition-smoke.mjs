import { pathToFileURL } from "node:url";

const FIXTURE_SUITE = "no-cheat-expedition";
const FIXTURE_ITEM_ID = "minecraft:oak_log";
const EXPECTED_COUNT = 4;
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live no-cheat expedition smoke only connects to a loopback HTTP service");
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

async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== FIXTURE_SUITE
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(options, companionId, mode) {
  const before = await snapshot(options.base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const pathname = `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`;
  await request(options.base, pathname, {
    method: "POST",
    body: { suite: FIXTURE_SUITE, mode },
  });

  const deadline = Date.now() + (options.fixtureWaitMs ?? 15_000);
  do {
    const current = await snapshot(options.base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ")) {
        throw new Error(`Minecraft rejected ${FIXTURE_SUITE}:${mode}: ${status}`);
      }
      if (status.startsWith("live-fixture:failed ")) {
        throw new Error(`Minecraft fixture ${FIXTURE_SUITE}:${mode} failed: ${status}`);
      }
      return { snapshot: current, status };
    }
    await new Promise((resolve) => setTimeout(resolve, options.fixturePollMs ?? 20));
  } while (Date.now() < deadline);
  throw new Error(`Minecraft did not acknowledge fixture ${FIXTURE_SUITE}:${mode}`);
}

export function parseSetupStatus(status) {
  const match = /^no-cheat-expedition:setup\|c=([01]),o=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected no-cheat expedition setup status: ${JSON.stringify(status)}`);
  return {
    cheatsEnabled: Number(match[1]),
    origin: { x: Number(match[2]), y: Number(match[3]), z: Number(match[4]) },
    itemId: FIXTURE_ITEM_ID,
  };
}

export function validateSetup(setup, current) {
  if (setup.cheatsEnabled !== 0
    || current?.gameMode !== "survival"
    || current?.materialMode !== "survival") {
    throw new Error(`No-cheat expedition requires Forge survival without cheats: ${JSON.stringify({
      cheatsEnabled: setup.cheatsEnabled,
      gameMode: current?.gameMode,
      materialMode: current?.materialMode,
    })}`);
  }
  return setup;
}

export function parseInspectionStatus(status) {
  const match = /^no-cheat-expedition:i\|(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected no-cheat expedition inspection status: ${JSON.stringify(status)}`);
  const values = match.slice(1).map(Number);
  return {
    complete: values[0],
    cheatsObserved: values[1],
    creativeObserved: values[2],
    sawGather: values[3],
    sawDeliver: values[4],
    sawExcursion: values[5],
    maxDistanceMilli: values[6],
    maxStepMilli: values[7],
    logBreaks: values[8],
    deliverySpawns: values[9],
    deliveryItems: values[10],
    playerItems: values[11],
    npcItems: values[12],
    worldItems: values[13],
    returnDistanceMilli: values[14],
    maxOwnerDriftMilli: values[15],
    taskIdStable: values[16],
    observationErrors: values[17],
    breakSyncErrors: values[18],
    remainingFixtureLogs: values[19],
    queuedTargets: values[20],
    skippedTargets: values[21],
    excursions: values[22],
    treeCluster: values[23],
    clusterReached: values[24],
    targetSelected: values[25],
  };
}

export function validateInspection(inspection, current) {
  const valid = inspection.complete === 1
    && inspection.cheatsObserved === 0
    && inspection.creativeObserved === 0
    && inspection.sawGather === 1
    && inspection.sawDeliver === 1
    && inspection.sawExcursion === 1
    && inspection.maxDistanceMilli >= 55_000
    && inspection.maxStepMilli <= 4_000
    && inspection.logBreaks === EXPECTED_COUNT
    && inspection.deliverySpawns >= 1
    && inspection.deliveryItems === EXPECTED_COUNT
    && inspection.playerItems === EXPECTED_COUNT
    && inspection.npcItems === 0
    && inspection.worldItems === 0
    && inspection.returnDistanceMilli <= 3_200
    && inspection.maxOwnerDriftMilli <= 1_500
    && inspection.taskIdStable === 1
    && inspection.observationErrors === 0
    && inspection.breakSyncErrors === 0
    && inspection.remainingFixtureLogs === 0
    && inspection.queuedTargets === 0
    && inspection.skippedTargets === 0
    && inspection.excursions === 0
    && inspection.treeCluster === 0
    && inspection.clusterReached === 0
    && inspection.targetSelected === 0
    && current?.gameMode === "survival"
    && current?.materialMode === "survival";
  if (!valid) {
    throw new Error(`No-cheat expedition evidence was insufficient: ${JSON.stringify({
      inspection,
      gameMode: current?.gameMode,
      materialMode: current?.materialMode,
    })}`);
  }
  return inspection;
}

export function parseCleanupStatus(status) {
  if (status === "no-cheat-expedition:cleanup|none") return { none: true };
  const match = /^no-cheat-expedition:cleanup\|r=([01]),([01]),([01]),([01]),([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected no-cheat expedition cleanup status: ${JSON.stringify(status)}`);
  return {
    none: false,
    player: Number(match[1]),
    npc: Number(match[2]),
    respawn: Number(match[3]),
    blocks: Number(match[4]),
    items: Number(match[5]),
  };
}

export function validateCleanup(cleanup, allowNone = false) {
  if (cleanup.none) {
    if (!allowNone) throw new Error("No-cheat expedition cleanup found no fixture after setup");
    return cleanup;
  }
  if ([cleanup.player, cleanup.npc, cleanup.respawn, cleanup.blocks, cleanup.items].some((value) => value !== 1)) {
    throw new Error(`No-cheat expedition cleanup was incomplete: ${JSON.stringify(cleanup)}`);
  }
  return cleanup;
}

export function failureWithInspection(error, inspection) {
  const message = error instanceof Error ? error.message : String(error);
  return new Error(`${message}; last inspection: ${JSON.stringify(inspection)}`, { cause: error });
}

export function expeditionTaskSpec(itemId, player) {
  if (itemId !== FIXTURE_ITEM_ID) throw new Error(`Unsupported fixture resource ${JSON.stringify(itemId)}`);
  if (typeof player !== "string" || !player.trim()) throw new Error("No current player was exposed by the Forge companion");
  return {
    kind: "macro",
    skillId: "life.expedition-and-deliver",
    arguments: { itemId, count: EXPECTED_COUNT, player: player.trim() },
    requestedBy: "live-no-cheat-expedition-smoke",
    note: "Local reversible unprivileged walking expedition with physical delivery",
  };
}

function currentPlayer(companion, current) {
  if (typeof companion.ownerName === "string" && companion.ownerName.trim()) return companion.ownerName.trim();
  const owner = current?.nearbyEntities?.find((entity) => entity.disposition === "owner");
  return typeof owner?.name === "string" ? owner.name.trim() : "";
}

async function connectedForgeCompanion(base) {
  const response = await request(base, "/api/companions");
  const companion = response.companions?.find((candidate) => (
    candidate.connected === true && candidate.embodiment === "in-world-npc"
  ));
  if (!companion?.id) throw new Error("No connected in-world NPC was found");
  if (companion.backend !== "forge-1.20.1") {
    throw new Error(`No-cheat expedition requires a Forge 1.20.1 companion, received ${JSON.stringify(companion.backend)}`);
  }
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  return companion;
}

function requireSameTaskId(record, taskId, phase) {
  if (!record || record.id !== taskId) {
    throw new Error(`No-cheat expedition task ID drift during ${phase}: expected ${taskId}, received ${record?.id ?? "missing"}`);
  }
  return record;
}

export async function waitForSameTask(options, taskId, requireSuccess = true) {
  const deadline = Date.now() + options.waitMs;
  do {
    const current = requireSameTaskId(
      await request(options.base, `/api/tasks/${encodeURIComponent(taskId)}`),
      taskId,
      "poll",
    );
    if (TERMINAL.has(current.status)) {
      if (requireSuccess && current.status !== "succeeded") {
        throw new Error(`No-cheat expedition task ${taskId} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
      }
      return current;
    }
    await new Promise((resolve) => setTimeout(resolve, options.pollMs ?? 500));
  } while (Date.now() < deadline);
  throw new Error(`No-cheat expedition task ${taskId} timed out`);
}

async function cancelAndWait(options, taskId) {
  if (!taskId) return null;
  let current = requireSameTaskId(
    await request(options.base, `/api/tasks/${encodeURIComponent(taskId)}`),
    taskId,
    "cleanup",
  );
  if (!TERMINAL.has(current.status)) {
    current = requireSameTaskId(await request(options.base, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: "POST",
      body: { reason: "live no-cheat expedition fixture cleanup" },
    }), taskId, "cancel");
  }
  return TERMINAL.has(current.status)
    ? current
    : waitForSameTask({ ...options, waitMs: Math.min(options.waitMs, 30_000) }, taskId, false);
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

export async function runLiveNoCheatExpeditionSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      spec: expeditionTaskSpec(FIXTURE_ITEM_ID, "<current-player>"),
    };
  }

  const companion = await connectedForgeCompanion(options.base);
  let setupAcknowledged = false;
  let taskId = null;
  let result = null;
  let failure = null;
  let failureInspection = null;
  let cleanupEvidence = null;
  try {
    const setupResult = await fixture(options, companion.id, "setup");
    setupAcknowledged = true;
    const setup = validateSetup(parseSetupStatus(setupResult.status), setupResult.snapshot);
    const player = currentPlayer(companion, setupResult.snapshot);
    const spec = expeditionTaskSpec(setup.itemId, player);
    if (Object.hasOwn(spec, "movement")) throw new Error("No-cheat expedition macro must not override movement");

    const assigned = await request(options.base, `/api/companions/${encodeURIComponent(companion.id)}/tasks`, {
      method: "POST",
      body: { spec, owner: "live-no-cheat-expedition-smoke" },
    });
    if (typeof assigned?.id !== "string" || !assigned.id) {
      throw new Error("No-cheat expedition assignment did not return a task ID");
    }
    taskId = assigned.id;
    requireSameTaskId(assigned, taskId, "assignment");
    const finished = await waitForSameTask(options, taskId, true);

    const inspectionResult = await fixture(options, companion.id, "inspect");
    const inspection = validateInspection(
      parseInspectionStatus(inspectionResult.status),
      inspectionResult.snapshot,
    );
    result = {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      companionId: companion.id,
      player,
      taskId,
      task: { id: finished.id, status: finished.status, message: finished.message },
      inspection,
    };
  } catch (error) {
    failure = error;
    if (setupAcknowledged && taskId !== null) {
      try {
        const inspectionResult = await fixture(options, companion.id, "inspect");
        failureInspection = parseInspectionStatus(inspectionResult.status);
      } catch {
        // Preserve the original failure when diagnostics are unavailable.
      }
    }
  } finally {
    try {
      await cancelAndWait(options, taskId);
    } catch (error) {
      if (failure === null) failure = error;
    }
    try {
      const cleanupResult = await fixture(options, companion.id, "cleanup");
      cleanupEvidence = validateCleanup(parseCleanupStatus(cleanupResult.status), !setupAcknowledged);
    } catch (error) {
      if (failure === null) failure = error;
    }
  }

  if (failure !== null) {
    throw failureInspection === null ? failure : failureWithInspection(failure, failureInspection);
  }
  return { ...result, cleanup: cleanupEvidence };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveNoCheatExpeditionSmoke(parseCli(process.argv.slice(2))).then(
    (report) => process.stdout.write(`${JSON.stringify(report, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live no-cheat expedition smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
