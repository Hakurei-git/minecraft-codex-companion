import { useCallback, useEffect, useMemo, useState, type ReactElement } from "react";
import type { DeclarativeSkill } from "@mc/protocol";
import { Check, LockKeyhole, RefreshCw, ShieldAlert, ShieldCheck, X } from "lucide-react";
import { fetchSkills, reviewSkill } from "./api.js";

const STATUS_TEXT: Record<DeclarativeSkill["security"]["status"], string> = {
  trusted: "内置信任",
  pending: "等待审核",
  approved: "已批准",
  rejected: "已拒绝",
};

function sourceLabel(skill: DeclarativeSkill): string {
  const source = skill.manifest.source;
  if (source.kind === "built-in") return "内置技能";
  if (source.kind === "learned") return source.author ? `本地 · ${source.author}` : "本地学习";
  return source.author ?? "外部来源";
}

function permissionSummary(skill: DeclarativeSkill): string[] {
  const permissions = skill.manifest.permissions;
  const labels = permissions.tools.map((tool) => tool.replace(/^mc_/, "mc:"));
  labels.push(permissions.network === "none" ? "无网络" : `网络:${permissions.network}`);
  if (permissions.allowedHosts.length) labels.push(...permissions.allowedHosts.map((host) => `域名:${host}`));
  labels.push("无文件访问", "禁用系统命令");
  return labels;
}

export function SkillsSecurityPanel(): ReactElement {
  const [skills, setSkills] = useState<DeclarativeSkill[]>([]);
  const [loading, setLoading] = useState(true);
  const [reviewingId, setReviewingId] = useState<string | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setSkills(await fetchSkills());
      setError("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const counts = useMemo(() => ({
    approved: skills.filter((skill) => ["trusted", "approved"].includes(skill.security.status)).length,
    pending: skills.filter((skill) => skill.security.status === "pending").length,
    rejected: skills.filter((skill) => skill.security.status === "rejected").length,
  }), [skills]);

  const review = async (skill: DeclarativeSkill, approved: boolean) => {
    if (skill.builtIn) return;
    setReviewingId(skill.id);
    try {
      const updated = await reviewSkill(skill.id, approved);
      setSkills((current) => current.map((entry) => entry.id === updated.id ? updated : entry));
      setError("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setReviewingId(null);
    }
  };

  return (
    <section className="skills-security-panel" aria-labelledby="skills-security-title">
      <div className="section-title">
        <LockKeyhole size={17} />
        <h2 id="skills-security-title">Skills 安全审核</h2>
        <span>{counts.approved} 可用 · {counts.pending} 待审 · {counts.rejected} 拒绝</span>
        <button className="panel-refresh" type="button" title="刷新技能审核列表" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={14} />
        </button>
      </div>
      {error && <div className="skills-error"><ShieldAlert size={15} /><span>{error}</span></div>}
      <div className="skills-table-wrap">
        <table className="skills-table">
          <thead>
            <tr><th>技能</th><th>来源 / 许可</th><th>SHA-256</th><th>最小权限</th><th>审核状态</th><th>操作</th></tr>
          </thead>
          <tbody>
            {skills.map((skill) => (
              <tr key={skill.id}>
                <td><strong>{skill.name}</strong><small>{skill.id} · v{skill.manifest.version}</small></td>
                <td>
                  {skill.manifest.source.url
                    ? <a href={skill.manifest.source.url} target="_blank" rel="noreferrer">{sourceLabel(skill)}</a>
                    : <span>{sourceLabel(skill)}</span>}
                  <small>{skill.manifest.source.license ?? "未声明许可"}</small>
                </td>
                <td><code title={skill.security.sha256}>{skill.security.sha256.slice(0, 12)}…</code></td>
                <td><div className="permission-tags">{permissionSummary(skill).map((permission) => <span key={permission}>{permission}</span>)}</div></td>
                <td>
                  <span className={`security-status security-${skill.security.status}`}>
                    {skill.security.status === "trusted" || skill.security.status === "approved" ? <ShieldCheck size={13} /> : <ShieldAlert size={13} />}
                    {STATUS_TEXT[skill.security.status]}
                  </span>
                  {skill.security.findings.length > 0 && <small title={skill.security.findings.join("\n")}>{skill.security.findings.length} 项发现</small>}
                </td>
                <td>
                  <div className="review-actions">
                    <button type="button" className="approve-skill" title="批准此版本" disabled={skill.builtIn || reviewingId === skill.id} onClick={() => void review(skill, true)}><Check size={14} /></button>
                    <button type="button" className="reject-skill" title="拒绝此版本" disabled={skill.builtIn || reviewingId === skill.id} onClick={() => void review(skill, false)}><X size={14} /></button>
                  </div>
                </td>
              </tr>
            ))}
            {!loading && skills.length === 0 && <tr><td colSpan={6} className="skills-empty">暂无可审核技能</td></tr>}
            {loading && skills.length === 0 && <tr><td colSpan={6} className="skills-empty">正在读取本地审核记录…</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="security-footnote">仅显示服务端已完成静态检查的声明式技能；批准只对当前 SHA-256 版本有效，文件变化后必须重新审核。</p>
    </section>
  );
}
