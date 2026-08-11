import { EventEmitter } from "node:events";
import { randomUUID } from "node:crypto";
import type { CompanionEvent } from "@mc/protocol";
import { redactSensitiveData, redactSensitiveText } from "./skill-security.js";

export class CompanionEventBus {
  readonly #emitter = new EventEmitter();
  readonly #events: CompanionEvent[] = [];
  readonly #limit: number;

  constructor(limit = 500) {
    this.#limit = limit;
  }

  publish(event: Omit<CompanionEvent, "id" | "at"> & { at?: string }): CompanionEvent {
    const complete: CompanionEvent = {
      ...event,
      message: redactSensitiveText(event.message),
      ...(event.data ? { data: redactSensitiveData(event.data) as Record<string, unknown> } : {}),
      id: randomUUID(),
      at: event.at ?? new Date().toISOString(),
    };
    this.#events.push(complete);
    if (this.#events.length > this.#limit) {
      this.#events.splice(0, this.#events.length - this.#limit);
    }
    this.#emitter.emit("event", complete);
    return complete;
  }

  recent(limit = 100): CompanionEvent[] {
    return this.#events.slice(-Math.max(0, Math.min(limit, this.#limit)));
  }

  subscribe(listener: (event: CompanionEvent) => void): () => void {
    this.#emitter.on("event", listener);
    return () => this.#emitter.off("event", listener);
  }
}
