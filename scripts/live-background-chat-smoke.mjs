import { spawn } from "node:child_process";
import path from "node:path";
import { pathToFileURL } from "node:url";
import WebSocket from "ws";

const projectRoot = path.resolve(import.meta.dirname, "..");

function localBaseUrl(raw) {
  const url = new URL(raw);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(url.hostname.toLowerCase())) {
    throw new Error("Background chat smoke only connects to a loopback control service");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

async function requestJson(baseUrl, pathname, options = {}) {
  const response = await fetch(new URL(pathname, baseUrl), {
    ...options,
    headers: options.body === undefined ? options.headers : {
      "content-type": "application/json; charset=utf-8",
      ...options.headers,
    },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 500)}`);
  return response.json();
}

function runBackgroundChat(message) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  const script = path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1");
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-File", script,
      "-MessageUtf8Base64", encoded,
      "-RespawnIfDead",
    ], {
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
      else reject(new Error(`Background Minecraft chat failed (${code}): ${stderr.slice(-1_000)}`));
    });
  });
}

async function waitFor(check, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

export async function runLiveBackgroundChatSmoke({
  baseUrl: rawBaseUrl = process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
  timeoutMs = 180_000,
} = {}) {
  const baseUrl = localBaseUrl(rawBaseUrl);
  const companions = await requestJson(baseUrl, "/api/companions");
  const companion = companions.companions?.find((candidate) => (
    candidate.connected === true
    && candidate.backend === "forge-1.20.1"
    && candidate.embodiment === "in-world-npc"
  ));
  if (!companion?.id) throw new Error("A connected Forge in-world NPC is required");

  const settings = await requestJson(baseUrl, "/api/chat/settings");
  if (!settings.playerName) throw new Error("Minecraft chat player is not configured");
  if (!settings.freeChatEnabled || settings.target !== "antigravity-mcp") {
    await requestJson(baseUrl, "/api/chat/settings", {
      method: "PUT",
      body: JSON.stringify({
        freeChatEnabled: true,
        playerName: settings.playerName,
        target: "antigravity-mcp",
        persona: settings.persona,
      }),
    });
  }

  const before = await requestJson(baseUrl, "/api/antigravity/status");
  if (!before.connected || !before.conversationId) throw new Error("Antigravity bound conversation is not ready");

  const previous = await requestJson(baseUrl, "/api/chat/messages?afterSequence=0&limit=100");
  const afterSequence = Number(previous.nextSequence ?? 0);
  const nonce = `T_CHAT_${Date.now().toString(36).toUpperCase()}`;
  const expectedReply = `BACKGROUND_CHAT_OK_${nonce}`;
  const prompt = `Antigravity: Minecraft background T chat test ${nonce}. Reply in Minecraft using mc_chat with exactly ${expectedReply}`;
  const events = [];
  const socketUrl = new URL("/api/events", baseUrl);
  socketUrl.protocol = socketUrl.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(socketUrl);
  await new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
  socket.on("message", (data) => {
    try {
      const envelope = JSON.parse(data.toString());
      if (envelope.type === "event") events.push(envelope.event);
      if (envelope.type === "bootstrap" && Array.isArray(envelope.events)) events.push(...envelope.events);
    } catch {
      // Ignore malformed diagnostics; validated chat events are checked below.
    }
  });

  try {
    await runBackgroundChat(prompt);
    const incoming = await waitFor(async () => {
      const result = await requestJson(baseUrl, `/api/chat/messages?afterSequence=${afterSequence}&limit=100`);
      return result.messages?.find((message) => message.message.includes(nonce));
    }, 15_000, "the T-chat message to enter the Forge bridge");

    const replyEvent = await waitFor(async () => events.find((event) => (
      event?.type === "chat"
      && event?.companionId === companion.id
      && String(event?.data?.message ?? "").includes(expectedReply)
    )), timeoutMs, "the Antigravity mc_chat reply");
    const after = await requestJson(baseUrl, "/api/antigravity/status");
    if (after.conversationId !== before.conversationId) throw new Error("Antigravity switched conversations during T-chat smoke");

    return {
      ok: true,
      localOnly: true,
      usedMinecraftTChat: true,
      incomingSequence: incoming.sequence,
      reply: String(replyEvent.data.message),
      replyOwner: String(replyEvent.data.owner ?? ""),
      ownerAttributedToAntigravity: replyEvent.data.owner === "antigravity-autoplay",
      conversationReused: true,
      foregroundInteractionUsed: false,
      physicalMouseOrKeyboardInputUsed: false,
      clipboardUsed: false,
      screenshotUsed: false,
    };
  } finally {
    socket.close();
  }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const secondsArgument = process.argv.find((argument) => argument.startsWith("--wait-seconds="));
  const seconds = secondsArgument ? Number(secondsArgument.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 10 || seconds > 600) throw new Error("--wait-seconds must be between 10 and 600");
  const report = await runLiveBackgroundChatSmoke({ timeoutMs: seconds * 1_000 });
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
}
