import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);
const BASE_SCENARIOS = new Set(["mixed", "chain"]);
const REQUIRED_VANILLA_WOOD = [
  "minecraft:oak_planks",
  "minecraft:spruce_planks",
  "minecraft:birch_planks",
  "minecraft:jungle_planks",
  "minecraft:acacia_planks",
  "minecraft:dark_oak_planks",
  "minecraft:mangrove_planks",
  "minecraft:cherry_planks",
  "minecraft:bamboo_planks",
  "minecraft:crimson_planks",
  "minecraft:warped_planks",
];
const REQUIRED_VANILLA_MASONRY = [
  "minecraft:stone",
  "minecraft:cobblestone",
  "minecraft:stone_bricks",
  "minecraft:cobbled_deepslate",
  "minecraft:deepslate_bricks",
  "minecraft:bricks",
];
const FIXTURE_BLOCKS = [
  ["minecraft:oak_planks", 0, 0, 0],
  ["minecraft:oak_stairs", 2, 0, 0],
  ["minecraft:oak_slab", 4, 0, 0],
  ["minecraft:oak_fence", 0, 0, 2],
  ["minecraft:oak_trapdoor", 2, 0, 2],
  ["minecraft:oak_pressure_plate", 4, 0, 2],
];
const MASONRY_FIXTURE_BLOCKS = [
  ["minecraft:stone", 0, 0, 0],
  ["minecraft:stone_stairs", 2, 0, 0],
  ["minecraft:stone_slab", 4, 0, 0],
];

function isScenario(value) {
  return BASE_SCENARIOS.has(value) || /^family-\d{1,4}$/u.test(value);
}

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live build palette smoke only connects to a loopback HTTP service");
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
  if (mode.startsWith("setup-")) {
    return `build-fixture:setup scenario=${mode.slice("setup-".length)} origin=`;
  }
  if (mode.startsWith("inspect-")) {
    return `build-fixture:${mode.slice("inspect-".length)} `;
  }
  if (mode === "catalog" || mode.startsWith("catalog-")) return "build-fixture:catalog ";
  if (mode === "cleanup") return "build-fixture:cleanup ";
  throw new Error(`Unsupported build fixture mode ${mode}`);
}

export function fixtureRetryable(mode) {
  return mode.startsWith("inspect-") || mode === "cleanup";
}

export function fixtureAcknowledgement(current, beforeSequence, mode) {
  const acknowledgement = current?.liveFixtureAck;
  if (Number(acknowledgement?.sequence ?? 0) <= beforeSequence
    || acknowledgement?.suite !== "build-palette"
    || acknowledgement?.mode !== mode) return null;
  return String(acknowledgement.status ?? "");
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const beforeSequence = Number(before.liveFixtureAck?.sequence ?? 0);
  const expected = fixtureExpectedPrefix(mode);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "build-palette", mode },
  });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 250;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
    const current = await snapshot(base, companionId);
    const status = fixtureAcknowledgement(current, beforeSequence, mode);
    if (status !== null) {
      if (status.startsWith("live-fixture:denied ")) {
        throw new Error(`Minecraft rejected build palette fixture ${mode}: ${status}`);
      }
      if (status.startsWith("live-fixture:failed ") && !fixtureRetryable(mode)) {
        throw new Error(`Minecraft build palette fixture ${mode} failed: ${status}`);
      }
      if (status.startsWith(expected)) return { ...current, status };
      if (!fixtureRetryable(mode)) {
        throw new Error(`Minecraft build palette fixture ${mode} returned an unexpected acknowledgement: ${status}`);
      }
    }
    if (fixtureRetryable(mode) && Date.now() >= nextRetry) {
      await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
        method: "POST",
        body: { suite: "build-palette", mode },
      });
      nextRetry = Date.now() + 250;
    }
  }
  throw new Error(`Minecraft did not acknowledge build palette fixture ${mode}`);
}

export function parseSetupStatus(status, scenario) {
  if (!isScenario(scenario)) throw new Error(`Unsupported build palette scenario ${scenario}`);
  const match = /^build-fixture:setup scenario=(mixed|chain|family-\d{1,4}) origin=(-?\d+),(-?\d+),(-?\d+)$/u.exec(status ?? "");
  if (!match || match[1] !== scenario) {
    throw new Error(`Unexpected ${scenario} build fixture setup status: ${JSON.stringify(status)}`);
  }
  return { x: Number(match[2]), y: Number(match[3]), z: Number(match[4]) };
}

export function parseInspection(status, scenario) {
  if (!isScenario(scenario)) throw new Error(`Unsupported build palette scenario ${scenario}`);
  const match = /^build-fixture:(mixed|chain|family-\d{1,4}) expected=(\d+),matching=(\d+),wrong=(\d+)$/u.exec(status ?? "");
  if (!match || match[1] !== scenario) {
    throw new Error(`Unexpected ${scenario} build fixture inspection status: ${JSON.stringify(status)}`);
  }
  return { expected: Number(match[2]), matching: Number(match[3]), wrong: Number(match[4]) };
}

export function validateInspection(inspection, expectedCount = 6) {
  if (inspection.expected !== expectedCount
    || inspection.matching !== expectedCount
    || inspection.wrong !== 0) {
    throw new Error(`Build palette fixture did not match all expected blocks: ${JSON.stringify(inspection)}`);
  }
  return inspection;
}

export function validateCleanupStatus(status) {
  if (!["build-fixture:cleanup restored", "build-fixture:cleanup none"].includes(status)) {
    throw new Error(`Build palette fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

export function buildPlanDraft(origin, scenario, family) {
  if (!isScenario(scenario)) throw new Error(`Unsupported build palette scenario ${scenario}`);
  if (![origin?.x, origin?.y, origin?.z].every(Number.isInteger)) {
    throw new Error("Build fixture origin must contain integer coordinates");
  }
  if (scenario.startsWith("family-") && (!family || !Array.isArray(family.blockIds))) {
    throw new Error("A registry-derived family is required for a family build fixture");
  }
  const blocks = scenario.startsWith("family-")
    ? (family.category === "wood" ? FIXTURE_BLOCKS : MASONRY_FIXTURE_BLOCKS)
    : FIXTURE_BLOCKS;
  return {
    name: `Live build palette ${scenario}`,
    source: "demo",
    origin: { x: origin.x, y: origin.y, z: origin.z },
    blocks: blocks.map(([blockId, x, y, z]) => ({
      position: { x, y, z },
      blockId,
      properties: {},
    })),
  };
}

export function buildTaskSpec(planId, scenario, family) {
  if (!isScenario(scenario)) throw new Error(`Unsupported build palette scenario ${scenario}`);
  if (scenario.startsWith("family-") && (!family?.baseId || !family?.category)) {
    throw new Error("A registry-derived family is required for a family task");
  }
  const materialPreference = scenario === "mixed"
    ? { source: "inventory", allowMixed: true }
    : scenario === "chain"
      ? { source: "inventory", preferredBlockId: "minecraft:dark_oak_planks", allowMixed: false }
      : { source: "inventory", preferredBlockId: family.baseId, allowMixed: false };
  return {
    kind: "build",
    planId,
    placement: "plan-origin",
    materialPreference,
    requestedBy: "live-build-palette-smoke",
    note: `Local reversible build palette acceptance: ${scenario}`,
  };
}

export function parseCatalogSummary(status) {
  const match = /^build-fixture:catalog count=(\d+),supported=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected build family catalog summary: ${JSON.stringify(status)}`);
  const result = { count: Number(match[1]), supported: Number(match[2]) };
  if (result.count > 10_000 || result.supported > result.count) {
    throw new Error(`Invalid build family catalog totals: ${JSON.stringify(result)}`);
  }
  return result;
}

export function parseCatalogEntry(status, expectedIndex, expectedCount) {
  const match = /^build-fixture:catalog index=(\d+),count=(\d+),category=(wood|masonry),base=([a-z0-9_.-]+:[a-z0-9_./-]+),source=(none|[a-z0-9_.-]+:[a-z0-9_./-]+),supported=([01]),reason=([a-z0-9+.-]+),blocks=(none|[a-z0-9_.-]+:[a-z0-9_./-]+(?:\|[a-z0-9_.-]+:[a-z0-9_./-]+)*)$/u.exec(status ?? "");
  if (!match || Number(match[1]) !== expectedIndex || Number(match[2]) !== expectedCount) {
    throw new Error(`Unexpected build family catalog entry: ${JSON.stringify(status)}`);
  }
  const result = {
    index: Number(match[1]),
    count: Number(match[2]),
    category: match[3],
    baseId: match[4],
    sourceId: match[5] === "none" ? "" : match[5],
    supported: match[6] === "1",
    skipReason: match[7] === "none" ? "" : match[7],
    blockIds: match[8] === "none" ? [] : match[8].split("|"),
  };
  const expectedBlocks = result.category === "wood" ? 6 : 3;
  if (result.supported && (!result.sourceId || result.skipReason || result.blockIds.length !== expectedBlocks)) {
    throw new Error(`Supported build family catalog entry is incomplete: ${JSON.stringify(result)}`);
  }
  if (!result.supported && result.blockIds.length !== 0) {
    throw new Error(`Skipped build family catalog entry unexpectedly contains runnable blocks: ${JSON.stringify(result)}`);
  }
  return result;
}

async function createConfirmedPlan(base, origin, scenario, family) {
  const draft = buildPlanDraft(origin, scenario, family);
  const preview = await request(base, "/api/build-plans/preview", {
    method: "POST",
    body: draft,
  });
  if (!preview?.id
    || preview.confirmed !== false
    || JSON.stringify(preview.origin) !== JSON.stringify(draft.origin)
    || JSON.stringify(preview.blocks) !== JSON.stringify(draft.blocks)) {
    throw new Error(`Control plane returned an invalid build preview: ${JSON.stringify(preview)}`);
  }
  const confirmed = await request(base, `/api/build-plans/${encodeURIComponent(preview.id)}/confirm`, {
    method: "POST",
  });
  if (confirmed?.id !== preview.id
    || confirmed.confirmed !== true
    || JSON.stringify(confirmed.origin) !== JSON.stringify(draft.origin)
    || JSON.stringify(confirmed.blocks) !== JSON.stringify(draft.blocks)) {
    throw new Error(`Build preview ${preview.id} was not confirmed intact`);
  }
  return confirmed;
}

async function assignBuild(base, companionId, planId, scenario, family) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: { spec: buildTaskSpec(planId, scenario, family), owner: "live-build-palette-smoke" },
  });
}

export async function waitForTerminal(base, task, waitMs, requireSuccess = false) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Build palette task ${task.id} timed out`);
  if (requireSuccess && current.status !== "succeeded") {
    throw new Error(`Build palette task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return current;
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live build palette fixture cleanup" },
    });
  }
  return waitForTerminal(base, current, waitMs, false);
}

async function cleanupAndConfirm(base, companionId) {
  const status = String((await fixture(base, companionId, "cleanup")).status ?? "");
  return validateCleanupStatus(status);
}

async function runScenario(options, companion, scenario, family = null) {
  let task = null;
  let cleanupStatus = "not-run";
  try {
    await cleanupAndConfirm(options.base, companion.id);
    const origin = parseSetupStatus(
      (await fixture(options.base, companion.id, `setup-${scenario}`)).status,
      scenario,
    );
    const plan = await createConfirmedPlan(options.base, origin, scenario, family);
    task = await assignBuild(options.base, companion.id, plan.id, scenario, family);
    const finished = await waitForTerminal(options.base, task, options.waitMs, true);
    const inspection = validateInspection(parseInspection(
      (await fixture(options.base, companion.id, `inspect-${scenario}`)).status,
      scenario,
    ), family?.blockIds?.length ?? 6);
    return {
      scenario,
      family: family ? {
        index: family.index,
        category: family.category,
        baseId: family.baseId,
        sourceId: family.sourceId,
        blockIds: family.blockIds,
      } : undefined,
      origin,
      planId: plan.id,
      task: { id: finished.id, status: finished.status, message: finished.message },
      inspection,
    };
  } finally {
    await cancelAndWait(options.base, task, Math.min(options.waitMs, 30_000));
    cleanupStatus = await cleanupAndConfirm(options.base, companion.id);
    if (cleanupStatus !== "build-fixture:cleanup restored" && cleanupStatus !== "build-fixture:cleanup none") {
      throw new Error(`Unexpected cleanup status ${cleanupStatus}`);
    }
  }
}

export function parseCli(argv) {
  const scenarioArg = argv.find((value) => value.startsWith("--scenario="));
  const scenario = scenarioArg?.slice("--scenario=".length) ?? "all";
  if (![...BASE_SCENARIOS, "matrix", "all"].includes(scenario) && !/^family-\d{1,4}$/u.test(scenario)) {
    throw new Error("--scenario must be mixed, chain, matrix, family-N, or all");
  }
  const waitArg = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = waitArg ? Number(waitArg.slice("--wait-seconds=".length)) : 300;
  if (!Number.isFinite(seconds) || seconds < 15 || seconds > 900) {
    throw new Error("--wait-seconds must be between 15 and 900");
  }
  return {
    apply: argv.includes("--apply"),
    scenarios: scenario === "all" ? ["mixed", "chain", "matrix"] : [scenario],
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

async function loadFamilyCatalog(base, companionId) {
  const summary = parseCatalogSummary(
    (await fixture(base, companionId, "catalog")).status,
  );
  const families = [];
  for (let index = 0; index < summary.count; index++) {
    families.push(parseCatalogEntry(
      (await fixture(base, companionId, `catalog-${index}`)).status,
      index,
      summary.count,
    ));
  }
  return { ...summary, families };
}

function namespaceOf(id) {
  return id.slice(0, id.indexOf(":"));
}

function selectMatrixFamilies(catalog) {
  const byBase = new Map(catalog.families.map((family) => [family.baseId, family]));
  const required = [...REQUIRED_VANILLA_WOOD, ...REQUIRED_VANILLA_MASONRY];
  const missing = required.filter((baseId) => !byBase.has(baseId));
  if (missing.length) throw new Error(`Registry family catalog is missing required vanilla families: ${missing.join(",")}`);
  const invalid = required.filter((baseId) => {
    const family = byBase.get(baseId);
    return !family.supported;
  });
  if (invalid.length) throw new Error(`Required vanilla material families are not safely testable: ${invalid.join(",")}`);
  const vanilla = required.map((baseId) => byBase.get(baseId));
  const modded = catalog.families.filter((family) => (
    namespaceOf(family.baseId) !== "minecraft"
    && family.category === "wood"
    && family.supported
  ));
  const skipped = catalog.families.filter((family) => (
    namespaceOf(family.baseId) !== "minecraft"
    && family.category === "wood"
    && !family.supported
  ));
  return { families: [...vanilla, ...modded], skipped };
}

export async function runLiveBuildPaletteSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
       scenarios: options.scenarios,
       blockCount: FIXTURE_BLOCKS.length,
       matrixRequirements: {
         vanillaWood: REQUIRED_VANILLA_WOOD,
         vanillaMasonry: REQUIRED_VANILLA_MASONRY,
       },
    };
  }
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  const results = [];
  const skippedFamilies = [];
  for (const scenario of options.scenarios) {
    if (BASE_SCENARIOS.has(scenario)) {
      results.push(await runScenario(options, companion, scenario));
      continue;
    }
    const catalog = await loadFamilyCatalog(options.base, companion.id);
    const matrix = selectMatrixFamilies(catalog);
    skippedFamilies.push(...matrix.skipped);
    if (scenario === "matrix") {
      for (const family of matrix.families) {
        results.push(await runScenario(options, companion, `family-${family.index}`, family));
      }
      continue;
    }
    if (scenario.startsWith("family-")) {
      const family = catalog.families[Number(scenario.slice("family-".length))];
      if (!family) throw new Error(`Unknown build family ${scenario}`);
      if (!family.supported) throw new Error(`Build family ${family.baseId} is skipped: ${family.skipReason}`);
      results.push(await runScenario(options, companion, scenario, family));
      continue;
    }
    throw new Error(`Unsupported build palette scenario ${scenario}`);
  }
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    companionId: companion.id,
    results,
    skippedFamilies,
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveBuildPaletteSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live build palette smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
