import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export function loopbackBase(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("live storage restart smoke only connects to a loopback HTTP service");
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
  if (!response.ok) throw new Error(`${method} ${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 1_000)}`);
  return response.status === 204 ? null : response.json();
}

async function connectedCompanion(base) {
  const response = await request(base, "/api/companions");
  const companions = response.companions?.filter((candidate) => (
    candidate.connected === true && candidate.embodiment === "in-world-npc"
  )) ?? [];
  if (companions.length === 0) throw new Error("No connected Forge in-world NPC was found");
  if (companions.length !== 1) throw new Error(`Expected one connected Forge NPC, found ${companions.length}`);
  return companions[0];
}

async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

export function fixtureExpectedPrefix(mode) {
  if (mode === "inspect-restart") return "storage-fixture:restart ";
  if (mode === "setup-restart") return "storage-fixture:setup scenario=restart";
  if (mode === "cleanup") return "storage-fixture:cleanup ";
  throw new Error(`Unsupported storage fixture mode ${mode}`);
}

export function requireWorldId(value) {
  const worldId = typeof value === "string" ? value : "";
  if (!worldId.trim() || worldId.length > 128 || /[\u0000-\u001f\u007f]/u.test(worldId)) {
    throw new Error("The live restart smoke requires a non-sensitive Minecraft world ID");
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
    || value?.CursorCaptureReleased !== true
    || value?.ForcedTerminationUsed !== false || value?.MouseOrKeyboardInputUsed !== false
    || value?.ClipboardUsed !== false || value?.ScreenshotUsed !== false) {
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

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  const sequence = Number(acknowledgement?.sequence);
  if (!acknowledgement
    || !Number.isSafeInteger(sequence)
    || sequence <= Number(beforeSequence)
    || acknowledgement.suite !== "storage"
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
    || acknowledgement.suite !== "storage"
    || acknowledgement.mode !== mode) {
    throw new Error(`Invalid storage fixture ${mode} acknowledgement envelope`);
  }
  const status = acknowledgement.status;
  if (status.startsWith("live-fixture:denied ") || status.startsWith("live-fixture:failed ")) {
    throw new Error(`Minecraft rejected storage fixture ${mode}: ${status}`);
  }
  if (!status.startsWith(fixtureExpectedPrefix(mode))) {
    throw new Error(`Minecraft returned an unexpected storage fixture ${mode} acknowledgement: ${status}`);
  }
  if (mode === "cleanup"
    && status !== "storage-fixture:cleanup none"
    && status !== "storage-fixture:cleanup restored") {
    throw new Error(`Minecraft returned an invalid storage cleanup acknowledgement: ${status}`);
  }
  return acknowledgement;
}

export function validateCleanupAcknowledgement(acknowledgement, requireRestored = true) {
  const validated = validateFixtureAcknowledgement(acknowledgement, "cleanup");
  if (requireRestored && validated.status !== "storage-fixture:cleanup restored") {
    throw new Error(`Storage fixture cleanup did not restore its snapshot: ${validated.status}`);
  }
  return { ...validated, restored: validated.status === "storage-fixture:cleanup restored" };
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeAck = Number(before.liveFixtureAck?.sequence ?? 0);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "storage", mode },
  });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 20));
    const current = await snapshot(base, companionId);
    const acknowledgement = fixtureAcknowledgement(current, beforeAck, mode);
    if (acknowledgement) {
      return {
        ...current,
        status: validateFixtureAcknowledgement(acknowledgement, mode).status,
        fixtureAck: acknowledgement,
      };
    }
  }
  throw new Error(`Minecraft did not acknowledge storage fixture ${mode}`);
}

async function sendMinecraftTChat(message) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  await runPowerShell(
    "send-minecraft-chat-background.ps1",
    ["-MessageUtf8Base64", encoded, "-RespawnIfDead"],
    30_000,
  );
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

async function waitForNewTask(base, companionId, priorIds) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/tasks");
    const task = response.tasks?.find((candidate) => (
      candidate.companionId === companionId
      && !priorIds.has(candidate.id)
      && candidate.spec?.kind === "macro"
      && candidate.spec?.skillId === "life.retrieve-and-deliver"
      && candidate.spec?.arguments?.count === 96
    ));
    if (task) return task;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("Minecraft T chat did not create the restart retrieve task");
}

async function waitForForgeAcceptance(base, companionId, taskId) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const entry = current.taskQueue?.find((candidate) => (
      candidate.id === taskId && candidate.kind === "retrieve" && candidate.phase === "active"
    ));
    if (entry) return { sequence: current.sequence, progress: entry.progress };
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("Forge did not expose the retrieve task before restart");
}

async function waitForDisconnect(base, companionId) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/companions");
    const companion = response.companions?.find((candidate) => candidate.id === companionId);
    if (!companion?.connected) return true;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error("Minecraft bridge remained connected after a normal game exit");
}

async function waitForReconnect(base, companionId, expectedWorldId, expectedDimension) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/companions");
    const companion = response.companions?.find((candidate) => candidate.id === companionId && candidate.connected);
    if (companion) {
      const current = await snapshot(base, companionId);
      if (current.worldId !== expectedWorldId) {
        throw new Error(`Minecraft reconnected to world ${JSON.stringify(current.worldId)}, expected ${JSON.stringify(expectedWorldId)}`);
      }
      if (current.dimension !== expectedDimension) {
        throw new Error(`Minecraft reconnected in dimension ${JSON.stringify(current.dimension)}, expected ${JSON.stringify(expectedDimension)}`);
      }
      return current;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Minecraft bridge did not reconnect after restart");
}

async function waitForTask(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (Date.now() < deadline && !TERMINAL.has(current.status)) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Restart task ${task.id} timed out`);
  if (current.status !== "succeeded") {
    throw new Error(`Restart task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return { id: current.id, status: current.status, message: current.message, spec: current.spec };
}

export function parseInspection(status) {
  const match = /^storage-fixture:restart home=(\d+),npc=(\d+),player=(\d+),world=(\d+),near=(\d+),containers=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected restart storage inspection: ${JSON.stringify(status)}`);
  return {
    home: Number(match[1]), npc: Number(match[2]), player: Number(match[3]),
    world: Number(match[4]), near: Number(match[5]), containers: Number(match[6]),
  };
}

export function validateInspection(inspection, phase) {
  const valid = phase === "initial"
    ? inspection.home === 96 && inspection.npc === 0 && inspection.player === 0
      && inspection.world === 0 && inspection.containers === 96
    : inspection.home === 0 && inspection.npc === 0
      && inspection.player + inspection.world === 96 && inspection.near === inspection.world;
  if (!valid) throw new Error(`Invalid ${phase} restart inspection: ${JSON.stringify(inspection)}`);
  return inspection;
}

export function validateStartingSnapshot(value) {
  if (!value || typeof value !== "object") throw new Error("Minecraft NPC snapshot is missing");
  if (value.npcDowned !== false) throw new Error("Storage restart smoke requires an active NPC");
  if (typeof value.activeTaskId !== "string" || value.activeTaskId.trim()) {
    throw new Error("Storage restart smoke requires no active task");
  }
  if (!Number.isInteger(value.pausedTaskCount) || value.pausedTaskCount !== 0) {
    throw new Error("Storage restart smoke requires no paused tasks");
  }
  if (!Array.isArray(value.taskQueue) || value.taskQueue.length !== 0) {
    throw new Error("Storage restart smoke requires an empty task queue");
  }
  if (value.taskSchedulerLifecycle !== "idle") {
    throw new Error("Storage restart smoke requires an idle task scheduler");
  }
  if (typeof value.dimension !== "string" || !value.dimension) {
    throw new Error("Storage restart smoke requires the NPC dimension");
  }
  return {
    worldId: requireWorldId(value.worldId),
    dimension: value.dimension,
    stance: value.stance,
    status: value.status,
  };
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 300;
  if (!Number.isFinite(seconds) || seconds < 120 || seconds > 900) {
    throw new Error("--wait-seconds must be between 120 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveStorageRestartSmoke(options) {
  const companion = await connectedCompanion(options.base);
  const baseline = validateStartingSnapshot(await snapshot(options.base, companion.id));
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      usedMinecraftTChat: true,
      normalSaveAndQuit: true,
      companionId: companion.id,
      worldId: baseline.worldId,
      dimension: baseline.dimension,
    };
  }

  const message = "从家里箱子拿96个圆石给我";
  const prior = await request(options.base, "/api/tasks");
  const priorIds = new Set(prior.tasks?.map((task) => task.id) ?? []);
  let task = null;
  let gameStopped = false;
  let reconnected = false;
  let setupAttempted = false;
  let setupCompleted = false;
  let result = null;
  let primaryError = null;
  let cleanup = null;
  let saveAndQuit = null;
  let finalGameClosed = false;
  const cleanupErrors = [];
  try {
    setupAttempted = true;
    await fixture(options.base, companion.id, "setup-restart");
    setupCompleted = true;
    const initial = validateInspection(parseInspection(
      (await fixture(options.base, companion.id, "inspect-restart")).status,
    ), "initial");

    await sendMinecraftTChat(message);
    task = await waitForNewTask(options.base, companion.id, priorIds);
    const acceptedBeforeRestart = await waitForForgeAcceptance(options.base, companion.id, task.id);
    const stopOutput = await runPowerShell(
      "stop-minecraft-for-update.ps1",
      gracefulCloseArguments(options.base, companion.id),
      120_000,
    );
    saveAndQuit = parseGracefulCloseEvidence(stopOutput);
    gameStopped = true;
    await waitForDisconnect(options.base, companion.id);

    const duringRestart = await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`);
    if (TERMINAL.has(duringRestart.status)) {
      throw new Error(`Storage task became ${duringRestart.status} before Minecraft could reconnect`);
    }

    await runPowerShell("launch-hmcl-background.ps1", ["-WaitSeconds", "180"], 240_000);
    await runPowerShell("enter-hmcl-test-world.ps1", worldEntryArguments(baseline.worldId), 330_000);
    await waitForReconnect(options.base, companion.id, baseline.worldId, baseline.dimension);
    reconnected = true;

    const taskReport = await waitForTask(options.base, task, options.waitMs);
    const inspection = validateInspection(parseInspection(
      (await fixture(options.base, companion.id, "inspect-restart")).status,
    ), "final");
    result = {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      usedMinecraftTChat: true,
      normalSaveAndQuit: true,
      companionId: companion.id,
      worldId: baseline.worldId,
      dimension: baseline.dimension,
      message,
      task: taskReport,
      acceptedBeforeRestart,
      statusDuringRestart: duringRestart.status,
      saveAndQuit,
      initial,
      inspection,
    };
  } catch (error) {
    primaryError = error;
  } finally {
    if (gameStopped && !reconnected) {
      try {
        await runPowerShell("launch-hmcl-background.ps1", ["-WaitSeconds", "180"], 240_000);
        await runPowerShell("enter-hmcl-test-world.ps1", worldEntryArguments(baseline.worldId), 330_000);
        await waitForReconnect(options.base, companion.id, baseline.worldId, baseline.dimension);
        reconnected = true;
      } catch (error) {
        // Leave the persisted marker intact so the next cleanup can restore it.
        cleanupErrors.push(new Error(`Minecraft recovery failed; storage marker was preserved: ${error instanceof Error ? error.message : String(error)}`));
      }
    }
    if ((reconnected || !gameStopped) && setupAttempted) {
      if (task?.id) {
        try {
          const current = await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`);
          if (!TERMINAL.has(current.status)) {
            await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
              method: "POST",
              body: { reason: "live storage restart fixture cleanup" },
            });
          }
        } catch (error) {
          cleanupErrors.push(error);
        }
      }
      try {
        const acknowledged = await fixture(options.base, companion.id, "cleanup");
        cleanup = validateCleanupAcknowledgement(acknowledged.fixtureAck, setupCompleted);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (reconnected || !gameStopped) {
      try {
        const finalStopOutput = await runPowerShell(
          "stop-minecraft-for-update.ps1",
          gracefulCloseArguments(options.base, companion.id),
          120_000,
        );
        parseGracefulCloseEvidence(finalStopOutput);
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
  if (cleanupErrors.length) throw new AggregateError(cleanupErrors, "Storage restart cleanup failed");
  if (!result) throw new Error("Storage restart smoke produced no result");
  return { ...result, cleanup, finalGameClosed };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveStorageRestartSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live storage restart smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
