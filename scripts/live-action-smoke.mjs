import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const TASKS = new Set([
  "stone-pickaxe",
  "smelt-iron",
  "walk-log",
  "log-delivery",
  "expedition-log",
  "build-material-chain",
]);

export function localBaseUrl(raw) {
  const url = new URL(raw);
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(host)) {
    throw new Error("live-action-smoke only connects to a loopback HTTP control service");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

async function request(baseUrl, pathname, { method = "GET", body } = {}) {
  const response = await fetch(new URL(pathname, baseUrl), {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    throw new Error(`${method} ${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 2_000)}`);
  }
  return response.status === 204 ? null : response.json();
}

function ownerNameFor(companion) {
  if (typeof companion.ownerName === "string" && companion.ownerName.trim()) return companion.ownerName.trim();
  const owner = companion.snapshot?.nearbyEntities?.find((entity) => entity.disposition === "owner");
  if (typeof owner?.name === "string" && owner.name.trim()) return owner.name.trim();
  return "owner";
}

export function taskSpecFor(kind, companion) {
  const player = ownerNameFor(companion);
  const requestedBy = "live-action-smoke";
  if (kind === "stone-pickaxe") {
    return {
      kind: "craft",
      itemId: "minecraft:stone_pickaxe",
      count: 1,
      deliverTo: player,
      requestedBy,
      note: "实机验证：制作石镐动作链并交付主人",
    };
  }
  if (kind === "smelt-iron") {
    return {
      kind: "smelt",
      itemId: "minecraft:raw_iron",
      count: 3,
      requestedBy,
      note: "实机验证：从自然资源补齐工具、原矿、熔炉与燃料后烧炼 3 个铁锭",
    };
  }
  if (kind === "walk-log") {
    return {
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 1,
      movement: "walk",
      requestedBy,
      note: "实机验证：纯走路采集一块原木，不触发远征/传送",
    };
  }
  if (kind === "log-delivery") {
    return {
      kind: "macro",
      skillId: "life.gather-and-deliver",
      arguments: { itemId: "#minecraft:logs", count: 1, player },
      requestedBy,
      note: "实机验证：采集一块原木并交付主人",
    };
  }
  if (kind === "expedition-log") {
    return {
      kind: "macro",
      skillId: "life.expedition-and-deliver",
      arguments: { itemId: "#minecraft:logs", count: 1, player },
      requestedBy,
      note: "实机验证：允许远程搜索的远征采集完成后返回并交付主人",
    };
  }
  if (kind === "build-material-chain") {
    throw new Error("build-material-chain requires a freshly confirmed local preview");
  }
  throw new Error(`Unknown task ${kind}`);
}

export function buildMaterialChainDraft() {
  return {
    name: "实机材料链验证台",
    source: "demo",
    origin: { x: 0, y: 0, z: 0 },
    blocks: [
      { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:cobblestone", properties: {} },
      { position: { x: 1, y: 0, z: 0 }, blockId: "minecraft:oak_planks", properties: {} },
      { position: { x: 2, y: 0, z: 0 }, blockId: "minecraft:glass", properties: {} },
      { position: { x: 0, y: 1, z: 0 }, blockId: "minecraft:torch", properties: {} },
      { position: { x: 1, y: 1, z: 0 }, blockId: "minecraft:glass_pane", properties: {} },
    ],
  };
}

export function buildMaterialTaskSpec(planId, companion) {
  const anchor = companion.snapshot?.position;
  if (!anchor || ![anchor.x, anchor.y, anchor.z].every(Number.isFinite)) {
    throw new Error("Connected companion does not expose a finite in-world position");
  }
  return {
    kind: "build",
    planId,
    placement: "companion",
    offset: { x: 3, y: 0, z: 3 },
    placementAnchor: { x: anchor.x, y: anchor.y, z: anchor.z },
    requestedBy: "live-action-smoke",
    note: "实机验证：仓库、制作、熔炼和安全采集补料后恢复同一建筑任务",
  };
}

function parseCli(argv) {
  const taskArg = argv.find((value) => value.startsWith("--task="));
  const companionArg = argv.find((value) => value.startsWith("--companion="));
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const task = taskArg ? taskArg.slice("--task=".length) : "stone-pickaxe";
  if (!TASKS.has(task)) throw new Error(`--task must be one of ${[...TASKS].join(", ")}`);
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 1 || seconds > 900) throw new Error("--wait-seconds must be between 1 and 900");
  return {
    apply: argv.includes("--apply"),
    task,
    companionId: companionArg ? companionArg.slice("--companion=".length).trim() : "",
    waitMs: seconds * 1_000,
    baseUrl: localBaseUrl(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLiveActionSmoke(options) {
  const companions = await request(options.baseUrl, "/api/companions");
  const companion = options.companionId
    ? companions.companions?.find((candidate) => candidate.id === options.companionId)
    : companions.companions?.find((candidate) => candidate.connected === true && candidate.embodiment === "in-world-npc");
  if (!companion?.id) throw new Error("No connected Forge in-world NPC was found");
  if (companion.connected !== true) throw new Error(`Companion ${companion.id} is not connected`);

  const buildDraft = options.task === "build-material-chain" ? buildMaterialChainDraft() : null;
  const spec = buildDraft === null ? taskSpecFor(options.task, companion) : null;
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      companionId: companion.id,
      task: options.task,
      spec: spec ?? { kind: "build", requiresFreshConfirmedPreview: true, draft: buildDraft },
      hint: `Re-run with --apply to assign this task to ${companion.id}`,
    };
  }

  let appliedSpec = spec;
  if (buildDraft !== null) {
    const preview = await request(options.baseUrl, "/api/build-plans/preview", {
      method: "POST",
      body: buildDraft,
    });
    const confirmed = await request(
      options.baseUrl,
      `/api/build-plans/${encodeURIComponent(preview.id)}/confirm`,
      { method: "POST" },
    );
    if (confirmed.confirmed !== true) throw new Error(`Build preview ${preview.id} was not confirmed`);
    appliedSpec = buildMaterialTaskSpec(confirmed.id, companion);
  }

  const assigned = await request(options.baseUrl, `/api/companions/${encodeURIComponent(companion.id)}/tasks`, {
    method: "POST",
    body: { spec: appliedSpec, owner: "live-action-smoke" },
  });

  const deadline = Date.now() + options.waitMs;
  const messages = [];
  let record = assigned;
  while (Date.now() < deadline) {
    record = await request(options.baseUrl, `/api/tasks/${encodeURIComponent(assigned.id)}`);
    if (typeof record.message === "string" && messages[messages.length - 1] !== record.message) {
      messages.push(record.message);
      if (messages.length > 20) messages.shift();
    }
    if (TERMINAL.has(record.status)) break;
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  if (!TERMINAL.has(record.status)) throw new Error(`Task ${assigned.id} did not finish before timeout`);
  const report = {
    ok: record.status === "succeeded",
    dryRun: false,
    localOnly: true,
    companionId: companion.id,
    taskId: assigned.id,
    task: options.task,
    status: record.status,
    progress: record.progress,
    message: record.message,
    recentMessages: messages,
    error: record.error?.code ?? null,
  };
  if (record.status !== "succeeded") {
    throw new Error(JSON.stringify(report, null, 2));
  }
  return report;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  try {
    const report = await runLiveActionSmoke(parseCli(process.argv.slice(2)));
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`Live action smoke failed: ${message}\n`);
    process.exitCode = 1;
  }
}
