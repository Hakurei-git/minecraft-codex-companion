import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hosts = new Set(["127.0.0.1", "localhost", "::1", "[::1]"]);
  if (url.protocol !== "http:" || !hosts.has(url.hostname.toLowerCase())) {
    throw new Error("live deep-mining smoke only connects to a loopback HTTP service");
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

export async function connectedCompanion(base) {
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

export async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

export function fixtureExpectedPrefix(mode) {
  if (mode === "setup") return "deep-mining:setup|o=";
  if (mode === "inspect") return "deep-mining:i|";
  if (mode === "cleanup") return "deep-mining:cleanup|";
  throw new Error(`Unsupported deep-mining fixture mode ${mode}`);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  const sequence = Number(acknowledgement?.sequence);
  if (!acknowledgement
    || !Number.isSafeInteger(sequence)
    || sequence <= Number(beforeSequence)
    || acknowledgement.suite !== "deep-mining"
    || acknowledgement.mode !== mode) return null;
  return {
    sequence,
    suite: acknowledgement.suite,
    mode: acknowledgement.mode,
    status: String(acknowledgement.status ?? ""),
  };
}

function validateFixtureAcknowledgement(acknowledgement, mode) {
  if (!acknowledgement
    || !Number.isSafeInteger(acknowledgement.sequence)
    || acknowledgement.sequence <= 0
    || acknowledgement.suite !== "deep-mining"
    || acknowledgement.mode !== mode) {
    throw new Error(`Invalid deep-mining fixture ${mode} acknowledgement envelope`);
  }
  if (acknowledgement.status.startsWith("live-fixture:denied ")
    || acknowledgement.status.startsWith("live-fixture:failed ")) {
    throw new Error(`Minecraft rejected deep-mining fixture ${mode}: ${acknowledgement.status}`);
  }
  if (!acknowledgement.status.startsWith(fixtureExpectedPrefix(mode))) {
    throw new Error(`Unexpected deep-mining fixture ${mode} acknowledgement: ${acknowledgement.status}`);
  }
  return acknowledgement;
}

export function validateCleanupAcknowledgement(acknowledgement) {
  const validated = validateFixtureAcknowledgement(acknowledgement, "cleanup");
  if (validated.status !== "deep-mining:cleanup|r=1,1,1,1") {
    throw new Error(`Deep-mining fixture did not restore all saved state: ${validated.status}`);
  }
  return { ...validated, restored: true };
}

export async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "deep-mining", mode },
  });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    await pause(20);
    const current = await snapshot(base, companionId);
    const acknowledgement = fixtureAcknowledgement(current, beforeSequence, mode);
    if (acknowledgement) {
      return {
        snapshot: current,
        acknowledgement: validateFixtureAcknowledgement(acknowledgement, mode),
      };
    }
  }
  throw new Error(`Minecraft did not acknowledge deep-mining fixture ${mode}`);
}

export function requireWorldId(value) {
  const worldId = typeof value === "string" ? value : "";
  if (!worldId.trim() || worldId.length > 128 || /[\u0000-\u001f\u007f]/u.test(worldId)) {
    throw new Error("The deep-mining restart smoke requires a non-sensitive Minecraft world ID");
  }
  return worldId;
}

export function worldEntryArguments(worldId) {
  return [
    "-WorldId", requireWorldId(worldId),
    "-SkipLogInspection",
    "-NoLogMenuGraceSeconds", "75",
    "-WaitSeconds", "300",
  ];
}

export function gracefulCloseArguments(base, companionId) {
  const control = loopbackBase(base);
  if (typeof companionId !== "string" || !companionId.trim() || companionId.length > 128) {
    throw new Error("Minecraft graceful-close helper requires the connected companion ID");
  }
  return [
    "-WaitSeconds", "90",
    "-ControlBaseUri", control.origin,
    "-CompanionId", companionId,
    "-AsJson",
  ];
}

export function parseGracefulCloseEvidence(stdout) {
  let value;
  try {
    value = JSON.parse(String(stdout).trim());
  } catch {
    throw new Error("Minecraft graceful-close helper did not return JSON evidence");
  }
  if (value?.SavedAndClosed !== true || value?.LeftWorldBeforeWindowClose !== true
    || value?.PauseMenuConfirmed !== true || value?.BackgroundPauseLeaseArmed !== true
    || value?.CursorCaptureReleased !== true || value?.ForcedTerminationUsed !== false
    || value?.MouseOrKeyboardInputUsed !== false || value?.ClipboardUsed !== false
    || value?.ScreenshotUsed !== false) {
    throw new Error("Minecraft did not complete the audited Save and Quit path");
  }
  return {
    savedAndClosed: true,
    leftWorldBeforeWindowClose: true,
    pauseMenuConfirmed: true,
    backgroundPauseLeaseArmed: true,
    cursorCaptureReleased: true,
    forcedTerminationUsed: false,
    mouseOrKeyboardInputUsed: false,
    clipboardUsed: false,
    screenshotUsed: false,
  };
}

async function runPowerShell(script, args, timeout) {
  const result = await execFileAsync("powershell.exe", [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", path.join(projectRoot, "scripts", script),
    ...args,
  ], {
    cwd: projectRoot,
    windowsHide: true,
    timeout,
    maxBuffer: 500_000,
  });
  return result.stdout;
}

export function validateStartingSnapshot(value) {
  if (!value || typeof value !== "object") throw new Error("Minecraft NPC snapshot is missing");
  if (value.npcDowned !== false) throw new Error("Deep-mining smoke requires an active NPC");
  if (typeof value.activeTaskId !== "string" || value.activeTaskId.trim()) {
    throw new Error("Deep-mining smoke requires no active task");
  }
  if (!Number.isInteger(value.pausedTaskCount) || value.pausedTaskCount !== 0) {
    throw new Error("Deep-mining smoke requires no paused tasks");
  }
  if (!Array.isArray(value.taskQueue) || value.taskQueue.length !== 0) {
    throw new Error("Deep-mining smoke requires an empty task queue");
  }
  if (value.taskSchedulerLifecycle !== "idle") {
    throw new Error("Deep-mining smoke requires an idle task scheduler");
  }
  if (value.materialMode !== "survival") {
    throw new Error(`Deep-mining smoke requires survival material mode, received ${value.materialMode}`);
  }
  if (typeof value.dimension !== "string" || !value.dimension) {
    throw new Error("Deep-mining smoke requires the NPC dimension");
  }
  return {
    worldId: requireWorldId(value.worldId),
    dimension: value.dimension,
    materialMode: value.materialMode,
  };
}

export function deepMiningTaskSpec(ownerName) {
  if (typeof ownerName !== "string" || !ownerName.trim() || ownerName.length > 64) {
    throw new Error("Deep-mining task requires the bound owner name");
  }
  return {
    kind: "craft",
    itemId: "minecraft:diamond_pickaxe",
    count: 1,
    deliverTo: ownerName,
    requestedBy: ownerName,
    note: "Reversible deep-mining restart acceptance",
  };
}

export function validateTask(task, companionId, ownerName) {
  const spec = task?.spec;
  if (!task?.id || task.companionId !== companionId || spec?.kind !== "craft"
    || spec.itemId !== "minecraft:diamond_pickaxe" || spec.count !== 1
    || spec.deliverTo !== ownerName || spec.requestedBy !== ownerName) {
    throw new Error(`Control service created the wrong deep-mining task: ${JSON.stringify(task)}`);
  }
  return task;
}

export function validateCheckpoint(current, task) {
  const mining = current?.miningState;
  if (task?.status !== "running" || mining?.phase !== "branching"
    || mining.itemId !== "minecraft:diamond" || mining.targetY !== -58
    || mining.staircaseStep < 4 || mining.branchProgress < 4
    || mining.brokenBlocks < 20) {
    throw new Error(`Invalid deep-mining restart checkpoint: ${JSON.stringify({ taskStatus: task?.status, mining })}`);
  }
  return {
    taskId: task.id,
    taskStatus: task.status,
    snapshotSequence: current.sequence,
    position: current.position,
    miningState: mining,
  };
}

async function waitForCheckpoint(base, companionId, task, waitMs) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    await pause(50);
    const [current, record] = await Promise.all([
      snapshot(base, companionId),
      request(base, `/api/tasks/${encodeURIComponent(task.id)}`),
    ]);
    if (TERMINAL.has(record.status)) {
      throw new Error(`Deep-mining task became ${record.status} before the restart checkpoint`);
    }
    const mining = current.miningState;
    if (mining?.phase === "branching" && mining.staircaseStep >= 4
      && mining.branchProgress >= 4 && mining.brokenBlocks >= 20) {
      return validateCheckpoint(current, record);
    }
  }
  throw new Error("Deep-mining task did not reach the restart checkpoint");
}

async function waitForDisconnect(base, companionId) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/companions");
    const companion = response.companions?.find((candidate) => candidate.id === companionId);
    if (!companion?.connected) return true;
    await pause(200);
  }
  throw new Error("Minecraft bridge remained connected after a normal game exit");
}

async function waitForReconnect(base, companionId, worldId, dimension) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/companions");
    const companion = response.companions?.find((candidate) => candidate.id === companionId && candidate.connected);
    if (companion) {
      const current = await snapshot(base, companionId);
      if (current.worldId !== worldId || current.dimension !== dimension) {
        throw new Error("Minecraft reconnected to a different world or dimension");
      }
      return current;
    }
    await pause(500);
  }
  throw new Error("Minecraft bridge did not reconnect after restart");
}

export async function waitForTask(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (Date.now() < deadline && !TERMINAL.has(current.status)) {
    await pause(250);
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Deep-mining task ${task.id} timed out`);
  if (current.status !== "succeeded") {
    throw new Error(`Deep-mining task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return current;
}

export function parseInspection(status) {
  const pattern = /^deep-mining:i\|ok=([01]),l=(\d+),t=(\d+),p=(\d+),d=([01]),b=([01]),s=(\d+),r=(\d+),x=(\d+),k=(\d+),o=(\d+),g=(\d+),v=([01]),q=([01]),w=(\d+),j=(\d+),n=([01]),e=(\d+)$/u;
  const match = pattern.exec(status ?? "");
  if (!match) throw new Error(`Unexpected deep-mining inspection: ${JSON.stringify(status)}`);
  const numbers = match.slice(1).map(Number);
  const keys = [
    "ok", "ladders", "torches", "usableIronPickaxes", "sawDescending", "sawBranching",
    "staircaseStep", "branchProgress", "placedTorches", "brokenBlocks", "diamonds",
    "playerDiamondPickaxes", "deliverySeen", "taskIdStable", "discardedStoneStacks", "discardedStoneItems",
    "stoneDropLedgerSeen", "observationErrors",
  ];
  return Object.fromEntries(keys.map((key, index) => [key, numbers[index]]));
}

export function validateInspection(value) {
  if (value.ok !== 1 || value.ladders < 32 || value.torches < 32
    || value.usableIronPickaxes < 2 || value.sawDescending !== 1 || value.sawBranching !== 1
    || value.staircaseStep < 4 || value.branchProgress < 8 || value.placedTorches < 1
    || value.brokenBlocks < 20 || value.diamonds < 3 || value.playerDiamondPickaxes < 1
    || value.deliverySeen !== 1 || value.taskIdStable !== 1
    || value.discardedStoneStacks < 2 || value.discardedStoneItems < 128
    || value.stoneDropLedgerSeen !== 1 || value.observationErrors !== 0) {
    throw new Error(`Incomplete deep-mining evidence: ${JSON.stringify(value)}`);
  }
  return value;
}

export function parseCli(argv) {
  const waitArgument = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArgument ? Number(waitArgument.slice("--wait-seconds=".length)) : 300;
  if (!Number.isFinite(seconds) || seconds < 120 || seconds > 900) {
    throw new Error("--wait-seconds must be between 120 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveDeepMiningSmoke(options) {
  const companion = await connectedCompanion(options.base);
  const baseline = validateStartingSnapshot(await snapshot(options.base, companion.id));
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      externalApi: false,
      normalSaveAndQuit: true,
      companionId: companion.id,
      worldId: baseline.worldId,
    };
  }

  let task = null;
  let setupCompleted = false;
  let gameStopped = false;
  let reconnected = false;
  let finalGameClosed = false;
  let result = null;
  let primaryError = null;
  let cleanup = null;
  const cleanupErrors = [];
  try {
    await fixture(options.base, companion.id, "setup");
    setupCompleted = true;
    task = validateTask(await request(
      options.base,
      `/api/companions/${encodeURIComponent(companion.id)}/tasks`,
      {
        method: "POST",
        body: {
          spec: deepMiningTaskSpec(companion.ownerName),
          owner: "live-deep-mining-smoke",
        },
      },
    ), companion.id, companion.ownerName);

    const checkpoint = await waitForCheckpoint(options.base, companion.id, task, options.waitMs);
    const stopOutput = await runPowerShell(
      "stop-minecraft-for-update.ps1",
      gracefulCloseArguments(options.base, companion.id),
      120_000,
    );
    const saveAndQuit = parseGracefulCloseEvidence(stopOutput);
    gameStopped = true;
    await waitForDisconnect(options.base, companion.id);

    const duringRestart = await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`);
    if (TERMINAL.has(duringRestart.status)) {
      throw new Error(`Deep-mining task became ${duringRestart.status} before Minecraft reconnected`);
    }

    await runPowerShell("launch-hmcl-background.ps1", ["-WaitSeconds", "180"], 240_000);
    await runPowerShell("enter-hmcl-test-world.ps1", worldEntryArguments(baseline.worldId), 330_000);
    await waitForReconnect(
      options.base,
      companion.id,
      baseline.worldId,
      baseline.dimension,
    );
    reconnected = true;

    const completed = validateTask(
      await waitForTask(options.base, task, options.waitMs),
      companion.id,
      companion.ownerName,
    );
    const inspectionAck = (await fixture(options.base, companion.id, "inspect")).acknowledgement;
    const inspection = validateInspection(parseInspection(inspectionAck.status));
    result = {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      externalApi: false,
      normalSaveAndQuit: true,
      companionId: companion.id,
      worldId: baseline.worldId,
      task: {
        id: completed.id,
        status: completed.status,
        message: completed.message,
        spec: completed.spec,
      },
      checkpoint,
      statusDuringRestart: duringRestart.status,
      resumedSameTaskId: completed.id === checkpoint.taskId,
      saveAndQuit,
      inspection,
    };
  } catch (error) {
    primaryError = error;
  } finally {
    if (gameStopped && !reconnected) {
      try {
        await runPowerShell("launch-hmcl-background.ps1", ["-WaitSeconds", "180"], 240_000);
        await runPowerShell("enter-hmcl-test-world.ps1", worldEntryArguments(baseline.worldId), 330_000);
        await waitForReconnect(
          options.base,
          companion.id,
          baseline.worldId,
          baseline.dimension,
        );
        reconnected = true;
      } catch (error) {
        cleanupErrors.push(new Error(`Minecraft recovery failed; deep-mining marker was preserved: ${error instanceof Error ? error.message : String(error)}`));
      }
    }

    if ((reconnected || !gameStopped) && setupCompleted) {
      if (task?.id) {
        try {
          const current = await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`);
          if (!TERMINAL.has(current.status)) {
            await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
              method: "POST",
              body: { reason: "live deep-mining fixture cleanup" },
            });
          }
        } catch (error) {
          cleanupErrors.push(error);
        }
      }
      try {
        cleanup = validateCleanupAcknowledgement(
          (await fixture(options.base, companion.id, "cleanup")).acknowledgement,
        );
      } catch (error) {
        cleanupErrors.push(error);
      }
    }

    if (reconnected || !gameStopped) {
      try {
        const finalStop = await runPowerShell(
          "stop-minecraft-for-update.ps1",
          gracefulCloseArguments(options.base, companion.id),
          120_000,
        );
        parseGracefulCloseEvidence(finalStop);
        await waitForDisconnect(options.base, companion.id);
        finalGameClosed = true;
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
  }

  if (primaryError) {
    const suffix = cleanupErrors.length
      ? `; cleanup: ${cleanupErrors.map((error) => error instanceof Error ? error.message : String(error)).join("; ")}`
      : "";
    throw new Error(`${primaryError instanceof Error ? primaryError.message : String(primaryError)}${suffix}`);
  }
  if (cleanupErrors.length) throw new AggregateError(cleanupErrors, "Deep-mining cleanup failed");
  if (!result) throw new Error("Deep-mining smoke produced no result");
  return { ...result, cleanup, finalGameClosed };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveDeepMiningSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live deep-mining smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
