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
import {
  assertGoalTaskCoverage,
  findNewAgentGoal,
  taskIdsFromPlan,
  validateAgentGoalPlan,
  TERMINAL_GOAL_STATUSES,
} from "./agent-goal-acceptance.mjs";

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
  let goal = null;
  let goalPlan = null;
  let finalNode = null;
  let agentTaskIds = new Set();
  let previousTaskIds = new Set();
  let previousGoalIds = new Set();
  let previousRequestIds = new Set();
  let newGoals = [];
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
    const previousGoals = await requestJson(base, "/api/agent/goals");
    previousGoalIds = new Set((previousGoals.goals ?? []).map((record) => record.id));
    const previousDriver = await requestJson(base, "/api/codex/status");
    previousRequestIds = new Set((previousDriver.recentRequests ?? []).map((record) => record.id));

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
        if (envelope.type === "bootstrap" && Array.isArray(envelope.events)) {
          liveEvents.push(...envelope.events);
        }
      } catch {
        // Only structured local events are acceptance evidence.
      }
    });

    const chatTransport = await sendBackgroundChat(STABLE_DIAMOND_PROMPT);
    if (!/Sent\s*:\s*True/iu.test(chatTransport)
      || !/PhysicalMouseOrKeyboardInputUsed\s*:\s*False/iu.test(chatTransport)) {
      throw new Error("The background T-chat transport did not return its local-only success evidence");
    }

    const acceptedRequest = await waitFor(async () => {
      const status = await requestJson(base, "/api/codex/status");
      return status.recentRequests?.find((record) => (
        !previousRequestIds.has(record.id)
        && record.message === STABLE_DIAMOND_PROMPT
        && record.providerRole === null
      ));
    }, 15_000, "the exact stable T-chat command to enter the local driver");

    goal = await waitFor(async () => {
      const response = await requestJson(base, "/api/agent/goals");
      return findNewAgentGoal(response.goals, previousGoalIds, companion.id, STABLE_DIAMOND_PROMPT);
    }, 30_000, "one local Agent goal");
    goalPlan = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/plan`);
    finalNode = validateAgentGoalPlan(goal, goalPlan, finalDiamondTaskSpec(companion.ownerName));

    const localTaskEvent = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "task"
      && event?.data?.requestId === acceptedRequest.id
      && typeof event?.data?.taskId === "string"
    )), 15_000, "the local deterministic task event");
    const requestId = localTaskEvent.data.requestId;
    const firstTaskId = localTaskEvent.data.taskId;
    task = await waitFor(async () => {
      const response = await requestJson(base, "/api/tasks");
      return response.tasks?.find((candidate) => (
        candidate.id === firstTaskId
        && candidate.companionId === companion.id
        && !previousTaskIds.has(candidate.id)
      ));
    }, 30_000, "the exact first Agent child task");
    const startReply = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "codex-driver"
      && event?.data?.phase === "start"
      && event?.data?.interactionId === requestId
    )), 15_000, "the local Minecraft task-start reply");

    const terminalGoal = await waitFor(async () => {
      const current = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}`);
      if (current.status === "failed" || current.status === "cancelled") {
        throw new Error(`Agent goal ended ${current.status}: ${current.error?.message ?? current.message ?? "unknown error"}`);
      }
      return current.status === "succeeded" ? current : null;
    }, timeoutMs, "the local Agent goal to complete");
    newGoals = (await requestJson(base, "/api/agent/goals")).goals?.filter((candidate) => !previousGoalIds.has(candidate.id)) ?? [];
    goalPlan = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/plan`);
    agentTaskIds = taskIdsFromPlan(goalPlan);
    const allTasks = (await requestJson(base, "/api/tasks")).tasks ?? [];
    const tasksById = new Map(allTasks.map((candidate) => [candidate.id, candidate]));
    assertGoalTaskCoverage(goalPlan, tasksById);
    const finalTaskId = goalPlan.nodes.find((node) => node.id === finalNode.id)?.checkpoint?.taskId;
    if (typeof finalTaskId !== "string") throw new Error("The final Agent craft node has no task checkpoint");
    const terminalTask = validateDeepMiningTask(
      tasksById.get(finalTaskId),
      companion.id,
      companion.ownerName,
    );
    const expectedSpec = finalDiamondTaskSpec(companion.ownerName);
    for (const [key, value] of Object.entries(expectedSpec)) {
      if (terminalTask.spec?.[key] !== value) throw new Error(`Stable final task ${key} mismatch: expected ${value}, received ${terminalTask.spec?.[key]}`);
    }
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
      newGoalCount: newGoals.length,
      newTaskCount: allTasks.filter((candidate) => candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)).length,
      agentTaskCount: agentTaskIds.size,
      firstTaskId,
      goalStatus: terminalGoal.status,
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
    if (!goal?.id && previousGoalIds.size > 0) {
      try {
        const goals = (await requestJson(base, "/api/agent/goals")).goals ?? [];
        goal = findNewAgentGoal(goals, previousGoalIds, companion.id);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
    if (goal?.id) {
      try {
        await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/cancel`, {
          method: "POST",
          body: JSON.stringify({ reason: "final stable T-chat acceptance cleanup" }),
        });
        goalPlan = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/plan`).catch(() => goalPlan);
        agentTaskIds = new Set([...agentTaskIds, ...taskIdsFromPlan(goalPlan)]);
        for (const taskId of agentTaskIds) {
          const current = await requestJson(base, `/api/tasks/${encodeURIComponent(taskId)}`);
          if (!terminalStatuses.has(current.status)) {
            await requestJson(base, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
              method: "POST",
              body: JSON.stringify({ reason: "final stable T-chat acceptance cleanup" }),
            });
          }
        }
        if (task?.id && !agentTaskIds.has(task.id)) {
          await requestJson(base, `/api/tasks/${encodeURIComponent(task.id)}/cancel`, {
            method: "POST",
            body: JSON.stringify({ reason: "final stable T-chat acceptance cleanup" }),
          });
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
