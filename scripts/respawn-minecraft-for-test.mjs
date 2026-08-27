import { pathToFileURL } from "node:url";

const POLL_MILLISECONDS = 200;

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

async function requestJson(base, pathname, fetchImpl, options = {}) {
  const response = await fetchImpl(new URL(pathname, base), {
    ...options,
    headers: options.body === undefined ? options.headers : {
      "content-type": "application/json; charset=utf-8",
      ...options.headers,
    },
  });
  if (!response.ok) {
    throw new Error(`${pathname} returned HTTP ${response.status}`);
  }
  return response.json();
}

function selectCompanion(response, companionId) {
  const matches = (response?.companions ?? []).filter((companion) => (
    companion.connected === true
    && (!companionId || companion.id === companionId)
    && ["forge-1.20.1", "neoforge-1.21.1"].includes(companion.backend)
  ));
  if (matches.length !== 1) {
    throw new Error("Test respawn requires exactly one matching connected Minecraft companion");
  }
  return matches[0];
}

export async function respawnMinecraftForTest(options, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const sleep = dependencies.sleep ?? ((milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)));
  const base = loopbackBase(options.baseUrl);
  const companion = selectCompanion(
    await requestJson(base, "/api/companions", fetchImpl),
    options.companionId,
  );
  const snapshotPath = `/api/companions/${encodeURIComponent(companion.id)}/snapshot`;
  const initial = await requestJson(base, snapshotPath, fetchImpl);
  if (initial.clientUiState === "gameplay") {
    return {
      companionId: companion.id,
      backend: companion.backend,
      initialClientUiState: "gameplay",
      finalClientUiState: "gameplay",
      respawnRequested: false,
      mouseOrKeyboardInputUsed: false,
      windowMutationUsed: false,
      cursorMutationUsed: false,
      httpMethodsUsed: ["GET"],
    };
  }
  if (initial.clientUiState !== "death") {
    throw new Error(`Minecraft must be on the death screen, not ${initial.clientUiState ?? "unknown"}`);
  }

  await requestJson(
    base,
    `/api/companions/${encodeURIComponent(companion.id)}/live-fixtures`,
    fetchImpl,
    {
      method: "POST",
      body: JSON.stringify({ suite: "client-recovery", mode: "respawn" }),
    },
  );

  const deadline = Date.now() + options.waitSeconds * 1_000;
  let latest = initial;
  while (Date.now() < deadline) {
    await sleep(POLL_MILLISECONDS);
    latest = await requestJson(base, snapshotPath, fetchImpl);
    if (latest.clientUiState === "gameplay") {
      return {
        companionId: companion.id,
        backend: companion.backend,
        initialClientUiState: "death",
        finalClientUiState: "gameplay",
        respawnRequested: true,
        mouseOrKeyboardInputUsed: false,
        windowMutationUsed: false,
        cursorMutationUsed: false,
        httpMethodsUsed: ["GET", "POST"],
      };
    }
  }
  throw new Error(`Minecraft did not return to gameplay within ${options.waitSeconds} seconds`);
}

export function parseOptions(arguments_) {
  const options = {
    baseUrl: process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
    companionId: "",
    waitSeconds: 15,
  };
  for (const argument of arguments_) {
    if (argument.startsWith("--url=")) options.baseUrl = argument.slice("--url=".length);
    else if (argument.startsWith("--companion-id=")) options.companionId = argument.slice("--companion-id=".length);
    else if (argument.startsWith("--wait-seconds=")) options.waitSeconds = Number(argument.slice("--wait-seconds=".length));
    else throw new Error(`Unknown argument: ${argument}`);
  }
  if (!Number.isInteger(options.waitSeconds) || options.waitSeconds < 1 || options.waitSeconds > 60) {
    throw new Error("--wait-seconds must be an integer from 1 through 60");
  }
  if (options.companionId && !/^[A-Za-z0-9._:-]{1,128}$/u.test(options.companionId)) {
    throw new Error("--companion-id is invalid");
  }
  loopbackBase(options.baseUrl);
  return options;
}

async function main() {
  const result = await respawnMinecraftForTest(parseOptions(process.argv.slice(2)));
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  });
}
