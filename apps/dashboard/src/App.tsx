import { useEffect, useMemo, useState, type FormEvent, type ReactElement } from "react";
import type { BuildPlan, Companion, CompanionEvent, TaskRecord, TaskSpec } from "@mc/protocol";
import {
  Bot,
  Box,
  CheckCircle2,
  ChevronRight,
  CircleStop,
  HeartPulse,
  LocateFixed,
  MessageSquareText,
  Pickaxe,
  Play,
  RefreshCw,
  Settings,
  Shield,
  Sprout,
  Pause,
  Sword,
  Upload,
  X,
} from "lucide-react";
import { BuildViewport } from "./BuildViewport.js";
import { BuildImportDialog } from "./BuildImportDialog.js";
import { AiProviderDialog } from "./AiProviderDialog.js";
import { SkillsSecurityPanel } from "./SkillsSecurityPanel.js";
import { npcStatusView } from "./npc-status.js";
import {
  assignTask,
  cancelTask,
  controlCompanion,
  emergencyStop,
  confirmBuild,
  fetchBuildPlans,
  fetchCompanions,
  fetchTasks,
  sendChat,
  subscribeEvents,
} from "./api.js";

function quickTasks(ownerName: string): Array<{ label: string; icon: typeof Shield; spec: TaskSpec }> {
  return [
    { label: `跟随 ${ownerName}`, icon: ChevronRight, spec: { kind: "follow", player: ownerName, distance: 3, requestedBy: "dashboard" } },
    { label: `护卫 ${ownerName}`, icon: Shield, spec: { kind: "guard", player: ownerName, radius: 12, requestedBy: "dashboard" } },
    { label: "采集原木", icon: Pickaxe, spec: { kind: "gather", itemId: "#minecraft:logs", count: 16, requestedBy: "dashboard" } },
    { label: "照料农田", icon: Sprout, spec: { kind: "farm", cropId: "minecraft:wheat", action: "cycle", radius: 12, requestedBy: "dashboard" } },
    { label: "整理家中仓库", icon: Box, spec: { kind: "organize-storage", radius: 24, requestedBy: "dashboard" } },
    { label: "进食到饱", icon: HeartPulse, spec: { kind: "eat", count: 64, requestedBy: "dashboard" } },
    { label: "附近钓鱼", icon: Sprout, spec: { kind: "fish", count: 1, radius: 24, requestedBy: "dashboard" } },
    { label: "睡到天亮", icon: Pause, spec: { kind: "sleep", radius: 32, requestedBy: "dashboard" } },
    { label: "附近战斗", icon: Sword, spec: { kind: "combat", targetType: "hostile", maxDistance: 24, requestedBy: "dashboard" } },
    { label: "喂养龙", icon: HeartPulse, spec: { kind: "dragon", action: "feed", requestedBy: "dashboard" } },
  ];
}

function taskLabel(task: TaskRecord): string {
  const labels: Record<TaskSpec["kind"], string> = {
    follow: "跟随",
    guard: "护卫",
    move: "移动",
    gather: "采集",
    craft: "制作",
    smelt: "熔炼",
    farm: "农务",
    store: "整理",
    retrieve: "仓库取物",
    "organize-storage": "整理仓库",
    deliver: "交付",
  eat: "进食",
  "provision-food": "寻食备粮",
  ranch: "畜牧",
    drop: "丢弃",
    fish: "钓鱼",
    sleep: "睡觉",
    explore: "探索",
    combat: "战斗",
    dragon: "养龙",
    build: "建造",
    macro: "技能",
  };
  return labels[task.spec.kind];
}

function taskStateLabel(status: TaskRecord["status"]): string {
  return {
    queued: "排队",
    running: "执行中",
    paused: "已暂停",
    succeeded: "已完成",
    failed: "失败",
    cancelled: "已取消",
  }[status];
}

export default function App(): ReactElement {
  const [companions, setCompanions] = useState<Companion[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [buildPlans, setBuildPlans] = useState<BuildPlan[]>([]);
  const [selectedPlanId, setSelectedPlanId] = useState("");
  const [events, setEvents] = useState<CompanionEvent[]>([]);
  const [chat, setChat] = useState("");
  const [socketConnected, setSocketConnected] = useState(false);
  const [error, setError] = useState("");
  const [showAiSettings, setShowAiSettings] = useState(false);
  const [showBuildImport, setShowBuildImport] = useState(false);

  const selected = useMemo(
    () => companions.find((companion) => companion.id === selectedId) ?? companions[0],
    [companions, selectedId],
  );
  const selectedPlan = useMemo(
    () => buildPlans.find((plan) => plan.id === selectedPlanId) ?? buildPlans[0],
    [buildPlans, selectedPlanId],
  );

  const refresh = async () => {
    try {
      const [nextCompanions, nextTasks, nextBuildPlans] = await Promise.all([fetchCompanions(), fetchTasks(), fetchBuildPlans()]);
      setCompanions(nextCompanions);
      setTasks(nextTasks);
      setBuildPlans(nextBuildPlans);
      if (!selectedId && nextCompanions[0]) setSelectedId(nextCompanions[0].id);
      if (!selectedPlanId && nextBuildPlans[0]) setSelectedPlanId(nextBuildPlans[0].id);
      setError("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1200);
    const unsubscribe = subscribeEvents({
      onBootstrap: (initial) => setEvents(initial.slice(-80)),
      onEvent: (event) => setEvents((current) => [...current.slice(-79), event]),
      onState: setSocketConnected,
    });
    return () => {
      window.clearInterval(timer);
      unsubscribe();
    };
  }, []);

  const runQuickTask = async (spec: TaskSpec) => {
    if (!selected) return;
    try {
      await assignTask(selected.id, spec);
      await refresh();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  const submitChat = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected || !chat.trim()) return;
    try {
      await sendChat(selected.id, chat.trim());
      setChat("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  const acceptImportedPlan = (plan: BuildPlan) => {
    setBuildPlans((current) => [plan, ...current.filter((item) => item.id !== plan.id)]);
    setSelectedPlanId(plan.id);
  };

  const approveBuild = async () => {
    if (!selectedPlan) return;
    try {
      acceptImportedPlan(await confirmBuild(selectedPlan.id));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  const startBuild = async () => {
    if (!selected || !selectedPlan?.confirmed) return;
    try {
      await assignTask(selected.id, {
        kind: "build",
        planId: selectedPlan.id,
        placement: selectedPlan.builtIn ? "companion" : "plan-origin",
        requestedBy: "dashboard",
      });
      await refresh();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  const activeTasks = tasks.filter((task) => task.status === "running" || task.status === "queued" || task.status === "paused");
  const selectedQuickTasks = quickTasks(selected?.ownerName ?? "Player");
  const npcStatus = npcStatusView(selected, tasks);

  const controlNpc = async (action: "recall" | "follow" | "stay") => {
    if (!selected) return;
    try {
      await controlCompanion(selected.id, action);
      await refresh();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-lockup">
          <span className="brand-mark"><Bot size={19} /></span>
          <div>
            <h1>Minecraft Codex Companion</h1>
            <p>{selected?.snapshot.worldId ?? "等待游戏连接"}</p>
          </div>
        </div>
        <div className="topbar-actions">
          <span className={`connection-state ${socketConnected ? "is-online" : ""}`}>
            <span />{socketConnected ? "实时连接" : "正在重连"}
          </span>
          <button className="icon-button" title="AI 服务配置" onClick={() => setShowAiSettings(true)}><Settings size={18} /></button>
          <button className="icon-button refresh-button" title="刷新状态" onClick={() => void refresh()}><RefreshCw size={18} /></button>
          <button className="stop-button" onClick={() => void emergencyStop(false)}><CircleStop size={18} />急停</button>
        </div>
      </header>

      <aside className="sidebar">
        <div className="sidebar-heading">陪玩角色 <span>{companions.length}</span></div>
        <div className="companion-list">
          {companions.map((companion) => (
            <button
              key={companion.id}
              className={`companion-row ${selected?.id === companion.id ? "is-selected" : ""}`}
              onClick={() => setSelectedId(companion.id)}
            >
              <span className="avatar"><Bot size={19} /></span>
              <span className="companion-copy">
                <strong>{companion.name}</strong>
                <small>{companion.embodiment === "in-world-npc" ? "游戏内 NPC" : companion.backend} · {companion.snapshot.status}</small>
              </span>
              <span className={`presence ${companion.connected ? "is-online" : ""}`} />
            </button>
          ))}
        </div>

        {selected?.embodiment === "in-world-npc" && (
          <div className="npc-presence-actions" aria-label="NPC 控制">
            <button title="召回到身边" onClick={() => void controlNpc("recall")}><LocateFixed size={16} /><span>召回</span></button>
            <button title="跟随主人" onClick={() => void controlNpc("follow")}><ChevronRight size={16} /><span>跟随</span></button>
            <button title="原地等待" onClick={() => void controlNpc("stay")}><Pause size={16} /><span>等待</span></button>
          </div>
        )}

        <div className="sidebar-heading task-heading">快速任务</div>
        <div className="quick-actions">
          {selectedQuickTasks.map(({ label, icon: Icon, spec }) => (
            <button key={label} onClick={() => void runQuickTask(spec)} disabled={!selected}>
              <Icon size={17} /><span>{label}</span>
            </button>
          ))}
        </div>
      </aside>

      <main className="workspace">
        {error && <div className="error-banner"><span>{error}</span><button title="关闭" onClick={() => setError("")}><X size={16} /></button></div>}

        <section className="status-band">
          <div><span>生命</span><strong>{npcStatus.health}</strong></div>
          <div><span>饱食</span><strong>{npcStatus.food}</strong></div>
          <div><span>模式与姿态</span><strong>{npcStatus.mode}</strong></div>
          <div><span>活动任务</span><strong title={npcStatus.activeTask}>{npcStatus.activeTask}</strong></div>
          <div><span>龙状态</span><strong title={npcStatus.dragon}>{npcStatus.dragon}</strong></div>
          <div><span>坐标</span><strong>{selected ? `${selected.snapshot.position.x.toFixed(0)}, ${selected.snapshot.position.y.toFixed(0)}, ${selected.snapshot.position.z.toFixed(0)}` : "-"}</strong></div>
          <div><span>控制者</span><strong>{selected?.leaseOwner ?? "无人"}</strong></div>
        </section>

        <section className="npc-detail-strip" aria-label="NPC 详细状态">
          <div>
            <span>当前装备</span>
            <strong>{npcStatus.equipment.length ? npcStatus.equipment.join(" · ") : "暂无装备数据"}</strong>
          </div>
          <div>
            <span>背包物品</span>
            <strong title={npcStatus.inventory.join(" · ")}>{npcStatus.inventory.length ? npcStatus.inventory.join(" · ") : "背包为空"}</strong>
          </div>
          <div>
            <span>{selected?.snapshot.miningState ? "深层采矿" : "队列概览"}</span>
            <strong title={selected?.snapshot.miningState ? npcStatus.mining : npcStatus.scheduler}>
              {selected?.snapshot.miningState ? npcStatus.mining : npcStatus.scheduler}
            </strong>
          </div>
        </section>

        <section className="main-grid">
          <div className="world-panel">
            <div className="section-title"><Box size={17} /><h2>建筑预览</h2><span>拖动旋转</span></div>
            <div className="build-toolbar">
              <select
                aria-label="建筑计划"
                value={selectedPlan?.id ?? ""}
                onChange={(event) => setSelectedPlanId(event.target.value)}
                disabled={buildPlans.length === 0}
              >
                {buildPlans.length === 0 && <option value="">示例建筑</option>}
                {buildPlans.map((plan) => <option key={plan.id} value={plan.id}>{plan.name}</option>)}
              </select>
              <button type="button" title="导入建筑" onClick={() => setShowBuildImport(true)}><Upload size={15} /></button>
            </div>
            <BuildViewport plan={selectedPlan} />
            <div className="viewport-footer">
              <span className="plan-summary">
                {selectedPlan ? `${selectedPlan.blocks.length} 方块 · ${selectedPlan.size.x}×${selectedPlan.size.y}×${selectedPlan.size.z}` : "示例建筑"}
              </span>
              <div className="build-plan-actions">
                <button type="button" className="secondary-command" disabled={!selectedPlan || selectedPlan.confirmed} onClick={() => void approveBuild()}>
                  <CheckCircle2 size={14} />{selectedPlan?.confirmed ? "已确认" : "确认"}
                </button>
                <button type="button" className="primary-command" disabled={!selected || !selectedPlan?.confirmed} onClick={() => void startBuild()}>
                  <Play size={14} />开始
                </button>
              </div>
              <span>{selectedPlan ? `${selectedPlan.builtIn ? "内置安全模板" : "导入计划"} · ${selectedPlan.manifest.source.license ?? "自有内容"}` : "未选择计划"}</span>
              <strong title={selectedPlan?.manifest.sha256}>{selectedPlan ? `SHA-256 ${selectedPlan.manifest.sha256.slice(0, 10)}…` : "-"}</strong>
            </div>
          </div>

          <div className="right-rail">
            <section className="task-panel">
              <div className="section-title"><Pickaxe size={17} /><h2>任务队列</h2><span>{activeTasks.length} 活动</span></div>
              <div className="task-list">
                {tasks.slice(0, 8).map((task) => (
                  <div className="task-row" key={task.id}>
                    <div className="task-row-head">
                      <strong>{taskLabel(task)}</strong>
                      <span className={`task-state state-${task.status}`}>{taskStateLabel(task.status)}</span>
                    </div>
                    <p>{task.message}</p>
                    <div className="progress-track"><span style={{ width: `${task.progress * 100}%` }} /></div>
                    {(task.status === "running" || task.status === "queued" || task.status === "paused") && (
                      <button className="cancel-task" title="取消任务" onClick={() => void cancelTask(task.id)}><X size={14} /></button>
                    )}
                  </div>
                ))}
                {tasks.length === 0 && <div className="empty-state">暂无任务</div>}
              </div>
            </section>
          </div>
        </section>

        <section className="activity-panel">
          <div className="section-title"><MessageSquareText size={17} /><h2>实时活动</h2><span>{events.length}</span></div>
          <div className="activity-stream">
            {events.slice(-10).reverse().map((entry) => (
              <div className={`activity-row activity-${entry.type}`} key={entry.id}>
                <time>{new Date(entry.at).toLocaleTimeString("zh-CN", { hour12: false })}</time>
                <span>{entry.message}</span>
              </div>
            ))}
          </div>
          <form className="chat-bar" onSubmit={(event) => void submitChat(event)}>
            <MessageSquareText size={17} />
            <input value={chat} onChange={(event) => setChat(event.target.value)} placeholder="让 Codex 在游戏里说……" maxLength={256} />
            <button type="submit" disabled={!chat.trim() || !selected}>发送</button>
          </form>
        </section>

        <SkillsSecurityPanel />
      </main>
      <AiProviderDialog open={showAiSettings} onClose={() => setShowAiSettings(false)} />
      <BuildImportDialog open={showBuildImport} onClose={() => setShowBuildImport(false)} onImported={acceptImportedPlan} />
    </div>
  );
}
