import { randomUUID } from "node:crypto";
import type {
  AiTaskDecision,
  AiTaskDecisionResult,
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
  LiveFixtureRequest,
  PermissionProfile,
  TaskProgressDetails,
  TaskRecord,
  TaskSpec,
  WorldSnapshot,
} from "@mc/protocol";
import { sanitizeGameChatText } from "./game-chat-text.js";
import { capabilitySchema, chatMessageSchema } from "@mc/protocol";
import { BackendTaskFailure, type CompanionBackend, type TaskCallbacks } from "./backend.js";
import type { AiDecisionMutationOptions, ChatDeliveryOptions } from "./control-api.js";
import { BuildPlanStore } from "./build-plan-store.js";
import { importBuildDraft } from "./build-importer.js";
import { ChatSettingsStore } from "./chat-settings-store.js";
import { DeclarativeSkillStore } from "./declarative-skill-store.js";
import { ControlError } from "./errors.js";
import { CompanionEventBus } from "./event-bus.js";
import { TaskJournal } from "./task-journal.js";
import { redactSensitiveText } from "./skill-security.js";
import { commitAiTaskDecision } from "./ai-task-decision.js";

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
const AI_DECISION_TTL_MS = 60_000;
const MAX_PENDING_AI_DECISIONS = 128;

interface PendingAiDecision {
  companionId: string;
  requester: string;
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
  #chatSequence = 0;

  constructor(options: {
    stateDirectory?: string;
    skills?: DeclarativeSkillStore;
    chatSettings?: ChatSettingsStore;
  } = {}) {
    this.skills = options.skills ?? new DeclarativeSkillStore(options.stateDirectory);
    this.chatSettings = options.chatSettings ?? new ChatSettingsStore(options.stateDirectory);
    this.#taskJournal = new TaskJournal(options.stateDirectory);
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
    const unfinished = [...this.#tasks.values()]
      .filter((task) => task.companionId === backend.id && !["succeeded", "failed", "cancelled"].includes(task.status))
      .sort((left, right) => left.createdAt.localeCompare(right.createdAt));
    for (const task of unfinished) {
      if (task.status === "queued") runtime.queue.push(task.id);
      else if (backend.resumeTask) void this.#executeTask(runtime, task, true);
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
    return this.#requireCompanion(id).backend.snapshot();
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
    const resolvedSpec: TaskSpec = spec.kind === "macro" ? this.#prepareMacroSpec(runtime, spec) : spec;
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
    } else this.#assertTaskAllowed(runtime, task.spec);
    task.status = "queued";
    task.message = `等待从失败点恢复（${Math.round(task.progress * 100)}%）`;
    task.startedAt = null;
    task.finishedAt = null;
    task.error = null;
    this.#taskOwners.set(task.id, owner);
    this.#terminalNotifications.delete(task.id);
    runtime.queue = runtime.queue.filter((id) => id !== task.id);
    runtime.queue.push(task.id);
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
    return {
      ...spec,
      placementAnchor: spec.placementAnchor ?? snapshot.position,
      materialMode: spec.materialMode
        ?? snapshot.materialMode
        ?? (snapshot.gameMode === "creative" ? "creative" : "survival"),
    };
  }

  #resolveMacroSteps(
    spec: Extract<TaskSpec, { kind: "macro" }>,
    ranchChatFixture = false,
  ): Array<{ label: string; task: TaskSpec }> {
    const resolved = this.skills.resolve(spec.skillId, spec.arguments)
      .filter((step) => step.whenMaterialMode === "always" || step.whenMaterialMode === spec.materialMode)
      .map((step) => {
        if (step.task.kind !== "build") return { label: step.label, task: step.task };
        return {
          label: step.label,
          task: {
            ...step.task,
            ...(step.task.placement === "companion"
              ? { placementAnchor: step.task.placementAnchor ?? spec.placementAnchor }
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
      const child = this.#macroChild(parent, step.label, step.task);
      try {
        const result = await runtime.backend.resumeTask(child, {
          onProgress: (progress, message, phase, details) => callbacks.onProgress(
            (index + Math.max(0, Math.min(1, progress))) / steps.length,
            `${step.label}：${message}`,
            phase,
            macroStepProgressDetails(index, step.task, Math.max(0, Math.min(1, progress)), details),
          ),
        }, signal);
        callbacks.onProgress(
          (index + 1) / steps.length,
          `${step.label}：${result}`,
          undefined,
          macroStepProgressDetails(index, step.task, 1),
        );
        index += 1;
      } catch (caught) {
        if (!(atStepBoundary && caught instanceof Error && caught.message === "RECOVERED_TASK_NOT_ACTIVE")) throw caught;
      }
    }

    for (; index < steps.length; index += 1) {
      if (signal.aborted) throw signal.reason instanceof Error ? signal.reason : new Error("技能已取消");
      const step = steps[index]!;
      const child = this.#macroChild(parent, step.label, step.task);
      const result = await runtime.backend.runTask(child, {
        onProgress: (progress, message, phase, details) => callbacks.onProgress(
          (index + Math.max(0, Math.min(1, progress))) / steps.length,
          `${step.label}：${message}`,
          phase,
          macroStepProgressDetails(index, step.task, Math.max(0, Math.min(1, progress)), details),
        ),
      }, signal);
      callbacks.onProgress(
        (index + 1) / steps.length,
        `${step.label}：${result}`,
        undefined,
        macroStepProgressDetails(index, step.task, 1),
      );
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
      void this.#pump(runtime);
    }
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
