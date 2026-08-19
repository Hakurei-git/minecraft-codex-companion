import {
  aiTaskDecisionSchema,
  taskSpecSchema,
  type AiTaskDecision,
  type AiTaskDecisionResult,
  type Companion,
  type GoalSpec,
  type InventoryItem,
  type TaskRecord,
  type TaskSpec,
} from "@mc/protocol";
import type { MinecraftControlApi } from "./control-api.js";

const REQUESTER_ARGUMENT_KEYS = new Set([
  "deliverto",
  "player",
  "playername",
  "recipient",
  "recipientplayer",
  "requestedby",
  "requester",
  "targetplayer",
]);
const EXACT_SKILL_PLACEHOLDER = /^\$\{([A-Za-z][A-Za-z0-9_]{0,47})\}$/u;

function normalizedPlayerName(value: string): string {
  return value.trim().toLocaleLowerCase("en-US");
}

function bindRequesterArguments(value: unknown, requester: string): unknown {
  if (Array.isArray(value)) return value.map((item) => bindRequesterArguments(item, requester));
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, child]) => {
    const normalized = key.replaceAll(/[-_]/gu, "").toLocaleLowerCase("en-US");
    return [key, REQUESTER_ARGUMENT_KEYS.has(normalized)
      ? requester
      : bindRequesterArguments(child, requester)];
  }));
}

export async function bindTaskToRequester(
  control: MinecraftControlApi,
  spec: TaskSpec,
  requester: string,
): Promise<TaskSpec> {
  const bound: Record<string, unknown> = { ...spec, requestedBy: requester };
  if (["follow", "guard", "deliver", "drop"].includes(spec.kind)) bound.player = requester;
  if (spec.kind === "craft" && spec.deliverTo !== undefined) bound.deliverTo = requester;
  if (spec.kind === "provision-food" && (spec.destination === "player" || spec.player !== undefined)) {
    bound.player = requester;
  }
  if (spec.kind === "macro") {
    const argumentsBound = bindRequesterArguments(spec.arguments, requester) as Record<string, unknown>;
    const skill = await control.getSkill(spec.skillId);
    for (const step of skill.steps) {
      const task = step.task as Record<string, unknown>;
      for (const field of ["player", "deliverTo"] as const) {
        const target = task[field];
        if (target === undefined) continue;
        if (typeof target !== "string") throw new Error(`技能 ${spec.skillId} 包含无法绑定的玩家目标`);
        const placeholder = target.match(EXACT_SKILL_PLACEHOLDER)?.[1];
        if (placeholder) {
          argumentsBound[placeholder] = requester;
          continue;
        }
        if (normalizedPlayerName(target) !== normalizedPlayerName(requester)) {
          throw new Error(`技能 ${spec.skillId} 包含固定的其他玩家目标`);
        }
      }
    }
    bound.arguments = argumentsBound;
  }
  return taskSpecSchema.parse(bound);
}

function inventorySlotLabel(item: InventoryItem): string {
  const inferred = item.slotType ?? (
    item.slot < 27 ? "backpack"
      : item.slot === 27 ? "main_hand"
        : item.slot === 28 ? "off_hand"
          : item.slot === 29 ? "head"
            : item.slot === 30 ? "chest"
              : item.slot === 31 ? "legs"
                : item.slot === 32 ? "feet"
                  : undefined
  );
  return ({
    backpack: "背包",
    main_hand: "主手",
    off_hand: "副手",
    head: "头部槽",
    chest: "胸甲槽",
    legs: "护腿槽",
    feet: "靴子槽",
  } as Record<string, string>)[inferred ?? ""] ?? `槽位 ${item.slot}`;
}

function taskKindLabel(kind: string): string {
  return ({
    combat: "战斗护主",
    gather: "采集",
    deliver: "交付物品",
    eat: "进食",
    "provision-food": "寻找食物",
    ranch: "畜牧",
    fish: "钓鱼",
    craft: "制作",
    smelt: "烧炼",
    farm: "农务",
    build: "建造",
    dragon: "骑龙",
  } as Record<string, string>)[kind] ?? kind;
}

function taskStepSummary(task: TaskRecord | null): string {
  if (!task || task.currentStepIndex === undefined || !task.currentStepKind) return "";
  const countText = task.completedCount !== undefined && task.targetCount !== undefined
    ? `，已完成 ${task.completedCount}/${task.targetCount}`
    : task.targetCount !== undefined ? `，目标数量 ${task.targetCount}` : "";
  const retainedText = task.retainedCount === undefined ? "" : `，实际保有 ${task.retainedCount}`;
  const progressText = task.stepProgress === undefined ? "" : `，步骤进度 ${Math.round(task.stepProgress * 100)}%`;
  return `；当前第 ${task.currentStepIndex + 1} 步${taskKindLabel(task.currentStepKind)}${progressText}${countText}${retainedText}`;
}

function personaInspectionReply(personaReply: string, factualReply: string): string {
  const prefix = personaReply.trim().replace(/[。！？!?]+$/u, "");
  if (!prefix || /^(?:状态|结果|查询结果|实时状态)$/u.test(prefix)) return factualReply;
  const maxPrefixLength = Math.max(0, 220 - factualReply.length - 2);
  if (maxPrefixLength < 1) return factualReply.slice(0, 220);
  return `${prefix.slice(0, maxPrefixLength)}。${factualReply}`.slice(0, 220);
}

export function inspectionReply(
  companion: Companion,
  scope: "activity" | "vitals" | "inventory" | "full",
  activeTask: TaskRecord | null = null,
): string {
  const snapshot = companion.snapshot;
  const activeKind = snapshot.activeTaskKind?.trim() || activeTask?.currentStepKind || activeTask?.spec.kind;
  const progress = snapshot.activeTaskProgress ?? activeTask?.progress;
  const taskProgress = progress === undefined ? "" : ` ${Math.round(progress * 100)}%`;
  const paused = snapshot.pausedTaskCount ? `，暂停任务 ${snapshot.pausedTaskCount}` : "";
  const regeneration = snapshot.automaticEating
    ? "自动进食中"
    : snapshot.naturalRegenerationEnabled === false
      ? "自然回血已关闭"
      : snapshot.canNaturalRegenerate === true
        ? "当前可自然回血"
        : snapshot.canNaturalRegenerate === false && snapshot.health < snapshot.maxHealth
          ? "当前饱食不足以自然回血"
          : null;
  const vitals = [
    `生命 ${snapshot.health}/${snapshot.maxHealth}`,
    `饱食度 ${snapshot.food}/${snapshot.maxFood ?? 20}`,
    ...(snapshot.saturation === undefined ? [] : [`饱和度 ${snapshot.saturation.toFixed(1)}`]),
    ...(regeneration ? [regeneration] : []),
    ...(snapshot.armor === undefined ? [] : [`护甲 ${snapshot.armor}`]),
    ...(snapshot.stance ? [`姿态 ${snapshot.stance}`] : []),
    `状态：${snapshot.status}`,
    `任务：${activeKind ? `${activeKind}${taskProgress}${paused}` : "无"}`,
  ].join("，");
  const inventory = snapshot.inventory.length === 0
    ? "物品：空"
    : `物品：${snapshot.inventory.map((item) => `${item.displayName}×${item.count}（${inventorySlotLabel(item)}）`).join("、")}`;
  if (scope === "activity") {
    const active = snapshot.taskQueue?.find((task) => task.phase === "active");
    const kind = active?.kind ?? activeKind;
    const activeProgress = active?.progress ?? progress;
    const progressText = activeProgress === undefined ? "" : `，进度 ${Math.round(activeProgress * 100)}%`;
    if (!kind) return `我现在没有执行任务，处于待命状态。实时状态：${snapshot.status}`;
    return `我正在${taskKindLabel(kind)}${progressText}${taskStepSummary(activeTask)}。实时动作：${snapshot.status}`;
  }
  if (scope === "vitals") return vitals;
  if (scope === "inventory") return inventory;
  return `${vitals}。${inventory}`;
}

export interface AiDecisionCommitContext {
  companionId: string;
  requester: string;
  message?: string | undefined;
  owner: string;
  interactionId: string;
}

function taskIntentText(spec: TaskSpec): string {
  if (spec.kind === "macro") return spec.skillId;
  if ("itemId" in spec && typeof spec.itemId === "string") return spec.itemId;
  if (spec.kind === "farm") return `${spec.action} ${spec.cropId}`;
  if (spec.kind === "ranch") return `${spec.action} ${spec.animalType}`;
  if (spec.kind === "build") return spec.planId;
  return spec.kind;
}

function shouldAttemptAgentGoal(spec: TaskSpec, objective: string): boolean {
  if (spec.kind === "macro") {
    return spec.skillId.startsWith("build.")
      || spec.skillId.startsWith("craft.")
      || spec.skillId.startsWith("dragon.")
      || spec.skillId === "life.crop-cycle"
      || spec.skillId === "life.establish-ranch"
      || spec.skillId === "life.craft-and-place-bed";
  }
  if (spec.kind === "craft") {
    return spec.itemId.startsWith("minecraft:")
      && !["minecraft:pickaxe", "minecraft:axe", "minecraft:shovel", "minecraft:hoe", "minecraft:melee_weapon"].includes(spec.itemId);
  }
  if (["farm", "ranch", "organize-storage", "provision-food", "dragon"].includes(spec.kind)) return true;
  return /(?:钻石镐|diamond pickaxe|火把|torch|床|bed|农田|农场|田地|牧场|畜牧|围栏|仓库|储物|食物|肉|food|meat|装备|护甲|防具|武器|铁剑|盾牌|剪刀|水桶|桶|工作台|熔炉|建造|房子|小屋|住宅|刷石机|刷怪|树场|瞭望塔|骑龙|龙|dragon)/iu
    .test(objective);
}

function goalTitle(objective: string, spec: TaskSpec): string {
  const trimmed = objective.trim().replace(/\s+/gu, " ");
  if (trimmed) return trimmed.slice(0, 160);
  return `AI ${spec.kind} ${taskIntentText(spec)}`.slice(0, 160);
}

async function tryCommitViaAgentGoal(
  control: MinecraftControlApi,
  context: AiDecisionCommitContext,
  decision: Extract<AiTaskDecision, { type: "task" | "skill" }>,
  proposed: TaskSpec,
  owner: string,
): Promise<{ goalId: string; taskId?: string } | null> {
  const objective = [
    context.message?.trim(),
    decision.summary.trim(),
    taskIntentText(proposed),
  ].filter(Boolean).join(" | ").slice(0, 500);
  if (!shouldAttemptAgentGoal(proposed, objective)) return null;
  const spec: GoalSpec = {
    title: goalTitle(objective, proposed),
    objective: objective || goalTitle("", proposed),
    requestedBy: context.requester,
    source: "t-chat",
    priority: proposed.priority ?? 100,
    mode: "smart",
    constraints: [
      "Route this AI decision through the local Agent WorkGraph and single-writer task executor.",
      "Do not upload files, screenshots, provider keys, local paths, account data, prompts, logs, or raw world saves.",
    ],
    taskHints: [],
    metadata: {
      routedFrom: "mc_submit_ai_decision",
      aiDecisionType: decision.type,
      proposedTaskKind: proposed.kind,
      ...(proposed.kind === "macro" ? { proposedSkillId: proposed.skillId } : {}),
      ...("itemId" in proposed && typeof proposed.itemId === "string" ? { proposedItemId: proposed.itemId } : {}),
    },
  };
  const goal = await control.submitGoal(context.companionId, spec, owner);
  const plan = await control.getPlan(goal.id);
  if (goal.plannedAt === null || plan.nodes.some((node) => node.id === "await_plan" && node.status === "blocked")) {
    await control.cancelGoal(goal.id, "Local Agent planner did not recognize this AI decision; falling back to direct task.");
    return null;
  }
  const advanced = await control.advanceGoal(goal.id, owner, {
    aiDecisionInteractionId: context.interactionId,
  });
  return {
    goalId: goal.id,
    ...(advanced.task?.id ? { taskId: advanced.task.id } : {}),
  };
}

export async function commitAiTaskDecision(
  control: MinecraftControlApi,
  context: AiDecisionCommitContext,
  input: AiTaskDecision,
): Promise<AiTaskDecisionResult> {
  const decision = aiTaskDecisionSchema.parse(input);
  let taskId: string | undefined;
  let goalId: string | undefined;
  let reply = decision.reply;
  if (decision.type === "inspect") {
    const companion = await control.getCompanion(context.companionId);
    const activeTask = companion.activeTaskId
      ? await Promise.resolve(control.getTask(companion.activeTaskId)).catch(() => null)
      : null;
    reply = personaInspectionReply(decision.reply, inspectionReply(companion, decision.scope, activeTask));
  } else if (decision.type === "control") {
    await control.controlCompanion(context.companionId, decision.action, {
      aiDecisionInteractionId: context.interactionId,
    });
  } else if (decision.type === "retry-build") {
    const task = await control.retryLatestBuildTask(
      context.companionId,
      context.owner,
      context.requester,
      { aiDecisionInteractionId: context.interactionId },
    );
    taskId = task.id;
  } else if (decision.type === "task" || decision.type === "skill") {
    const proposed: TaskSpec = decision.type === "task"
      ? decision.spec
      : taskSpecSchema.parse({
          kind: "macro",
          skillId: decision.skillId,
          arguments: decision.arguments,
          requestedBy: context.requester,
          ...(decision.materialMode ? { materialMode: decision.materialMode } : {}),
          ...(decision.materialPreference ? { materialPreference: decision.materialPreference } : {}),
        });
    const companion = await control.getCompanion(context.companionId);
    const owner = companion.leaseOwner ?? context.owner;
    const bound = await bindTaskToRequester(control, proposed, context.requester);
    const agentGoal = await tryCommitViaAgentGoal(control, context, decision, bound, owner);
    if (agentGoal) {
      goalId = agentGoal.goalId;
      taskId = agentGoal.taskId;
    } else {
      const task = await control.assignTask(
        context.companionId,
        bound,
        owner,
        {
          replaceConflictingDelivery: decision.type === "task"
            && decision.replaceConflictingDelivery === true,
          aiDecisionInteractionId: context.interactionId,
        },
      );
      taskId = task.id;
    }
  }
  return {
    ok: true,
    interactionId: context.interactionId,
    decisionType: decision.type,
    ...(goalId ? { goalId } : {}),
    ...(taskId ? { taskId } : {}),
    reply,
  };
}
