import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const FIXTURE_TAG = "CodexAcceptanceRanchAnimal";
export const RANCH_ACTOR_EVIDENCE = Object.freeze({
  actor: "ai-npc",
  playerGameplayAssistanceUsed: false,
});
const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function loopbackBase(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("live ranch smoke only connects to a loopback HTTP service");
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

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "ranch"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const expectedStatusPrefix = mode === "inspect" ? "ranch-fixture:adults=" : "";
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "ranch", mode },
  });
  if (mode === "arm-chat-establish") return before;
  const deadline = Date.now() + 10_000;
  let current = before;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 150));
    current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status === null) continue;
    if (status.startsWith("live-fixture:denied ")) {
      throw new Error(`Minecraft rejected ranch fixture ${mode}: ${status}`);
    }
    if (status.startsWith("live-fixture:failed ")) {
      throw new Error(`Minecraft ranch fixture ${mode} failed: ${status}`);
    }
    if (expectedStatusPrefix && !status.startsWith(expectedStatusPrefix)) {
      throw new Error(`Minecraft ranch fixture ${mode} returned an unexpected acknowledgement: ${status}`);
    }
    return { ...current, status };
  }
  throw new Error(`Minecraft did not acknowledge ranch fixture ${mode}`);
}

async function sendMinecraftTChat(message) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  await execFileAsync("powershell.exe", [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
    "-MessageUtf8Base64", encoded,
    "-RespawnIfDead",
  ], {
    cwd: projectRoot,
    windowsHide: true,
    timeout: 20_000,
    maxBuffer: 100_000,
  });
}

async function waitForNewRanchMacro(base, companionId, priorIds, waitMs = 15_000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/tasks");
    const task = response.tasks?.find((candidate) => (
      candidate.companionId === companionId
      && !priorIds.has(candidate.id)
      && candidate.spec?.kind === "macro"
      && candidate.spec?.skillId === "life.establish-ranch"
    ));
    if (task) return task;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("Minecraft T chat did not create life.establish-ranch");
}

async function assignRanch(base, companionId, action, count) {
  const assigned = await request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: {
      owner: "live-ranch-smoke",
      spec: {
        kind: "ranch",
        action,
        animalType: "minecraft:cow",
        count,
        radius: 64,
        fixtureTag: FIXTURE_TAG,
        requestedBy: "live-ranch-smoke",
        note: `Local reversible ranch acceptance: ${action}`,
      },
    },
  });
  return assigned;
}

export async function waitForTask(base, task, waitMs) {
  const messages = [];
  const deadline = Date.now() + waitMs;
  let current = task;
  while (Date.now() < deadline && !TERMINAL.has(current.status)) {
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
    if (typeof current.message === "string" && messages.at(-1) !== current.message) {
      messages.push(current.message);
      if (messages.length > 20) messages.shift();
    }
  }
  if (!TERMINAL.has(current.status)) {
    const detail = current.message ?? "no progress message";
    const trail = messages.length > 0 ? `; progress=${JSON.stringify(messages)}` : "";
    throw new Error(
      `Ranch task ${task.id} timed out at ${Number(current.progress ?? 0).toFixed(3)}: ${detail}${trail}`,
    );
  }
  if (current.status !== "succeeded") {
    const failure = current.message ?? current.error?.message ?? current.error?.code ?? "unknown";
    const trail = messages.length > 0 ? `; progress=${JSON.stringify(messages)}` : "";
    throw new Error(`Ranch task ${task.id} ${current.status}: ${failure}${trail}`);
  }
  return {
    id: current.id,
    action: current.spec.kind === "ranch" ? current.spec.action : current.spec.skillId,
    status: current.status,
    message: current.message,
    messages,
  };
}

export function parseInspection(status) {
  const match = /^ranch-fixture:adults=(\d+),babies=(\d+),inside=(\d+),outside=(\d+),built=([01]),blocks=(\d+),placements=(\d+),gate=(missing|open|closed)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected ranch inspection status: ${JSON.stringify(status)}`);
  return {
    adults: Number(match[1]),
    babies: Number(match[2]),
    inside: Number(match[3]),
    outside: Number(match[4]),
    built: Number(match[5]),
    blocks: Number(match[6]),
    placements: Number(match[7]),
    gate: match[8],
  };
}

export function isUnbuiltPreparation(inspection) {
  return inspection.built === 0
    && inspection.blocks === 0
    && inspection.placements === 0
    && inspection.gate === "missing";
}

export function isNpcBuiltPen(inspection) {
  return inspection.built === 1
    && inspection.blocks === 32
    && inspection.placements === 32
    && inspection.gate === "closed";
}

async function inspectUntil(base, companionId, predicate, waitMs, label) {
  const deadline = Date.now() + waitMs;
  let inspection = null;
  while (Date.now() < deadline) {
    const current = await fixture(base, companionId, "inspect");
    inspection = parseInspection(current.status);
    if (predicate(inspection)) return inspection;
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error(`${label} inspection did not converge: ${JSON.stringify(inspection)}`);
}

function parseCli(argv) {
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

export async function runLiveRanchSmoke(options) {
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      ...RANCH_ACTOR_EVIDENCE,
      usedMinecraftTChat: true,
      companionId: companion.id,
      stages: ["establish", "breed", "cull", "cleanup"],
    };
  }

  const tasks = [];
  let establishReady = null;
  let establish = null;
  let breedReady = null;
  let breed = null;
  let cullReady = null;
  let cull = null;
  let activeTask = null;
  let cleanupStatus = "not-run";
  try {
    await fixture(options.base, companion.id, "cleanup");
    await fixture(options.base, companion.id, "setup-establish");
    establishReady = await inspectUntil(options.base, companion.id, (value) => (
      value.adults === 2 && value.babies === 0 && value.inside === 0
        && value.outside === 2 && isUnbuiltPreparation(value)
    ), 30_000, "establish preparation");
    const before = await request(options.base, "/api/tasks");
    const priorIds = new Set(before.tasks?.map((task) => task.id) ?? []);
    await fixture(options.base, companion.id, "arm-chat-establish");
    await sendMinecraftTChat("建个围栏养两只牛");
    activeTask = await waitForNewRanchMacro(options.base, companion.id, priorIds);
    if (activeTask.spec.arguments?.animalType !== "minecraft:cow"
      || activeTask.spec.arguments?.count !== 2
      || activeTask.spec.arguments?.radius !== 128) {
      throw new Error(`T chat created the wrong ranch macro: ${JSON.stringify(activeTask.spec)}`);
    }
    tasks.push(await waitForTask(options.base, activeTask, options.waitMs));
    activeTask = null;
    establish = await inspectUntil(options.base, companion.id, (value) => (
      value.adults >= 2 && value.inside >= 2 && value.outside === 0 && isNpcBuiltPen(value)
    ), 30_000, "establish");

    await fixture(options.base, companion.id, "supply-breed");
    breedReady = await inspectUntil(options.base, companion.id, (value) => (
      value.adults >= 2 && value.babies === 0 && value.inside >= 2
        && value.outside === 0 && isNpcBuiltPen(value)
    ), 30_000, "breed preparation");
    activeTask = await assignRanch(options.base, companion.id, "breed", 2);
    tasks.push(await waitForTask(options.base, activeTask, options.waitMs));
    activeTask = null;
    breed = await inspectUntil(options.base, companion.id, (value) => (
      value.adults >= 2 && value.babies >= 1 && value.inside >= 3 && isNpcBuiltPen(value)
    ), 45_000, "breed");

    await fixture(options.base, companion.id, "setup-cull");
    cullReady = await inspectUntil(options.base, companion.id, (value) => (
      value.adults >= 3 && value.babies >= 1 && value.inside >= 4
        && value.outside === 0 && isNpcBuiltPen(value)
    ), 30_000, "cull preparation");
    activeTask = await assignRanch(options.base, companion.id, "cull", 3);
    tasks.push(await waitForTask(options.base, activeTask, options.waitMs));
    activeTask = null;
    cull = await inspectUntil(options.base, companion.id, (value) => (
      value.adults === 2 && value.babies >= 1 && value.inside === 3
        && value.outside === 0 && isNpcBuiltPen(value)
    ), 30_000, "cull");
  } finally {
    if (activeTask?.id) {
      try {
        const current = await request(options.base, `/api/tasks/${encodeURIComponent(activeTask.id)}`);
        if (!TERMINAL.has(current.status)) {
          await request(options.base, `/api/tasks/${encodeURIComponent(activeTask.id)}/cancel`, {
            method: "POST",
            body: { reason: "live ranch fixture cleanup" },
          });
        }
      } catch {
        // Continue with world cleanup even if task cancellation races terminal completion.
      }
    }
    try {
      const restored = await fixture(options.base, companion.id, "cleanup");
      cleanupStatus = restored.status;
    } catch (error) {
      cleanupStatus = `failed:${error instanceof Error ? error.message : String(error)}`;
    }
  }

  if (cleanupStatus !== "ranch-fixture:cleanup restored") {
    throw new Error(`Ranch fixture cleanup was not confirmed: ${cleanupStatus}`);
  }
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    ...RANCH_ACTOR_EVIDENCE,
    usedMinecraftTChat: true,
    companionId: companion.id,
    tasks,
    inspections: {
      establishReady,
      establish,
      breedReady,
      breed,
      cullReady,
      cull,
    },
    cleanupStatus,
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveRanchSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live ranch smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
