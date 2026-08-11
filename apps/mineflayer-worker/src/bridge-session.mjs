import { PROTOCOL_VERSION } from "@mc/protocol";
import WebSocket from "ws";
import { createCompanion, createSnapshot } from "./snapshot.mjs";

function bridgeUrl(controlUrl) {
  const url = new URL(controlUrl);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = `${url.pathname.replace(/\/$/, "")}/bridge`;
  url.search = "";
  return url.toString();
}

export class BridgeSession {
  #bot;
  #botConfig;
  #config;
  #runner;
  #socket = null;
  #sequence = 0;
  #active = null;
  #snapshotTimer = null;
  #heartbeatTimer = null;
  #reconnectTimer = null;
  #closed = false;

  constructor(bot, botConfig, config, runner) {
    this.#bot = bot;
    this.#botConfig = botConfig;
    this.#config = config;
    this.#runner = runner;
  }

  start() {
    this.#bot.on("chat", (sender, message) => {
      if (!this.#botConfig.chatLeader || sender === this.#bot.username || !message.trim()) return;
      this.#send({
        type: "chat",
        companionId: this.#botConfig.id,
        sender,
        message: message.slice(0, 256),
        at: new Date().toISOString(),
      });
    });
    this.#connect();
  }

  close() {
    this.#closed = true;
    this.#active?.controller.abort(new Error("Bridge session closed"));
    this.#runner.stopAll();
    if (this.#snapshotTimer) clearInterval(this.#snapshotTimer);
    if (this.#heartbeatTimer) clearInterval(this.#heartbeatTimer);
    if (this.#reconnectTimer) clearTimeout(this.#reconnectTimer);
    this.#socket?.close();
  }

  #connect() {
    if (this.#closed) return;
    const socket = new WebSocket(bridgeUrl(this.#config.controlUrl));
    this.#socket = socket;
    socket.on("open", () => {
      const snapshot = this.#snapshot();
      this.#send({
        type: "hello",
        protocolVersion: PROTOCOL_VERSION,
        token: this.#config.bridgeToken,
        companion: createCompanion(this.#bot, this.#botConfig, this.#config, snapshot),
      });
      this.#send({ type: "snapshot", companionId: this.#botConfig.id, snapshot });
      this.#snapshotTimer = setInterval(() => {
        this.#send({ type: "snapshot", companionId: this.#botConfig.id, snapshot: this.#snapshot() });
      }, 2_000);
      this.#heartbeatTimer = setInterval(() => {
        this.#send({ type: "heartbeat", companionId: this.#botConfig.id, at: new Date().toISOString() });
      }, 10_000);
    });
    socket.on("message", (data) => {
      try {
        const command = JSON.parse(data.toString());
        void this.#handle(command);
      } catch (error) {
        console.error(`[${this.#botConfig.id}] invalid bridge command`, error instanceof Error ? error.message : error);
      }
    });
    socket.on("error", (error) => console.error(`[${this.#botConfig.id}] bridge error: ${error.message}`));
    socket.on("close", () => {
      if (this.#snapshotTimer) clearInterval(this.#snapshotTimer);
      if (this.#heartbeatTimer) clearInterval(this.#heartbeatTimer);
      this.#snapshotTimer = null;
      this.#heartbeatTimer = null;
      this.#active?.controller.abort(new Error("Control bridge disconnected"));
      if (!this.#closed) this.#reconnectTimer = setTimeout(() => this.#connect(), this.#config.reconnectDelayMs);
    });
  }

  async #handle(command) {
    if (!command || typeof command !== "object") return;
    if (command.type === "chat" && typeof command.message === "string") {
      this.#bot.chat(command.message.slice(0, 256));
      return;
    }
    if (command.type === "cancel-task") {
      if (this.#active?.taskId === command.taskId) {
        this.#active.controller.abort(new Error(command.reason || "Task cancelled"));
        this.#runner.stopForeground();
      }
      return;
    }
    if (command.type === "emergency-stop") {
      this.#active?.controller.abort(new Error("Emergency stop"));
      this.#runner.stopAll();
      if (command.disconnect) this.#bot.quit("Minecraft Codex Companion emergency stop");
      return;
    }
    if (command.type !== "run-task" || !command.task?.id) return;
    if (this.#active) {
      this.#send({
        type: "task-result",
        companionId: this.#botConfig.id,
        taskId: command.task.id,
        ok: false,
        message: "Mineflayer worker already has an active task",
        code: "WORKER_BUSY",
      });
      return;
    }
    const controller = new AbortController();
    this.#active = { taskId: command.task.id, controller };
    try {
      const message = await this.#runner.run(command.task, command.buildPlan, {
        onProgress: (progress, progressMessage) => this.#send({
          type: "task-progress",
          companionId: this.#botConfig.id,
          taskId: command.task.id,
          progress,
          message: progressMessage,
        }),
      }, controller.signal);
      this.#send({
        type: "task-result",
        companionId: this.#botConfig.id,
        taskId: command.task.id,
        ok: true,
        message,
      });
    } catch (error) {
      const cancelled = controller.signal.aborted;
      this.#send({
        type: "task-result",
        companionId: this.#botConfig.id,
        taskId: command.task.id,
        ok: false,
        message: error instanceof Error ? error.message : String(error),
        code: cancelled ? "CANCELLED" : "MINEFLAYER_TASK_FAILED",
      });
    } finally {
      if (this.#active?.taskId === command.task.id) this.#active = null;
    }
  }

  #snapshot() {
    this.#sequence += 1;
    return createSnapshot(this.#bot, this.#config, this.#runner.status, this.#sequence);
  }

  #send(message) {
    if (this.#socket?.readyState === WebSocket.OPEN) this.#socket.send(JSON.stringify(message));
  }
}
