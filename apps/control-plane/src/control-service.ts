import { randomUUID } from "node:crypto";
import type {
  AiTaskDecision,
  AiTaskDecisionResult,
  ActionSpec,
  BuildPlan,
  BuildPlanDraft,
  BuildImportRequest,
  ChatMessage,
  ChatSettings,
  ChatSettingsDraft,
  Companion,
  CompanionAction,
  DeclarativeSkill,
  DeclarativeSkillDraft,
  FacilityRecord,
  FacilityType,
  GoalRecord,
  GoalSpec,
  KnowledgeRecord,
  KnowledgeTopic,
  LiveFixtureRequest,
  ObservedFacility,
  PermissionProfile,
  TaskProgressDetails,
  TaskRecord,
  TaskSpec,
  WorkGraph,
  WorkNode,
  WorldSnapshot,
} from "@mc/protocol";
import { sanitizeGameChatText } from "./game-chat-text.js";
import {
  AGENT_PROTOCOL_VERSION,
  capabilitySchema,
  chatMessageSchema,
  facilityRecordSchema,
  goalRecordSchema,
  goalSpecSchema,
  knowledgeTopicSchema,
  workGraphSchema,
} from "@mc/protocol";
import { BackendTaskFailure, type CompanionBackend, type TaskCallbacks } from "./backend.js";
import type { AgentAdvanceResult, AiDecisionMutationOptions, ChatDeliveryOptions, FacilityDraft } from "./control-api.js";
import { AgentJournal, type AgentJournalState } from "./agent-journal.js";
import { BuildPlanStore } from "./build-plan-store.js";
import { importBuildDraft } from "./build-importer.js";
import { ChatSettingsStore } from "./chat-settings-store.js";
import { DeclarativeSkillStore } from "./declarative-skill-store.js";
import { ControlError } from "./errors.js";
import { CompanionEventBus } from "./event-bus.js";
import { GameplayKnowledgeIndex } from "./gameplay-knowledge-index.js";
import { planGoal } from "./goal-planner.js";
import { TaskJournal } from "./task-journal.js";
import { redactSensitiveText } from "./skill-security.js";
import { commitAiTaskDecision } from "./ai-task-decision.js";
import { z } from "zod";

interface RuntimeCompanion {
  backend: CompanionBackend;
  leaseOwner: string | null;
  activeTaskId: string | null;
  inFlightTaskIds: Set<string>;
  queue: string[];
}

const DEFAULT_PERMISSIONS: PermissionProfile = {
  mode: "convenience",
  allowCommands: true,
  allowPvp: false,
  allowBreakingContainers: false,
  requireBuildConfirmation: true,
};

const FACILITY_TYPES = new Set<FacilityType>([
  "home",
  "storage",
  "workstation",
  "farm",
  "ranch",
  "mine",
  "build",
  "dragon-landing",
  "portal",
  "redstone",
  "other",
]);

const TASK_CAPABILITIES: Partial<Record<TaskSpec["kind"], ReturnType<typeof capabilitySchema.parse>>> = {
  follow: "follow",
  guard: "combat",
  move: "move",
  gather: "gather",
  craft: "craft",
  smelt: "smelt",
  farm: "farm",
  store: "storage",
  retrieve: "storage",
  "organize-storage": "storage",
  deliver: "storage",
  eat: "storage",
  "provision-food": "gather",
  ranch: "farm",
  drop: "storage",
  fish: "fish",
  sleep: "sleep",
  explore: "move",
  combat: "combat",
  dragon: "dragon-care",
  build: "build",
};

const MAX_CHAT_MESSAGES = 200;
const TERMINAL_CHAT_OWNERS = new Set(["antigravity-autoplay", "codex-driver"]);
const START_REPLY_DEDUP_TTL_MS = 10 * 60_000;
const MAX_START_REPLY_DEDUP_ENTRIES = 512;
const RANCH_CHAT_FIXTURE_TTL_MS = 30_000;
const RANCH_CHAT_FIXTURE_TAG = "CodexAcceptanceRanchAnimal";
const BUILD_FAILURE_AUTO_RESUME_WINDOW_MS = 3 * 60_000;
// A house-local 12/32-block scan misses fields placed outside a larger home
// compound.  Remembered facilities may be much farther away; this value only
// controls the bounded block scan after the NPC reaches the recorded anchor.
const MIN_OUTDOOR_FARM_SCAN_RADIUS = 96;
const FARM_FACILITY_CLUSTER_RADIUS = 96;
const OUTDOOR_FARM_HOME_CLEARANCE = 48;
// Antigravity can spend 60 seconds waiting for a turn and another recovery
// cycle before it reaches the MCP call. Keep the one-shot decision guard alive
// for the whole turn so a late model cannot bypass it with a direct chat reply.
const AI_DECISION_TTL_MS = 3 * 60_000;
const MAX_PENDING_AI_DECISIONS = 128;

interface PendingAiDecision {
  companionId: string;
  requester: string;
  message: string;
  owner: string;
  expiresAt: number;
  submitting: boolean;
}

function normalizedPlayerName(value: string): string {
  return value.trim().toLocaleLowerCase("en-US");
}

function isBuildTask(spec: TaskSpec): boolean {
  return spec.kind === "build" || spec.kind === "macro" && spec.skillId.startsWith("build.");
}

function canonicalValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .filter(([, child]) => child !== undefined)
      .sort(([left], [right]) => left.localeCompare(right, "en-US"))
      .map(([key, child]) => [key, canonicalValue(child)]));
  }
  return value;
}

function buildRequestFingerprint(spec: TaskSpec): string {
  const semantic = { ...spec } as Record<string, unknown>;
  delete semantic.requestedBy;
  delete semantic.note;
  return JSON.stringify(canonicalValue(semantic));
}

function sameBuildRequest(left: TaskSpec, right: TaskSpec): boolean {
  if (!isBuildTask(left) || !isBuildTask(right)) return false;
  if (normalizedPlayerName(left.requestedBy) !== normalizedPlayerName(right.requestedBy)) return false;
  return buildRequestFingerprint(left) === buildRequestFingerprint(right);
}

function recentlyFinished(task: TaskRecord, now = Date.now()): boolean {
  const reference = Date.parse(task.finishedAt ?? task.startedAt ?? task.createdAt);
  return Number.isFinite(reference) && now - reference <= BUILD_FAILURE_AUTO_RESUME_WINDOW_MS;
}

function isWoodBuildingMaterialSelector(selector: string): boolean {
  const normalized = selector.trim().toLocaleLowerCase("en-US");
  if (normalized === "#minecraft:logs") return true;
  if (normalized.startsWith("#")) {
    return /(?:logs|planks|wooden_(?:slabs|stairs|doors|trapdoors|fences|buttons|pressure_plates))$/u.test(normalized);
  }
  const id = normalized.includes(":") ? normalized.slice(normalized.indexOf(":") + 1) : normalized;
  return id === "bamboo" || id === "bamboo_block" || id === "stripped_bamboo_block"
    || /(?:_log|_wood|_stem|_hyphae|_planks|_slab|_stairs|_door|_trapdoor|_fence|_fence_gate|_button|_pressure_plate)$/u.test(id);
}

function isConflictingMaterialDelivery(spec: TaskSpec, requestedBy: string): boolean {
  if (normalizedPlayerName(spec.requestedBy) !== normalizedPlayerName(requestedBy)) return false;
  if (spec.kind === "macro") {
    if (!new Set(["life.gather-and-deliver", "life.expedition-and-deliver"]).has(spec.skillId)) return false;
    const itemId = String(spec.arguments.itemId ?? "");
    return isWoodBuildingMaterialSelector(itemId);
  }
  if (spec.kind !== "deliver" && spec.kind !== "drop") return false;
  return isWoodBuildingMaterialSelector(spec.itemId);
}

const TASK_PROGRESS_DETAIL_KEYS = [
  "currentStepIndex",
  "currentStepKind",
  "stepProgress",
  "completedCount",
  "targetCount",
  "retainedCount",
  "resolvedPlacementAnchor",
] as const satisfies ReadonlyArray<keyof TaskProgressDetails>;

/** A `count` in the validated child spec is an authoritative requested target. */
function explicitTaskTargetCount(spec: TaskSpec): number | undefined {
  if (!("count" in spec) || typeof spec.count !== "number") return undefined;
  return spec.count;
}

function macroStepProgressDetails(
  index: number,
  spec: TaskSpec,
  stepProgress: number,
  reported?: TaskProgressDetails,
): TaskProgressDetails {
  if (spec.kind === "macro") throw new Error("Macro steps cannot contain another macro");
  return {
    currentStepIndex: index,
    currentStepKind: spec.kind,
    stepProgress,
    completedCount: reported?.completedCount,
    targetCount: reported?.targetCount ?? explicitTaskTargetCount(spec),
    retainedCount: reported?.retainedCount,
    resolvedPlacementAnchor: reported?.resolvedPlacementAnchor,
  };
}

/** Replace, rather than merge, progress facts so a new step cannot inherit stale counts. */
function applyTaskProgressDetails(task: TaskRecord, details?: TaskProgressDetails): void {
  for (const key of TASK_PROGRESS_DETAIL_KEYS) delete task[key];
  if (!details) return;
  for (const key of TASK_PROGRESS_DETAIL_KEYS) {
    const value = details[key];
    if (value !== undefined) Object.assign(task, { [key]: value });
  }
}

export class ControlService {
  readonly events = new CompanionEventBus();
  readonly permissions: PermissionProfile = { ...DEFAULT_PERMISSIONS };
  readonly buildPlans = new BuildPlanStore();
  readonly skills: DeclarativeSkillStore;
  readonly chatSettings: ChatSettingsStore;
  readonly #companions = new Map<string, RuntimeCompanion>();
  readonly #tasks = new Map<string, TaskRecord>();
  readonly #controllers = new Map<string, AbortController>();
  readonly #macroSteps = new Map<string, Array<{ label: string; task: TaskSpec }>>();
  readonly #taskOwners = new Map<string, string>();
  readonly #terminalNotifications = new Set<string>();
  readonly #deliveredStartReplies = new Map<string, number>();
  readonly #armedRanchChatFixtures = new Map<string, number>();
  readonly #taskJournal: TaskJournal;
  readonly #chatMessages: ChatMessage[] = [];
  readonly #pendingAiDecisions = new Map<string, PendingAiDecision>();
  readonly #agentJournal: AgentJournal;
  #agentState: AgentJournalState;
  #chatSequence = 0;

  constructor(options: {
    stateDirectory?: string;
    skills?: DeclarativeSkillStore;
    chatSettings?: ChatSettingsStore;
  } = {}) {
    this.skills = options.skills ?? new DeclarativeSkillStore(options.stateDirectory);
    this.chatSettings = options.chatSettings ?? new ChatSettingsStore(options.stateDirectory);
    this.#taskJournal = new TaskJournal(options.stateDirectory);
    this.#agentJournal = new AgentJournal(options.stateDirectory);
    this.#agentState = this.#agentJournal.load();
    for (const { task, owner, terminalNotified } of this.#taskJournal.load()) {
      this.#tasks.set(task.id, task);
      this.#taskOwners.set(task.id, owner);
      if (terminalNotified) this.#terminalNotifications.add(task.id);
    }
  }

  registerBackend(backend: CompanionBackend): void {
    const existing = this.#companions.get(backend.id);
    if (existing) {
      existing.backend = backend;
      this.#syncSnapshotFacilities(backend.id, backend.snapshot());
      void this.#resumeAgentGoalsForCompanion(backend.id);
      this.events.publish({
        type: "connection",
        companionId: backend.id,
        message: `${backend.describe().name} 已重新连接并恢复任务监听`,
      });
      return;
    }
    this.#companions.set(backend.id, {
      backend,
      leaseOwner: null,
      activeTaskId: null,
      inFlightTaskIds: new Set(),
      queue: [],
    });
    const runtime = this.#companions.get(backend.id)!;
    this.#syncSnapshotFacilities(backend.id, backend.snapshot());
    const unfinished = [...this.#tasks.values()]
      .filter((task) => task.companionId === backend.id && !["succeeded", "failed", "cancelled"].includes(task.status))
      .sort((left, right) => left.createdAt.localeCompare(right.createdAt));
    for (const task of unfinished) {
      if (task.status === "queued") runtime.queue.push(task.id);
      else if (backend.resumeTask) {
        // A task persisted by an older release may still contain the player's
        // underground request position instead of the observed outdoor farm.
        // Re-resolve it after the live snapshot has synchronized facilities.
        if (task.spec.kind === "farm") task.spec = this.#prepareFarmSpec(runtime, task.spec);
        void this.#executeTask(runtime, task, true);
      }
      else this.#markRecoveryUnsupported(task);
    }
    for (const task of this.#tasks.values()) {
      if (task.companionId !== backend.id || !["succeeded", "failed", "cancelled"].includes(task.status)) continue;
      if (!TERMINAL_CHAT_OWNERS.has(this.#taskOwners.get(task.id) ?? "")
        || this.#terminalNotifications.has(task.id)) continue;
      void this.#notifyTaskTerminalOnce(task).finally(() => this.#persistTaskState());
    }
    this.#persistTaskState();
    void this.#pump(runtime);
    void this.#resumeAgentGoalsForCompanion(backend.id);
    this.events.publish({
      type: "connection",
      companionId: backend.id,
      message: `${backend.describe().name} 已通过 ${backend.describe().backend} 连接`,
    });
  }

  listCompanions(): Companion[] {
    return [...this.#companions.values()].map((runtime) => this.#describeRuntime(runtime));
  }

  getCompanion(id: string): Companion {
    return this.#describeRuntime(this.#requireCompanion(id));
  }

  getSnapshot(id: string): WorldSnapshot {
    const snapshot = this.#requireCompanion(id).backend.snapshot();
    this.#syncSnapshotFacilities(id, snapshot);
    return snapshot;
  }

  getChatSettings(companionId?: string): Promise<ChatSettings> {
    const companionName = companionId ? this.#companions.get(companionId)?.backend.describe().name : undefined;
    return this.chatSettings.get(companionName);
  }

  async updateChatSettings(input: ChatSettingsDraft): Promise<ChatSettings> {
    const companionName = input.companionName?.trim()
      || this.#companions.values().next().value?.backend.describe().name
      || "Companion";
    const settings = await this.chatSettings.update({ ...input, companionName });
    this.events.publish({
      type: "system",
      companionId: null,
      message: settings.freeChatEnabled
        ? `自由聊天已开启：${settings.playerName} -> ${settings.target === "antigravity-mcp" ? "反重力 MCP" : "当前 Codex / Claude"}；智能 AI ${settings.actionMode === "smart" ? "已启用" : "未启用"}`
        : `自由聊天已关闭；智能 AI ${settings.actionMode === "smart" ? "已启用" : "未启用"}`,
      data: {
        target: settings.target,
        playerName: settings.playerName,
        actionMode: settings.actionMode,
        tokenBudget: settings.tokenBudget,
      },
    });
    return settings;
  }

  beginAiDecision(message: ChatMessage, owner = "antigravity-autoplay"): string {
    this.#pruneAiDecisions();
    while (this.#pendingAiDecisions.size >= MAX_PENDING_AI_DECISIONS) {
      const oldest = this.#pendingAiDecisions.keys().next().value as string | undefined;
      if (!oldest) break;
      this.#pendingAiDecisions.delete(oldest);
    }
    const interactionId = `mc-ai-${message.sequence}-${randomUUID()}`;
    this.#pendingAiDecisions.set(interactionId, {
      companionId: message.companionId,
      requester: message.sender,
      message: message.message,
      owner,
      expiresAt: Date.now() + AI_DECISION_TTL_MS,
      submitting: false,
    });
    return interactionId;
  }

  cancelAiDecision(interactionId: string): void {
    this.#pendingAiDecisions.delete(interactionId);
  }

  async submitAiDecision(
    interactionId: string,
    decision: AiTaskDecision,
  ): Promise<AiTaskDecisionResult> {
    this.#pruneAiDecisions();
    const pending = this.#pendingAiDecisions.get(interactionId);
    if (!pending) {
      this.events.publish({
        type: "system",
        companionId: null,
        message: "智能 AI 决策提交失败",
        data: {
          interactionId,
          code: "AI_DECISION_NOT_PENDING",
          committed: false,
        },
      });
      throw new ControlError({
        code: "AI_DECISION_NOT_PENDING",
        message: "这条旧的智能请求已经完成、超时或因上游错误失效，不能再次执行",
        statusCode: 404,
        retryable: false,
        suggestedRecovery: "请在 Minecraft 的 T 聊天中重新发送原请求；如果刚恢复网络，可先输入“恢复反重力”立即重连",
      });
    }
    if (pending.submitting) {
      this.events.publish({
        type: "system",
        companionId: pending.companionId,
        message: "智能 AI 决策提交失败",
        data: {
          interactionId,
          code: "AI_DECISION_DUPLICATE",
          committed: false,
        },
      });
      throw new ControlError({
        code: "AI_DECISION_DUPLICATE",
        message: "该智能规划决策正在提交，不能重复执行",
        statusCode: 409,
      });
    }
    pending.submitting = true;
    let committed = false;
    try {
      const result = await commitAiTaskDecision(this, {
        companionId: pending.companionId,
        requester: pending.requester,
        message: pending.message,
        owner: pending.owner,
        interactionId,
      }, decision);
      committed = true;
      await this.sendChat(pending.companionId, result.reply, pending.owner, {
        interactionId,
        phase: ["task", "skill", "retry-build"].includes(result.decisionType) ? "start" : "chat",
      });
      this.#pendingAiDecisions.delete(interactionId);
      this.events.publish({
        type: result.taskId ? "task" : "system",
        companionId: pending.companionId,
        message: `智能 AI 决策已本地提交：${result.decisionType}`,
        data: {
          interactionId,
          decisionType: result.decisionType,
          ...(result.taskId ? { taskId: result.taskId } : {}),
        },
      });
      return result;
    } catch (caught) {
      if (committed) this.#pendingAiDecisions.delete(interactionId);
      else pending.submitting = false;
      const error = caught instanceof Error ? caught : new Error(String(caught));
      const code = caught instanceof ControlError || "code" in error
        ? String((error as Error & { code?: string }).code ?? "CONTROL_ERROR")
        : "CONTROL_ERROR";
      this.events.publish({
        type: "system",
        companionId: pending.companionId,
        message: "智能 AI 决策提交失败",
        data: {
          interactionId,
          decisionType: decision.type,
          code,
          committed,
        },
      });
      throw caught;
    }
  }

  submitGoal(companionId: string, spec: GoalSpec, owner = "agent"): GoalRecord {
    const runtime = this.#requireCompanion(companionId);
    const parsedSpec = goalSpecSchema.parse(spec);
    const now = new Date().toISOString();
    const goal = goalRecordSchema.parse({
      id: randomUUID(),
      worldId: runtime.backend.snapshot().worldId,
      companionId,
      version: AGENT_PROTOCOL_VERSION,
      spec: parsedSpec,
      status: "planning",
      progress: 0,
      message: "Goal recorded and initial work graph generated",
      createdAt: now,
      plannedAt: now,
    });
    const graph = this.#buildInitialWorkGraph(goal, now);
    if (graph.status === "draft" || graph.nodes.some((node) => node.status === "blocked")) {
      goal.plannedAt = null;
      goal.message = "Goal recorded; waiting for a supported local planner route";
    } else {
      goal.message = parsedSpec.taskHints.length > 0
        ? "Goal recorded from task hints"
        : `Goal planned locally with ${Math.max(0, graph.nodes.length - 1)} actionable work node${graph.nodes.length === 2 ? "" : "s"}`;
    }
    this.#agentState = {
      ...this.#agentState,
      goals: [...this.#agentState.goals.filter((entry) => entry.id !== goal.id), goal],
      workGraphs: [
        ...this.#agentState.workGraphs.filter((entry) => entry.goalId !== goal.id),
        graph,
      ],
    };
    this.#persistAgentState();
    this.events.publish({
      type: "system",
      companionId,
      message: `Agent goal accepted: ${goal.spec.title}`,
      data: { goalId: goal.id, owner, workNodes: graph.nodes.length },
    });
    return structuredClone(goal);
  }

  listGoals(): GoalRecord[] {
    return [...this.#agentState.goals]
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt))
      .map((goal) => structuredClone(goal));
  }

  getGoal(id: string): GoalRecord {
    const goal = this.#agentState.goals.find((entry) => entry.id === id);
    if (!goal) {
      throw new ControlError({ code: "GOAL_NOT_FOUND", message: `找不到 Agent 目标 ${id}`, statusCode: 404 });
    }
    return structuredClone(goal);
  }

  getPlan(goalId: string): WorkGraph {
    const graph = this.#agentState.workGraphs.find((entry) => entry.goalId === goalId);
    if (!graph) {
      throw new ControlError({ code: "WORK_GRAPH_NOT_FOUND", message: `找不到目标 ${goalId} 的工作图`, statusCode: 404 });
    }
    return structuredClone(graph);
  }

  async advanceGoal(
    goalId: string,
    owner = "agent-goal",
    options: AiDecisionMutationOptions = {},
  ): Promise<AgentAdvanceResult> {
    const goal = this.#requireMutableGoal(goalId);
    const graph = this.#requireMutableWorkGraph(goalId);
    const now = new Date().toISOString();
    let startedTask: TaskRecord | undefined;
    let advancedNodeId: string | undefined;

    this.#syncWorkGraphFromTasks(goal, graph, now);
    if (["paused", "succeeded", "failed", "cancelled"].includes(goal.status)
      || ["paused", "succeeded", "failed", "cancelled"].includes(graph.status)) {
      this.#persistAgentState();
      return {
        goal: structuredClone(goal),
        plan: structuredClone(graph),
        ...(startedTask ? { task: structuredClone(startedTask) } : {}),
        ...(advancedNodeId ? { advancedNodeId } : {}),
      };
    }

    for (let guard = 0; guard < 32; guard += 1) {
      this.#syncWorkGraphFromTasks(goal, graph, new Date().toISOString());
      if (this.#completeGoalIfDone(goal, graph, new Date().toISOString())) break;
      const ready = this.#nextReadyWorkNode(graph);
      if (!ready) break;

      advancedNodeId = ready.id;
      ready.status = "running";
      ready.attempts += 1;
      ready.checkpoint = {
        ...ready.checkpoint,
        owner,
        startedAt: new Date().toISOString(),
      };
      graph.status = "running";
      graph.updatedAt = new Date().toISOString();
      goal.status = "running";
      goal.startedAt ??= graph.updatedAt;
      goal.activeWorkNodeId = ready.id;
      goal.message = `Running work node: ${ready.label}`;
      this.#persistAgentState();

      try {
        const task = await this.#executeWorkNodeAction(goal, graph, ready, owner, options);
        if (task) {
          startedTask = task;
          break;
        }
      } catch (caught) {
        const message = caught instanceof Error ? caught.message : String(caught);
        ready.status = "failed";
        ready.progress = 0;
        ready.checkpoint = {
          ...ready.checkpoint,
          failedAt: new Date().toISOString(),
          error: redactSensitiveText(message).slice(0, 240),
        };
        graph.status = "failed";
        graph.updatedAt = new Date().toISOString();
        goal.status = "failed";
        goal.finishedAt = graph.updatedAt;
        goal.activeWorkNodeId = null;
        goal.message = redactSensitiveText(message).slice(0, 500);
        goal.error = {
          code: caught instanceof ControlError ? caught.code : "WORK_NODE_FAILED",
          message: goal.message || "Work node failed",
          retryable: caught instanceof ControlError ? caught.retryable ?? true : true,
          failedNodeId: ready.id,
          suggestedRecovery: caught instanceof ControlError ? caught.suggestedRecovery : "观察环境后恢复或重试该目标。",
        };
        break;
      }
    }

    this.#syncWorkGraphFromTasks(goal, graph, new Date().toISOString());
    this.#completeGoalIfDone(goal, graph, new Date().toISOString());
    this.#settleIdleWorkGraph(goal, graph, new Date().toISOString());
    this.#persistAgentState();
    return {
      goal: structuredClone(goal),
      plan: structuredClone(graph),
      ...(startedTask ? { task: structuredClone(startedTask) } : {}),
      ...(advancedNodeId ? { advancedNodeId } : {}),
    };
  }

  pauseGoal(id: string, reason = "Goal paused"): GoalRecord {
    const goal = this.#mutateGoal(id, (record, now) => {
      if (["succeeded", "failed", "cancelled"].includes(record.status)) {
        throw new ControlError({ code: "GOAL_TERMINAL", message: "已结束的 Agent 目标不能暂停", statusCode: 409 });
      }
      record.status = "paused";
      record.message = reason.slice(0, 500);
      for (const graph of this.#agentState.workGraphs.filter((entry) => entry.goalId === id)) {
        graph.status = "paused";
        graph.updatedAt = now;
      }
    });
    this.events.publish({ type: "system", companionId: goal.companionId, message: `Agent goal paused: ${goal.spec.title}`, data: { goalId: id } });
    return goal;
  }

  resumeGoal(id: string): GoalRecord {
    const goal = this.#mutateGoal(id, (record, now) => {
      if (record.status !== "paused") {
        throw new ControlError({ code: "GOAL_NOT_PAUSED", message: "只有暂停中的 Agent 目标可以恢复", statusCode: 409 });
      }
      const graph = this.#agentState.workGraphs.find((entry) => entry.goalId === id);
      record.status = graph?.status === "running" ? "running" : "planning";
      record.message = "Goal resumed";
      if (graph) {
        graph.status = graph.nodes.some((node) => node.status === "running") ? "running" : "ready";
        graph.updatedAt = now;
      }
    });
    this.events.publish({ type: "system", companionId: goal.companionId, message: `Agent goal resumed: ${goal.spec.title}`, data: { goalId: id } });
    return goal;
  }

  cancelGoal(id: string, reason = "Goal cancelled"): GoalRecord {
    const linkedTaskIds = new Set<string>();
    for (const graph of this.#agentState.workGraphs.filter((entry) => entry.goalId === id)) {
      for (const node of graph.nodes) {
        const taskId = node.checkpoint.taskId;
        if (typeof taskId === "string" && taskId.trim()) linkedTaskIds.add(taskId);
      }
    }
    const goal = this.#mutateGoal(id, (record, now) => {
      if (["succeeded", "failed", "cancelled"].includes(record.status)) return;
      record.status = "cancelled";
      record.activeWorkNodeId = null;
      record.message = reason.slice(0, 500);
      record.finishedAt = now;
      for (const graph of this.#agentState.workGraphs.filter((entry) => entry.goalId === id)) {
        graph.status = "cancelled";
        graph.updatedAt = now;
        for (const node of graph.nodes) {
          if (!["succeeded", "failed", "skipped"].includes(node.status)) node.status = "skipped";
        }
      }
    });
    for (const taskId of linkedTaskIds) {
      const task = this.#tasks.get(taskId);
      if (task && !["succeeded", "failed", "cancelled"].includes(task.status)) {
        this.cancelTask(taskId, reason.slice(0, 500));
      }
    }
    this.events.publish({ type: "system", companionId: goal.companionId, message: `Agent goal cancelled: ${goal.spec.title}`, data: { goalId: id } });
    return goal;
  }

  queryKnowledge(query: string, topics: KnowledgeTopic[] = []): KnowledgeRecord[] {
    const parsedTopics = z.array(knowledgeTopicSchema).max(16).parse(topics);
    return new GameplayKnowledgeIndex(this.#agentState.knowledge).query({
      query,
      topics: parsedTopics,
      limit: 64,
    });
  }

  listFacilities(worldId?: string): FacilityRecord[] {
    return this.#agentState.facilities
      .filter((facility) => !worldId || facility.worldId === worldId)
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
      .map((facility) => structuredClone(facility));
  }

  #facilitySignature(input: Pick<FacilityRecord, "worldId" | "dimension" | "type" | "name" | "position" | "tags">): string {
    const tags = [...input.tags].sort((left, right) => left.localeCompare(right, "en-US")).join(",");
    const x = Math.trunc(input.position.x);
    const y = Math.trunc(input.position.y);
    const z = Math.trunc(input.position.z);
    return [
      input.worldId,
      input.dimension,
      input.type,
      input.name.toLocaleLowerCase("en-US"),
      `${x},${y},${z}`,
      tags,
    ].join("\u0000");
  }

  #upsertFacility(input: FacilityDraft, now: string): FacilityRecord {
    const parsed = facilityRecordSchema.parse({
      ...input,
      id: input.id ?? randomUUID(),
      tags: input.tags ?? [],
      properties: input.properties ?? {},
      createdAt: now,
      updatedAt: now,
      lastUsedAt: null,
    });
    let existingIndex = this.#agentState.facilities.findIndex((entry) => (
      entry.id === parsed.id || this.#facilitySignature(entry) === this.#facilitySignature(parsed)
    ));
    // Bed head/foot coordinates and respawn relocation must update one home
    // record instead of creating a second home. A snapshot carries the stable
    // companion id so moving the bed can update the same journal entry even
    // when the new spawn is farther away than the old head/foot pair.
    if (existingIndex < 0 && parsed.type === "home") {
      const companionId = typeof parsed.properties.companionId === "string"
        ? parsed.properties.companionId
        : "";
      existingIndex = this.#agentState.facilities.findIndex((entry) => (
        entry.type === "home"
        && entry.worldId === parsed.worldId
        && entry.dimension === parsed.dimension
        && (
          companionId.length > 0 && entry.properties.companionId === companionId
          || Math.abs(entry.position.x - parsed.position.x) <= 1
            && Math.abs(entry.position.y - parsed.position.y) <= 1
            && Math.abs(entry.position.z - parsed.position.z) <= 1
        )
      ));
      // One-time migration for an old journal that predates companionId. Only
      // claim an unambiguous snapshot-backed home; never merge another
      // player's explicit/manual home merely because it is in the same world.
      if (existingIndex < 0 && companionId.length > 0) {
        const legacy = this.#agentState.facilities
          .map((entry, index) => ({ entry, index }))
          .filter(({ entry }) => entry.type === "home"
            && entry.worldId === parsed.worldId
            && entry.dimension === parsed.dimension
            && entry.properties.source === "snapshot.homeState"
            && typeof entry.properties.companionId !== "string");
        if (legacy.length === 1) existingIndex = legacy[0]!.index;
      }
    }
    const existing = existingIndex >= 0 ? this.#agentState.facilities[existingIndex] : undefined;
    const incomingBoundarySource = typeof parsed.properties.boundarySource === "string"
      ? parsed.properties.boundarySource
      : "";
    const existingBoundarySource = typeof existing?.properties.boundarySource === "string"
      ? existing.properties.boundarySource
      : "";
    const coreRadius = typeof existing?.properties.coreRadius === "number"
      ? existing.properties.coreRadius
      : 24;
    const anchorMovedOutsideManualHome = existing
      ? (existing.position.x - parsed.position.x) ** 2
        + (existing.position.z - parsed.position.z) ** 2 > coreRadius ** 2
      : false;
    const preserveManualBoundary = parsed.type === "home"
      && existingBoundarySource === "manual"
      && incomingBoundarySource !== "manual"
      && parsed.properties.forceBoundaryRefresh !== true
      && !anchorMovedOutsideManualHome;
    const properties: Record<string, unknown> = {
      ...(existing?.properties ?? {}),
      ...parsed.properties,
      ...(preserveManualBoundary ? {
        source: existing?.properties.source,
        boundarySource: "manual",
        confidence: existing?.properties.confidence ?? 1,
      } : {}),
    };
    // This is a one-shot merge instruction, not durable world metadata.
    delete properties.forceBoundaryRefresh;
    const facility = facilityRecordSchema.parse({
      ...parsed,
      id: existing?.id ?? parsed.id,
      createdAt: existing?.createdAt ?? parsed.createdAt,
      updatedAt: now,
      lastUsedAt: existing?.lastUsedAt ?? parsed.lastUsedAt,
      ...(preserveManualBoundary && existing?.bounds ? { bounds: existing.bounds } : {}),
      tags: preserveManualBoundary && existing
        ? [...new Set([...existing.tags, ...parsed.tags])].slice(0, 32)
        : parsed.tags,
      owner: parsed.owner ?? existing?.owner,
      sourceGoalId: parsed.sourceGoalId ?? existing?.sourceGoalId,
      properties,
    });
    let facilities = [...this.#agentState.facilities];
    if (existingIndex >= 0) facilities[existingIndex] = facility;
    else facilities.push(facility);
    if (facility.type === "home") {
      const companionId = typeof facility.properties.companionId === "string"
        ? facility.properties.companionId
        : "";
      facilities = facilities.filter((entry) => {
        if (entry.id === facility.id || entry.type !== "home"
          || entry.worldId !== facility.worldId || entry.dimension !== facility.dimension) return true;
        const sameCompanion = companionId.length > 0 && entry.properties.companionId === companionId;
        const adjacentBedHalf = Math.abs(entry.position.x - facility.position.x) <= 1
          && Math.abs(entry.position.y - facility.position.y) <= 1
          && Math.abs(entry.position.z - facility.position.z) <= 1;
        return !sameCompanion && !adjacentBedHalf;
      });
    }
    this.#agentState = { ...this.#agentState, facilities };
    return facility;
  }

  #syncSnapshotFacilities(companionId: string, snapshot: WorldSnapshot): void {
    const now = new Date().toISOString();
    const drafts = this.#facilityDraftsFromSnapshot(snapshot, companionId);
    if (drafts.length === 0) return;
    let synced = 0;
    for (const draft of drafts) {
      try {
        this.#upsertFacility(draft, now);
        synced += 1;
      } catch (caught) {
        this.events.publish({
          type: "warning",
          companionId,
          message: `Skipped invalid observed facility: ${caught instanceof Error ? redactSensitiveText(caught.message) : "unknown error"}`,
          data: { worldId: snapshot.worldId, facilityName: draft.name, facilityType: draft.type },
        });
      }
    }
    if (synced === 0) return;
    this.#persistAgentState();
    this.events.publish({
      type: "system",
      companionId,
      message: `Agent facilities synchronized from snapshot: ${synced}`,
      data: { worldId: snapshot.worldId, facilityCount: synced },
    });
  }

  #facilityDraftsFromSnapshot(snapshot: WorldSnapshot, companionId: string): FacilityDraft[] {
    const drafts: FacilityDraft[] = [];
    const observed = snapshot.observedFacilities ?? [];
    for (const facility of observed) {
      drafts.push(this.#observedFacilityDraft(snapshot, facility));
    }
    if (snapshot.homeState && !snapshot.homeState.temporary) {
      drafts.push({
        worldId: snapshot.worldId,
        dimension: snapshot.homeState.dimension,
        type: "home",
        name: "Observed home spawn",
        position: snapshot.homeState.position,
        ...(snapshot.homeState.bounds ? { bounds: snapshot.homeState.bounds } : {}),
        tags: ["spawn", "home"],
        properties: {
          source: "snapshot.homeState",
          companionId,
          temporary: false,
          coreRadius: snapshot.homeState.coreRadius,
          boundarySource: snapshot.homeState.boundarySource,
          confidence: snapshot.homeState.confidence,
        },
      });
    }
    if (snapshot.miningState) {
      const itemTag = snapshot.miningState.itemId.split(":").at(-1)?.replace(/[^a-z0-9_-]/giu, "_") || "resource";
      drafts.push({
        worldId: snapshot.worldId,
        dimension: snapshot.dimension,
        type: "mine",
        name: `Observed ${itemTag} mine`,
        position: snapshot.miningState.entrance ?? snapshot.miningState.lastSafeStand ?? snapshot.position,
        tags: ["mining", itemTag],
        properties: {
          source: "snapshot.miningState",
          phase: snapshot.miningState.phase,
          targetY: snapshot.miningState.targetY,
          itemId: snapshot.miningState.itemId,
        },
      });
    }
    if (snapshot.dragonState) {
      drafts.push({
        worldId: snapshot.worldId,
        dimension: snapshot.dimension,
        type: "dragon-landing",
        name: `${snapshot.dragonState.name} landing area`,
        position: snapshot.position,
        tags: ["dragon", snapshot.dragonState.modId],
        properties: {
          source: "snapshot.dragonState",
          entityId: snapshot.dragonState.entityId,
          ownedByPlayer: snapshot.dragonState.ownedByPlayer,
          flying: snapshot.dragonState.flying,
        },
      });
    }
    return drafts;
  }

  #observedFacilityDraft(snapshot: WorldSnapshot, facility: ObservedFacility): FacilityDraft {
    return {
      worldId: snapshot.worldId,
      dimension: snapshot.dimension,
      type: facility.type,
      name: facility.name,
      position: facility.position,
      ...(facility.bounds ? { bounds: facility.bounds } : {}),
      tags: facility.tags,
      ...(facility.owner ? { owner: facility.owner } : {}),
      properties: {
        ...facility.properties,
        source: "snapshot.observedFacilities",
        // A fresh in-world observation is stronger evidence than a previous
        // failed work scan. Without this reset, one temporary miss poisoned a
        // real outdoor field forever because upsert merges old properties.
        ...(facility.type === "farm" && facility.tags.includes("crop") && facility.tags.includes("farmland")
          ? { invalidForCropWork: false, physicallyObservedAt: snapshot.capturedAt }
          : {}),
      },
    };
  }

  registerFacility(input: FacilityDraft): FacilityRecord {
    const now = new Date().toISOString();
    const facility = this.#upsertFacility(input, now);
    this.#persistAgentState();
    this.events.publish({
      type: "system",
      companionId: null,
      message: `Agent facility registered: ${facility.name}`,
      data: { facilityId: facility.id, worldId: facility.worldId, type: facility.type },
    });
    return structuredClone(facility);
  }

  #pruneAiDecisions(now = Date.now()): void {
    for (const [interactionId, pending] of this.#pendingAiDecisions) {
      if (pending.expiresAt <= now) this.#pendingAiDecisions.delete(interactionId);
    }
  }

  #assertAiDecisionMutationAllowed(
    companionId: string,
    interactionId?: string,
  ): void {
    this.#pruneAiDecisions();
    const exact = interactionId ? this.#pendingAiDecisions.get(interactionId) : undefined;
    if (exact?.companionId === companionId && exact.submitting) return;
    const pending = [...this.#pendingAiDecisions.entries()]
      .find(([, candidate]) => candidate.companionId === companionId);
    if (!pending) return;
    throw new ControlError({
      code: "AI_DECISION_TOOL_BLOCKED",
      message: "智能 AI 正在生成本轮唯一决策，不能绕过本地验证直接执行动作",
      statusCode: 409,
      retryable: false,
      suggestedRecovery: `只调用一次 mc_submit_ai_decision，并使用 interactionId=${pending[0]}`,
    });
  }

  listChatMessages(afterSequence = 0, limit = 50): ChatMessage[] {
    const after = Math.max(0, Math.trunc(afterSequence));
    const take = Math.max(1, Math.min(100, Math.trunc(limit)));
    return this.#chatMessages
      .filter((message) => message.sequence > after)
      .slice(0, take)
      .map((message) => ({ ...message }));
  }

  async recordIncomingChat(input: {
    companionId: string;
    sender: string;
    message: string;
    at: string;
  }, explicitlyDirected = false): Promise<ChatMessage | null> {
    const settings = await this.getChatSettings(input.companionId);
    if (normalizedPlayerName(input.sender) !== normalizedPlayerName(settings.playerName)) return null;
    if (!explicitlyDirected && (!settings.freeChatEnabled || settings.target !== "antigravity-mcp")) return null;

    const next = chatMessageSchema.parse({
      sequence: this.#chatSequence + 1,
      at: input.at,
      companionId: input.companionId,
      sender: input.sender.trim(),
      message: redactSensitiveText(input.message.trim()),
    });
    this.#chatSequence = next.sequence;
    this.#chatMessages.push(next);
    if (this.#chatMessages.length > MAX_CHAT_MESSAGES) {
      this.#chatMessages.splice(0, this.#chatMessages.length - MAX_CHAT_MESSAGES);
    }
    return { ...next };
  }

  listTasks(): TaskRecord[] {
    return [...this.#tasks.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  getTask(id: string): TaskRecord {
    const task = this.#tasks.get(id);
    if (!task) {
      throw new ControlError({ code: "TASK_NOT_FOUND", message: `找不到任务 ${id}`, statusCode: 404 });
    }
    return task;
  }

  listBuildPlans(): BuildPlan[] {
    return this.buildPlans.list();
  }

  getBuildPlan(id: string): BuildPlan {
    return this.buildPlans.get(id);
  }

  previewBuild(draft: BuildPlanDraft): BuildPlan {
    const plan = this.buildPlans.preview(draft);
    this.events.publish({
      type: "system",
      companionId: null,
      message: `建筑预览已生成：${plan.name}（${plan.blocks.length} 方块，等待确认）`,
      data: { planId: plan.id, source: plan.source },
    });
    return plan;
  }

  async importBuild(request: BuildImportRequest): Promise<BuildPlan> {
    try {
      return this.previewBuild(await importBuildDraft(request));
    } catch (caught) {
      throw new ControlError({
        code: "BUILD_IMPORT_FAILED",
        message: caught instanceof Error ? caught.message : String(caught),
        statusCode: 400,
      });
    }
  }

  confirmBuild(id: string): BuildPlan {
    const plan = this.buildPlans.confirm(id);
    this.events.publish({
      type: "system",
      companionId: null,
      message: `建筑计划已确认：${plan.name}`,
      data: { planId: plan.id },
    });
    return plan;
  }

  listSkills(): DeclarativeSkill[] {
    return this.skills.list();
  }

  getSkill(id: string): DeclarativeSkill {
    return this.skills.get(id);
  }

  saveSkill(draft: DeclarativeSkillDraft): DeclarativeSkill {
    const skill = this.skills.save(draft);
    this.events.publish({ type: "system", companionId: null, message: `声明式技能已保存：${skill.name}`, data: { skillId: skill.id } });
    return skill;
  }

  removeSkill(id: string): void {
    const skill = this.skills.get(id);
    this.skills.remove(id);
    this.events.publish({ type: "system", companionId: null, message: `声明式技能已删除：${skill.name}`, data: { skillId: id } });
  }

  reviewSkill(id: string, approved: boolean): DeclarativeSkill {
    const skill = this.skills.review(id, approved);
    this.events.publish({
      type: "system",
      companionId: null,
      message: approved ? `技能安全审核已批准：${skill.name}` : `技能安全审核已拒绝：${skill.name}`,
      data: { skillId: id, securityStatus: skill.security.status },
    });
    return skill;
  }

  acquireLease(companionId: string, owner: string, force = false): Companion {
    const runtime = this.#requireCompanion(companionId);
    if (runtime.leaseOwner && runtime.leaseOwner !== owner && !force) {
      throw new ControlError({
        code: "LEASE_CONFLICT",
        message: `${runtime.backend.describe().name} 当前由 ${runtime.leaseOwner} 控制`,
        statusCode: 409,
        retryable: true,
        suggestedRecovery: "先释放现有控制租约，或明确使用 force 接管。",
      });
    }
    runtime.leaseOwner = owner;
    this.events.publish({ type: "system", companionId, message: `${owner} 已取得控制权` });
    return this.#describeRuntime(runtime);
  }

  releaseLease(companionId: string, owner: string): Companion {
    const runtime = this.#requireCompanion(companionId);
    if (runtime.leaseOwner && runtime.leaseOwner !== owner) {
      throw new ControlError({ code: "LEASE_CONFLICT", message: `控制权属于 ${runtime.leaseOwner}`, statusCode: 409 });
    }
    runtime.leaseOwner = null;
    this.events.publish({ type: "system", companionId, message: `${owner} 已释放控制权` });
    return this.#describeRuntime(runtime);
  }

  assignTask(
    companionId: string,
    spec: TaskSpec,
    owner = "dashboard",
    options: { replaceConflictingDelivery?: boolean } & AiDecisionMutationOptions = {},
  ): TaskRecord {
    this.#assertAiDecisionMutationAllowed(companionId, options.aiDecisionInteractionId);
    const runtime = this.#requireCompanion(companionId);
    this.#assertLease(runtime, owner);
    const resolvedSpec: TaskSpec = spec.kind === "macro"
      ? this.#prepareMacroSpec(runtime, spec)
      : spec.kind === "farm"
        ? this.#prepareFarmSpec(runtime, spec)
        : spec.kind === "build"
          ? this.#prepareBuildSpec(runtime, spec)
        : spec.kind === "provision-food"
          ? this.#prepareProvisionFoodSpec(runtime, spec)
        : spec;
    if (isBuildTask(resolvedSpec) && options.replaceConflictingDelivery) {
      for (const candidate of [...this.#tasks.values()]) {
        if (candidate.companionId !== companionId
          || this.#taskOwners.get(candidate.id) !== owner
          || !["queued", "running", "paused"].includes(candidate.status)
          || !isConflictingMaterialDelivery(candidate.spec, resolvedSpec.requestedBy)) continue;
        this.cancelTask(candidate.id, "玩家已纠正为使用材料建造，不再交付材料");
      }
    }
    if (isBuildTask(resolvedSpec)) {
      const matching = [...this.#tasks.values()]
        .filter((candidate) => candidate.companionId === companionId
          && this.#taskOwners.get(candidate.id) === owner
          && sameBuildRequest(candidate.spec, resolvedSpec))
        .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
      const activeBuild = matching.find((candidate) => ["queued", "running", "paused"].includes(candidate.status));
      if (activeBuild) return activeBuild;
      const failedBuild = matching.find((candidate) => candidate.status === "failed"
        && candidate.error?.retryable === true
        && recentlyFinished(candidate));
      if (failedBuild) return this.#requeueBuildTask(runtime, failedBuild, owner);
    }
    const ranchChatFixture = resolvedSpec.kind === "macro"
      && resolvedSpec.skillId === "life.establish-ranch"
      && this.#consumeRanchChatFixture(companionId);
    const macroSteps = resolvedSpec.kind === "macro"
      ? this.#resolveMacroSteps(resolvedSpec, ranchChatFixture)
      : null;
    if (macroSteps) macroSteps.forEach((step) => this.#assertTaskAllowed(runtime, step.task));
    else this.#assertTaskAllowed(runtime, resolvedSpec);
    const now = new Date().toISOString();
    const task: TaskRecord = {
      id: randomUUID(),
      companionId,
      spec: resolvedSpec,
      status: "queued",
      progress: 0,
      message: "等待执行",
      createdAt: now,
      startedAt: null,
      finishedAt: null,
      error: null,
    };
    this.#tasks.set(task.id, task);
    this.#taskOwners.set(task.id, owner);
    if (macroSteps) this.#macroSteps.set(task.id, macroSteps);
    runtime.queue.push(task.id);
    this.#persistTaskState();
    this.events.publish({ type: "task", companionId, message: `任务已加入队列：${resolvedSpec.kind}`, data: { taskId: task.id } });
    void this.#pump(runtime);
    return task;
  }

  /** Requeues the same task id so the Forge NPC can restore its retained build checkpoint. */
  retryLatestBuildTask(
    companionId: string,
    owner = "dashboard",
    requestedBy?: string,
    options: AiDecisionMutationOptions = {},
  ): TaskRecord {
    this.#assertAiDecisionMutationAllowed(companionId, options.aiDecisionInteractionId);
    const runtime = this.#requireCompanion(companionId);
    this.#assertLease(runtime, owner);
    const matching = [...this.#tasks.values()]
      .filter((task) => {
        if (task.companionId !== companionId || !isBuildTask(task.spec)) return false;
        const taskOwner = this.#taskOwners.get(task.id) ?? "local";
        const legacyOwnerMatch = taskOwner === "local" && requestedBy !== undefined;
        if (taskOwner !== owner && !legacyOwnerMatch) return false;
        return requestedBy === undefined
          || normalizedPlayerName(task.spec.requestedBy) === normalizedPlayerName(requestedBy);
      })
      .sort((left, right) => (
        right.finishedAt ?? right.startedAt ?? right.createdAt
      ).localeCompare(left.finishedAt ?? left.startedAt ?? left.createdAt));
    const alreadyActive = matching.find((task) => ["queued", "running", "paused"].includes(task.status));
    if (alreadyActive) return alreadyActive;
    const task = matching.find((candidate) => candidate.status === "failed" && candidate.error?.retryable === true);
    if (!task) {
      const permanentFailure = matching.find((candidate) => candidate.status === "failed");
      throw new ControlError({
        code: permanentFailure ? "BUILD_CHECKPOINT_NOT_RETRYABLE" : "BUILD_CHECKPOINT_NOT_FOUND",
        message: permanentFailure
          ? "最近的建造失败不可重试，请根据失败原因重新选择位置或方案"
          : "没有属于当前玩家和控制入口的可恢复建造任务，请先从建造菜单选择建筑",
        statusCode: permanentFailure ? 409 : 404,
        retryable: false,
      });
    }
    return this.#requeueBuildTask(runtime, task, owner);
  }

  /** Requeues one retryable task with the same id, regardless of task kind. */
  retryTask(
    taskId: string,
    owner = "dashboard",
    options: AiDecisionMutationOptions = {},
  ): TaskRecord {
    const task = this.getTask(taskId);
    this.#assertAiDecisionMutationAllowed(task.companionId, options.aiDecisionInteractionId);
    const runtime = this.#requireCompanion(task.companionId);
    this.#assertLease(runtime, owner);
    const taskOwner = this.#taskOwners.get(task.id) ?? "local";
    if (taskOwner !== owner && taskOwner !== "local") {
      throw new ControlError({
        code: "TASK_OWNER_MISMATCH",
        message: "只能由原控制入口重试该任务",
        statusCode: 409,
        retryable: false,
      });
    }
    if (["queued", "running", "paused"].includes(task.status)) return task;
    if (task.status !== "failed" || task.error?.retryable !== true) {
      throw new ControlError({
        code: "TASK_NOT_RETRYABLE",
        message: "该任务当前不可重试",
        statusCode: 409,
        retryable: false,
      });
    }
    return this.#requeueBuildTask(runtime, task, owner);
  }

  #requeueBuildTask(runtime: RuntimeCompanion, task: TaskRecord, owner: string): TaskRecord {
    if (task.spec.kind === "macro") {
      const steps = this.#resolveMacroSteps(task.spec);
      steps.forEach((step) => this.#assertTaskAllowed(runtime, step.task));
      this.#macroSteps.set(task.id, steps);
    } else {
      if (task.spec.kind === "farm") task.spec = this.#prepareFarmSpec(runtime, task.spec);
      this.#assertTaskAllowed(runtime, task.spec);
    }
    task.status = "queued";
    task.message = `等待从失败点恢复（${Math.round(task.progress * 100)}%）`;
    task.startedAt = null;
    task.finishedAt = null;
    task.error = null;
    this.#taskOwners.set(task.id, owner);
    this.#terminalNotifications.delete(task.id);
    runtime.queue = runtime.queue.filter((id) => id !== task.id);
    runtime.queue.push(task.id);
    this.#syncAgentGoalsLinkedToTask(task, new Date().toISOString());
    this.#persistTaskState();
    this.events.publish({
      type: "task",
      companionId: runtime.backend.id,
      message: `建造任务已从失败点重新入队：${task.id}`,
      data: { taskId: task.id, progress: task.progress, resumed: true },
    });
    void this.#pump(runtime);
    return task;
  }

  #syncAgentGoalsLinkedToTask(task: TaskRecord, now: string): void {
    let changed = false;
    for (const graph of this.#agentState.workGraphs) {
      if (!graph.nodes.some((node) => this.#checkpointString(node, "taskId") === task.id)) continue;
      const goal = this.#agentState.goals.find((candidate) => candidate.id === graph.goalId);
      if (!goal) continue;
      this.#syncWorkGraphFromTasks(goal, graph, now);
      changed = true;
    }
    if (changed) this.#persistAgentState();
  }

  async sendChat(
    companionId: string,
    message: string,
    owner = "dashboard",
    options: ChatDeliveryOptions = {},
  ): Promise<void> {
    this.#assertAiDecisionMutationAllowed(companionId, options.interactionId);
    const runtime = this.#requireCompanion(companionId);
    const safeMessage = sanitizeGameChatText(message);
    const interactionId = options.interactionId?.trim();
    const dedupKey = options.phase === "start" && interactionId
      ? `${companionId}\u0000${owner}\u0000${interactionId}`
      : null;
    if (dedupKey) {
      const now = Date.now();
      this.#pruneDeliveredStartReplies(now);
      if (this.#deliveredStartReplies.has(dedupKey)) {
        this.events.publish({
          type: "system",
          companionId,
          message: "已合并同一玩家输入的重复任务启动回复",
          data: { owner, interactionId, phase: "start", duplicateReplySuppressed: true },
        });
        return;
      }
      // Reserve before awaiting the backend so concurrent MCP calls for the
      // same turn cannot both pass the gate. A failed delivery releases it.
      this.#deliveredStartReplies.set(dedupKey, now);
    }
    try {
      await runtime.backend.sendChat(safeMessage);
    } catch (caught) {
      if (dedupKey) this.#deliveredStartReplies.delete(dedupKey);
      throw caught;
    }
    this.events.publish({
      type: "chat",
      companionId,
      message: `${runtime.backend.describe().name}: ${safeMessage}`,
      data: {
        message: safeMessage,
        owner,
        ...(interactionId ? { interactionId } : {}),
        ...(options.phase ? { phase: options.phase } : {}),
      },
    });
  }

  #pruneDeliveredStartReplies(now: number): void {
    for (const [key, deliveredAt] of this.#deliveredStartReplies) {
      if (now - deliveredAt > START_REPLY_DEDUP_TTL_MS) this.#deliveredStartReplies.delete(key);
    }
    while (this.#deliveredStartReplies.size >= MAX_START_REPLY_DEDUP_ENTRIES) {
      const oldest = this.#deliveredStartReplies.keys().next().value as string | undefined;
      if (!oldest) break;
      this.#deliveredStartReplies.delete(oldest);
    }
  }

  async controlCompanion(
    companionId: string,
    action: CompanionAction,
    options: AiDecisionMutationOptions = {},
  ): Promise<Companion> {
    this.#assertAiDecisionMutationAllowed(companionId, options.aiDecisionInteractionId);
    const runtime = this.#requireCompanion(companionId);
    if (!runtime.backend.control) {
      throw new ControlError({ code: "NPC_CONTROL_UNSUPPORTED", message: "该角色不是可召回的游戏内 NPC", statusCode: 422 });
    }
    await runtime.backend.control(action);
    this.events.publish({ type: "system", companionId, message: `NPC 控制：${action}` });
    return this.#describeRuntime(runtime);
  }

  async runLiveFixture(companionId: string, fixture: LiveFixtureRequest): Promise<void> {
    const runtime = this.#requireCompanion(companionId);
    if (!runtime.backend.sendBridgeCommand) {
      throw new ControlError({
        code: "LIVE_FIXTURE_UNSUPPORTED",
        message: "This companion backend does not support live fixtures",
        statusCode: 422,
      });
    }
    await runtime.backend.sendBridgeCommand({ type: "live-fixture", ...fixture });
  }

  armNextRanchChatFixture(companionId: string): void {
    this.#requireCompanion(companionId);
    this.#armedRanchChatFixtures.set(companionId, Date.now() + RANCH_CHAT_FIXTURE_TTL_MS);
  }

  isRanchChatFixtureArmed(companionId: string): boolean {
    const expiresAt = this.#armedRanchChatFixtures.get(companionId);
    if (expiresAt === undefined) return false;
    if (expiresAt < Date.now()) {
      this.#armedRanchChatFixtures.delete(companionId);
      return false;
    }
    return true;
  }

  clearRanchChatFixture(companionId: string): void {
    this.#armedRanchChatFixtures.delete(companionId);
  }

  cancelTask(taskId: string, reason = "用户取消"): TaskRecord {
    const task = this.getTask(taskId);
    if (["succeeded", "failed", "cancelled"].includes(task.status)) {
      if (task.status === "failed" && task.error?.retryable === true && isBuildTask(task.spec)) {
        const runtime = this.#requireCompanion(task.companionId);
        if (runtime.backend.sendBridgeCommand) {
          void runtime.backend.sendBridgeCommand({
            type: "cancel-task",
            taskId,
            reason,
          }).catch(() => undefined);
        }
      }
      return task;
    }
    const runtime = this.#requireCompanion(task.companionId);
    const wasInFlight = runtime.inFlightTaskIds.has(taskId);
    this.#controllers.get(taskId)?.abort(new Error(reason));
    task.status = "cancelled";
    task.message = reason;
    task.finishedAt = new Date().toISOString();
    runtime.queue = runtime.queue.filter((id) => id !== taskId);
    runtime.inFlightTaskIds.delete(taskId);
    this.#macroSteps.delete(taskId);
    if (runtime.activeTaskId === taskId) {
      runtime.activeTaskId = runtime.inFlightTaskIds.values().next().value ?? null;
    }
    this.events.publish({ type: "task", companionId: task.companionId, message: `任务已取消：${reason}`, data: { taskId } });
    if (!wasInFlight) {
      void this.#notifyTaskTerminalOnce(task).finally(() => this.#persistTaskState());
    }
    this.#persistTaskState();
    void this.#pump(runtime);
    return task;
  }

  async emergencyStop(disconnect = false): Promise<void> {
    const stoppedAt = new Date().toISOString();
    for (const goal of this.#agentState.goals) {
      if (["succeeded", "failed", "cancelled"].includes(goal.status)) continue;
      goal.status = "cancelled";
      goal.activeWorkNodeId = null;
      goal.message = "紧急停止";
      goal.finishedAt = stoppedAt;
      goal.error = null;
      const graph = this.#agentState.workGraphs.find((entry) => entry.goalId === goal.id);
      if (!graph) continue;
      graph.status = "cancelled";
      graph.updatedAt = stoppedAt;
      for (const node of graph.nodes) {
        if (!["succeeded", "failed", "skipped"].includes(node.status)) node.status = "skipped";
      }
    }
    this.#persistAgentState();
    for (const controller of this.#controllers.values()) controller.abort(new Error("紧急停止"));
    this.#controllers.clear();
    this.#macroSteps.clear();
    for (const task of this.#tasks.values()) {
      if (task.status === "running" || task.status === "queued") {
        task.status = "cancelled";
        task.message = "紧急停止";
        task.finishedAt = new Date().toISOString();
      }
    }
    await Promise.all([...this.#companions.values()].map(async (runtime) => {
      runtime.queue = [];
      runtime.activeTaskId = null;
      runtime.inFlightTaskIds.clear();
      await runtime.backend.stop(disconnect);
    }));
    this.#persistTaskState();
    this.events.publish({ type: "warning", companionId: null, message: disconnect ? "所有角色已急停并断开" : "所有角色已急停" });
  }

  #describeRuntime(runtime: RuntimeCompanion): Companion {
    const base = runtime.backend.describe();
    return {
      ...base,
      leaseOwner: runtime.leaseOwner,
      activeTaskId: runtime.activeTaskId,
      snapshot: runtime.backend.snapshot(),
    };
  }

  #requireCompanion(id: string): RuntimeCompanion {
    const runtime = this.#companions.get(id);
    if (!runtime) {
      throw new ControlError({ code: "COMPANION_NOT_FOUND", message: `找不到角色 ${id}`, statusCode: 404 });
    }
    return runtime;
  }

  #assertLease(runtime: RuntimeCompanion, owner: string): void {
    if (runtime.leaseOwner && runtime.leaseOwner !== owner) {
      throw new ControlError({ code: "LEASE_CONFLICT", message: `角色由 ${runtime.leaseOwner} 控制`, statusCode: 409, retryable: true });
    }
  }

  #assertCapability(runtime: RuntimeCompanion, spec: TaskSpec): void {
    const needed = TASK_CAPABILITIES[spec.kind];
    if (needed && !runtime.backend.capabilities().includes(needed)) {
      throw new ControlError({
        code: "UNSUPPORTED_CAPABILITY",
        message: `${runtime.backend.describe().name} 不支持 ${needed}`,
        statusCode: 422,
        suggestedRecovery: "选择支持该能力的角色或改用更基础的任务。",
      });
    }
  }

  #assertTaskAllowed(runtime: RuntimeCompanion, spec: TaskSpec): void {
    this.#assertCapability(runtime, spec);
    if (spec.kind !== "build" || !this.permissions.requireBuildConfirmation) return;
    const plan = this.buildPlans.get(spec.planId);
    if (!plan.confirmed) {
      throw new ControlError({
        code: "BUILD_CONFIRMATION_REQUIRED",
        message: `建筑计划 ${plan.name} 尚未确认`,
        statusCode: 409,
        suggestedRecovery: "先检查建筑预览和材料清单，再调用确认建筑工具。",
      });
    }
  }

  #prepareMacroSpec(
    runtime: RuntimeCompanion,
    spec: Extract<TaskSpec, { kind: "macro" }>,
  ): Extract<TaskSpec, { kind: "macro" }> {
    const snapshot = runtime.backend.snapshot();
    const placementAnchor = spec.placementAnchor
      ?? (spec.skillId === "build.crop-farm"
        ? this.#outdoorFarmBuildAnchor(snapshot)
        : snapshot.position);
    const homeBounds = spec.homeBounds ?? this.#rememberedHomeBounds(snapshot);
    return {
      ...spec,
      placementAnchor,
      ...(homeBounds ? { homeBounds } : {}),
      materialMode: spec.materialMode
        ?? snapshot.materialMode
        ?? (snapshot.gameMode === "creative" ? "creative" : "survival"),
    };
  }

  #rememberedHomeBounds(snapshot: WorldSnapshot): NonNullable<FacilityDraft["bounds"]> | undefined {
    const remembered = this.#agentState.facilities
      .filter((facility) => facility.worldId === snapshot.worldId)
      .filter((facility) => facility.dimension === snapshot.dimension)
      .filter((facility) => facility.type === "home" && facility.bounds)
      .sort((left, right) => {
        const leftManual = left.properties.boundarySource === "manual" ? 1 : 0;
        const rightManual = right.properties.boundarySource === "manual" ? 1 : 0;
        if (leftManual !== rightManual) return rightManual - leftManual;
        return (right.lastUsedAt ?? right.updatedAt).localeCompare(left.lastUsedAt ?? left.updatedAt);
      })[0];
    return remembered?.bounds;
  }

  #prepareProvisionFoodSpec(
    runtime: RuntimeCompanion,
    spec: Extract<TaskSpec, { kind: "provision-food" }>,
  ): Extract<TaskSpec, { kind: "provision-food" }> {
    if (spec.farmAnchor || spec.source !== "auto") return spec;
    const snapshot = runtime.backend.snapshot();
    const reference = snapshot.homeState?.dimension === snapshot.dimension
      ? snapshot.homeState.position
      : snapshot.ownerPosition ?? snapshot.position;
    const farm = this.#agentState.facilities
      .filter((facility) => facility.worldId === snapshot.worldId)
      .filter((facility) => facility.dimension === snapshot.dimension)
      .filter((facility) => facility.type === "farm")
      .filter((facility) => !this.#isInvalidCropFarm(facility))
      .sort((left, right) => {
        const leftDistance = (left.position.x - reference.x) ** 2 + (left.position.z - reference.z) ** 2;
        const rightDistance = (right.position.x - reference.x) ** 2 + (right.position.z - reference.z) ** 2;
        return leftDistance - rightDistance;
      })[0];
    return farm ? { ...spec, farmAnchor: farm.position } : spec;
  }

  #prepareBuildSpec(
    runtime: RuntimeCompanion,
    spec: Extract<TaskSpec, { kind: "build" }>,
  ): Extract<TaskSpec, { kind: "build" }> {
    if (spec.homeBounds) return spec;
    const bounds = this.#rememberedHomeBounds(runtime.backend.snapshot());
    return bounds ? { ...spec, homeBounds: bounds } : spec;
  }

  /**
   * A newly-created field belongs outside the current house. Prefer a point
   * beyond the remembered home/build/storage cluster; Forge performs the final
   * terrain and open-sky validation before placing the blueprint.
   */
  #outdoorFarmBuildAnchor(snapshot: WorldSnapshot): FacilityDraft["position"] {
    const base = snapshot.homeState?.dimension === snapshot.dimension
      ? snapshot.homeState.position
      : snapshot.ownerPosition ?? snapshot.position;
    const diagonal = Math.round(OUTDOOR_FARM_HOME_CLEARANCE / Math.sqrt(2));
    const offsets = [
      { x: OUTDOOR_FARM_HOME_CLEARANCE, z: 0 },
      { x: -OUTDOOR_FARM_HOME_CLEARANCE, z: 0 },
      { x: 0, z: OUTDOOR_FARM_HOME_CLEARANCE },
      { x: 0, z: -OUTDOOR_FARM_HOME_CLEARANCE },
      { x: diagonal, z: diagonal },
      { x: diagonal, z: -diagonal },
      { x: -diagonal, z: diagonal },
      { x: -diagonal, z: -diagonal },
    ];
    const residential = this.#agentState.facilities
      .filter((facility) => facility.worldId === snapshot.worldId)
      .filter((facility) => facility.dimension === snapshot.dimension)
      .filter((facility) => ["home", "build", "storage"].includes(facility.type));
    const candidates = offsets.map((offset, order) => {
      const position = { x: base.x + offset.x, y: base.y, z: base.z + offset.z };
      const overlapsExpandedBounds = residential.some((facility) => {
        if (!facility.bounds) return false;
        const margin = 12;
        return position.x >= facility.bounds.min.x - margin
          && position.x <= facility.bounds.max.x + margin
          && position.z >= facility.bounds.min.z - margin
          && position.z <= facility.bounds.max.z + margin;
      });
      const nearestResidentialDistance = residential.length === 0
        ? Number.POSITIVE_INFINITY
        : Math.min(...residential.map((facility) => (
          (facility.position.x - position.x) ** 2
          + (facility.position.z - position.z) ** 2
        )));
      const travelDistance = (snapshot.position.x - position.x) ** 2
        + (snapshot.position.z - position.z) ** 2;
      return { position, overlapsExpandedBounds, nearestResidentialDistance, travelDistance, order };
    });
    candidates.sort((left, right) => {
      if (left.overlapsExpandedBounds !== right.overlapsExpandedBounds) {
        return left.overlapsExpandedBounds ? 1 : -1;
      }
      if (left.nearestResidentialDistance !== right.nearestResidentialDistance) {
        return right.nearestResidentialDistance - left.nearestResidentialDistance;
      }
      if (left.travelDistance !== right.travelDistance) return left.travelDistance - right.travelDistance;
      return left.order - right.order;
    });
    return candidates[0]!.position;
  }

  /**
   * Routes farm work back to a remembered field instead of limiting every
   * maintenance pass to the NPC's current room. Horizontal distance is used
   * deliberately: build placement can lift an underground request anchor to
   * the safe surface while preserving the original X/Z coordinates.
   */
  #prepareFarmSpec(
    runtime: RuntimeCompanion,
    spec: Extract<TaskSpec, { kind: "farm" }>,
  ): Extract<TaskSpec, { kind: "farm" }> {
    const snapshot = runtime.backend.snapshot();
    if (spec.lockPlacementAnchor && spec.placementAnchor) {
      return {
        ...spec,
        radius: Math.max(MIN_OUTDOOR_FARM_SCAN_RADIUS, spec.radius),
      };
    }
    const reference = spec.placementAnchor ?? snapshot.position;
    const candidates = this.#agentState.facilities
      .filter((facility) => facility.worldId === snapshot.worldId)
      .filter((facility) => facility.dimension === snapshot.dimension)
      .filter((facility) => facility.type === "farm")
      .filter((facility) => facility.properties.invalidForCropWork !== true)
      // Crop work must not accidentally route to a remembered tree farm. Old
      // records without subtype tags remain eligible for backwards
      // compatibility, while explicit crop/farmland records are preferred by
      // the distance and recency ordering below.
      .filter((facility) => !facility.tags.includes("tree-farm")
        || facility.tags.includes("crop")
        || facility.tags.includes("farmland"))
      .sort((left, right) => {
        const leftDistance = (left.position.x - reference.x) ** 2
          + (left.position.z - reference.z) ** 2;
        const rightDistance = (right.position.x - reference.x) ** 2
          + (right.position.z - reference.z) ** 2;
        if (leftDistance !== rightDistance) return leftDistance - rightDistance;
        return (right.lastUsedAt ?? right.updatedAt).localeCompare(left.lastUsedAt ?? left.updatedAt);
      });
    const nearest = candidates[0];
    // Old macro builds could record an underground request anchor even after
    // Minecraft had observed real farmland on the surface. If an actually
    // observed crop block belongs to the same horizontal facility cluster,
    // prefer that source-backed coordinate over the inferred record.
    const observedInNearestCluster = nearest
      ? candidates
        .filter((facility) => this.#isObservedCropFarmland(facility))
        .filter((facility) => (
          (facility.position.x - nearest.position.x) ** 2
          + (facility.position.z - nearest.position.z) ** 2
        ) <= FARM_FACILITY_CLUSTER_RADIUS ** 2)
        .sort((left, right) => {
          const leftDistance = (left.position.x - reference.x) ** 2
            + (left.position.z - reference.z) ** 2;
          const rightDistance = (right.position.x - reference.x) ** 2
            + (right.position.z - reference.z) ** 2;
          return leftDistance - rightDistance;
        })[0]
      : undefined;
    const remembered = observedInNearestCluster ?? nearest;
    if (remembered) {
      const now = new Date().toISOString();
      remembered.lastUsedAt = now;
      remembered.updatedAt = now;
      this.#persistAgentState();
    }
    return {
      ...spec,
      // A 12/32-block room-local scan was too small for fields outside a house
      // or compound. The remembered anchor has no distance cap within the same
      // world/dimension, so a remote outdoor farm is reached first; this radius
      // then covers the complete field around that anchor.
      radius: Math.max(MIN_OUTDOOR_FARM_SCAN_RADIUS, spec.radius),
      ...(remembered
        ? { placementAnchor: remembered.position }
        : spec.placementAnchor
          ? { placementAnchor: spec.placementAnchor }
          : {}),
    };
  }

  #prepareMacroStepTask(runtime: RuntimeCompanion, task: TaskSpec): TaskSpec {
    return task.kind === "farm" ? this.#prepareFarmSpec(runtime, task) : task;
  }

  #isObservedCropFarmland(facility: FacilityRecord): boolean {
    return facility.type === "farm"
      && facility.properties.source === "snapshot.observedFacilities"
      && facility.tags.includes("crop")
      && facility.tags.includes("farmland");
  }

  #isInvalidCropFarm(facility: FacilityRecord): boolean {
    return facility.type === "farm" && facility.properties.invalidForCropWork === true;
  }

  /** Persist the resolved outdoor facility anchor on the parent macro. */
  #rememberResolvedFarmAnchor(parent: TaskRecord, stepTask: TaskSpec): void {
    if (parent.spec.kind !== "macro" || stepTask.kind !== "farm" || !stepTask.placementAnchor) return;
    parent.spec = {
      ...parent.spec,
      placementAnchor: stepTask.placementAnchor,
    };
  }

  /**
   * Forge can relocate an outdoor blueprint after terrain validation. Keep the
   * exact physical build origin on the parent and lock every following farm
   * child to it, otherwise an old facility record or the request position can
   * send the NPC away from the field it has just built.
   */
  #rememberResolvedMacroBuildAnchor(
    parent: TaskRecord,
    steps: Array<{ label: string; task: TaskSpec }>,
    currentIndex: number,
    details?: TaskProgressDetails,
  ): void {
    if (parent.spec.kind !== "macro") return;
    if (steps[currentIndex]?.task.kind !== "build" || !details?.resolvedPlacementAnchor) return;
    const placementAnchor = details.resolvedPlacementAnchor;
    parent.spec = {
      ...parent.spec,
      placementAnchor,
    };
    if (parent.spec.skillId !== "build.crop-farm") return;
    for (let index = currentIndex + 1; index < steps.length; index += 1) {
      const step = steps[index]!;
      if (step.task.kind !== "farm") continue;
      steps[index] = {
        ...step,
        task: {
          ...step.task,
          placementAnchor,
          lockPlacementAnchor: true,
        },
      };
    }
  }

  #resolveMacroSteps(
    spec: Extract<TaskSpec, { kind: "macro" }>,
    ranchChatFixture = false,
  ): Array<{ label: string; task: TaskSpec }> {
    const resolved = this.skills.resolve(spec.skillId, spec.arguments)
      .filter((step) => step.whenMaterialMode === "always" || step.whenMaterialMode === spec.materialMode)
      .map((step) => {
        if (step.task.kind === "farm") {
          return {
            label: step.label,
            task: {
              ...step.task,
              placementAnchor: step.task.placementAnchor ?? spec.placementAnchor,
            },
          };
        }
        if (step.task.kind !== "build") return { label: step.label, task: step.task };
        return {
          label: step.label,
          task: {
            ...step.task,
            ...(step.task.placement === "companion"
              ? { placementAnchor: step.task.placementAnchor ?? spec.placementAnchor }
              : {}),
            ...(step.task.kind === "build" && spec.homeBounds && !step.task.homeBounds
              ? { homeBounds: spec.homeBounds }
              : {}),
            materialPreference: step.task.materialPreference ?? spec.materialPreference,
          },
        };
      });
    if (!ranchChatFixture) return resolved;
    const ranch = resolved.find((step) => step.task.kind === "ranch" && step.task.action === "establish");
    if (!ranch || ranch.task.kind !== "ranch") {
      throw new ControlError({
        code: "LIVE_FIXTURE_RANCH_STEP_MISSING",
        message: "The ranch acceptance fixture could not resolve its establish step",
        statusCode: 500,
      });
    }
    return resolved.map((step) => (
      step.task.kind === "ranch" && step.task.action === "establish"
        ? {
            ...step,
            task: { ...step.task, fixtureTag: RANCH_CHAT_FIXTURE_TAG },
          }
        : step
    ));
  }

  #consumeRanchChatFixture(companionId: string): boolean {
    const expiresAt = this.#armedRanchChatFixtures.get(companionId);
    if (expiresAt === undefined) return false;
    this.#armedRanchChatFixtures.delete(companionId);
    return expiresAt >= Date.now();
  }

  async #runMacro(
    runtime: RuntimeCompanion,
    parent: TaskRecord,
    callbacks: TaskCallbacks,
    signal: AbortSignal,
    resumeCurrent = false,
  ): Promise<string> {
    if (parent.spec.kind !== "macro") throw new Error("Expected macro task");
    parent.spec = this.#prepareMacroSpec(runtime, parent.spec);
    const steps = this.#macroSteps.get(parent.id) ?? this.#resolveMacroSteps(parent.spec);
    this.#macroSteps.set(parent.id, steps);
    if (steps.length === 0) return `声明式技能 ${parent.spec.skillId} 在当前模式无需执行步骤`;
    let index = Math.min(steps.length - 1, Math.max(0, Math.floor(parent.progress * steps.length + 1e-9)));

    if (resumeCurrent && runtime.backend.resumeTask) {
      const scaledProgress = parent.progress * steps.length;
      const atStepBoundary = Math.abs(scaledProgress - Math.round(scaledProgress)) < 1e-6;
      const step = steps[index]!;
      const stepTask = this.#prepareMacroStepTask(runtime, step.task);
      this.#rememberResolvedFarmAnchor(parent, stepTask);
      const child = this.#macroChild(parent, step.label, stepTask);
      try {
        const result = await runtime.backend.resumeTask(child, {
          onProgress: (progress, message, phase, details) => {
            this.#rememberResolvedMacroBuildAnchor(parent, steps, index, details);
            callbacks.onProgress(
              (index + Math.max(0, Math.min(1, progress))) / steps.length,
              `${step.label}：${message}`,
              phase,
              macroStepProgressDetails(index, stepTask, Math.max(0, Math.min(1, progress)), details),
            );
          },
        }, signal);
        callbacks.onProgress(
          (index + 1) / steps.length,
          `${step.label}：${result}`,
          undefined,
          macroStepProgressDetails(index, stepTask, 1),
        );
        this.#registerCompletedMacroBuildCheckpoint(parent, stepTask);
        index += 1;
      } catch (caught) {
        if (!(atStepBoundary && caught instanceof Error && caught.message === "RECOVERED_TASK_NOT_ACTIVE")) throw caught;
      }
    }

    for (; index < steps.length; index += 1) {
      if (signal.aborted) throw signal.reason instanceof Error ? signal.reason : new Error("技能已取消");
      const step = steps[index]!;
      const stepTask = this.#prepareMacroStepTask(runtime, step.task);
      this.#rememberResolvedFarmAnchor(parent, stepTask);
      const child = this.#macroChild(parent, step.label, stepTask);
      const result = await runtime.backend.runTask(child, {
        onProgress: (progress, message, phase, details) => {
          this.#rememberResolvedMacroBuildAnchor(parent, steps, index, details);
          callbacks.onProgress(
            (index + Math.max(0, Math.min(1, progress))) / steps.length,
            `${step.label}：${message}`,
            phase,
            macroStepProgressDetails(index, stepTask, Math.max(0, Math.min(1, progress)), details),
          );
        },
      }, signal);
      callbacks.onProgress(
        (index + 1) / steps.length,
        `${step.label}：${result}`,
        undefined,
        macroStepProgressDetails(index, stepTask, 1),
      );
      this.#registerCompletedMacroBuildCheckpoint(parent, stepTask);
    }
    return `声明式技能 ${parent.spec.skillId} 已完成`;
  }

  #macroChild(parent: TaskRecord, label: string, spec: TaskSpec): TaskRecord {
    const now = new Date().toISOString();
    return {
      id: parent.id,
      companionId: parent.companionId,
      spec,
      status: "running",
      progress: 0,
      message: label,
      createdAt: parent.createdAt,
      startedAt: parent.startedAt ?? now,
      finishedAt: null,
      error: null,
    };
  }

  async #pump(runtime: RuntimeCompanion): Promise<void> {
    const concurrent = runtime.backend.supportsConcurrentTasks === true;
    if (!concurrent && runtime.inFlightTaskIds.size > 0) return;
    const taskId = runtime.queue.shift();
    if (!taskId) return;
    const task = this.#tasks.get(taskId);
    if (!task || task.status !== "queued") {
      void this.#pump(runtime);
      return;
    }
    await this.#executeTask(runtime, task, false);
  }

  async #executeTask(runtime: RuntimeCompanion, task: TaskRecord, recovered: boolean): Promise<void> {
    const taskId = task.id;
    if (runtime.inFlightTaskIds.has(taskId)) return;
    const concurrent = runtime.backend.supportsConcurrentTasks === true;
    runtime.inFlightTaskIds.add(taskId);
    if (!recovered) {
      task.status = "running";
      task.startedAt = new Date().toISOString();
      task.message = "正在执行";
    } else if (task.status === "running") {
      runtime.activeTaskId = taskId;
    }
    if (!recovered || task.status === "running") runtime.activeTaskId = taskId;
    const controller = new AbortController();
    this.#controllers.set(taskId, controller);
    this.#persistTaskState();
    this.events.publish({
      type: "task",
      companionId: task.companionId,
      message: recovered ? `已恢复任务监听：${task.spec.kind}` : `开始任务：${task.spec.kind}`,
      data: { taskId, recovered },
    });
    const callbacks: TaskCallbacks = {
      onProgress: (progress, progressMessage, phase, details) => {
        if (["succeeded", "failed", "cancelled"].includes(task.status)) return;
        task.status = phase === "paused" ? "paused" : "running";
        if (phase === "paused") {
          if (runtime.activeTaskId === taskId) {
            runtime.activeTaskId = [...runtime.inFlightTaskIds]
              .find((candidateId) => candidateId !== taskId && this.#tasks.get(candidateId)?.status === "running") ?? null;
          }
        } else {
          runtime.activeTaskId = taskId;
        }
        task.progress = progress;
        task.message = progressMessage;
        applyTaskProgressDetails(task, details);
        this.#persistTaskState();
        this.events.publish({
          type: "task",
          companionId: task.companionId,
          message: progressMessage,
          data: { taskId, progress, ...details },
        });
      },
    };
    try {
      const completion = task.spec.kind === "macro"
        ? this.#runMacro(runtime, task, callbacks, controller.signal, recovered)
        : recovered
          ? runtime.backend.resumeTask!(task, callbacks, controller.signal)
          : runtime.backend.runTask(task, callbacks, controller.signal);
      if (concurrent) void this.#pump(runtime);
      const message = await completion;
      if (task.status === "running" || task.status === "paused") {
        task.status = "succeeded";
        task.progress = 1;
        task.message = message;
        task.finishedAt = new Date().toISOString();
        // Direct MCP/dashboard build tasks do not have an Agent WorkGraph
        // checkpoint to trigger facility memory. Record them at the same
        // completion boundary as planned goals so every successful building
        // route can be reused after a restart.
        if (task.spec.kind === "build") this.#registerDirectBuildFacility(task);
        if (task.spec.kind === "macro" && task.spec.skillId === "build.crop-farm") {
          // A crop-farm macro has a farm child after its blueprint child. Wait
          // until that child succeeds before exposing the new farm as a
          // maintenance candidate; otherwise the just-recorded build point can
          // redirect its own farm step back to the NPC's current position.
          this.#registerDirectMacroBuildFacility(task);
        }
        this.#persistTaskState();
        this.events.publish({ type: "task", companionId: task.companionId, message, data: { taskId } });
      }
    } catch (error) {
      if (controller.signal.aborted) {
        if (task.finishedAt === null) {
          task.status = "cancelled";
          task.message = error instanceof Error ? error.message : "任务已取消";
          task.finishedAt = new Date().toISOString();
        }
      } else {
        const recoveryLost = recovered && error instanceof Error && error.message === "RECOVERED_TASK_NOT_ACTIVE";
        const backendFailure = error instanceof BackendTaskFailure ? error : null;
        task.status = "failed";
        task.message = recoveryLost ? "控制服务重启后未在 Minecraft 存档中找到该任务" : error instanceof Error ? error.message : String(error);
        task.finishedAt = new Date().toISOString();
        task.error = {
          code: recoveryLost ? "TASK_RECOVERY_LOST" : backendFailure?.code ?? "BACKEND_FAILURE",
          message: task.message,
          retryable: backendFailure?.retryable ?? true,
          suggestedRecovery: recoveryLost ? "重新下达该任务。" : "重新观察环境后重试。",
        };
        this.events.publish({ type: "warning", companionId: task.companionId, message: `任务失败：${task.message}`, data: { taskId } });
      }
    } finally {
      await this.#notifyTaskTerminalOnce(task).catch(() => undefined);
      this.#controllers.delete(taskId);
      this.#macroSteps.delete(taskId);
      runtime.inFlightTaskIds.delete(taskId);
      if (runtime.activeTaskId === taskId) {
        runtime.activeTaskId = runtime.inFlightTaskIds.values().next().value ?? null;
      }
      this.#persistTaskState();
      this.#continueAgentGoalsWaitingOnTask(task);
      void this.#pump(runtime);
    }
  }

  /**
   * A facility exists as soon as its blueprint child succeeds, not only after
   * a long farm/ranch/storage macro finishes. Persisting here means a crash,
   * pause or later livestock search cannot make the Agent rebuild the already
   * completed structure.
   */
  #registerCompletedMacroBuildCheckpoint(parent: TaskRecord, stepTask: TaskSpec): void {
    if (parent.spec.kind !== "macro" || stepTask.kind !== "build") return;
    const graph = this.#agentState.workGraphs.find((candidate) => candidate.nodes.some(
      (node) => this.#checkpointString(node, "taskId") === parent.id,
    ));
    if (!graph) {
      // An instant simulator/backend can finish before advanceGoal writes the
      // taskId into its running skill node. Defer to that graph instead of
      // creating a generic direct record that would shadow the specialized
      // farm/ranch record a few microticks later.
      if (this.#hasAgentMacroAssociation(parent)) return;
      // Ranch establishment needs the pen anchor before the long livestock
      // search, so it is remembered immediately. Other multi-step macros are
      // finalized at their terminal boundary below.
      if (parent.spec.skillId !== "build.crop-farm") this.#registerDirectMacroBuildFacility(parent);
      return;
    }
    const node = graph.nodes.find((candidate) => this.#checkpointString(candidate, "taskId") === parent.id);
    const goal = this.#agentState.goals.find((candidate) => candidate.id === graph.goalId);
    if (!node || !goal || !this.#checkpointBoolean(node, "shouldRegisterFacilityAfterBuild")) return;
    const now = new Date().toISOString();
    this.#registerCompletedFacilityIfRequested(goal, node, parent, now);
    graph.updatedAt = now;
    this.#persistAgentState();
  }

  /**
   * Tell direct-task facility registration apart from a macro that belongs to
   * an Agent WorkGraph. The graph/task link is normally written before the
   * backend starts, but very fast backends can finish in the same turn. In
   * that race the active skill node is still enough evidence to defer the
   * generic record; once the link is present it also protects the terminal
   * macro callback from creating a duplicate beside the authoritative
   * sourceGoalId record.
   */
  #hasAgentMacroAssociation(task: TaskRecord): boolean {
    if (task.spec.kind !== "macro") return false;
    const skillId = task.spec.skillId;
    return this.#agentState.workGraphs.some((graph) => {
      const goal = this.#agentState.goals.find((candidate) => candidate.id === graph.goalId);
      if (!goal || goal.companionId !== task.companionId) return false;
      return graph.nodes.some((node) => {
        if (this.#checkpointString(node, "taskId") === task.id) return true;
        if (["succeeded", "failed", "skipped"].includes(node.status)) return false;
        return node.action.kind === "skill" && node.action.skillId === skillId;
      });
    });
  }

  /**
   * Register a standalone `build` task that was submitted through MCP or the
   * dashboard rather than through the Agent planner. The same exact blueprint
   * bounds are used as for a planned goal, and the upsert journal prevents a
   * duplicate record when the player later asks the Agent to use the building.
   */
  #registerDirectBuildFacility(task: TaskRecord): void {
    if (task.spec.kind !== "build") return;
    // Planned build nodes are registered by their WorkGraph checkpoint, which
    // may carry a specialized type such as farm/ranch/storage. Do not create
    // a generic duplicate before that authoritative registration runs.
    if (this.#agentState.workGraphs.some((graph) => graph.nodes.some(
      (node) => this.#checkpointString(node, "taskId") === task.id,
    ))) return;
    const runtime = this.#companions.get(task.companionId);
    const snapshot = runtime?.backend.snapshot();
    if (!snapshot) return;
    const position = this.#facilityPositionForCompletedTask(task, snapshot);
    if (!position) return;
    const blueprint = this.#facilityBlueprintForCompletedTask(task);
    const bounds = blueprint ? this.#blueprintBounds(blueprint.plan, blueprint.origin) : undefined;
    const now = new Date().toISOString();
    this.#upsertFacility({
      worldId: snapshot.worldId,
      dimension: snapshot.dimension,
      type: "build",
      name: `Direct build: ${task.spec.planId}`.slice(0, 120),
      position,
      ...(bounds ? { bounds } : {}),
      tags: ["build", "direct-task", task.spec.planId],
      owner: task.spec.requestedBy,
      properties: {
        source: "direct-task",
        taskId: task.id,
        taskKind: task.spec.kind,
        companionId: task.companionId,
        planId: task.spec.planId,
        ...(blueprint ? { blueprintPlanId: blueprint.plan.id, boundarySource: "blueprint", confidence: 1 } : {}),
      },
    }, now);
    this.#persistAgentState();
  }

  /** Register the first successful blueprint child of a standalone macro. */
  #registerDirectMacroBuildFacility(parent: TaskRecord): void {
    if (parent.spec.kind !== "macro") return;
    // Agent-planned macros are registered by their WorkGraph node with the
    // specialized type and sourceGoalId. A direct fallback must never shadow
    // that record, including the fast-backend completion race.
    if (this.#hasAgentMacroAssociation(parent)) return;
    const type = this.#directMacroFacilityType(parent.spec.skillId);
    if (!type) return;
    const runtime = this.#companions.get(parent.companionId);
    const snapshot = runtime?.backend.snapshot();
    if (!snapshot) return;
    const position = this.#facilityPositionForCompletedTask(parent, snapshot);
    if (!position) return;
    const blueprint = this.#facilityBlueprintForCompletedTask(parent);
    const bounds = blueprint ? this.#blueprintBounds(blueprint.plan, blueprint.origin) : undefined;
    const skillId = parent.spec.skillId;
    const readable = skillId.replace(/^build\.|^life\./u, "").replace(/[-_.]+/gu, " ").trim();
    const tags = new Set<string>([type, "direct-task", skillId]);
    if (skillId.startsWith("build.")) tags.add("build");
    if (skillId === "build.crop-farm") tags.add("crop");
    if (skillId === "build.storage-room") tags.add("home");
    if (skillId === "life.establish-ranch" || skillId === "build.animal-pen") tags.add("livestock");
    if (skillId.includes("cobblestone-generator")) tags.add("cobblestone-generator");
    if (skillId.includes("mob-farm")) tags.add("mob-farm");
    const now = new Date().toISOString();
    this.#upsertFacility({
      worldId: snapshot.worldId,
      dimension: snapshot.dimension,
      type,
      name: `Direct ${type}: ${readable || skillId}`.slice(0, 120),
      position,
      ...(bounds ? { bounds } : {}),
      tags: [...tags].slice(0, 32),
      owner: parent.spec.requestedBy,
      properties: {
        source: "direct-task",
        taskId: parent.id,
        taskKind: parent.spec.kind,
        companionId: parent.companionId,
        skillId,
        ...(blueprint ? { blueprintPlanId: blueprint.plan.id, boundarySource: "blueprint", confidence: 1 } : {}),
      },
    }, now);
    this.#persistAgentState();
  }

  #directMacroFacilityType(skillId: string): FacilityType | null {
    if (skillId === "build.crop-farm") return "farm";
    if (skillId === "build.storage-room") return "storage";
    if (skillId === "life.establish-ranch" || skillId === "build.animal-pen") return "ranch";
    if (skillId.includes("cobblestone-generator") || skillId.includes("mob-farm")) return "redstone";
    return skillId.startsWith("build.") ? "build" : null;
  }

  #markRecoveryUnsupported(task: TaskRecord): void {
    task.status = "failed";
    task.message = "当前角色后端不支持控制服务重启后的任务恢复";
    task.finishedAt = new Date().toISOString();
    task.error = {
      code: "TASK_RECOVERY_UNSUPPORTED",
      message: task.message,
      retryable: true,
      suggestedRecovery: "重新下达该任务。",
    };
  }

  #persistTaskState(): void {
    this.#taskJournal.save(this.#tasks.values(), this.#taskOwners, this.#terminalNotifications);
  }

  #persistAgentState(): void {
    this.#agentJournal.save(this.#agentState);
  }

  #requireMutableGoal(id: string): GoalRecord {
    const goal = this.#agentState.goals.find((entry) => entry.id === id);
    if (!goal) {
      throw new ControlError({ code: "GOAL_NOT_FOUND", message: `找不到 Agent 目标 ${id}`, statusCode: 404 });
    }
    return goal;
  }

  #requireMutableWorkGraph(goalId: string): WorkGraph {
    const graph = this.#agentState.workGraphs.find((entry) => entry.goalId === goalId);
    if (!graph) {
      throw new ControlError({ code: "WORK_GRAPH_NOT_FOUND", message: `找不到目标 ${goalId} 的工作图`, statusCode: 404 });
    }
    return graph;
  }

  #checkpointString(node: WorkNode, key: string): string | undefined {
    const value = node.checkpoint[key];
    return typeof value === "string" && value.trim() ? value : undefined;
  }

  #checkpointNumber(node: WorkNode, key: string): number | undefined {
    const value = node.checkpoint[key];
    return typeof value === "number" && Number.isFinite(value) ? value : undefined;
  }

  #checkpointBoolean(node: WorkNode, key: string): boolean {
    return node.checkpoint[key] === true;
  }

  #checkpointStringArray(node: WorkNode, key: string): string[] {
    const value = node.checkpoint[key];
    if (!Array.isArray(value)) return [];
    return value
      .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
      .map((entry) => entry.trim())
      .slice(0, 16);
  }

  #markWorkNodeSucceeded(node: WorkNode, checkpoint: Record<string, unknown> = {}): void {
    node.status = "succeeded";
    node.progress = 1;
    node.checkpoint = {
      ...node.checkpoint,
      ...checkpoint,
      completedAt: new Date().toISOString(),
    };
  }

  /**
   * A remembered field is only a hint until Minecraft can physically find
   * farmland there. On a source-backed miss, invalidate that record once and
   * reopen the prerequisite/build branch so the Agent creates one new outdoor
   * field instead of repeatedly searching the house or failing the whole goal.
   */
  #recoverMissingRememberedFarm(
    goal: GoalRecord,
    graph: WorkGraph,
    node: WorkNode,
    task: TaskRecord,
    now: string,
  ): boolean {
    if (node.id !== "operate_crop_farm"
      || task.status !== "failed"
      || task.error?.code !== "FARM_TARGET_NOT_FOUND"
      || this.#checkpointBoolean(node, "outdoorFallbackAttempted")) return false;

    const queryNodeId = this.#checkpointString(node, "preferredFacilityQueryNodeId")
      ?? "query_existing_farm";
    const queryNode = graph.nodes.find((candidate) => candidate.id === queryNodeId);
    const invalidIds = new Set<string>();
    const activeDimension = this.#companions.get(goal.companionId)?.backend.snapshot().dimension;
    const queriedFacilityId = queryNode ? this.#checkpointString(queryNode, "firstFacilityId") : undefined;
    if (queriedFacilityId) invalidIds.add(queriedFacilityId);
    if (task.spec.kind === "farm" && task.spec.placementAnchor) {
      const anchor = task.spec.placementAnchor;
      // Observed farmland is recorded per block. Invalidate only the selected
      // field cluster, not every separate outdoor field inside the old 64-block
      // search area. Other real farms must remain eligible for the next pass.
      const failedSearchRadius = Math.max(8, Math.min(16, task.spec.radius));
      for (const facility of this.#agentState.facilities) {
        if (facility.worldId !== goal.worldId || facility.type !== "farm") continue;
        if (activeDimension && facility.dimension !== activeDimension) continue;
        const clusterDistance = (facility.position.x - anchor.x) ** 2
          + (facility.position.z - anchor.z) ** 2;
        if (clusterDistance <= failedSearchRadius ** 2) invalidIds.add(facility.id);
      }
    }
    for (const facility of this.#agentState.facilities) {
      if (!invalidIds.has(facility.id)) continue;
      facility.updatedAt = now;
      facility.properties = {
        ...facility.properties,
        invalidForCropWork: true,
        validationFailureCode: "FARM_TARGET_NOT_FOUND",
        validationFailedAt: now,
      };
    }

    if (queryNode) {
      queryNode.status = "succeeded";
      queryNode.progress = 1;
      queryNode.checkpoint = {
        ...queryNode.checkpoint,
        facilityIds: [],
        facilityCount: 0,
        invalidFacilityIds: [...invalidIds].slice(0, 32),
        fallbackToOutdoorBuildAt: now,
      };
      delete queryNode.checkpoint.firstFacilityId;
      delete queryNode.checkpoint.firstFacilityName;
      delete queryNode.checkpoint.firstFacilityType;
      delete queryNode.checkpoint.firstFacilityPosition;
    }

    const reopenIds = new Set([
      "find_or_craft_hoe",
      "find_or_craft_bucket",
      "build_crop_farm",
      "verify_farm_memory",
      "operate_crop_farm",
    ]);
    for (const candidate of graph.nodes) {
      if (!reopenIds.has(candidate.id)) continue;
      candidate.status = "pending";
      candidate.progress = 0;
      delete candidate.checkpoint.taskId;
      delete candidate.checkpoint.taskStatus;
      delete candidate.checkpoint.taskMessage;
      delete candidate.checkpoint.reusedFacilityId;
      delete candidate.checkpoint.reusedFacilityName;
      delete candidate.checkpoint.skippedAt;
      delete candidate.checkpoint.skippedReason;
      if (candidate.id === "operate_crop_farm") {
        candidate.checkpoint = {
          ...candidate.checkpoint,
          outdoorFallbackAttempted: true,
          invalidFacilityIds: [...invalidIds].slice(0, 32),
          previousFailedTaskId: task.id,
          previousFailureCode: task.error.code,
        };
      }
    }

    graph.status = "ready";
    graph.updatedAt = now;
    goal.status = "planning";
    goal.finishedAt = null;
    goal.activeWorkNodeId = null;
    goal.error = null;
    goal.message = "Remembered farm was not found; preparing one new outdoor farm";
    // The recovery can be discovered while the task executor itself is
    // unwinding. In that re-entrant path the current advance pass may settle
    // the graph as merely ready. Schedule one guarded follow-up so the newly
    // reopened prerequisite/build branch starts without another player chat.
    const continuationOwner = this.#checkpointString(node, "owner") ?? "agent-goal";
    const continuation = setTimeout(() => {
      const currentGoal = this.#agentState.goals.find((candidate) => candidate.id === goal.id);
      const currentGraph = this.#agentState.workGraphs.find((candidate) => candidate.goalId === goal.id);
      if (currentGoal?.status !== "planning" || currentGraph?.status !== "ready") return;
      void this.advanceGoal(goal.id, continuationOwner).catch((caught) => {
        this.events.publish({
          type: "warning",
          companionId: goal.companionId,
          message: `Outdoor farm fallback continuation failed: ${caught instanceof Error ? redactSensitiveText(caught.message) : "unknown error"}`,
          data: { goalId: goal.id, failedTaskId: task.id },
        });
      });
    }, 0);
    continuation.unref();
    return true;
  }

  #syncWorkGraphFromTasks(goal: GoalRecord, graph: WorkGraph, now: string): void {
    if (["cancelled", "succeeded"].includes(goal.status) || ["cancelled", "succeeded"].includes(graph.status)) return;
    let activeNodeId: string | null = null;
    let recoveredFailedTaskNode = false;
    for (const node of graph.nodes) {
      const taskId = this.#checkpointString(node, "taskId");
      if (!taskId) continue;
      const task = this.#tasks.get(taskId);
      if (!task) continue;
      const wasFailed = node.status === "failed";
      node.progress = task.progress;
      node.checkpoint = {
        ...node.checkpoint,
        taskStatus: task.status,
        taskMessage: redactSensitiveText(task.message).slice(0, 240),
        syncedAt: now,
      };
      if (task.status === "queued" || task.status === "running") {
        node.status = "running";
        activeNodeId = node.id;
      } else if (task.status === "paused") {
        node.status = "paused";
        activeNodeId = node.id;
      } else if (task.status === "succeeded") {
        node.status = "succeeded";
        node.progress = 1;
        this.#registerCompletedFacilityIfRequested(goal, node, task, now);
      } else if (task.status === "failed" || task.status === "cancelled") {
        if (this.#recoverMissingRememberedFarm(goal, graph, node, task, now)) continue;
        node.status = "failed";
        graph.status = "failed";
        graph.updatedAt = now;
        goal.status = "failed";
        goal.finishedAt = now;
        goal.activeWorkNodeId = null;
        goal.message = redactSensitiveText(task.message).slice(0, 500);
        goal.error = {
          code: task.error?.code ?? (task.status === "cancelled" ? "TASK_CANCELLED" : "TASK_FAILED"),
          message: goal.message || "Agent task node failed",
          retryable: task.error?.retryable ?? task.status !== "cancelled",
          failedNodeId: node.id,
          ...(task.error?.suggestedRecovery ? { suggestedRecovery: task.error.suggestedRecovery } : {}),
        };
      }
      if (wasFailed && ["queued", "running", "paused", "succeeded"].includes(task.status)) {
        recoveredFailedTaskNode = true;
        node.checkpoint = {
          ...node.checkpoint,
          recoveredAt: now,
        };
      }
    }
    const progress = graph.nodes.length === 0
      ? 0
      : graph.nodes.reduce((sum, node) => sum + node.progress, 0) / graph.nodes.length;
    goal.progress = Math.max(0, Math.min(1, progress));
    if (recoveredFailedTaskNode && !graph.nodes.some((node) => node.status === "failed")) {
      goal.finishedAt = null;
      goal.error = null;
      graph.updatedAt = now;
      if (activeNodeId) {
        graph.status = "running";
        goal.status = "running";
        goal.activeWorkNodeId = activeNodeId;
        goal.message = "Recovered failed work node and resumed the same task";
      } else {
        graph.status = "ready";
        goal.status = "planning";
        goal.activeWorkNodeId = null;
        goal.message = "Recovered task completed; goal is ready for the remaining work nodes";
      }
    }
    if (goal.status === "failed" || graph.status === "failed") return;
    if (activeNodeId) {
      goal.activeWorkNodeId = activeNodeId;
      if (goal.status !== "paused") goal.status = "running";
      if (graph.status !== "paused") graph.status = "running";
      graph.updatedAt = now;
    }
  }

  #registerCompletedFacilityIfRequested(goal: GoalRecord, node: WorkNode, task: TaskRecord, now: string): void {
    if (!this.#checkpointBoolean(node, "shouldRegisterFacilityAfterBuild")) return;
    const type = this.#facilityTypeForCompletedNode(node, task);
    const snapshot = this.#companions.get(goal.companionId)?.backend.snapshot();
    const position = this.#facilityPositionForCompletedTask(task, snapshot);
    const blueprint = this.#facilityBlueprintForCompletedTask(task);
    const bounds = blueprint && position
      ? this.#blueprintBounds(blueprint.plan, blueprint.origin)
      : undefined;
    const registeredFacilityId = this.#checkpointString(node, "registeredFacilityId");
    if (registeredFacilityId) {
      const existing = this.#agentState.facilities.find((facility) => facility.id === registeredFacilityId);
      if (existing && position) {
        const positionChanged = existing.position.x !== position.x
          || existing.position.y !== position.y
          || existing.position.z !== position.z;
        const boundsChanged = bounds && JSON.stringify(existing.bounds) !== JSON.stringify(bounds);
        if (!positionChanged && !boundsChanged) return;
        if (positionChanged) existing.position = position;
        if (bounds) existing.bounds = bounds;
        existing.updatedAt = now;
        existing.properties = {
          ...existing.properties,
          correctedFromLegacyAnchor: true,
          correctedAt: now,
          ...(blueprint ? {
            blueprintPlanId: blueprint.plan.id,
            boundarySource: "blueprint",
            confidence: 1,
          } : {}),
        };
        node.checkpoint = {
          ...node.checkpoint,
          correctedFacilityPosition: position,
          ...(bounds ? { correctedFacilityBounds: bounds } : {}),
          correctedFacilityAt: now,
        };
      }
      return;
    }
    if (!position || !snapshot?.dimension) {
      node.checkpoint = {
        ...node.checkpoint,
        facilityRegistrationSkippedAt: now,
        facilityRegistrationSkippedReason: "No live snapshot or placement anchor was available",
      };
      return;
    }
    const tags = this.#facilityTagsForCompletedNode(type, node, task);
    const facility = this.#upsertFacility({
      worldId: goal.worldId,
      dimension: snapshot.dimension,
      type,
      name: this.#facilityNameForCompletedNode(type, node, task),
      position,
      ...(bounds ? { bounds } : {}),
      tags,
      owner: goal.spec.requestedBy,
      sourceGoalId: goal.id,
      properties: {
        source: "agent.workGraph",
        nodeId: node.id,
        taskId: task.id,
        taskKind: task.spec.kind,
        companionId: goal.companionId,
        ...(task.spec.kind === "macro" ? { skillId: task.spec.skillId } : {}),
        ...(task.spec.kind === "build" ? { planId: task.spec.planId } : {}),
        ...(blueprint ? {
          blueprintPlanId: blueprint.plan.id,
          boundarySource: "blueprint",
          confidence: 1,
        } : {}),
      },
    }, now);
    node.checkpoint = {
      ...node.checkpoint,
      registeredFacilityId: facility.id,
      registeredFacilityName: facility.name,
      registeredFacilityType: facility.type,
      registeredAt: now,
    };
  }

  #facilityTypeForCompletedNode(node: WorkNode, task: TaskRecord): FacilityType {
    const raw = this.#checkpointString(node, "facilityType");
    if (raw && FACILITY_TYPES.has(raw as FacilityType)) return raw as FacilityType;
    if (task.spec.kind === "macro") {
      if (task.spec.skillId === "build.crop-farm") return "farm";
      if (task.spec.skillId === "build.storage-room") return "storage";
      if (task.spec.skillId === "life.establish-ranch" || task.spec.skillId === "build.animal-pen") return "ranch";
      if (task.spec.skillId.includes("cobblestone-generator") || task.spec.skillId.includes("mob-farm")) return "redstone";
    }
    return task.spec.kind === "build" || task.spec.kind === "macro" ? "build" : "other";
  }

  #facilityTagsForCompletedNode(type: FacilityType, node: WorkNode, task: TaskRecord): string[] {
    const tags = new Set<string>([
      type,
      "agent-goal",
      ...this.#checkpointStringArray(node, "facilityTags"),
    ]);
    if (task.spec.kind === "macro") {
      tags.add(task.spec.skillId);
      if (task.spec.skillId.startsWith("build.")) tags.add("build");
      if (task.spec.skillId === "build.crop-farm") tags.add("crop");
      if (task.spec.skillId === "build.storage-room") tags.add("home");
      if (task.spec.skillId === "life.establish-ranch" || task.spec.skillId === "build.animal-pen") tags.add("livestock");
      if (task.spec.skillId.includes("cobblestone-generator")) tags.add("cobblestone-generator");
      if (task.spec.skillId.includes("mob-farm")) tags.add("mob-farm");
    } else if (task.spec.kind === "build") {
      tags.add("build");
      tags.add(task.spec.planId);
    }
    return [...tags].slice(0, 32);
  }

  #facilityNameForCompletedNode(type: FacilityType, node: WorkNode, task: TaskRecord): string {
    const explicit = this.#checkpointString(node, "facilityName");
    if (explicit) return explicit.slice(0, 120);
    if (task.spec.kind === "macro") {
      const readable = task.spec.skillId
        .replace(/^build\./u, "")
        .replace(/^life\./u, "")
        .replace(/[-_.]+/gu, " ")
        .trim();
      if (readable) return `Agent ${type}: ${readable}`.slice(0, 120);
    }
    if (task.spec.kind === "build") return `Agent build: ${task.spec.planId}`.slice(0, 120);
    return `Agent facility: ${node.label}`.slice(0, 120);
  }

  #facilityPositionForCompletedTask(task: TaskRecord, snapshot?: WorldSnapshot): FacilityDraft["position"] | null {
    if (task.spec.kind === "macro" && task.spec.skillId === "build.crop-farm" && snapshot) {
      const reference = task.spec.placementAnchor ?? snapshot.position;
      const observed = this.#agentState.facilities
        .filter((facility) => facility.worldId === snapshot.worldId)
        .filter((facility) => facility.dimension === snapshot.dimension)
        .filter((facility) => this.#isObservedCropFarmland(facility))
        .filter((facility) => (
          (facility.position.x - reference.x) ** 2
          + (facility.position.z - reference.z) ** 2
        ) <= FARM_FACILITY_CLUSTER_RADIUS ** 2)
        .sort((left, right) => {
          const leftDistance = (left.position.x - reference.x) ** 2
            + (left.position.z - reference.z) ** 2;
          const rightDistance = (right.position.x - reference.x) ** 2
            + (right.position.z - reference.z) ** 2;
          return leftDistance - rightDistance;
        })[0];
      if (observed) return observed.position;
      // New farm macros carry a control-selected outdoor anchor. Keep that
      // coordinate when its elevation agrees with the post-build snapshot;
      // legacy underground request anchors are rejected by the vertical check.
      const anchor = task.spec.placementAnchor;
      if (anchor && Math.abs(anchor.y - snapshot.position.y) <= 16) {
        const home = snapshot.homeState?.dimension === snapshot.dimension
          ? snapshot.homeState.position
          : snapshot.ownerPosition;
        const outsideCurrentHouse = !home || (
          (anchor.x - home.x) ** 2 + (anchor.z - home.z) ** 2
        ) >= (OUTDOOR_FARM_HOME_CLEARANCE / 2) ** 2;
        if (outsideCurrentHouse) return anchor;
      }
      // Immediately after a real farm task the NPC is normally standing at the
      // field, which is still safer than persisting a legacy underground Y.
      return snapshot.position;
    }
    if (task.spec.kind === "macro" && task.spec.placementAnchor) return task.spec.placementAnchor;
    if (task.spec.kind === "build" && task.resolvedPlacementAnchor) return task.resolvedPlacementAnchor;
    if (task.spec.kind === "build" && task.spec.placementAnchor) return task.spec.placementAnchor;
    if (task.spec.kind === "build") {
      try {
        return this.buildPlans.get(task.spec.planId).origin;
      } catch {
        return snapshot?.position ?? null;
      }
    }
    return snapshot?.position ?? null;
  }

  #facilityBlueprintForCompletedTask(task: TaskRecord): { plan: BuildPlan; origin: FacilityDraft["position"] } | null {
    try {
      if (task.spec.kind === "build") {
        const plan = this.buildPlans.get(task.spec.planId);
        const origin = task.resolvedPlacementAnchor ?? task.spec.placementAnchor ?? plan.origin;
        return { plan, origin };
      }
      if (task.spec.kind !== "macro") return null;
      const build = this.#resolveMacroSteps(task.spec).find((step) => step.task.kind === "build");
      if (!build || build.task.kind !== "build") return null;
      const plan = this.buildPlans.get(build.task.planId);
      const origin = task.spec.placementAnchor ?? build.task.placementAnchor;
      return origin ? { plan, origin } : null;
    } catch {
      // A removed local blueprint must not prevent the completed task or its
      // point anchor from being journaled; only exact bounds are omitted.
      return null;
    }
  }

  #blueprintBounds(plan: BuildPlan, origin: FacilityDraft["position"]): NonNullable<FacilityDraft["bounds"]> {
    const xs = plan.blocks.map((block) => block.position.x);
    const ys = plan.blocks.map((block) => block.position.y);
    const zs = plan.blocks.map((block) => block.position.z);
    return {
      min: {
        x: origin.x + Math.min(...xs),
        y: origin.y + Math.min(...ys),
        z: origin.z + Math.min(...zs),
      },
      max: {
        x: origin.x + Math.max(...xs),
        y: origin.y + Math.max(...ys),
        z: origin.z + Math.max(...zs),
      },
    };
  }

  #dependencySatisfied(node: WorkNode | undefined): boolean {
    return node?.status === "succeeded" || node?.status === "skipped";
  }

  #completeGoalIfDone(goal: GoalRecord, graph: WorkGraph, now: string): boolean {
    if (graph.nodes.length === 0 || !graph.nodes.every((node) => node.status === "succeeded" || node.status === "skipped")) return false;
    graph.status = "succeeded";
    graph.updatedAt = now;
    goal.status = "succeeded";
    goal.progress = 1;
    goal.activeWorkNodeId = null;
    goal.finishedAt = now;
    goal.message = "Agent goal completed";
    goal.error = null;
    return true;
  }

  #settleIdleWorkGraph(goal: GoalRecord, graph: WorkGraph, now: string): void {
    if (["paused", "succeeded", "failed", "cancelled"].includes(goal.status)
      || ["paused", "succeeded", "failed", "cancelled"].includes(graph.status)) return;
    if (graph.nodes.some((node) => node.status === "running" || node.status === "paused")) return;
    goal.activeWorkNodeId = null;
    if (graph.nodes.some((node) => node.status === "blocked")) {
      graph.status = "draft";
      graph.updatedAt = now;
      goal.status = "planning";
      goal.message = "Goal is waiting for an expanded local planner route";
      return;
    }
    if (graph.nodes.some((node) => node.status === "pending")) {
      graph.status = "ready";
      graph.updatedAt = now;
      goal.status = "planning";
      goal.message = "Goal is ready for the next work node";
    }
  }

  #nextReadyWorkNode(graph: WorkGraph): WorkNode | undefined {
    const byId = new Map(graph.nodes.map((node) => [node.id, node]));
    for (const node of graph.nodes) {
      if (node.status !== "pending") continue;
      if (!node.dependsOn.every((dependency) => this.#dependencySatisfied(byId.get(dependency)))) continue;
      if (this.#skipIfReusableFacilityAlreadyExists(graph, node)) continue;
      return node;
    }
    return undefined;
  }

  #skipIfReusableFacilityAlreadyExists(graph: WorkGraph, node: WorkNode): boolean {
    const queryNodeId = this.#checkpointString(node, "skipIfFacilityQueryNodeId");
    if (!queryNodeId) return false;
    const queryNode = graph.nodes.find((candidate) => candidate.id === queryNodeId);
    if (!queryNode || !this.#dependencySatisfied(queryNode)) return false;
    const facilityCount = this.#checkpointNumber(queryNode, "facilityCount") ?? 0;
    if (facilityCount <= 0) return false;
    node.status = "skipped";
    node.progress = 1;
    node.checkpoint = {
      ...node.checkpoint,
      skippedAt: new Date().toISOString(),
      skippedReason: "Reusable facility already exists",
      reusedFacilityId: this.#checkpointString(queryNode, "firstFacilityId") ?? "",
      reusedFacilityName: this.#checkpointString(queryNode, "firstFacilityName") ?? "",
    };
    return true;
  }

  async #executeWorkNodeAction(
    goal: GoalRecord,
    graph: WorkGraph,
    node: WorkNode,
    owner: string,
    options: AiDecisionMutationOptions = {},
  ): Promise<TaskRecord | undefined> {
    const action = node.action;
    switch (action.kind) {
      case "query-knowledge": {
        const records = this.queryKnowledge(action.query, action.topics);
        this.#markWorkNodeSucceeded(node, {
          recordIds: records.map((record) => record.id).slice(0, 32),
          recordCount: records.length,
        });
        graph.updatedAt = new Date().toISOString();
        return undefined;
      }
      case "query-facilities": {
        const facilities = this.#queryFacilitiesForAction(goal, action);
        const usedAt = new Date().toISOString();
        for (const facility of facilities) {
          facility.lastUsedAt = usedAt;
          facility.updatedAt = usedAt;
        }
        this.#markWorkNodeSucceeded(node, {
          facilityIds: facilities.map((facility) => facility.id).slice(0, 32),
          facilityCount: facilities.length,
          ...(facilities[0] ? {
            firstFacilityId: facilities[0].id,
            firstFacilityName: facilities[0].name,
            firstFacilityType: facilities[0].type,
            firstFacilityPosition: facilities[0].position,
          } : {}),
        });
        graph.updatedAt = usedAt;
        return undefined;
      }
      case "noop": {
        this.#markWorkNodeSucceeded(node, { note: action.note });
        graph.updatedAt = new Date().toISOString();
        return undefined;
      }
      case "verify": {
        this.#markWorkNodeSucceeded(node, {
          evidenceKind: action.evidenceKind,
          expectation: action.expectation,
          localVerification: true,
        });
        graph.updatedAt = new Date().toISOString();
        return undefined;
      }
      case "register-facility": {
        const facility = this.registerFacility(action.facility);
        this.#markWorkNodeSucceeded(node, { facilityId: facility.id, facilityType: facility.type });
        graph.updatedAt = new Date().toISOString();
        return undefined;
      }
      case "chat": {
        await this.sendChat(goal.companionId, action.message, owner, {
          ...(options.aiDecisionInteractionId
            ? { interactionId: options.aiDecisionInteractionId }
            : {}),
        });
        this.#markWorkNodeSucceeded(node, { chatDelivered: true });
        graph.updatedAt = new Date().toISOString();
        return undefined;
      }
      case "control": {
        await this.controlCompanion(goal.companionId, action.action, options);
        this.#markWorkNodeSucceeded(node, { controlAction: action.action });
        graph.updatedAt = new Date().toISOString();
        return undefined;
      }
      case "task": {
        const spec = action.spec.kind === "ranch"
          ? this.#prepareRanchSpecFromWorkGraph(graph, node, action.spec)
          : action.spec;
        const task = this.assignTask(goal.companionId, spec, owner, options);
        node.status = task.status === "succeeded" ? "succeeded" : "running";
        node.progress = task.progress;
        node.checkpoint = {
          ...node.checkpoint,
          taskId: task.id,
          taskStatus: task.status,
          owner,
        };
        graph.updatedAt = new Date().toISOString();
        return task;
      }
      case "skill": {
        const spec: TaskSpec = {
          kind: "macro",
          skillId: action.skillId,
          arguments: action.arguments,
          requestedBy: goal.spec.requestedBy,
          note: goal.spec.objective.slice(0, 500),
          ...(action.materialMode ? { materialMode: action.materialMode } : {}),
          ...(action.materialPreference ? { materialPreference: action.materialPreference } : {}),
        };
        const task = this.assignTask(goal.companionId, spec, owner, options);
        node.status = task.status === "succeeded" ? "succeeded" : "running";
        node.progress = task.progress;
        node.checkpoint = {
          ...node.checkpoint,
          taskId: task.id,
          taskStatus: task.status,
          owner,
        };
        graph.updatedAt = new Date().toISOString();
        return task;
      }
    }
  }

  #prepareRanchSpecFromWorkGraph(
    graph: WorkGraph,
    node: WorkNode,
    spec: Extract<TaskSpec, { kind: "ranch" }>,
  ): Extract<TaskSpec, { kind: "ranch" }> {
    if (spec.penAnchor) return spec;
    const queryNodeId = this.#checkpointString(node, "preferredFacilityQueryNodeId");
    const buildNodeId = this.#checkpointString(node, "preferredFacilityBuildNodeId");
    const queryNode = queryNodeId ? graph.nodes.find((candidate) => candidate.id === queryNodeId) : undefined;
    const buildNode = buildNodeId ? graph.nodes.find((candidate) => candidate.id === buildNodeId) : undefined;
    const registeredFacilityId = buildNode
      ? this.#checkpointString(buildNode, "registeredFacilityId")
      : undefined;
    const facility = registeredFacilityId
      ? this.#agentState.facilities.find((candidate) => candidate.id === registeredFacilityId)
      : undefined;
    const queriedPosition = queryNode?.checkpoint.firstFacilityPosition;
    const penAnchor = facility?.position ?? (
      queriedPosition && typeof queriedPosition === "object"
        && typeof (queriedPosition as Record<string, unknown>).x === "number"
        && typeof (queriedPosition as Record<string, unknown>).y === "number"
        && typeof (queriedPosition as Record<string, unknown>).z === "number"
        ? {
            x: (queriedPosition as { x: number }).x,
            y: (queriedPosition as { y: number }).y,
            z: (queriedPosition as { z: number }).z,
          }
        : undefined
    );
    return penAnchor ? { ...spec, penAnchor } : spec;
  }

  #queryFacilitiesForAction(
    goal: GoalRecord,
    action: Extract<ActionSpec, { kind: "query-facilities" }>,
  ): FacilityRecord[] {
    const worldId = action.worldId ?? goal.worldId;
    const tags = new Set(action.tags);
    return this.#agentState.facilities
      .filter((facility) => facility.worldId === worldId)
      .filter((facility) => !action.dimension || facility.dimension === action.dimension)
      .filter((facility) => !action.type || facility.type === action.type)
      .filter((facility) => !this.#isInvalidCropFarm(facility))
      .filter((facility) => !action.owner || facility.owner === action.owner)
      .filter((facility) => tags.size === 0 || action.tags.every((tag) => facility.tags.includes(tag)))
      .sort((left, right) => (
        (right.lastUsedAt ?? right.updatedAt).localeCompare(left.lastUsedAt ?? left.updatedAt)
      ))
      .slice(0, action.limit);
  }

  #continueAgentGoalsWaitingOnTask(task: TaskRecord): void {
    if (!["succeeded", "failed", "cancelled"].includes(task.status)) return;
    this.#pruneAiDecisions();
    const decisionStillSettling = [...this.#pendingAiDecisions.values()]
      .some((candidate) => candidate.companionId === task.companionId);
    if (decisionStillSettling) {
      // An instant backend task can finish before submitAiDecision has delivered
      // its one start reply and released the one-shot mutation guard. Continue
      // after that transaction settles instead of failing the validated graph.
      const retry = setTimeout(() => this.#continueAgentGoalsWaitingOnTask(task), 25);
      retry.unref();
      return;
    }
    for (const graph of this.#agentState.workGraphs) {
      const node = graph.nodes.find((candidate) => this.#checkpointString(candidate, "taskId") === task.id);
      if (!node) continue;
      const goal = this.#agentState.goals.find((candidate) => candidate.id === graph.goalId);
      if (!goal || ["paused", "succeeded", "failed", "cancelled"].includes(goal.status)) continue;
      const owner = this.#checkpointString(node, "owner") ?? "agent-goal";
      void this.advanceGoal(goal.id, owner).catch((caught) => {
        this.events.publish({
          type: "warning",
          companionId: goal.companionId,
          message: `Agent goal continuation failed: ${caught instanceof Error ? redactSensitiveText(caught.message) : "unknown error"}`,
          data: { goalId: goal.id, nodeId: node.id, taskId: task.id },
        });
      });
    }
  }

  async #resumeAgentGoalsForCompanion(companionId: string): Promise<void> {
    const candidates = this.#agentState.goals
      .filter((goal) => goal.companionId === companionId)
      .filter((goal) => !["paused", "succeeded", "failed", "cancelled"].includes(goal.status))
      .sort((left, right) => left.createdAt.localeCompare(right.createdAt));
    for (const goal of candidates) {
      const graph = this.#agentState.workGraphs.find((entry) => entry.goalId === goal.id);
      if (!graph || ["paused", "succeeded", "failed", "cancelled"].includes(graph.status)) continue;
      const activeNode = graph.nodes.find((node) => node.id === goal.activeWorkNodeId)
        ?? graph.nodes.find((node) => node.status === "running" || node.status === "paused")
        ?? graph.nodes.find((node) => this.#checkpointString(node, "owner"));
      const owner = activeNode ? this.#checkpointString(activeNode, "owner") ?? "agent-goal" : "agent-goal";
      try {
        await this.advanceGoal(goal.id, owner);
      } catch (caught) {
        this.events.publish({
          type: "warning",
          companionId,
          message: `Agent goal resume failed: ${caught instanceof Error ? redactSensitiveText(caught.message) : "unknown error"}`,
          data: { goalId: goal.id },
        });
      }
    }
  }

  #buildInitialWorkGraph(goal: GoalRecord, now: string): WorkGraph {
    const taskHints = goal.spec.taskHints;
    const knowledgeNode: WorkNode = {
      id: "knowledge_lookup",
      label: "Retrieve local gameplay knowledge",
      action: {
        kind: "query-knowledge",
        query: goal.spec.objective,
        topics: [],
      } satisfies ActionSpec,
      dependsOn: [],
      status: "pending",
      attempts: 0,
      progress: 0,
      checkpoint: {},
    };
    const planned = taskHints.length > 0
      ? {
          nodes: taskHints.map((spec, index) => ({
            id: `task_${index + 1}`,
            label: `${spec.kind} task`,
            action: { kind: "task", spec } satisfies ActionSpec,
            dependsOn: index === 0 ? [knowledgeNode.id] : [`task_${index}`],
            status: "pending" as const,
            attempts: 0,
            progress: 0,
            checkpoint: {},
          })),
          edges: taskHints.map((_, index) => ({
            from: index === 0 ? knowledgeNode.id : `task_${index}`,
            to: `task_${index + 1}`,
          })),
          status: "ready" as const,
        }
      : planGoal(goal, knowledgeNode.id);
    const actionNodes: WorkNode[] = planned.nodes;
    const nodes = [knowledgeNode, ...actionNodes];
    return workGraphSchema.parse({
      id: randomUUID(),
      goalId: goal.id,
      version: AGENT_PROTOCOL_VERSION,
      status: planned.status,
      nodes,
      edges: planned.edges,
      createdAt: now,
      updatedAt: now,
    });
  }

  #mutateGoal(id: string, mutate: (goal: GoalRecord, now: string) => void): GoalRecord {
    const index = this.#agentState.goals.findIndex((entry) => entry.id === id);
    if (index < 0) {
      throw new ControlError({ code: "GOAL_NOT_FOUND", message: `找不到 Agent 目标 ${id}`, statusCode: 404 });
    }
    const nextGoals = this.#agentState.goals.map((goal) => structuredClone(goal));
    const goal = nextGoals[index]!;
    mutate(goal, new Date().toISOString());
    this.#agentState = { ...this.#agentState, goals: nextGoals };
    this.#persistAgentState();
    return structuredClone(goal);
  }

  async #notifyTaskTerminalOnce(task: TaskRecord): Promise<void> {
    if (this.#terminalNotifications.has(task.id)) return;
    const owner = this.#taskOwners.get(task.id);
    if (!owner || !TERMINAL_CHAT_OWNERS.has(owner)) return;
    // A legacy journal may contain mojibake from before chat validation was added.
    // Do not let a historical notification reject during backend registration and
    // bring down the control service before unfinished tasks can be recovered.
    try {
      await this.#notifyTaskTerminal(task);
    } catch (error) {
      if (!(error instanceof Error) || !("code" in error && error.code === "INVALID_GAME_CHAT_TEXT")) {
        throw error;
      }
    }
    this.#terminalNotifications.add(task.id);
  }

  async #notifyTaskTerminal(task: TaskRecord): Promise<void> {
    const owner = this.#taskOwners.get(task.id);
    if (!owner || !TERMINAL_CHAT_OWNERS.has(owner)) return;
    const prefix = task.status === "succeeded"
      ? "任务完成"
      : task.status === "failed"
        ? "任务失败"
        : task.status === "cancelled"
          ? "任务已取消"
          : null;
    if (!prefix) return;
    const detail = task.message.trim().slice(0, 180);
    await this.sendChat(task.companionId, `${prefix}：${detail || task.spec.kind}`, owner);
  }
}
