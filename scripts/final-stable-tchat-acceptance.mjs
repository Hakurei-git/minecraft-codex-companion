import { spawn } from "node:child_process";
import path from "node:path";
import { pathToFileURL } from "node:url";
import WebSocket from "ws";

import {
  fixture as deepMiningFixture,
  parseInspection,
  validateCleanupAcknowledgement,
  validateInspection,
  validateStartingSnapshot,
  validateTask as validateDeepMiningTask,
  waitForTask as waitForDeepMiningTask,
} from "./live-deep-mining-smoke.mjs";
import { finalDiamondTaskSpec } from "./final-smart-tchat-acceptance.mjs";

const projectRoot = path.resolve(import.meta.dirname, "..");
const terminalStatuses = new Set(["succeeded", "failed", "cancelled"]);

// This exact phrasing is intentionally covered by the local deterministic
// parser. It must work while the launcher checkbox has selected stable mode.
export const STABLE_DIAMOND_PROMPT = "给我做一把钻石镐";

export function stableChatSettings(original) {
  return {
    freeChatEnabled: original.freeChatEnabled,
    playerName: original.playerName,
    companionName: original.companionName,
    target: original.target,
    actionMode: "stable",
    tokenBudget: original.tokenBudget,
    persona: original.persona,
  };
}

function restorableChatSettings(original) {
  return {
    freeChatEnabled: original.freeChatEnabled,
    playerName: original.playerName,
    companionName: original.companionName,
    target: original.target,
    actionMode: original.actionMode,
    tokenBudget: original.tokenBudget,
    persona: original.persona,
  };
}

function loopbackBase(raw) {
  const url = new URL(raw);
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(host)) {
    throw new Error("Stable T-chat acceptance only connects to a loopback control service");
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
  if (!response.ok) {
    throw new Error(`${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`);
  }
  return response.json();
}

function runBackgroundChatHelper(arguments_) {
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
      ...arguments_,
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
      else reject(new Error(`Background T chat failed (${code}): ${stderr.slice(-800)}`));
    });
  });
}

function sendBackgroundChat(message) {
  return runBackgroundChatHelper([
    "-MessageUtf8Base64", Buffer.from(message, "utf8").toString("base64"),
    "-RespawnIfDead",
  ]);
}

function normalizeBackgroundMinecraft() {
  return runBackgroundChatHelper(["-NormalizeOnly", "-RespawnIfDead"]);
}

async function waitFor(check, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const result = await check();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

export async function runFinalStableTchatAcceptance({
  baseUrl = process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
  timeoutMs = 900_000,
} = {}) {
  const base = loopbackBase(baseUrl);
  const companions = await requestJson(base, "/api/companions");
  const companion = companions.companions?.find((candidate) => (
    candidate.connected === true
    && candidate.backend === "forge-1.20.1"
    && candidate.embodiment === "in-world-npc"
  ));
  if (!companion?.id) throw new Error("A connected Forge in-world NPC is required");
  if (!companion.ownerName) throw new Error("The Forge NPC has no bound owner name");

  await normalizeBackgroundMinecraft();
  const beforeSnapshot = await requestJson(base, `/api/companions/${encodeURIComponent(companion.id)}/snapshot`);
  if (beforeSnapshot.clientUiState !== "gameplay") throw new Error("Minecraft must be in gameplay state");
  validateStartingSnapshot(beforeSnapshot);

  const originalSettings = await requestJson(
    base,
    `/api/chat/settings?companionId=${encodeURIComponent(companion.id)}`,
  );
  const liveEvents = [];
  let socket = null;
  let setupCompleted = false;
  let settingsChanged = false;
  let task = null;
  let previousTaskIds = new Set();
  let result = null;
  let primaryError = null;
  let cleanup = null;
  const cleanupErrors = [];

  try {
    const configured = await requestJson(base, "/api/chat/settings", {
      method: "PUT",
      body: JSON.stringify(stableChatSettings(originalSettings)),
    });
    settingsChanged = true;
    if (configured.actionMode !== "stable") throw new Error("Stable action mode was not applied");

    await deepMiningFixture(base, companion.id, "setup");
    setupCompleted = true;
    const previousTasks = await requestJson(base, "/api/tasks");
    previousTaskIds = new Set((previousTasks.tasks ?? []).map((record) => record.id));

    const socketUrl = new URL("/api/events", base);
    socketUrl.protocol = socketUrl.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(socketUrl);
    await new Promise((resolve, reject) => {
      socket.once("open", resolve);
      socket.once("error", reject);
    });
    socket.on("message", (data) => {
      try {
        const envelope = JSON.parse(data.toString());
        if (envelope.type === "event") liveEvents.push(envelope.event);
      } catch {
        // Only structured local events are acceptance evidence.
      }
    });

    const chatTransport = await sendBackgroundChat(STABLE_DIAMOND_PROMPT);
    if (!/Sent\s*:\s*True/iu.test(chatTransport)
      || !/PhysicalMouseOrKeyboardInputUsed\s*:\s*False/iu.test(chatTransport)) {
      throw new Error("The background T-chat transport did not return its local-only success evidence");
    }

    task = await waitFor(async () => {
      const response = await requestJson(base, "/api/tasks");
      return response.tasks?.find((candidate) => (
        candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)
      ));
    }, 30_000, "one local deterministic task");

    await new Promise((resolve) => setTimeout(resolve, 1_000));
    const newTasks = (await requestJson(base, "/api/tasks")).tasks?.filter((candidate) => (
      candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)
    )) ?? [];
    if (newTasks.length !== 1) throw new Error(`Expected one new task, received ${newTasks.length}`);
    validateDeepMiningTask(task, companion.id, companion.ownerName);
    const expectedSpec = finalDiamondTaskSpec(companion.ownerName);
    for (const [key, value] of Object.entries(expectedSpec)) {
      if (task.spec?.[key] !== value) {
        throw new Error(`Stable task ${key} mismatch: expected ${value}, received ${task.spec?.[key]}`);
      }
    }

    const localTaskEvent = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "task" && event?.data?.taskId === task.id && event?.data?.requestId
    )), 15_000, "the local deterministic task event");
    const requestId = localTaskEvent.data.requestId;
    const startReply = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "codex-driver"
      && event?.data?.phase === "start"
      && event?.data?.interactionId === requestId
    )), 15_000, "the local Minecraft task-start reply");

    const terminalTask = validateDeepMiningTask(
      await waitForDeepMiningTask(base, task, timeoutMs),
      companion.id,
      companion.ownerName,
    );
    const terminalReply = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "codex-driver"
      && String(event?.data?.message ?? "").startsWith("任务完成")
    )), 15_000, "the local Minecraft terminal reply");
    const inspectionAck = (await deepMiningFixture(base, companion.id, "inspect")).acknowledgement;
    const inspection = validateInspection(parseInspection(inspectionAck.status));

    const smartDecisionEvents = liveEvents.filter((event) => event?.data?.decisionType).length;
    const antigravityEvents = liveEvents.filter((event) => event?.data?.owner === "antigravity-autoplay").length;
    if (smartDecisionEvents !== 0 || antigravityEvents !== 0) {
      throw new Error(`Stable command escaped local execution: decisions=${smartDecisionEvents}, antigravity=${antigravityEvents}`);
    }

    result = {
      ok: true,
      controlServiceLoopbackOnly: true,
      baseUrlInjected: false,
      usedMinecraftTChat: true,
      localChatHistoryRequired: false,
      backgroundChatTransportConfirmed: true,
      actionMode: "stable",
      externalModelRequired: false,
      physicalMouseOrKeyboardInputUsed: false,
      clipboardUsed: false,
      screenshotUsed: false,
      companionName: companion.name,
      newTaskCount: newTasks.length,
      task: {
        kind: terminalTask.spec.kind,
        itemId: terminalTask.spec.itemId,
        count: terminalTask.spec.count,
        requestedBy: terminalTask.spec.requestedBy,
        deliverTo: terminalTask.spec.deliverTo,
        status: terminalTask.status,
      },
      localDeterministicTaskEvents: 1,
      smartDecisionEvents,
      antigravityEvents,
      minecraftReplies: { start: Boolean(startReply), terminal: Boolean(terminalReply) },
      deepMining: inspection,
    };
  } catch (error) {
    primaryError = error;
  } finally {
    socket?.close();
    if (!task?.id && previousTaskIds.size > 0) {
      try {
        const tasks = (await requestJson(base, "/api/tasks")).tasks ?? [];
        task = tasks.find((candidate) => (
          candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)
        )) ?? null;
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (task?.id) {
      try {
        let current = await requestJson(base, `/api/tasks/${encodeURIComponent(task.id)}`);
        if (!terminalStatuses.has(current.status)) {
          await requestJson(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
            method: "POST",
            body: JSON.stringify({ reason: "final stable T-chat acceptance cleanup" }),
          });
          current = await waitFor(async () => {
            const candidate = await requestJson(base, `/api/tasks/${encodeURIComponent(task.id)}`);
            return terminalStatuses.has(candidate.status) ? candidate : null;
          }, 30_000, "the stable acceptance task to stop during cleanup");
        }
        await waitFor(async () => {
          const currentCompanion = (await requestJson(base, "/api/companions")).companions
            ?.find((candidate) => candidate.id === companion.id);
          return currentCompanion && !currentCompanion.activeTaskId ? currentCompanion : null;
        }, 30_000, "the NPC to become idle during cleanup");
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (setupCompleted) {
      try {
        cleanup = validateCleanupAcknowledgement(
          (await deepMiningFixture(base, companion.id, "cleanup")).acknowledgement,
        );
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (settingsChanged) {
      try {
        const restored = await requestJson(base, "/api/chat/settings", {
          method: "PUT",
          body: JSON.stringify(restorableChatSettings(originalSettings)),
        });
        if (restored.actionMode !== originalSettings.actionMode) {
          throw new Error("Original action mode was not restored");
        }
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
  }

  if (primaryError || cleanupErrors.length > 0) {
    const messages = [primaryError, ...cleanupErrors]
      .filter(Boolean)
      .map((error) => error instanceof Error ? error.message : String(error));
    throw new Error(messages.join("; cleanup: "));
  }
  return { ...result, settingsRestored: true, cleanup };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const secondsArgument = process.argv.find((argument) => argument.startsWith("--wait-seconds="));
  const seconds = secondsArgument ? Number(secondsArgument.slice("--wait-seconds=".length)) : 900;
  if (!Number.isFinite(seconds) || seconds < 120 || seconds > 1_200) {
    throw new Error("--wait-seconds must be between 120 and 1200");
  }
  const result = await runFinalStableTchatAcceptance({ timeoutMs: seconds * 1_000 });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}
