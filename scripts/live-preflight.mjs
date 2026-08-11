import { pathToFileURL } from "node:url";

export const REQUIRED_FORGE_CAPABILITIES = [
  "chat",
  "observe",
  "move",
  "follow",
  "combat",
  "gather",
  "craft",
  "smelt",
  "farm",
  "storage",
  "fish",
  "sleep",
  "build",
  "commands",
  "dragon-care",
  "multi-bot",
];
const SLOT_TYPES = new Set(["backpack", "main_hand", "off_hand", "head", "chest", "legs", "feet"]);
const STANCES = new Set(["follow", "stay", "guard", "work"]);
const SCHEDULER_STATES = new Set(["idle", "running", "downed"]);

function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function isVector(value) {
  return value !== null
    && typeof value === "object"
    && isFiniteNumber(value.x)
    && isFiniteNumber(value.y)
    && isFiniteNumber(value.z);
}

export function validateLiveCompanion(companion) {
  const issues = [];
  if (!companion || typeof companion !== "object") return ["companion record is missing"];
  if (companion.connected !== true) issues.push("companion is not connected");
  if (companion.embodiment !== "in-world-npc") issues.push("companion is not the Forge in-world NPC");

  const capabilities = new Set(Array.isArray(companion.capabilities) ? companion.capabilities : []);
  for (const capability of REQUIRED_FORGE_CAPABILITIES) {
    if (!capabilities.has(capability)) issues.push(`missing capability: ${capability}`);
  }

  const snapshot = companion.snapshot;
  if (!snapshot || typeof snapshot !== "object") return [...issues, "world snapshot is missing"];
  if (!isFiniteNumber(snapshot.health) || !isFiniteNumber(snapshot.maxHealth) || snapshot.maxHealth <= 0) {
    issues.push("health fields are invalid");
  }
  if (!isFiniteNumber(snapshot.food) || !isFiniteNumber(snapshot.maxFood) || snapshot.maxFood <= 0) {
    issues.push("food fields are invalid");
  }
  if (!isFiniteNumber(snapshot.saturation) || !isFiniteNumber(snapshot.exhaustion)) {
    issues.push("hunger detail fields are missing");
  }
  if (!isFiniteNumber(snapshot.air) || !isFiniteNumber(snapshot.maxAir)) issues.push("air fields are missing");
  if (!isFiniteNumber(snapshot.armor) || !isFiniteNumber(snapshot.absorption)) issues.push("defense fields are missing");
  if (!isVector(snapshot.position)) issues.push("NPC position is invalid");
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(snapshot.npcEntityUuid ?? "")) {
    issues.push("NPC entity UUID is missing");
  }
  if (!new Set(["survival", "creative"]).has(snapshot.materialMode)) issues.push("material mode is missing");
  if (typeof snapshot.naturalRegenerationEnabled !== "boolean"
      || typeof snapshot.canNaturalRegenerate !== "boolean"
      || typeof snapshot.automaticEating !== "boolean") {
    issues.push("eating or regeneration state is missing");
  }
  if (!STANCES.has(snapshot.stance)) issues.push("NPC stance is missing");
  if (!SCHEDULER_STATES.has(snapshot.taskSchedulerLifecycle)) issues.push("task scheduler state is missing");
  if (!Array.isArray(snapshot.taskQueue)) issues.push("task queue is missing");
  if (!Array.isArray(snapshot.inventory)) {
    issues.push("inventory is missing");
  } else {
    for (const item of snapshot.inventory) {
      if (typeof item?.id !== "string" || typeof item?.displayName !== "string"
          || !Number.isInteger(item?.count) || item.count < 1
          || !Number.isInteger(item?.slot) || !SLOT_TYPES.has(item?.slotType)) {
        issues.push("inventory contains an incomplete item record");
        break;
      }
    }
  }
  if (!Array.isArray(snapshot.effects) || !Array.isArray(snapshot.nearbyEntities)) {
    issues.push("effects or nearby entity observations are missing");
  }
  if (typeof snapshot.status !== "string" || !snapshot.status.trim()) issues.push("NPC status text is missing");
  if (!snapshot.homeState || typeof snapshot.homeState !== "object"
      || typeof snapshot.homeState.dimension !== "string"
      || !isVector(snapshot.homeState.position)
      || typeof snapshot.homeState.temporary !== "boolean") {
    issues.push("home/respawn state is missing");
  }
  return issues;
}

export function validateAntigravityBinding(status) {
  return status?.available === true
    && status?.connected === true
    && typeof status?.conversationId === "string"
    && status.conversationId.length > 0
    && typeof status?.conversationTitle === "string"
    && status.conversationTitle.trim().length > 0
    && status.conversationTitle.length <= 240
    && !/[\x00-\x1f\x7f]/u.test(status.conversationTitle);
}

function localBaseUrl(raw) {
  const url = new URL(raw);
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(host)) {
    throw new Error("Live preflight only connects to a loopback HTTP control service");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

async function requestJson(baseUrl, pathname) {
  const response = await fetch(new URL(pathname, baseUrl), { signal: AbortSignal.timeout(3_000) });
  if (!response.ok) throw new Error(`${pathname} returned HTTP ${response.status}`);
  return response.json();
}

export async function runLivePreflight({
  baseUrl: rawBaseUrl = process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
  waitMs = 180_000,
  requireAntigravity = true,
} = {}) {
  const baseUrl = localBaseUrl(rawBaseUrl);
  const deadline = Date.now() + Math.max(0, waitMs);
  let companion = null;
  let lastHealth = null;
  do {
    try {
      lastHealth = await requestJson(baseUrl, "/api/health");
      const response = await requestJson(baseUrl, "/api/companions");
      companion = response.companions?.find((candidate) => (
        candidate.connected === true && candidate.embodiment === "in-world-npc"
      )) ?? null;
      if (companion) break;
    } catch {
      // The launcher and Forge bridge may still be starting.
    }
    if (Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 500));
  } while (Date.now() < deadline);

  if (!lastHealth?.ok) throw new Error("Control service is not healthy");
  if (!companion) throw new Error("No connected Forge in-world NPC was found before the preflight timeout");
  const issues = validateLiveCompanion(companion);
  if (issues.length) throw new Error(`Forge NPC preflight failed: ${issues.join("; ")}`);

  let antigravity = { available: false, connected: false, conversationId: null };
  try {
    antigravity = await requestJson(baseUrl, "/api/antigravity/status");
  } catch {
    // The report below remains redacted and states only readiness booleans.
  }
  const antigravityReady = validateAntigravityBinding(antigravity);
  if (requireAntigravity && !antigravityReady) {
    throw new Error("Antigravity is not connected to the required bound conversation");
  }

  return {
    ok: true,
    localOnly: true,
    destructiveActions: false,
    companion: {
      backend: companion.backend,
      embodiment: companion.embodiment,
      capabilities: REQUIRED_FORGE_CAPABILITIES.length,
      inventoryRecords: companion.snapshot.inventory.length,
      queuedTasks: companion.snapshot.taskQueue.length,
      homeResolved: true,
    },
    antigravity: {
      available: antigravity.available === true,
      connected: antigravity.connected === true,
      conversationBound: antigravityReady,
      exactConversationBound: antigravityReady,
    },
  };
}

function parseCliArguments(arguments_) {
  const wait = arguments_.find((value) => value.startsWith("--wait-seconds="));
  const seconds = wait ? Number(wait.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 0 || seconds > 600) throw new Error("--wait-seconds must be between 0 and 600");
  return {
    waitMs: seconds * 1_000,
    requireAntigravity: !arguments_.includes("--skip-antigravity"),
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  try {
    const report = await runLivePreflight(parseCliArguments(process.argv.slice(2)));
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    process.stderr.write(`Live preflight failed: ${message}\n`);
    process.exitCode = 1;
  }
}
