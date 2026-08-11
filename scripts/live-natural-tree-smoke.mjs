import { pathToFileURL } from "node:url";

const TERMINAL = new Set(["succeeded", "failed", "cancelled"]);

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live natural tree smoke only connects to a loopback HTTP service");
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
  if (mode === "setup") return "tree-fixture:setup";
  if (mode === "inspect") return "tree-fixture:protected=";
  if (mode === "cleanup") return "tree-fixture:cleanup ";
  throw new Error(`Unsupported natural tree fixture mode ${mode}`);
}

export function fixtureRetryable(mode) {
  return mode === "inspect" || mode === "cleanup";
}

async function fixture(base, companionId, mode) {
  const before = await snapshot(base, companionId);
  const expected = fixtureExpectedPrefix(mode);
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
    method: "POST",
    body: { suite: "natural-tree", mode },
  });
  const deadline = Date.now() + 15_000;
  let nextRetry = Date.now() + 250;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
    const current = await snapshot(base, companionId);
    const status = String(current.status ?? "");
    if (status.startsWith("live-fixture:denied ")) {
      throw new Error(`Minecraft rejected natural tree fixture ${mode}: ${status}`);
    }
    if (Number(current.sequence) > Number(before.sequence) && status.startsWith(expected)) return current;
    if (fixtureRetryable(mode) && Date.now() >= nextRetry) {
      await request(base, `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`, {
        method: "POST",
        body: { suite: "natural-tree", mode },
      });
      nextRetry = Date.now() + 250;
    }
  }
  throw new Error(`Minecraft did not acknowledge natural tree fixture ${mode}`);
}

export function gatherTaskSpec() {
  return {
    kind: "gather",
    itemId: "#minecraft:logs",
    count: 10,
    movement: "walk",
    requestedBy: "live-natural-tree-smoke",
    note: "Local reversible natural-tree reach and home-protection acceptance",
  };
}

export function parseInspection(status) {
  const match = /^tree-fixture:protected=(\d+),remote=(\d+),npcLogs=(\d+),boundaryProtected=(\d+),artificial=(\d+),breaks=(\d+),rb=(\d+),los=(\d+),max=(\d+),sync=(\d+)$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected natural tree inspection status: ${JSON.stringify(status)}`);
  return {
    protected: Number(match[1]),
    remote: Number(match[2]),
    npcLogs: Number(match[3]),
    boundaryProtected: Number(match[4]),
    artificial: Number(match[5]),
    breaks: Number(match[6]),
    remoteBreaks: Number(match[7]),
    losViolations: Number(match[8]),
    maxTouchMilli: Number(match[9]),
    syncViolations: Number(match[10]),
  };
}

export function validateSetupInspection(inspection) {
  if (inspection.protected !== 4
    || inspection.remote !== 4
    || inspection.npcLogs !== 0
    || inspection.boundaryProtected !== 2
    || inspection.artificial !== 4
    || inspection.breaks !== 0
    || inspection.remoteBreaks !== 0
    || inspection.losViolations !== 0
    || inspection.maxTouchMilli !== 0
    || inspection.syncViolations !== 0) {
    throw new Error(`Natural tree fixture setup invariants failed: ${JSON.stringify(inspection)}`);
  }
  return inspection;
}

export function validateInspection(inspection) {
  if (inspection.protected !== 4
    || inspection.remote !== 0
    || inspection.npcLogs !== 10
    || inspection.boundaryProtected !== 2
    || inspection.artificial !== 4
    || inspection.breaks !== 10
    || inspection.remoteBreaks !== 0
    || inspection.losViolations !== 0
    || inspection.maxTouchMilli > 4_500
    || inspection.syncViolations !== 0) {
    throw new Error(`Natural tree fixture invariants failed: ${JSON.stringify(inspection)}`);
  }
  return inspection;
}

export function validateCleanupStatus(status) {
  if (!["tree-fixture:cleanup restored", "tree-fixture:cleanup none"].includes(status)) {
    throw new Error(`Natural tree fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

export async function waitForTerminal(base, task, waitMs, requireSuccess = false) {
  const deadline = Date.now() + waitMs;
  let current = task;
  while (!TERMINAL.has(current.status) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  }
  if (!TERMINAL.has(current.status)) throw new Error(`Natural tree task ${task.id} timed out`);
  if (requireSuccess && current.status !== "succeeded") {
    throw new Error(`Natural tree task ${task.id} ${current.status}: ${current.error?.code ?? current.message ?? "unknown"}`);
  }
  return current;
}

async function cancelAndWait(base, task, waitMs) {
  if (!task?.id) return null;
  let current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}`);
  if (!TERMINAL.has(current.status)) {
    current = await request(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
      method: "POST",
      body: { reason: "live natural tree fixture cleanup" },
    });
  }
  return waitForTerminal(base, current, waitMs, false);
}

async function cleanupAndConfirm(base, companionId) {
  return validateCleanupStatus(String((await fixture(base, companionId, "cleanup")).status ?? ""));
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

export async function runLiveNaturalTreeSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      spec: gatherTaskSpec(),
    };
  }
  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId) throw new Error(`Companion already has active task ${companion.activeTaskId}`);
  let task = null;
  try {
    await cleanupAndConfirm(options.base, companion.id);
    const setup = await fixture(options.base, companion.id, "setup");
    if (setup.materialMode !== "survival") {
      throw new Error(`Natural tree fixture must run in survival material mode, received ${JSON.stringify(setup.materialMode)}`);
    }
    const setupInspection = validateSetupInspection(parseInspection(
      (await fixture(options.base, companion.id, "inspect")).status,
    ));
    task = await request(options.base, `/api/companions/${encodeURIComponent(companion.id)}/tasks`, {
      method: "POST",
      body: { spec: gatherTaskSpec(), owner: "live-natural-tree-smoke" },
    });
    const finished = await waitForTerminal(options.base, task, options.waitMs, true);
    const inspection = validateInspection(parseInspection(
      (await fixture(options.base, companion.id, "inspect")).status,
    ));
    return {
      ok: true,
      dryRun: false,
      localOnly: true,
      reversible: true,
      companionId: companion.id,
      setupInspection,
      task: { id: finished.id, status: finished.status, message: finished.message },
      inspection,
    };
  } finally {
    await cancelAndWait(options.base, task, Math.min(options.waitMs, 30_000));
    await cleanupAndConfirm(options.base, companion.id);
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveNaturalTreeSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live natural tree smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
