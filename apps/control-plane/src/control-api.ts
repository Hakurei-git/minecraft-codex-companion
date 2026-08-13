import type {
  AiTaskDecision,
  AiTaskDecisionResult,
  BuildPlan,
  BuildPlanDraft,
  BuildImportRequest,
  ChatMessage,
  ChatSettings,
  ChatSettingsDraft,
  Companion,
  CompanionAction,
  DeclarativeSkill,
  DeclarativeSkillDraft,
  TaskRecord,
  TaskSpec,
  WorldSnapshot,
} from "@mc/protocol";

/**
 * Local delivery metadata used to correlate player-facing replies with one
 * inbound chat turn. It intentionally stays outside @mc/protocol because it
 * is control-plane bookkeeping, not part of the Minecraft bridge wire format.
 */
export type ChatReplyPhase = "start" | "progress" | "terminal" | "chat";

export interface ChatDeliveryOptions {
  /** Stable identifier for one inbound player message / driver request. */
  interactionId?: string;
  /** Only duplicate `start` replies are coalesced; later phases are preserved. */
  phase?: ChatReplyPhase;
}

export interface AiDecisionMutationOptions {
  /** Internal proof that this mutation belongs to the validated one-shot AI decision. */
  aiDecisionInteractionId?: string;
}

export interface MinecraftControlApi {
  listCompanions(): Companion[] | Promise<Companion[]>;
  getCompanion(id: string): Companion | Promise<Companion>;
  getSnapshot(id: string): WorldSnapshot | Promise<WorldSnapshot>;
  getChatSettings(companionId?: string): ChatSettings | Promise<ChatSettings>;
  updateChatSettings(input: ChatSettingsDraft): ChatSettings | Promise<ChatSettings>;
  submitAiDecision(interactionId: string, decision: AiTaskDecision): AiTaskDecisionResult | Promise<AiTaskDecisionResult>;
  listChatMessages(afterSequence?: number, limit?: number): ChatMessage[] | Promise<ChatMessage[]>;
  listTasks(): TaskRecord[] | Promise<TaskRecord[]>;
  getTask(id: string): TaskRecord | Promise<TaskRecord>;
  assignTask(
    companionId: string,
    spec: TaskSpec,
    owner?: string,
    options?: { replaceConflictingDelivery?: boolean } & AiDecisionMutationOptions,
  ): TaskRecord | Promise<TaskRecord>;
  retryLatestBuildTask(
    companionId: string,
    owner?: string,
    requestedBy?: string,
    options?: AiDecisionMutationOptions,
  ): TaskRecord | Promise<TaskRecord>;
  retryTask(
    taskId: string,
    owner?: string,
    options?: AiDecisionMutationOptions,
  ): TaskRecord | Promise<TaskRecord>;
  cancelTask(taskId: string, reason?: string): TaskRecord | Promise<TaskRecord>;
  sendChat(
    companionId: string,
    message: string,
    owner?: string,
    options?: ChatDeliveryOptions,
  ): void | Promise<void>;
  controlCompanion(
    companionId: string,
    action: CompanionAction,
    options?: AiDecisionMutationOptions,
  ): Companion | Promise<Companion>;
  listBuildPlans(): BuildPlan[] | Promise<BuildPlan[]>;
  previewBuild(draft: BuildPlanDraft): BuildPlan | Promise<BuildPlan>;
  importBuild(request: BuildImportRequest): BuildPlan | Promise<BuildPlan>;
  confirmBuild(id: string): BuildPlan | Promise<BuildPlan>;
  listSkills(): DeclarativeSkill[] | Promise<DeclarativeSkill[]>;
  getSkill(id: string): DeclarativeSkill | Promise<DeclarativeSkill>;
  saveSkill(draft: DeclarativeSkillDraft): DeclarativeSkill | Promise<DeclarativeSkill>;
  removeSkill(id: string): void | Promise<void>;
  acquireLease(companionId: string, owner: string, force?: boolean): Companion | Promise<Companion>;
  releaseLease(companionId: string, owner: string): Companion | Promise<Companion>;
  emergencyStop(disconnect?: boolean): void | Promise<void>;
}

interface ErrorEnvelope {
  error?: {
    code?: string;
    message?: string;
    retryable?: boolean;
    suggestedRecovery?: string;
  };
}

export class RemoteControlError extends Error {
  readonly code: string;
  readonly retryable: boolean;
  readonly suggestedRecovery: string | undefined;

  constructor(body: ErrorEnvelope, status: number) {
    const details = body.error;
    super(details?.message ?? `控制服务请求失败 (${status})`);
    this.name = "RemoteControlError";
    this.code = details?.code ?? "REMOTE_CONTROL_ERROR";
    this.retryable = details?.retryable ?? status >= 500;
    this.suggestedRecovery = details?.suggestedRecovery;
  }
}

export class HttpControlClient implements MinecraftControlApi {
  readonly #baseUrl: string;

  constructor(baseUrl = "http://127.0.0.1:8765") {
    this.#baseUrl = baseUrl.replace(/\/$/, "");
  }

  async listCompanions(): Promise<Companion[]> {
    return (await this.#request<{ companions: Companion[] }>("/api/companions")).companions;
  }

  async getCompanion(id: string): Promise<Companion> {
    return this.#request(`/api/companions/${encodeURIComponent(id)}`);
  }

  async getSnapshot(id: string): Promise<WorldSnapshot> {
    return this.#request(`/api/companions/${encodeURIComponent(id)}/snapshot`);
  }

  async getChatSettings(companionId?: string): Promise<ChatSettings> {
    const query = companionId ? `?companionId=${encodeURIComponent(companionId)}` : "";
    return this.#request(`/api/chat/settings${query}`);
  }

  async updateChatSettings(input: ChatSettingsDraft): Promise<ChatSettings> {
    return this.#request("/api/chat/settings", {
      method: "PUT",
      body: JSON.stringify(input),
    });
  }

  async submitAiDecision(interactionId: string, decision: AiTaskDecision): Promise<AiTaskDecisionResult> {
    return this.#request(`/api/ai/decisions/${encodeURIComponent(interactionId)}`, {
      method: "POST",
      body: JSON.stringify({ decision }),
    });
  }

  async listChatMessages(afterSequence = 0, limit = 50): Promise<ChatMessage[]> {
    const query = new URLSearchParams({
      afterSequence: String(afterSequence),
      limit: String(limit),
    });
    return (await this.#request<{ messages: ChatMessage[] }>(`/api/chat/messages?${query}`)).messages;
  }

  async listTasks(): Promise<TaskRecord[]> {
    return (await this.#request<{ tasks: TaskRecord[] }>("/api/tasks")).tasks;
  }

  async getTask(id: string): Promise<TaskRecord> {
    return this.#request(`/api/tasks/${encodeURIComponent(id)}`);
  }

  async assignTask(
    companionId: string,
    spec: TaskSpec,
    owner = "mcp",
    options: { replaceConflictingDelivery?: boolean } & AiDecisionMutationOptions = {},
  ): Promise<TaskRecord> {
    return this.#request(`/api/companions/${encodeURIComponent(companionId)}/tasks`, {
      method: "POST",
      body: JSON.stringify({
        spec,
        owner,
        replaceConflictingDelivery: options.replaceConflictingDelivery,
        aiDecisionInteractionId: options.aiDecisionInteractionId,
      }),
    });
  }

  async retryLatestBuildTask(
    companionId: string,
    owner = "mcp",
    requestedBy?: string,
    options: AiDecisionMutationOptions = {},
  ): Promise<TaskRecord> {
    return this.#request(`/api/companions/${encodeURIComponent(companionId)}/tasks/retry-build`, {
      method: "POST",
      body: JSON.stringify({
        owner,
        ...(requestedBy ? { requestedBy } : {}),
        ...(options.aiDecisionInteractionId
          ? { aiDecisionInteractionId: options.aiDecisionInteractionId }
          : {}),
      }),
    });
  }

  async retryTask(
    taskId: string,
    owner = "mcp",
    options: AiDecisionMutationOptions = {},
  ): Promise<TaskRecord> {
    return this.#request(`/api/tasks/${encodeURIComponent(taskId)}/retry`, {
      method: "POST",
      body: JSON.stringify({
        owner,
        ...(options.aiDecisionInteractionId
          ? { aiDecisionInteractionId: options.aiDecisionInteractionId }
          : {}),
      }),
    });
  }

  async cancelTask(taskId: string, reason = "MCP 请求取消"): Promise<TaskRecord> {
    return this.#request(`/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    });
  }

  async sendChat(
    companionId: string,
    message: string,
    owner = "mcp",
    options: ChatDeliveryOptions = {},
  ): Promise<void> {
    await this.#request(`/api/companions/${encodeURIComponent(companionId)}/chat`, {
      method: "POST",
      body: JSON.stringify({ message, owner, ...options }),
    });
  }

  async controlCompanion(
    companionId: string,
    action: CompanionAction,
    options: AiDecisionMutationOptions = {},
  ): Promise<Companion> {
    return this.#request(`/api/companions/${encodeURIComponent(companionId)}/actions`, {
      method: "POST",
      body: JSON.stringify({ action, ...options }),
    });
  }

  async listBuildPlans(): Promise<BuildPlan[]> {
    return (await this.#request<{ plans: BuildPlan[] }>("/api/build-plans")).plans;
  }

  async previewBuild(draft: BuildPlanDraft): Promise<BuildPlan> {
    return this.#request("/api/build-plans/preview", {
      method: "POST",
      body: JSON.stringify(draft),
    });
  }

  async importBuild(request: BuildImportRequest): Promise<BuildPlan> {
    return this.#request("/api/build-plans/import", {
      method: "POST",
      body: JSON.stringify(request),
    });
  }

  async confirmBuild(id: string): Promise<BuildPlan> {
    return this.#request(`/api/build-plans/${encodeURIComponent(id)}/confirm`, { method: "POST" });
  }

  async listSkills(): Promise<DeclarativeSkill[]> {
    return (await this.#request<{ skills: DeclarativeSkill[] }>("/api/skills")).skills;
  }

  async getSkill(id: string): Promise<DeclarativeSkill> {
    return this.#request(`/api/skills/${encodeURIComponent(id)}`);
  }

  async saveSkill(draft: DeclarativeSkillDraft): Promise<DeclarativeSkill> {
    return this.#request(`/api/skills/${encodeURIComponent(draft.id)}`, {
      method: "PUT",
      body: JSON.stringify(draft),
    });
  }

  async removeSkill(id: string): Promise<void> {
    await this.#request(`/api/skills/${encodeURIComponent(id)}`, { method: "DELETE" });
  }

  async acquireLease(companionId: string, owner: string, force = false): Promise<Companion> {
    return this.#request(`/api/companions/${encodeURIComponent(companionId)}/lease`, {
      method: "POST",
      body: JSON.stringify({ owner, force }),
    });
  }

  async releaseLease(companionId: string, owner: string): Promise<Companion> {
    return this.#request(`/api/companions/${encodeURIComponent(companionId)}/lease`, {
      method: "DELETE",
      body: JSON.stringify({ owner }),
    });
  }

  async emergencyStop(disconnect = false): Promise<void> {
    await this.#request("/api/emergency-stop", {
      method: "POST",
      body: JSON.stringify({ disconnect }),
    });
  }

  async #request<T>(path: string, init?: RequestInit): Promise<T> {
    const headers = new Headers(init?.headers);
    if (init?.body) headers.set("content-type", "application/json");
    const response = await fetch(`${this.#baseUrl}${path}`, {
      ...init,
      headers,
    });
    const body = await response.json().catch(() => ({})) as T & ErrorEnvelope;
    if (!response.ok) throw new RemoteControlError(body, response.status);
    return body;
  }
}
