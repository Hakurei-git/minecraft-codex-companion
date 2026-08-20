import { describe, expect, it } from "vitest";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { BackendTaskFailure, type CompanionBackend, type TaskCallbacks } from "./backend.js";
import { ControlService } from "./control-service.js";
import { SimulatorBackend } from "./simulator-backend.js";
import type { CompanionAction, TaskRecord, WorldSnapshot } from "@mc/protocol";

type TestBounds = {
  min: { x: number; y: number; z: number };
  max: { x: number; y: number; z: number };
};

function horizontalBoundsGap(left: TestBounds, right: TestBounds): number {
  const dx = left.max.x < right.min.x
    ? right.min.x - left.max.x
    : right.max.x < left.min.x
      ? left.min.x - right.max.x
      : 0;
  const dz = left.max.z < right.min.z
    ? right.min.z - left.max.z
    : right.max.z < left.min.z
      ? left.min.z - right.max.z
      : 0;
  return Math.sqrt(dx ** 2 + dz ** 2);
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function withService<T>(
  run: (service: ControlService) => T | Promise<T>,
  backend: CompanionBackend = new SimulatorBackend(),
): Promise<T> {
  const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-goal-planner-"));
  try {
    const service = new ControlService({ stateDirectory });
    service.registerBackend(backend);
    return await run(service);
  } finally {
    await rm(stateDirectory, { recursive: true, force: true });
  }
}

class FacilitySnapshotBackend extends SimulatorBackend {
  override snapshot(): WorldSnapshot {
    const base = super.snapshot();
    return {
      ...base,
      homeState: {
        dimension: base.dimension,
        position: { x: 10, y: 64, z: 10 },
        temporary: false,
        coreRadius: 24,
        boundarySource: "radius-fallback",
        confidence: 0.15,
      },
      miningState: {
        phase: "descending",
        itemId: "minecraft:diamond",
        targetY: -58,
        staircaseStep: 8,
        branchIndex: 1,
        branchProgress: 4,
        regionIndex: 0,
        brokenBlocks: 24,
        placedTorches: 3,
        entrance: { x: -4, y: 64, z: -4 },
        lastSafeStand: { x: -4, y: 42, z: -4 },
      },
      dragonState: {
        modId: "bookofdragons",
        entityId: "00000000-0000-4000-8000-00000000d001",
        name: "Test Dragon",
        mounted: false,
        ownedByPlayer: true,
        flying: false,
      },
      observedFacilities: [
        {
          type: "workstation",
          name: "Observed crafting table",
          position: { x: 3, y: 64, z: 3 },
          tags: ["crafting_table", "base"],
          properties: { blockId: "minecraft:crafting_table" },
        },
        {
          type: "workstation",
          name: "Observed furnace",
          position: { x: 4, y: 64, z: 3 },
          tags: ["furnace", "base"],
          properties: { blockId: "minecraft:furnace" },
        },
        {
          type: "storage",
          name: "Observed home chest",
          position: { x: 5, y: 64, z: 3 },
          tags: ["home", "chest"],
          properties: { blockId: "minecraft:chest" },
        },
        {
          type: "farm",
          name: "Observed outdoor crop farmland",
          position: { x: 28, y: 64, z: -24 },
          tags: ["crop", "farmland"],
          properties: { blockId: "minecraft:farmland" },
        },
      ],
    };
  }
}

class PendingGoalBackend extends SimulatorBackend {
  readonly controlActions: CompanionAction[] = [];

  async control(action: CompanionAction): Promise<void> {
    this.controlActions.push(action);
  }

  override runTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string> {
    callbacks.onProgress(0.25, `pending ${task.id}`, "active");
    return new Promise((_resolve, reject) => {
      const abort = () => reject(signal.reason instanceof Error ? signal.reason : new Error("cancelled"));
      if (signal.aborted) abort();
      else signal.addEventListener("abort", abort, { once: true });
    });
  }
}

class RecoveringGoalBackend extends SimulatorBackend {
  readonly resumedIds: string[] = [];
  readonly continuedSpecs: string[] = [];

  resumeTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.resumedIds.push(task.id);
    callbacks.onProgress(1, `resumed ${task.id}`, "active");
    return Promise.resolve(`resumed ${task.id}`);
  }

  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.continuedSpecs.push(task.spec.kind === "craft" ? task.spec.itemId : task.spec.kind);
    callbacks.onProgress(1, `continued ${task.id}`, "active");
    return Promise.resolve(`continued ${task.id}`);
  }
}

class FailOnceGoalTaskBackend extends SimulatorBackend {
  readonly attemptedTaskIds: string[] = [];

  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.attemptedTaskIds.push(task.id);
    if (this.attemptedTaskIds.length === 1) {
      callbacks.onProgress(0.42, `transient failure ${task.id}`, "active");
      return Promise.reject(new Error("transient retryable task failure"));
    }
    callbacks.onProgress(1, `recovered ${task.id}`, "active");
    return Promise.resolve(`recovered ${task.id}`);
  }
}

class MissingRememberedFarmBackend extends SimulatorBackend {
  readonly tasks: TaskRecord[] = [];
  readonly home = { x: -156, y: 76, z: -62 };
  #position = { ...this.home };
  #failedRememberedFarm = false;

  override snapshot(): WorldSnapshot {
    const base = super.snapshot();
    return {
      ...base,
      position: { ...this.#position },
      ownerPosition: { ...this.home },
      homeState: {
        dimension: base.dimension,
        position: { ...this.home },
        temporary: false,
        coreRadius: 24,
        boundarySource: "radius-fallback",
        confidence: 0.15,
      },
    };
  }

  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.tasks.push(structuredClone(task));
    if (task.spec.kind === "farm" && !this.#failedRememberedFarm) {
      this.#failedRememberedFarm = true;
      callbacks.onProgress(0, "remembered farm missing", "active");
      return Promise.reject(new BackendTaskFailure(
        "FARM_TARGET_NOT_FOUND",
        "remembered farm missing",
        true,
      ));
    }
    if (task.spec.kind === "build" && task.spec.placementAnchor) {
      this.#position = { ...task.spec.placementAnchor };
    }
    callbacks.onProgress(1, `${task.spec.kind} complete`, "active");
    return Promise.resolve(`${task.spec.kind} complete`);
  }
}

async function waitForGoalStatus(
  service: ControlService,
  goalId: string,
  status: "succeeded" | "failed" | "cancelled",
  timeoutMs = 5_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (service.getGoal(goalId).status === status) return;
    await delay(100);
  }
  throw new Error(`Timed out waiting for goal ${goalId} to become ${status}; current=${service.getGoal(goalId).status}`);
}

describe("local Agent goal planner", () => {
  it("turns a no-hint diamond pickaxe goal into a deep-mining work chain", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "Craft a diamond pickaxe",
        objective: "Craft a diamond pickaxe and deliver it to me.",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(goal.plannedAt).toBeTruthy();
      expect(goal.message).toContain("Goal planned locally");
      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_existing_workstation",
        "query_existing_furnace",
        "prepare_food_reserve",
        "craft_iron_pickaxe",
        "craft_torches",
        "query_existing_diamond_mine",
        "craft_ladders",
        "prepare_spare_wood",
        "mine_diamonds",
        "craft_diamond_pickaxe",
      ]);
      expect(plan.nodes.find((node) => node.id === "craft_iron_pickaxe")).toMatchObject({
        checkpoint: {
          preferredWorkstationQueryNodeId: "query_existing_workstation",
          preferredFurnaceQueryNodeId: "query_existing_furnace",
        },
      });
      expect(plan.nodes.find((node) => node.id === "craft_ladders")).toMatchObject({
        checkpoint: {
          rememberedMineQueryNodeId: "query_existing_diamond_mine",
          requireVerifiedPhysicalReuseBeforeSkipping: true,
        },
      });
      expect(plan.nodes.find((node) => node.id === "prepare_spare_wood")).toMatchObject({
        action: { kind: "task", spec: { kind: "gather", count: 64, countMode: "inventory-total" } },
        checkpoint: { keepInBackpack: true, targetCount: 64, afterSupplyCrafting: true },
        dependsOn: ["craft_ladders"],
      });
      expect(plan.nodes.find((node) => node.id === "mine_diamonds")?.dependsOn).toEqual(["prepare_spare_wood"]);
      expect(plan.nodes.at(-1)).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:diamond_pickaxe",
            count: 1,
            deliverTo: "PlayerOne",
          },
        },
      });
      expect(plan.nodes.find((node) => node.id === "mine_diamonds")).toMatchObject({
        action: { kind: "task", spec: { kind: "gather", count: 3, countMode: "inventory-total" } },
        checkpoint: {
          targetY: -58,
          discardLowValueStoneWhenInventoryFull: true,
          requiredTool: "minecraft:iron_pickaxe",
        },
      });
    });
  });

  it("plans torch goals by preferring nearby coal before crafting", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "我要64个火把",
        objective: "我要64个火把，附近有煤矿就先采附近的。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_existing_workstation",
        "find_coal_for_torches",
        "prepare_sticks_for_torches",
        "craft_torches",
      ]);
      expect(plan.nodes[2]).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "gather",
            itemId: "minecraft:coal",
            count: 16,
            countMode: "inventory-total",
          },
        },
        checkpoint: { preferNearbyResource: true },
      });
      expect(plan.nodes[3]).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "gather",
            itemId: "#minecraft:logs",
            countMode: "inventory-total",
          },
        },
      });
      expect(plan.nodes[4]).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:torch",
            count: 64,
          },
        },
      });
    });
  });

  it("plans crop farm goals with prerequisites and facility memory verification", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "建造田地",
        objective: "建造田地，然后以后可以在同一个农田自动播种收获。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_existing_farm",
        "find_or_craft_hoe",
        "find_or_craft_bucket",
        "build_crop_farm",
        "verify_farm_memory",
      ]);
      expect(plan.nodes[1]).toMatchObject({
        action: {
          kind: "query-facilities",
          type: "farm",
          tags: ["crop"],
        },
      });
      expect(plan.nodes[3]).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:bucket",
          },
        },
        checkpoint: {
          preferExistingItem: true,
          itemRole: "water_bucket",
        },
      });
      expect(plan.nodes[3]?.checkpoint).not.toHaveProperty("skipIfFacilityQueryNodeId");
      expect(plan.nodes[4]).toMatchObject({
        action: {
          kind: "skill",
          skillId: "build.crop-farm",
          arguments: {
            cropId: "minecraft:wheat",
            radius: 12,
          },
        },
        checkpoint: {
          shouldReuseExistingFacility: false,
          forceNewFacility: true,
          shouldRegisterFacilityAfterBuild: true,
        },
      });
      expect(plan.nodes[4]?.checkpoint).not.toHaveProperty("skipIfFacilityQueryNodeId");
      expect(plan.nodes[5]).toMatchObject({
        action: {
          kind: "verify",
          expectation: expect.stringContaining("stored as a farm facility"),
        },
      });
    });
  });

  it("recalls the companion before a requested farm work chain", async () => {
    const backend = new PendingGoalBackend();
    await withService(async (service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "先回来再建造农田",
        objective: "先停止其他目标并回到我身边，然后准备锄头、水桶和水，建造农田并种上小麦。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "recall_companion",
        "query_existing_farm",
        "find_or_craft_hoe",
        "find_or_craft_bucket",
        "build_crop_farm",
        "verify_farm_memory",
      ]);
      expect(plan.nodes[1]).toMatchObject({
        action: { kind: "control", action: "recall" },
        dependsOn: ["knowledge_lookup"],
        checkpoint: {
          controlPriority: true,
          returnBeforeWork: true,
          resumePlannedWorkAfterRecall: true,
        },
      });
      expect(plan.nodes[2]?.dependsOn).toEqual(["recall_companion"]);

      const advanced = await service.advanceGoal(goal.id, "agent-test");
      expect(advanced.task).toMatchObject({
        status: "running",
        spec: { kind: "craft", itemId: "minecraft:stone_hoe" },
      });
      expect(backend.controlActions).toEqual(["recall"]);
      expect(advanced.plan.nodes[1]).toMatchObject({ status: "succeeded", progress: 1 });
      service.cancelGoal(goal.id, "farm recall test complete");
      expect(service.getTask(advanced.task!.id)).toMatchObject({
        status: "cancelled",
        message: "farm recall test complete",
      });
      expect(service.getGoal(goal.id)).toMatchObject({
        status: "cancelled",
        activeWorkNodeId: null,
      });
    }, backend);
  });

  it("reuses remembered crop farms instead of repeating farm prerequisites and construction", async () => {
    await withService(async (service) => {
      const backend = service.getCompanion("codex-sim");
      const facility = service.registerFacility({
        worldId: backend.snapshot.worldId,
        dimension: "minecraft:overworld",
        type: "farm",
        name: "Home crop farm",
        position: { x: 12, y: 64, z: 12 },
        tags: ["crop", "home"],
        owner: "PlayerOne",
        properties: {},
      });
      const goal = service.submitGoal("codex-sim", {
        title: "照料田地",
        objective: "照料田地，继续用之前那个农田。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.task).toMatchObject({
        spec: {
          kind: "farm",
          action: "cycle",
          cropId: "minecraft:wheat",
        },
      });
      expect(advanced.plan.nodes).toMatchObject([
        { id: "knowledge_lookup", status: "succeeded" },
        {
          id: "query_existing_farm",
          status: "succeeded",
          checkpoint: {
            facilityCount: 1,
            firstFacilityId: facility.id,
            firstFacilityName: "Home crop farm",
          },
        },
        { id: "find_or_craft_hoe", status: "skipped", checkpoint: { reusedFacilityId: facility.id } },
        { id: "find_or_craft_bucket", status: "skipped", checkpoint: { reusedFacilityId: facility.id } },
        { id: "build_crop_farm", status: "skipped", checkpoint: { reusedFacilityId: facility.id } },
        { id: "verify_farm_memory", status: "succeeded" },
        { id: "operate_crop_farm", status: "running" },
      ]);
      await waitForGoalStatus(service, goal.id, "succeeded");
      expect(service.getGoal(goal.id)).toMatchObject({ status: "succeeded", progress: 1 });
      expect(service.listFacilities(backend.snapshot.worldId)[0]).toMatchObject({
        id: facility.id,
        lastUsedAt: expect.any(String),
      });
    });
  });

  it("creates and records a distinct crop farm when the player explicitly asks for a new one", async () => {
    await withService(async (service) => {
      const backend = service.getCompanion("codex-sim");
      const existing = service.registerFacility({
        worldId: backend.snapshot.worldId,
        dimension: backend.snapshot.dimension,
        type: "farm",
        name: "Nearby observed crop block from an older field",
        position: { x: -90, y: 76, z: -58 },
        tags: ["crop", "farmland"],
        owner: "PlayerOne",
        properties: { source: "snapshot.observedFacilities" },
      });
      const goal = service.submitGoal("codex-sim", {
        title: "新建农田",
        objective: "Luna，帮我新建一块农田。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      await service.advanceGoal(goal.id, "agent-test");
      await waitForGoalStatus(service, goal.id, "succeeded", 20_000);

      const plan = service.getPlan(goal.id);
      expect(plan.nodes.find((node) => node.id === "query_existing_farm")).toMatchObject({
        status: "succeeded",
        checkpoint: { facilityCount: 1, firstFacilityId: existing.id },
      });
      expect(plan.nodes.find((node) => node.id === "build_crop_farm")).toMatchObject({
        status: "succeeded",
        checkpoint: { forceNewFacility: true, shouldReuseExistingFacility: false },
      });
      const farms = service.listFacilities(backend.snapshot.worldId)
        .filter((facility) => facility.type === "farm");
      expect(farms.some((facility) => facility.id === existing.id)).toBe(true);
      const created = farms.find((facility) => facility.sourceGoalId === goal.id && facility.id !== existing.id);
      expect(created).toBeDefined();
      expect(created?.bounds).toBeDefined();
      expect(created!.position.x).toBeGreaterThanOrEqual(created!.bounds!.min.x);
      expect(created!.position.x).toBeLessThanOrEqual(created!.bounds!.max.x);
      expect(created!.position.z).toBeGreaterThanOrEqual(created!.bounds!.min.z);
      expect(created!.position.z).toBeLessThanOrEqual(created!.bounds!.max.z);
    });
  }, 25_000);

  it("invalidates an unrecognized house-side farm and creates one new recorded field outdoors", async () => {
    const backend = new MissingRememberedFarmBackend();
    await withService(async (service) => {
      const snapshot = backend.snapshot();
      const mistakenHouseRecord = service.registerFacility({
        worldId: snapshot.worldId,
        dimension: snapshot.dimension,
        type: "farm",
        name: "Legacy house misclassified as crop farm",
        position: { ...backend.home },
        tags: ["crop", "farmland"],
        owner: "PlayerOne",
        properties: { source: "agent.workGraph" },
      });
      const goal = service.submitGoal(backend.id, {
        title: "照料已有农田",
        objective: "照料并补种已有农田；如果旧农田无法识别，就在当前房屋外新建并记录一块农田。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: ["不得在当前房屋内建造农田"],
        taskHints: [],
        metadata: {},
      });

      await service.advanceGoal(goal.id, "antigravity-autoplay");
      await waitForGoalStatus(service, goal.id, "succeeded", 10_000);

      const facilities = service.listFacilities(snapshot.worldId);
      expect(facilities.find((facility) => facility.id === mistakenHouseRecord.id)).toMatchObject({
        properties: {
          invalidForCropWork: true,
          validationFailureCode: "FARM_TARGET_NOT_FOUND",
        },
      });
      const recorded = facilities.find((facility) => (
        facility.sourceGoalId === goal.id
        && facility.type === "farm"
        && facility.properties.invalidForCropWork !== true
      ));
      expect(recorded).toBeDefined();
      expect(recorded!.bounds).toBeDefined();
      const distanceFromHouse = horizontalBoundsGap(recorded!.bounds!, {
        min: backend.home,
        max: backend.home,
      });
      expect(distanceFromHouse).toBeGreaterThanOrEqual(16);
      expect(distanceFromHouse).toBeLessThanOrEqual(40);

      const buildAttempt = backend.tasks.find((task) => task.spec.kind === "build");
      expect(buildAttempt?.spec).toMatchObject({
        kind: "build",
        sitePolicy: "home-compound",
        placementAnchor: recorded!.position,
        compoundPlacement: { zone: "production", minDistance: 16, maxDistance: 40 },
      });
      const farmAttempts = backend.tasks.filter((task) => task.spec.kind === "farm");
      expect(farmAttempts.length).toBeGreaterThanOrEqual(2);
      expect(farmAttempts.at(-1)?.spec).toMatchObject({
        kind: "farm",
        placementAnchor: recorded!.position,
      });
      expect(service.getPlan(goal.id).nodes.every((node) => (
        node.status === "succeeded" || node.status === "skipped"
      ))).toBe(true);
    }, backend);
  }, 15_000);

  it("records newly completed planned facilities so later goals can reuse them", async () => {
    await withService(async (service) => {
      const snapshot = service.getCompanion("codex-sim").snapshot;
      const goal = service.submitGoal("codex-sim", {
        title: "建造田地",
        objective: "建造田地，然后以后可以在同一个农田自动播种收获。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      await service.advanceGoal(goal.id, "agent-test");
      await waitForGoalStatus(service, goal.id, "succeeded", 20_000);

      const farm = service.listFacilities(snapshot.worldId)
        .find((facility) => facility.sourceGoalId === goal.id && facility.type === "farm");
      expect(farm).toMatchObject({
        name: "Agent farm: crop farm",
        tags: expect.arrayContaining(["farm", "crop", "build.crop-farm", "agent-goal"]),
        position: { x: expect.any(Number), y: snapshot.position.y, z: expect.any(Number) },
        properties: {
          source: "agent.workGraph",
          nodeId: "build_crop_farm",
          taskKind: "macro",
          skillId: "build.crop-farm",
        },
      });
      expect(farm!.bounds).toBeDefined();
      const homeGap = horizontalBoundsGap(farm!.bounds!, { min: snapshot.position, max: snapshot.position });
      expect(homeGap).toBeGreaterThanOrEqual(16);
      expect(homeGap).toBeLessThanOrEqual(40);

      const laterGoal = service.submitGoal("codex-sim", {
        title: "照料田地",
        objective: "照料田地，继续用之前那个农田。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const advanced = await service.advanceGoal(laterGoal.id, "agent-test");
      expect(advanced.task).toMatchObject({ spec: { kind: "farm", action: "cycle" } });
      expect(advanced.plan.nodes.find((node) => node.id === "build_crop_farm")).toMatchObject({
        status: "skipped",
        checkpoint: { reusedFacilityId: farm?.id },
      });
      await waitForGoalStatus(service, laterGoal.id, "succeeded");
      expect(service.getGoal(laterGoal.id)).toMatchObject({ status: "succeeded", progress: 1 });
    }, new RecoveringGoalBackend());
  }, 25_000);

  it("crafts requested chest items instead of treating every chest mention as a storage-room goal", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "我要一个箱子",
        objective: "给我制作一个箱子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual(["knowledge_lookup", "query_existing_workstation", "query_existing_storage", "craft_requested_item"]);
      expect(plan.nodes[1]).toMatchObject({
        action: {
          kind: "query-facilities",
          type: "workstation",
          tags: ["crafting_table"],
        },
      });
      expect(plan.nodes[3]).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:chest",
            count: 1,
            deliverTo: "PlayerOne",
          },
        },
      });
    });
  });

  it("plans ordinary equipment crafting through storage, workstation, and furnace lookups", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "给我做一把铁剑",
        objective: "给我做一把铁剑，材料不够就先找箱子、挖矿、烧铁。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_existing_workstation",
        "query_existing_storage",
        "query_existing_furnace",
        "craft_requested_item",
      ]);
      expect(plan.nodes.at(-1)).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:iron_sword",
            count: 1,
            deliverTo: "PlayerOne",
          },
        },
        checkpoint: {
          searchStorageFirst: true,
          preferredFurnaceQueryNodeId: "query_existing_furnace",
          preferredStorageQueryNodeId: "query_existing_storage",
        },
      });
    });
  });

  it("plans iron equipment kit requests as a recoverable Agent skill chain", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "做一套铁装备",
        objective: "给我做一套铁装备，做好后自动换上更好的装备。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: { proposedSkillId: "craft.iron-equipment" },
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_existing_workstation",
        "query_existing_storage",
        "query_existing_furnace",
        "craft_requested_kit",
        "verify_auto_equipment_policy",
      ]);
      expect(plan.nodes[4]).toMatchObject({
        action: {
          kind: "skill",
          skillId: "craft.iron-equipment",
          arguments: {
            ironInput: "minecraft:raw_iron",
            ironCount: 32,
          },
        },
        checkpoint: {
          autoEquipBetterArmorAndWeapons: true,
          storeLowTierSpareEquipment: true,
        },
      });
    });
  });

  it("counts existing logs toward building-material inventory prerequisites", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "准备建筑材料",
        objective: "准备一套建筑材料，背包里已有的原木也要算进去。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [],
        metadata: { proposedSkillId: "craft.building-materials" },
      });
      const plan = service.getPlan(goal.id);

      expect(plan.nodes.find((node) => node.id === "prepare_building_wood")).toMatchObject({
        action: {
          kind: "task",
          spec: {
            kind: "gather",
            itemId: "#minecraft:logs",
            count: 64,
            countMode: "inventory-total",
          },
        },
      });
    });
  });

  it("plans ranch operations by reusing remembered pens instead of rebuilding only", async () => {
    await withService(async (service) => {
      const snapshot = service.getCompanion("codex-sim").snapshot;
      const ranch = service.registerFacility({
        worldId: snapshot.worldId,
        dimension: "minecraft:overworld",
        type: "ranch",
        name: "Home sheep pen",
        position: { x: 18, y: 64, z: 18 },
        tags: ["livestock", "sheep"],
        owner: "PlayerOne",
        properties: {},
      });
      const goal = service.submitGoal("codex-sim", {
        title: "繁殖羊",
        objective: "去之前的牧场繁殖羊，不要重新建牧场。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.plan.nodes.find((node) => node.id === "build_ranch_pen")).toMatchObject({
        status: "skipped",
        checkpoint: { reusedFacilityId: ranch.id },
      });
      expect(advanced.task).toMatchObject({
        spec: {
          kind: "ranch",
          action: "establish",
          animalType: "minecraft:sheep",
          penAnchor: ranch.position,
        },
      });
      await waitForGoalStatus(service, goal.id, "succeeded");
      expect(service.listTasks().filter((task) => task.spec.kind === "ranch").map((task) => task.spec))
        .toEqual(expect.arrayContaining([
          expect.objectContaining({ kind: "ranch", action: "establish", penAnchor: ranch.position }),
          expect.objectContaining({ kind: "ranch", action: "breed", penAnchor: ranch.position }),
        ]));
    });
  });

  it("plans dragon actions with landing-facility context", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "骑龙跟随",
        objective: "骑龙跟随我，降落和下龙时注意安全。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: { proposedSkillId: "dragon.mount-and-follow" },
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_dragon_landing_area",
        "mount_and_follow_dragon",
      ]);
      expect(plan.nodes[2]).toMatchObject({
        action: {
          kind: "skill",
          skillId: "dragon.mount-and-follow",
        },
        checkpoint: {
          supportedMods: ["bookofdragons", "saintsdragons"],
          avoidDismountUntilLanded: true,
        },
      });
    });
  });

  it("plans shared dragon riding with front/rear seat safety context", async () => {
    await withService((service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "一起骑龙",
        objective: "让玩家和 NPC 一起骑龙，玩家前座 NPC 后座。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: { proposedSkillId: "dragon.shared-ride" },
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_dragon_landing_area",
        "share_ride_dragon",
      ]);
      expect(plan.nodes[2]).toMatchObject({
        action: {
          kind: "skill",
          skillId: "dragon.shared-ride",
        },
        checkpoint: {
          playerSeat: "front",
          companionSeat: "rear",
          avoidDismountUntilLanded: true,
        },
      });
    });
  });

  it("wraps requested blueprint builds with facility lookup and memory verification", async () => {
    await withService(async (service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "建造房子",
        objective: "在家附近建造一个房子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(plan.status).toBe("ready");
      expect(plan.nodes.map((node) => node.id)).toEqual([
        "knowledge_lookup",
        "query_existing_build_basic-shelter",
        "build_requested_structure",
        "verify_build_memory",
      ]);
      expect(plan.nodes[1]).toMatchObject({
        action: {
          kind: "query-facilities",
          type: "build",
          tags: ["basic-shelter"],
        },
      });
      expect(plan.nodes[2]).toMatchObject({
        action: {
          kind: "skill",
          skillId: "build.basic-shelter",
        },
        checkpoint: {
          facilityType: "build",
          facilityTags: ["basic-shelter"],
          shouldRegisterFacilityAfterBuild: true,
          skipIfFacilityQueryNodeId: "query_existing_build_basic-shelter",
        },
      });
    });
  });

  it("records completed blueprint builds and skips duplicate rebuild requests", async () => {
    await withService(async (service) => {
      const snapshot = service.getCompanion("codex-sim").snapshot;
      const goal = service.submitGoal("codex-sim", {
        title: "建造房子",
        objective: "在家附近建造一个房子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      await service.advanceGoal(goal.id, "agent-test");
      await waitForGoalStatus(service, goal.id, "succeeded", 10_000);
      const built = service.listFacilities(snapshot.worldId)
        .find((facility) => facility.sourceGoalId === goal.id && facility.type === "build");
      expect(built).toBeDefined();
      expect(built).toMatchObject({
        tags: expect.arrayContaining(["build", "basic-shelter", "build.basic-shelter", "agent-goal"]),
        position: { x: expect.any(Number), y: snapshot.position.y, z: expect.any(Number) },
        bounds: {
          min: built!.position,
          max: {
            x: built!.position.x + 6,
            y: snapshot.position.y + 4,
            z: built!.position.z + 6,
          },
        },
        properties: {
          source: "agent.workGraph",
          nodeId: "build_requested_structure",
          skillId: "build.basic-shelter",
          compoundZone: "residential",
          boundarySource: "blueprint",
          confidence: 1,
        },
      });
      const shelterGap = horizontalBoundsGap(built!.bounds!, { min: snapshot.position, max: snapshot.position });
      expect(shelterGap).toBeGreaterThanOrEqual(8);
      expect(shelterGap).toBeLessThanOrEqual(24);

      const duplicate = service.submitGoal("codex-sim", {
        title: "再建造房子",
        objective: "再建造一个房子；如果已经记得同类房子就复用，不要重复建造新房子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const advanced = await service.advanceGoal(duplicate.id, "agent-test");

      expect(advanced.task).toBeUndefined();
      expect(advanced.goal).toMatchObject({ status: "succeeded", progress: 1 });
      expect(advanced.plan.nodes.find((node) => node.id === "build_requested_structure")).toMatchObject({
        status: "skipped",
        checkpoint: { reusedFacilityId: built?.id },
      });
    }, new RecoveringGoalBackend());
  }, 15_000);

  it("advances a planned craft goal through the real task executor and auto-completes the graph", async () => {
    await withService(async (service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "我要一个箱子",
        objective: "给我制作一个箱子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.advancedNodeId).toBe("craft_requested_item");
      expect(advanced.task).toMatchObject({
        companionId: "codex-sim",
        spec: {
          kind: "craft",
          itemId: "minecraft:chest",
          deliverTo: "PlayerOne",
        },
      });
      expect(advanced.plan.nodes[0]).toMatchObject({ id: "knowledge_lookup", status: "succeeded" });
      expect(advanced.plan.nodes[1]).toMatchObject({
        id: "query_existing_workstation",
        status: "succeeded",
      });
      expect(advanced.plan.nodes[2]).toMatchObject({
        id: "query_existing_storage",
        status: "succeeded",
      });
      expect(advanced.plan.nodes[3]).toMatchObject({
        id: "craft_requested_item",
        status: "running",
        checkpoint: {
          taskId: advanced.task?.id,
          owner: "agent-test",
        },
      });

      await waitForGoalStatus(service, goal.id, "succeeded", 20_000);
      const completed = service.getGoal(goal.id);
      const plan = service.getPlan(goal.id);
      expect(completed).toMatchObject({ status: "succeeded", progress: 1, activeWorkNodeId: null });
      expect(plan.status).toBe("succeeded");
      expect(plan.nodes.map((node) => node.status)).toEqual(["succeeded", "succeeded", "succeeded", "succeeded"]);
    });
  });

  it("resumes a running Agent work node after a control-service restart", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-goal-restart-running-"));
    try {
      const first = new ControlService({ stateDirectory });
      first.registerBackend(new PendingGoalBackend());
      const goal = first.submitGoal("codex-sim", {
        title: "Craft a diamond pickaxe",
        objective: "Craft a diamond pickaxe and deliver it to me.",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      const advanced = await first.advanceGoal(goal.id, "agent-test");

      expect(advanced.advancedNodeId).toBe("prepare_food_reserve");
      expect(advanced.task).toMatchObject({
        companionId: "codex-sim",
        status: "running",
        progress: 0.25,
      });

      const second = new ControlService({ stateDirectory });
      const recovering = new RecoveringGoalBackend();
      second.registerBackend(recovering);

      await waitForGoalStatus(second, goal.id, "succeeded", 10_000);
      const recoveredPlan = second.getPlan(goal.id);
      expect(recovering.resumedIds).toEqual([advanced.task?.id]);
      expect(recovering.continuedSpecs).toEqual(expect.arrayContaining([
        "minecraft:iron_pickaxe",
        "minecraft:torch",
        "minecraft:diamond_pickaxe",
      ]));
      expect(second.getGoal(goal.id)).toMatchObject({ status: "succeeded", progress: 1, activeWorkNodeId: null });
      expect(recoveredPlan.status).toBe("succeeded");
      expect(recoveredPlan.nodes.every((node) => node.status === "succeeded" || node.status === "skipped")).toBe(true);
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  }, 15_000);

  it("syncs already-finished task journal entries after restart and continues the remaining WorkGraph nodes", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-goal-restart-terminal-"));
    try {
      const first = new ControlService({ stateDirectory });
      first.registerBackend(new PendingGoalBackend());
      const goal = first.submitGoal("codex-sim", {
        title: "Collect wood then craft a chest",
        objective: "Collect wood, then craft a chest and deliver it.",
        requestedBy: "PlayerOne",
        source: "mcp",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [
          {
            kind: "gather",
            itemId: "minecraft:oak_log",
            count: 8,
            requestedBy: "PlayerOne",
          },
          {
            kind: "craft",
            itemId: "minecraft:chest",
            count: 1,
            deliverTo: "PlayerOne",
            requestedBy: "PlayerOne",
          },
        ],
        metadata: {},
      });
      const advanced = await first.advanceGoal(goal.id, "agent-test");
      expect(advanced.advancedNodeId).toBe("task_1");
      expect(advanced.task).toMatchObject({ status: "running", spec: { kind: "gather" } });

      const taskJournalPath = path.join(stateDirectory, "task-journal.json");
      const journal = JSON.parse(await readFile(taskJournalPath, "utf8")) as {
        version: 1;
        tasks: Array<{ task: TaskRecord; owner: string; terminalNotified?: boolean }>;
      };
      const entry = journal.tasks.find((candidate) => candidate.task.id === advanced.task?.id);
      expect(entry).toBeTruthy();
      entry!.task.status = "succeeded";
      entry!.task.progress = 1;
      entry!.task.message = "finished before control-service restart";
      entry!.task.finishedAt = new Date().toISOString();
      await writeFile(taskJournalPath, `${JSON.stringify(journal, null, 2)}\n`, "utf8");

      const second = new ControlService({ stateDirectory });
      const recovering = new RecoveringGoalBackend();
      second.registerBackend(recovering);

      await waitForGoalStatus(second, goal.id, "succeeded", 10_000);
      expect(recovering.resumedIds).toEqual([]);
      expect(recovering.continuedSpecs).toEqual(["minecraft:chest"]);
      expect(second.getPlan(goal.id).nodes).toMatchObject([
        { id: "knowledge_lookup", status: "succeeded" },
        {
          id: "task_1",
          status: "succeeded",
          checkpoint: {
            taskStatus: "succeeded",
            taskMessage: "finished before control-service restart",
          },
        },
        {
          id: "task_2",
          status: "succeeded",
          action: { kind: "task", spec: { kind: "craft", itemId: "minecraft:chest", deliverTo: "PlayerOne" } },
        },
      ]);
      expect(second.getGoal(goal.id)).toMatchObject({ status: "succeeded", progress: 1, activeWorkNodeId: null });
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  }, 15_000);

  it("updates remembered crafting workstations while still executing the requested craft", async () => {
    await withService(async (service) => {
      const snapshot = service.getCompanion("codex-sim").snapshot;
      const workstation = service.registerFacility({
        worldId: snapshot.worldId,
        dimension: "minecraft:overworld",
        type: "workstation",
        name: "Base crafting table",
        position: { x: 5, y: 64, z: 5 },
        tags: ["crafting_table", "base"],
        owner: "PlayerOne",
        properties: {},
      });
      const goal = service.submitGoal("codex-sim", {
        title: "我要一个箱子",
        objective: "给我制作一个箱子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.task).toMatchObject({ spec: { kind: "craft", itemId: "minecraft:chest" } });
      expect(advanced.plan.nodes[1]).toMatchObject({
        id: "query_existing_workstation",
        status: "succeeded",
        checkpoint: {
          facilityCount: 1,
          firstFacilityId: workstation.id,
          firstFacilityName: "Base crafting table",
        },
      });
      expect(service.listFacilities(snapshot.worldId)[0]).toMatchObject({
        id: workstation.id,
        lastUsedAt: expect.any(String),
      });
      await waitForGoalStatus(service, goal.id, "succeeded");
    });
  });

  it("synchronizes observed snapshot facilities and reuses them in later goals", async () => {
    await withService(async (service) => {
      const snapshot = service.getSnapshot("codex-sim");
      const firstFacilities = service.listFacilities(snapshot.worldId);

      expect(firstFacilities.map((facility) => [facility.type, facility.name])).toEqual(expect.arrayContaining([
        ["workstation", "Observed crafting table"],
        ["workstation", "Observed furnace"],
        ["storage", "Observed home chest"],
        ["farm", "Observed outdoor crop farmland"],
        ["home", "Observed home spawn"],
        ["mine", "Observed diamond mine"],
        ["dragon-landing", "Test Dragon landing area"],
      ]));
      expect(firstFacilities.find((facility) => facility.name === "Observed crafting table")).toMatchObject({
        tags: ["crafting_table", "base"],
        properties: {
          source: "snapshot.observedFacilities",
          blockId: "minecraft:crafting_table",
        },
      });

      const observedFarm = firstFacilities.find((facility) => facility.name === "Observed outdoor crop farmland");
      expect(observedFarm).toMatchObject({
        properties: {
          source: "snapshot.observedFacilities",
          invalidForCropWork: false,
          physicallyObservedAt: expect.any(String),
        },
      });
      service.registerFacility({
        worldId: snapshot.worldId,
        dimension: snapshot.dimension,
        type: "farm",
        name: "Observed outdoor crop farmland",
        position: { x: 28, y: 64, z: -24 },
        tags: ["crop", "farmland"],
        properties: { invalidForCropWork: true, validationFailureCode: "FARM_TARGET_NOT_FOUND" },
      });
      expect(service.listFacilities(snapshot.worldId).find((facility) => facility.id === observedFarm?.id)?.properties.invalidForCropWork).toBe(true);

      service.getSnapshot("codex-sim");
      expect(service.listFacilities(snapshot.worldId)).toHaveLength(firstFacilities.length);
      expect(service.listFacilities(snapshot.worldId).find((facility) => facility.id === observedFarm?.id)?.properties.invalidForCropWork).toBe(false);

      const goal = service.submitGoal("codex-sim", {
        title: "我要一个箱子",
        objective: "给我制作一个箱子。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.plan.nodes[1]).toMatchObject({
        id: "query_existing_workstation",
        checkpoint: {
          facilityCount: 1,
          firstFacilityName: "Observed crafting table",
        },
      });
      expect(advanced.task).toMatchObject({ spec: { kind: "craft", itemId: "minecraft:chest" } });
      await waitForGoalStatus(service, goal.id, "succeeded");
    }, new FacilitySnapshotBackend());
  });

  it("keeps ladder safety supplies until a remembered mine is physically reused", async () => {
    await withService(async (service) => {
      const snapshot = service.getCompanion("codex-sim").snapshot;
      const mine = service.registerFacility({
        worldId: snapshot.worldId,
        dimension: "minecraft:overworld",
        type: "mine",
        name: "Deep diamond branch mine",
        position: { x: -12, y: -58, z: 30 },
        tags: ["diamond", "deep-mining"],
        owner: "PlayerOne",
        properties: {},
      });
      const goal = service.submitGoal("codex-sim", {
        title: "Craft a diamond pickaxe",
        objective: "Craft a diamond pickaxe and deliver it to me.",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      await service.advanceGoal(goal.id, "agent-test");
      await waitForGoalStatus(service, goal.id, "succeeded", 20_000);
      const plan = service.getPlan(goal.id);

      expect(plan.nodes.find((node) => node.id === "query_existing_diamond_mine")).toMatchObject({
        status: "succeeded",
        checkpoint: {
          facilityCount: 1,
          firstFacilityId: mine.id,
        },
      });
      expect(plan.nodes.find((node) => node.id === "craft_ladders")).toMatchObject({
        status: "succeeded",
        checkpoint: {
          rememberedMineQueryNodeId: "query_existing_diamond_mine",
          requireVerifiedPhysicalReuseBeforeSkipping: true,
        },
      });
    });
  }, 25_000);

  it("advances skill work nodes by queuing a macro task through the existing skill executor", async () => {
    await withService(async (service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "制作床",
        objective: "制作一张床并放到家附近。",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.advancedNodeId).toBe("craft_and_place_bed");
      expect(advanced.task).toMatchObject({
        spec: {
          kind: "macro",
          skillId: "life.craft-and-place-bed",
          requestedBy: "PlayerOne",
        },
      });
      expect(service.getPlan(goal.id).nodes.find((node) => node.id === "craft_and_place_bed")).toMatchObject({
        status: "running",
        action: {
          kind: "skill",
          skillId: "life.craft-and-place-bed",
        },
        checkpoint: {
          taskId: advanced.task?.id,
          owner: "agent-test",
        },
      });

      await waitForGoalStatus(service, goal.id, "succeeded");
      expect(service.getPlan(goal.id).status).toBe("succeeded");
    });
  });

  it("marks a work node and goal failed when the single-writer task executor rejects the action", async () => {
    await withService(async (service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "Invalid build",
        objective: "Try an invalid build plan.",
        requestedBy: "PlayerOne",
        source: "mcp",
        priority: 100,
        mode: "stable",
        constraints: [],
        taskHints: [{
          kind: "build",
          planId: "missing-plan",
          requestedBy: "PlayerOne",
        }],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");

      expect(advanced.goal).toMatchObject({
        status: "failed",
        activeWorkNodeId: null,
        error: {
          code: "BUILD_PLAN_NOT_FOUND",
          failedNodeId: "task_1",
        },
      });
      expect(advanced.plan).toMatchObject({
        status: "failed",
        nodes: [
          { id: "knowledge_lookup", status: "succeeded" },
          { id: "task_1", status: "failed" },
        ],
      });
      expect(advanced.task).toBeUndefined();
    });
  });

  it("reopens a failed WorkGraph when its linked task id is retried and then continues the goal", async () => {
    const backend = new FailOnceGoalTaskBackend();
    await withService(async (service) => {
      const goal = service.submitGoal(backend.id, {
        title: "Retry the same physical task",
        objective: "Retry the same task from its failure checkpoint and finish the goal.",
        requestedBy: "PlayerOne",
        source: "t-chat",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [{
          kind: "follow",
          player: "PlayerOne",
          distance: 3,
          requestedBy: "PlayerOne",
        }],
        metadata: {},
      });

      const advanced = await service.advanceGoal(goal.id, "agent-test");
      const taskId = advanced.task?.id;
      expect(taskId).toBeTruthy();
      await waitForGoalStatus(service, goal.id, "failed");
      expect(service.getPlan(goal.id)).toMatchObject({
        status: "failed",
        nodes: [
          { id: "knowledge_lookup", status: "succeeded" },
          { id: "task_1", status: "failed", checkpoint: { taskId } },
        ],
      });

      const retried = service.retryTask(taskId!, "agent-test");
      expect(retried.id).toBe(taskId);
      expect(service.getGoal(goal.id).status).not.toBe("failed");
      expect(service.getPlan(goal.id).status).not.toBe("failed");

      await waitForGoalStatus(service, goal.id, "succeeded");
      expect(service.getPlan(goal.id)).toMatchObject({
        status: "succeeded",
        nodes: [
          { id: "knowledge_lookup", status: "succeeded" },
          { id: "task_1", status: "succeeded", checkpoint: { taskId } },
        ],
      });
      expect(backend.attemptedTaskIds).toEqual([taskId, taskId]);
    }, backend);
  });

  it("keeps unsupported goals blocked instead of pretending to know a route", async () => {
    await withService(async (service) => {
      const goal = service.submitGoal("codex-sim", {
        title: "抽象目标",
        objective: "帮我做一个现在规则库还不支持的完全抽象目标。",
        requestedBy: "PlayerOne",
        source: "mcp",
        priority: 100,
        mode: "smart",
        constraints: [],
        taskHints: [],
        metadata: {},
      });
      const plan = service.getPlan(goal.id);

      expect(goal.plannedAt).toBeNull();
      expect(plan.status).toBe("draft");
      expect(plan.nodes.map((node) => node.id)).toEqual(["knowledge_lookup", "await_plan"]);
      expect(plan.nodes[1]).toMatchObject({ status: "blocked", action: { kind: "noop" } });

      const advanced = await service.advanceGoal(goal.id, "agent-test");
      expect(advanced.goal).toMatchObject({
        status: "planning",
        activeWorkNodeId: null,
        message: "Goal is waiting for an expanded local planner route",
      });
      expect(advanced.plan.status).toBe("draft");
      expect(advanced.plan.nodes).toMatchObject([
        { id: "knowledge_lookup", status: "succeeded" },
        { id: "await_plan", status: "blocked" },
      ]);
      expect(advanced.task).toBeUndefined();
    });
  });
});
