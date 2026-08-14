import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const SCENARIOS = new Set(["retrieve", "organize", "expand", "craft-expand"]);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export function loopbackBase(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("live storage smoke only connects to a loopback HTTP service");
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
  if (mode === "inspect-craft-expand") return "storage-fixture:craft-expand|";
  if (mode.startsWith("inspect-")) return `storage-fixture:${mode.slice("inspect-".length)} `;
  if (mode.startsWith("setup-")) return `storage-fixture:setup scenario=${mode.slice("setup-".length)}`;
  return "";
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "storage"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const expectedPrefix = fixtureExpectedPrefix(mode);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "storage", mode },
  });
  const deadline = Date.now() + 12_000;
  let current = before;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 150));
    current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status === null) continue;
    if (status.startsWith("live-fixture:denied ")) {
      throw new Error(`Minecraft rejected storage fixture ${mode}: ${status}`);
    }
    if (status.startsWith("live-fixture:failed ")) {
      throw new Error(`Minecraft storage fixture ${mode} failed: ${status}`);
    }
    if (expectedPrefix && !status.startsWith(expectedPrefix)) {
      throw new Error(`Minecraft storage fixture ${mode} returned an unexpected acknowledgement: ${status}`);
    }
    return { ...current, status };
  }
  throw new Error(`Minecraft did not acknowledge storage fixture ${mode}`);
}

async function sendMinecraftTChat(message, base) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  await execFileAsync("powershell.exe", [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
    "-MessageUtf8Base64", encoded,
    "-ControlBaseUri", base.origin,
    "-RespawnIfDead",
  ], {
    cwd: projectRoot,
    windowsHide: true,
    timeout: 20_000,
    maxBuffer: 100_000,
  });
}

function matchesScenarioTask(task, scenario) {
  if (scenario === "retrieve") {
    return task.spec?.kind === "macro" && task.spec?.skillId === "life.retrieve-and-deliver";
  }
  return task.spec?.kind === "organize-storage";
}

async function waitForNewTask(base, companionId, priorIds, scenario) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/tasks");
    const task = response.tasks?.find((candidate) => (
      candidate.companionId === companionId
      && !priorIds.has(candidate.id)
      && matchesScenarioTask(candidate, scenario)
    ));
    if (task) return task;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Minecraft T chat did not create the expected ${scenario} storage task`);
}

async function waitForTask(base, task, waitMs) {
  const deadline = Date.now() + waitMs;
  let current = task;
  const messages = [];
  while (Date.now() < deadline && !TERMINAL.has(current.status)) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
    if (typeof current.message === "string" && messages.at(-1) !== current.message) messages.push(current.message);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Storage task ${task.id} timed out`);
  if (current.status !== "succeeded") {
    throw new Error(`Storage task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return { id: current.id, status: current.status, message: current.message, spec: current.spec, messages };
}

export function parseInspection(status, scenario) {
  if (scenario === "retrieve") {
    const match = /^storage-fixture:retrieve home=(\d+),npc=(\d+),player=(\d+),world=(\d+),near=(\d+),containers=(\d+)$/u.exec(status ?? "");
    if (!match) throw new Error(`Unexpected retrieve inspection: ${JSON.stringify(status)}`);
    return {
      home: Number(match[1]), npc: Number(match[2]), player: Number(match[3]),
      world: Number(match[4]), near: Number(match[5]), containers: Number(match[6]),
    };
  }
  if (scenario === "organize") {
    const match = /^storage-fixture:organize homeSurplus=(\d+),npcSurplus=(\d+),npcFood=(\d+),homeFood=(\d+),containers=(\d+)$/u.exec(status ?? "");
    if (!match) throw new Error(`Unexpected organize inspection: ${JSON.stringify(status)}`);
    return {
      homeSurplus: Number(match[1]), npcSurplus: Number(match[2]), npcFood: Number(match[3]),
      homeFood: Number(match[4]), containers: Number(match[5]),
    };
  }
  if (scenario === "craft-expand") {
    const match = /^storage-fixture:craft-expand\|hf=(\d+),hs=(\d+),nf=(\d+),nl=(\d+),np=(\d+),nt=(\d+),nc=(\d+),e=(\d+),t=(\d+),tp=(\d+),cp=(\d+),d=(\d+),u=(\d+)$/u.exec(status ?? "");
    if (!match) throw new Error(`Unexpected craft-expand inspection: ${JSON.stringify(status)}`);
    return {
      homeFiller: Number(match[1]), homeSurplus: Number(match[2]), npcFixture: Number(match[3]),
      npcLogs: Number(match[4]), npcPlanks: Number(match[5]), npcTables: Number(match[6]),
      npcChests: Number(match[7]), expanded: Number(match[8]), tables: Number(match[9]),
      tablePlacements: Number(match[10]), chestPlacements: Number(match[11]),
      direct: Number(match[12]), unknown: Number(match[13]),
    };
  }
  if (scenario !== "expand") throw new Error(`Unsupported storage inspection scenario: ${scenario}`);
  const match = /^storage-fixture:expand homeFiller=(\d+),homeSurplus=(\d+),npc=(\d+),expanded=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected expand inspection: ${JSON.stringify(status)}`);
  return { homeFiller: Number(match[1]), homeSurplus: Number(match[2]), npc: Number(match[3]), expanded: Number(match[4]) };
}

export function validateInspection(inspection, scenario, phase) {
  if (scenario === "retrieve") {
    const valid = phase === "initial"
      ? inspection.home === 8 && inspection.npc === 0 && inspection.player === 0
        && inspection.world === 0 && inspection.containers === 2
      : inspection.home === 0 && inspection.npc === 0
        && inspection.player + inspection.world === 8 && inspection.near === inspection.world;
    if (!valid) throw new Error(`Invalid ${phase} retrieve inspection: ${JSON.stringify(inspection)}`);
  } else if (scenario === "organize") {
    const valid = phase === "initial"
      ? inspection.homeSurplus === 0 && inspection.npcSurplus === 4
        && inspection.npcFood === 4 && inspection.homeFood === 0
      : inspection.homeSurplus === 4 && inspection.npcSurplus === 0
        && inspection.npcFood === 4 && inspection.homeFood === 0 && inspection.containers >= 1;
    if (!valid) throw new Error(`Invalid ${phase} organize inspection: ${JSON.stringify(inspection)}`);
  } else if (scenario === "expand") {
    const valid = phase === "initial"
      ? inspection.homeFiller === 1_728 && inspection.homeSurplus === 0
        && inspection.npc === 5 && inspection.expanded === 0
      : inspection.homeFiller === 1_728 && inspection.homeSurplus === 4
        && inspection.npc === 0 && inspection.expanded >= 1;
    if (!valid) throw new Error(`Invalid ${phase} expand inspection: ${JSON.stringify(inspection)}`);
  } else if (scenario === "craft-expand") {
    const valid = phase === "initial"
      ? inspection.homeFiller === 1_728 && inspection.homeSurplus === 0
        && inspection.npcFixture === 7 && inspection.npcLogs === 3
        && inspection.npcPlanks === 0 && inspection.npcTables === 0 && inspection.npcChests === 0
        && inspection.expanded === 0 && inspection.tables === 0
        && inspection.tablePlacements === 0 && inspection.chestPlacements === 0
        && inspection.direct === 0 && inspection.unknown === 0
      : inspection.homeFiller === 1_728 && inspection.homeSurplus === 4
        && inspection.npcFixture === 0 && inspection.npcLogs === 0
        && inspection.npcPlanks === 0 && inspection.npcTables === 0 && inspection.npcChests === 0
        && inspection.expanded === 1 && inspection.tables === 1
        && inspection.tablePlacements === 1 && inspection.chestPlacements === 1
        && inspection.direct === 0 && inspection.unknown === 0;
    if (!valid) throw new Error(`Invalid ${phase} craft-expand inspection: ${JSON.stringify(inspection)}`);
  } else {
    throw new Error(`Unsupported storage validation scenario: ${scenario}`);
  }
  return inspection;
}

export function parseCli(argv) {
  const scenarioArg = argv.find((value) => value.startsWith("--scenario="));
  const scenario = scenarioArg?.slice("--scenario=".length) ?? "all";
  if (scenario !== "all" && !SCENARIOS.has(scenario)) {
    throw new Error("--scenario must be retrieve, organize, expand, craft-expand, or all");
  }
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 600) {
    throw new Error("--wait-seconds must be between 30 and 600");
  }
  return {
    apply: argv.includes("--apply"),
    scenarios: scenario === "all" ? [...SCENARIOS] : [scenario],
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

async function cleanupAndWait(base, companionId) {
  const acknowledgement = await fixture(base, companionId, "cleanup");
  const status = String(acknowledgement.status ?? "");
  if (["storage-fixture:cleanup restored", "storage-fixture:cleanup none"].includes(status)) return status;
  throw new Error(`Minecraft did not confirm storage fixture cleanup: ${JSON.stringify(status)}`);
}

export function validateFinalCleanup(status, setupAcknowledged) {
  const expected = setupAcknowledged
    ? ["storage-fixture:cleanup restored"]
    : ["storage-fixture:cleanup restored", "storage-fixture:cleanup none"];
  if (!expected.includes(status)) {
    throw new Error(`Storage fixture cleanup was not confirmed: ${status}`);
  }
  return status;
}

async function runScenario(options, companion, scenario) {
  const message = scenario === "retrieve"
    ? "从家里箱子拿8个圆石给我"
    : "整理家里箱子和背包里的多余物品";
  const prior = await request(options.base, "/api/tasks");
  const priorIds = new Set(prior.tasks?.map((task) => task.id) ?? []);
  let task = null;
  let cleanupStatus = "not-run";
  let setupAcknowledged = false;
  let primaryError = null;
  try {
    await cleanupAndWait(options.base, companion.id);
    await fixture(options.base, companion.id, `setup-${scenario}`);
    setupAcknowledged = true;
    const initial = validateInspection(
      parseInspection((await fixture(options.base, companion.id, `inspect-${scenario}`)).status, scenario),
      scenario,
      "initial",
    );
    await sendMinecraftTChat(message, options.base);
    task = await waitForNewTask(options.base, companion.id, priorIds, scenario);
    const taskReport = await waitForTask(options.base, task, options.waitMs);
    const inspection = validateInspection(
      parseInspection((await fixture(options.base, companion.id, `inspect-${scenario}`)).status, scenario),
      scenario,
      "final",
    );
    return { scenario, message, task: taskReport, initial, inspection };
  } catch (error) {
    primaryError = error;
    throw error;
  } finally {
    try {
      if (task?.id) {
        const current = await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`);
        if (!TERMINAL.has(current.status)) {
          await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
            method: "POST",
            body: { reason: "live storage fixture cleanup" },
          });
        }
      }
      cleanupStatus = await cleanupAndWait(options.base, companion.id);
      validateFinalCleanup(cleanupStatus, setupAcknowledged);
    } catch (cleanupError) {
      if (!primaryError) throw cleanupError;
      const detail = cleanupError instanceof Error ? cleanupError.message : String(cleanupError);
      if (primaryError instanceof Error) {
        primaryError.message = `${primaryError.message}; cleanup also failed: ${detail}`;
        throw primaryError;
      }
      throw new AggregateError([primaryError, cleanupError], "Storage scenario and cleanup both failed");
    }
  }
}

export async function runLiveStorageSmoke(options) {
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      usedMinecraftTChat: true,
      scenarios: options.scenarios,
      companionId: companion.id,
    };
  }
  const results = [];
  for (const scenario of options.scenarios) results.push(await runScenario(options, companion, scenario));
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    usedMinecraftTChat: true,
    companionId: companion.id,
    results,
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveStorageSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live storage smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
