import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const TARGET_COUNT = 16;
export const HUNTABLE_COUNT = 18;
export const PROMPT = "\u7ed9\u621116\u4e2a\u8089";

export const FOOD_SURVIVAL_ACTOR_EVIDENCE = Object.freeze({
  actor: "ai-npc",
  playerGameplayAssistanceUsed: false,
  usedMinecraftTChat: true,
});

export function loopbackBase(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("live food survival restart smoke only connects to a loopback HTTP service");
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
    throw new Error(`Expected exactly one connected in-world NPC, found ${companions.length}`);
  }
  return companions[0];
}

async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

export function requireWorldId(value) {
  const worldId = typeof value === "string" ? value : "";
  if (!worldId.trim() || worldId.length > 128 || /[\u0000-\u001f\u007f]/u.test(worldId)) {
    throw new Error("The live food survival restart smoke requires a valid Minecraft world ID");
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

export function parseGracefulCloseEvidence(stdout) {
  let value;
  try {
    value = JSON.parse(String(stdout).trim());
  } catch {
    throw new Error("Minecraft graceful-close helper did not return JSON evidence");
  }
  if (value?.SavedAndClosed !== true || value?.LeftWorldBeforeWindowClose !== true
    || value?.PauseMenuConfirmed !== true
    || value?.BackgroundPauseLeaseArmed !== true
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

export function fixtureExpectedPrefix(mode) {
  return {
    setup: "food-survival:setup ",
    "setup-16": "food-survival:setup ",
    inspect: "food-survival:a=",
    "arm-guard": "food-survival:guard armed=1",
    "release-guard": "food-survival:guard released=1",
    checkpoint: "food-survival:checkpoint ",
    "verify-restart": "food-survival:restart same=1,",
    cleanup: "food-survival:cleanup ",
  }[mode] ?? (() => { throw new Error(`Unsupported food survival fixture mode ${mode}`); })();
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  const sequence = Number(acknowledgement?.sequence);
  if (!acknowledgement
    || !Number.isSafeInteger(sequence)
    || sequence <= Number(beforeSequence)
    || acknowledgement.suite !== "food-survival"
    || acknowledgement.mode !== mode) return null;
  return {
    sequence,
    suite: acknowledgement.suite,
    mode: acknowledgement.mode,
    status: String(acknowledgement.status ?? ""),
  };
}

export function validateFixtureAcknowledgement(acknowledgement, mode) {
  if (!acknowledgement || acknowledgement.suite !== "food-survival" || acknowledgement.mode !== mode) {
    throw new Error(`Invalid food survival fixture ${mode} acknowledgement envelope`);
  }
  if (acknowledgement.status.startsWith("live-fixture:denied ")
    || acknowledgement.status.startsWith("live-fixture:failed ")) {
    throw new Error(`Minecraft rejected food survival fixture ${mode}: ${acknowledgement.status}`);
  }
  if (!acknowledgement.status.startsWith(fixtureExpectedPrefix(mode))) {
    throw new Error(`Unexpected food survival fixture ${mode} acknowledgement: ${acknowledgement.status}`);
  }
  if (mode === "cleanup"
    && !["food-survival:cleanup restored", "food-survival:cleanup none"].includes(acknowledgement.status)) {
    throw new Error(`Food survival cleanup was incomplete: ${acknowledgement.status}`);
  }
  return acknowledgement;
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "food-survival", mode },
  });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 40));
    const current = await snapshot(base, companionId);
    const acknowledgement = fixtureAcknowledgement(current, beforeSequence, mode);
    if (acknowledgement) {
      return { ...current, fixtureAck: validateFixtureAcknowledgement(acknowledgement, mode) };
    }
  }
  throw new Error(`Minecraft did not acknowledge food survival fixture ${mode}`);
}

export function parseInspection(status) {
  const match = /^food-survival:a=(\d+),k=(\d+),r=(\d+),i=([01]),l=([01]),o=([01]),w=(\d+),g=([01]),u=([01]),x=([01]),s=(\d+),p=(\d+),v=(\d+),d=(\d+),t=([01]),q=(\d+),h=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected food survival inspection: ${JSON.stringify(status)}`);
  return {
    attacks: Number(match[1]),
    kills: Number(match[2]),
    rawDrops: Number(match[3]),
    inputObserved: Number(match[4]),
    litObserved: Number(match[5]),
    outputObserved: Number(match[6]),
    withdrawn: Number(match[7]),
    guardObserved: Number(match[8]),
    resumeObserved: Number(match[9]),
    restartObserved: Number(match[10]),
    survivingAdults: Number(match[11]),
    protectedAlive: Number(match[12]),
    violations: Number(match[13]),
    physicalDelivered: Number(match[14]),
    sameTaskObserved: Number(match[15]),
    targetCount: Number(match[16]),
    huntableCount: Number(match[17]),
  };
}

export function validateInspection(value, phase) {
  const fixedLedger = value.targetCount === TARGET_COUNT && value.huntableCount === HUNTABLE_COUNT;
  const conservedAdults = value.kills + value.survivingAdults === value.huntableCount;
  const protectedSafe = value.protectedAlive === 3 && value.violations === 0;
  let valid;
  if (phase === "initial") {
    valid = value.attacks === 0 && value.kills === 0 && value.rawDrops === 0
      && value.inputObserved === 0 && value.litObserved === 0 && value.outputObserved === 0
      && value.withdrawn === 0 && value.guardObserved === 0 && value.resumeObserved === 0
      && value.restartObserved === 0 && value.survivingAdults === HUNTABLE_COUNT && protectedSafe
      && value.physicalDelivered === 0 && value.sameTaskObserved === 0;
  } else if (phase === "cooking") {
    valid = value.attacks >= value.kills && value.kills >= 1 && conservedAdults
      && value.rawDrops >= TARGET_COUNT && value.inputObserved === 1 && value.litObserved === 1
      && value.guardObserved === 0 && value.restartObserved === 0
      && value.survivingAdults >= 2 && protectedSafe && value.physicalDelivered === 0;
  } else if (phase === "guard") {
    valid = value.guardObserved === 1 && value.resumeObserved === 0
      && value.sameTaskObserved === 1 && conservedAdults && protectedSafe;
  } else if (phase === "resumed") {
    valid = value.guardObserved === 1 && value.resumeObserved === 1
      && value.sameTaskObserved === 1 && conservedAdults && protectedSafe;
  } else if (phase === "final") {
    valid = value.attacks >= value.kills && value.kills >= 1 && conservedAdults
      && value.rawDrops >= TARGET_COUNT && value.inputObserved === 1 && value.litObserved === 1
      && value.outputObserved === 1 && value.withdrawn >= TARGET_COUNT
      && value.guardObserved === 1 && value.resumeObserved === 1 && value.restartObserved === 1
      && value.survivingAdults >= 2 && protectedSafe
      && value.physicalDelivered === TARGET_COUNT && value.sameTaskObserved === 1;
  } else {
    throw new Error(`Unknown food survival inspection phase ${phase}`);
  }
  if (!fixedLedger || !valid) throw new Error(`Food survival ${phase} invariants failed: ${JSON.stringify(value)}`);
  return value;
}

export function validateStartingSnapshot(value) {
  if (value.npcDowned !== false) throw new Error("Food survival smoke requires an active NPC");
  if (typeof value.activeTaskId !== "string" || value.activeTaskId.trim()) {
    throw new Error("Food survival smoke requires no active task");
  }
  if (!Number.isInteger(value.pausedTaskCount) || value.pausedTaskCount !== 0) {
    throw new Error("Food survival smoke requires no paused tasks");
  }
  if (!Array.isArray(value.taskQueue) || value.taskQueue.length !== 0
    || value.taskSchedulerLifecycle !== "idle") {
    throw new Error("Food survival smoke requires an idle scheduler");
  }
  if (typeof value.dimension !== "string" || !value.dimension) {
    throw new Error("Food survival smoke requires the NPC dimension");
  }
  return { worldId: requireWorldId(value.worldId), dimension: value.dimension };
}

async function inspectUntil(base, companionId, phase, waitMs) {
  const deadline = Date.now() + waitMs;
  let last = null;
  let lastError = null;
  while (Date.now() < deadline) {
    last = parseInspection((await fixture(base, companionId, "inspect")).fixtureAck.status);
    try {
      return validateInspection(last, phase);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw lastError ?? new Error(`Food survival ${phase} inspection did not converge: ${JSON.stringify(last)}`);
}

async function sendMinecraftTChat(message) {
  await runPowerShell(
    "send-minecraft-chat-background.ps1",
    ["-MessageUtf8Base64", Buffer.from(message, "utf8").toString("base64"), "-RespawnIfDead"],
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

export function selectNewCompanionTasks(response, companionId, priorIds) {
  return response?.tasks?.filter((candidate) => (
    candidate.companionId === companionId && !priorIds.has(candidate.id)
  )) ?? [];
}

async function waitForNewTasks(base, companionId, priorIds) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/tasks");
    const tasks = selectNewCompanionTasks(response, companionId, priorIds);
    if (tasks.length > 0) {
      await new Promise((resolve) => setTimeout(resolve, 250));
      return selectNewCompanionTasks(await request(base, "/api/tasks"), companionId, priorIds);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error("Minecraft T chat did not create a task");
}

export function validateTaskSpec(task) {
  const spec = task?.spec;
  if (spec?.kind !== "provision-food" || spec.count !== TARGET_COUNT || spec.source !== "hunt"
    || spec.destination !== "player" || typeof spec.player !== "string" || !spec.player.trim()) {
    throw new Error(`T chat created the wrong food survival task: ${JSON.stringify(spec)}`);
  }
  return task;
}

export function validateCreatedTaskSet(tasks) {
  if (!Array.isArray(tasks) || tasks.length !== 1) {
    throw new Error(`Minecraft T chat created ${Array.isArray(tasks) ? tasks.length : 0} tasks instead of one`);
  }
  return validateTaskSpec(tasks[0]);
}

async function waitForGuardQueue(base, companionId, taskId) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const combat = current.taskQueue?.find((entry) => entry.id === "local:combat-assist" && entry.phase === "active");
    const paused = current.taskQueue?.find((entry) => entry.id === taskId && entry.phase === "paused");
    if (combat && paused && current.pausedTaskCount >= 1) return true;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("NPC did not expose owner-protection interruption while cooking");
}

async function waitForResumedQueue(base, companionId, taskId) {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const resumed = current.taskQueue?.find((entry) => entry.id === taskId && entry.phase === "active");
    const combat = current.taskQueue?.some((entry) => entry.id === "local:combat-assist");
    if (resumed && !combat && current.pausedTaskCount === 0) return true;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error("NPC did not resume the same food task after owner protection");
}

async function waitForDisconnect(base, companionId) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/companions");
    const companion = response.companions?.find((candidate) => candidate.id === companionId);
    if (!companion?.connected) return true;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error("Minecraft bridge remained connected after normal Save and Quit");
}

async function waitForReconnect(base, companionId, expectedWorldId, expectedDimension) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/companions");
    const companion = response.companions?.find((candidate) => candidate.id === companionId && candidate.connected);
    if (companion) {
      const current = await snapshot(base, companionId);
      if (current.worldId !== expectedWorldId || current.dimension !== expectedDimension) {
        throw new Error("Minecraft reconnected to a different world or dimension");
      }
      return current;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error("Minecraft bridge did not reconnect after restart");
}

async function waitForTerminal(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (Date.now() < deadline && !TERMINAL.has(current.status)) {
    await new Promise((resolve) => setTimeout(resolve, 250));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Food survival task ${task.id} timed out`);
  if (current.status !== "succeeded") {
    throw new Error(`Food survival task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return { id: current.id, status: current.status, spec: current.spec, message: current.message };
}

async function cancelOwnedTasks(base, ownedTaskIds) {
  for (const taskId of ownedTaskIds) {
    const task = await request(base, `/api/tasks/${encodeURIComponent(taskId)}`);
    if (!TERMINAL.has(task.status)) {
      await request(base, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
        method: "POST",
        body: { reason: "live food survival restart fixture cleanup" },
      });
    }
  }
}

async function waitForIdle(base, companionId) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    if (!current.activeTaskId && current.pausedTaskCount === 0
      && current.taskSchedulerLifecycle === "idle" && current.taskQueue?.length === 0) return current;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("NPC scheduler did not become idle for food survival cleanup");
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
    closeAfter: !argv.includes("--keep-game-open"),
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveFoodSurvivalRestartSmoke(options) {
  const companion = await connectedCompanion(options.base);
  const baseline = validateStartingSnapshot(await snapshot(options.base, companion.id));
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      normalSaveAndQuitRequired: true,
      ...FOOD_SURVIVAL_ACTOR_EVIDENCE,
      companionId: companion.id,
      prompt: PROMPT,
    };
  }

  const prior = await request(options.base, "/api/tasks");
  const priorIds = new Set(prior.tasks?.map((task) => task.id) ?? []);
  let task = null;
  let setupAttempted = false;
  let setupCompleted = false;
  let gameStopped = false;
  let reconnected = false;
  let finalGameClosed = false;
  let saveAndQuit = null;
  let ownedTaskIds = new Set();
  let result = null;
  let primaryError = null;
  const cleanupErrors = [];
  try {
    setupAttempted = true;
    await fixture(options.base, companion.id, "setup-16");
    setupCompleted = true;
    const initial = validateInspection(parseInspection(
      (await fixture(options.base, companion.id, "inspect")).fixtureAck.status,
    ), "initial");

    await sendMinecraftTChat(PROMPT);
    const createdTasks = await waitForNewTasks(options.base, companion.id, priorIds);
    ownedTaskIds = new Set(createdTasks.map((created) => created.id));
    task = createdTasks[0] ?? null;
    task = validateCreatedTaskSet(createdTasks);
    const cooking = await inspectUntil(options.base, companion.id, "cooking", Math.min(options.waitMs, 300_000));

    await fixture(options.base, companion.id, "arm-guard");
    await waitForGuardQueue(options.base, companion.id, task.id);
    const guard = await inspectUntil(options.base, companion.id, "guard", 10_000);
    await fixture(options.base, companion.id, "release-guard");
    await waitForResumedQueue(options.base, companion.id, task.id);
    const resumed = await inspectUntil(options.base, companion.id, "resumed", 10_000);

    const checkpoint = (await fixture(options.base, companion.id, "checkpoint")).fixtureAck.status;
    const stopOutput = await runPowerShell(
      "stop-minecraft-for-update.ps1",
      gracefulCloseArguments(options.base, companion.id),
      120_000,
    );
    gameStopped = true;
    saveAndQuit = parseGracefulCloseEvidence(stopOutput);
    await waitForDisconnect(options.base, companion.id);
    const duringRestart = await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`);
    if (TERMINAL.has(duringRestart.status)) {
      throw new Error(`Food survival task became ${duringRestart.status} before Minecraft could reconnect`);
    }

    await runPowerShell("launch-hmcl-background.ps1", ["-WaitSeconds", "180"], 240_000);
    await runPowerShell("enter-hmcl-test-world.ps1", worldEntryArguments(baseline.worldId), 330_000);
    await waitForReconnect(options.base, companion.id, baseline.worldId, baseline.dimension);
    reconnected = true;
    const restart = (await fixture(options.base, companion.id, "verify-restart")).fixtureAck.status;

    const terminal = await waitForTerminal(options.base, task, options.waitMs);
    if (terminal.id !== task.id) throw new Error("Food survival task ID changed after restart");
    const final = await inspectUntil(options.base, companion.id, "final", 30_000);
    result = {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      normalSaveAndQuit: saveAndQuit.leftWorldBeforeWindowClose,
      saveAndQuit,
      worldIdentityVerified: true,
      ...FOOD_SURVIVAL_ACTOR_EVIDENCE,
      companionId: companion.id,
      prompt: PROMPT,
      task: terminal,
      statusDuringRestart: duringRestart.status,
      initial,
      cooking,
      guard,
      resumed,
      checkpoint,
      restart,
      final,
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
        cleanupErrors.push(new Error(`Minecraft recovery failed; fixture marker was preserved: ${error instanceof Error ? error.message : String(error)}`));
      }
    }
    if ((reconnected || !gameStopped) && setupAttempted) {
      try {
        await fixture(options.base, companion.id, "release-guard");
      } catch {
        // Setup may have failed before the marker existed.
      }
      try {
        await cancelOwnedTasks(options.base, ownedTaskIds);
        await waitForIdle(options.base, companion.id);
      } catch (error) {
        cleanupErrors.push(error);
      }
      try {
        const cleanup = validateFixtureAcknowledgement(
          (await fixture(options.base, companion.id, "cleanup")).fixtureAck,
          "cleanup",
        );
        if (setupCompleted && cleanup.status !== "food-survival:cleanup restored") {
          throw new Error(`Food survival fixture did not restore its snapshot: ${cleanup.status}`);
        }
        if (result) result.cleanup = cleanup.status;
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (options.closeAfter !== false && (reconnected || !gameStopped)) {
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
  if (cleanupErrors.length) throw new AggregateError(cleanupErrors, "Food survival restart cleanup failed");
  if (!result) throw new Error("Food survival restart smoke produced no result");
  return { ...result, minecraftClosed: finalGameClosed };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveFoodSurvivalRestartSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live food survival restart smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
