import { describe, expect, it } from "vitest";
import { AGENT_PROTOCOL_VERSION, actionSpecSchema, aiProviderDraftSchema, bridgeMessageSchema, buildPlanDraftSchema, buildPlanSchema, chatSettingsDraftSchema, declarativeSkillDraftSchema, declarativeSkillSchema, dragonStateSchema, facilityRecordSchema, goalRecordSchema, goalSpecSchema, liveFixtureRequestSchema, resourceReservationSchema, taskRecordSchema, taskSpecSchema, workGraphSchema, worldSnapshotSchema } from "./index.js";

describe("protocol schemas", () => {
  it("accepts the explicit multi-agent free-chat target", () => {
    expect(chatSettingsDraftSchema.parse({
      freeChatEnabled: true,
      playerName: "PlayerOne",
      companionName: "Aster",
      target: "multi-agent",
    })).toMatchObject({
      target: "multi-agent",
      actionMode: "stable",
      tokenBudget: 512,
    });
  });

  it("validates explicit chat intelligence modes and output token budgets", () => {
    expect(chatSettingsDraftSchema.parse({
      freeChatEnabled: false,
      playerName: "PlayerOne",
      target: "active-provider",
      actionMode: "smart",
      tokenBudget: 2_048,
    })).toMatchObject({ actionMode: "smart", tokenBudget: 2_048 });
    expect(chatSettingsDraftSchema.parse({
      freeChatEnabled: false,
      playerName: "PlayerOne",
      target: "active-provider",
      actionMode: "hybrid",
    })).toMatchObject({ actionMode: "smart", tokenBudget: 512 });
    expect(() => chatSettingsDraftSchema.parse({
      freeChatEnabled: false,
      playerName: "PlayerOne",
      target: "active-provider",
      actionMode: "stable",
      tokenBudget: 64,
    })).toThrow();
  });

  it("applies safe defaults to follow tasks", () => {
    const task = taskSpecSchema.parse({ kind: "follow", player: "PlayerOne" });
    if (task.kind !== "follow") throw new Error("Expected a follow task");
    expect(task.distance).toBe(3);
    expect(task.requestedBy).toBe("user");
  });

  it("preserves an explicit scheduler priority", () => {
    expect(taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 16,
      priority: 40,
    })).toMatchObject({ priority: 40 });
    expect(() => taskSpecSchema.parse({
      kind: "combat",
      targetType: "minecraft:zombie",
      priority: 1001,
    })).toThrow();
  });

  it("supports walk-only gather tasks for local movement verification", () => {
    expect(taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 1,
    })).not.toHaveProperty("movement");
    expect(taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 1,
      movement: "walk",
    })).toMatchObject({ movement: "walk" });
    expect(() => taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 1,
      movement: "teleport",
    })).toThrow();
  });

  it("distinguishes newly acquired gather counts from inventory reserve targets", () => {
    expect(taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 64,
    })).not.toHaveProperty("countMode");
    expect(taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 64,
      countMode: "acquire",
    })).toMatchObject({ countMode: "acquire" });
    expect(taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 64,
      countMode: "inventory-total",
    })).toMatchObject({ countMode: "inventory-total" });
    expect(() => taskSpecSchema.parse({
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 64,
      countMode: "inventory-delta",
    })).toThrow();
  });

  it("accepts dragon shared-ride tasks for supported two-seat adapters", () => {
    expect(taskSpecSchema.parse({
      kind: "dragon",
      action: "share-ride",
      targetId: "",
      requestedBy: "PlayerOne",
    })).toMatchObject({
      kind: "dragon",
      action: "share-ride",
      requestedBy: "PlayerOne",
    });
  });

  it("accepts Agent v2 goals and work graphs without changing the bridge protocol", () => {
    const goal = goalRecordSchema.parse({
      id: "00000000-0000-4000-8000-000000000201",
      worldId: "world-one",
      companionId: "codex-sim",
      spec: {
        title: "Craft a diamond pickaxe",
        objective: "Prepare tools, mine diamonds, craft the pickaxe, and deliver it.",
        requestedBy: "PlayerOne",
        mode: "smart",
        taskHints: [{
          kind: "craft",
          itemId: "minecraft:diamond_pickaxe",
          count: 1,
          deliverTo: "PlayerOne",
        }],
      },
      status: "planning",
      progress: 0,
      message: "Planning prerequisites",
      createdAt: "2026-08-16T00:00:00.000Z",
    });
    expect(goal.version).toBe(AGENT_PROTOCOL_VERSION);
    expect(goal.spec.constraints).toEqual([]);

    const graph = workGraphSchema.parse({
      id: "00000000-0000-4000-8000-000000000202",
      goalId: goal.id,
      status: "ready",
      nodes: [{
        id: "observe",
        label: "Observe current inventory and nearby facilities",
        action: { kind: "query-knowledge", query: "diamond pickaxe prerequisites", topics: ["crafting", "mining"] },
      }, {
        id: "craft_pickaxe",
        label: "Craft and deliver the diamond pickaxe",
        dependsOn: ["observe"],
        action: {
          kind: "task",
          spec: {
            kind: "craft",
            itemId: "minecraft:diamond_pickaxe",
            count: 1,
            deliverTo: "PlayerOne",
          },
        },
      }],
      edges: [{ from: "observe", to: "craft_pickaxe" }],
      createdAt: "2026-08-16T00:00:01.000Z",
      updatedAt: "2026-08-16T00:00:01.000Z",
    });
    expect(graph.version).toBe(AGENT_PROTOCOL_VERSION);
    expect(graph.nodes[1]?.status).toBe("pending");
  });

  it("rejects dangling Agent v2 work-graph dependencies and arbitrary control tools", () => {
    expect(() => workGraphSchema.parse({
      id: "00000000-0000-4000-8000-000000000203",
      goalId: "00000000-0000-4000-8000-000000000201",
      nodes: [{
        id: "craft_pickaxe",
        label: "Craft pickaxe",
        dependsOn: ["missing_node"],
        action: { kind: "task", spec: { kind: "craft", itemId: "minecraft:diamond_pickaxe", count: 1 } },
      }],
      edges: [{ from: "craft_pickaxe", to: "missing_node" }],
      createdAt: "2026-08-16T00:00:01.000Z",
      updatedAt: "2026-08-16T00:00:01.000Z",
    })).toThrow();
    expect(() => actionSpecSchema.parse({ kind: "shell", command: "type secrets.txt" })).toThrow();
  });

  it("captures reusable facilities and bounded resource reservations for Agent planning", () => {
    expect(facilityRecordSchema.parse({
      id: "00000000-0000-4000-8000-000000000204",
      worldId: "world-one",
      dimension: "minecraft:overworld",
      type: "workstation",
      name: "Base crafting table",
      position: { x: 4, y: 64, z: 5 },
      tags: ["crafting_table", "base"],
      createdAt: "2026-08-16T00:00:00.000Z",
      updatedAt: "2026-08-16T00:00:00.000Z",
    })).toMatchObject({ type: "workstation", lastUsedAt: null });
    expect(resourceReservationSchema.parse({
      id: "00000000-0000-4000-8000-000000000205",
      goalId: "00000000-0000-4000-8000-000000000201",
      nodeId: "craft_pickaxe",
      itemId: "minecraft:diamond",
      count: 3,
      source: "home-storage",
      createdAt: "2026-08-16T00:00:00.000Z",
      updatedAt: "2026-08-16T00:00:00.000Z",
    })).toMatchObject({ status: "planned" });
    expect(actionSpecSchema.parse({
      kind: "query-facilities",
      worldId: "world-one",
      dimension: "minecraft:overworld",
      type: "farm",
      tags: ["crop"],
      owner: "PlayerOne",
      limit: 4,
    })).toMatchObject({ kind: "query-facilities", type: "farm", tags: ["crop"], limit: 4 });
    expect(worldSnapshotSchema.parse({
      sequence: 1,
      capturedAt: "2026-08-16T00:00:00.000Z",
      worldId: "world-one",
      dimension: "minecraft:overworld",
      position: { x: 0, y: 64, z: 0 },
      yaw: 0,
      pitch: 0,
      health: 20,
      maxHealth: 20,
      food: 20,
      air: 300,
      gameMode: "survival",
      timeOfDay: 0,
      weather: "clear",
      inventory: [],
      nearbyEntities: [],
      status: "ready",
      observedFacilities: [{
        type: "workstation",
        name: "Observed crafting table",
        position: { x: 2, y: 64, z: 2 },
        tags: ["crafting_table"],
        properties: { blockId: "minecraft:crafting_table" },
      }],
    })).toMatchObject({
      observedFacilities: [{ type: "workstation", tags: ["crafting_table"] }],
    });
    expect(() => goalSpecSchema.parse({
      title: "Bad",
      objective: "x",
      requestedBy: "PlayerOne",
      taskHints: new Array(17).fill({ kind: "follow", player: "PlayerOne" }),
    })).toThrow();
  });

  it("preserves a bounded player-built home scan and its 24-block home circle", () => {
    expect(worldSnapshotSchema.shape.homeState.unwrap().parse({
      dimension: "minecraft:overworld",
      position: { x: 10, y: 64, z: 12 },
      temporary: false,
      bounds: {
        min: { x: 3, y: 63, z: 5 },
        max: { x: 18, y: 72, z: 20 },
      },
      coreRadius: 24,
      boundarySource: "enclosed-scan",
      confidence: 0.9,
    })).toMatchObject({
      coreRadius: 24,
      boundarySource: "enclosed-scan",
      bounds: { min: { x: 3, y: 63, z: 5 }, max: { x: 18, y: 72, z: 20 } },
    });
  });

  it("rejects impossible health snapshots", () => {
    expect(() => worldSnapshotSchema.parse({ health: -1 })).toThrow();
  });

  it("distinguishes the death screen from other client UI states", () => {
    expect(worldSnapshotSchema.shape.clientUiState.unwrap().parse("death")).toBe("death");
    expect(() => worldSnapshotSchema.shape.clientUiState.unwrap().parse("unknown-screen")).toThrow();
  });

  it("normalizes omitted health for non-living nearby entities", () => {
    const snapshot = worldSnapshotSchema.parse({
      sequence: 1,
      capturedAt: "2026-08-01T00:00:00.000Z",
      worldId: "test-world",
      dimension: "minecraft:overworld",
      position: { x: 0, y: 64, z: 0 },
      ownerPosition: { x: 3, y: 64, z: 4 },
      ownerDistance: 5,
      yaw: 0,
      pitch: 0,
      health: 20,
      maxHealth: 20,
      food: 20,
      maxFood: 20,
      saturation: 5,
      materialMode: "creative",
      naturalRegenerationEnabled: true,
      canNaturalRegenerate: false,
      automaticEating: false,
      managedEating: true,
      usingItem: true,
      air: 300,
      gameMode: "creative",
      timeOfDay: 0,
      weather: "clear",
      inventory: [],
      recentItemTransactions: [{
        sequence: 1,
        gameTime: 20,
        taskId: "gather-1",
        action: "gather",
        itemId: "minecraft:coal",
        delta: 26,
        balanceAfter: 26,
      }, {
        sequence: 2,
        gameTime: 40,
        taskId: "craft-1",
        action: "craft",
        itemId: "minecraft:coal",
        delta: -16,
        balanceAfter: 10,
      }],
      nearbyEntities: [{
        id: "item-1",
        type: "minecraft:item",
        name: "Oak Log",
        position: { x: 1, y: 64, z: 1 },
        distance: 1.4,
        disposition: "neutral",
      }],
      status: "ready",
      miningState: {
        phase: "descending",
        itemId: "minecraft:diamond",
        targetY: -58,
        staircaseStep: 19,
        branchIndex: 0,
        branchProgress: 0,
        regionIndex: 0,
        brokenBlocks: 38,
        placedTorches: 2,
        entrance: { x: 4, y: 64, z: 7 },
        lastSafeStand: { x: 23, y: 45, z: 7 },
      },
      clientUiState: "gameplay",
      liveFixtureAck: {
        sequence: 4,
        suite: "dragon",
        mode: "stage-obstacle-book",
        status: "dragon-fixture:obstacle mod=bookofdragons,target=1.0:80.0:2.0,wallMaxX=-6,blocks=162",
      },
    });

    expect(snapshot.nearbyEntities[0]?.health).toBeNull();
    expect(snapshot).toMatchObject({
      maxFood: 20,
      materialMode: "creative",
      naturalRegenerationEnabled: true,
      managedEating: true,
      usingItem: true,
      clientUiState: "gameplay",
      ownerDistance: 5,
    });
    expect(snapshot.recentItemTransactions?.[1]).toMatchObject({ itemId: "minecraft:coal", delta: -16, balanceAfter: 10 });
    expect(snapshot.liveFixtureAck).toMatchObject({ sequence: 4, suite: "dragon", mode: "stage-obstacle-book" });
    expect(snapshot.miningState).toMatchObject({
      phase: "descending",
      itemId: "minecraft:diamond",
      targetY: -58,
      staircaseStep: 19,
      brokenBlocks: 38,
    });

    const waitingSnapshot = worldSnapshotSchema.parse({
      ...snapshot,
      miningState: { ...snapshot.miningState, phase: "waiting-entry" },
    });
    expect(waitingSnapshot.miningState?.phase).toBe("waiting-entry");
  });

  it("rejects invalid inventory transaction history", () => {
    const transaction = worldSnapshotSchema.shape.recentItemTransactions.unwrap().element;
    expect(() => transaction.parse({
      sequence: 1,
      gameTime: 1,
      action: "craft",
      itemId: "minecraft:coal",
      delta: 0,
      balanceAfter: 1,
    })).toThrow();
    expect(() => transaction.parse({
      sequence: 1,
      gameTime: 1,
      action: "craft",
      itemId: "minecraft:coal",
      delta: -1,
      balanceAfter: -1,
    })).toThrow();
  });

  it("bounds live fixture acknowledgements carried by snapshots", () => {
    expect(() => worldSnapshotSchema.shape.liveFixtureAck.unwrap().parse({
      sequence: 1,
      suite: "dragon;command",
      mode: "inspect-book",
      status: "ok",
    })).toThrow();
    expect(worldSnapshotSchema.shape.liveFixtureAck.unwrap().parse({
      sequence: 1,
      suite: "dragon",
      mode: "inspect-book",
      status: "x".repeat(2_048),
    }).status).toHaveLength(2_048);
    expect(() => worldSnapshotSchema.shape.liveFixtureAck.unwrap().parse({
      sequence: 1,
      suite: "dragon",
      mode: "inspect-book",
      status: "x".repeat(2_049),
    })).toThrow();
  });

  it("preserves observable dragon autopilot state", () => {
    expect(dragonStateSchema.parse({
      modId: "bookofdragons",
      entityId: "00000000-0000-4000-8000-000000000042",
      name: "Frost",
      mounted: true,
      playerMounted: true,
      coRiding: true,
      autopilot: true,
      playerInputLocked: true,
      controlMode: "npc-autopilot",
      ownedByPlayer: true,
      flying: true,
    })).toMatchObject({
      autopilot: true,
      playerInputLocked: true,
      controlMode: "npc-autopilot",
    });
  });

  it("limits destructive task quantities", () => {
    expect(() => taskSpecSchema.parse({ kind: "gather", itemId: "minecraft:stone", count: 5000 })).toThrow();
  });

  it("accepts a locked remote farm anchor across the full outdoor scan radius", () => {
    expect(taskSpecSchema.parse({
      kind: "farm",
      cropId: "minecraft:wheat",
      action: "plant",
      radius: 96,
      placementAnchor: { x: -87, y: 73, z: -138 },
      lockPlacementAnchor: true,
    })).toMatchObject({
      radius: 96,
      placementAnchor: { x: -87, y: 73, z: -138 },
      lockPlacementAnchor: true,
    });
    expect(() => taskSpecSchema.parse({
      kind: "farm",
      cropId: "minecraft:wheat",
      action: "plant",
      radius: 97,
    })).toThrow();
  });

  it("keeps macro step progress and observed item counts distinct from parent progress", () => {
    const progress = {
      currentStepIndex: 0,
      currentStepKind: "gather" as const,
      stepProgress: 53 / 64,
      completedCount: 53,
      targetCount: 64,
      retainedCount: 51,
    };
    expect(taskRecordSchema.parse({
      id: "00000000-0000-4000-8000-000000000064",
      companionId: "codex-sim",
      spec: {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "minecraft:coal", count: 64, player: "PlayerOne" },
      },
      status: "running",
      progress: 53 / 128,
      message: "采集资源：已采集 53/64",
      createdAt: "2026-08-10T00:00:00.000Z",
      startedAt: "2026-08-10T00:00:01.000Z",
      finishedAt: null,
      error: null,
      ...progress,
    })).toMatchObject(progress);
    expect(bridgeMessageSchema.parse({
      type: "task-progress",
      companionId: "codex-sim",
      taskId: "00000000-0000-4000-8000-000000000064",
      progress: 53 / 64,
      message: "已采集 53/64",
      ...progress,
    })).toMatchObject(progress);
    expect(bridgeMessageSchema.parse({
      type: "task-result",
      companionId: "codex-sim",
      taskId: "00000000-0000-4000-8000-000000000064",
      ok: true,
      message: "已采集 53/64",
      ...progress,
    })).toMatchObject(progress);
    expect(() => bridgeMessageSchema.parse({
      type: "task-progress",
      companionId: "codex-sim",
      taskId: "00000000-0000-4000-8000-000000000064",
      progress: 53 / 64,
      message: "invalid estimated count",
      completedCount: 26.5,
    })).toThrow();
    expect(bridgeMessageSchema.parse({
      type: "task-progress",
      companionId: "codex-forge",
      taskId: "00000000-0000-4000-8000-000000000065",
      progress: 1,
      message: "outdoor build complete",
      resolvedPlacementAnchor: { x: -87, y: 73, z: -138 },
    })).toMatchObject({
      resolvedPlacementAnchor: { x: -87, y: 73, z: -138 },
    });
  });

  it("accepts reliable bridge chat IDs while preserving legacy clients", () => {
    const base = {
      type: "chat" as const,
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "继续任务",
      at: "2026-08-15T00:00:00.000Z",
    };
    expect(bridgeMessageSchema.parse(base)).not.toHaveProperty("messageId");
    expect(bridgeMessageSchema.parse({
      ...base,
      messageId: "00000000-0000-4000-8000-000000000001",
    })).toMatchObject({ messageId: "00000000-0000-4000-8000-000000000001" });
    expect(() => bridgeMessageSchema.parse({ ...base, messageId: "not-a-uuid" })).toThrow();
  });

  it("accepts Forge chat display acknowledgements only with a delivery UUID", () => {
    expect(bridgeMessageSchema.parse({
      type: "chat-delivered",
      companionId: "codex-forge",
      deliveryId: "00000000-0000-4000-8000-000000000002",
    })).toMatchObject({ deliveryId: "00000000-0000-4000-8000-000000000002" });
    expect(() => bridgeMessageSchema.parse({
      type: "chat-delivered",
      companionId: "codex-forge",
      deliveryId: "not-a-uuid",
    })).toThrow();
  });

  it("keeps old build tasks compatible while accepting companion-relative placement", () => {
    expect(taskSpecSchema.parse({ kind: "build", planId: "legacy-plan" })).toMatchObject({
      kind: "build",
      planId: "legacy-plan",
    });
    expect(taskSpecSchema.parse({
      kind: "build",
      planId: "safe-template",
      placement: "companion",
      offset: { x: 3, y: 0, z: 3 },
      placementAnchor: { x: 10, y: 64, z: -4 },
      homeBounds: {
        min: { x: -20, y: 60, z: -20 },
        max: { x: 20, y: 80, z: 20 },
      },
    })).toMatchObject({
      placement: "companion",
      offset: { x: 3, y: 0, z: 3 },
      placementAnchor: { x: 10, y: 64, z: -4 },
    });
    expect(taskSpecSchema.parse({
      kind: "macro",
      skillId: "build.basic-shelter",
      placementAnchor: { x: 10, y: 64, z: -4 },
      materialMode: "survival",
    })).toMatchObject({ materialMode: "survival" });
  });

  it.each([
    { zone: "residential", minDistance: 8, maxDistance: 24 },
    { zone: "production", minDistance: 16, maxDistance: 40 },
    { zone: "industrial", minDistance: 40, maxDistance: 64 },
  ] as const)("validates the $zone home-compound placement contract", ({ zone, minDistance, maxDistance }) => {
    expect(taskSpecSchema.parse({
      kind: "build",
      planId: `${zone}-template`,
      placement: "companion",
      sitePolicy: "home-compound",
      compoundPlacement: { zone, minDistance, maxDistance },
    })).toMatchObject({
      sitePolicy: "home-compound",
      compoundPlacement: {
        zone,
        minDistance,
        maxDistance,
        facilityClearance: 12,
        terrainPreparation: "light",
        protectedBounds: [],
      },
    });
  });

  it("preserves protected bounds on persistent macro tasks and rejects inverted rings", () => {
    const compoundPlacement = {
      zone: "production" as const,
      minDistance: 16,
      maxDistance: 40,
      facilityClearance: 12,
      terrainPreparation: "light" as const,
      protectedBounds: [{
        min: { x: -16, y: 64, z: -16 },
        max: { x: -8, y: 72, z: -8 },
      }],
    };
    expect(taskRecordSchema.parse({
      id: "00000000-0000-4000-8000-000000000066",
      companionId: "codex-forge",
      spec: {
        kind: "macro",
        skillId: "build.crop-farm",
        placementAnchor: { x: 24, y: 64, z: 0 },
        compoundPlacement,
      },
      status: "running",
      progress: 0.25,
      message: "building in the production ring",
      createdAt: "2026-08-20T00:00:00.000Z",
      startedAt: "2026-08-20T00:00:01.000Z",
      finishedAt: null,
      error: null,
    })).toMatchObject({
      spec: {
        placementAnchor: { x: 24, y: 64, z: 0 },
        compoundPlacement,
      },
    });
    expect(taskSpecSchema.parse({
      kind: "macro",
      skillId: "build.basic-shelter",
      placementAnchor: { x: 40, y: 72, z: 40 },
      sitePolicy: "default",
    })).toMatchObject({
      kind: "macro",
      sitePolicy: "default",
      placementAnchor: { x: 40, y: 72, z: 40 },
    });
    expect(() => taskSpecSchema.parse({
      kind: "build",
      planId: "invalid-template",
      sitePolicy: "home-compound",
      compoundPlacement: {
        zone: "production",
        minDistance: 40,
        maxDistance: 16,
      },
    })).toThrow();
    expect(() => taskSpecSchema.parse({
      kind: "build",
      planId: "unsafe-clearance-template",
      sitePolicy: "home-compound",
      compoundPlacement: {
        zone: "production",
        minDistance: 16,
        maxDistance: 40,
        facilityClearance: 11,
      },
    })).toThrow();
  });

  it.each([
    {
      label: "fractional coordinate",
      protectedBounds: [{
        min: { x: -16.5, y: 64, z: -16 },
        max: { x: -8, y: 72, z: -8 },
      }],
    },
    {
      label: "inverted axis",
      protectedBounds: [{
        min: { x: -8, y: 64, z: -16 },
        max: { x: -16, y: 72, z: -8 },
      }],
    },
    {
      label: "missing axis",
      protectedBounds: [{
        min: { x: -16, y: 64 },
        max: { x: -8, y: 72, z: -8 },
      }],
    },
    {
      label: "unknown coordinate field",
      protectedBounds: [{
        min: { x: -16, y: 64, z: -16, radius: 12 },
        max: { x: -8, y: 72, z: -8 },
      }],
    },
    {
      label: "more than 64 entries",
      protectedBounds: Array.from({ length: 65 }, (_, index) => ({
        min: { x: index, y: 64, z: index },
        max: { x: index + 1, y: 72, z: index + 1 },
      })),
    },
  ])("rejects malformed protected bounds fail-closed: $label", ({ protectedBounds }) => {
    expect(() => taskSpecSchema.parse({
      kind: "build",
      planId: "protected-bounds-fixture",
      sitePolicy: "home-compound",
      compoundPlacement: {
        zone: "production",
        minDistance: 16,
        maxDistance: 40,
        protectedBounds,
      },
    })).toThrow();
  });

  it.each([
    {
      label: "fractional blueprint Y",
      origin: { x: 0, y: 64, z: 0 },
      position: { x: 0, y: 0.5, z: 0 },
    },
    {
      label: "fractional blueprint origin",
      origin: { x: 0.25, y: 64, z: 0 },
      position: { x: 0, y: 0, z: 0 },
    },
  ])("requires integer block-grid coordinates: $label", ({ origin, position }) => {
    expect(() => buildPlanDraftSchema.parse({
      name: "Integer coordinate fixture",
      source: "json",
      origin,
      blocks: [{ position, blockId: "minecraft:stone", properties: {} }],
    })).toThrow();
  });

  it("requires a complete data-only security manifest on build plans", () => {
    const base = {
      id: "00000000-0000-4000-8000-000000000101",
      name: "Safe template",
      source: "demo",
      origin: { x: 0, y: 0, z: 0 },
      size: { x: 1, y: 1, z: 1 },
      blocks: [{ position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:stone", properties: {} }],
      requiredItems: { "minecraft:stone": 1 },
      confirmed: true,
      builtIn: true,
      createdAt: "2026-08-02T00:00:00.000Z",
    };
    expect(() => buildPlanSchema.parse(base)).toThrow();
    expect(buildPlanSchema.parse({
      ...base,
      manifest: {
        version: "1.0.0",
        source: { kind: "built-in", author: "test", license: "CC0-1.0" },
        permissions: {
          network: "none",
          fileAccess: "none",
          systemCommands: false,
          commandBlocks: false,
          blockEntityNbt: false,
        },
        sha256: "a".repeat(64),
      },
    }).manifest.permissions.network).toBe("none");
  });

  it("accepts an explicit NPC eating task", () => {
    const task = taskSpecSchema.parse({ kind: "eat", itemId: "minecraft:melon_slice" });
    expect(task).toMatchObject({ kind: "eat", itemId: "minecraft:melon_slice", count: 1 });
  });

  it("accepts a bounded persistent food provisioning task", () => {
    expect(taskSpecSchema.parse({ kind: "provision-food" })).toMatchObject({
      kind: "provision-food",
      count: 8,
      source: "auto",
      foodCategory: "any",
      destination: "backpack",
    });
    expect(() => taskSpecSchema.parse({ kind: "provision-food", count: 65 })).toThrow();
    expect(() => taskSpecSchema.parse({ kind: "provision-food", source: "unsafe" })).toThrow();
    expect(taskSpecSchema.parse({
      kind: "provision-food",
      destination: "player",
      player: "PlayerOne",
      foodCategory: "meat",
    })).toMatchObject({ destination: "player", player: "PlayerOne", foodCategory: "meat" });
    expect(() => taskSpecSchema.parse({ kind: "provision-food", destination: "void" })).toThrow();
  });

  it("accepts bounded ranch tasks for vanilla livestock", () => {
    expect(taskSpecSchema.parse({ kind: "ranch", animalType: "minecraft:cow" })).toMatchObject({
      kind: "ranch",
      action: "establish",
      animalType: "minecraft:cow",
      count: 2,
      radius: 128,
    });
    expect(taskSpecSchema.parse({
      kind: "ranch",
      penAnchor: { x: 18, y: 64, z: -6 },
    })).toMatchObject({ penAnchor: { x: 18, y: 64, z: -6 } });
    expect(taskSpecSchema.parse({
      kind: "ranch",
      fixtureTag: "CodexAcceptanceRanchAnimal",
    })).toMatchObject({ fixtureTag: "CodexAcceptanceRanchAnimal" });
    expect(() => taskSpecSchema.parse({ kind: "ranch", fixtureTag: "bad tag; kill @e" })).toThrow();
    expect(() => taskSpecSchema.parse({ kind: "ranch", animalType: "minecraft:wolf" })).toThrow();
  });

  it("validates build material preferences without accepting arbitrary paths", () => {
    expect(taskSpecSchema.parse({
      kind: "macro",
      skillId: "build.basic-shelter",
      materialPreference: {
        source: "inventory",
        preferredBlockId: "minecraft:dark_oak_planks",
      },
    })).toMatchObject({
      kind: "macro",
      materialPreference: {
        source: "inventory",
        preferredBlockId: "minecraft:dark_oak_planks",
        allowMixed: false,
      },
    });
    expect(() => taskSpecSchema.parse({
      kind: "build",
      planId: "builtin-basic-shelter",
      materialPreference: { source: "nearby", preferredBlockId: "../secret" },
    })).toThrow();
  });

  it("accepts only enumerated live fixture suites and modes", () => {
    expect(liveFixtureRequestSchema.parse({ suite: "dragon", mode: "spawn-book" })).toEqual({
      suite: "dragon",
      mode: "spawn-book",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "dragon", mode: "co-ride-book" })).toEqual({
      suite: "dragon",
      mode: "co-ride-book",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "dragon", mode: "inspect-saints" })).toEqual({
      suite: "dragon",
      mode: "inspect-saints",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "dragon", mode: "stage-obstacle-book" })).toEqual({
      suite: "dragon",
      mode: "stage-obstacle-book",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "dragon-care", mode: "stage-heal" })).toEqual({
      suite: "dragon-care",
      mode: "stage-heal",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "follow", mode: "setup" })).toEqual({
      suite: "follow",
      mode: "setup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "follow", mode: "inspect-air" })).toEqual({
      suite: "follow",
      mode: "inspect-air",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "follow", mode: "cleanup" })).toEqual({
      suite: "follow",
      mode: "cleanup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "ranch", mode: "setup-establish" })).toEqual({
      suite: "ranch",
      mode: "setup-establish",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "ranch", mode: "arm-chat-establish" })).toEqual({
      suite: "ranch",
      mode: "arm-chat-establish",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-delivery", mode: "setup-player" })).toEqual({
      suite: "food-delivery",
      mode: "setup-player",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-delivery", mode: "setup-home" })).toEqual({
      suite: "food-delivery",
      mode: "setup-home",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-delivery", mode: "inspect-home" })).toEqual({
      suite: "food-delivery",
      mode: "inspect-home",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-survival", mode: "arm-guard" })).toEqual({
      suite: "food-survival",
      mode: "arm-guard",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-survival", mode: "verify-restart" })).toEqual({
      suite: "food-survival",
      mode: "verify-restart",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-survival", mode: "recover-cleanup" })).toEqual({
      suite: "food-survival",
      mode: "recover-cleanup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "storage", mode: "setup-retrieve" })).toEqual({
      suite: "storage",
      mode: "setup-retrieve",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "storage", mode: "inspect-expand" })).toEqual({
      suite: "storage",
      mode: "inspect-expand",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "storage", mode: "setup-craft-expand" })).toEqual({
      suite: "storage",
      mode: "setup-craft-expand",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "storage", mode: "inspect-craft-expand" })).toEqual({
      suite: "storage",
      mode: "inspect-craft-expand",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "storage", mode: "setup-restart" })).toEqual({
      suite: "storage",
      mode: "setup-restart",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "no-cheat-expedition", mode: "setup" })).toEqual({
      suite: "no-cheat-expedition",
      mode: "setup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "no-cheat-expedition", mode: "inspect" })).toEqual({
      suite: "no-cheat-expedition",
      mode: "inspect",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "no-cheat-expedition", mode: "cleanup" })).toEqual({
      suite: "no-cheat-expedition",
      mode: "cleanup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "setup-mixed" })).toEqual({
      suite: "build-palette",
      mode: "setup-mixed",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "inspect-chain" })).toEqual({
      suite: "build-palette",
      mode: "inspect-chain",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "catalog" })).toEqual({
      suite: "build-palette",
      mode: "catalog",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "catalog-17" })).toEqual({
      suite: "build-palette",
      mode: "catalog-17",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "setup-family-17" })).toEqual({
      suite: "build-palette",
      mode: "setup-family-17",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-material-chain", mode: "inspect" })).toEqual({
      suite: "build-material-chain",
      mode: "inspect",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-resume", mode: "inspect-failed" })).toEqual({
      suite: "build-resume",
      mode: "inspect-failed",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "build-resume", mode: "release" })).toEqual({
      suite: "build-resume",
      mode: "release",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "natural-tree", mode: "setup" })).toEqual({
      suite: "natural-tree",
      mode: "setup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "natural-tree", mode: "cleanup" })).toEqual({
      suite: "natural-tree",
      mode: "cleanup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "player-state", mode: "inspect" })).toEqual({
      suite: "player-state",
      mode: "inspect",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "eating-action", mode: "setup-rotten" })).toEqual({
      suite: "eating-action",
      mode: "setup-rotten",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "eating-action", mode: "setup-full" })).toEqual({
      suite: "eating-action",
      mode: "setup-full",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "fishing-action", mode: "setup" })).toEqual({
      suite: "fishing-action",
      mode: "setup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "farm-action", mode: "setup-empty" })).toEqual({
      suite: "farm-action",
      mode: "setup-empty",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "guard-resume", mode: "release" })).toEqual({
      suite: "guard-resume",
      mode: "release",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "craft-chain", mode: "checkpoint" })).toEqual({
      suite: "craft-chain",
      mode: "checkpoint",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "resource-priority", mode: "inspect" })).toEqual({
      suite: "resource-priority",
      mode: "inspect",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "resource-priority", mode: "setup-fishing" })).toEqual({
      suite: "resource-priority",
      mode: "setup-fishing",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "resource-priority", mode: "setup-torches" })).toEqual({
      suite: "resource-priority",
      mode: "setup-torches",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "resource-priority", mode: "inspect-craft" })).toEqual({
      suite: "resource-priority",
      mode: "inspect-craft",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "food-survival", mode: "setup-16" })).toEqual({
      suite: "food-survival",
      mode: "setup-16",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "bed-sleep", mode: "prepare-night" })).toEqual({
      suite: "bed-sleep",
      mode: "prepare-night",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "deep-mining", mode: "setup" })).toEqual({
      suite: "deep-mining",
      mode: "setup",
    });
    expect(liveFixtureRequestSchema.parse({ suite: "save-and-quit", mode: "arm" })).toEqual({
      suite: "save-and-quit",
      mode: "arm",
    });
    expect(() => liveFixtureRequestSchema.parse({ suite: "dragon", mode: "fishing" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "dragon-care", mode: "run-command" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "setup" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-material-chain", mode: "run-command" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "inspect-natural-tree" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "setup-family-10000" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-palette", mode: "setup-family-1;op" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-resume", mode: "inspect" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "build-resume", mode: "setup", command: "/setblock ~ ~ ~ air" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "natural-tree", mode: "setup-mixed" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "no-cheat-expedition", mode: "teleport" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({
      suite: "no-cheat-expedition",
      mode: "setup",
      command: "/tp @s 0 100 0",
    })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "player-state", mode: "arm" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "eating-action", mode: "setup-beef" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "food-survival", mode: "spawn-cow" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "craft-chain", mode: "execute" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({
      suite: "resource-priority",
      mode: "inspect",
      command: "/fill ~ ~ ~ ~8 ~8 ~8 coal_ore",
    })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "bed-sleep", mode: "set-time", time: 13000 })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "deep-mining", mode: "run-command" })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({
      suite: "save-and-quit",
      mode: "arm",
      leaseMillis: 300000,
    })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({ suite: "natural-tree", mode: "inspect", radius: 64 })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({
      suite: "dragon",
      mode: "spawn-book",
      command: "/op @a",
    })).toThrow();
  });

  it("bounds parameterized live fixtures without exposing command strings", () => {
    expect(liveFixtureRequestSchema.parse({
      suite: "life-skill",
      mode: "bed-chain",
    })).toEqual({ suite: "life-skill", mode: "bed-chain" });
    expect(liveFixtureRequestSchema.parse({
      suite: "drop-to-npc",
      mode: "drop",
      itemId: "minecraft:oak_log",
      count: 64,
    })).toMatchObject({ itemId: "minecraft:oak_log", count: 64 });
    expect(() => liveFixtureRequestSchema.parse({
      suite: "drop-to-npc",
      mode: "drop",
      itemId: "minecraft:oak_log; say secret",
      count: 1,
    })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({
      suite: "npc-state",
      mode: "set",
      food: 21,
      saturation: 5,
      health: 20,
    })).toThrow();
    expect(() => liveFixtureRequestSchema.parse({
      suite: "npc-state",
      mode: "set",
      food: 4,
      saturation: 5,
      health: 20,
    })).toThrow();
  });

  it("accepts home placement only as a typed craft option", () => {
    expect(taskSpecSchema.parse({
      kind: "craft",
      itemId: "minecraft:white_bed",
      count: 1,
      placeAtHome: true,
    })).toMatchObject({ kind: "craft", placeAtHome: true });
  });

  it("accepts Codex and Claude compatible provider profiles", () => {
    expect(aiProviderDraftSchema.parse({
      kind: "codex-api",
      name: "Private Codex",
      baseUrl: "https://gateway.example.test/v1",
      model: "custom-codex-model",
      apiKey: "secret",
    }).kind).toBe("codex-api");

    expect(aiProviderDraftSchema.parse({
      kind: "claude-api",
      name: "Private Claude",
      baseUrl: "http://127.0.0.1:9000",
      model: "custom-claude-model",
    }).kind).toBe("claude-api");
  });

  it("normalizes least-privilege skill manifests", () => {
    const draft = declarativeSkillDraftSchema.parse({
      id: "custom.safe",
      name: "Safe skill",
      description: "Typed Minecraft task only",
      steps: [{ label: "Gather", task: { kind: "gather", itemId: "minecraft:stone", count: 1 } }],
    });
    expect(draft.manifest).toBeUndefined();

    expect(() => declarativeSkillDraftSchema.parse({
      ...draft,
      manifest: {
        source: { kind: "external", author: "Author" },
        permissions: { tools: ["shell_command"] },
      },
    })).toThrow();

    expect(declarativeSkillSchema.parse({
      ...draft,
      manifest: {},
      security: {
        status: "approved",
        sha256: "a".repeat(64),
        reviewedAt: "2026-08-02T00:00:00.000Z",
        findings: [],
      },
      builtIn: false,
      createdAt: "2026-08-02T00:00:00.000Z",
      updatedAt: "2026-08-02T00:00:00.000Z",
    }).manifest.permissions).toMatchObject({ network: "none", fileAccess: "none", systemCommands: false });
  });
});
