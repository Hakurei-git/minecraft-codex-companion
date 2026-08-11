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
export const CODEX_DIAMOND_PROMPT = "给我制作一把钻石镐并交给我";

export function codexChatSettings(original) {
  return {
    freeChatEnabled: true,
    playerName: original.playerName,
    companionName: original.companionName,
    target: "active-provider",
    actionMode: "smart",
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
    throw new Error("Codex T-chat acceptance only connects to a loopback control service");
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

export async function runFinalCodexTchatAcceptance({
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

  const providers = await requestJson(base, "/api/ai/providers");
  const activeProvider = providers.providers?.find((candidate) => candidate.active === true);
  if (activeProvider?.id !== "codex-cli"
    || activeProvider.kind !== "codex-cli"
    || activeProvider.state !== "ready"
    || activeProvider.executable !== true) {
    throw new Error("The built-in Codex CLI provider must be active and ready");
  }

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
  let driverRequestId = null;
  let previousTaskIds = new Set();
  let result = null;
  let primaryError = null;
  let cleanup = null;
  const cleanupErrors = [];

  try {
    const configured = await requestJson(base, "/api/chat/settings", {
      method: "PUT",
      body: JSON.stringify(codexChatSettings(originalSettings)),
    });
    settingsChanged = true;
    if (!configured.freeChatEnabled
      || configured.target !== "active-provider"
      || configured.actionMode !== "smart") {
      throw new Error("Codex smart T-chat settings were not applied");
    }

    await deepMiningFixture(base, companion.id, "setup");
    setupCompleted = true;
    const previousTasks = await requestJson(base, "/api/tasks");
    previousTaskIds = new Set((previousTasks.tasks ?? []).map((record) => record.id));
    const previousDriver = await requestJson(base, "/api/codex/status");
    const previousRequestIds = new Set((previousDriver.recentRequests ?? []).map((record) => record.id));

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
        // Only structured local events are accepted as evidence.
      }
    });

    const chatTransport = await sendBackgroundChat(CODEX_DIAMOND_PROMPT);
    if (!/Sent\s*:\s*True/iu.test(chatTransport)
      || !/PhysicalMouseOrKeyboardInputUsed\s*:\s*False/iu.test(chatTransport)) {
      throw new Error("The background T-chat transport did not return its local-only success evidence");
    }
    const acceptedRequest = await waitFor(async () => {
      const status = await requestJson(base, "/api/codex/status");
      return status.recentRequests?.find((record) => (
        !previousRequestIds.has(record.id)
        && record.message === CODEX_DIAMOND_PROMPT
        && record.providerRole === null
      ));
    }, 15_000, "the exact Codex T-chat command to enter the Codex driver");
    driverRequestId = acceptedRequest.id;

    task = await waitFor(async () => {
      const driverRequest = await requestJson(base, `/api/codex/requests/${encodeURIComponent(driverRequestId)}`);
      if (driverRequest.status === "failed") {
        throw new Error(`Codex smart planning failed: ${driverRequest.error ?? "unknown error"}`);
      }
      const response = await requestJson(base, "/api/tasks");
      return response.tasks?.find((candidate) => (
        candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)
      ));
    }, timeoutMs, "one Codex smart-AI task");

    await new Promise((resolve) => setTimeout(resolve, 1_000));
    const newTasks = (await requestJson(base, "/api/tasks")).tasks?.filter((candidate) => (
      candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)
    )) ?? [];
    if (newTasks.length !== 1) throw new Error(`Expected one new task, received ${newTasks.length}`);
    validateDeepMiningTask(task, companion.id, companion.ownerName);
    for (const [key, value] of Object.entries(finalDiamondTaskSpec(companion.ownerName))) {
      if (task.spec?.[key] !== value) {
        throw new Error(`Codex task ${key} mismatch: expected ${value}, received ${task.spec?.[key]}`);
      }
    }

    const decisionEvent = await waitFor(async () => liveEvents.find((event) => (
      event?.data?.taskId === task.id
      && event?.data?.decisionType === "task"
      && event?.data?.requestId === driverRequestId
    )), 15_000, "the single Codex smart-decision event");
    const requestId = decisionEvent.data.requestId;
    const matchingDecisionEvents = liveEvents.filter((event) => (
      event?.data?.taskId === task.id
      && event?.data?.decisionType === "task"
      && event?.data?.requestId === requestId
    ));
    if (matchingDecisionEvents.length !== 1) {
      throw new Error(`Expected one Codex smart-decision event, received ${matchingDecisionEvents.length}`);
    }
    const driverRequest = await requestJson(base, `/api/codex/requests/${encodeURIComponent(requestId)}`);
    if (driverRequest.status !== "succeeded" || driverRequest.providerRole !== null) {
      throw new Error("The request did not complete through the active Codex provider");
    }
    const startReply = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "codex-driver"
      && event?.data?.phase === "start"
      && event?.data?.interactionId === requestId
    )), 15_000, "the Codex Minecraft task-start reply");

    const terminalTask = validateDeepMiningTask(
      await waitForDeepMiningTask(base, task, timeoutMs),
      companion.id,
      companion.ownerName,
    );
    const terminalReply = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "codex-driver"
      && String(event?.data?.message ?? "").startsWith("任务完成")
    )), 15_000, "the Codex Minecraft terminal reply");

    const inspectionAck = (await deepMiningFixture(base, companion.id, "inspect")).acknowledgement;
    const inspection = validateInspection(parseInspection(inspectionAck.status));
    const antigravityEvents = liveEvents.filter((event) => (
      event?.data?.owner === "antigravity-autoplay"
    )).length;
    if (antigravityEvents !== 0) {
      throw new Error(`Codex acceptance unexpectedly routed ${antigravityEvents} Antigravity events`);
    }

    result = {
      ok: true,
      controlServiceLoopbackOnly: true,
      baseUrlInjected: false,
      usedMinecraftTChat: true,
      provider: { id: activeProvider.id, kind: activeProvider.kind, state: activeProvider.state },
      actionMode: "smart",
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
      codexSmartDecisionEvents: matchingDecisionEvents.length,
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
            body: JSON.stringify({ reason: "final Codex T-chat acceptance cleanup" }),
          });
          current = await waitFor(async () => {
            const candidate = await requestJson(base, `/api/tasks/${encodeURIComponent(task.id)}`);
            return terminalStatuses.has(candidate.status) ? candidate : null;
          }, 30_000, "the Codex acceptance task to stop during cleanup");
        }
        await waitFor(async () => {
          const currentCompanion = (await requestJson(base, "/api/companions")).companions
            ?.find((candidate) => candidate.id === companion.id);
          return currentCompanion && !currentCompanion.activeTaskId ? currentCompanion : null;
        }, 30_000, "the NPC to become idle during Codex cleanup");
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
        if (restored.target !== originalSettings.target
          || restored.actionMode !== originalSettings.actionMode
          || restored.freeChatEnabled !== originalSettings.freeChatEnabled) {
          throw new Error("Original chat routing settings were not restored");
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
  const result = await runFinalCodexTchatAcceptance({ timeoutMs: seconds * 1_000 });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}
