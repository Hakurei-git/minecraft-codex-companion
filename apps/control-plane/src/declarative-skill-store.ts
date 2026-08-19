import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import path from "node:path";
import {
  declarativeSkillDraftSchema,
  declarativeSkillSchema,
  taskSpecSchema,
  type DeclarativeSkill,
  type DeclarativeSkillDraft,
  type SkillParameter,
  type TaskSpec,
} from "@mc/protocol";
import { z } from "zod";
import { ControlError } from "./errors.js";
import { assertNoSensitiveData, auditSkillDraft } from "./skill-security.js";
import {
  ADDITIONAL_BUILTIN_SKILL_DRAFTS,
  BUILTIN_CONTENT_AUTHOR,
  BUILTIN_CONTENT_LICENSE,
  BUILTIN_CONTENT_VERSION,
} from "./builtin-content.js";

const BUILTIN_AT = "2026-07-30T00:00:00.000Z";
const PLACEHOLDER = /\$\{([A-Za-z][A-Za-z0-9_]{0,47})\}/g;
const EXACT_PLACEHOLDER = /^\$\{([A-Za-z][A-Za-z0-9_]{0,47})\}$/;

const BUILTIN_DRAFTS: unknown[] = [
  {
    id: "life.crop-cycle",
    name: "农田日常",
    description: "收获、补种指定作物并把收获物存入附近容器。",
    parameters: [
      { name: "cropId", description: "作物物品 ID", type: "string", required: false, defaultValue: "minecraft:wheat" },
      { name: "radius", description: "农务半径", type: "integer", required: false, defaultValue: 12, minimum: 1, maximum: 64 },
    ],
    steps: [
      { label: "照料并补种已有室外农田", task: { kind: "farm", cropId: "${cropId}", action: "plant", radius: "${radius}" } },
      { label: "整理收获", task: { kind: "store", itemId: "${cropId}" } },
    ],
  },
  {
    id: "life.mining-run",
    name: "采集补给",
    description: "按指定数量采集一种资源，适合木材、石料和矿物补给。",
    parameters: [
      { name: "itemId", description: "目标方块或物品 ID", type: "string" },
      { name: "count", description: "采集数量", type: "integer", required: false, defaultValue: 32, minimum: 1, maximum: 4096 },
    ],
    steps: [
      { label: "采集资源", task: { kind: "gather", itemId: "${itemId}", count: "${count}" } },
    ],
  },
  {
    id: "life.expedition-and-deliver",
    name: "远征采集并返回交付",
    description: "持续扩大搜索范围直至采够目标；缺少必要矿具时先按真实配方补齐，护主后恢复。完成后返回指定玩家并物理交付。开启作弊时仅在远距或寻路恢复条件下安全传送，未开启作弊时全程走路。",
    parameters: [
      { name: "itemId", description: "目标物品 ID 或物品标签", type: "string" },
      { name: "count", description: "远征采集和交付数量", type: "integer", required: false, defaultValue: 16, minimum: 1, maximum: 4096 },
      { name: "player", description: "远征结束后接收物品的玩家名", type: "string" },
    ],
    steps: [
      { label: "远征采集资源", task: { kind: "gather", itemId: "${itemId}", count: "${count}" } },
      { label: "返回并交付玩家", task: { kind: "deliver", itemId: "${itemId}", count: "${count}", player: "${player}" } },
    ],
  },
  {
    id: "life.gather-and-deliver",
    name: "采集并交付",
    description: "按指定数量采集资源，并把成果交给指定玩家。",
    parameters: [
      { name: "itemId", description: "目标物品 ID 或物品标签", type: "string" },
      { name: "count", description: "采集和交付数量", type: "integer", required: false, defaultValue: 8, minimum: 1, maximum: 4096 },
      { name: "player", description: "接收物品的玩家名", type: "string" },
      { name: "movement", description: "采集移动策略；walk 禁止远程传送恢复", type: "string", required: false, defaultValue: "auto", enumValues: ["auto", "walk"] },
    ],
    steps: [
      { label: "采集资源", task: { kind: "gather", itemId: "${itemId}", count: "${count}", movement: "${movement}" } },
      { label: "交付玩家", task: { kind: "deliver", itemId: "${itemId}", count: "${count}", player: "${player}" } },
    ],
  },
  {
    id: "life.retrieve-and-deliver",
    name: "从家园仓库取物并交付",
    description: "从家或复活点附近的仓库取出指定物品，再走到指定玩家身边物理交付。",
    parameters: [
      { name: "itemId", description: "目标物品 ID 或物品标签", type: "string" },
      { name: "count", description: "取出和交付数量", type: "integer", required: false, defaultValue: 1, minimum: 1, maximum: 4096 },
      { name: "player", description: "接收物品的玩家名", type: "string" },
    ],
    steps: [
      { label: "从家园仓库取物", task: { kind: "retrieve", itemId: "${itemId}", count: "${count}" } },
      { label: "交付玩家", task: { kind: "deliver", itemId: "${itemId}", count: "${count}", player: "${player}" } },
    ],
  },
  {
    id: "life.smelt-and-store",
    name: "烧炼并入库",
    description: "烧炼指定原料，然后把产物放入附近容器。",
    parameters: [
      { name: "inputId", description: "烧炼原料物品 ID", type: "string" },
      { name: "outputId", description: "产物物品 ID", type: "string" },
      { name: "count", description: "烧炼数量", type: "integer", required: false, defaultValue: 8, minimum: 1, maximum: 256 },
    ],
    steps: [
      { label: "烧炼物品", task: { kind: "smelt", itemId: "${inputId}", count: "${count}" } },
      { label: "存放产物", task: { kind: "store", itemId: "${outputId}", count: "${count}" } },
    ],
  },
  {
    id: "dragon.daily-care",
    name: "龙类日常照料",
    description: "先观察目标龙的状态，再靠近并进行一次喂养。",
    parameters: [
      { name: "targetId", description: "龙实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [
      { label: "观察龙类", task: { kind: "dragon", action: "observe", targetId: "${targetId}" } },
      { label: "喂养龙类", task: { kind: "dragon", action: "feed", targetId: "${targetId}" } },
    ],
  },
  {
    id: "dragon.mount-and-follow",
    name: "骑龙跟随",
    description: "寻找玩家拥有的目标龙，骑乘后让龙持续跟随主人。",
    parameters: [
      { name: "targetId", description: "龙实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [
      { label: "骑乘目标龙", task: { kind: "dragon", action: "mount", targetId: "${targetId}" } },
      { label: "切换骑龙跟随", task: { kind: "dragon", action: "follow", targetId: "${targetId}" } },
    ],
  },
  {
    id: "dragon.shared-ride",
    name: "玩家与 NPC 共骑",
    description: "把玩家放在前座、NPC 放在后座，同骑兼容的 Book of Dragons 或 Saints Dragons 龙；下龙前优先安全降落。",
    parameters: [
      { name: "targetId", description: "龙实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [
      { label: "观察可共骑龙", task: { kind: "dragon", action: "observe", targetId: "${targetId}" } },
      { label: "建立共骑座位", task: { kind: "dragon", action: "share-ride", targetId: "${targetId}" } },
      { label: "切换骑龙跟随", task: { kind: "dragon", action: "follow", targetId: "${targetId}" } },
    ],
  },
  {
    id: "dragon.assist-combat",
    name: "骑龙协战",
    description: "让玩家拥有的龙协助攻击玩家最近交战的有效目标。",
    parameters: [
      { name: "targetId", description: "龙实体 UUID；留空时选择最近目标", type: "string", required: false, defaultValue: "" },
    ],
    steps: [
      { label: "指挥龙协战", task: { kind: "dragon", action: "assist-combat", targetId: "${targetId}" } },
    ],
  },
  {
    id: "combat.guard-owner",
    name: "玩家护卫",
    description: "进入持续护卫模式并在指定半径内处理敌对生物。",
    parameters: [
      { name: "player", description: "需要护卫的玩家名", type: "string" },
      { name: "radius", description: "护卫半径", type: "number", required: false, defaultValue: 12, minimum: 2, maximum: 64 },
    ],
    steps: [
      { label: "开始护卫", task: { kind: "guard", player: "${player}", radius: "${radius}" } },
    ],
  },
  ...ADDITIONAL_BUILTIN_SKILL_DRAFTS,
];

const persistedStateSchema = z.object({
  version: z.union([z.literal(1), z.literal(2)]),
  skills: z.array(z.unknown()),
});

function validateParameterValue(parameter: SkillParameter, value: unknown): unknown {
  const validType = parameter.type === "string"
    ? typeof value === "string"
    : parameter.type === "boolean"
      ? typeof value === "boolean"
      : parameter.type === "integer"
        ? typeof value === "number" && Number.isInteger(value)
        : typeof value === "number" && Number.isFinite(value);
  if (!validType) {
    throw new ControlError({
      code: "SKILL_ARGUMENT_TYPE",
      message: `技能参数 ${parameter.name} 必须是 ${parameter.type}`,
      statusCode: 400,
    });
  }
  if (typeof value === "number") {
    if (parameter.minimum !== undefined && value < parameter.minimum) {
      throw new ControlError({ code: "SKILL_ARGUMENT_RANGE", message: `${parameter.name} 不能小于 ${parameter.minimum}`, statusCode: 400 });
    }
    if (parameter.maximum !== undefined && value > parameter.maximum) {
      throw new ControlError({ code: "SKILL_ARGUMENT_RANGE", message: `${parameter.name} 不能大于 ${parameter.maximum}`, statusCode: 400 });
    }
  }
  if (parameter.enumValues && !parameter.enumValues.some((candidate) => candidate === value)) {
    throw new ControlError({ code: "SKILL_ARGUMENT_ENUM", message: `${parameter.name} 不在允许值中`, statusCode: 400 });
  }
  return value;
}

function parameterSamples(parameters: SkillParameter[]): Record<string, unknown> {
  return Object.fromEntries(parameters.map((parameter) => {
    if (parameter.defaultValue !== undefined) return [parameter.name, validateParameterValue(parameter, parameter.defaultValue)];
    if (parameter.enumValues?.length) return [parameter.name, validateParameterValue(parameter, parameter.enumValues[0])];
    if (parameter.type === "string") return [parameter.name, "minecraft:stone"];
    if (parameter.type === "boolean") return [parameter.name, false];
    return [parameter.name, parameter.minimum ?? 1];
  }));
}

function renderTemplate(value: unknown, argumentsByName: Record<string, unknown>): unknown {
  if (typeof value === "string") {
    const exact = EXACT_PLACEHOLDER.exec(value);
    if (exact) return argumentsByName[exact[1]!];
    return value.replace(PLACEHOLDER, (_match, name: string) => {
      const replacement = argumentsByName[name];
      if (replacement === undefined) throw new Error(`Missing optional parameter ${name} used inside text`);
      return String(replacement);
    });
  }
  if (Array.isArray(value)) return value.map((item) => renderTemplate(item, argumentsByName));
  if (value && typeof value === "object") {
    const rendered: Record<string, unknown> = {};
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      const next = renderTemplate(child, argumentsByName);
      if (next !== undefined) rendered[key] = next;
    }
    return rendered;
  }
  return value;
}

function normalizeDraft(input: DeclarativeSkillDraft): DeclarativeSkillDraft {
  const draft = declarativeSkillDraftSchema.parse(input);
  const names = new Set<string>();
  for (const parameter of draft.parameters) {
    if (names.has(parameter.name)) {
      throw new ControlError({ code: "SKILL_PARAMETER_DUPLICATE", message: `技能参数重复：${parameter.name}`, statusCode: 400 });
    }
    names.add(parameter.name);
    if (parameter.minimum !== undefined && parameter.maximum !== undefined && parameter.minimum > parameter.maximum) {
      throw new ControlError({ code: "SKILL_PARAMETER_RANGE", message: `${parameter.name} 的最小值大于最大值`, statusCode: 400 });
    }
    if (parameter.defaultValue !== undefined) validateParameterValue(parameter, parameter.defaultValue);
  }

  const sample = parameterSamples(draft.parameters);
  for (const step of draft.steps) {
    const serialized = JSON.stringify(step.task);
    for (const match of serialized.matchAll(PLACEHOLDER)) {
      if (!names.has(match[1]!)) {
        throw new ControlError({ code: "SKILL_PARAMETER_UNKNOWN", message: `步骤 ${step.label} 使用了未知参数 ${match[1]}`, statusCode: 400 });
      }
    }
    try {
      taskSpecSchema.parse(renderTemplate(step.task, sample));
    } catch (caught) {
      const message = caught instanceof Error ? caught.message : String(caught);
      throw new ControlError({ code: "SKILL_TASK_INVALID", message: `步骤 ${step.label} 无效：${message}`, statusCode: 400 });
    }
  }
  return draft;
}

function builtInSkills(): DeclarativeSkill[] {
  return BUILTIN_DRAFTS.map((input) => {
    const draft = normalizeDraft(declarativeSkillDraftSchema.parse({
      ...(input as Record<string, unknown>),
      manifest: {
        version: BUILTIN_CONTENT_VERSION,
        source: { kind: "built-in", author: BUILTIN_CONTENT_AUTHOR, license: BUILTIN_CONTENT_LICENSE },
        permissions: {
          tools: ["mc_assign_task"],
          network: "none",
          allowedHosts: [],
          fileAccess: "none",
          systemCommands: false,
        },
      },
    }));
    const audit = auditSkillDraft(draft, { builtIn: true });
    if (audit.security.status !== "trusted") {
      throw new Error(`Built-in skill ${draft.id} failed security audit: ${audit.security.findings.join("; ")}`);
    }
    return {
      ...draft,
      ...audit,
      builtIn: true,
      createdAt: BUILTIN_AT,
      updatedAt: BUILTIN_AT,
    };
  });
}

export class DeclarativeSkillStore {
  readonly #statePath: string | null;
  readonly #builtIns = new Map(builtInSkills().map((skill) => [skill.id, skill]));
  readonly #custom = new Map<string, DeclarativeSkill>();

  constructor(stateDirectory?: string) {
    this.#statePath = stateDirectory ? path.join(stateDirectory, "declarative-skills.json") : null;
    this.#load();
  }

  list(): DeclarativeSkill[] {
    return structuredClone([...this.#builtIns.values(), ...this.#custom.values()]);
  }

  get(id: string): DeclarativeSkill {
    const skill = this.#builtIns.get(id) ?? this.#custom.get(id);
    if (!skill) throw new ControlError({ code: "SKILL_NOT_FOUND", message: `找不到声明式技能 ${id}`, statusCode: 404 });
    return structuredClone(skill);
  }

  save(input: DeclarativeSkillDraft): DeclarativeSkill {
    const draft = normalizeDraft(input);
    if (this.#builtIns.has(draft.id)) {
      throw new ControlError({ code: "SKILL_BUILT_IN", message: "内置技能不能覆盖", statusCode: 409 });
    }
    if (draft.manifest?.source.kind === "built-in") {
      throw new ControlError({ code: "SKILL_SOURCE_INVALID", message: "Custom skills cannot claim built-in trust", statusCode: 400 });
    }
    assertNoSensitiveData(draft, "skill");
    const audit = auditSkillDraft(draft);
    if (audit.security.status === "rejected") {
      throw new ControlError({
        code: "SKILL_SECURITY_REJECTED",
        message: `Skill failed security review: ${audit.security.findings.join("; ")}`,
        statusCode: 400,
      });
    }
    const previous = this.#custom.get(draft.id);
    const now = new Date().toISOString();
    const skill: DeclarativeSkill = {
      ...draft,
      ...audit,
      builtIn: false,
      createdAt: previous?.createdAt ?? now,
      updatedAt: now,
    };
    this.#custom.set(skill.id, skill);
    this.#persist();
    return structuredClone(skill);
  }

  remove(id: string): void {
    if (this.#builtIns.has(id)) throw new ControlError({ code: "SKILL_BUILT_IN", message: "内置技能不能删除", statusCode: 409 });
    if (!this.#custom.delete(id)) throw new ControlError({ code: "SKILL_NOT_FOUND", message: `找不到声明式技能 ${id}`, statusCode: 404 });
    this.#persist();
  }

  review(id: string, approved: boolean): DeclarativeSkill {
    if (this.#builtIns.has(id)) throw new ControlError({ code: "SKILL_BUILT_IN", message: "Built-in skills are already trusted", statusCode: 409 });
    const current = this.#custom.get(id);
    if (!current) throw new ControlError({ code: "SKILL_NOT_FOUND", message: `Skill not found: ${id}`, statusCode: 404 });
    const audit = auditSkillDraft(current, { explicitlyApproved: approved });
    const now = new Date().toISOString();
    const reviewed: DeclarativeSkill = {
      ...current,
      ...audit,
      security: approved
        ? audit.security
        : { ...audit.security, status: "rejected", reviewedAt: now, findings: [...audit.security.findings, "Rejected by local user"] },
      updatedAt: now,
    };
    this.#custom.set(id, reviewed);
    this.#persist();
    return structuredClone(reviewed);
  }

  resolve(id: string, supplied: Record<string, unknown>): Array<{
    label: string;
    whenMaterialMode: "always" | "survival" | "creative";
    task: TaskSpec;
  }> {
    const skill = this.get(id);
    if (skill.security.status !== "trusted" && skill.security.status !== "approved") {
      throw new ControlError({
        code: "SKILL_NOT_APPROVED",
        message: `Skill ${id} cannot run while security status is ${skill.security.status}`,
        statusCode: 403,
      });
    }
    assertNoSensitiveData(supplied, "skill arguments");
    const parameters = new Map(skill.parameters.map((parameter) => [parameter.name, parameter]));
    for (const name of Object.keys(supplied)) {
      if (!parameters.has(name)) throw new ControlError({ code: "SKILL_ARGUMENT_UNKNOWN", message: `技能 ${id} 没有参数 ${name}`, statusCode: 400 });
    }
    const values: Record<string, unknown> = {};
    for (const parameter of skill.parameters) {
      const hasValue = Object.prototype.hasOwnProperty.call(supplied, parameter.name);
      const value = hasValue ? supplied[parameter.name] : parameter.defaultValue;
      if (value === undefined) {
        if (parameter.required) throw new ControlError({ code: "SKILL_ARGUMENT_REQUIRED", message: `缺少技能参数 ${parameter.name}`, statusCode: 400 });
        continue;
      }
      values[parameter.name] = validateParameterValue(parameter, value);
    }
    return skill.steps.map((step) => ({
      label: step.label,
      whenMaterialMode: step.whenMaterialMode ?? "always",
      task: taskSpecSchema.parse(renderTemplate(step.task, values)),
    }));
  }

  #load(): void {
    if (!this.#statePath || !existsSync(this.#statePath)) return;
    const state = persistedStateSchema.parse(JSON.parse(readFileSync(this.#statePath, "utf8")));
    for (const raw of state.skills) {
      const parsedCurrent = declarativeSkillSchema.safeParse(raw);
      const rawRecord = raw && typeof raw === "object" ? raw as Record<string, unknown> : {};
      const draftResult = declarativeSkillDraftSchema.safeParse(raw);
      if (!draftResult.success) continue;
      if (rawRecord.builtIn || this.#builtIns.has(draftResult.data.id)) continue;
      const draft = normalizeDraft(draftResult.data);
      const firstAudit = auditSkillDraft(draft);
      const matchingStoredReview = parsedCurrent.success
        && parsedCurrent.data.security.sha256 === firstAudit.security.sha256
        ? parsedCurrent.data.security
        : null;
      const audit = matchingStoredReview?.status === "approved"
        ? auditSkillDraft(draft, { explicitlyApproved: true })
        : matchingStoredReview?.status === "rejected"
          ? {
              ...firstAudit,
              security: {
                ...firstAudit.security,
                status: "rejected" as const,
                reviewedAt: matchingStoredReview.reviewedAt,
                findings: [...new Set([...firstAudit.security.findings, ...matchingStoredReview.findings])].slice(0, 64),
              },
            }
          : firstAudit;
      if (audit.security.status === "rejected" && firstAudit.security.findings.length > 0) continue;
      this.#custom.set(draft.id, {
        ...draft,
        ...audit,
        builtIn: false,
        createdAt: typeof rawRecord.createdAt === "string" ? rawRecord.createdAt : new Date().toISOString(),
        updatedAt: typeof rawRecord.updatedAt === "string" ? rawRecord.updatedAt : new Date().toISOString(),
      });
    }
  }

  #persist(): void {
    if (!this.#statePath) return;
    mkdirSync(path.dirname(this.#statePath), { recursive: true });
    const temporary = `${this.#statePath}.${process.pid}.tmp`;
    writeFileSync(temporary, `${JSON.stringify({ version: 2, skills: [...this.#custom.values()] }, null, 2)}\n`, { encoding: "utf8", mode: 0o600 });
    renameSync(temporary, this.#statePath);
  }
}
