import { pathToFileURL } from "node:url";

const TERMINAL_POLL_MS = 250;

export function loopbackBase(value) {
  const url = new URL(value);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname)) {
    throw new Error("The control URL must use loopback HTTP");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

async function request(base, pathname, fetchImpl) {
  const response = await fetchImpl(new URL(pathname, base));
  if (!response.ok) {
    throw new Error(`${pathname} returned HTTP ${response.status}`);
  }
  return response.json();
}

function selectConnection(health, companionId) {
  const connections = health?.minecraftBridge?.connections ?? [];
  const matches = connections.filter((connection) => (
    connection.connected === true
    && (!companionId || connection.companionId === companionId)
  ));
  if (matches.length !== 1) {
    throw new Error("Manual T-chat observation requires exactly one matching connected companion");
  }
  return matches[0];
}

function newCompanionTasks(response, baselineIds, companionId) {
  return (response?.tasks ?? []).filter((task) => (
    task.companionId === companionId && !baselineIds.has(task.id)
  ));
}

export async function waitForManualChat(options, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const sleep = dependencies.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const onInstruction = dependencies.onInstruction ?? ((message) => console.error(message));
  const base = loopbackBase(options.baseUrl);
  const initialHealth = await request(base, "/api/health", fetchImpl);
  const initialConnection = selectConnection(initialHealth, options.companionId);
  const companionId = initialConnection.companionId;
  const initialTasks = await request(base, "/api/tasks", fetchImpl);
  const baselineIds = new Set((initialTasks.tasks ?? []).map((task) => task.id));
  const initialIncoming = Number(initialConnection.incomingChatCount ?? 0);
  const initialDelivered = Number(initialConnection.deliveredChatCount ?? 0);
  const initialRoundTrips = Number(initialConnection.roundTripCount ?? 0);
  const deadline = Date.now() + options.waitSeconds * 1_000;

  onInstruction(`MANUAL_UI_REQUIRED: in Minecraft, press T and submit exactly: ${options.prompt}`);

  let latestConnection = initialConnection;
  let latestTasks = [];
  while (Date.now() < deadline) {
    const health = await request(base, "/api/health", fetchImpl);
    latestConnection = selectConnection(health, companionId);
    const manualSubmissionObserved = Number(latestConnection.incomingChatCount ?? 0) > initialIncoming;
    if (manualSubmissionObserved) {
      const tasks = await request(base, "/api/tasks", fetchImpl);
      latestTasks = newCompanionTasks(tasks, baselineIds, companionId);
      const matchingTasks = options.expectedTaskKind
        ? latestTasks.filter((task) => task.spec?.kind === options.expectedTaskKind)
        : latestTasks;
      if (!options.requireTask || matchingTasks.length > 0) {
        return {
          manualSubmissionObserved: true,
          companionId,
          backend: latestConnection.backend,
          prompt: options.prompt,
          initialIncomingChatCount: initialIncoming,
          finalIncomingChatCount: Number(latestConnection.incomingChatCount ?? 0),
          initialDeliveredChatCount: initialDelivered,
          finalDeliveredChatCount: Number(latestConnection.deliveredChatCount ?? 0),
          initialRoundTripCount: initialRoundTrips,
          finalRoundTripCount: Number(latestConnection.roundTripCount ?? 0),
          newTasks: latestTasks.map((task) => ({
            id: task.id,
            kind: task.spec?.kind,
            status: task.status,
          })),
          expectedTaskKind: options.expectedTaskKind ?? null,
          mouseOrKeyboardInputUsedByObserver: false,
          windowMutationUsedByObserver: false,
          cursorMutationUsedByObserver: false,
          httpMethodsUsed: ["GET"],
        };
      }
    }
    await sleep(TERMINAL_POLL_MS);
  }

  throw new Error(
    `Timed out waiting for manual Minecraft T chat${options.expectedTaskKind ? ` and task kind ${options.expectedTaskKind}` : ""}`,
  );
}

export function parseOptions(arguments_) {
  const options = {
    baseUrl: process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
    companionId: "",
    prompt: "",
    waitSeconds: 300,
    requireTask: false,
    expectedTaskKind: "",
  };
  for (const argument of arguments_) {
    if (argument.startsWith("--url=")) options.baseUrl = argument.slice("--url=".length);
    else if (argument.startsWith("--companion-id=")) options.companionId = argument.slice("--companion-id=".length);
    else if (argument.startsWith("--prompt=")) options.prompt = argument.slice("--prompt=".length);
    else if (argument.startsWith("--wait-seconds=")) options.waitSeconds = Number(argument.slice("--wait-seconds=".length));
    else if (argument === "--require-task") options.requireTask = true;
    else if (argument.startsWith("--expected-task-kind=")) {
      options.expectedTaskKind = argument.slice("--expected-task-kind=".length);
      options.requireTask = true;
    } else throw new Error(`Unknown argument: ${argument}`);
  }
  if (!options.prompt || options.prompt.length > 256 || /[\r\n\0]/u.test(options.prompt)) {
    throw new Error("--prompt must contain 1-256 characters without control lines");
  }
  if (!Number.isInteger(options.waitSeconds) || options.waitSeconds < 10 || options.waitSeconds > 1800) {
    throw new Error("--wait-seconds must be an integer from 10 through 1800");
  }
  if (options.companionId && !/^[A-Za-z0-9._:-]{1,128}$/u.test(options.companionId)) {
    throw new Error("--companion-id is invalid");
  }
  if (options.expectedTaskKind && !/^[a-z][a-z0-9-]{0,63}$/u.test(options.expectedTaskKind)) {
    throw new Error("--expected-task-kind is invalid");
  }
  return options;
}

async function main() {
  const result = await waitForManualChat(parseOptions(process.argv.slice(2)));
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
