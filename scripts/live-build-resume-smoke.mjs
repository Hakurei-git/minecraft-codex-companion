import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const REPEATABLE_FIXTURES = new Set(["inspect-failed", "release", "inspect-complete", "cleanup"]);
const OWNER = "live-build-resume-smoke";
const REQUESTED_BY = "build-resume-acceptance";
const BLOCKS = [
  ["minecraft:birch_planks", 0, 0, 0],
  ["minecraft:spruce_stairs", 2, 0, 0],
  ["minecraft:jungle_slab", 4, 0, 0],
  ["minecraft:acacia_fence", 0, 0, 2],
  ["minecraft:dark_oak_trapdoor", 2, 0, 2],
  ["minecraft:mangrove_pressure_plate", 4, 0, 2],
];

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live build resume smoke only connects to a loopback HTTP service");
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
  switch (mode) {
    case "setup": return "build-resume:setup origin=";
    case "inspect-failed": return "build-resume:f=";
    case "release": return "build-resume:release task=";
    case "inspect-complete": return "build-resume:complete expected=";
    case "cleanup": return "build-resume:cleanup ";
    default: throw new Error(`Unsupported build resume fixture mode ${mode}`);
  }
}

export function fixtureIsRepeatable(mode) {
  return REPEATABLE_FIXTURES.has(mode);
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "build-resume"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const expected = fixtureExpectedPrefix(mode);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "build-resume", mode },
  });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 250;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ")) {
        throw new Error(`Minecraft rejected build resume fixture ${mode}: ${status}`);
      }
      if (status.startsWith("live-fixture:failed ") && !fixtureIsRepeatable(mode)) {
        throw new Error(`Minecraft build resume fixture ${mode} failed: ${status}`);
      }
      if (status.startsWith(expected)) return { ...current, status };
      if (!fixtureIsRepeatable(mode)) {
        throw new Error(`Minecraft build resume fixture ${mode} returned an unexpected acknowledgement: ${status}`);
      }
    }
    if (fixtureIsRepeatable(mode) && Date.now() >= nextRetry) {
      await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
        method: "POST",
        body: { suite: "build-resume", mode },
      });
      nextRetry = Date.now() + 250;
    }
  }
  throw new Error(`Minecraft did not acknowledge build resume fixture ${mode}`);
}

export function parseSetupStatus(status) {
  const match = /^build-resume:setup origin=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected build resume setup status: ${JSON.stringify(status)}`);
  return { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]) };
}

export function parseFailedInspection(status) {
  const match = /^build-resume:f=([^,\s]+),(\d+),(\d+),([A-Z0-9_]+),(\d+),(\d+),(\d+),(\d+),(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected failed build resume inspection: ${JSON.stringify(status)}`);
  return {
    taskId: match[1],
    index: Number(match[2]),
    total: Number(match[3]),
    code: match[4],
    prefix: Number(match[5]),
    blocker: Number(match[6]),
    tail: Number(match[7]),
    denied: Number(match[8]),
    releasedBreaks: Number(match[9]),
  };
}

export function validateFailedInspection(inspection, taskId) {
  if (inspection.taskId !== taskId
    || inspection.index !== 3
    || inspection.total !== BLOCKS.length
    || inspection.code !== "BLOCK_BREAK_DENIED"
    || inspection.prefix !== 3
    || inspection.blocker !== 1
    || inspection.tail !== 0
    || inspection.denied < 3
    || inspection.releasedBreaks !== 0) {
    throw new Error(`Build failure checkpoint invariants failed: ${JSON.stringify(inspection)}`);
  }
  return inspection;
}

export function parseReleaseStatus(status) {
  const match = /^build-resume:release task=([^,\s]+),index=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected build resume release status: ${JSON.stringify(status)}`);
  return { taskId: match[1], index: Number(match[2]) };
}

export function validateRelease(release, taskId) {
  if (release.taskId !== taskId || release.index !== 3) {
    throw new Error(`Build resume release changed checkpoint identity: ${JSON.stringify(release)}`);
  }
  return release;
}

export function parseCompleteInspection(status) {
  const match = /^build-resume:complete expected=(\d+),matching=(\d+),wrong=(\d+),denied=(\d+),releasedBreaks=(\d+),recoverable=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected completed build resume inspection: ${JSON.stringify(status)}`);
  return {
    expected: Number(match[1]),
    matching: Number(match[2]),
    wrong: Number(match[3]),
    denied: Number(match[4]),
    releasedBreaks: Number(match[5]),
    recoverable: Number(match[6]),
  };
}

export function validateCompleteInspection(inspection) {
  if (inspection.expected !== BLOCKS.length
    || inspection.matching !== BLOCKS.length
    || inspection.wrong !== 0
    || inspection.denied < 3
    || inspection.releasedBreaks < 1
    || inspection.recoverable !== 0) {
    throw new Error(`Build resume completion invariants failed: ${JSON.stringify(inspection)}`);
  }
  return inspection;
}

export function validateFailedTask(task) {
  if (task?.status !== "failed"
    || task.error?.code !== "BLOCK_BREAK_DENIED"
    || task.error?.retryable !== true
    || Math.abs(Number(task.progress) - 0.5) > 1e-9) {
    throw new Error(`Build task did not fail at the exact recoverable midpoint: ${JSON.stringify(task)}`);
  }
  return task;
}

export function validateRecoverableQueue(snapshotValue, taskId) {
  const entry = snapshotValue?.taskQueue?.find((candidate) => candidate.id === taskId);
  if (!entry
    || entry.kind !== "build"
    || entry.phase !== "paused"
    || Math.abs(Number(entry.progress) - 0.5) > 1e-9
    || !String(entry.pauseReason ?? "").includes("BLOCK_BREAK_DENIED")) {
    throw new Error(`Snapshot did not expose the exact recoverable build checkpoint: ${JSON.stringify(entry)}`);
  }
  return entry;
}

export function validateCleanupStatus(status) {
  if (!["build-resume:cleanup restored", "build-resume:cleanup none"].includes(status)) {
    throw new Error(`Build resume fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

export function buildPlanDraft(origin) {
  if (![origin?.x, origin?.y, origin?.z].every(Number.isInteger)) {
    throw new Error("Build resume fixture origin must contain integer coordinates");
  }
  return {
    name: "Live build failure checkpoint resume",
    source: "demo",
    origin: { x: origin.x, y: origin.y, z: origin.z },
    blocks: BLOCKS.map(([blockId, x, y, z]) => ({
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
    materialPreference: { source: "inventory", allowMixed: true },
    requestedBy: REQUESTED_BY,
    note: "Local reversible exact-checkpoint build resume acceptance",
  };
}

async function createConfirmedPlan(base, origin) {
  const draft = buildPlanDraft(origin);
  const preview = await request(base, "/api/build-plans/preview", { method: "POST", body: draft });
  if (!preview?.id
    || preview.confirmed !== false
    || JSON.stringify(preview.origin) !== JSON.stringify(draft.origin)
    || JSON.stringify(preview.blocks) !== JSON.stringify(draft.blocks)) {
    throw new Error(`Control plane returned an invalid build resume preview: ${JSON.stringify(preview)}`);
  }
  const confirmed = await request(base, `/api/build-plans/${encodeURIComponent(preview.id)}/confirm`, {
    method: "POST",
  });
  if (confirmed?.id !== preview.id
    || confirmed.confirmed !== true
    || JSON.stringify(confirmed.origin) !== JSON.stringify(draft.origin)
    || JSON.stringify(confirmed.blocks) !== JSON.stringify(draft.blocks)) {
    throw new Error(`Build resume preview ${preview.id} was not confirmed intact`);
  }
  return confirmed;
}

async function assignBuild(base, companionId, planId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec: buildTaskSpec(planId), owner: OWNER },
  });
}

async function retryBuild(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks/retry-build`, {
    method: "POST",
    body: { owner: OWNER, requestedBy: REQUESTED_BY },
  });
}

export async function waitForTerminal(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Build resume task ${task.id} timed out`);
  return current;
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live build resume fixture cleanup" },
    });
  }
  return waitForTerminal(base, current, waitMs);
}

async function cleanupAndConfirm(base, companionId) {
  const status = String((await fixture(base, companionId, "cleanup")).status ?? "");
  return validateCleanupStatus(status);
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 300;
  if (!Number.isFinite(seconds) || seconds < 15 || seconds > 900) {
    throw new Error("--wait-seconds must be between 15 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveBuildResumeSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      sameTaskRequired: true,
      checkpointIndex: 3,
      blockCount: BLOCKS.length,
    };
  }

  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  let task = null;
  let retried = null;
  try {
    await cleanupAndConfirm(options.base, companion.id);
    const origin = parseSetupStatus(String((await fixture(options.base, companion.id, "setup")).status ?? ""));
    const plan = await createConfirmedPlan(options.base, origin);
    task = await assignBuild(options.base, companion.id, plan.id);
    const failed = validateFailedTask(await waitForTerminal(options.base, task, options.waitMs));
    const failedSnapshot = await fixture(options.base, companion.id, "inspect-failed");
    const checkpoint = validateFailedInspection(parseFailedInspection(failedSnapshot.status), task.id);
    validateRecoverableQueue(failedSnapshot, task.id);

    const released = validateRelease(parseReleaseStatus(
      String((await fixture(options.base, companion.id, "release")).status ?? ""),
    ), task.id);
    retried = await retryBuild(options.base, companion.id);
    if (retried.id !== task.id || Math.abs(Number(retried.progress) - Number(failed.progress)) > 1e-9) {
      throw new Error(`Retry did not preserve task identity and progress: ${JSON.stringify(retried)}`);
    }
    const finished = await waitForTerminal(options.base, retried, options.waitMs);
    if (finished.id !== task.id || finished.status !== "succeeded") {
      throw new Error(`Resumed build did not succeed as the same task: ${JSON.stringify(finished)}`);
    }
    const completion = validateCompleteInspection(parseCompleteInspection(
      String((await fixture(options.base, companion.id, "inspect-complete")).status ?? ""),
    ));
    return {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      companionId: companion.id,
      origin,
      planId: plan.id,
      task: {
        id: finished.id,
        failedProgress: failed.progress,
        finalStatus: finished.status,
      },
      checkpoint,
      released,
      completion,
    };
  } finally {
    try {
      await cancelAndWait(options.base, retried ?? task, Math.min(options.waitMs, 30_000));
    } finally {
      await cleanupAndConfirm(options.base, companion.id);
    }
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveBuildResumeSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live build resume smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
