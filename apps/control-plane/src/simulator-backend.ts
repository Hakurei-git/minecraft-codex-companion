import type {
  Capability,
  Companion,
  InventoryItem,
  TaskRecord,
  TaskSpec,
  WorldSnapshot,
} from "@mc/protocol";
import type { CompanionBackend, TaskCallbacks } from "./backend.js";

const SIMULATOR_CAPABILITIES: Capability[] = [
  "chat",
  "observe",
  "move",
  "follow",
  "combat",
  "gather",
  "craft",
  "smelt",
  "farm",
  "storage",
  "fish",
  "sleep",
  "build",
  "commands",
  "dragon-care",
  "multi-bot",
];

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    const abort = () => {
      clearTimeout(timer);
      reject(signal.reason instanceof Error ? signal.reason : new Error("Task cancelled"));
    };
    if (signal.aborted) {
      abort();
      return;
    }
    signal.addEventListener("abort", abort, { once: true });
  });
}

export class SimulatorBackend implements CompanionBackend {
  readonly id: string;
  readonly #name: string;
  #sequence = 0;
  #position = { x: -156, y: 76, z: -62 };
  #food = 18;
  #status = "待命";
  #inventory: InventoryItem[] = [
    { id: "minecraft:bread", displayName: "面包", count: 12, slot: 0 },
    { id: "minecraft:iron_sword", displayName: "铁剑", count: 1, slot: 1 },
    { id: "minecraft:oak_log", displayName: "橡木原木", count: 8, slot: 2 },
  ];

  constructor(id = "codex-sim", name = "Codex") {
    this.id = id;
    this.#name = name;
  }

  capabilities(): readonly Capability[] {
    return SIMULATOR_CAPABILITIES;
  }

  snapshot(): WorldSnapshot {
    this.#sequence += 1;
    return {
      sequence: this.#sequence,
      capturedAt: new Date().toISOString(),
      worldId: "simulated-dragon-world",
      dimension: "minecraft:overworld",
      position: { ...this.#position },
      yaw: 42,
      pitch: 0,
      health: 20,
      maxHealth: 20,
      food: this.#food,
      maxFood: 20,
      saturation: Math.min(5, this.#food),
      materialMode: "survival",
      naturalRegenerationEnabled: true,
      canNaturalRegenerate: false,
      automaticEating: false,
      air: 300,
      gameMode: "survival",
      timeOfDay: 6200,
      weather: "clear",
      inventory: this.#inventory.map((item) => ({ ...item })),
      nearbyEntities: [
        {
          id: "player-owner",
          type: "minecraft:player",
          name: "Player",
          position: { x: -153, y: 76, z: -60 },
          distance: 3.6,
          health: 18,
          disposition: "owner",
        },
        {
          id: "dragon-toothless",
          type: "bookofdragons:night_fury",
          name: "无牙仔",
          position: { x: -149, y: 76, z: -58 },
          distance: 8.2,
          health: 64,
          disposition: "ally",
        },
      ],
      status: this.#status,
    };
  }

  describe(): Companion {
    return {
      id: this.id,
      name: this.#name,
      backend: "simulator",
      gameVersion: "1.20.1",
      loader: "Forge simulator",
      connected: true,
      capabilities: [...SIMULATOR_CAPABILITIES],
      leaseOwner: null,
      activeTaskId: null,
      snapshot: this.snapshot(),
      embodiment: "simulation",
      ownerName: "Player",
      entityUuid: null,
    };
  }

  async runTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string> {
    const labels = this.#taskLabels(task.spec);
    this.#status = labels.start;
    const steps = 8;
    for (let step = 1; step <= steps; step += 1) {
      await delay(240, signal);
      if (task.spec.kind === "move") {
        const ratio = step / steps;
        this.#position = {
          x: this.#position.x + (task.spec.target.x - this.#position.x) * ratio,
          y: this.#position.y + (task.spec.target.y - this.#position.y) * ratio,
          z: this.#position.z + (task.spec.target.z - this.#position.z) * ratio,
        };
      }
      callbacks.onProgress(step / steps, `${labels.progress} ${Math.round((step / steps) * 100)}%`);
    }
    this.#applyResult(task.spec);
    this.#status = "待命";
    return labels.done;
  }

  async sendChat(message: string): Promise<void> {
    this.#status = `说：${message}`;
    await new Promise((resolve) => setTimeout(resolve, 120));
    this.#status = "待命";
  }

  async stop(): Promise<void> {
    this.#status = "已急停";
  }

  #applyResult(spec: TaskSpec): void {
    if (spec.kind === "eat") {
      const food = spec.itemId
        ? this.#inventory.find((item) => item.id === spec.itemId)
        : this.#inventory.find((item) => /bread|melon|apple|carrot|potato|meat|fish/.test(item.id));
      if (food) {
        const eaten = Math.min(food.count, spec.count);
        food.count -= eaten;
        this.#food = Math.min(20, this.#food + eaten * 2);
        this.#inventory = this.#inventory.filter((item) => item.count > 0);
      }
      return;
    }
    if (spec.kind === "provision-food") {
      const existing = this.#inventory.find((item) => item.id === "minecraft:cooked_beef");
      if (existing) existing.count += spec.count;
      else this.#inventory.push({ id: "minecraft:cooked_beef", displayName: "牛排", count: spec.count, slot: this.#inventory.length });
      return;
    }
    if (spec.kind === "ranch") return;
    if (spec.kind === "deliver" || spec.kind === "drop") {
      let remaining = spec.count;
      for (const item of this.#inventory) {
        const matches = spec.itemId === "#minecraft:logs"
          ? /_(?:log|stem)$/.test(item.id)
          : item.id === spec.itemId;
        if (!matches || remaining <= 0) continue;
        const delivered = Math.min(item.count, remaining);
        item.count -= delivered;
        remaining -= delivered;
      }
      this.#inventory = this.#inventory.filter((item) => item.count > 0);
      return;
    }
    if (spec.kind === "fish") {
      const existing = this.#inventory.find((item) => item.id === "minecraft:cod");
      if (existing) existing.count += spec.count;
      else this.#inventory.push({ id: "minecraft:cod", displayName: "生鳕鱼", count: spec.count, slot: this.#inventory.length });
      return;
    }
    if (spec.kind === "gather" || spec.kind === "craft" || spec.kind === "smelt") {
      const existing = this.#inventory.find((item) => item.id === spec.itemId);
      if (existing) {
        existing.count += spec.count;
      } else {
        this.#inventory.push({
          id: spec.itemId,
          displayName: spec.itemId.split(":").at(-1) ?? spec.itemId,
          count: spec.count,
          slot: this.#inventory.length,
        });
      }
    }
  }

  #taskLabels(spec: TaskSpec): { start: string; progress: string; done: string } {
    switch (spec.kind) {
      case "follow":
        return { start: `正在跟随 ${spec.player}`, progress: "跟随中", done: `已开始跟随 ${spec.player}` };
      case "guard":
        return { start: `正在护卫 ${spec.player}`, progress: "检查威胁", done: `已进入 ${spec.radius} 格护卫范围` };
      case "move":
        return { start: "正在前往目标", progress: "移动中", done: "已到达目标位置" };
      case "gather":
        return { start: `正在采集 ${spec.itemId}`, progress: "采集中", done: `已采集 ${spec.count} 个 ${spec.itemId}` };
      case "craft":
        return { start: `正在制作 ${spec.itemId}`, progress: "制作中", done: `已制作 ${spec.count} 个 ${spec.itemId}` };
      case "smelt":
        return { start: `正在熔炼 ${spec.itemId}`, progress: "熔炼中", done: `已熔炼 ${spec.count} 个 ${spec.itemId}` };
      case "farm":
        return { start: `正在照料 ${spec.cropId}`, progress: "农务进行中", done: `已完成 ${spec.action}` };
      case "store":
        return { start: "正在整理物品", progress: "整理箱子", done: "物品已整理" };
      case "retrieve":
        return { start: `正在从仓库取出 ${spec.itemId}`, progress: "取物中", done: `已取出 ${spec.count} 个 ${spec.itemId}` };
      case "organize-storage":
        return { start: "正在整理家园仓库", progress: "分类入库中", done: "家园仓库已整理" };
      case "deliver":
        return { start: `正在把 ${spec.itemId} 交给 ${spec.player}`, progress: "交付中", done: `已交付 ${spec.count} 个 ${spec.itemId}` };
      case "eat":
        return { start: `正在吃 ${spec.itemId ?? "食物"}`, progress: "进食中", done: `已吃下 ${spec.count} 份 ${spec.itemId ?? "食物"}` };
      case "provision-food":
        return { start: "正在寻找食物", progress: "寻食与备粮中", done: `已备好 ${spec.count} 份口粮` };
      case "ranch":
        return { start: "正在建立畜牧围栏", progress: "牵引与照料牲畜", done: `畜牧任务 ${spec.action} 已完成` };
      case "drop":
        return { start: `正在丢出 ${spec.itemId}`, progress: "投掷物品", done: `已丢出 ${spec.count} 个 ${spec.itemId}${spec.player ? ` 给 ${spec.player}` : ""}` };
      case "fish":
        return { start: "正在寻找水面钓鱼", progress: "垂钓中", done: `已完成 ${spec.count} 次垂钓` };
      case "sleep":
        return { start: "正在寻找床铺", progress: "睡眠中", done: "已经睡到天亮" };
      case "explore":
        return { start: "正在探索", progress: "探索中", done: `已探索半径 ${spec.radius} 格` };
      case "combat":
        return { start: `正在搜索 ${spec.targetType}`, progress: "战斗中", done: "威胁已清除" };
      case "dragon":
        return { start: `正在执行养龙动作 ${spec.action}`, progress: "与龙互动", done: `养龙动作 ${spec.action} 已完成` };
      case "build":
        return { start: `正在建造 ${spec.planId}`, progress: "放置方块", done: "建筑已完成" };
      case "macro":
        return { start: `正在执行技能 ${spec.skillId}`, progress: "执行技能", done: `技能 ${spec.skillId} 已完成` };
    }
  }
}
