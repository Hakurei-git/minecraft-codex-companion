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
    throw new Error("live food delivery smoke only connects to a loopback HTTP service");
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

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const expectedStatusPrefix = {
    "setup-player": "",
    "inspect-player": "food-fixture:player=",
    "setup-home": "",
    "inspect-home": "food-fixture:home=",
    cleanup: "",
  }[mode] ?? "";
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "food-delivery", mode },
  });
  const deadline = Date.now() + 10_000;
  let current = before;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 150));
    current = await snapshot(base, companionId);
    if (Number(current.sequence) > Number(before.sequence)
      && (!expectedStatusPrefix || String(current.status ?? "").startsWith(expectedStatusPrefix))) return current;
  }
  throw new Error(`Minecraft did not acknowledge food delivery fixture ${mode}`);
}

async function sendMinecraftTChat(message) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  const script = path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1");
  await execFileAsync("powershell.exe", [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", script,
    "-MessageUtf8Base64", encoded,
    "-RespawnIfDead",
  ], {
    cwd: projectRoot,
    windowsHide: true,
    timeout: 20_000,
    maxBuffer: 100_000,
  });
}

async function waitForNewTask(base, companionId, priorIds, waitMs) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/tasks");
    const task = response.tasks?.find((candidate) => (
      candidate.companionId === companionId
      && !priorIds.has(candidate.id)
      && candidate.spec?.kind === "provision-food"
    ));
    if (task) return task;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("Minecraft T chat did not create a provision-food task");
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
  if (!TERMINAL.has(current.status)) throw new Error(`Food delivery task ${task.id} timed out`);
  if (current.status !== "succeeded") {
    throw new Error(`Food delivery task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return { id: current.id, status: current.status, message: current.message, spec: current.spec, messages };
}

export function parseInspection(status, destination) {
  if (destination === "home-storage") {
    const match = /^food-fixture:home=(\d+),npc=(\d+),containers=(\d+)$/u.exec(status ?? "");
    if (!match) throw new Error(`Unexpected home food inspection status: ${JSON.stringify(status)}`);
    return { home: Number(match[1]), npc: Number(match[2]), containers: Number(match[3]) };
  }
  const match = /^food-fixture:player=(\d+),npc=(\d+),world=(\d+),near=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected player food inspection status: ${JSON.stringify(status)}`);
  return { player: Number(match[1]), npc: Number(match[2]), world: Number(match[3]), near: Number(match[4]) };
}

export function validateInitialInspection(inspection, destination) {
  if (destination === "home-storage") {
    if (inspection.home !== 0 || inspection.npc !== 8 || inspection.containers !== 0) {
      throw new Error(`Home food fixture initial state is invalid: ${JSON.stringify(inspection)}`);
    }
    return inspection;
  }
  if (inspection.player !== 0 || inspection.npc !== 8 || inspection.world !== 0 || inspection.near !== 0) {
    throw new Error(`Player food fixture initial state is invalid: ${JSON.stringify(inspection)}`);
  }
  return inspection;
}

async function cleanupAndWait(base, companionId) {
  await fixture(base, companionId, "cleanup");
  const deadline = Date.now() + 10_000;
  let current = await snapshot(base, companionId);
  while (Date.now() < deadline) {
    const status = String(current.status ?? "");
    if (["food-fixture:cleanup restored", "food-fixture:cleanup none", "跟随待命"].includes(status)) {
      return status;
    }
    await new Promise((resolve) => setTimeout(resolve, 150));
    current = await snapshot(base, companionId);
  }
  throw new Error(`Minecraft did not confirm food fixture cleanup: ${JSON.stringify(current.status)}`);
}

async function inspectUntilDelivered(base, companionId, destination) {
  const deadline = Date.now() + 30_000;
  let inspection = null;
  while (Date.now() < deadline) {
    const mode = destination === "home-storage" ? "inspect-home" : "inspect-player";
    inspection = parseInspection((await fixture(base, companionId, mode)).status, destination);
    if (destination === "home-storage") {
      if (inspection.npc === 0 && inspection.home === 8 && inspection.containers >= 1) return inspection;
    } else if (inspection.npc === 0
      && inspection.player + inspection.world === 8
      && inspection.near === inspection.world) return inspection;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Physical food pickup did not converge: ${JSON.stringify(inspection)}`);
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 120;
  if (!Number.isFinite(seconds) || seconds < 15 || seconds > 300) {
    throw new Error("--wait-seconds must be between 15 and 300");
  }
  const destinationArg = argv.find((value) => value.startsWith("--destination="));
  const destination = destinationArg?.slice("--destination=".length) ?? "player";
  if (!["player", "home-storage"].includes(destination)) {
    throw new Error("--destination must be player or home-storage");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    destination,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveFoodDeliverySmoke(options) {
  const destination = options.destination ?? "player";
  if (!["player", "home-storage"].includes(destination)) throw new Error("Unsupported food delivery destination");
  const message = destination === "home-storage" ? "找些食物放到家里箱子" : "给我找些食物";
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      usedMinecraftTChat: true,
      message,
      destination,
      companionId: companion.id,
    };
  }

  const prior = await request(options.base, "/api/tasks");
  const priorIds = new Set(prior.tasks?.map((task) => task.id) ?? []);
  let task = null;
  let taskReport = null;
  let initialInspection = null;
  let inspection = null;
  let cleanupStatus = "not-run";
  try {
    await cleanupAndWait(options.base, companion.id);
    await fixture(options.base, companion.id, destination === "home-storage" ? "setup-home" : "setup-player");
    const initialMode = destination === "home-storage" ? "inspect-home" : "inspect-player";
    initialInspection = validateInitialInspection(
      parseInspection((await fixture(options.base, companion.id, initialMode)).status, destination),
      destination,
    );
    await sendMinecraftTChat(message);
    task = await waitForNewTask(options.base, companion.id, priorIds, 15_000);
    if (task.spec.kind !== "provision-food"
      || task.spec.count !== 8
      || task.spec.source !== "auto"
      || task.spec.destination !== destination) {
      throw new Error(`T chat created the wrong food task: ${JSON.stringify(task.spec)}`);
    }
    taskReport = await waitForTask(options.base, task, options.waitMs);
    inspection = await inspectUntilDelivered(options.base, companion.id, destination);
  } finally {
    if (task?.id && !TERMINAL.has((await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}`)).status)) {
      await request(options.base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
        method: "POST",
        body: { reason: "live food delivery fixture cleanup" },
      });
    }
    try {
      cleanupStatus = await cleanupAndWait(options.base, companion.id);
    } catch (error) {
      cleanupStatus = `failed:${error instanceof Error ? error.message : String(error)}`;
    }
  }

  if (!["food-fixture:cleanup restored", "跟随待命"].includes(cleanupStatus)) {
    throw new Error(`Food delivery fixture cleanup was not confirmed: ${cleanupStatus}`);
  }
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    usedMinecraftTChat: true,
    destination,
    message,
    companionId: companion.id,
    task: taskReport,
    initialInspection,
    inspection,
    cleanupStatus,
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveFoodDeliverySmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live food delivery smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
