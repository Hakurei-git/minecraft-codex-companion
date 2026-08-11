import type { Companion, InventoryItem, TaskRecord } from "@mc/protocol";

type UnknownRecord = Record<string, unknown>;

export interface NpcStatusView {
  health: string;
  food: string;
  mode: string;
  equipment: string[];
  inventory: string[];
  activeTask: string;
  scheduler: string;
  mining: string;
  dragon: string;
}

const EQUIPMENT_SLOT_LABELS: Record<string, string> = {
  main_hand: "主手",
  off_hand: "副手",
  head: "头部",
  chest: "胸甲",
  legs: "护腿",
  feet: "靴子",
};

const GAME_MODE_LABELS: Record<string, string> = {
  survival: "生存",
  creative: "创造",
  adventure: "冒险",
  spectator: "旁观",
};

const STANCE_LABELS: Record<string, string> = {
  follow: "跟随",
  stay: "等待",
  guard: "护卫",
  work: "工作",
};

const MINING_PHASE_LABELS: Record<string, string> = {
  preflight: "准备补给",
  "seek-cave": "寻找洞穴",
  "waiting-entry": "等待安全入口",
  descending: "阶梯下降",
  branching: "分支挖矿",
  vein: "采集矿脉",
  returning: "返回主矿道",
};

function objectValue(value: unknown): UnknownRecord | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as UnknownRecord
    : null;
}

function displayScalar(value: unknown): string | null {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "boolean") return value ? "是" : "否";
  return null;
}

function itemName(value: unknown): string | null {
  if (typeof value === "string") return value;
  const item = objectValue(value);
  if (!item) return null;
  return displayScalar(item.displayName) ?? displayScalar(item.name) ?? displayScalar(item.id);
}

export function equipmentSummary(snapshot: Companion["snapshot"]): string[] {
  const extended = snapshot as Companion["snapshot"] & UnknownRecord;
  const explicit = objectValue(extended.equipment);
  if (explicit) {
    const entries = Object.entries(explicit)
      .map(([slot, value]) => {
        const name = itemName(value);
        return name ? `${EQUIPMENT_SLOT_LABELS[slot] ?? slot}：${name}` : null;
      })
      .filter((entry): entry is string => Boolean(entry));
    if (entries.length) return entries;
  }

  return snapshot.inventory
    .filter((item: InventoryItem) => item.slotType && item.slotType !== "backpack")
    .map((item: InventoryItem) => `${EQUIPMENT_SLOT_LABELS[item.slotType!] ?? item.slotType}：${item.displayName}`);
}

export function inventorySummary(snapshot: Companion["snapshot"]): string[] {
  const totals = new Map<string, { name: string; count: number }>();
  for (const item of snapshot.inventory) {
    const isBackpack = item.slotType === "backpack" || (item.slotType === undefined && item.slot < 27);
    if (!isBackpack) continue;
    const key = `${item.id}\u0000${item.displayName}`;
    const previous = totals.get(key);
    totals.set(key, { name: item.displayName, count: (previous?.count ?? 0) + item.count });
  }
  return [...totals.values()].map((item) => `${item.name} ×${item.count}`);
}

export function modeSummary(snapshot: Companion["snapshot"]): string {
  const extended = snapshot as Companion["snapshot"] & UnknownRecord;
  const rawMode = displayScalar(extended.materialMode) ?? snapshot.gameMode;
  const gameMode = GAME_MODE_LABELS[rawMode] ?? rawMode;
  const stance = snapshot.stance ? (STANCE_LABELS[snapshot.stance] ?? snapshot.stance) : null;
  return [gameMode, stance].filter(Boolean).join(" · ");
}

export function foodSummary(snapshot: Companion["snapshot"]): string {
  const extended = snapshot as Companion["snapshot"] & UnknownRecord;
  const maxFood = displayScalar(extended.maxFood) ?? "20";
  const saturation = typeof snapshot.saturation === "number"
    ? `饱和 ${snapshot.saturation.toFixed(1)}`
    : null;
  const regeneration = extended.automaticEating === true
    ? "自动进食中"
    : extended.naturalRegenerationEnabled === false
      ? "自然回血已关闭"
      : extended.canNaturalRegenerate === true
        ? "可自然回血"
        : extended.canNaturalRegenerate === false && snapshot.health < snapshot.maxHealth
          ? "饱食不足以回血"
          : null;
  return [`${snapshot.food} / ${maxFood}`, saturation, regeneration].filter(Boolean).join(" · ");
}

export function dragonSummary(snapshot: Companion["snapshot"]): string {
  const extended = snapshot as Companion["snapshot"] & UnknownRecord;
  const raw = extended.dragonState ?? extended.dragon;
  const scalar = displayScalar(raw);
  if (scalar) return scalar;
  const dragon = objectValue(raw);
  if (!dragon) return "暂无数据";

  const name = displayScalar(dragon.name) ?? displayScalar(dragon.type) ?? displayScalar(dragon.id) ?? "已绑定龙";
  const coRiding = dragon.coRiding === true || (dragon.playerMounted === true && dragon.mounted === true);
  const state = displayScalar(dragon.status)
    ?? displayScalar(dragon.state)
    ?? (coRiding ? "主人与 NPC 同骑" : dragon.playerMounted === true ? "主人骑乘中" : dragon.mounted === true ? "NPC 骑乘中" : null);
  const health = displayScalar(dragon.health);
  const maxHealth = displayScalar(dragon.maxHealth);
  const healthText = health ? `生命 ${health}${maxHealth ? `/${maxHealth}` : ""}` : null;
  const rideState = dragon.playerRideReady === true
    ? "主人可骑"
    : dragon.seatLocked === true
      ? "座位已锁"
      : dragon.saddled === false
        ? "缺少鞍"
        : null;
  const sharing = dragon.sharedRideEnabled === true ? "与 NPC 共享" : null;
  const control = dragon.autopilot === true || dragon.controlMode === "npc-autopilot"
    ? "NPC 自动驾驶"
    : null;
  return [name, state, healthText, rideState, sharing, control].filter(Boolean).join(" · ") || "暂无数据";
}

export function activeTaskSummary(companion: Companion | undefined, tasks: TaskRecord[]): string {
  if (!companion) return "暂无任务";
  const active = (companion.activeTaskId
    ? tasks.find((task) => task.id === companion.activeTaskId)
    : undefined)
    ?? tasks.find((task) => task.companionId === companion.id && task.status === "running")
    ?? tasks.find((task) => task.companionId === companion.id && task.status === "queued")
    ?? tasks.find((task) => task.companionId === companion.id && task.status === "paused");
  if (!active) return "暂无任务";
  return `${active.spec.kind} · ${active.message}`;
}

export function schedulerSummary(snapshot: Companion["snapshot"]): string {
  const lifecycle = snapshot.taskSchedulerLifecycle;
  const lifecycleLabel = lifecycle === "downed" ? "倒地暂停" : lifecycle === "running" ? "运行中" : "空闲";
  const queued = snapshot.taskQueue ?? [];
  const paused = queued.filter((entry) => entry.phase === "paused").length;
  const priority = snapshot.activeTaskPriority ?? queued.find((entry) => entry.phase === "active")?.priority ?? 0;
  if (!queued.length && lifecycle === undefined) return "暂无调度数据";
  return [lifecycleLabel, priority > 0 ? `优先级 ${priority}` : "", paused > 0 ? `暂停 ${paused}` : ""]
    .filter(Boolean)
    .join(" · ");
}

export function miningSummary(snapshot: Companion["snapshot"]): string {
  const state = snapshot.miningState;
  if (!state) return "未在深层采矿";
  const phase = MINING_PHASE_LABELS[state.phase] ?? state.phase;
  const branch = state.phase === "branching" || state.phase === "returning"
    ? `矿区 ${state.regionIndex + 1} · 分支 ${state.branchIndex + 1} · ${state.branchProgress}/32`
    : `阶梯 ${state.staircaseStep}`;
  return `${phase} · 目标 Y=${state.targetY} · ${branch} · 火把 ${state.placedTorches} · 开掘 ${state.brokenBlocks}`;
}

export function npcStatusView(companion: Companion | undefined, tasks: TaskRecord[]): NpcStatusView {
  if (!companion) {
    return {
      health: "--",
      food: "--",
      mode: "--",
      equipment: [],
      inventory: [],
      activeTask: "暂无任务",
      scheduler: "暂无调度数据",
      mining: "未在深层采矿",
      dragon: "暂无数据",
    };
  }
  const snapshot = companion.snapshot;
  return {
    health: `${snapshot.health} / ${snapshot.maxHealth}`,
    food: foodSummary(snapshot),
    mode: modeSummary(snapshot),
    equipment: equipmentSummary(snapshot),
    inventory: inventorySummary(snapshot),
    activeTask: activeTaskSummary(companion, tasks),
    scheduler: schedulerSummary(snapshot),
    mining: miningSummary(snapshot),
    dragon: dragonSummary(snapshot),
  };
}
