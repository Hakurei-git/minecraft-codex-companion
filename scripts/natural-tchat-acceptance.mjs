import { spawn } from "node:child_process";
import path from "node:path";
import { pathToFileURL } from "node:url";
import WebSocket from "ws";

const projectRoot = path.resolve(import.meta.dirname, "..");

function loopbackBase(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("Natural T-chat acceptance only connects to a loopback control service");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

async function requestJson(base, pathname, options = {}) {
  const response = await fetch(new URL(pathname, base), {
    ...options,
    headers: options.body === undefined ? options.headers : {
      "content-type": "application/json; charset=utf-8",
      ...options.headers,
    },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 400)}`);
  return response.json();
}

function positiveInteger(value, label) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error(`${label} must be a positive integer`);
  return parsed;
}

function runBackgroundChat(message, processId, windowHandle) {
  const arguments_ = [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
    "-MessageUtf8Base64", Buffer.from(message, "utf8").toString("base64"),
    "-RespawnIfDead",
    "-MinecraftProcessId", String(processId),
    "-NativeWindowHandle", String(windowHandle),
  ];
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", arguments_, {
      cwd: projectRoot,
      windowsHide: true,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve(stdout);
      else reject(new Error(`Background Minecraft T chat failed (${code}): ${stderr.slice(-1_000)}`));
    });
  });
}

async function waitFor(check, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

export async function runNaturalTchatAcceptance({
  baseUrl = process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
  backend = process.env.MC_COMPANION_BACKEND ?? "forge-1.20.1",
  expectedOwner = process.env.MC_COMPANION_EXPECTED_OWNER ?? "antigravity-autoplay",
  prompt = process.env.MC_COMPANION_CHAT_PROMPT ?? "请用一句自然的中文告诉我，你会怎样陪我继续冒险？只聊天，不执行动作。",
  processId = positiveInteger(process.env.MC_MINECRAFT_PROCESS_ID, "MC_MINECRAFT_PROCESS_ID"),
  windowHandle = positiveInteger(process.env.MC_MINECRAFT_WINDOW_HANDLE, "MC_MINECRAFT_WINDOW_HANDLE"),
  timeoutMs = 240_000,
} = {}) {
  const base = loopbackBase(baseUrl);
  const companions = await requestJson(base, "/api/companions");
  const companion = companions.companions?.find((candidate) => (
    candidate.connected === true
    && candidate.backend === backend
    && ["in-world-npc", "remote-player"].includes(candidate.embodiment)
  ));
  if (!companion?.id) throw new Error(`A connected ${backend} Minecraft companion is required`);
  const settings = await requestJson(base, `/api/chat/settings?companionId=${encodeURIComponent(companion.id)}`);
  const beforeAntigravity = expectedOwner === "antigravity-autoplay"
    ? await requestJson(base, "/api/antigravity/status")
    : null;
  if (beforeAntigravity && (!beforeAntigravity.connected || !beforeAntigravity.conversationId)) {
    throw new Error("The fixed Antigravity conversation is not connected");
  }
  const beforeCodex = expectedOwner === "codex-driver"
    ? await requestJson(base, "/api/codex/status")
    : null;
  const previousRequestIds = new Set(beforeCodex?.recentRequests?.map((request) => request.id) ?? []);
  const previousMessages = await requestJson(base, "/api/chat/messages?afterSequence=0&limit=100");
  const afterSequence = Number(previousMessages.nextSequence ?? 0);
  const nonce = `NATURAL_T_CHAT_${Date.now().toString(36).toUpperCase()}`;
  const submitted = `${prompt}（验收标识 ${nonce}，请勿复述标识。）`;
  const events = [];
  const socketUrl = new URL("/api/events", base);
  socketUrl.protocol = "ws:";
  const socket = new WebSocket(socketUrl);
  await new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  socket.on("message", (data) => {
    try {
      const envelope = JSON.parse(data.toString());
      if (envelope.type === "event") events.push(envelope.event);
    } catch {
      // Only new structured events from this socket are acceptance evidence.
    }
  });

  try {
    const transport = await runBackgroundChat(submitted, processId, windowHandle);
    if (!/Sent\s*:\s*True/iu.test(transport)
      || !/PhysicalMouseOrKeyboardInputUsed\s*:\s*False/iu.test(transport)) {
      throw new Error("The Minecraft T-chat transport did not return local-only success evidence");
    }
    let acceptedCodexRequest = null;
    const incoming = beforeCodex
      ? await waitFor(async () => {
          const status = await requestJson(base, "/api/codex/status");
          const request = status.recentRequests?.find((candidate) => (
            !previousRequestIds.has(candidate.id) && String(candidate.message ?? "").includes(nonce)
          ));
          if (request?.status === "failed") throw new Error(`Codex request failed: ${request.error ?? "unknown error"}`);
          if (!request) return null;
          acceptedCodexRequest = request;
          return { sequence: null };
        }, 15_000, "the exact natural-language Codex request")
      : await waitFor(async () => {
          const result = await requestJson(base, `/api/chat/messages?afterSequence=${afterSequence}&limit=100`);
          return result.messages?.find((message) => String(message.message ?? "").includes(nonce));
        }, 15_000, "the exact natural-language Antigravity T-chat message");
    const replyEvent = await waitFor(async () => events.find((event) => (
      event?.type === "chat"
      && event?.companionId === companion.id
      && event?.data?.owner === expectedOwner
      && typeof event?.data?.message === "string"
      && event.data.message.trim().length >= 4
    )), timeoutMs, `a natural Minecraft reply owned by ${expectedOwner}`);
    const reply = replyEvent.data.message.trim();
    if (/失败|error|timed out|timeout/iu.test(reply)) throw new Error(`Provider returned a failure reply: ${reply}`);

    let conversationReused = null;
    if (beforeAntigravity) {
      const after = await requestJson(base, "/api/antigravity/status");
      conversationReused = after.conversationId === beforeAntigravity.conversationId;
      if (!conversationReused) throw new Error("Antigravity changed its fixed conversation during natural T chat");
    }
    let codexRequest = null;
    if (beforeCodex) {
      codexRequest = await waitFor(async () => {
        const request = await requestJson(base, `/api/codex/requests/${encodeURIComponent(acceptedCodexRequest.id)}`);
        if (request?.status === "failed") throw new Error(`Codex request failed: ${request.error ?? "unknown error"}`);
        return request?.status === "succeeded" ? request : null;
      }, timeoutMs, "the Codex request to finish");
    }
    return {
      ok: true,
      backend,
      embodiment: companion.embodiment,
      worldId: companion.snapshot?.worldId ?? null,
      usedMinecraftTChat: true,
      incomingSequence: incoming.sequence,
      reply,
      replyOwner: expectedOwner,
      target: settings.target,
      personaMode: settings.persona?.mode ?? null,
      conversationId: beforeAntigravity?.conversationId ?? null,
      conversationReused,
      codexRequestId: codexRequest?.id ?? null,
      codexProviderRole: codexRequest?.providerRole ?? null,
      physicalMouseOrKeyboardInputUsed: false,
      clipboardUsed: false,
      screenshotUsed: false,
    };
  } finally {
    socket.close();
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const report = await runNaturalTchatAcceptance();
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
}
