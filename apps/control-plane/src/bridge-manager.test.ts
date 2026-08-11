import { once } from "node:events";
import type { AddressInfo } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { PROTOCOL_VERSION, type BridgeMessage, type WorldSnapshot } from "@mc/protocol";
import WebSocket, { WebSocketServer } from "ws";
import { afterEach, describe, expect, it, vi } from "vitest";
import { BridgeManager, ChatRelayGuard } from "./bridge-manager.js";
import { ControlService } from "./control-service.js";

const TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

function snapshot(sequence = 1): WorldSnapshot {
  return {
    sequence,
    capturedAt: new Date().toISOString(),
    worldId: "forge-test-world",
    dimension: "minecraft:overworld",
    position: { x: 8, y: 64, z: -4 },
    ownerPosition: { x: 0, y: 64, z: 0 },
    ownerDistance: 9,
    yaw: 0,
    pitch: 0,
    health: 20,
    maxHealth: 20,
    food: 20,
    air: 300,
    gameMode: "survival",
    timeOfDay: 1000,
    weather: "clear",
    inventory: [],
    nearbyEntities: [],
    status: "ready",
    stance: "work",
  };
}

function hello(token = TOKEN) {
  return {
    type: "hello" as const,
    protocolVersion: PROTOCOL_VERSION,
    token,
    companion: {
      id: "codex-forge",
      name: "Codex",
      backend: "forge-1.20.1" as const,
      gameVersion: "1.20.1",
      loader: "Forge 47.4.21",
      capabilities: ["chat", "observe", "move", "follow", "combat", "gather", "farm", "storage"] as const,
      snapshot: snapshot(),
    },
  };
}

async function waitFor(check: () => boolean, timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() >= deadline) throw new Error("Timed out waiting for bridge state");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

function nextJson(socket: WebSocket): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const onError = (error: Error) => {
      socket.off("message", onMessage);
      reject(error);
    };
    const onMessage = (data: WebSocket.RawData) => {
      socket.off("error", onError);
      resolve(JSON.parse(data.toString("utf8")) as Record<string, unknown>);
    };
    socket.once("error", onError);
    socket.once("message", onMessage);
  });
}

describe("BridgeManager", () => {
  const servers: WebSocketServer[] = [];
  const sockets: WebSocket[] = [];

  async function connect(manager: BridgeManager): Promise<WebSocket> {
    const server = new WebSocketServer({ host: "127.0.0.1", port: 0 });
    servers.push(server);
    server.on("connection", (socket) => manager.attach(socket));
    await once(server, "listening");
    const address = server.address() as AddressInfo;
    const socket = new WebSocket(`ws://127.0.0.1:${address.port}`);
    sockets.push(socket);
    await once(socket, "open");
    return socket;
  }

  afterEach(async () => {
    for (const socket of sockets.splice(0)) socket.terminate();
    await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve()))));
  });

  it("rejects a bridge using the wrong token", async () => {
    const service = new ControlService();
    const socket = await connect(new BridgeManager({ service, token: TOKEN }));
    const closed = once(socket, "close");

    socket.send(JSON.stringify(hello("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")));

    const [code] = await closed;
    expect(code).toBe(1008);
    expect(service.listCompanions()).toEqual([]);
  });

  it("runs a Forge task through the authenticated WebSocket", async () => {
    const service = new ControlService();
    const onChat = vi.fn();
    const socket = await connect(new BridgeManager({ service, token: TOKEN, onChat }));
    socket.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);

    const commandPromise = nextJson(socket);
    const task = service.assignTask("codex-forge", {
      kind: "follow",
      player: "PlayerOne",
      distance: 3,
      requestedBy: "test",
    }, "test");
    const command = await commandPromise;
    expect(command.type).toBe("run-task");
    expect((command.task as { id: string }).id).toBe(task.id);

    const progress: BridgeMessage = {
      type: "task-progress",
      companionId: "codex-forge",
      taskId: task.id,
      progress: 0.5,
      message: "正在接近 PlayerOne",
    };
    socket.send(JSON.stringify(progress));
    await waitFor(() => service.getTask(task.id).progress === 0.5);

    socket.send(JSON.stringify({
      type: "snapshot",
      companionId: "codex-forge",
      snapshot: snapshot(2),
    } satisfies BridgeMessage));
    await waitFor(() => service.getSnapshot("codex-forge").sequence === 2);

    socket.send(JSON.stringify({
      type: "chat",
      companionId: "codex-forge",
      sender: "PlayerOne",
      message: "Codex，跟我来",
      at: new Date().toISOString(),
    } satisfies BridgeMessage));
    await waitFor(() => onChat.mock.calls.length === 1);

    socket.send(JSON.stringify({
      type: "task-result",
      companionId: "codex-forge",
      taskId: task.id,
      ok: true,
      message: "已跟随 PlayerOne",
    } satisfies BridgeMessage));
    await waitFor(() => service.getTask(task.id).status === "succeeded");
    expect(service.getTask(task.id).message).toBe("已跟随 PlayerOne");

    const closed = once(socket, "close");
    socket.close();
    await closed;
    await waitFor(() => !service.getCompanion("codex-forge").connected);
  });

  it("does not acknowledge recall until a newer snapshot proves the NPC is physically near its owner", async () => {
    const service = new ControlService();
    const socket = await connect(new BridgeManager({ service, token: TOKEN }));
    socket.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);

    const commandPromise = nextJson(socket);
    let settled = false;
    const recall = service.controlCompanion("codex-forge", "recall").then((companion) => {
      settled = true;
      return companion;
    });
    expect(await commandPromise).toMatchObject({ type: "npc-control", action: "recall" });

    socket.send(JSON.stringify({
      type: "snapshot",
      companionId: "codex-forge",
      snapshot: { ...snapshot(2), ownerDistance: 20 },
    } satisfies BridgeMessage));
    await waitFor(() => service.getSnapshot("codex-forge").sequence === 2);
    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(settled).toBe(false);

    socket.send(JSON.stringify({
      type: "snapshot",
      companionId: "codex-forge",
      snapshot: { ...snapshot(3), ownerDistance: 1.5, stance: "follow" },
    } satisfies BridgeMessage));

    const companion = await recall;
    expect(companion.snapshot).toMatchObject({ sequence: 3, ownerDistance: 1.5 });
  });

  it("preserves a structured Forge task failure code", async () => {
    const service = new ControlService();
    const socket = await connect(new BridgeManager({ service, token: TOKEN }));
    socket.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);

    const commandPromise = nextJson(socket);
    const task = service.assignTask("codex-forge", {
      kind: "farm",
      cropId: "minecraft:wheat",
      action: "harvest",
      radius: 8,
      requestedBy: "test",
    }, "test");
    await commandPromise;

    socket.send(JSON.stringify({
      type: "task-result",
      companionId: "codex-forge",
      taskId: task.id,
      ok: false,
      message: "指定范围内没有可执行的农务目标",
      code: "FARM_TARGET_NOT_FOUND",
    } satisfies BridgeMessage));

    await waitFor(() => service.getTask(task.id).status === "failed");
    expect(service.getTask(task.id)).toMatchObject({
      message: "指定范围内没有可执行的农务目标",
      error: {
        code: "FARM_TARGET_NOT_FOUND",
        message: "指定范围内没有可执行的农务目标",
        retryable: true,
      },
    });
  });

  it("marks an otherwise open bridge as disconnected when heartbeats stop", async () => {
    const now = vi.spyOn(Date, "now").mockReturnValue(1_000_000);
    try {
      const service = new ControlService();
      const socket = await connect(new BridgeManager({ service, token: TOKEN }));
      socket.send(JSON.stringify(hello()));
      await waitFor(() => service.listCompanions().length === 1);
      expect(service.getCompanion("codex-forge").connected).toBe(true);

      now.mockReturnValue(1_031_000);
      expect(service.getCompanion("codex-forge").connected).toBe(false);

      socket.send(JSON.stringify({
        type: "heartbeat",
        companionId: "codex-forge",
        at: new Date().toISOString(),
      } satisfies BridgeMessage));
      await waitFor(() => service.getCompanion("codex-forge").connected);
    } finally {
      now.mockRestore();
    }
  });

  it("dispatches Forge tasks concurrently and tracks pause and resume phases", async () => {
    const service = new ControlService();
    const socket = await connect(new BridgeManager({ service, token: TOKEN }));
    socket.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);

    const gatherCommandPromise = nextJson(socket);
    const gather = service.assignTask("codex-forge", {
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 16,
      priority: 40,
      requestedBy: "test",
    }, "test");
    const gatherCommand = await gatherCommandPromise;
    expect((gatherCommand.task as { id: string }).id).toBe(gather.id);

    const combatCommandPromise = nextJson(socket);
    const combat = service.assignTask("codex-forge", {
      kind: "combat",
      targetType: "minecraft:zombie",
      maxDistance: 32,
      priority: 100,
      requestedBy: "test",
    }, "test");
    const combatCommand = await combatCommandPromise;
    expect((combatCommand.task as { id: string }).id).toBe(combat.id);

    socket.send(JSON.stringify({
      type: "task-progress",
      companionId: "codex-forge",
      taskId: gather.id,
      progress: 0.25,
      message: "paused for combat",
      phase: "paused",
      completedCount: 4,
      targetCount: 16,
      retainedCount: 4,
    } satisfies BridgeMessage));
    socket.send(JSON.stringify({
      type: "task-progress",
      companionId: "codex-forge",
      taskId: combat.id,
      progress: 0.5,
      message: "attacking",
      phase: "active",
    } satisfies BridgeMessage));
    await waitFor(() => service.getTask(gather.id).status === "paused");
    expect(service.getTask(gather.id)).toMatchObject({
      completedCount: 4,
      targetCount: 16,
      retainedCount: 4,
    });
    expect(service.getTask(combat.id).status).toBe("running");
    expect(service.getCompanion("codex-forge").activeTaskId).toBe(combat.id);

    socket.send(JSON.stringify({
      type: "task-result",
      companionId: "codex-forge",
      taskId: combat.id,
      ok: true,
      message: "combat complete",
    } satisfies BridgeMessage));
    socket.send(JSON.stringify({
      type: "task-progress",
      companionId: "codex-forge",
      taskId: gather.id,
      progress: 0.25,
      message: "gather resumed",
      phase: "active",
    } satisfies BridgeMessage));
    await waitFor(() => service.getTask(combat.id).status === "succeeded");
    await waitFor(() => service.getTask(gather.id).status === "running");
    expect(service.getCompanion("codex-forge").activeTaskId).toBe(gather.id);

    socket.send(JSON.stringify({
      type: "task-result",
      companionId: "codex-forge",
      taskId: gather.id,
      ok: true,
      message: "gather complete",
      completedCount: 16,
      targetCount: 16,
      retainedCount: 16,
    } satisfies BridgeMessage));
    await waitFor(() => service.getTask(gather.id).status === "succeeded");
    expect(service.getTask(gather.id)).toMatchObject({
      progress: 1,
      completedCount: 16,
      targetCount: 16,
      retainedCount: 16,
    });
  });

  it("keeps an in-flight NPC task attached across a bridge reconnect", async () => {
    const service = new ControlService();
    const manager = new BridgeManager({ service, token: TOKEN });
    const first = await connect(manager);
    first.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);

    const commandPromise = nextJson(first);
    const task = service.assignTask("codex-forge", {
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 16,
      requestedBy: "test",
    }, "test");
    await commandPromise;

    const closed = once(first, "close");
    first.close();
    await closed;
    await waitFor(() => !service.getCompanion("codex-forge").connected);
    expect(service.getTask(task.id).status).toBe("running");

    const second = await connect(manager);
    second.send(JSON.stringify(hello()));
    await waitFor(() => service.getCompanion("codex-forge").connected);
    expect(service.getTask(task.id).status).toBe("running");

    second.send(JSON.stringify({
      type: "task-result",
      companionId: "codex-forge",
      taskId: task.id,
      ok: true,
      message: "断线期间完成，重连后补发",
    } satisfies BridgeMessage));
    await waitFor(() => service.getTask(task.id).status === "succeeded");
    expect(service.getTask(task.id).message).toBe("断线期间完成，重连后补发");
  });

  it("delivers an in-flight cancellation after the bridge reconnects", async () => {
    const service = new ControlService();
    const manager = new BridgeManager({ service, token: TOKEN });
    const first = await connect(manager);
    first.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);

    const runPromise = nextJson(first);
    const task = service.assignTask("codex-forge", {
      kind: "gather",
      itemId: "#minecraft:logs",
      count: 16,
      requestedBy: "test",
    }, "test");
    await runPromise;

    const closed = once(first, "close");
    first.close();
    await closed;
    await waitFor(() => !service.getCompanion("codex-forge").connected);
    service.cancelTask(task.id, "玩家要求停止采集");
    expect(service.getTask(task.id).status).toBe("cancelled");

    const second = await connect(manager);
    const cancelPromise = nextJson(second);
    second.send(JSON.stringify(hello()));
    const command = await cancelPromise;
    expect(command).toMatchObject({
      type: "cancel-task",
      taskId: task.id,
      reason: "玩家要求停止采集",
    });
  });

  it("resumes a multi-step Forge task after a full control-service restart", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-bridge-restart-"));
    try {
      const firstService = new ControlService({ stateDirectory });
      const first = await connect(new BridgeManager({ service: firstService, token: TOKEN }));
      first.send(JSON.stringify(hello()));
      await waitFor(() => firstService.listCompanions().length === 1);

      const gatherCommandPromise = nextJson(first);
      const task = firstService.assignTask("codex-forge", {
        kind: "macro",
        skillId: "life.gather-and-deliver",
        arguments: { itemId: "#minecraft:logs", count: 8, player: "PlayerOne" },
        requestedBy: "test",
      }, "antigravity-autoplay");
      const gatherCommand = await gatherCommandPromise;
      expect((gatherCommand.task as { id: string }).id).toBe(task.id);
      expect((gatherCommand.task as { spec: { kind: string } }).spec.kind).toBe("gather");

      const closed = once(first, "close");
      first.close();
      await closed;

      const secondService = new ControlService({ stateDirectory });
      const second = await connect(new BridgeManager({ service: secondService, token: TOKEN }));
      const reconnectHello = hello();
      reconnectHello.companion.snapshot.taskQueue = [{
        id: task.id,
        kind: "gather",
        phase: "active",
        priority: 50,
        progress: 0.5,
      }];
      second.send(JSON.stringify(reconnectHello));
      await waitFor(() => secondService.listCompanions().length === 1);

      const deliverCommandPromise = nextJson(second);
      second.send(JSON.stringify({
        type: "task-result",
        companionId: "codex-forge",
        taskId: task.id,
        ok: true,
        message: "已采集 8 个原木",
      } satisfies BridgeMessage));
      const deliverCommand = await deliverCommandPromise;
      expect((deliverCommand.task as { id: string }).id).toBe(task.id);
      expect((deliverCommand.task as { spec: { kind: string } }).spec.kind).toBe("deliver");

      const terminalChatPromise = nextJson(second);
      second.send(JSON.stringify({
        type: "task-result",
        companionId: "codex-forge",
        taskId: task.id,
        ok: true,
        message: "已把 8 个原木丢给 PlayerOne",
      } satisfies BridgeMessage));
      const terminalChat = await terminalChatPromise;
      await waitFor(() => secondService.getTask(task.id).status === "succeeded");
      expect(terminalChat).toMatchObject({ type: "chat" });
      expect(String(terminalChat.message)).toMatch(/^任务完成：/u);
    } finally {
      await rm(stateDirectory, { recursive: true, force: true });
    }
  });

  it("closes a client that sends another companion's identity", async () => {
    const service = new ControlService();
    const onChat = vi.fn();
    const socket = await connect(new BridgeManager({ service, token: TOKEN, onChat }));
    socket.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);
    const closed = once(socket, "close");

    socket.send(JSON.stringify({
      type: "chat",
      companionId: "different-companion",
      sender: "PlayerOne",
      message: "spoofed",
      at: new Date().toISOString(),
    } satisfies BridgeMessage));

    const [code] = await closed;
    expect(code).toBe(1008);
    expect(onChat).not.toHaveBeenCalled();
  });

  it("records unhandled target-player chat in the Antigravity inbox", async () => {
    const service = new ControlService();
    await service.updateChatSettings({
      freeChatEnabled: true,
      playerName: "PlayerOne",
      target: "antigravity-mcp",
      persona: {
        mode: "inherit",
        displayName: "",
        personality: "",
        speakingStyle: "",
        memoryNotes: "",
      },
    });
    const onChat = vi.fn().mockResolvedValue(false);
    const socket = await connect(new BridgeManager({ service, token: TOKEN, onChat }));
    socket.send(JSON.stringify(hello()));
    await waitFor(() => service.listCompanions().length === 1);
    const at = "2026-07-31T08:30:00.000Z";

    socket.send(JSON.stringify({
      type: "chat",
      companionId: "codex-forge",
      sender: "PlayerOne",
      message: "反重力，陪我聊聊天",
      at,
    } satisfies BridgeMessage));

    await waitFor(() => service.listChatMessages().length === 1);
    expect(onChat).toHaveBeenCalledOnce();
    expect(service.listChatMessages()).toEqual([{
      sequence: 1,
      at,
      companionId: "codex-forge",
      sender: "PlayerOne",
      message: "反重力，陪我聊聊天",
    }]);
  });
});

describe("ChatRelayGuard", () => {
  it("drops companion echoes and duplicate lines within the relay window", () => {
    let now = 10_000;
    const guard = new ChatRelayGuard({ ttlMs: 1_000, now: () => now });
    expect(guard.shouldForward("PlayerOne", "@codex follow me", ["Codex", "Worker-1"])).toBe(true);
    expect(guard.shouldForward("PlayerOne", "  @codex   follow me ", ["Codex", "Worker-1"])).toBe(false);
    expect(guard.shouldForward("Codex", "I am following", ["Codex", "Worker-1"])).toBe(false);
    now += 1_001;
    expect(guard.shouldForward("PlayerOne", "@codex follow me", ["Codex", "Worker-1"])).toBe(true);
  });
});
