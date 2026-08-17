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
import {
  assertGoalTaskCoverage,
  findNewAgentGoal,
  taskIdsFromPlan,
  validateAgentGoalPlan,
} from "./agent-goal-acceptance.mjs";

const projectRoot = path.resolve(import.meta.dirname, "..");
const terminalStatuses = new Set(["succeeded", "failed", "cancelled"]);
export const FINAL_DIAMOND_PROMPT = "给我制作一把钻石镐并交给我";

export function finalDiamondTaskSpec(ownerName) {
  if (typeof ownerName !== "string" || !ownerName.trim() || ownerName.length > 64) {
    throw new Error("Final diamond-pickaxe acceptance requires the bound owner name");
  }
  return {
    requestedBy: ownerName,
    kind: "craft",
    itemId: "minecraft:diamond_pickaxe",
    count: 1,
    deliverTo: ownerName,
  };
}

function loopbackBase(raw) {
  const url = new URL(raw);
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(host)) {
    throw new Error("Final acceptance only connects to a loopback control service");
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

function sendBackgroundChat(message) {
  const encoded = Buffer.from(message, "utf8").toString("base64");
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
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
      else reject(new Error(`Background T chat failed (${code}): ${stderr.slice(-800)}`));
    });
  });
}

function normalizeBackgroundMinecraft() {
  return new Promise((resolve, reject) => {
    const child = spawn("powershell.exe", [
      "-NoProfile",
      "-ExecutionPolicy", "Bypass",
      "-File", path.join(projectRoot, "scripts", "send-minecraft-chat-background.ps1"),
      "-NormalizeOnly",
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
      else reject(new Error(`Background Minecraft normalization failed (${code}): ${stderr.slice(-800)}`));
    });
  });
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

export async function runFinalSmartTchatAcceptance({
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

  const beforeStatus = await requestJson(base, "/api/antigravity/status");
  if (!beforeStatus.connected || !beforeStatus.conversationId) {
    throw new Error("The bound Antigravity conversation is not ready");
  }
  await normalizeBackgroundMinecraft();
  const beforeSnapshot = await requestJson(base, `/api/companions/${encodeURIComponent(companion.id)}/snapshot`);
  if (beforeSnapshot.clientUiState !== "gameplay") throw new Error("Minecraft must be in gameplay state");
  validateStartingSnapshot(beforeSnapshot);
  const chatSettings = await requestJson(
    base,
    `/api/chat/settings?companionId=${encodeURIComponent(companion.id)}`,
  );
  if (!chatSettings.freeChatEnabled
    || chatSettings.target !== "antigravity-mcp"
    || chatSettings.actionMode !== "smart") {
    throw new Error("Final acceptance requires free chat, Antigravity MCP, and smart AI mode");
  }

  const liveEvents = [];
  let socket = null;
  let setupCompleted = false;
  let task = null;
  let goal = null;
  let goalPlan = null;
  let finalNode = null;
  let agentTaskIds = new Set();
  let previousGoalIds = new Set();
  let newGoals = [];
  let result = null;
  let primaryError = null;
  let cleanup = null;
  const cleanupErrors = [];

  try {
    await deepMiningFixture(base, companion.id, "setup");
    setupCompleted = true;
    const previousTasks = await requestJson(base, "/api/tasks");
    const previousTaskIds = new Set((previousTasks.tasks ?? []).map((record) => record.id));
    const previousGoals = await requestJson(base, "/api/agent/goals");
    previousGoalIds = new Set((previousGoals.goals ?? []).map((record) => record.id));
    const previousMessages = await requestJson(base, "/api/chat/messages?afterSequence=0&limit=100");
    const afterSequence = Number(previousMessages.nextSequence ?? 0);
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
        // Only validated structured events are used as acceptance evidence.
      }
    });

    await sendBackgroundChat(FINAL_DIAMOND_PROMPT);
    await waitFor(async () => {
      const messages = await requestJson(base, `/api/chat/messages?afterSequence=${afterSequence}&limit=100`);
      return messages.messages?.some((message) => message.message === FINAL_DIAMOND_PROMPT);
    }, 15_000, "the exact T-chat command to enter the Forge bridge");

    goal = await waitFor(async () => {
      const response = await requestJson(base, "/api/agent/goals");
      return findNewAgentGoal(response.goals, previousGoalIds, companion.id);
    }, timeoutMs, "one Antigravity Agent goal");
    goalPlan = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/plan`);
    finalNode = validateAgentGoalPlan(goal, goalPlan, finalDiamondTaskSpec(companion.ownerName));

    const decisionEvent = await waitFor(async () => liveEvents.find((event) => (
      typeof event?.data?.taskId === "string"
      && !previousTaskIds.has(event.data.taskId)
      && event?.data?.decisionType === "task"
    )), 15_000, "the single smart-decision event");
    task = await waitFor(async () => {
      const response = await requestJson(base, "/api/tasks");
      return response.tasks?.find((candidate) => (
        candidate.id === decisionEvent.data.taskId
        && candidate.companionId === companion.id
        && !previousTaskIds.has(candidate.id)
      ));
    }, timeoutMs, "the exact first Antigravity Agent child task");
    const interactionId = decisionEvent.data.interactionId;
    const matchingDecisionEvents = liveEvents.filter((event) => (
      event?.data?.taskId === task.id && event?.data?.decisionType === "task"
    ));
    if (matchingDecisionEvents.length !== 1) {
      throw new Error(`Expected one smart-decision event, received ${matchingDecisionEvents.length}`);
    }
    const startReply = liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "antigravity-autoplay"
      && event?.data?.phase === "start"
      && event?.data?.interactionId === interactionId
    ));
    if (!startReply) throw new Error("The Minecraft task-start reply was not delivered");

    const terminalGoal = await waitFor(async () => {
      const current = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}`);
      if (current.status === "failed" || current.status === "cancelled") {
        throw new Error(`Antigravity Agent goal ended ${current.status}: ${current.error?.message ?? current.message ?? "unknown error"}`);
      }
      return current.status === "succeeded" ? current : null;
    }, timeoutMs, "the Antigravity Agent goal to complete");
    newGoals = (await requestJson(base, "/api/agent/goals")).goals?.filter((candidate) => !previousGoalIds.has(candidate.id)) ?? [];
    goalPlan = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/plan`);
    agentTaskIds = taskIdsFromPlan(goalPlan);
    const allTasks = (await requestJson(base, "/api/tasks")).tasks ?? [];
    const tasksById = new Map(allTasks.map((candidate) => [candidate.id, candidate]));
    assertGoalTaskCoverage(goalPlan, tasksById);
    const finalTaskId = goalPlan.nodes.find((node) => node.id === finalNode.id)?.checkpoint?.taskId;
    if (typeof finalTaskId !== "string") throw new Error("The final Antigravity craft node has no task checkpoint");
    const terminalTask = validateDeepMiningTask(tasksById.get(finalTaskId), companion.id, companion.ownerName);
    const terminalReply = await waitFor(async () => liveEvents.find((event) => (
      event?.type === "chat"
      && event?.data?.owner === "antigravity-autoplay"
      && String(event?.data?.message ?? "").startsWith("任务完成")
    )), 15_000, "the Minecraft terminal reply");

    const inspectionAck = (await deepMiningFixture(base, companion.id, "inspect")).acknowledgement;
    const inspection = validateInspection(parseInspection(inspectionAck.status));

    const afterStatus = await requestJson(base, "/api/antigravity/status");
    if (afterStatus.conversationId !== beforeStatus.conversationId) {
      throw new Error("Antigravity switched conversations during final acceptance");
    }

    result = {
      ok: true,
      controlServiceLoopbackOnly: true,
      baseUrlInjected: false,
      usedMinecraftTChat: true,
      physicalMouseOrKeyboardInputUsed: false,
      clipboardUsed: false,
      screenshotUsed: false,
      companionName: companion.name,
      conversationReused: true,
      newGoalCount: newGoals.length,
      newTaskCount: allTasks.filter((candidate) => candidate.companionId === companion.id && !previousTaskIds.has(candidate.id)).length,
      agentTaskCount: agentTaskIds.size,
      goalStatus: terminalGoal.status,
      task: {
        kind: terminalTask.spec.kind,
        itemId: terminalTask.spec.itemId,
        count: terminalTask.spec.count,
        requestedBy: terminalTask.spec.requestedBy,
        deliverTo: terminalTask.spec.deliverTo,
        status: terminalTask.status,
      },
      smartDecisionEvents: matchingDecisionEvents.length,
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
          body: JSON.stringify({ reason: "final smart T-chat acceptance cleanup" }),
        });
        goalPlan = await requestJson(base, `/api/agent/goals/${encodeURIComponent(goal.id)}/plan`).catch(() => goalPlan);
        agentTaskIds = new Set([...agentTaskIds, ...taskIdsFromPlan(goalPlan)]);
        for (const taskId of agentTaskIds) {
          const current = await requestJson(base, `/api/tasks/${encodeURIComponent(taskId)}`);
          if (!terminalStatuses.has(current.status)) {
            await requestJson(base, `/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
              method: "POST",
              body: JSON.stringify({ reason: "final smart T-chat acceptance cleanup" }),
            });
          }
        }
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
  }

  if (primaryError || cleanupErrors.length > 0) {
    const messages = [primaryError, ...cleanupErrors]
      .filter(Boolean)
      .map((error) => error instanceof Error ? error.message : String(error));
    throw new Error(messages.join("; cleanup: "));
  }
  return { ...result, cleanup };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const secondsArgument = process.argv.find((argument) => argument.startsWith("--wait-seconds="));
  const seconds = secondsArgument ? Number(secondsArgument.slice("--wait-seconds=".length)) : 900;
  if (!Number.isFinite(seconds) || seconds < 120 || seconds > 1_200) {
    throw new Error("--wait-seconds must be between 120 and 1200");
  }
  const result = await runFinalSmartTchatAcceptance({ timeoutMs: seconds * 1_000 });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}
