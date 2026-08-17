import { useEffect, useMemo, useState, type FormEvent, type ReactElement } from "react";
import type {
  Companion,
  FacilityRecord,
  GoalRecord,
  KnowledgeRecord,
  KnowledgeTopic,
  WorkGraph,
  WorkNode,
} from "@mc/protocol";
import {
  BrainCircuit,
  Database,
  FastForward,
  ListTree,
  Pause,
  Play,
  RefreshCw,
  Search,
  X,
} from "lucide-react";
import {
  advanceAgentGoal,
  cancelAgentGoal,
  fetchAgentFacilities,
  fetchAgentGoals,
  fetchAgentPlan,
  pauseAgentGoal,
  queryAgentKnowledge,
  resumeAgentGoal,
  submitAgentGoal,
} from "./api.js";

const KNOWLEDGE_TOPICS: KnowledgeTopic[] = [
  "crafting",
  "smelting",
  "mining",
  "farming",
  "ranching",
  "food",
  "combat",
  "storage",
  "building",
  "redstone",
  "dragon",
  "travel",
  "other",
];

function goalStatusLabel(status: GoalRecord["status"]): string {
  return {
    queued: "排队",
    planning: "规划中",
    running: "执行中",
    paused: "已暂停",
    succeeded: "已完成",
    failed: "失败",
    cancelled: "已取消",
  }[status];
}

function nodeStatusLabel(status: WorkNode["status"]): string {
  return {
    pending: "等待",
    ready: "就绪",
    running: "执行中",
    blocked: "阻塞",
    paused: "暂停",
    succeeded: "完成",
    failed: "失败",
    skipped: "跳过",
  }[status];
}

function actionSummary(node: WorkNode): string {
  const action = node.action;
  switch (action.kind) {
    case "task":
      return action.spec.kind === "craft"
        ? `task:craft ${action.spec.itemId}×${action.spec.count}`
        : action.spec.kind === "gather"
          ? `task:gather ${action.spec.itemId}×${action.spec.count}`
          : `task:${action.spec.kind}`;
    case "skill":
      return `skill:${action.skillId}`;
    case "query-knowledge":
      return `knowledge:${action.query}`;
    case "query-facilities":
      return `facility:${action.type ?? "any"} ${action.tags.join(",")}`;
    case "register-facility":
      return `register:${action.facility.type}`;
    case "verify":
      return `verify:${action.evidenceKind}`;
    case "control":
      return `control:${action.action}`;
    case "chat":
      return "chat";
    case "noop":
      return "noop";
  }
}

function compactPosition(facility: FacilityRecord): string {
  return `${Math.round(facility.position.x)}, ${Math.round(facility.position.y)}, ${Math.round(facility.position.z)}`;
}

interface AgentPanelProps {
  companion: Companion | undefined;
}

export function AgentPanel({ companion }: AgentPanelProps): ReactElement {
  const [goals, setGoals] = useState<GoalRecord[]>([]);
  const [facilities, setFacilities] = useState<FacilityRecord[]>([]);
  const [selectedGoalId, setSelectedGoalId] = useState("");
  const [plan, setPlan] = useState<WorkGraph | null>(null);
  const [knowledgeQuery, setKnowledgeQuery] = useState("钻石镐 火把 农田 牧场");
  const [knowledgeTopic, setKnowledgeTopic] = useState<KnowledgeTopic | "">("");
  const [knowledge, setKnowledge] = useState<KnowledgeRecord[]>([]);
  const [newGoalText, setNewGoalText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const selectedGoal = useMemo(
    () => goals.find((goal) => goal.id === selectedGoalId) ?? goals[0],
    [goals, selectedGoalId],
  );
  const activeNode = useMemo(
    () => plan?.nodes.find((node) => node.id === selectedGoal?.activeWorkNodeId) ?? plan?.nodes.find((node) => node.status === "running"),
    [plan, selectedGoal?.activeWorkNodeId],
  );

  const refreshAgent = async (goalId = selectedGoal?.id) => {
    try {
      const [nextGoals, nextFacilities] = await Promise.all([
        fetchAgentGoals(),
        fetchAgentFacilities(companion?.snapshot.worldId),
      ]);
      setGoals(nextGoals);
      setFacilities(nextFacilities);
      const nextGoalId = goalId || nextGoals[0]?.id || "";
      setSelectedGoalId((current) => current || nextGoalId);
      const targetGoalId = nextGoals.some((goal) => goal.id === nextGoalId) ? nextGoalId : nextGoals[0]?.id;
      setPlan(targetGoalId ? await fetchAgentPlan(targetGoalId) : null);
      setError("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  const refreshKnowledge = async () => {
    try {
      const topics = knowledgeTopic ? [knowledgeTopic] : [];
      setKnowledge(await queryAgentKnowledge(knowledgeQuery, topics));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    }
  };

  useEffect(() => {
    void refreshAgent();
    void refreshKnowledge();
    const timer = window.setInterval(() => void refreshAgent(), 2_500);
    return () => window.clearInterval(timer);
  }, [companion?.snapshot.worldId]);

  useEffect(() => {
    if (!selectedGoal?.id) {
      setPlan(null);
      return;
    }
    void fetchAgentPlan(selectedGoal.id)
      .then(setPlan)
      .catch((caught) => setError(caught instanceof Error ? caught.message : String(caught)));
  }, [selectedGoal?.id]);

  const withBusy = async (run: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await run();
      await refreshAgent();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  };

  const createGoal = async (event: FormEvent) => {
    event.preventDefault();
    if (!companion || !newGoalText.trim()) return;
    await withBusy(async () => {
      const goal = await submitAgentGoal(companion.id, newGoalText, companion.ownerName || "dashboard");
      setSelectedGoalId(goal.id);
      setNewGoalText("");
    });
  };

  return (
    <section className="agent-panel" aria-label="Agent v2 控制台">
      {error && (
        <div className="agent-error">
          <span>{error}</span>
          <button type="button" title="关闭" onClick={() => setError("")}><X size={14} /></button>
        </div>
      )}

      <div className="agent-panel-grid">
        <div className="agent-card agent-goals-card">
          <div className="section-title">
            <BrainCircuit size={17} />
            <h2>Agent Goals</h2>
            <button className="panel-refresh" type="button" title="刷新 Agent 状态" onClick={() => void refreshAgent()}>
              <RefreshCw size={14} />
            </button>
          </div>

          <form className="agent-goal-form" onSubmit={(event) => void createGoal(event)}>
            <input
              value={newGoalText}
              onChange={(event) => setNewGoalText(event.target.value)}
              placeholder="输入高层目标：例如 制作钻石镐并交给我"
              maxLength={500}
              disabled={!companion || busy}
            />
            <button type="submit" disabled={!companion || !newGoalText.trim() || busy}>创建</button>
          </form>

          <div className="agent-goal-list">
            {goals.slice(0, 12).map((goal) => (
              <button
                key={goal.id}
                type="button"
                className={`agent-goal-row ${selectedGoal?.id === goal.id ? "is-selected" : ""}`}
                onClick={() => setSelectedGoalId(goal.id)}
              >
                <span className={`agent-status status-${goal.status}`}>{goalStatusLabel(goal.status)}</span>
                <strong title={goal.spec.objective}>{goal.spec.title}</strong>
                <small>{Math.round(goal.progress * 100)}% · {goal.message || goal.spec.source}</small>
              </button>
            ))}
            {goals.length === 0 && <div className="empty-state">暂无 Agent 目标</div>}
          </div>
        </div>

        <div className="agent-card agent-plan-card">
          <div className="section-title">
            <ListTree size={17} />
            <h2>WorkGraph</h2>
            <span>{plan ? `${plan.nodes.length} 节点 · ${plan.status}` : "未选择目标"}</span>
          </div>
          <div className="agent-plan-toolbar">
            <strong title={activeNode?.label}>{activeNode ? `当前：${activeNode.label}` : "当前：无活动节点"}</strong>
            <div>
              <button type="button" disabled={!selectedGoal || busy} onClick={() => void withBusy(() => advanceAgentGoal(selectedGoal!.id))}>
                <FastForward size={13} />推进
              </button>
              <button type="button" disabled={!selectedGoal || busy || selectedGoal.status === "paused"} onClick={() => void withBusy(() => pauseAgentGoal(selectedGoal!.id))}>
                <Pause size={13} />暂停
              </button>
              <button type="button" disabled={!selectedGoal || busy || selectedGoal.status !== "paused"} onClick={() => void withBusy(() => resumeAgentGoal(selectedGoal!.id))}>
                <Play size={13} />恢复
              </button>
              <button type="button" disabled={!selectedGoal || busy} onClick={() => void withBusy(() => cancelAgentGoal(selectedGoal!.id))}>
                <X size={13} />取消
              </button>
            </div>
          </div>
          <div className="agent-node-list">
            {plan?.nodes.map((node) => (
              <div key={node.id} className={`agent-node-row node-${node.status}`}>
                <span>{nodeStatusLabel(node.status)}</span>
                <strong title={node.label}>{node.label}</strong>
                <small title={actionSummary(node)}>{node.id} · {actionSummary(node)} · {Math.round(node.progress * 100)}%</small>
              </div>
            ))}
            {!plan && <div className="empty-state">选择一个目标查看工作链</div>}
          </div>
        </div>

        <div className="agent-card agent-facilities-card">
          <div className="section-title">
            <Database size={17} />
            <h2>设施记忆</h2>
            <span>{facilities.length}</span>
          </div>
          <div className="agent-table-wrap">
            <table className="agent-table">
              <thead>
                <tr><th>类型</th><th>名称</th><th>坐标</th><th>标签</th></tr>
              </thead>
              <tbody>
                {facilities.slice(0, 80).map((facility) => (
                  <tr key={facility.id}>
                    <td>{facility.type}</td>
                    <td title={facility.name}>{facility.name}</td>
                    <td>{compactPosition(facility)}</td>
                    <td title={facility.tags.join(", ")}>{facility.tags.join(", ") || "-"}</td>
                  </tr>
                ))}
                {facilities.length === 0 && <tr><td className="skills-empty" colSpan={4}>暂无设施记忆</td></tr>}
              </tbody>
            </table>
          </div>
        </div>

        <div className="agent-card agent-knowledge-card">
          <div className="section-title">
            <Search size={17} />
            <h2>本地玩法知识库</h2>
            <span>{knowledge.length}</span>
          </div>
          <form className="agent-knowledge-form" onSubmit={(event) => {
            event.preventDefault();
            void refreshKnowledge();
          }}>
            <input value={knowledgeQuery} onChange={(event) => setKnowledgeQuery(event.target.value)} maxLength={240} />
            <select value={knowledgeTopic} onChange={(event) => setKnowledgeTopic(event.target.value as KnowledgeTopic | "")}>
              <option value="">全部主题</option>
              {KNOWLEDGE_TOPICS.map((topic) => <option key={topic} value={topic}>{topic}</option>)}
            </select>
            <button type="submit">查询</button>
          </form>
          <div className="agent-knowledge-list">
            {knowledge.slice(0, 10).map((record) => (
              <article key={record.id}>
                <strong>{record.id}</strong>
                <span>{record.topic} · {record.source} · {record.confidence}</span>
                <p>{record.summary}</p>
              </article>
            ))}
            {knowledge.length === 0 && <div className="empty-state">没有匹配知识</div>}
          </div>
        </div>
      </div>
    </section>
  );
}
