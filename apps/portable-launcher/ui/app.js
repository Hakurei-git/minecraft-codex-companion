"use strict";

const session = new URLSearchParams(window.location.search).get("session") || "";
const fields = [
  "launcherPath",
  "launcherArguments",
  "minecraftRoot",
  "sourceVersion",
  "targetVersion",
  "playerName",
  "companionName",
  "port",
  "freeChatEnabled",
  "tokenBudget",
  "antigravityConfigPath",
  "antigravityConversationTitle",
];
let latest = null;
let targetTouched = false;
let npcSkinMode = "default";
let toastTimer = 0;

function element(id) { return document.getElementById(id); }

async function api(path, options = {}) {
  const response = await fetch(`${path}?session=${encodeURIComponent(session)}`, {
    method: options.method || "GET",
    headers: {
      "x-companion-session": session,
      ...(options.body ? { "content-type": "application/json" } : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || `HTTP ${response.status}`);
  return result;
}

function toast(message, error = false) {
  const node = element("toast");
  node.textContent = message;
  node.className = `show${error ? " error" : ""}`;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => { node.className = ""; }, 3800);
}

function configFromForm() {
  const config = {};
  for (const id of fields) {
    const node = element(id);
    config[id] = node.type === "checkbox" ? node.checked : node.value;
  }
  config.port = Number(config.port);
  config.tokenBudget = Number(config.tokenBudget);
  config.actionMode = element("smartAiEnabled").checked ? "smart" : "stable";
  config.chatTarget = document.querySelector('input[name="chatTarget"]:checked')?.value || "active-provider";
  config.persona = {
    mode: document.querySelector('input[name="personaMode"]:checked')?.value || "inherit",
    displayName: element("personaDisplayName").value,
    personality: element("personaPersonality").value,
    speakingStyle: element("personaSpeakingStyle").value,
    memoryNotes: element("personaMemoryNotes").value,
  };
  config.npcSkinMode = npcSkinMode;
  return config;
}

function updatePersonaUi() {
  const mode = document.querySelector('input[name="personaMode"]:checked')?.value || "inherit";
  const target = document.querySelector('input[name="chatTarget"]:checked')?.value || "active-provider";
  element("persona-fields").hidden = mode !== "custom";
  element("persona-status").textContent = mode === "custom"
    ? "叠加 Minecraft 专属人格"
    : target === "antigravity-mcp" ? "沿用反重力当前人格" : "沿用 Codex / Claude 当前人格";
}

function updateSmartAiUi() {
  const enabled = element("smartAiEnabled").checked;
  element("tokenBudget").disabled = !enabled;
  element("smart-ai-note").textContent = enabled
    ? "AI 每次只生成一个受本地验证的结构化任务；Claude 为硬上限，Codex 与反重力为软预算。"
    : "关闭时动作只走本地规则；自由聊天由左侧开关独立控制。";
}

function updateSkinUi(customAvailable = latest?.skin?.customAvailable) {
  const custom = npcSkinMode === "custom";
  element("skin-status").textContent = custom
    ? customAvailable ? "自定义皮肤，重启游戏后生效" : "自定义皮肤文件缺失"
    : "当前白发猫娘皮肤";
  element("skin-preview").src = custom && customAvailable
    ? `/api/skin-preview?session=${encodeURIComponent(session)}&v=${Date.now()}`
    : "/assets/companion.png";
}

function fillConfig(config) {
  for (const id of fields) {
    const node = element(id);
    if (!node) continue;
    if (node.type === "checkbox") node.checked = Boolean(config[id]);
    else node.value = config[id] ?? "";
  }
  element("smartAiEnabled").checked = config.actionMode === "smart" || config.actionMode === "hybrid";
  const radio = document.querySelector(`input[name="chatTarget"][value="${config.chatTarget}"]`);
  if (radio) radio.checked = true;
  const persona = config.persona || {};
  const personaRadio = document.querySelector(`input[name="personaMode"][value="${persona.mode === "custom" ? "custom" : "inherit"}"]`);
  if (personaRadio) personaRadio.checked = true;
  element("personaDisplayName").value = persona.displayName || "";
  element("personaPersonality").value = persona.personality || "";
  element("personaSpeakingStyle").value = persona.speakingStyle || "";
  element("personaMemoryNotes").value = persona.memoryNotes || "";
  npcSkinMode = config.npcSkinMode === "custom" ? "custom" : "default";
  updatePersonaUi();
  updateSmartAiUi();
  updateSkinUi();
}

function fillInstances(instances, selected) {
  const select = element("sourceVersion");
  const sources = instances.filter((item) => !item.isCompanionClone);
  select.replaceChildren(new Option("请选择", ""));
  for (const item of sources) select.add(new Option(item.name, item.name));
  if (sources.some((item) => item.name === selected)) select.value = selected;
}

function renderService(status, port) {
  const node = element("service-state");
  node.classList.toggle("running", Boolean(status?.running));
  node.querySelector("span").textContent = status?.running
    ? `服务运行中 · ${status.companions || 0} 个 NPC`
    : status?.error || "服务未启动";
  element("dashboard-url").textContent = `http://127.0.0.1:${port || 8765}/`;
}

function renderEvents(events) {
  const list = element("events");
  if (!events?.length) {
    list.innerHTML = '<li class="empty">暂无操作记录</li>';
    return;
  }
  list.replaceChildren(...events.slice().reverse().map((event) => {
    const item = document.createElement("li");
    item.className = event.level;
    const time = document.createElement("time");
    time.textContent = new Date(event.at).toLocaleTimeString("zh-CN", { hour12: false });
    const level = document.createElement("b");
    level.textContent = event.level;
    const message = document.createElement("span");
    message.textContent = event.message;
    item.append(time, level, message);
    return item;
  }));
}

function render(data, initial = false) {
  latest = data;
  if (initial) {
    fillConfig(data.config);
    fillInstances(data.instances, data.config.sourceVersion);
    targetTouched = Boolean(data.config.targetVersion);
    element("state-directory").textContent = data.stateDirectory;
    element("companion-prompt").textContent = data.prompt;
  }
  element("payload-status").textContent = data.payload.valid ? "便携运行时完整" : data.payload.error;
  element("payload-status").style.color = data.payload.valid ? "" : "#ffadad";
  element("operation-state").textContent = data.operation || "就绪";
  renderService(data.service, initial ? data.config.port : Number(element("port").value));
  renderEvents(data.events);
}

async function refresh(initial = false) {
  render(await api("/api/bootstrap"), initial);
}

async function save() {
  if (!element("config-form").reportValidity()) throw new Error("请先补全有效配置");
  const result = await api("/api/save", { method: "POST", body: { config: configFromForm() } });
  fillConfig(result.config);
  fillInstances(result.instances, result.config.sourceVersion);
  element("save-state").textContent = "已保存";
  return result.config;
}

async function action(name, endpoint, options = {}) {
  document.body.classList.add("busy");
  element("operation-state").textContent = name;
  try {
    if (options.save !== false) await save();
    const result = await api(endpoint, { method: "POST", body: {} });
    if (options.mcp) element("mcp-state").textContent = "测试通过";
    toast(options.success || `${name}完成`);
    return result;
  } catch (error) {
    toast(error instanceof Error ? error.message : String(error), true);
    throw error;
  } finally {
    document.body.classList.remove("busy");
    await refresh(false).catch(() => undefined);
  }
}

element("config-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  document.body.classList.add("busy");
  try {
    await save();
    toast("配置已保存");
  } catch (error) {
    toast(error instanceof Error ? error.message : String(error), true);
  } finally {
    document.body.classList.remove("busy");
    await refresh(false).catch(() => undefined);
  }
});

element("targetVersion").addEventListener("input", () => { targetTouched = true; element("save-state").textContent = "有未保存修改"; });
element("sourceVersion").addEventListener("change", (event) => {
  if (!targetTouched || !element("targetVersion").value) element("targetVersion").value = event.target.value ? `${event.target.value}-Codex` : "";
  element("save-state").textContent = "有未保存修改";
});
element("minecraftRoot").addEventListener("change", async () => {
  element("save-state").textContent = "有未保存修改";
});
for (const input of document.querySelectorAll("input, select, textarea")) {
  input.addEventListener("change", () => { element("save-state").textContent = "有未保存修改"; });
}
for (const input of document.querySelectorAll('input[name="personaMode"], input[name="chatTarget"]')) {
  input.addEventListener("change", updatePersonaUi);
}
element("smartAiEnabled").addEventListener("change", updateSmartAiUi);

for (const button of document.querySelectorAll("[data-browse]")) {
  button.addEventListener("click", async () => {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    try {
      const launcher = button.dataset.browse === "launcher";
      const field = element(launcher ? "launcherPath" : "minecraftRoot");
      const result = await api(launcher ? "/api/browse-launcher" : "/api/browse-folder", {
        method: "POST",
        body: { current: field.value },
      });
      if (result.path) {
        field.value = result.path;
        element("save-state").textContent = "有未保存修改";
        if (!launcher) {
          const data = await api("/api/instances", { method: "POST", body: { minecraftRoot: field.value } });
          fillInstances(data.instances, element("sourceVersion").value);
        }
      }
    } catch (error) {
      toast(error instanceof Error ? error.message : String(error), true);
    } finally {
      button.disabled = false;
      button.removeAttribute("aria-busy");
    }
  });
}

const actions = {
  prepare: () => action("一键准备并启动", "/api/prepare", { success: "实例和服务已准备，启动器已打开" }),
  install: () => action("安装或更新实例", "/api/install"),
  "start-service": () => action("启动服务", "/api/service/start"),
  "stop-service": () => action("停止服务", "/api/service/stop", { save: false }),
  launch: () => action("打开启动器", "/api/launcher/start"),
  dashboard: () => action("打开控制台", "/api/dashboard/open", { save: false }),
  "close-app": async () => {
    await api("/api/app/exit", { method: "POST", body: {} });
    document.body.innerHTML = '<main style="padding:48px;text-align:center"><h2>设置程序已关闭</h2><p>控制服务仍可继续运行，可以关闭此页面。</p></main>';
  },
  "install-antigravity": () => action("写入反重力 MCP 配置", "/api/antigravity/install", { success: "MCP 配置已写入；重启反重力后生效" }),
  "test-mcp": () => action("测试 MCP 通道", "/api/mcp/test", { mcp: true }),
  "bind-antigravity": () => action("按标题绑定反重力会话", "/api/antigravity/bind"),
  "recover-antigravity": () => action("解除反重力会话卡住", "/api/antigravity/recover"),
  "choose-skin": async () => {
    document.body.classList.add("busy");
    try {
      const result = await api("/api/browse-skin", { method: "POST", body: {} });
      if (!result.selected) return;
      npcSkinMode = "custom";
      if (latest) latest.skin = { customAvailable: true };
      updateSkinUi(true);
      element("save-state").textContent = "有未保存修改";
      toast("皮肤已导入，保存配置后生效");
    } catch (error) {
      toast(error instanceof Error ? error.message : String(error), true);
    } finally {
      document.body.classList.remove("busy");
    }
  },
  "default-skin": async () => {
    npcSkinMode = "default";
    updateSkinUi();
    element("save-state").textContent = "有未保存修改";
    toast("已切换为默认皮肤，保存配置后生效");
  },
  "copy-prompt": async () => {
    try {
      await navigator.clipboard.writeText(element("companion-prompt").textContent);
      toast("陪玩提示词已复制");
    } catch {
      const range = document.createRange();
      range.selectNodeContents(element("companion-prompt"));
      window.getSelection().removeAllRanges();
      window.getSelection().addRange(range);
      toast("已选中提示词，请按 Ctrl+C");
    }
  },
};

for (const button of document.querySelectorAll("[data-action]")) {
  button.addEventListener("click", () => { void actions[button.dataset.action](); });
}
element("refresh").addEventListener("click", () => { void refresh(false).catch((error) => toast(error.message, true)); });

void refresh(true).catch((error) => toast(error instanceof Error ? error.message : String(error), true));
window.setInterval(() => { if (!document.body.classList.contains("busy")) void refresh(false).catch(() => undefined); }, 4000);
