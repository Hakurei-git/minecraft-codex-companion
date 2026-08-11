import { timingSafeEqual } from "node:crypto";
import type { BridgeMessage } from "@mc/protocol";
import WebSocket from "ws";
import { WebSocketBridgeBackend } from "./bridge-backend.js";
import type { ControlService } from "./control-service.js";

export interface BridgeManagerOptions {
  service: ControlService;
  token: string;
  onChat?(message: Extract<BridgeMessage, { type: "chat" }>): boolean | void | Promise<boolean | void>;
  onDiagnostic?(message: string): void;
}

export interface ChatRelayGuardOptions {
  ttlMs?: number;
  now?: () => number;
}

/** Prevents one server chat line from becoming several AI turns. */
export class ChatRelayGuard {
  readonly #ttlMs: number;
  readonly #now: () => number;
  readonly #recent = new Map<string, number>();

  constructor(options: ChatRelayGuardOptions = {}) {
    this.#ttlMs = options.ttlMs ?? 1_500;
    this.#now = options.now ?? Date.now;
  }

  shouldForward(sender: string, message: string, companionNames: Iterable<string>): boolean {
    const cleanSender = normalizeChat(sender);
    const cleanMessage = normalizeChat(message);
    if (!cleanSender || !cleanMessage) return false;
    for (const name of companionNames) {
      if (cleanSender === normalizeChat(name)) return false;
    }
    const now = this.#now();
    for (const [key, at] of this.#recent) {
      if (now - at >= this.#ttlMs) this.#recent.delete(key);
    }
    const key = `${cleanSender}\u0000${cleanMessage}`;
    const previous = this.#recent.get(key);
    this.#recent.set(key, now);
    return previous === undefined || now - previous >= this.#ttlMs;
  }
}

function normalizeChat(value: string): string {
  return String(value).trim().replace(/\s+/gu, " ").toLocaleLowerCase("zh-CN");
}

function tokensMatch(actual: string, expected: string): boolean {
  const actualBytes = Buffer.from(actual, "utf8");
  const expectedBytes = Buffer.from(expected, "utf8");
  return actualBytes.length === expectedBytes.length && timingSafeEqual(actualBytes, expectedBytes);
}

export class BridgeManager {
  readonly #service: ControlService;
  readonly #token: string;
  readonly #onChat: BridgeManagerOptions["onChat"];
  readonly #onDiagnostic: BridgeManagerOptions["onDiagnostic"];
  readonly #chatGuard = new ChatRelayGuard();
  readonly #companionNames = new Map<string, string>();
  readonly #backends = new Map<string, WebSocketBridgeBackend>();

  constructor(options: BridgeManagerOptions) {
    this.#service = options.service;
    this.#token = options.token;
    this.#onChat = options.onChat;
    this.#onDiagnostic = options.onDiagnostic;
  }

  attach(socket: WebSocket): void {
    let backend: WebSocketBridgeBackend | null = null;
    let authenticated = false;

    const reject = (code: number, reason: string) => {
      this.#onDiagnostic?.(`Bridge connection rejected before registration: ${reason}`);
      if (socket.readyState === WebSocket.OPEN) socket.close(code, reason);
    };

    socket.on("message", (raw) => {
      try {
        const message = WebSocketBridgeBackend.parseMessage(raw);
        if (!authenticated) {
          if (message.type !== "hello") {
            reject(1008, "hello required");
            return;
          }
          if (!tokensMatch(message.token, this.#token)) {
            reject(1008, "invalid token");
            return;
          }
          const existing = this.#backends.get(message.companion.id);
          if (existing) {
            existing.reconnect(socket, message);
            backend = existing;
          } else {
            backend = new WebSocketBridgeBackend(socket, message, (id) => this.#service.getBuildPlan(id));
            this.#backends.set(backend.id, backend);
          }
          authenticated = true;
          this.#companionNames.set(backend.id, message.companion.name);
          this.#service.registerBackend(backend);
          return;
        }
        if (!backend || message.type === "hello") return;
        if (!backend.isCurrentConnection(socket)) return;
        if (message.companionId !== backend.id) {
          reject(1008, "companion id mismatch");
          return;
        }
        backend.handle(message);
        if (message.type === "chat") {
          const forwarded = this.#chatGuard.shouldForward(
            message.sender,
            message.message,
            this.#companionNames.values(),
          );
          this.#service.events.publish({
            type: "chat",
            companionId: message.companionId,
            message: `${message.sender}: ${message.message}`,
            data: { sender: message.sender, direction: "incoming", forwarded },
          });
          if (forwarded) void this.#routeChat(message);
        }
      } catch (caught) {
        this.#onDiagnostic?.(`Invalid bridge message: ${validationSummary(caught)}`);
        reject(1003, "invalid bridge message");
      }
    });

    socket.on("close", () => {
      if (!backend) this.#onDiagnostic?.("Bridge connection closed before registration");
      const disconnected = backend?.disconnect(socket) === true;
      if (backend && disconnected) {
        this.#service.events.publish({
          type: "connection",
          companionId: backend.id,
          message: `${backend.describe().name} 的客户端桥接已断开`,
        });
      }
    });
    socket.on("error", () => backend?.disconnect(socket, "Minecraft 客户端桥接发生网络错误"));
  }

  async #routeChat(message: Extract<BridgeMessage, { type: "chat" }>): Promise<void> {
    try {
      const handled = await this.#onChat?.(message);
      if (handled !== true) await this.#service.recordIncomingChat(message);
    } catch (caught) {
      this.#service.events.publish({
        type: "warning",
        companionId: message.companionId,
        message: `游戏聊天路由失败：${caught instanceof Error ? caught.message : String(caught)}`,
      });
    }
  }
}

function validationSummary(caught: unknown): string {
  if (caught && typeof caught === "object" && "issues" in caught) {
    const issues = (caught as { issues?: Array<{ path?: PropertyKey[]; message?: string }> }).issues;
    if (Array.isArray(issues) && issues.length > 0) {
      return issues
        .slice(0, 8)
        .map((issue) => `${issue.path?.map(String).join(".") || "message"}: ${issue.message ?? "invalid value"}`)
        .join("; ");
    }
  }
  return caught instanceof Error ? caught.name : "unknown validation error";
}
