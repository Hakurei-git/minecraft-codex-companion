import type {
  BridgeCommand,
  Capability,
  CompanionAction,
  Companion,
  TaskRecord,
  TaskProgressDetails,
  WorldSnapshot,
} from "@mc/protocol";

export interface TaskCallbacks {
  onProgress(
    progress: number,
    message: string,
    phase?: "active" | "paused",
    details?: TaskProgressDetails,
  ): void;
}

export class BackendTaskFailure extends Error {
  readonly code: string;
  readonly retryable: boolean;

  constructor(code: string, message: string, retryable = true) {
    super(message);
    this.name = "BackendTaskFailure";
    this.code = /^[A-Z][A-Z0-9_]{0,63}$/u.test(code) ? code : "BACKEND_FAILURE";
    this.retryable = retryable;
  }
}

export interface CompanionBackend {
  readonly id: string;
  readonly supportsConcurrentTasks?: boolean;
  describe(): Companion;
  snapshot(): WorldSnapshot;
  capabilities(): readonly Capability[];
  runTask(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string>;
  resumeTask?(task: TaskRecord, callbacks: TaskCallbacks, signal: AbortSignal): Promise<string>;
  sendChat(message: string): Promise<void>;
  stop(disconnect: boolean): Promise<void>;
  control?(action: CompanionAction): Promise<void>;
  sendBridgeCommand?(command: BridgeCommand): Promise<void>;
}
