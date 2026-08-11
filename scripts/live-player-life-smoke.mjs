import { execFile } from "node:child_process";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const FIXTURE_ACK_TIMEOUT_MS = 30_000;
const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

export const PLAYER_LIFE_ACTOR_EVIDENCE = Object.freeze({
  actor: "ai-npc",
  playerGameplayAssistanceUsed: false,
});

export function explicitEatingCases() {
  return [
    {
      scenario: "rotten",
      setupMode: "setup-rotten",
      prompt: "把3个腐肉吃掉",
      itemId: "minecraft:rotten_flesh",
      count: 3,
      initialFood: 10,
      expectedEaten: 3,
      expectedRotten: 0,
      expectedMelon: 2,
    },
    {
      scenario: "melon",
      setupMode: "setup-melon",
      prompt: "把2片西瓜吃掉",
      itemId: "minecraft:melon_slice",
      count: 2,
      initialFood: 16,
      expectedEaten: 2,
      expectedRotten: 3,
      expectedMelon: 0,
    },
    {
      scenario: "full",
      setupMode: "setup-full",
      prompt: "把西瓜吃掉",
      itemId: "minecraft:melon_slice",
      count: 1,
      initialFood: 20,
      expectedEaten: 0,
      expectedRotten: 3,
      expectedMelon: 2,
    },
  ];
}

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live player life smoke only connects to a loopback HTTP service");
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

export function fixturePrefix(suite, mode) {
  if (suite === "player-state") {
    if (mode === "setup") return "state-fixture:setup";
    if (mode === "inspect") return "state-fixture:h=";
    if (mode === "cleanup") return "state-fixture:cleanup ";
  }
  if (suite === "eating-action") {
    if (mode === "setup-rotten") return "eat-fixture:setup-rotten";
    if (mode === "setup-melon") return "eat-fixture:setup-melon";
    if (mode === "setup-full") return "eat-fixture:setup-full";
    if (mode === "inspect") return "eat-fixture:c=";
    if (mode === "cleanup") return "eat-fixture:cleanup ";
  }
  if (suite === "fishing-action") {
    if (mode === "setup") return "fish-fixture:setup";
    if (mode === "inspect") return "fish-fixture:hooks=";
    if (mode === "cleanup") return "fish-fixture:cleanup ";
  }
  if (suite === "farm-action") {
    if (mode === "setup-work") return "farm-fixture:setup-work";
    if (mode === "setup-empty") return "farm-fixture:setup-empty";
    if (mode === "inspect") return "farm-fixture:case=";
    if (mode === "cleanup") return "farm-fixture:cleanup ";
  }
  if (suite === "guard-resume") {
    if (mode === "setup") return "guard-fixture:setup";
    if (mode === "arm") return "guard-fixture:armed";
    if (mode === "release") return "guard-fixture:released";
    if (mode === "inspect") return "guard-fixture:phase=";
    if (mode === "cleanup") return "guard-fixture:cleanup ";
  }
  throw new Error(`Unsupported fixture ${suite}:${mode}`);
}

export function fixtureAcknowledged(suite, mode, prefix, beforeSequence, current) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= Number(beforeSequence)
    || acknowledgement?.suite !== suite
    || acknowledgement?.mode !== mode) return false;
  if (String(acknowledgement?.status ?? "").startsWith(prefix)) return true;
  return suite === "guard-resume"
    && mode === "arm"
    && current?.activeTaskKind === "combat"
    && Number(current?.pausedTaskCount ?? 0) > 0;
}

export function fixtureUsesOrderedInspection(suite, mode) {
  return suite === "guard-resume" && (mode === "arm" || mode === "release");
}

async function fixture(base, companionId, suite, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const prefix = fixturePrefix(suite, mode);
  const pathname = `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`;
  const body = { suite, mode };
  await request(base, pathname, {
    method: "POST",
    body,
  });
  // Combat ticks overwrite the arm/release acknowledgement almost immediately.
  // WebSocket commands are ordered, so the immediately following inspect call
  // is the durable assertion for both transitions.
  if (fixtureUsesOrderedInspection(suite, mode)) return before;
  const deadline = Date.now() + FIXTURE_ACK_TIMEOUT_MS;
  let nextInspectRetry = Date.now() + 250;
  while (Date.now() < deadline) {
    const current = await snapshot(base, companionId);
    const acknowledgement = current.liveFixtureAck;
    const status = String(acknowledgement?.status ?? "");
    const currentAcknowledgement = Number(acknowledgement?.sequence ?? 0) > beforeSequence
      && acknowledgement?.suite === suite
      && acknowledgement?.mode === mode;
    if (currentAcknowledgement
      && (status.startsWith("live-fixture:denied ") || status.startsWith("live-fixture:failed "))) {
      throw new Error(`Minecraft rejected fixture ${suite}:${mode}: ${status}`);
    }
    if (fixtureAcknowledged(suite, mode, prefix, beforeSequence, current)) {
      return { ...current, status };
    }
    if ((mode === "inspect" || mode === "cleanup") && Date.now() >= nextInspectRetry) {
      await request(base, pathname, { method: "POST", body });
      nextInspectRetry = Date.now() + 250;
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error(`Minecraft did not acknowledge fixture ${suite}:${mode}`);
}

export function taskSpecs() {
  return {
    fishing: {
      kind: "fish",
      count: 1,
      radius: 24,
      requestedBy: "live-player-life-smoke",
      note: "Reversible entity-level cast, hook, reel and loot acceptance",
    },
    farmWork: {
      kind: "farm",
      cropId: "minecraft:wheat",
      action: "cycle",
      radius: 8,
      requestedBy: "live-player-life-smoke",
      note: "Reversible mature crop harvest and replant acceptance",
    },
    farmEmpty: {
      kind: "farm",
      cropId: "minecraft:wheat",
      action: "harvest",
      radius: 8,
      requestedBy: "live-player-life-smoke",
      note: "Zero-work farm tasks must fail rather than report success",
    },
    guardGather: {
      kind: "gather",
      itemId: "minecraft:oak_log",
      count: 12,
      movement: "walk",
      requestedBy: "live-player-life-smoke",
      note: "Gather progress must survive temporary owner-protection combat",
    },
  };
}

export function parseStateInspection(status) {
  const match = /^state-fixture:h=(\d+),f=(\d+),e=(\d+),beef=(\d+),managed=([01]),using=([01]),dh=([01]),dc=([01]),sh=([01]),regen=([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected player state inspection: ${JSON.stringify(status)}`);
  return {
    healthMilli: Number(match[1]),
    food: Number(match[2]),
    eaten: Number(match[3]),
    cookedBeef: Number(match[4]),
    managedEating: Number(match[5]),
    usingItem: Number(match[6]),
    diamondHelmet: Number(match[7]),
    diamondChestplate: Number(match[8]),
    shield: Number(match[9]),
    naturalRegeneration: Number(match[10]),
  };
}

export function validateStateInspection(value) {
  if (value.healthMilli <= 12_000 || value.food !== 20 || value.eaten !== 2
    || value.cookedBeef !== 2 || value.managedEating !== 0 || value.usingItem !== 0
    || value.diamondHelmet !== 1 || value.diamondChestplate !== 1 || value.shield !== 1
    || value.naturalRegeneration !== 1) {
    throw new Error(`Player state invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function isLowFoodAutomaticEatingSnapshot(value) {
  return value?.automaticEating === true && Number(value.food) < 10;
}

export function isExplicitEatingSnapshot(value, taskId) {
  return value?.activeTaskId === taskId
    && value?.activeTaskKind === "eat"
    && value?.managedEating === true
    && value?.usingItem === true
    && value?.automaticEating === false;
}

export function parseEatingInspection(status) {
  const match = /^eat-fixture:c=(rotten|melon|full),f=(\d+),e=(\d+),r=(\d+),m=(\d+),s=(\d+),x=(\d+),si=([a-z0-9_]+),fi=([a-z0-9_]+),v=(\d+),mg=([01]),u=([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected eating inspection: ${JSON.stringify(status)}`);
  return {
    scenario: match[1],
    food: Number(match[2]),
    eaten: Number(match[3]),
    rottenFlesh: Number(match[4]),
    melonSlices: Number(match[5]),
    useStarts: Number(match[6]),
    useFinishes: Number(match[7]),
    startedItem: match[8],
    finishedItem: match[9],
    violations: Number(match[10]),
    managedEating: Number(match[11]),
    usingItem: Number(match[12]),
  };
}

export function validateEatingInspection(value, testCase, phase) {
  const expected = phase === "before"
    ? {
        food: testCase.initialFood,
        eaten: 0,
        rottenFlesh: 3,
        melonSlices: 2,
        useStarts: 0,
        useFinishes: 0,
        startedItem: "none",
        finishedItem: "none",
      }
    : {
        food: 20,
        eaten: testCase.expectedEaten,
        rottenFlesh: testCase.expectedRotten,
        melonSlices: testCase.expectedMelon,
        useStarts: testCase.expectedEaten,
        useFinishes: testCase.expectedEaten,
        startedItem: testCase.expectedEaten > 0 ? testCase.scenario : "none",
        finishedItem: testCase.expectedEaten > 0 ? testCase.scenario : "none",
      };
  if (value.scenario !== testCase.scenario
    || value.food !== expected.food
    || value.eaten !== expected.eaten
    || value.rottenFlesh !== expected.rottenFlesh
    || value.melonSlices !== expected.melonSlices
    || value.useStarts !== expected.useStarts
    || value.useFinishes !== expected.useFinishes
    || value.startedItem !== expected.startedItem
    || value.finishedItem !== expected.finishedItem
    || value.violations !== 0
    || value.managedEating !== 0
    || value.usingItem !== 0) {
    throw new Error(`Eating ${phase} invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function parseFishingInspection(status) {
  const match = /^fish-fixture:hooks=(\d+),owned=(\d+),active=(\d+),loot=(\d+),damage=(-?\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected fishing inspection: ${JSON.stringify(status)}`);
  return {
    hooks: Number(match[1]),
    ownedHooks: Number(match[2]),
    activeHooks: Number(match[3]),
    loot: Number(match[4]),
    rodDamage: Number(match[5]),
  };
}

export function validateFishingInspection(value, phase) {
  const valid = phase === "cast"
    ? value.hooks >= 1 && value.ownedHooks >= 1 && value.activeHooks >= 1
    : value.hooks >= 1 && value.ownedHooks >= 1 && value.activeHooks === 0
      && value.loot >= 1 && value.rodDamage >= 1;
  if (!valid) throw new Error(`Fishing ${phase} invariants failed: ${JSON.stringify(value)}`);
  return value;
}

export function parseFarmInspection(status) {
  const match = /^farm-fixture:case=(work|empty),mature=(\d+),young=(\d+),breaks=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected farm inspection: ${JSON.stringify(status)}`);
  return { scenario: match[1], mature: Number(match[2]), young: Number(match[3]), breaks: Number(match[4]) };
}

export function validateFarmInspection(value, phase) {
  const valid = phase === "work-before"
    ? value.scenario === "work" && value.mature === 2 && value.young === 0 && value.breaks === 0
    : phase === "work-after"
      ? value.scenario === "work" && value.mature === 0 && value.young === 2 && value.breaks === 2
      : value.scenario === "empty" && value.mature === 0 && value.young === 0 && value.breaks === 0;
  if (!valid) throw new Error(`Farm ${phase} invariants failed: ${JSON.stringify(value)}`);
  return value;
}

export function parseGuardInspection(status) {
  const match = /^guard-fixture:phase=(combat|work),paused=(\d+),pre=(\d+),now=(\d+),hostile=(\d+),same=([01]),resumed=([01]),logs=(\d+),breaks=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected guard inspection: ${JSON.stringify(status)}`);
  return {
    phase: match[1],
    paused: Number(match[2]),
    beforeProgressMilli: Number(match[3]),
    currentProgressMilli: Number(match[4]),
    hostiles: Number(match[5]),
    sameTask: Number(match[6]),
    resumed: Number(match[7]),
    logs: Number(match[8]),
    breaks: Number(match[9]),
  };
}

export function validateGuardInspection(value, phase) {
  const valid = phase === "combat"
    ? value.phase === "combat" && value.paused >= 1 && value.hostiles === 1 && value.sameTask === 1
      && value.beforeProgressMilli > 0 && value.beforeProgressMilli < 1000
      && value.currentProgressMilli === value.beforeProgressMilli
    : phase === "resumed"
      ? value.phase === "work" && value.hostiles === 0 && value.sameTask === 1 && value.resumed === 1
        && value.currentProgressMilli >= value.beforeProgressMilli
      : value.phase === "work" && value.hostiles === 0 && value.resumed === 1
        && value.logs === 12 && value.breaks === 12;
  if (!valid) throw new Error(`Guard ${phase} invariants failed: ${JSON.stringify(value)}`);
  return value;
}

export function validateCleanup(status, suite) {
  const expected = [`${fixturePrefix(suite, "cleanup")}restored`, `${fixturePrefix(suite, "cleanup")}none`];
  if (!expected.includes(status)) throw new Error(`Fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  return status;
}

async function assign(base, companionId, spec) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec, owner: "live-player-life-smoke" },
  });
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

async function waitForNewEatTask(base, companionId, priorIds, testCase, waitMs = 15_000) {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    const response = await request(base, "/api/tasks");
    const task = response.tasks?.find((candidate) => (
      candidate.companionId === companionId
      && !priorIds.has(candidate.id)
      && candidate.spec?.kind === "eat"
    ));
    if (task) {
      if (task.spec.itemId !== testCase.itemId || task.spec.count !== testCase.count) {
        throw new Error(`Minecraft T chat created the wrong eat task: ${JSON.stringify(task.spec)}`);
      }
      return task;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`Minecraft T chat did not create the ${testCase.scenario} eat task`);
}

async function observeExplicitEating(base, companionId, task, waitMs) {
  const deadline = Date.now() + Math.min(waitMs, 10_000);
  while (Date.now() < deadline) {
    const current = await taskRecord(base, task);
    const state = await snapshot(base, companionId);
    if (isExplicitEatingSnapshot(state, task.id)) return true;
    if (TERMINAL.has(current.status)) return false;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return false;
}

async function taskRecord(base, task) {
  return request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
}

async function waitForTask(base, task, waitMs, predicate) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (Date.now() < deadline) {
    current = await taskRecord(base, task);
    if (predicate(current)) return current;
    if (TERMINAL.has(current.status)) break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Task ${task.id} did not reach the required state: ${JSON.stringify(current)}`);
}

async function waitTerminal(base, task, waitMs) {
  return waitForTask(base, task, waitMs, (current) => TERMINAL.has(current.status));
}

async function cancelTask(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await taskRecord(base, task);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live player life fixture cleanup" },
    });
  }
  return waitTerminal(base, current, waitMs);
}

async function cleanup(base, companionId, suite) {
  return validateCleanup(String((await fixture(base, companionId, suite, "cleanup")).status ?? ""), suite);
}

async function inspectUntil(base, companionId, suite, parser, validator, waitMs) {
  const deadline = Date.now() + waitMs;
  let value = null;
  let error = null;
  while (Date.now() < deadline) {
    value = parser((await fixture(base, companionId, suite, "inspect")).status);
    try {
      return validator(value);
    } catch (candidate) {
      error = candidate;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw error ?? new Error(`Fixture ${suite} inspection did not converge: ${JSON.stringify(value)}`);
}

async function runState(base, companionId, waitMs) {
  try {
    await cleanup(base, companionId, "player-state");
    await fixture(base, companionId, "player-state", "setup");
    const eatingDeadline = Date.now() + Math.min(waitMs, 5_000);
    let automaticEatingObserved = false;
    while (Date.now() < eatingDeadline) {
      if (isLowFoodAutomaticEatingSnapshot(await snapshot(base, companionId))) {
        automaticEatingObserved = true;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    if (!automaticEatingObserved) {
      throw new Error("NPC did not expose automatic eating while below half hunger");
    }
    const completed = await inspectUntil(
      base,
      companionId,
      "player-state",
      parseStateInspection,
      validateStateInspection,
      Math.min(waitMs, 30_000),
    );
    return { ...completed, automaticEatingObserved };
  } finally {
    await cleanup(base, companionId, "player-state");
  }
}

async function runExplicitEatingCase(base, companionId, waitMs, testCase) {
  let task = null;
  try {
    await cleanup(base, companionId, "eating-action");
    await fixture(base, companionId, "eating-action", testCase.setupMode);
    const before = validateEatingInspection(parseEatingInspection(
      (await fixture(base, companionId, "eating-action", "inspect")).status,
    ), testCase, "before");
    const taskList = await request(base, "/api/tasks");
    const priorIds = new Set(taskList.tasks?.map((candidate) => candidate.id) ?? []);
    await sendMinecraftTChat(testCase.prompt);
    task = await waitForNewEatTask(base, companionId, priorIds, testCase);
    const actionObserved = testCase.expectedEaten > 0
      ? await observeExplicitEating(base, companionId, task, waitMs)
      : false;
    if (testCase.expectedEaten > 0 && !actionObserved) {
      throw new Error(`NPC did not expose the ${testCase.scenario} use-item action`);
    }
    const terminal = await waitTerminal(base, task, waitMs);
    if (terminal.status !== "succeeded") {
      throw new Error(`Eating task failed: ${terminal.error?.code ?? terminal.message}`);
    }
    const after = validateEatingInspection(parseEatingInspection(
      (await fixture(base, companionId, "eating-action", "inspect")).status,
    ), testCase, "after");
    return {
      taskId: terminal.id,
      status: terminal.status,
      prompt: testCase.prompt,
      itemId: testCase.itemId,
      count: testCase.count,
      actionObserved,
      before,
      after,
    };
  } finally {
    await cancelTask(base, task, Math.min(waitMs, 30_000));
    await cleanup(base, companionId, "eating-action");
  }
}

async function runExplicitEating(base, companionId, waitMs) {
  const results = {};
  for (const testCase of explicitEatingCases()) {
    results[testCase.scenario] = await runExplicitEatingCase(base, companionId, waitMs, testCase);
  }
  return results;
}

async function runFishing(base, companionId, waitMs, specs) {
  let task = null;
  try {
    await cleanup(base, companionId, "fishing-action");
    await fixture(base, companionId, "fishing-action", "setup");
    task = await assign(base, companionId, specs.fishing);
    const cast = await inspectUntil(
      base,
      companionId,
      "fishing-action",
      parseFishingInspection,
      (value) => validateFishingInspection(value, "cast"),
      Math.min(waitMs, 30_000),
    );
    const terminal = await waitTerminal(base, task, waitMs);
    if (terminal.status !== "succeeded") throw new Error(`Fishing task failed: ${terminal.error?.code ?? terminal.message}`);
    const caught = validateFishingInspection(parseFishingInspection(
      (await fixture(base, companionId, "fishing-action", "inspect")).status,
    ), "caught");
    return { taskId: terminal.id, status: terminal.status, cast, caught };
  } finally {
    await cancelTask(base, task, Math.min(waitMs, 30_000));
    await cleanup(base, companionId, "fishing-action");
  }
}

async function runFarm(base, companionId, waitMs, specs) {
  const results = {};
  let task = null;
  try {
    await cleanup(base, companionId, "farm-action");
    await fixture(base, companionId, "farm-action", "setup-work");
    results.before = validateFarmInspection(parseFarmInspection(
      (await fixture(base, companionId, "farm-action", "inspect")).status,
    ), "work-before");
    task = await assign(base, companionId, specs.farmWork);
    const work = await waitTerminal(base, task, waitMs);
    if (work.status !== "succeeded") throw new Error(`Farm work task failed: ${work.error?.code ?? work.message}`);
    results.workTask = { id: work.id, status: work.status };
    results.after = validateFarmInspection(parseFarmInspection(
      (await fixture(base, companionId, "farm-action", "inspect")).status,
    ), "work-after");
    task = null;
    await cleanup(base, companionId, "farm-action");

    await fixture(base, companionId, "farm-action", "setup-empty");
    results.empty = validateFarmInspection(parseFarmInspection(
      (await fixture(base, companionId, "farm-action", "inspect")).status,
    ), "empty");
    task = await assign(base, companionId, specs.farmEmpty);
    const empty = await waitTerminal(base, task, waitMs);
    if (empty.status !== "failed" || empty.error?.code !== "FARM_TARGET_NOT_FOUND") {
      throw new Error(`Zero-work farm task did not fail correctly: ${JSON.stringify(empty)}`);
    }
    results.emptyTask = { id: empty.id, status: empty.status, error: empty.error.code };
    return results;
  } finally {
    await cancelTask(base, task, Math.min(waitMs, 30_000));
    await cleanup(base, companionId, "farm-action");
  }
}

async function runGuard(base, companionId, waitMs, specs) {
  let task = null;
  let released = false;
  try {
    await cleanup(base, companionId, "guard-resume");
    await fixture(base, companionId, "guard-resume", "setup");
    task = await assign(base, companionId, specs.guardGather);
    await waitForTask(base, task, waitMs, (current) => (
      current.status === "running" && Number(current.progress) > 0 && Number(current.progress) < 1
    ));
    await fixture(base, companionId, "guard-resume", "arm");
    const combat = await inspectUntil(
      base,
      companionId,
      "guard-resume",
      parseGuardInspection,
      (value) => validateGuardInspection(value, "combat"),
      Math.min(waitMs, 15_000),
    );
    await fixture(base, companionId, "guard-resume", "release");
    released = true;
    const resumed = await inspectUntil(
      base,
      companionId,
      "guard-resume",
      parseGuardInspection,
      (value) => validateGuardInspection(value, "resumed"),
      Math.min(waitMs, 15_000),
    );
    const terminal = await waitTerminal(base, task, waitMs);
    if (terminal.status !== "succeeded") throw new Error(`Interrupted gather task failed: ${terminal.error?.code ?? terminal.message}`);
    const completed = validateGuardInspection(parseGuardInspection(
      (await fixture(base, companionId, "guard-resume", "inspect")).status,
    ), "completed");
    return { taskId: terminal.id, status: terminal.status, combat, resumed, completed };
  } finally {
    if (!released) {
      try {
        await fixture(base, companionId, "guard-resume", "release");
        await new Promise((resolve) => setTimeout(resolve, 500));
      } catch {
        // Setup may have failed before a marker existed.
      }
    }
    await cancelTask(base, task, Math.min(waitMs, 30_000));
    await cleanup(base, companionId, "guard-resume");
  }
}

export function parseCli(argv) {
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) {
    throw new Error("--wait-seconds must be between 30 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export async function runLivePlayerLifeSmoke(options) {
  const specs = taskSpecs();
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      ...PLAYER_LIFE_ACTOR_EVIDENCE,
      usedMinecraftTChat: true,
      specs,
      explicitEatingCases: explicitEatingCases(),
    };
  }
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    ...PLAYER_LIFE_ACTOR_EVIDENCE,
    usedMinecraftTChat: true,
    companionId: companion.id,
    playerState: await runState(options.base, companion.id, options.waitMs),
    specifiedEating: await runExplicitEating(options.base, companion.id, options.waitMs),
    fishing: await runFishing(options.base, companion.id, options.waitMs, specs),
    farm: await runFarm(options.base, companion.id, options.waitMs, specs),
    guardResume: await runGuard(options.base, companion.id, options.waitMs, specs),
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLivePlayerLifeSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live player life smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
