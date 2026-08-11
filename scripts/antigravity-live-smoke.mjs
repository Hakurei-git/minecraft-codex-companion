import os from "node:os";
import path from "node:path";
import WebSocket from "ws";
import { AntigravityAgentBridge } from "../apps/control-plane/dist/antigravity-agent-bridge.js";

const baseUrl = process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765";
const companionsResponse = await fetch(`${baseUrl}/api/companions`);
if (!companionsResponse.ok) throw new Error(`Companion service returned HTTP ${companionsResponse.status}`);
const companions = (await companionsResponse.json()).companions ?? [];
const companion = companions.find((candidate) => candidate.backend === "simulator");
if (!companion) throw new Error("Live Antigravity smoke test requires the simulator backend; refusing to touch a real world");

const events = [];
const socketUrl = baseUrl.replace(/^http/u, "ws") + "/api/events";
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
    // Ignore malformed diagnostics; the service protocol itself validates events.
  }
});

const stateDirectory = path.join(process.env.LOCALAPPDATA ?? path.join(os.homedir(), ".local", "share"), "MinecraftCodexCompanion");
const bridge = new AntigravityAgentBridge({ stateDirectory });
const before = await bridge.status();
if (!before.connected || !before.conversationId) throw new Error("Antigravity bound conversation is not ready");
const startedAt = new Date().toISOString();
await bridge.trigger({
  sequence: 1,
  at: new Date().toISOString(),
  companionId: companion.id,
  sender: "PlayerOne",
  message: "这是本地模拟器通道验证。请只调用一次 mc_chat，发送：反重力通道验证通过。不要执行其他游戏动作。",
}, {
  mode: "inherit",
  displayName: "",
  personality: "",
  speakingStyle: "",
  memoryNotes: "",
});
const after = await bridge.status();
if (after.conversationId !== before.conversationId) throw new Error("Antigravity smoke test unexpectedly switched conversations");
await new Promise((resolve) => setTimeout(resolve, 1_000));
socket.close();

const matching = events.filter((event) =>
  event?.type === "chat"
  && event?.companionId === companion.id
  && event?.at >= startedAt
  && String(event?.data?.message ?? "").includes("反重力通道验证通过")
);
if (matching.length !== 1) {
  const newChats = events.filter((event) => event?.type === "chat" && event?.at >= startedAt).map((event) => ({
    owner: String(event?.data?.owner ?? ""),
    message: String(event?.data?.message ?? "").slice(0, 120),
  }));
  throw new Error(`Expected one Antigravity mc_chat reply, received ${matching.length}; new chats=${JSON.stringify(newChats)}`);
}
process.stdout.write(`${JSON.stringify({ ok: true, companionId: companion.id, replies: matching.length, conversationReused: true })}\n`);
