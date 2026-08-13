import {
  aiTaskDecisionSchema,
  taskSpecSchema,
  type AiTaskDecision,
  type AiTaskDecisionResult,
  type Companion,
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
  owner: string;
  interactionId: string;
}

export async function commitAiTaskDecision(
  control: MinecraftControlApi,
  context: AiDecisionCommitContext,
  input: AiTaskDecision,
): Promise<AiTaskDecisionResult> {
  const decision = aiTaskDecisionSchema.parse(input);
  let taskId: string | undefined;
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
    const task = await control.assignTask(
      context.companionId,
      await bindTaskToRequester(control, proposed, context.requester),
      owner,
      {
        replaceConflictingDelivery: decision.type === "task"
          && decision.replaceConflictingDelivery === true,
        aiDecisionInteractionId: context.interactionId,
      },
    );
    taskId = task.id;
  }
  return {
    ok: true,
    interactionId: context.interactionId,
    decisionType: decision.type,
    ...(taskId ? { taskId } : {}),
    reply,
  };
}
