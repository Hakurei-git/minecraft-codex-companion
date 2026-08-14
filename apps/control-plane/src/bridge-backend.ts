import type {
  BridgeCommand,
  BridgeMessage,
  Capability,
  Companion,
  CompanionAction,
  TaskRecord,
  TaskProgressDetails,
  WorldSnapshot,
  BuildPlan,
} from "@mc/protocol";
import { bridgeMessageSchema } from "@mc/protocol";
import { randomUUID } from "node:crypto";
import WebSocket from "ws";
import { BackendTaskFailure, type CompanionBackend, type TaskCallbacks } from "./backend.js";
import { buildContentHash } from "./build-plan-store.js";

type BridgeHello = Extract<BridgeMessage, { type: "hello" }>;
type TaskProgress = Extract<BridgeMessage, { type: "task-progress" }>;
type TaskResult = Extract<BridgeMessage, { type: "task-result" }>;

function observedProgressDetails(message: TaskProgress | TaskResult): TaskProgressDetails | undefined {
  const details: TaskProgressDetails = {
    currentStepIndex: message.currentStepIndex,
    currentStepKind: message.currentStepKind,
    stepProgress: message.stepProgress,
    completedCount: message.completedCount,
    targetCount: message.targetCount,
    retainedCount: message.retainedCount,
  };
  return Object.values(details).some((value) => value !== undefined) ? details : undefined;
}

interface PendingTask {
  callbacks: TaskCallbacks;
  recoveryCommand?: BridgeCommand;
  resolve(message: string): void;
  reject(error: Error): void;
  cleanup(): void;
}

interface PendingControl {
  action: CompanionAction;
  baselineSequence: number;
  resolve(): void;
  reject(error: Error): void;
  timer: NodeJS.Timeout;
}

interface PendingChatDelivery {
  resolve(): void;
  reject(error: Error): void;
  timer: NodeJS.Timeout;
}

const MAX_DEFERRED_SAFETY_COMMANDS = 64;
const STALE_CONNECTION_MS = 30_000;
const CONTROL_ACK_TIMEOUT_MS = 3_000;
const CHAT_DELIVERY_ACK_TIMEOUT_MS = 8_000;
const RECALL_ACK_DISTANCE = 8;

export function positionBuildPlanForTask(
  plan: BuildPlan,
  spec: Extract<TaskRecord["spec"], { kind: "build" }>,
  snapshot: WorldSnapshot,
): BuildPlan {
  if (spec.placement !== "companion") return plan;
  const offset = spec.offset ?? { x: 0, y: 0, z: 0 };
  const anchor = spec.placementAnchor ?? snapshot.position;
  const positioned = {
    ...plan,
    origin: {
      x: Math.floor(anchor.x) + offset.x,
      // Snapshot positions are entity feet coordinates. Forge snaps companion
      // placement to the target X/Z surface; this remains the transport anchor.
      y: Math.floor(anchor.y) + offset.y,
      z: Math.floor(anchor.z) + offset.z,
    },
  };
  return {
    ...positioned,
    manifest: {
      ...positioned.manifest,
      sha256: buildContentHash({
        name: positioned.name,
        source: positioned.source,
        origin: positioned.origin,
        blocks: positioned.blocks,
      }),
    },
  };
}

function controlAcknowledged(action: CompanionAction, snapshot: WorldSnapshot): boolean {
  switch (action) {
    case "recall":
    case "summon":
      return typeof snapshot.ownerDistance === "number"
        && Number.isFinite(snapshot.ownerDistance)
        && snapshot.ownerDistance <= RECALL_ACK_DISTANCE;
    case "follow":
      return snapshot.stance === "follow";
    case "stay":
      return snapshot.stance === "stay";
  }
}

export class WebSocketBridgeBackend implements CompanionBackend {
  readonly id: string;
  readonly supportsConcurrentTasks = true;
  #socket: WebSocket;
  #base: BridgeHello["companion"];
  readonly #resolveBuildPlan: ((id: string) => import("@mc/protocol").BuildPlan) | undefined;
  readonly #pendingTasks = new Map<string, PendingTask>();
  readonly #pendingControls = new Set<PendingControl>();
  readonly #pendingChatDeliveries = new Map<string, PendingChatDelivery>();
  readonly #deferredSafetyCommands = new Map<string, BridgeCommand>();
  #snapshot: WorldSnapshot;
  #connected = true;
  #lastSeenAt = Date.now();

  constructor(
    socket: WebSocket,
    hello: BridgeHello,
    resolveBuildPlan?: (id: string) => import("@mc/protocol").BuildPlan,
  ) {
    this.#socket = socket;
    this.#base = hello.companion;
    this.id = hello.companion.id;
    this.#snapshot = hello.companion.snapshot;
    this.#resolveBuildPlan = resolveBuildPlan;
  }

  describe(): Companion {
    return {
      ...this.#base,
      connected: this.#live(),
      leaseOwner: null,
      activeTaskId: null,
      snapshot: this.#snapshot,
    };
  }

  snapshot(): WorldSnapshot {
    return this.#snapshot;
  }

  capabilities(): readonly Capability[] {
    return this.#base.capabilities;
  }

  reconnect(socket: WebSocket, hello: BridgeHello): void {
    if (hello.companion.id !== this.id) throw new Error("Bridge companion identity changed during reconnect");
    const previous = this.#socket;
    this.#rejectPendingControls(new Error("Minecraft bridge reconnected before NPC control acknowledgement"));
    this.#rejectPendingChatDeliveries(new Error("Minecraft bridge reconnected before chat delivery acknowledgement"));
    this.#socket = socket;
    this.#base = hello.companion;
    this.#snapshot = hello.companion.snapshot;
    this.#connected = true;
    this.#lastSeenAt = Date.now();
    if (previous !== socket && previous.readyState === WebSocket.OPEN) {
      previous.close(1000, "replaced by reconnect");
    }
    if (this.#snapshot.taskQueue) {
      setTimeout(() => {
        if (this.#socket !== socket || !this.#connected) return;
        const activeTaskIds = new Set(this.#snapshot.taskQueue?.map((entry) => entry.id));
        for (const [taskId, pending] of this.#pendingTasks) {
          if (activeTaskIds.has(taskId) || !pending.recoveryCommand) continue;
          void this.sendBridgeCommand(pending.recoveryCommand).catch((error) => {
            const current = this.#pendingTasks.get(taskId);
            if (current !== pending) return;
            this.#pendingTasks.delete(taskId);
            pending.reject(error instanceof Error ? error : new Error(String(error)));
          });
        }
      }, 250);
    }
    void this.#flushDeferredSafetyCommands();
  }

  isCurrentConnection(socket: WebSocket): boolean {
    return this.#socket === socket && this.#connected;
  }

  async runTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string> {
    if (!this.#live()) throw new Error("Minecraft 客户端桥接已断开或心跳超时");
    const command = this.#taskCommand(task);
    return this.#trackTask(task, callbacks, signal, command);
  }

  #taskCommand(task: TaskRecord): Extract<BridgeCommand, { type: "run-task" }> {
    const unresolvedBuildPlan = task.spec.kind === "build" ? this.#resolveBuildPlan?.(task.spec.planId) : undefined;
    const buildPlan = task.spec.kind === "build" && unresolvedBuildPlan
      ? positionBuildPlanForTask(unresolvedBuildPlan, task.spec, this.#snapshot)
      : undefined;
    return {
      type: "run-task",
      task,
      ...(buildPlan ? { buildPlan } : {}),
    };
  }

  async resumeTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string> {
    if (!this.#live()) throw new Error("Minecraft 客户端桥接已断开或心跳超时");
    if (this.#snapshot.taskQueue && !this.#snapshot.taskQueue.some((entry) => entry.id === task.id)) {
      return this.#trackTask(task, callbacks, signal, this.#taskCommand(task));
    }
    return this.#trackTask(task, callbacks, signal, undefined, true);
  }

  #trackTask(
    task: TaskRecord,
    callbacks: TaskCallbacks,
    signal: AbortSignal,
    command?: BridgeCommand,
    verifyExisting = false,
  ): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      if (this.#pendingTasks.has(task.id)) {
        reject(new Error(`Task ${task.id} is already being tracked`));
        return;
      }
      let verificationTimer: NodeJS.Timeout | undefined;
      const abort = () => {
        this.#pendingTasks.delete(task.id);
        cleanup();
        this.#sendOrDeferSafetyCommand(`cancel:${task.id}`, {
          type: "cancel-task",
          taskId: task.id,
          reason: signal.reason instanceof Error ? signal.reason.message : "任务已取消",
        });
        reject(signal.reason instanceof Error ? signal.reason : new Error("任务已取消"));
      };
      const cleanup = () => {
        signal.removeEventListener("abort", abort);
        if (verificationTimer) clearTimeout(verificationTimer);
      };
      this.#pendingTasks.set(task.id, {
        callbacks,
        ...(command?.type === "run-task" ? { recoveryCommand: command } : {}),
        resolve: (message) => {
          cleanup();
          resolve(message);
        },
        reject: (error) => {
          cleanup();
          reject(error);
        },
        cleanup,
      });
      signal.addEventListener("abort", abort, { once: true });
      if (verifyExisting && this.#snapshot.taskQueue) {
        verificationTimer = setTimeout(() => {
          const pending = this.#pendingTasks.get(task.id);
          if (!pending || this.#snapshot.taskQueue?.some((entry) => entry.id === task.id)) return;
          this.#pendingTasks.delete(task.id);
          cleanup();
          reject(new Error("RECOVERED_TASK_NOT_ACTIVE"));
        }, 2_000);
      }
      if (!command) return;
      this.sendBridgeCommand(command).catch((error) => {
        this.#pendingTasks.delete(task.id);
        cleanup();
        reject(error instanceof Error ? error : new Error(String(error)));
      });
    });
  }

  async sendChat(message: string): Promise<void> {
    if (this.#base.backend !== "forge-1.20.1") {
      await this.sendBridgeCommand({ type: "chat", message });
      return;
    }
    const deliveryId = randomUUID();
    await new Promise<void>((resolve, reject) => {
      const pending: PendingChatDelivery = {
        resolve: () => {
          clearTimeout(pending.timer);
          this.#pendingChatDeliveries.delete(deliveryId);
          resolve();
        },
        reject: (error) => {
          clearTimeout(pending.timer);
          this.#pendingChatDeliveries.delete(deliveryId);
          reject(error);
        },
        timer: setTimeout(() => {
          pending.reject(new BackendTaskFailure(
            "CHAT_DELIVERY_ACK_TIMEOUT",
            "Minecraft accepted the bridge command but did not confirm that the reply appeared in game chat",
            true,
          ));
        }, CHAT_DELIVERY_ACK_TIMEOUT_MS),
      };
      this.#pendingChatDeliveries.set(deliveryId, pending);
      this.sendBridgeCommand({ type: "chat", message, deliveryId }).catch((error) => {
        pending.reject(error instanceof Error ? error : new Error(String(error)));
      });
    });
  }

  async stop(disconnect: boolean): Promise<void> {
    await this.sendBridgeCommand({ type: "emergency-stop", disconnect });
  }

  async control(action: CompanionAction): Promise<void> {
    const baselineSequence = this.#snapshot.sequence;
    await this.sendBridgeCommand({ type: "npc-control", action });
    if (this.#base.backend !== "forge-1.20.1") return;
    await new Promise<void>((resolve, reject) => {
      const pending: PendingControl = {
        action,
        baselineSequence,
        resolve: () => {
          clearTimeout(pending.timer);
          this.#pendingControls.delete(pending);
          resolve();
        },
        reject: (error) => {
          clearTimeout(pending.timer);
          this.#pendingControls.delete(pending);
          reject(error);
        },
        timer: setTimeout(() => {
          pending.reject(new BackendTaskFailure(
            "NPC_CONTROL_ACK_TIMEOUT",
            `NPC ${action} command was sent but Minecraft did not confirm the physical state`,
            true,
          ));
        }, CONTROL_ACK_TIMEOUT_MS),
      };
      this.#pendingControls.add(pending);
      this.#resolvePendingControls(this.#snapshot);
    });
  }

  async sendBridgeCommand(command: BridgeCommand): Promise<void> {
    if (!this.#live()) {
      throw new Error("Minecraft 客户端桥接不可用或心跳超时");
    }
    await new Promise<void>((resolve, reject) => {
      this.#socket.send(JSON.stringify(command), (error) => error ? reject(error) : resolve());
    });
  }

  handle(message: BridgeMessage): void {
    if (message.type === "hello") return;
    if (message.companionId !== this.id) return;
    this.#lastSeenAt = Date.now();
    switch (message.type) {
      case "snapshot":
        if (message.snapshot.sequence >= this.#snapshot.sequence) {
          this.#snapshot = message.snapshot;
          this.#resolvePendingControls(message.snapshot);
        }
        break;
      case "task-progress":
        this.#handleProgress(message);
        break;
      case "task-result":
        this.#handleResult(message);
        break;
      case "chat-delivered":
        this.#pendingChatDeliveries.get(message.deliveryId)?.resolve();
        break;
      case "heartbeat":
      case "chat":
        break;
    }
  }

  disconnect(source: WebSocket, reason = "Minecraft 客户端桥接已断开"): boolean {
    if (this.#socket !== source || !this.#connected) return false;
    this.#connected = false;
    this.#rejectPendingControls(new Error(reason));
    this.#rejectPendingChatDeliveries(new Error(reason));
    return true;
  }

  #resolvePendingControls(snapshot: WorldSnapshot): void {
    for (const pending of [...this.#pendingControls]) {
      if (snapshot.sequence <= pending.baselineSequence) continue;
      if (controlAcknowledged(pending.action, snapshot)) pending.resolve();
    }
  }

  #rejectPendingControls(error: Error): void {
    for (const pending of [...this.#pendingControls]) pending.reject(error);
  }

  #rejectPendingChatDeliveries(error: Error): void {
    for (const pending of [...this.#pendingChatDeliveries.values()]) pending.reject(error);
  }

  #live(): boolean {
    return this.#connected
      && this.#socket.readyState === WebSocket.OPEN
      && Date.now() - this.#lastSeenAt <= STALE_CONNECTION_MS;
  }

  static parseMessage(data: WebSocket.RawData): BridgeMessage {
    const text = typeof data === "string" ? data : data.toString("utf8");
    return bridgeMessageSchema.parse(JSON.parse(text));
  }

  #handleProgress(message: TaskProgress): void {
    this.#pendingTasks.get(message.taskId)?.callbacks.onProgress(
      message.progress,
      message.message,
      message.phase,
      observedProgressDetails(message),
    );
  }

  #handleResult(message: TaskResult): void {
    const pending = this.#pendingTasks.get(message.taskId);
    if (!pending) return;
    const details = observedProgressDetails(message);
    if (message.ok && details) {
      pending.callbacks.onProgress(1, message.message, "active", details);
    }
    this.#pendingTasks.delete(message.taskId);
    if (message.ok) pending.resolve(message.message);
    else pending.reject(message.code
      ? new BackendTaskFailure(message.code, message.message)
      : new Error(message.message));
  }

  #sendOrDeferSafetyCommand(key: string, command: BridgeCommand): void {
    if (!this.#connected || this.#socket.readyState !== WebSocket.OPEN) {
      this.#rememberSafetyCommand(key, command);
      return;
    }
    this.sendBridgeCommand(command).catch(() => this.#rememberSafetyCommand(key, command));
  }

  #rememberSafetyCommand(key: string, command: BridgeCommand): void {
    this.#deferredSafetyCommands.delete(key);
    this.#deferredSafetyCommands.set(key, command);
    while (this.#deferredSafetyCommands.size > MAX_DEFERRED_SAFETY_COMMANDS) {
      const oldest = this.#deferredSafetyCommands.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      this.#deferredSafetyCommands.delete(oldest);
    }
  }

  async #flushDeferredSafetyCommands(): Promise<void> {
    for (const [key, command] of [...this.#deferredSafetyCommands]) {
      if (!this.#connected || this.#socket.readyState !== WebSocket.OPEN) return;
      try {
        await this.sendBridgeCommand(command);
        if (this.#deferredSafetyCommands.get(key) === command) this.#deferredSafetyCommands.delete(key);
      } catch {
        return;
      }
    }
  }
}
