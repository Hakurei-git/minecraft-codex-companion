import { useEffect, useMemo, useState, type FormEvent, type ReactElement } from "react";
import type { AiProviderDraft, AiProviderKind, AiProviderProfile, ChatSettingsDraft } from "@mc/protocol";
import {
  BadgeCheck,
  Bot,
  Check,
  CloudCog,
  Copy,
  Eye,
  EyeOff,
  FlaskConical,
  KeyRound,
  MessageCircle,
  Orbit,
  Plus,
  Save,
  Server,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import {
  activateAiProvider,
  createAiProvider,
  deleteAiProvider,
  fetchAiProviders,
  fetchChatSettings,
  fetchMcpConfig,
  testAiProvider,
  updateAiProvider,
  updateChatSettings,
} from "./api.js";

interface Props {
  open: boolean;
  onClose(): void;
}

interface FormState {
  kind: Exclude<AiProviderKind, "codex-cli">;
  name: string;
  baseUrl: string;
  model: string;
  mcpUrl: string;
  apiKey: string;
  clearApiKey: boolean;
}

const EMPTY_FORM: FormState = {
  kind: "codex-api",
  name: "",
  baseUrl: "",
  model: "",
  mcpUrl: "http://127.0.0.1:8765/mcp",
  apiKey: "",
  clearApiKey: false,
};

const DEFAULT_CHAT_SETTINGS: ChatSettingsDraft = {
  freeChatEnabled: false,
  playerName: "Player",
  companionName: "Companion",
  target: "active-provider",
  actionMode: "stable",
  tokenBudget: 512,
  persona: {
    mode: "inherit",
    displayName: "",
    personality: "",
    speakingStyle: "",
    memoryNotes: "",
  },
};

const KIND_LABELS: Record<AiProviderKind, string> = {
  "codex-cli": "Codex · 本机登录",
  "codex-api": "Codex · 自定义 API",
  "claude-api": "Claude · 兼容 API",
  "antigravity-mcp": "反重力 · MCP",
};

function iconFor(kind: AiProviderKind): typeof Bot {
  if (kind === "codex-cli") return Bot;
  if (kind === "claude-api") return CloudCog;
  if (kind === "antigravity-mcp") return Orbit;
  return Server;
}

function formFrom(profile: AiProviderProfile): FormState {
  return {
    kind: profile.kind === "codex-cli" ? "codex-api" : profile.kind,
    name: profile.name,
    baseUrl: profile.baseUrl ?? "",
    model: profile.model ?? "",
    mcpUrl: profile.mcpUrl ?? "http://127.0.0.1:8765/mcp",
    apiKey: "",
    clearApiKey: false,
  };
}

function draftFrom(form: FormState): AiProviderDraft {
  if (form.kind === "antigravity-mcp") {
    return { kind: form.kind, name: form.name.trim(), mcpUrl: form.mcpUrl.trim() };
  }
  return {
    kind: form.kind,
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    model: form.model.trim(),
    ...(form.apiKey.trim() ? { apiKey: form.apiKey.trim() } : {}),
  };
}

function chatDraftFrom(settings: ChatSettingsDraft): ChatSettingsDraft {
  return {
    freeChatEnabled: settings.freeChatEnabled,
    playerName: settings.playerName,
    companionName: settings.companionName ?? "Companion",
    target: settings.target,
    actionMode: settings.actionMode ?? "stable",
    tokenBudget: settings.tokenBudget ?? 512,
    persona: { ...settings.persona },
  };
}

export function AiProviderDialog({ open, onClose }: Props): ReactElement | null {
  const [providers, setProviders] = useState<AiProviderProfile[]>([]);
  const [selectedId, setSelectedId] = useState("codex-cli");
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [showKey, setShowKey] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);
  const [chatSettings, setChatSettings] = useState<ChatSettingsDraft>(DEFAULT_CHAT_SETTINGS);
  const [chatSettingsLoaded, setChatSettingsLoaded] = useState(false);
  const [chatBusy, setChatBusy] = useState(false);
  const [chatSaved, setChatSaved] = useState(false);

  const selected = useMemo(
    () => providers.find((provider) => provider.id === selectedId) ?? providers[0],
    [providers, selectedId],
  );

  const load = async (preferId?: string) => {
    const next = await fetchAiProviders();
    setProviders(next);
    const id = preferId ?? selectedId;
    if (next.some((provider) => provider.id === id)) setSelectedId(id);
    else if (next[0]) setSelectedId(next[0].id);
  };

  const loadChatSettings = async () => {
    const next = await fetchChatSettings();
    setChatSettings(chatDraftFrom(next));
    setChatSettingsLoaded(true);
    setChatSaved(false);
  };

  useEffect(() => {
    if (!open) return;
    setError("");
    setChatSettingsLoaded(false);
    void Promise.all([load(), loadChatSettings()])
      .catch((caught) => setError(caught instanceof Error ? caught.message : String(caught)));
  }, [open]);

  useEffect(() => {
    if (!selected || creating) return;
    setForm(formFrom(selected));
    setShowKey(false);
    setCopied(false);
  }, [selected?.id, creating]);

  if (!open) return null;

  const selectProvider = (profile: AiProviderProfile) => {
    setCreating(false);
    setSelectedId(profile.id);
    setError("");
  };

  const startCreate = () => {
    setCreating(true);
    setForm({ ...EMPTY_FORM });
    setShowKey(false);
    setError("");
  };

  const changeChatSettings = (update: (current: ChatSettingsDraft) => ChatSettingsDraft) => {
    setChatSettings(update);
    setChatSaved(false);
  };

  const saveChatSettings = async (event: FormEvent) => {
    event.preventDefault();
    setChatBusy(true);
    setError("");
    try {
      const next = await updateChatSettings({
        ...chatSettings,
        playerName: chatSettings.playerName.trim(),
        companionName: (chatSettings.companionName ?? "Companion").trim(),
        persona: {
          ...chatSettings.persona,
          displayName: chatSettings.persona.displayName.trim(),
          personality: chatSettings.persona.personality.trim(),
          speakingStyle: chatSettings.persona.speakingStyle.trim(),
          memoryNotes: chatSettings.persona.memoryNotes.trim(),
        },
      });
      setChatSettings(chatDraftFrom(next));
      setChatSaved(true);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setChatBusy(false);
    }
  };

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setBusy("save");
    setError("");
    try {
      if (creating) {
        const created = await createAiProvider(draftFrom(form));
        setCreating(false);
        await load(created.id);
      } else if (selected && !selected.builtIn) {
        await updateAiProvider(selected.id, draftFrom(form), form.clearApiKey);
        await load(selected.id);
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy("");
    }
  };

  const activate = async () => {
    if (!selected) return;
    setBusy("activate");
    setError("");
    try {
      await activateAiProvider(selected.id);
      await load(selected.id);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy("");
    }
  };

  const testConnection = async () => {
    if (!selected) return;
    setBusy("test");
    setError("");
    try {
      await testAiProvider(selected.id);
      await load(selected.id);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy("");
    }
  };

  const remove = async () => {
    if (!selected || selected.builtIn) return;
    if (!window.confirm(`删除 AI 服务“${selected.name}”？`)) return;
    setBusy("delete");
    try {
      await deleteAiProvider(selected.id);
      setCreating(false);
      await load("codex-cli");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy("");
    }
  };

  const copyMcp = async () => {
    try {
      const config = await fetchMcpConfig();
      await navigator.clipboard.writeText(JSON.stringify(config.antigravity, null, 2));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_500);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  const ActiveIcon = iconFor(creating ? form.kind : selected?.kind ?? form.kind);
  const customEditor = creating || (selected && !selected.builtIn);

  return (
    <div className="ai-dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.currentTarget === event.target) onClose();
    }}>
      <section className="ai-dialog" role="dialog" aria-modal="true" aria-label="AI 生成服务">
        <header className="ai-dialog-header">
          <div>
            <h2>AI 生成服务</h2>
            <p>配置 Codex、Claude 兼容 API 或反重力 MCP</p>
          </div>
          <div className="ai-dialog-header-actions">
            <button className="secondary-command" type="button" onClick={startCreate}><Plus size={16} />新增配置</button>
            <button className="dialog-close" type="button" title="关闭" onClick={onClose}><X size={18} /></button>
          </div>
        </header>

        <form className="free-chat-band" onSubmit={(event) => void saveChatSettings(event)}>
          <div className="free-chat-band-primary">
            <div className="free-chat-heading">
              <span className="free-chat-icon"><MessageCircle size={17} /></span>
              <span><strong>自由聊天</strong><small>{chatSettings.freeChatEnabled ? "普通聊天自动响应" : "仅指令聊天响应"}</small></span>
            </div>

            <label className="free-chat-switch">
              <input
                type="checkbox"
                checked={chatSettings.freeChatEnabled}
                onChange={(event) => changeChatSettings((value) => ({ ...value, freeChatEnabled: event.target.checked }))}
              />
              <span className="switch-track" aria-hidden="true"><span /></span>
              <span>{chatSettings.freeChatEnabled ? "已开启" : "已关闭"}</span>
            </label>

            <label className="compact-chat-field">
              <span>NPC 名称</span>
              <input
                required
                maxLength={64}
                value={chatSettings.companionName ?? ""}
                onChange={(event) => changeChatSettings((value) => ({ ...value, companionName: event.target.value }))}
              />
            </label>

            <label className="compact-chat-field">
              <span>响应玩家</span>
              <input
                required
                maxLength={64}
                value={chatSettings.playerName}
                onChange={(event) => changeChatSettings((value) => ({ ...value, playerName: event.target.value }))}
              />
            </label>

            <label className="compact-chat-field">
              <span>响应端</span>
              <select
                value={chatSettings.target}
                onChange={(event) => changeChatSettings((value) => ({
                  ...value,
                  target: event.target.value as ChatSettingsDraft["target"],
                }))}
              >
                <option value="active-provider">当前 Codex / Claude</option>
                <option value="multi-agent">Codex + Claude 协作</option>
                <option value="antigravity-mcp">反重力 MCP</option>
              </select>
            </label>

            <fieldset className="chat-persona-mode">
              <legend>人格模式</legend>
              <div>
                <button
                  type="button"
                  className={chatSettings.persona.mode === "inherit" ? "is-active" : ""}
                  onClick={() => changeChatSettings((value) => ({
                    ...value,
                    persona: { ...value.persona, mode: "inherit" },
                  }))}
                >
                  {chatSettings.target === "antigravity-mcp"
                    ? "继承反重力"
                    : chatSettings.target === "multi-agent"
                      ? "继承协作 Agent"
                      : "继承当前 Agent"}
                </button>
                <button
                  type="button"
                  className={chatSettings.persona.mode === "custom" ? "is-active" : ""}
                  onClick={() => changeChatSettings((value) => ({
                    ...value,
                    persona: { ...value.persona, mode: "custom" },
                  }))}
                >
                  Minecraft 专属
                </button>
              </div>
            </fieldset>

            <button className="free-chat-save" type="submit" disabled={!chatSettingsLoaded || chatBusy}>
              {chatSaved ? <Check size={15} /> : <Save size={15} />}
              {chatBusy ? "保存中" : chatSaved ? "已保存" : "保存"}
            </button>
          </div>

          <div className="chat-intelligence-row">
            <fieldset className="chat-intelligence-mode">
              <legend>任务理解方式</legend>
              <div>
                <button
                  type="button"
                  className={chatSettings.actionMode !== "smart" ? "is-active" : ""}
                  onClick={() => changeChatSettings((value) => ({ ...value, actionMode: "stable" }))}
                >
                  不使用智能 AI
                </button>
                <button
                  type="button"
                  className={chatSettings.actionMode === "smart" ? "is-active" : ""}
                  onClick={() => changeChatSettings((value) => ({ ...value, actionMode: "smart" }))}
                >
                  启用智能 AI
                </button>
              </div>
            </fieldset>
            <label className="compact-chat-field token-budget-field">
              <span>单次输出预算（token）</span>
              <input
                type="number"
                min={128}
                max={4096}
                step={128}
                disabled={chatSettings.actionMode !== "smart"}
                value={chatSettings.tokenBudget ?? 512}
                onChange={(event) => changeChatSettings((value) => ({
                  ...value,
                  tokenBudget: Math.max(128, Math.min(4096, Number(event.target.value) || 128)),
                }))}
              />
            </label>
            <p className="chat-intelligence-note">
              {chatSettings.actionMode === "stable"
                ? "任务动作只走本地已识别的动作链，不调用 AI 规划；自由聊天是否调用响应端由上方开关决定。"
                : "复杂需求由 AI 转成一个受验证的本地任务；状态查询、召回和急停仍由本地直接执行。"}
            </p>
          </div>

          {chatSettings.persona.mode === "inherit" ? (
            <div className="persona-inherit-status">
              <Sparkles size={14} />
              <span>{chatSettings.target === "antigravity-mcp"
                ? "复用反重力自身已设定的人格，无需重复填写"
                : chatSettings.target === "multi-agent"
                  ? "Codex 与 Claude 分别保留独立会话，由 Codex 协调后只回复一次"
                  : "复用当前 Codex / Claude Agent 的现有人格，无需重复填写"}</span>
            </div>
          ) : (
            <div className="persona-custom-fields">
              <label><span>称呼</span><input maxLength={64} value={chatSettings.persona.displayName} onChange={(event) => changeChatSettings((value) => ({ ...value, persona: { ...value.persona, displayName: event.target.value } }))} placeholder="游戏内称呼" /></label>
              <label><span>人格</span><textarea maxLength={1200} value={chatSettings.persona.personality} onChange={(event) => changeChatSettings((value) => ({ ...value, persona: { ...value.persona, personality: event.target.value } }))} placeholder="性格、偏好与行为特点" /></label>
              <label><span>说话风格</span><textarea maxLength={600} value={chatSettings.persona.speakingStyle} onChange={(event) => changeChatSettings((value) => ({ ...value, persona: { ...value.persona, speakingStyle: event.target.value } }))} placeholder="语气、用词与表达习惯" /></label>
              <label><span>长期备注</span><textarea maxLength={2000} value={chatSettings.persona.memoryNotes} onChange={(event) => changeChatSettings((value) => ({ ...value, persona: { ...value.persona, memoryNotes: event.target.value } }))} placeholder="共同经历、约定与持续目标" /></label>
            </div>
          )}
        </form>

        <div className="ai-dialog-body">
          <aside className="provider-nav">
            {providers.map((profile) => {
              const Icon = iconFor(profile.kind);
              return (
                <button
                  type="button"
                  key={profile.id}
                  className={`provider-nav-row ${!creating && selected?.id === profile.id ? "is-selected" : ""}`}
                  onClick={() => selectProvider(profile)}
                >
                  <span className="provider-nav-icon"><Icon size={17} /></span>
                  <span><strong>{profile.name}</strong><small>{KIND_LABELS[profile.kind]}</small></span>
                  {profile.active && <Check className="provider-active-check" size={16} />}
                </button>
              );
            })}
            {creating && (
              <button type="button" className="provider-nav-row is-selected">
                <span className="provider-nav-icon"><Plus size={17} /></span>
                <span><strong>新配置</strong><small>{KIND_LABELS[form.kind]}</small></span>
              </button>
            )}
          </aside>

          <main className="provider-detail">
            {error && <div className="provider-error"><span>{error}</span><button type="button" title="关闭" onClick={() => setError("")}><X size={15} /></button></div>}

            {customEditor ? (
              <form className="provider-form" onSubmit={(event) => void save(event)}>
                <div className="provider-detail-heading">
                  <span className="provider-large-icon"><ActiveIcon size={23} /></span>
                  <div><h3>{creating ? "新增 AI 服务" : form.name}</h3><p>{KIND_LABELS[form.kind]}</p></div>
                </div>

                <fieldset className="provider-kind-control">
                  <legend>AI 类型</legend>
                  <div>
                    <button type="button" className={form.kind === "codex-api" ? "is-active" : ""} onClick={() => setForm((value) => ({ ...value, kind: "codex-api" }))}>Codex</button>
                    <button type="button" className={form.kind === "claude-api" ? "is-active" : ""} onClick={() => setForm((value) => ({ ...value, kind: "claude-api" }))}>Claude</button>
                    <button type="button" className={form.kind === "antigravity-mcp" ? "is-active" : ""} onClick={() => setForm((value) => ({ ...value, kind: "antigravity-mcp" }))}>反重力</button>
                  </div>
                </fieldset>

                <label className="provider-field"><span>配置名称</span><input required maxLength={80} value={form.name} onChange={(event) => setForm((value) => ({ ...value, name: event.target.value }))} placeholder="例如：我的 Claude" /></label>
                {form.kind === "antigravity-mcp" ? (
                  <label className="provider-field"><span>MCP 地址</span><input required type="url" value={form.mcpUrl} onChange={(event) => setForm((value) => ({ ...value, mcpUrl: event.target.value }))} /></label>
                ) : (
                  <>
                    <label className="provider-field"><span>Base URL</span><input required type="url" value={form.baseUrl} onChange={(event) => setForm((value) => ({ ...value, baseUrl: event.target.value }))} placeholder={form.kind === "claude-api" ? "https://你的-Claude-兼容网关" : "https://api.openai.com/v1"} /></label>
                    <label className="provider-field"><span>模型</span><input required value={form.model} onChange={(event) => setForm((value) => ({ ...value, model: event.target.value }))} placeholder={form.kind === "claude-api" ? "填写 Claude 兼容服务的模型 ID" : "填写 Codex 服务支持的模型 ID"} /></label>
                    <label className="provider-field provider-key-field">
                      <span>API Key</span>
                      <span className="key-input-wrap"><KeyRound size={16} /><input type={showKey ? "text" : "password"} value={form.apiKey} onChange={(event) => setForm((value) => ({ ...value, apiKey: event.target.value, clearApiKey: false }))} placeholder={!creating && selected?.hasApiKey ? "已加密保存，留空保持不变" : "可留空用于本地免密服务"} /><button type="button" title={showKey ? "隐藏密钥" : "显示密钥"} onClick={() => setShowKey((value) => !value)}>{showKey ? <EyeOff size={16} /> : <Eye size={16} />}</button></span>
                    </label>
                    {!creating && selected?.hasApiKey && <label className="clear-key-toggle"><input type="checkbox" checked={form.clearApiKey} onChange={(event) => setForm((value) => ({ ...value, clearApiKey: event.target.checked, apiKey: "" }))} /><span>清除已保存的密钥</span></label>}
                  </>
                )}

                <div className="provider-form-actions">
                  {!creating && selected && <button className="danger-command" type="button" title="删除配置" disabled={Boolean(busy)} onClick={() => void remove()}><Trash2 size={16} /></button>}
                  {!creating && selected && <button className="secondary-command" type="button" disabled={Boolean(busy)} onClick={() => void testConnection()}><FlaskConical size={16} />{busy === "test" ? "测试中" : "测试连接"}</button>}
                  {!creating && selected?.executable && <button className="secondary-command" type="button" disabled={selected.active || Boolean(busy)} onClick={() => void activate()}>{selected.active ? <Check size={16} /> : <Bot size={16} />}{selected.active ? "使用中" : "设为使用中"}</button>}
                  <button className="primary-command" type="submit" disabled={Boolean(busy)}><Save size={16} />{busy === "save" ? "保存中" : "保存配置"}</button>
                </div>
              </form>
            ) : selected ? (
              <div className="built-in-provider">
                <div className="provider-detail-heading">
                  <span className="provider-large-icon"><ActiveIcon size={23} /></span>
                  <div><h3>{selected.name}</h3><p>{KIND_LABELS[selected.kind]}</p></div>
                </div>
                <div className={`provider-state state-${selected.state}`}>
                  <BadgeCheck size={21} />
                  <div><strong>{selected.state === "error" ? "连接异常" : selected.state === "external" ? "外部控制" : "已就绪"}</strong><span>{selected.stateMessage}</span></div>
                </div>

                {selected.kind === "antigravity-mcp" ? (
                  <div className="mcp-config-block">
                    <label className="provider-field"><span>MCP 地址</span><input readOnly value={selected.mcpUrl ?? ""} /></label>
                    <button className="secondary-command" type="button" onClick={() => void copyMcp()}><Copy size={16} />{copied ? "已复制" : "复制反重力配置"}</button>
                  </div>
                ) : (
                  <div className="built-in-actions">
                    <button className="secondary-command" type="button" disabled={Boolean(busy)} onClick={() => void testConnection()}><FlaskConical size={16} />{busy === "test" ? "测试中" : "测试连接"}</button>
                    <button className="primary-command" type="button" disabled={selected.active || Boolean(busy)} onClick={() => void activate()}>{selected.active ? <Check size={16} /> : <Bot size={16} />}{selected.active ? "使用中" : "设为使用中"}</button>
                  </div>
                )}
              </div>
            ) : <div className="provider-loading">正在加载配置</div>}
          </main>
        </div>
      </section>
    </div>
  );
}
