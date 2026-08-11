import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  aiProviderDraftSchema,
  type AiProviderDraft,
  type AiProviderKind,
  type AiProviderProfile,
  type AiProviderState,
} from "@mc/protocol";
import type { SecretProtector } from "./secret-protector.js";
import { ControlError } from "./errors.js";

const CODEX_CLI_ID = "codex-cli";
const ANTIGRAVITY_ID = "antigravity-mcp";

function safeEndpoint(raw: string, kind: "api" | "mcp"): string {
  const url = new URL(raw);
  if (url.username || url.password || url.search || url.hash) {
    throw new ControlError({
      code: "AI_PROVIDER_URL_UNSAFE",
      message: "Provider URLs cannot contain credentials, query strings, or fragments",
      statusCode: 400,
    });
  }
  const local = ["localhost", "127.0.0.1", "::1"].includes(url.hostname.toLowerCase());
  if (!local && url.protocol !== "https:") {
    throw new ControlError({ code: "AI_PROVIDER_URL_UNSAFE", message: "Remote providers must use HTTPS", statusCode: 400 });
  }
  if (kind === "mcp" && !local) {
    const allowed = new Set((process.env.MC_MCP_ALLOW_HOSTS ?? "").split(",").map((value) => value.trim().toLowerCase()).filter(Boolean));
    if (!allowed.has(url.hostname.toLowerCase())) {
      throw new ControlError({ code: "NETWORK_TARGET_BLOCKED", message: "Remote MCP host is not explicitly allowlisted", statusCode: 403 });
    }
  }
  return raw.replace(/\/$/, "");
}

interface StoredProvider {
  id: string;
  name: string;
  kind: Exclude<AiProviderKind, "codex-cli">;
  baseUrl: string | null;
  model: string | null;
  mcpUrl: string | null;
  encryptedApiKey: string | null;
  createdAt: string;
  updatedAt: string;
}

interface PersistedProviderState {
  version: 1;
  activeProviderId: string;
  createdAt: string;
  providers: StoredProvider[];
}

interface TestState {
  state: AiProviderState;
  message: string;
  testedAt: string;
}

export interface RuntimeAiProvider {
  id: string;
  name: string;
  kind: AiProviderKind;
  baseUrl: string | null;
  model: string | null;
  mcpUrl: string | null;
  apiKey: string | null;
}

export class AiProviderStore {
  readonly #statePath: string;
  readonly #mcpUrl: string;
  readonly #protector: SecretProtector;
  readonly #tests = new Map<string, TestState>();
  readonly #ready: Promise<void>;
  #state: PersistedProviderState;

  constructor(options: { stateDirectory: string; mcpUrl: string; protector: SecretProtector }) {
    const now = new Date().toISOString();
    this.#statePath = path.join(options.stateDirectory, "ai-providers.json");
    this.#mcpUrl = options.mcpUrl;
    this.#protector = options.protector;
    this.#state = {
      version: 1,
      activeProviderId: CODEX_CLI_ID,
      createdAt: now,
      providers: [],
    };
    this.#ready = this.#load();
  }

  async list(): Promise<AiProviderProfile[]> {
    await this.#ready;
    return [this.#codexProfile(), this.#antigravityProfile(), ...this.#state.providers.map((item) => this.#public(item))];
  }

  async get(id: string): Promise<AiProviderProfile> {
    const found = (await this.list()).find((profile) => profile.id === id);
    if (!found) throw new ControlError({ code: "AI_PROVIDER_NOT_FOUND", message: `找不到 AI 服务 ${id}`, statusCode: 404 });
    return found;
  }

  async create(input: AiProviderDraft): Promise<AiProviderProfile> {
    await this.#ready;
    const draft = aiProviderDraftSchema.parse(input);
    const now = new Date().toISOString();
    const encryptedApiKey = "apiKey" in draft && draft.apiKey
      ? await this.#protector.protect(draft.apiKey)
      : null;
    const stored: StoredProvider = {
      id: randomUUID(),
      name: draft.name,
      kind: draft.kind,
      baseUrl: "baseUrl" in draft ? safeEndpoint(draft.baseUrl, "api") : null,
      model: "model" in draft ? draft.model : null,
      mcpUrl: "mcpUrl" in draft ? safeEndpoint(draft.mcpUrl, "mcp") : null,
      encryptedApiKey,
      createdAt: now,
      updatedAt: now,
    };
    this.#state.providers.push(stored);
    await this.#save();
    return this.#public(stored);
  }

  async update(id: string, input: AiProviderDraft, clearApiKey = false): Promise<AiProviderProfile> {
    await this.#ready;
    const draft = aiProviderDraftSchema.parse(input);
    const stored = this.#requireStored(id);
    const encryptedApiKey = clearApiKey
      ? null
      : "apiKey" in draft && draft.apiKey
        ? await this.#protector.protect(draft.apiKey)
        : stored.encryptedApiKey;
    stored.name = draft.name;
    stored.kind = draft.kind;
    stored.baseUrl = "baseUrl" in draft ? safeEndpoint(draft.baseUrl, "api") : null;
    stored.model = "model" in draft ? draft.model : null;
    stored.mcpUrl = "mcpUrl" in draft ? safeEndpoint(draft.mcpUrl, "mcp") : null;
    stored.encryptedApiKey = encryptedApiKey;
    stored.updatedAt = new Date().toISOString();
    this.#tests.delete(id);
    await this.#save();
    return this.#public(stored);
  }

  async remove(id: string): Promise<void> {
    await this.#ready;
    if (id === CODEX_CLI_ID || id === ANTIGRAVITY_ID) {
      throw new ControlError({ code: "AI_PROVIDER_BUILT_IN", message: "内置 AI 服务不能删除", statusCode: 409 });
    }
    const index = this.#state.providers.findIndex((provider) => provider.id === id);
    if (index < 0) throw new ControlError({ code: "AI_PROVIDER_NOT_FOUND", message: `找不到 AI 服务 ${id}`, statusCode: 404 });
    this.#state.providers.splice(index, 1);
    this.#tests.delete(id);
    if (this.#state.activeProviderId === id) this.#state.activeProviderId = CODEX_CLI_ID;
    await this.#save();
  }

  async activate(id: string): Promise<AiProviderProfile> {
    await this.#ready;
    const profile = await this.get(id);
    if (!profile.executable) {
      throw new ControlError({
        code: "AI_PROVIDER_EXTERNAL_ONLY",
        message: `${profile.name} 是外部 MCP 控制器，不能处理自动游戏聊天`,
        statusCode: 422,
      });
    }
    this.#state.activeProviderId = id;
    await this.#save();
    return (await this.list()).find((item) => item.id === id)!;
  }

  async activeRuntime(): Promise<RuntimeAiProvider> {
    await this.#ready;
    return this.runtime(this.#state.activeProviderId);
  }

  async runtime(id: string): Promise<RuntimeAiProvider> {
    await this.#ready;
    if (id === CODEX_CLI_ID) {
      return { id, name: "跟随 Codex", kind: "codex-cli", baseUrl: null, model: null, mcpUrl: this.#mcpUrl, apiKey: null };
    }
    if (id === ANTIGRAVITY_ID) {
      return { id, name: "反重力 MCP", kind: "antigravity-mcp", baseUrl: null, model: null, mcpUrl: this.#mcpUrl, apiKey: null };
    }
    const stored = this.#requireStored(id);
    return {
      id: stored.id,
      name: stored.name,
      kind: stored.kind,
      baseUrl: stored.baseUrl,
      model: stored.model,
      mcpUrl: stored.mcpUrl,
      apiKey: stored.encryptedApiKey ? await this.#protector.unprotect(stored.encryptedApiKey) : null,
    };
  }

  async recordTest(id: string, ok: boolean, message: string): Promise<AiProviderProfile> {
    await this.get(id);
    this.#tests.set(id, {
      state: ok ? "ready" : "error",
      message,
      testedAt: new Date().toISOString(),
    });
    return this.get(id);
  }

  async #load(): Promise<void> {
    try {
      const parsed = JSON.parse(await readFile(this.#statePath, "utf8")) as Partial<PersistedProviderState>;
      if (parsed.version !== 1 || !Array.isArray(parsed.providers) || typeof parsed.activeProviderId !== "string") {
        throw new Error("Unsupported AI provider state file");
      }
      this.#state = {
        version: 1,
        activeProviderId: parsed.activeProviderId,
        createdAt: typeof parsed.createdAt === "string" ? parsed.createdAt : new Date().toISOString(),
        providers: parsed.providers as StoredProvider[],
      };
      const ids = new Set([CODEX_CLI_ID, ...this.#state.providers.map((provider) => provider.id)]);
      if (!ids.has(this.#state.activeProviderId)) this.#state.activeProviderId = CODEX_CLI_ID;
    } catch (caught) {
      const code = caught instanceof Error && "code" in caught ? (caught as NodeJS.ErrnoException).code : undefined;
      if (code !== "ENOENT") throw caught;
      await this.#save();
    }
  }

  async #save(): Promise<void> {
    const directory = path.dirname(this.#statePath);
    const temporary = `${this.#statePath}.${process.pid}.tmp`;
    await mkdir(directory, { recursive: true });
    await writeFile(temporary, `${JSON.stringify(this.#state, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporary, this.#statePath);
  }

  #requireStored(id: string): StoredProvider {
    const stored = this.#state.providers.find((provider) => provider.id === id);
    if (!stored) throw new ControlError({ code: "AI_PROVIDER_NOT_FOUND", message: `找不到 AI 服务 ${id}`, statusCode: 404 });
    return stored;
  }

  #codexProfile(): AiProviderProfile {
    const test = this.#tests.get(CODEX_CLI_ID);
    return {
      id: CODEX_CLI_ID,
      name: "跟随 Codex",
      kind: "codex-cli",
      active: this.#state.activeProviderId === CODEX_CLI_ID,
      builtIn: true,
      executable: true,
      hasApiKey: false,
      baseUrl: null,
      model: null,
      mcpUrl: this.#mcpUrl,
      state: test?.state ?? "ready",
      stateMessage: test?.message ?? "使用当前 Codex 登录与 config.toml",
      lastTestedAt: test?.testedAt ?? null,
      createdAt: this.#state.createdAt,
      updatedAt: this.#state.createdAt,
    };
  }

  #antigravityProfile(): AiProviderProfile {
    const test = this.#tests.get(ANTIGRAVITY_ID);
    return {
      id: ANTIGRAVITY_ID,
      name: "反重力 MCP",
      kind: "antigravity-mcp",
      active: false,
      builtIn: true,
      executable: false,
      hasApiKey: false,
      baseUrl: null,
      model: null,
      mcpUrl: this.#mcpUrl,
      state: test?.state ?? "external",
      stateMessage: test?.message ?? "通过共享 MCP 从反重力控制 Minecraft",
      lastTestedAt: test?.testedAt ?? null,
      createdAt: this.#state.createdAt,
      updatedAt: this.#state.createdAt,
    };
  }

  #public(stored: StoredProvider): AiProviderProfile {
    const test = this.#tests.get(stored.id);
    const external = stored.kind === "antigravity-mcp";
    return {
      id: stored.id,
      name: stored.name,
      kind: stored.kind,
      active: this.#state.activeProviderId === stored.id,
      builtIn: false,
      executable: !external,
      hasApiKey: stored.encryptedApiKey !== null,
      baseUrl: stored.baseUrl,
      model: stored.model,
      mcpUrl: stored.mcpUrl,
      state: test?.state ?? (external ? "external" : "ready"),
      stateMessage: test?.message ?? (external ? "通过 MCP 外部控制" : "已配置，尚未测试"),
      lastTestedAt: test?.testedAt ?? null,
      createdAt: stored.createdAt,
      updatedAt: stored.updatedAt,
    };
  }
}
