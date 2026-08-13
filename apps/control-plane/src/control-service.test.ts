import { describe, expect, it } from "vitest";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import type { BridgeCommand, TaskRecord, WorldSnapshot } from "@mc/protocol";
import type { TaskCallbacks } from "./backend.js";
import { ControlService } from "./control-service.js";
import { BUILTIN_BUILD_IDS } from "./builtin-content.js";
import { SimulatorBackend } from "./simulator-backend.js";

class ConcurrentPendingBackend extends SimulatorBackend {
  readonly supportsConcurrentTasks = true;
  readonly sentChat: string[] = [];

  override runTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string> {
    callbacks.onProgress(0, `started ${task.id}`, "active");
    return new Promise((_resolve, reject) => {
      const abort = () => reject(signal.reason instanceof Error ? signal.reason : new Error("cancelled"));
      if (signal.aborted) abort();
      else signal.addEventListener("abort", abort, { once: true });
    });
  }

  override async sendChat(message: string): Promise<void> {
    this.sentChat.push(message);
  }
}

class RecoveringBackend extends SimulatorBackend {
  readonly resumedIds: string[] = [];
  readonly continuedIds: string[] = [];
  readonly sentChat: string[] = [];

  resumeTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.resumedIds.push(task.id);
    callbacks.onProgress(1, "恢复的当前步骤完成", "active");
    return Promise.resolve("恢复的当前步骤完成");
  }

  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.continuedIds.push(task.id);
    callbacks.onProgress(1, "后续步骤完成", "active");
    return Promise.resolve("后续步骤完成");
  }

  override async sendChat(message: string): Promise<void> {
    this.sentChat.push(message);
  }
}

class MacroCaptureBackend extends SimulatorBackend {
  readonly tasks: TaskRecord[] = [];
  readonly #mode: "survival" | "creative";

  constructor(mode: "survival" | "creative") {
    super(`codex-${mode}`);
    this.#mode = mode;
  }

  override snapshot(): WorldSnapshot {
    return {
      ...super.snapshot(),
      materialMode: this.#mode,
      gameMode: this.#mode,
    };
  }

  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.tasks.push(structuredClone(task));
    callbacks.onProgress(1, `${task.spec.kind} complete`, "active");
    return Promise.resolve(`${task.spec.kind} complete`);
  }
}

class FailOnceBuildBackend extends SimulatorBackend {
  readonly attemptedTaskIds: string[] = [];
  #failed = false;

  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    this.attemptedTaskIds.push(task.id);
    if (!this.#failed) {
      this.#failed = true;
      callbacks.onProgress(0.42, "BUILD_SITE_BLOCKED @ 1,64,1", "active");
      return Promise.reject(new Error("BUILD_SITE_BLOCKED: occupied"));
    }
    callbacks.onProgress(1, "从失败点完成", "active");
    return Promise.resolve("从失败点完成");
  }
}

class AlwaysFailBuildBackend extends SimulatorBackend {
  override runTask(task: TaskRecord, callbacks: TaskCallbacks): Promise<string> {
    callbacks.onProgress(0.25, `blocked ${task.id}`, "active");
    return Promise.reject(new Error("BUILD_SITE_BLOCKED: occupied"));
  }
}

class RecoverableCancelBackend extends AlwaysFailBuildBackend {
  readonly sentBridgeCommands: BridgeCommand[] = [];

  async sendBridgeCommand(command: BridgeCommand): Promise<void> {
    this.sentBridgeCommands.push(structuredClone(command));
  }
}

class PartialGatherMacroBackend extends SimulatorBackend {
  readonly #reportObservedCounts: boolean;

  constructor(id: string, reportObservedCounts: boolean) {
    super(id);
    this.#reportObservedCounts = reportObservedCounts;
  }

  override runTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string> {
    if (task.spec.kind !== "gather") return Promise.reject(new Error("Expected the gather macro step first"));
    callbacks.onProgress(
      53 / 64,
      "已采集 53/64",
      "active",
      this.#reportObservedCounts
        ? { completedCount: 53, targetCount: 64, retainedCount: 51 }
        : undefined,
    );
    return new Promise((_resolve, reject) => {
      const abort = () => reject(signal.reason instanceof Error ? signal.reason : new Error("cancelled"));
      if (signal.aborted) abort();
      else signal.addEventListener("abort", abort, { once: true });
    });
  }
}

describe("ControlService", () => {
  it("rejects a conflicting controller lease", () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    service.acquireLease("codex-sim", "codex");
    expect(() => service.acquireLease("codex-sim", "antigravity")).toThrow(/codex/);
  });

  it("exposes exact child progress for a macro without treating parent progress as an item count", async () => {
    const service = new ControlService();
    const backend = new PartialGatherMacroBackend("codex-structured-progress", true);
    service.registerBackend(backend);
    const assigned = service.assignTask(backend.id, {
      kind: "macro",
      skillId: "life.gather-and-deliver",
      arguments: { itemId: "minecraft:coal", count: 64, player: "PlayerOne" },
      requestedBy: "test",
    }, "test");

    for (let attempt = 0; attempt < 50 && service.getTask(assigned.id).stepProgress === undefined; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    expect(service.getTask(assigned.id)).toMatchObject({
      status: "running",
      progress: 53 / 128,
      currentStepIndex: 0,
      currentStepKind: "gather",
      stepProgress: 53 / 64,
      completedCount: 53,
      targetCount: 64,
      retainedCount: 51,
    });
    service.cancelTask(assigned.id, "structured progress test complete");
  });

  it("never invents completed or retained counts from a macro percentage", async () => {
    const service = new ControlService();
    const backend = new PartialGatherMacroBackend("codex-unobserved-counts", false);
    service.registerBackend(backend);
    const assigned = service.assignTask(backend.id, {
      kind: "macro",
      skillId: "life.gather-and-deliver",
      arguments: { itemId: "minecraft:coal", count: 64, player: "PlayerOne" },
      requestedBy: "test",
    }, "test");

    for (let attempt = 0; attempt < 50 && service.getTask(assigned.id).stepProgress === undefined; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    const running = service.getTask(assigned.id);
    expect(running).toMatchObject({
      progress: 53 / 128,
      currentStepIndex: 0,
      currentStepKind: "gather",
      stepProgress: 53 / 64,
      targetCount: 64,
    });
    expect(running).not.toHaveProperty("completedCount");
    expect(running).not.toHaveProperty("retainedCount");
    service.cancelTask(assigned.id, "unobserved count test complete");
  });

  it("runs a queued task to completion", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const task = service.assignTask("codex-sim", { kind: "follow", player: "PlayerOne", distance: 3, requestedBy: "test" }, "test");
    await new Promise((resolve) => setTimeout(resolve, 2300));
    expect(service.getTask(task.id).status).toBe("succeeded");
  }, 5000);

  it("retries the same failed build task id and preserves its progress checkpoint", async () => {
    const service = new ControlService();
    const backend = new FailOnceBuildBackend();
    service.registerBackend(backend);
    const failed = service.assignTask(backend.id, {
      kind: "build",
      planId: BUILTIN_BUILD_IDS.basicShelter,
      placement: "companion",
      requestedBy: "test",
    }, "codex-driver");
    for (let attempt = 0; attempt < 50 && service.getTask(failed.id).status !== "failed"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(service.getTask(failed.id)).toMatchObject({ status: "failed", progress: 0.42 });

    const retried = service.retryLatestBuildTask(backend.id, "codex-driver");
    expect(retried.id).toBe(failed.id);
    for (let attempt = 0; attempt < 50 && service.getTask(failed.id).status !== "succeeded"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(service.getTask(failed.id)).toMatchObject({ status: "succeeded", progress: 1 });
    expect(backend.attemptedTaskIds).toEqual([failed.id, failed.id]);
  });

  it("reuses an active build and automatically resumes the same failed build id", async () => {
    const pendingService = new ControlService();
    const pendingBackend = new ConcurrentPendingBackend();
    pendingService.registerBackend(pendingBackend);
    const first = pendingService.assignTask(pendingBackend.id, {
      kind: "macro",
      skillId: "build.basic-shelter",
      arguments: {},
      requestedBy: "PlayerOne",
    }, "codex-driver");
    const duplicate = pendingService.assignTask(pendingBackend.id, {
      kind: "macro",
      skillId: "build.basic-shelter",
      arguments: {},
      requestedBy: "PlayerOne",
    }, "codex-driver");
    expect(duplicate.id).toBe(first.id);
    pendingService.cancelTask(first.id, "测试完成");

    const recoveryService = new ControlService();
    const recoveryBackend = new FailOnceBuildBackend();
    recoveryService.registerBackend(recoveryBackend);
    const failed = recoveryService.assignTask(recoveryBackend.id, {
      kind: "build",
      planId: BUILTIN_BUILD_IDS.basicShelter,
      requestedBy: "PlayerOne",
    }, "codex-driver");
    for (let attempt = 0; attempt < 50 && recoveryService.getTask(failed.id).status !== "failed"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    const resumed = recoveryService.assignTask(recoveryBackend.id, {
      kind: "build",
      planId: BUILTIN_BUILD_IDS.basicShelter,
      requestedBy: "PlayerOne",
    }, "codex-driver");
    expect(resumed.id).toBe(failed.id);
    for (let attempt = 0; attempt < 50 && recoveryService.getTask(failed.id).status !== "succeeded"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(recoveryBackend.attemptedTaskIds).toEqual([failed.id, failed.id]);
  });

  it("retries a retryable craft task with the same id and original controller", async () => {
    const service = new ControlService();
    const backend = new FailOnceBuildBackend();
    service.registerBackend(backend);
    const failed = service.assignTask(backend.id, {
      kind: "craft",
      itemId: "minecraft:diamond_pickaxe",
      count: 1,
      deliverTo: "PlayerOne",
      requestedBy: "PlayerOne",
    }, "antigravity-autoplay");
    for (let attempt = 0; attempt < 50 && service.getTask(failed.id).status !== "failed"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(() => service.retryTask(failed.id, "codex-driver")).toThrow(/原控制入口/u);
    const retried = service.retryTask(failed.id, "antigravity-autoplay");
    expect(retried.id).toBe(failed.id);
    for (let attempt = 0; attempt < 50 && service.getTask(failed.id).status !== "succeeded"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(service.getTask(failed.id)).toMatchObject({ status: "succeeded", progress: 1 });
    expect(backend.attemptedTaskIds).toEqual([failed.id, failed.id]);
  });

  it("forwards cancellation for a terminal retryable build checkpoint", async () => {
    const service = new ControlService();
    const backend = new RecoverableCancelBackend();
    service.registerBackend(backend);
    const failed = service.assignTask(backend.id, {
      kind: "build",
      planId: BUILTIN_BUILD_IDS.basicShelter,
      requestedBy: "PlayerOne",
    });
    for (let attempt = 0; attempt < 50 && service.getTask(failed.id).status !== "failed"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }

    const unchanged = service.cancelTask(failed.id, "discard exact checkpoint");
    await Promise.resolve();

    expect(unchanged.status).toBe("failed");
    expect(backend.sentBridgeCommands).toEqual([{
      type: "cancel-task",
      taskId: failed.id,
      reason: "discard exact checkpoint",
    }]);
  });

  it("deduplicates only the same build identity, controller and placement", () => {
    const service = new ControlService();
    const backend = new ConcurrentPendingBackend();
    service.registerBackend(backend);
    const base = {
      kind: "macro" as const,
      skillId: "build.basic-shelter",
      arguments: {},
      placementAnchor: { x: 10, y: 64, z: 10 },
      materialMode: "survival" as const,
      materialPreference: {
        source: "inventory" as const,
        preferredBlockId: "minecraft:dark_oak_planks",
        allowMixed: false,
      },
      requestedBy: "PlayerOne",
    };
    const first = service.assignTask(backend.id, base, "codex-driver");
    const duplicate = service.assignTask(backend.id, { ...base, note: "same request, different wording" }, "codex-driver");
    const elsewhere = service.assignTask(backend.id, {
      ...base,
      placementAnchor: { x: 80, y: 64, z: 80 },
    }, "codex-driver");
    const differentMaterial = service.assignTask(backend.id, {
      ...base,
      materialPreference: {
        source: "inventory",
        preferredBlockId: "minecraft:spruce_planks",
        allowMixed: false,
      },
    }, "codex-driver");
    const creative = service.assignTask(backend.id, { ...base, materialMode: "creative" }, "codex-driver");
    const differentController = service.assignTask(backend.id, base, "antigravity-autoplay");

    expect(duplicate.id).toBe(first.id);
    expect(new Set([first.id, elsewhere.id, differentMaterial.id, creative.id, differentController.id]).size).toBe(5);
    for (const task of [first, elsewhere, differentMaterial, creative, differentController]) {
      service.cancelTask(task.id, "测试完成");
    }
  });

  it("does not automatically revive an old failed build", async () => {
    const service = new ControlService();
    const backend = new FailOnceBuildBackend();
    service.registerBackend(backend);
    const spec = {
      kind: "build" as const,
      planId: BUILTIN_BUILD_IDS.basicShelter,
      requestedBy: "PlayerOne",
    };
    const failed = service.assignTask(backend.id, spec, "codex-driver");
    for (let attempt = 0; attempt < 50 && service.getTask(failed.id).status !== "failed"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    failed.finishedAt = new Date(Date.now() - 10 * 60_000).toISOString();
    const fresh = service.assignTask(backend.id, spec, "codex-driver");
    expect(fresh.id).not.toBe(failed.id);
  });

  it("resumes only retryable builds from the same player and controller", async () => {
    const service = new ControlService();
    const backend = new AlwaysFailBuildBackend();
    service.registerBackend(backend);
    const assignFailed = async (requestedBy: string, owner: string) => {
      const task = service.assignTask(backend.id, {
        kind: "build",
        planId: BUILTIN_BUILD_IDS.basicShelter,
        requestedBy,
      }, owner);
      for (let attempt = 0; attempt < 50 && service.getTask(task.id).status !== "failed"; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 10));
      }
      return task;
    };
    const crow = await assignFailed("PlayerOne", "codex-driver");
    const alice = await assignFailed("Alice", "codex-driver");
    const otherController = await assignFailed("PlayerOne", "antigravity-autoplay");

    const resumed = service.retryLatestBuildTask(backend.id, "codex-driver", "PlayerOne");
    expect(resumed.id).toBe(crow.id);
    expect(resumed.id).not.toBe(otherController.id);
    expect(() => service.retryLatestBuildTask(backend.id, "codex-driver", "Mallory")).toThrow(/当前玩家/u);

    alice.error!.retryable = false;
    expect(() => service.retryLatestBuildTask(backend.id, "codex-driver", "Alice")).toThrow(/不可重试/u);
  });

  it("cancels a mistaken wood-delivery macro when the player corrects it to building", () => {
    const service = new ControlService();
    const backend = new ConcurrentPendingBackend();
    service.registerBackend(backend);
    const delivery = service.assignTask(backend.id, {
      kind: "macro",
      skillId: "life.gather-and-deliver",
      arguments: { itemId: "#minecraft:logs", count: 16, player: "PlayerOne" },
      requestedBy: "PlayerOne",
    }, "codex-driver");
    const build = service.assignTask(backend.id, {
      kind: "macro",
      skillId: "build.basic-shelter",
      arguments: {},
      requestedBy: "PlayerOne",
    }, "codex-driver", { replaceConflictingDelivery: true });
    expect(service.getTask(delivery.id).status).toBe("cancelled");
    expect(build.id).not.toBe(delivery.id);
    service.cancelTask(build.id, "测试完成");
  });

  it("cancels compatible wood-family delivery only for the same controller", () => {
    const service = new ControlService();
    const backend = new ConcurrentPendingBackend();
    service.registerBackend(backend);
    const mistaken = service.assignTask(backend.id, {
      kind: "deliver",
      itemId: "minecraft:stripped_dark_oak_wood",
      count: 16,
      player: "PlayerOne",
      requestedBy: "PlayerOne",
    }, "codex-driver");
    const otherController = service.assignTask(backend.id, {
      kind: "deliver",
      itemId: "minecraft:bamboo_planks",
      count: 16,
      player: "PlayerOne",
      requestedBy: "PlayerOne",
    }, "antigravity-autoplay");
    const build = service.assignTask(backend.id, {
      kind: "macro",
      skillId: "build.basic-shelter",
      arguments: {},
      requestedBy: "PlayerOne",
    }, "codex-driver", { replaceConflictingDelivery: true });

    expect(service.getTask(mistaken.id).status).toBe("cancelled");
    expect(service.getTask(otherController.id).status).toBe("running");
    service.cancelTask(otherController.id, "测试完成");
    service.cancelTask(build.id, "测试完成");
  });

  it("posts one terminal task message for Antigravity without keeping its turn open", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const terminalMessages: string[] = [];
    const unsubscribe = service.events.subscribe((event) => {
      if (event.type === "chat" && event.data?.owner === "antigravity-autoplay") {
        terminalMessages.push(String(event.data.message));
      }
    });
    const task = service.assignTask("codex-sim", {
      kind: "follow",
      player: "PlayerOne",
      distance: 3,
      requestedBy: "test",
    }, "antigravity-autoplay");
    await new Promise((resolve) => setTimeout(resolve, 2500));
    unsubscribe();
    expect(service.getTask(task.id).status).toBe("succeeded");
    expect(terminalMessages).toEqual([expect.stringMatching(/^任务完成：/u)]);
  }, 5000);

  it("coalesces duplicate start replies for one input without suppressing its terminal result", async () => {
    const service = new ControlService();
    const backend = new RecoveringBackend();
    service.registerBackend(backend);
    const task = service.assignTask("codex-sim", {
      kind: "follow",
      player: "PlayerOne",
      distance: 3,
      requestedBy: "PlayerOne",
    }, "antigravity-autoplay");
    const options = { interactionId: "mc-chat-42", phase: "start" as const };

    await service.sendChat(
      "codex-sim",
      "遵命，我去执行。",
      "antigravity-autoplay",
      options,
    );
    await service.sendChat(
      "codex-sim",
      `任务 ${task.id} 已经开始。`,
      "antigravity-autoplay",
      options,
    );

    await new Promise((resolve) => setTimeout(resolve, 2500));
    expect(service.getTask(task.id).status).toBe("succeeded");
    expect(backend.sentChat).toEqual([
      "遵命，我去执行。",
      expect.stringMatching(/^任务完成：/u),
    ]);
  }, 5000);

  it("posts one terminal task message for a deterministic local chat task", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const terminalMessages: string[] = [];
    const unsubscribe = service.events.subscribe((event) => {
      if (event.type === "chat" && event.data?.owner === "codex-driver") {
        terminalMessages.push(String(event.data.message));
      }
    });
    const task = service.assignTask("codex-sim", {
      kind: "follow",
      player: "PlayerOne",
      distance: 3,
      requestedBy: "test",
    }, "codex-driver");
    await new Promise((resolve) => setTimeout(resolve, 2500));
    unsubscribe();
    expect(service.getTask(task.id).status).toBe("succeeded");
    expect(terminalMessages).toEqual([expect.stringMatching(/^任务完成：/u)]);
  }, 5000);

  it("posts one cancellation message for a non-primary concurrent task", async () => {
    const service = new ControlService();
    const backend = new ConcurrentPendingBackend();
    service.registerBackend(backend);
    const first = service.assignTask("codex-sim", {
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 16,
      requestedBy: "test",
    }, "antigravity-autoplay");
    const second = service.assignTask("codex-sim", {
      kind: "combat",
      targetType: "hostile",
      maxDistance: 24,
      requestedBy: "test",
    }, "test");

    expect(service.getCompanion("codex-sim").activeTaskId).toBe(second.id);
    service.cancelTask(first.id, "切换任务");
    for (let attempt = 0; attempt < 50 && backend.sentChat.length === 0; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    expect(backend.sentChat).toEqual(["任务已取消：切换任务"]);
    service.cancelTask(second.id, "测试结束");
  });

  it("restores a multi-step task after a full control-service restart", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-task-journal-"));
    try {
      const first = new ControlService({ stateDirectory });
      first.registerBackend(new ConcurrentPendingBackend());
      const task = first.assignTask("codex-sim", {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 8, player: "PlayerOne" },
        requestedBy: "test",
      }, "antigravity-autoplay");
      expect(first.getTask(task.id).status).toBe("running");

      const second = new ControlService({ stateDirectory });
      const backend = new RecoveringBackend();
      second.registerBackend(backend);
      for (let attempt = 0; attempt < 100 && second.getTask(task.id).status !== "succeeded"; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 10));
      }

      expect(second.getTask(task.id)).toMatchObject({ status: "succeeded", progress: 1 });
      expect(backend.resumedIds).toEqual([task.id]);
      expect(backend.continuedIds).toEqual([task.id]);
      expect(backend.sentChat).toEqual([expect.stringMatching(/^任务完成：/u)]);
      await new Promise((resolve) => setTimeout(resolve, 20));
      const third = new ControlService({ stateDirectory });
      const reconnected = new RecoveringBackend();
      third.registerBackend(reconnected);
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(reconnected.sentChat).toEqual([]);
      const journal = await readFile(path.join(stateDirectory, "task-journal.json"), "utf8");
      expect(journal).not.toContain("apiKey");
      expect(JSON.parse(journal).tasks.find((entry: { task: TaskRecord }) => entry.task.id === task.id)).toMatchObject({
        owner: "antigravity-autoplay",
        terminalNotified: true,
      });
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  });

  it("expands a declarative macro into validated backend tasks", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const task = service.assignTask("codex-sim", {
      kind: "macro",
      skillId: "combat.guard-owner",
      arguments: { player: "PlayerOne", radius: 10 },
      requestedBy: "test",
    }, "test");

    await new Promise((resolve) => setTimeout(resolve, 2300));
    expect(service.getTask(task.id)).toMatchObject({
      status: "succeeded",
      progress: 1,
      message: "声明式技能 combat.guard-owner 已完成",
    });
  }, 5000);

  it("locks macro build placement and propagates one durable material palette task", async () => {
    const survival = new MacroCaptureBackend("survival");
    const survivalService = new ControlService();
    survivalService.registerBackend(survival);
    const survivalTask = survivalService.assignTask(survival.id, {
      kind: "macro",
      skillId: "build.basic-shelter",
      arguments: {},
      materialPreference: {
        source: "inventory",
        preferredBlockId: "minecraft:dark_oak_planks",
        allowMixed: false,
      },
      requestedBy: "test",
    }, "test");
    await new Promise((resolve) => setTimeout(resolve, 20));

    expect(survivalTask.spec).toMatchObject({
      kind: "macro",
      materialMode: "survival",
      placementAnchor: { x: -156, y: 76, z: -62 },
    });
    expect(survival.tasks.map((child) => child.spec.kind)).toEqual(["build"]);
    expect(survival.tasks.at(-1)?.spec).toMatchObject({
      kind: "build",
      placementAnchor: { x: -156, y: 76, z: -62 },
      materialPreference: {
        source: "inventory",
        preferredBlockId: "minecraft:dark_oak_planks",
      },
    });

    const creative = new MacroCaptureBackend("creative");
    const creativeService = new ControlService();
    creativeService.registerBackend(creative);
    creativeService.assignTask(creative.id, {
      kind: "macro",
      skillId: "build.basic-shelter",
      arguments: {},
      requestedBy: "test",
    }, "test");
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(creative.tasks.map((child) => child.spec.kind)).toEqual(["build"]);
  });

  it("requires a confirmed build preview before assigning construction", () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const plan = service.previewBuild({
      name: "测试平台",
      source: "json",
      origin: { x: 0, y: 64, z: 0 },
      blocks: [
        { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:stone", properties: {} },
      ],
    });

    expect(() => service.assignTask("codex-sim", {
      kind: "build",
      planId: plan.id,
      requestedBy: "test",
    }, "test")).toThrow(/尚未确认/);

    service.confirmBuild(plan.id);
    const task = service.assignTask("codex-sim", {
      kind: "build",
      planId: plan.id,
      requestedBy: "test",
    }, "test");
    expect(task.status).toBe("running");
    service.cancelTask(task.id, "测试完成");
  });

  it("accepts an explicitly directed Antigravity message while free chat is disabled", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    await service.updateChatSettings({
      freeChatEnabled: false,
      playerName: "PlayerOne",
      target: "active-provider",
      persona: {
        mode: "inherit",
        displayName: "",
        personality: "",
        speakingStyle: "",
        memoryNotes: "",
      },
    });

    const accepted = await service.recordIncomingChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "陪我探索",
      at: "2026-07-31T12:00:00.000Z",
    }, true);
    const rejected = await service.recordIncomingChat({
      companionId: "codex-sim",
      sender: "Alex",
      message: "接管 NPC",
      at: "2026-07-31T12:00:01.000Z",
    }, true);

    expect(accepted).toMatchObject({ sender: "PlayerOne", message: "陪我探索" });
    expect(rejected).toBeNull();
  });
});
