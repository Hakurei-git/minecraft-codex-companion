import { pathToFileURL } from "node:url";

const ACTIONS = new Set([
  "observe", "feed", "heal", "tame", "follow", "stay", "mount", "dismount",
  "care-for-egg", "recall", "assist-combat", "land", "fly-to",
]);
const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const DRAGON_FALL_JITTER_MILLI = 1_500;
const REPEATABLE_FIXTURES = new Set([
  "spawn-book", "spawn-saints", "inspect-book", "inspect-saints",
  "dismount-all", "cleanup-combat", "cleanup",
]);
const INSPECTION_FLAGS = Object.freeze([
  "alive", "owned", "npcMounted", "playerMounted", "coRiding", "firstPlayer",
  "flying", "onGround", "saddled", "seatLocked", "rideReady", "autopilot",
  "rootVehicleDragon", "beginCalled", "beginAccepted", "endCalled", "invalidated",
  "vehiclePacketSeen",
]);
const INSPECTION_MODS = Object.freeze({ 0: "bookofdragons", 1: "saintsdragons" });
const PROFILES = Object.freeze({
  bookofdragons: Object.freeze({
    modId: "bookofdragons",
    key: "book",
    spawn: "spawn-book",
    inspect: "inspect-book",
    wander: "set-book-wander",
    coRide: "co-ride-book",
    raise: "raise-book",
    far: "move-book-far",
    obstacle: "stage-obstacle-book",
    followCommand: 2,
    wanderCommand: 0,
  }),
  saintsdragons: Object.freeze({
    modId: "saintsdragons",
    key: "saints",
    spawn: "spawn-saints",
    inspect: "inspect-saints",
    wander: "set-saints-wander",
    coRide: "co-ride-saints",
    raise: "raise-saints",
    far: "move-saints-far",
    obstacle: "stage-obstacle-saints",
    followCommand: 0,
    wanderCommand: 2,
  }),
});

function option(argv, name, fallback = "") {
  const prefix = `--${name}=`;
  return argv.find((entry) => entry.startsWith(prefix))?.slice(prefix.length) ?? fallback;
}

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live dragon smoke only connects to a loopback HTTP service");
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

export function parseOffset(raw) {
  if (!raw) return null;
  const values = raw.split(",").map(Number);
  if (values.length !== 3 || values.some((value) => !Number.isFinite(value))) {
    throw new Error("--target-offset must contain three comma-separated finite numbers");
  }
  return { x: values[0], y: values[1], z: values[2] };
}

export function parseMods(raw = "bookofdragons,saintsdragons") {
  const values = [...new Set(raw.split(",").map((value) => value.trim()).filter(Boolean))];
  if (values.length === 0 || values.some((value) => !Object.hasOwn(PROFILES, value))) {
    throw new Error("--mods must contain bookofdragons and/or saintsdragons");
  }
  return values.map((value) => PROFILES[value]);
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
  if (!acknowledgement
    || acknowledgement.sequence <= beforeSequence
    || acknowledgement.suite !== "dragon"
    || acknowledgement.mode !== mode) return null;
  return acknowledgement.status;
}

export function fixturePrefix(mode) {
  if (mode === "spawn-book") return "dragon-fixture:spawn mod=bookofdragons,id=";
  if (mode === "spawn-saints") return "dragon-fixture:spawn mod=saintsdragons,id=";
  if (mode === "inspect-book") return "dragon:i|0|";
  if (mode === "inspect-saints") return "dragon:i|1|";
  if (mode === "set-book-wander") return "dragon-fixture:wander mod=bookofdragons,command=0";
  if (mode === "set-saints-wander") return "dragon-fixture:wander mod=saintsdragons,command=2";
  if (mode === "co-ride-book") return "dragon-fixture:co-ride mod=bookofdragons,result=";
  if (mode === "co-ride-saints") return "dragon-fixture:co-ride mod=saintsdragons,result=";
  if (mode === "move-book-far") return "dragon-fixture:far mod=bookofdragons,distanceMilli=";
  if (mode === "move-saints-far") return "dragon-fixture:far mod=saintsdragons,distanceMilli=";
  if (mode === "raise-book") return "dragon-fixture:raise mod=bookofdragons,yMilli=";
  if (mode === "raise-saints") return "dragon-fixture:raise mod=saintsdragons,yMilli=";
  if (mode === "stage-obstacle-book") return "dragon-fixture:obstacle mod=bookofdragons,target=";
  if (mode === "stage-obstacle-saints") return "dragon-fixture:obstacle mod=saintsdragons,target=";
  if (mode === "clear-obstacle") return "dragon-fixture:obstacle cleared=";
  if (mode === "spawn-combat-target") return "dragon-fixture:combat spawned=1,id=";
  if (mode === "arm-combat-target") return "dragon-fixture:combat armed=1,id=";
  if (mode === "cleanup-combat") return "dragon-fixture:combat cleanup=";
  if (mode === "dismount-all") return "dragon-fixture:dismount player=0,npc=0";
  if (mode === "cleanup") return "dragon-fixture:cleanup restored=1,entities=";
  throw new Error(`Unsupported dragon fixture mode ${mode}`);
}

export function fixtureIsRepeatable(mode) {
  return REPEATABLE_FIXTURES.has(mode);
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeAck = Number(before.liveFixtureAck?.sequence ?? 0);
  const expected = fixturePrefix(mode);
  const pathname = `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`;
  const body = { suite: "dragon", mode };
  await request(base, pathname, { method: "POST", body });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 300;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const status = String(current.status ?? "");
    const acknowledged = fixtureAcknowledgement(current, beforeAck, mode);
    if (acknowledged !== null) {
      if (acknowledged.startsWith("live-fixture:denied ")
        || acknowledged.startsWith("live-fixture:failed ")) {
        throw new Error(`Minecraft rejected dragon fixture ${mode}: ${acknowledged}`);
      }
      if (!acknowledged.startsWith(expected)) {
        throw new Error(`Minecraft returned an unexpected ${mode} acknowledgement: ${acknowledged}`);
      }
      return { ...current, status: acknowledged };
    }
    if (Number(current.sequence) > Number(before.sequence) && status.startsWith(expected)) return current;
    if (fixtureIsRepeatable(mode) && Date.now() >= nextRetry) {
      await request(base, pathname, { method: "POST", body });
      nextRetry = Date.now() + 300;
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error(`Minecraft did not acknowledge dragon fixture ${mode}`);
}

export function parseSpawnStatus(status, expectedMod) {
  const match = /^dragon-fixture:spawn mod=(bookofdragons|saintsdragons),id=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/u.exec(status ?? "");
  if (!match || match[1] !== expectedMod) {
    throw new Error(`Unexpected ${expectedMod} dragon spawn status: ${JSON.stringify(status)}`);
  }
  return { modId: match[1], targetId: match[2] };
}

export function parseInspection(status) {
  if (typeof status !== "string" || status.length > 120) {
    throw new Error(`Unexpected dragon inspection status: ${JSON.stringify(status)}`);
  }
  const fields = status.split("|");
  if (fields.length !== 15 || fields[0] !== "dragon:i" || !Object.hasOwn(INSPECTION_MODS, fields[1])) {
    throw new Error(`Unexpected dragon inspection status: ${JSON.stringify(status)}`);
  }
  const payload = fields.slice(0, -1).join("|");
  if (!/^[0-9a-z]{3}$/u.test(fields[14]) || fields[14] !== inspectionChecksum(payload)) {
    throw new Error(`Dragon inspection checksum failed: ${JSON.stringify(status)}`);
  }
  if (!/^[0-9a-f]{32}$/u.test(fields[2])) {
    throw new Error(`Unexpected dragon inspection UUID: ${JSON.stringify(fields[2])}`);
  }
  const flags = base36Integer(fields[3], "flags");
  if (flags < 0 || flags >= 2 ** INSPECTION_FLAGS.length) {
    throw new Error(`Dragon inspection flags are out of range: ${fields[3]}`);
  }
  const numericNames = [
    "command", "npcHealth", "npcFall", "dragonFall", "ownerDistance",
    "targetCount", "obstacleBlocks", "x", "y", "z",
  ];
  const numeric = Object.fromEntries(numericNames.map((name, index) => [
    name,
    base36Integer(fields[index + 4], name),
  ]));
  for (const name of [
    "npcHealth", "npcFall", "dragonFall", "ownerDistance", "targetCount", "obstacleBlocks",
  ]) {
    if (numeric[name] < 0) throw new Error(`Dragon inspection ${name} cannot be negative`);
  }
  const target = fields[2];
  return {
    modId: INSPECTION_MODS[fields[1]],
    targetId: `${target.slice(0, 8)}-${target.slice(8, 12)}-${target.slice(12, 16)}-${target.slice(16, 20)}-${target.slice(20)}`,
    ...Object.fromEntries(INSPECTION_FLAGS.map((name, index) => [name, (flags >> index) & 1])),
    ...numeric,
  };
}

function base36Integer(raw, label) {
  if (!/^-?[0-9a-z]+$/u.test(raw)) {
    throw new Error(`Dragon inspection ${label} is not base36: ${JSON.stringify(raw)}`);
  }
  const value = Number.parseInt(raw, 36);
  if (!Number.isSafeInteger(value) || value.toString(36) !== raw) {
    throw new Error(`Dragon inspection ${label} is not canonical: ${JSON.stringify(raw)}`);
  }
  return value;
}

function inspectionChecksum(payload) {
  let value = 0;
  for (let index = 0; index < payload.length; index += 1) {
    value = (value * 31 + payload.charCodeAt(index)) % 46_656;
  }
  return value.toString(36).padStart(3, "0");
}

export function parseObstacleStatus(status, expectedMod) {
  const match = /^dragon-fixture:obstacle mod=(bookofdragons|saintsdragons),target=(-?\d+(?:\.\d+)?):(-?\d+(?:\.\d+)?):(-?\d+(?:\.\d+)?),wallMaxX=(-?\d+),blocks=(\d+)$/u.exec(status ?? "");
  if (!match || match[1] !== expectedMod) {
    throw new Error(`Unexpected ${expectedMod} obstacle status: ${JSON.stringify(status)}`);
  }
  return {
    modId: match[1],
    target: { x: Number(match[2]), y: Number(match[3]), z: Number(match[4]) },
    wallMaxX: Number(match[5]),
    blocks: Number(match[6]),
  };
}

export function parseClearObstacleStatus(status) {
  const match = /^dragon-fixture:obstacle cleared=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected obstacle cleanup status: ${JSON.stringify(status)}`);
  return Number(match[1]);
}

export function validateCleanupStatus(status) {
  const match = /^dragon-fixture:cleanup restored=1,entities=(\d+),blocks=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Dragon fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  return { entities: Number(match[1]), blocks: Number(match[2]) };
}

function requireState(state, expected, label) {
  for (const [key, value] of Object.entries(expected)) {
    if (state[key] !== value) {
      throw new Error(`${label} expected ${key}=${value}, received ${JSON.stringify(state)}`);
    }
  }
  return state;
}

export function validateBaseline(state, profile) {
  requireState(state, {
    alive: 1,
    owned: 1,
    npcMounted: 0,
    playerMounted: 0,
    coRiding: 0,
    saddled: 1,
    seatLocked: 0,
    rideReady: 1,
  }, `${profile.modId} baseline`);
  if (state.modId !== profile.modId || state.npcHealth <= 0) {
    throw new Error(`${profile.modId} baseline identity/health failed: ${JSON.stringify(state)}`);
  }
  return state;
}

export function validateSharedRide(state, profile) {
  requireState(state, {
    npcMounted: 1,
    playerMounted: 1,
    coRiding: 1,
    firstPlayer: 1,
    rootVehicleDragon: 1,
    npcFall: 0,
  }, `${profile.modId} shared ride`);
  if (state.npcHealth <= 0 || state.dragonFall > DRAGON_FALL_JITTER_MILLI) {
    throw new Error(`${profile.modId} shared ride fall safety failed: ${JSON.stringify(state)}`);
  }
  return state;
}

function validateCompletedAutopilot(state, profile, phase) {
  return requireState(state, {
    autopilot: 0,
    beginCalled: 1,
    beginAccepted: 1,
    endCalled: 1,
    invalidated: 0,
    vehiclePacketSeen: 1,
  }, `${profile.modId} ${phase} autopilot lifecycle`);
}

export function validateObstacleArrival(state, obstacle, profile) {
  validateSharedRide(state, profile);
  validateCompletedAutopilot(state, profile, "obstacle flight");
  requireState(state, { flying: 1, onGround: 0 }, `${profile.modId} obstacle flight state`);
  if (state.obstacleBlocks !== obstacle.blocks || state.x / 1_000 <= obstacle.wallMaxX) {
    throw new Error(`${profile.modId} did not clear the intact terrain fixture: ${JSON.stringify({ state, obstacle })}`);
  }
  const distance = Math.hypot(
    state.x / 1_000 - obstacle.target.x,
    state.y / 1_000 - obstacle.target.y,
    state.z / 1_000 - obstacle.target.z,
  );
  if (distance > 5.05) {
    throw new Error(`${profile.modId} obstacle flight did not finish cleanly: ${JSON.stringify({ distance, obstacle, state })}`);
  }
  return { ...state, targetDistance: distance };
}

export function validateLanded(state, profile) {
  validateSharedRide(state, profile);
  validateCompletedAutopilot(state, profile, "landing");
  requireState(state, { flying: 0, onGround: 1 }, `${profile.modId} landing`);
  return state;
}

export function validateUninterruptedCombat(task, profile) {
  const paused = task.events?.filter((event) => event.status === "paused") ?? [];
  if (paused.length > 0) {
    throw new Error(`${profile.modId} combat paused unexpectedly: ${JSON.stringify(paused)}`);
  }
  return task;
}

async function cancelTask(base, taskId) {
  try {
    await request(base, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: "POST",
      body: { reason: "live dragon fixture cleanup" },
    });
  } catch {
    // Cleanup still runs even if the task became terminal between poll and cancel.
  }
}

async function runTask(base, companionId, spec, waitMs) {
  const assigned = await request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { owner: "live-acceptance", spec },
  });
  let task = assigned;
  const deadline = Date.now() + waitMs;
  const events = [];
  let signature = "";
  while (Date.now() < deadline && !TERMINAL.has(task.status)) {
    await new Promise((resolve) => setTimeout(resolve, 100));
    task = await request(base, `/api/tasks/${encodeURIComponent(assigned.id)}`);
    const next = `${task.status}|${task.progress}|${task.message}`;
    if (next !== signature) {
      events.push({ status: task.status, progress: task.progress, message: task.message });
      signature = next;
    }
  }
  if (!TERMINAL.has(task.status)) {
    await cancelTask(base, assigned.id);
    throw new Error(`Dragon action ${spec.action} timed out after ${waitMs}ms`);
  }
  if (task.status !== "succeeded") {
    throw new Error(`Dragon action ${spec.action} failed: ${JSON.stringify({
      status: task.status,
      message: task.message,
      code: task.error?.code ?? null,
      events,
    })}`);
  }
  return {
    taskId: task.id,
    action: spec.action,
    status: task.status,
    progress: task.progress,
    message: task.message,
    events,
  };
}

function dragonSpec(action, targetId, target) {
  const spec = {
    kind: "dragon",
    action,
    targetId,
    requestedBy: "live-dragon-acceptance",
    note: `Reversible ${action} acceptance against a fixed tagged dragon fixture`,
  };
  if (target) spec.target = target;
  return spec;
}

async function inspected(base, companionId, profile) {
  return parseInspection(String((await fixture(base, companionId, profile.inspect)).status ?? ""));
}

async function runProfileTask(base, companionId, profile, phase, spec, waitMs) {
  try {
    return await runTask(base, companionId, spec, waitMs);
  } catch (error) {
    let state = null;
    try {
      state = await inspected(base, companionId, profile);
    } catch {
      // The original task failure is still authoritative when inspection is unavailable.
    }
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`${profile.modId} ${phase} failed: ${message}; state=${JSON.stringify(state)}`);
  }
}

async function sampleObstacleStability(base, companionId, profile, obstacle) {
  const distances = [];
  for (let sample = 0; sample < 10; sample += 1) {
    await new Promise((resolve) => setTimeout(resolve, 100));
    const state = validateObstacleArrival(
      await inspected(base, companionId, profile), obstacle, profile,
    );
    distances.push(state.targetDistance);
  }
  return {
    durationMs: 1_000,
    intervalMs: 100,
    distances,
    maximumDistance: Math.max(...distances),
  };
}

async function runProfile(base, companionId, profile, waitMs, skipPreflightCleanup = false) {
  const phases = [];
  let cleanup = null;
  let result = null;
  try {
    if (skipPreflightCleanup) {
      phases.push({ phase: "preflight-rollback", skipped: true });
    } else {
      const recovered = validateCleanupStatus(
        String((await fixture(base, companionId, "cleanup")).status ?? ""),
      );
      phases.push({ phase: "preflight-rollback", cleanup: recovered });
    }
    const spawn = parseSpawnStatus(
      String((await fixture(base, companionId, profile.spawn)).status ?? ""),
      profile.modId,
    );
    const targetId = spawn.targetId;
    phases.push({ phase: "spawn", ...spawn });

    const baseline = validateBaseline(await inspected(base, companionId, profile), profile);
    phases.push({ phase: "baseline", state: baseline });
    phases.push({ phase: "observe", task: await runTask(
      base, companionId, dragonSpec("observe", targetId), waitMs,
    ) });

    await fixture(base, companionId, profile.wander);
    phases.push({ phase: "follow", task: await runTask(
      base, companionId, dragonSpec("follow", targetId), waitMs,
    ) });
    requireState(await inspected(base, companionId, profile), {
      command: profile.followCommand,
      npcMounted: 0,
    }, `${profile.modId} follow`);

    phases.push({ phase: "stay", task: await runTask(
      base, companionId, dragonSpec("stay", targetId), waitMs,
    ) });
    requireState(await inspected(base, companionId, profile), {
      command: 1,
      npcMounted: 0,
    }, `${profile.modId} stay`);

    phases.push({ phase: "mount", task: await runProfileTask(
      base, companionId, profile, "mount",
      dragonSpec("mount", targetId), waitMs,
    ) });
    requireState(await inspected(base, companionId, profile), {
      npcMounted: 1,
      playerMounted: 0,
      npcFall: 0,
    }, `${profile.modId} mount`);

    phases.push({ phase: "dismount", task: await runTask(
      base, companionId, dragonSpec("dismount", targetId), waitMs,
    ) });
    requireState(await inspected(base, companionId, profile), {
      npcMounted: 0,
      playerMounted: 0,
    }, `${profile.modId} dismount`);

    await fixture(base, companionId, profile.coRide);
    phases.push({ phase: "shared-ride", state: validateSharedRide(
      await inspected(base, companionId, profile), profile,
    ) });

    const obstacle = parseObstacleStatus(
      String((await fixture(base, companionId, profile.obstacle)).status ?? ""),
      profile.modId,
    );
    const terrainTask = await runProfileTask(
      base, companionId, profile, "terrain-flight",
      dragonSpec("fly-to", targetId, obstacle.target), waitMs,
    );
    const terrainState = validateObstacleArrival(
      await inspected(base, companionId, profile), obstacle, profile,
    );
    const stability = await sampleObstacleStability(base, companionId, profile, obstacle);
    phases.push({ phase: "terrain-flight", task: terrainTask, state: terrainState, stability });
    const cleared = parseClearObstacleStatus(
      String((await fixture(base, companionId, "clear-obstacle")).status ?? ""),
    );
    if (cleared !== obstacle.blocks) {
      throw new Error(`${profile.modId} obstacle rollback cleared ${cleared}/${obstacle.blocks} blocks`);
    }

    await fixture(base, companionId, profile.raise);
    const raised = validateSharedRide(await inspected(base, companionId, profile), profile);
    phases.push({ phase: "airborne-fall-safety", state: raised });
    phases.push({ phase: "land", task: await runProfileTask(
      base, companionId, profile, "land",
      dragonSpec("land", targetId), waitMs,
    ), state: validateLanded(await inspected(base, companionId, profile), profile) });

    await new Promise((resolve) => setTimeout(resolve, 750));
    await fixture(base, companionId, "dismount-all");
    await new Promise((resolve) => setTimeout(resolve, 750));
    requireState(await inspected(base, companionId, profile), {
      npcMounted: 0,
      playerMounted: 0,
      npcFall: 0,
    }, `${profile.modId} fixture dismount`);
    await fixture(base, companionId, profile.far);
    phases.push({ phase: "recall", task: await runProfileTask(
      base, companionId, profile, "recall",
      dragonSpec("recall", targetId), waitMs,
    ) });
    const recalled = await inspected(base, companionId, profile);
    if (recalled.ownerDistance > 24_000) {
      throw new Error(`${profile.modId} recall ended too far from owner: ${JSON.stringify(recalled)}`);
    }

    await fixture(base, companionId, profile.coRide);
    await fixture(base, companionId, "spawn-combat-target");
    await fixture(base, companionId, "arm-combat-target");
    const combatTask = validateUninterruptedCombat(await runProfileTask(
      base, companionId, profile, "assist-combat",
      dragonSpec("assist-combat", targetId), waitMs,
    ), profile);
    phases.push({ phase: "assist-combat", task: combatTask });
    const combat = validateSharedRide(await inspected(base, companionId, profile), profile);
    validateCompletedAutopilot(combat, profile, "assist combat");
    if (combat.targetCount !== 0) {
      throw new Error(`${profile.modId} combat fixture did not finish cleanly: ${JSON.stringify(combat)}`);
    }
    phases.push({ phase: "assist-combat-state", state: combat });
    result = { modId: profile.modId, targetId, phases };
  } finally {
    cleanup = validateCleanupStatus(
      String((await fixture(base, companionId, "cleanup")).status ?? ""),
    );
  }
  return { ...result, cleanup };
}

async function runSuite(base, companion, profiles, waitMs, apply, skipPreflightCleanup = false) {
  const plan = profiles.map((profile) => ({
    modId: profile.modId,
    fixtures: [
      profile.spawn, profile.inspect, profile.wander, profile.coRide, profile.obstacle,
      "clear-obstacle", profile.raise, "dismount-all", profile.far,
      "spawn-combat-target", "arm-combat-target", "cleanup",
    ],
    actions: [
      "observe", "follow", "stay", "mount", "dismount", "fly-to", "land", "recall", "assist-combat",
    ],
    invariants: ["owner", "shared-seat", "fall-safety", "terrain-clearance", "rollback"],
    separateCareAcceptance: ["feed", "heal", "tame", "care-for-egg"],
  }));
  if (!apply) return { ok: true, dryRun: true, localOnly: true, companionId: companion.id, plan };
  const results = [];
  for (const profile of profiles) {
    results.push(await runProfile(
      base, companion.id, profile, waitMs, skipPreflightCleanup,
    ));
  }
  return { ok: true, dryRun: false, localOnly: true, companionId: companion.id, results };
}

async function runSingleAction(base, companion, argv, waitMs, apply) {
  const action = option(argv, "action", "observe");
  if (!ACTIONS.has(action)) throw new Error(`Unsupported dragon action: ${action}`);
  const targetId = option(argv, "target-id").trim();
  const offset = parseOffset(option(argv, "target-offset"));
  let target = null;
  if (offset) {
    const position = companion.snapshot?.position;
    if (![position?.x, position?.y, position?.z].every(Number.isFinite)) {
      throw new Error("NPC position unavailable");
    }
    target = { x: position.x + offset.x, y: position.y + offset.y, z: position.z + offset.z };
  }
  const spec = dragonSpec(action, targetId || undefined, target);
  if (!apply) return { ok: true, dryRun: true, localOnly: true, companionId: companion.id, spec };
  const task = await runTask(base, companion.id, spec, waitMs);
  return { ok: true, dryRun: false, localOnly: true, companionId: companion.id, ...task };
}

export async function run(argv = process.argv.slice(2)) {
  const waitSeconds = Number(option(argv, "wait-seconds", argv.includes("--suite") ? "360" : "120"));
  if (!Number.isFinite(waitSeconds) || waitSeconds < 1 || waitSeconds > 600) {
    throw new Error("--wait-seconds must be between 1 and 600");
  }
  const base = loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765");
  const companion = await connectedCompanion(base);
  const apply = argv.includes("--apply");
  return argv.includes("--suite")
    ? runSuite(
      base,
      companion,
      parseMods(option(argv, "mods", "bookofdragons,saintsdragons")),
      waitSeconds * 1_000,
      apply,
      argv.includes("--skip-preflight-cleanup"),
    )
    : runSingleAction(base, companion, argv, waitSeconds * 1_000, apply);
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  run().then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live dragon smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
