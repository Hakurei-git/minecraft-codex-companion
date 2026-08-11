import { describe, expect, it } from "vitest";
import type { Companion, TaskRecord } from "@mc/protocol";
import {
  activeTaskSummary,
  dragonSummary,
  equipmentSummary,
  foodSummary,
  inventorySummary,
  modeSummary,
  miningSummary,
  npcStatusView,
  schedulerSummary,
} from "./npc-status.js";

const baseCompanion = {
  id: "codex-forge",
  name: "Aster",
  backend: "forge-1.20.1",
  gameVersion: "1.20.1",
  loader: "forge",
  capabilities: [],
  connected: true,
  leaseOwner: null,
  activeTaskId: null,
  snapshot: {
    sequence: 1,
    capturedAt: "2026-08-02T00:00:00.000Z",
    worldId: "world",
    dimension: "minecraft:overworld",
    position: { x: 0, y: 64, z: 0 },
    yaw: 0,
    pitch: 0,
    health: 12,
    maxHealth: 20,
    food: 9,
    air: 300,
    gameMode: "survival",
    timeOfDay: 0,
    weather: "clear",
    inventory: [
      { id: "minecraft:iron_sword", displayName: "铁剑", count: 1, slot: 0, slotType: "main_hand" },
      { id: "minecraft:iron_helmet", displayName: "铁头盔", count: 1, slot: 36, slotType: "head" },
      { id: "minecraft:oak_log", displayName: "橡木原木", count: 5, slot: 1, slotType: "backpack" },
      { id: "minecraft:oak_log", displayName: "橡木原木", count: 3, slot: 2, slotType: "backpack" },
    ],
    nearbyEntities: [],
    status: "follow",
  },
} satisfies Companion;

describe("NPC status presentation", () => {
  it("uses inventory slot types as an equipment fallback", () => {
    expect(equipmentSummary(baseCompanion.snapshot)).toEqual(["主手：铁剑", "头部：铁头盔"]);
  });

  it("shows exact backpack item names and combines matching stacks", () => {
    expect(inventorySummary(baseCompanion.snapshot)).toEqual(["橡木原木 ×8"]);
  });

  it("shows game mode and stance together", () => {
    expect(modeSummary({ ...baseCompanion.snapshot, stance: "follow" })).toBe("生存 · 跟随");
  });

  it("shows NPC material mode, saturation, eating and regeneration readiness", () => {
    const snapshot = {
      ...baseCompanion.snapshot,
      saturation: 1.5,
      materialMode: "creative" as const,
      naturalRegenerationEnabled: true,
      canNaturalRegenerate: false,
      automaticEating: true,
    };
    expect(modeSummary(snapshot)).toBe("创造");
    expect(foodSummary(snapshot)).toBe("9 / 20 · 饱和 1.5 · 自动进食中");
  });

  it("gracefully handles snapshots that do not expose dragon fields", () => {
    expect(dragonSummary(baseCompanion.snapshot)).toBe("暂无数据");
    expect(npcStatusView(baseCompanion, [])).toMatchObject({
      health: "12 / 20",
      food: "9 / 20",
      mode: "生存",
      inventory: ["橡木原木 ×8"],
      dragon: "暂无数据",
      activeTask: "暂无任务",
    });
  });

  it("presents future dragon and equipment extensions without requiring the protocol first", () => {
    const snapshot = {
      ...baseCompanion.snapshot,
      equipment: { chest: { displayName: "钻石胸甲" }, off_hand: "盾牌" },
      dragonState: {
        modId: "saintsdragons" as const,
        entityId: "00000000-0000-0000-0000-000000000001",
        name: "风暴",
        mounted: true,
        ownedByPlayer: true,
        flying: true,
        health: 42,
        maxHealth: 50,
      },
    };
    expect(equipmentSummary(snapshot)).toEqual(["胸甲：钻石胸甲", "副手：盾牌"]);
    expect(dragonSummary(snapshot)).toBe("风暴 · NPC 骑乘中 · 生命 42/50");
  });

  it("shows shared dragon seat readiness and missing saddle diagnostics", () => {
    const ready = {
      ...baseCompanion.snapshot,
      dragonState: {
        modId: "bookofdragons" as const,
        entityId: "00000000-0000-0000-0000-000000000002",
        name: "白霜",
        mounted: false,
        ownedByPlayer: true,
        flying: false,
        saddled: true,
        seatLocked: false,
        playerRideReady: true,
        sharedRideEnabled: true,
      },
    };
    expect(dragonSummary(ready)).toBe("白霜 · 主人可骑 · 与 NPC 共享");

    const missingSaddle = {
      ...ready,
      dragonState: { ...ready.dragonState, saddled: false, playerRideReady: false },
    };
    expect(dragonSummary(missingSaddle)).toBe("白霜 · 缺少鞍 · 与 NPC 共享");

    const ownerMounted = {
      ...ready,
      dragonState: { ...ready.dragonState, playerMounted: true },
    };
    expect(dragonSummary(ownerMounted)).toBe("白霜 · 主人骑乘中 · 主人可骑 · 与 NPC 共享");

    const coRiding = {
      ...ready,
      dragonState: {
        ...ready.dragonState,
        mounted: true,
        playerMounted: true,
        coRiding: true,
        autopilot: true,
        playerInputLocked: true,
        controlMode: "npc-autopilot" as const,
      },
    };
    expect(dragonSummary(coRiding)).toBe("白霜 · 主人与 NPC 同骑 · 主人可骑 · 与 NPC 共享 · NPC 自动驾驶");
  });

  it("finds the selected companion active task", () => {
    const task = {
      id: "d67cda79-cd69-4710-869f-2ed4d9e110a2",
      companionId: baseCompanion.id,
      spec: { kind: "follow", player: "PlayerOne", distance: 3, requestedBy: "test" },
      status: "running",
      progress: 0.5,
      message: "正在跟随 PlayerOne",
      createdAt: "2026-08-02T00:00:00.000Z",
      startedAt: "2026-08-02T00:00:00.000Z",
      finishedAt: null,
      error: null,
    } satisfies TaskRecord;
    expect(activeTaskSummary({ ...baseCompanion, activeTaskId: task.id }, [task])).toBe("follow · 正在跟随 PlayerOne");
  });

  it("keeps paused work visible when no task is active", () => {
    const task = {
      id: "8df971ae-4e93-4d5c-8a92-a626cbcdf15e",
      companionId: baseCompanion.id,
      spec: { kind: "gather", itemId: "#minecraft:logs", count: 16, requestedBy: "test" },
      status: "paused",
      progress: 0.5,
      message: "战斗期间暂停，稍后继续采集",
      createdAt: "2026-08-02T00:00:00.000Z",
      startedAt: "2026-08-02T00:00:00.000Z",
      finishedAt: null,
      error: null,
    } satisfies TaskRecord;
    expect(activeTaskSummary(baseCompanion, [task])).toBe("gather · 战斗期间暂停，稍后继续采集");
  });

  it("shows persisted scheduler priority and paused work", () => {
    expect(schedulerSummary({
      ...baseCompanion.snapshot,
      activeTaskPriority: 80,
      taskSchedulerLifecycle: "running",
      taskQueue: [
        { id: "work-1", kind: "gather", phase: "active", priority: 80, progress: 0.5 },
        { id: "work-2", kind: "follow", phase: "paused", priority: 40, progress: 0.2, pauseReason: "combat" },
      ],
    })).toBe("运行中 · 优先级 80 · 暂停 1");
  });

  it("shows resumable deep-mining phase and physical progress", () => {
    expect(miningSummary({
      ...baseCompanion.snapshot,
      miningState: {
        phase: "branching",
        itemId: "minecraft:diamond",
        targetY: -58,
        staircaseStep: 122,
        branchIndex: 2,
        branchProgress: 11,
        regionIndex: 0,
        brokenBlocks: 249,
        placedTorches: 17,
      },
    })).toBe("分支挖矿 · 目标 Y=-58 · 矿区 1 · 分支 3 · 11/32 · 火把 17 · 开掘 249");
  });
});
