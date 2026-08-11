import type {
  AiProviderDraft,
  AiProviderProfile,
  BuildImportRequest,
  BuildPlan,
  ChatSettings,
  ChatSettingsDraft,
  Companion,
  CompanionAction,
  CompanionEvent,
  DeclarativeSkill,
  TaskRecord,
  TaskSpec,
} from "@mc/protocol";

interface ApiErrorBody {
  error?: {
    code?: string;
    message?: string;
  };
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body !== undefined && !headers.has("content-type")) {
    headers.set("content-type", "application/json");
  }
  const response = await fetch(path, {
    ...init,
    headers,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as ApiErrorBody;
    throw new Error(body.error?.message ?? `请求失败 (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export interface McpConfigResponse {
  url: string;
  antigravity: {
    mcpServers: Record<string, {
      command: string;
      args: string[];
      env: Record<string, string>;
    }>;
  };
}

export async function fetchAiProviders(): Promise<AiProviderProfile[]> {
  return (await request<{ providers: AiProviderProfile[] }>("/api/ai/providers")).providers;
}

export async function createAiProvider(profile: AiProviderDraft): Promise<AiProviderProfile> {
  return request("/api/ai/providers", { method: "POST", body: JSON.stringify(profile) });
}

export async function updateAiProvider(
  id: string,
  profile: AiProviderDraft,
  clearApiKey = false,
): Promise<AiProviderProfile> {
  return request(`/api/ai/providers/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify({ profile, clearApiKey }),
  });
}

export async function deleteAiProvider(id: string): Promise<void> {
  await request(`/api/ai/providers/${encodeURIComponent(id)}`, { method: "DELETE" });
}

export async function activateAiProvider(id: string): Promise<AiProviderProfile> {
  return request(`/api/ai/providers/${encodeURIComponent(id)}/activate`, { method: "POST" });
}

export async function testAiProvider(id: string): Promise<AiProviderProfile> {
  return request(`/api/ai/providers/${encodeURIComponent(id)}/test`, { method: "POST" });
}

export async function fetchMcpConfig(): Promise<McpConfigResponse> {
  return request("/api/ai/mcp-config");
}

export async function fetchChatSettings(): Promise<ChatSettings> {
  return request("/api/chat/settings");
}

export async function updateChatSettings(settings: ChatSettingsDraft): Promise<ChatSettings> {
  return request("/api/chat/settings", {
    method: "PUT",
    body: JSON.stringify(settings),
  });
}

export async function fetchCompanions(): Promise<Companion[]> {
  const result = await request<{ companions: Companion[] }>("/api/companions");
  return result.companions;
}

export async function fetchTasks(): Promise<TaskRecord[]> {
  const result = await request<{ tasks: TaskRecord[] }>("/api/tasks");
  return result.tasks;
}

export async function fetchSkills(): Promise<DeclarativeSkill[]> {
  return (await request<{ skills: DeclarativeSkill[] }>("/api/skills")).skills;
}

export async function reviewSkill(id: string, approved: boolean): Promise<DeclarativeSkill> {
  return request(`/api/skills/${encodeURIComponent(id)}/review`, {
    method: "POST",
    body: JSON.stringify({ approved }),
  });
}

export async function fetchBuildPlans(): Promise<BuildPlan[]> {
  return (await request<{ plans: BuildPlan[] }>("/api/build-plans")).plans;
}

export async function importBuildFile(input: BuildImportRequest): Promise<BuildPlan> {
  return request("/api/build-plans/import", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export async function confirmBuild(planId: string): Promise<BuildPlan> {
  return request(`/api/build-plans/${encodeURIComponent(planId)}/confirm`, { method: "POST" });
}

export async function assignTask(companionId: string, spec: TaskSpec): Promise<TaskRecord> {
  return request<TaskRecord>(`/api/companions/${encodeURIComponent(companionId)}/tasks`, {
    method: "POST",
    body: JSON.stringify({ spec, owner: "dashboard" }),
  });
}

export async function sendChat(companionId: string, message: string): Promise<void> {
  await request(`/api/companions/${encodeURIComponent(companionId)}/chat`, {
    method: "POST",
    body: JSON.stringify({ message, owner: "dashboard" }),
  });
}

export async function controlCompanion(companionId: string, action: CompanionAction): Promise<Companion> {
  return request(`/api/companions/${encodeURIComponent(companionId)}/actions`, {
    method: "POST",
    body: JSON.stringify({ action }),
  });
}

export async function cancelTask(taskId: string): Promise<void> {
  await request(`/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
    method: "POST",
    body: JSON.stringify({ reason: "从控制面板取消" }),
  });
}

export async function emergencyStop(disconnect = false): Promise<void> {
  await request("/api/emergency-stop", {
    method: "POST",
    body: JSON.stringify({ disconnect }),
  });
}

export function subscribeEvents(handlers: {
  onBootstrap(events: CompanionEvent[]): void;
  onEvent(event: CompanionEvent): void;
  onState(connected: boolean): void;
}): () => void {
  let socket: WebSocket | null = null;
  let reconnectTimer: number | null = null;
  let stopped = false;

  const connect = () => {
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(`${protocol}//${location.host}/api/events`);
    socket.addEventListener("open", () => handlers.onState(true));
    socket.addEventListener("message", (message) => {
      const payload = JSON.parse(String(message.data)) as
        | { type: "bootstrap"; events: CompanionEvent[] }
        | { type: "event"; event: CompanionEvent };
      if (payload.type === "bootstrap") handlers.onBootstrap(payload.events);
      else handlers.onEvent(payload.event);
    });
    socket.addEventListener("close", () => {
      handlers.onState(false);
      if (!stopped) reconnectTimer = window.setTimeout(connect, 1_000);
    });
  };

  connect();
  return () => {
    stopped = true;
    if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
    socket?.close();
  };
}
