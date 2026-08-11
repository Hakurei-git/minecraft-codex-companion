import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import {
  DEFAULT_CHAT_ACTION_MODE,
  DEFAULT_CHAT_TOKEN_BUDGET,
  DEFAULT_COMPANION_PERSONA_SETTINGS,
  chatSettingsDraftSchema,
  chatSettingsSchema,
  type ChatSettings,
  type ChatSettingsDraft,
} from "@mc/protocol";

interface PersistedChatSettingsV1 extends Omit<ChatSettings, "companionName"> {
  version: 1;
}

interface PersistedChatSettingsV2 {
  version: 2;
  selectedCompanionName: string;
  profiles: ChatSettings[];
}

const DEFAULT_PLAYER_NAME = "Player";
const DEFAULT_COMPANION_NAME = "Companion";

function profileKey(value: string): string {
  return value.trim().toLocaleLowerCase("en-US");
}

function parsePersistedSettings(value: unknown): ChatSettings {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return chatSettingsSchema.parse(value);
  }
  const source = value as Record<string, unknown>;
  return chatSettingsSchema.parse({
    ...source,
    actionMode: source.actionMode === "hybrid" ? "smart" : source.actionMode,
  });
}

function defaultSettings(playerName: string, companionName: string): ChatSettings {
  return {
    freeChatEnabled: false,
    playerName: playerName.trim() || DEFAULT_PLAYER_NAME,
    companionName: companionName.trim() || DEFAULT_COMPANION_NAME,
    target: "active-provider",
    actionMode: DEFAULT_CHAT_ACTION_MODE,
    tokenBudget: DEFAULT_CHAT_TOKEN_BUDGET,
    persona: { ...DEFAULT_COMPANION_PERSONA_SETTINGS },
    updatedAt: new Date().toISOString(),
  };
}

export class ChatSettingsStore {
  readonly #statePath: string | null;
  readonly #ready: Promise<void>;
  readonly #profiles = new Map<string, ChatSettings>();
  #selectedCompanionName: string;
  #legacyUnclaimed = false;

  constructor(stateDirectory?: string, defaultPlayerName = DEFAULT_PLAYER_NAME) {
    this.#statePath = stateDirectory ? path.join(stateDirectory, "chat-settings.json") : null;
    const initial = defaultSettings(defaultPlayerName, DEFAULT_COMPANION_NAME);
    this.#selectedCompanionName = initial.companionName;
    this.#profiles.set(profileKey(initial.companionName), initial);
    this.#ready = this.#load();
  }

  async get(companionName?: string): Promise<ChatSettings> {
    await this.#ready;
    const selected = companionName?.trim() || this.#selectedCompanionName;
    const existing = this.#profiles.get(profileKey(selected));
    if (existing) return chatSettingsSchema.parse(existing);
    const fallback = this.#profiles.get(profileKey(this.#selectedCompanionName));
    const unclaimedDefault = this.#profiles.size === 1
      && profileKey(this.#selectedCompanionName) === profileKey(DEFAULT_COMPANION_NAME);
    if (companionName?.trim() && fallback && (this.#legacyUnclaimed || unclaimedDefault)) {
      const claimed = chatSettingsSchema.parse({ ...fallback, companionName: selected });
      this.#profiles.delete(profileKey(this.#selectedCompanionName));
      this.#profiles.set(profileKey(selected), claimed);
      this.#selectedCompanionName = selected;
      this.#legacyUnclaimed = false;
      return claimed;
    }
    const created = defaultSettings(fallback?.playerName ?? DEFAULT_PLAYER_NAME, selected);
    return chatSettingsSchema.parse(created);
  }

  async update(input: ChatSettingsDraft): Promise<ChatSettings> {
    await this.#ready;
    const draft = chatSettingsDraftSchema.parse(input);
    const next = chatSettingsSchema.parse({
      ...draft,
      companionName: draft.companionName ?? this.#selectedCompanionName,
      updatedAt: new Date().toISOString(),
    });
    this.#selectedCompanionName = next.companionName;
    this.#legacyUnclaimed = false;
    this.#profiles.set(profileKey(next.companionName), next);
    await this.#save();
    return this.get(next.companionName);
  }

  async #load(): Promise<void> {
    if (!this.#statePath) return;
    try {
      const parsed = JSON.parse(await readFile(this.#statePath, "utf8")) as
        | Partial<PersistedChatSettingsV1>
        | Partial<PersistedChatSettingsV2>;
      if (parsed.version === 2) {
        if (!Array.isArray(parsed.profiles) || typeof parsed.selectedCompanionName !== "string") {
          throw new Error("Invalid chat settings state file");
        }
        const profiles = parsed.profiles.map(parsePersistedSettings);
        if (profiles.length === 0) throw new Error("Chat settings state has no profiles");
        this.#profiles.clear();
        for (const profile of profiles) this.#profiles.set(profileKey(profile.companionName), profile);
        this.#selectedCompanionName = this.#profiles.has(profileKey(parsed.selectedCompanionName))
          ? parsed.selectedCompanionName
          : profiles[0]!.companionName;
        this.#legacyUnclaimed = false;
        return;
      }
      if (parsed.version !== 1) throw new Error("Unsupported chat settings state file");
      const legacy = parsePersistedSettings({
        ...parsed,
        companionName: DEFAULT_COMPANION_NAME,
        persona: parsed.persona ?? DEFAULT_COMPANION_PERSONA_SETTINGS,
      });
      this.#profiles.clear();
      this.#profiles.set(profileKey(legacy.companionName), legacy);
      this.#selectedCompanionName = legacy.companionName;
      this.#legacyUnclaimed = true;
    } catch (caught) {
      const code = caught instanceof Error && "code" in caught ? (caught as NodeJS.ErrnoException).code : undefined;
      if (code !== "ENOENT") throw caught;
    }
  }

  async #save(): Promise<void> {
    if (!this.#statePath) return;
    await mkdir(path.dirname(this.#statePath), { recursive: true });
    const temporaryPath = `${this.#statePath}.${process.pid}.tmp`;
    const state: PersistedChatSettingsV2 = {
      version: 2,
      selectedCompanionName: this.#selectedCompanionName,
      profiles: [...this.#profiles.values()].sort((left, right) => (
        left.companionName.localeCompare(right.companionName, "en-US")
      )),
    };
    await writeFile(temporaryPath, `${JSON.stringify(state, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    await rename(temporaryPath, this.#statePath);
  }
}
