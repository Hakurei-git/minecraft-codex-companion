import {
  aiTaskDecisionResultSchema,
  aiTaskDecisionSchema,
  buildBlockSchema,
  buildImportRequestSchema,
  buildPlanSchema,
  buildSourceSchema,
  chatMessageSchema,
  chatSettingsSchema,
  companionActionSchema,
  companionSchema,
  declarativeSkillDraftSchema,
  declarativeSkillSchema,
  taskRecordSchema,
  taskSpecSchema,
  vec3Schema,
  worldSnapshotSchema,
} from "@mc/protocol";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import type { MinecraftControlApi } from "./control-api.js";
import { ControlError } from "./errors.js";
import { assertNoSensitiveData, redactSensitiveData, redactSensitiveText } from "./skill-security.js";

const READ_ONLY = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
} as const;

const MUTATING = {
  readOnlyHint: false,
  destructiveHint: false,
  idempotentHint: false,
  openWorldHint: false,
} as const;

const DESTRUCTIVE = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: true,
  openWorldHint: false,
} as const;

function success(structuredContent: Record<string, unknown>, summary: string) {
  const safeContent = redactSensitiveData(structuredContent) as Record<string, unknown>;
  return {
    content: [{ type: "text" as const, text: redactSensitiveText(summary) }],
    structuredContent: safeContent,
  };
}

async function handleTool(run: () => Promise<ReturnType<typeof success>>) {
  try {
    return await run();
  } catch (caught) {
    const error = caught instanceof Error ? caught : new Error(String(caught));
    const code = caught instanceof ControlError || "code" in error
      ? String((error as Error & { code?: string }).code ?? "CONTROL_ERROR")
      : "CONTROL_ERROR";
    const recovery = "suggestedRecovery" in error
      ? (error as Error & { suggestedRecovery?: string }).suggestedRecovery
      : undefined;
    return {
      isError: true as const,
      content: [{
        type: "text" as const,
        text: redactSensitiveText([code, error.message, recovery].filter(Boolean).join(": ")),
      }],
    };
  }
}

async function waitForChatMessages(
  control: MinecraftControlApi,
  afterSequence: number,
  limit: number,
  waitSeconds: number,
) {
  const deadline = Date.now() + waitSeconds * 1_000;
  for (;;) {
    const messages = await control.listChatMessages(afterSequence, limit);
    if (messages.length > 0 || Date.now() >= deadline) return messages;
    await new Promise((resolve) => setTimeout(resolve, Math.min(250, Math.max(1, deadline - Date.now()))));
  }
}

export function createMinecraftMcpServer(control: MinecraftControlApi): McpServer {
  const server = new McpServer({
    name: "minecraft-codex-companion",
    version: "0.1.5",
  });

  server.registerTool("mc_list_companions", {
    title: "List Minecraft companions",
    description: "List connected companion players, capabilities, controller leases, and current status.",
    outputSchema: { companions: z.array(companionSchema) },
    annotations: READ_ONLY,
  }, async () => handleTool(async () => {
    const companions = await control.listCompanions();
    return success({ companions }, `找到 ${companions.length} 个 Minecraft 陪玩角色。`);
  }));

  server.registerTool("mc_list_chat_messages", {
    title: "Read Antigravity Minecraft chat inbox",
    description: "Read new free-chat messages routed to Antigravity, together with the current routing and optional persona-overlay settings. Preserve the current Antigravity agent persona when persona.mode is inherit; apply custom fields only as a Minecraft-specific overlay. Use waitSeconds for a live companion loop. Every player-facing response, including persona dialogue and the final answer, MUST be sent with mc_chat; text written only in the Antigravity conversation is invisible in Minecraft.",
    inputSchema: {
      afterSequence: z.number().int().nonnegative().default(0),
      limit: z.number().int().min(1).max(100).default(50),
      waitSeconds: z.number().int().min(0).max(30).default(0),
    },
    outputSchema: {
      settings: chatSettingsSchema,
      messages: z.array(chatMessageSchema),
      nextSequence: z.number().int().nonnegative(),
      replyRequirement: z.string(),
    },
    annotations: READ_ONLY,
  }, async ({ afterSequence, limit, waitSeconds }) => handleTool(async () => {
    const [settings, messages] = await Promise.all([
      control.getChatSettings(),
      waitForChatMessages(control, afterSequence, limit, waitSeconds),
    ]);
    const nextSequence = messages.at(-1)?.sequence ?? afterSequence;
    const replyRequirement = "对玩家说的每一句话都必须通过 mc_chat 发送；只写在反重力窗口中的文本不会显示在 Minecraft。";
    return success(
      { settings, messages, nextSequence, replyRequirement },
      messages.length > 0
        ? `收到 ${messages.length} 条新的 Minecraft 聊天。${replyRequirement}`
        : `当前没有新的 Minecraft 聊天。${replyRequirement}`,
    );
  }));

  server.registerTool("mc_list_skills", {
    title: "List declarative Minecraft skills",
    description: "List built-in and learned routines, their parameters, and validated task steps.",
    outputSchema: { skills: z.array(declarativeSkillSchema) },
    annotations: READ_ONLY,
  }, async () => handleTool(async () => {
    const skills = await control.listSkills();
    return success({ skills }, `找到 ${skills.length} 个声明式技能。`);
  }));

  server.registerTool("mc_list_build_plans", {
    title: "List audited Minecraft build plans",
    description: "List reusable build plans with material counts, source/license, SHA-256, and the complete no-network/no-command permission manifest. Built-in plans are already locally audited and confirmed.",
    outputSchema: { plans: z.array(buildPlanSchema) },
    annotations: READ_ONLY,
  }, async () => handleTool(async () => {
    const plans = await control.listBuildPlans();
    return success({ plans }, `找到 ${plans.length} 个建筑计划；每个计划均附带来源、许可、哈希和权限清单。`);
  }));

  server.registerTool("mc_save_skill", {
    title: "Save declarative Minecraft skill",
    description: "Create or update a learned routine made only from typed Minecraft task templates. Arbitrary code and nested macros are rejected.",
    inputSchema: declarativeSkillDraftSchema.shape,
    outputSchema: { skill: declarativeSkillSchema },
    annotations: MUTATING,
  }, async (draft) => handleTool(async () => {
    assertNoSensitiveData(draft, "mc_save_skill arguments");
    const skill = await control.saveSkill(draft);
    return success({ skill }, `声明式技能 ${skill.name} 已保存。`);
  }));

  server.registerTool("mc_delete_skill", {
    title: "Delete learned Minecraft skill",
    description: "Delete a learned declarative routine. Built-in routines cannot be removed.",
    inputSchema: { skillId: z.string().min(1) },
    outputSchema: { ok: z.boolean() },
    annotations: DESTRUCTIVE,
  }, async ({ skillId }) => handleTool(async () => {
    await control.removeSkill(skillId);
    return success({ ok: true }, `声明式技能 ${skillId} 已删除。`);
  }));

  server.registerTool("mc_observe", {
    title: "Observe Minecraft world",
    description: "Read one companion's position relative to its owner, health, inventory, recent bounded item transaction history, nearby entities, and world state before planning an action. Use recentItemTransactions to explain where an item went; never claim it was crafted, consumed, stored, delivered, or dropped without matching evidence.",
    inputSchema: { companionId: z.string().min(1) },
    outputSchema: { companion: companionSchema, snapshot: worldSnapshotSchema },
    annotations: READ_ONLY,
  }, async ({ companionId }) => handleTool(async () => {
    const [companion, snapshot] = await Promise.all([
      control.getCompanion(companionId),
      control.getSnapshot(companionId),
    ]);
    return success({ companion, snapshot }, `${companion.name} 当前位于 ${snapshot.position.x}, ${snapshot.position.y}, ${snapshot.position.z}。`);
  }));

  server.registerTool("mc_chat", {
    title: "Send Minecraft chat",
    description: "Send one short valid-Unicode chat message through a companion player. For an Antigravity-routed inbound message, pass its prompt-provided interactionId and phase. Duplicate phase=start replies for the same interaction are coalesced, while progress, terminal, and ordinary chat remain distinct. Match the player's current language and default to concise Simplified Chinese only when unclear. If INVALID_GAME_CHAT_TEXT is returned, regenerate the same reply once with valid Unicode and retry once; never send diagnostic text into Minecraft.",
    inputSchema: {
      companionId: z.string().min(1),
      message: z.string().min(1).max(256),
      owner: z.string().min(1).default("codex"),
      interactionId: z.string().trim().min(1).max(128).optional(),
      phase: z.enum(["start", "progress", "terminal", "chat"]).optional(),
    },
    outputSchema: { ok: z.boolean() },
    annotations: MUTATING,
  }, async ({ companionId, message, owner, interactionId, phase }) => handleTool(async () => {
    await control.sendChat(companionId, message, owner, {
      ...(interactionId ? { interactionId } : {}),
      ...(phase ? { phase } : {}),
    });
    return success({ ok: true }, "聊天消息已发送。 ");
  }));

  server.registerTool("mc_submit_ai_decision", {
    title: "Submit one bounded Minecraft AI decision",
    description: "Submit exactly one structured decision for the prompt-provided interactionId. The local service binds the real player, NPC, controller owner, validates the task or installed skill, executes at most one root action, and sends the reply to Minecraft. Use this instead of mc_chat, mc_assign_task, or mc_control_companion for a smart-mode Antigravity turn.",
    inputSchema: {
      interactionId: z.string().trim().min(1).max(128),
      decision: aiTaskDecisionSchema,
    },
    outputSchema: aiTaskDecisionResultSchema.shape,
    annotations: MUTATING,
  }, async ({ interactionId, decision }) => handleTool(async () => {
    const result = await control.submitAiDecision(interactionId, decision);
    return success(result, result.taskId
      ? `智能决策已提交，任务 ${result.taskId} 已加入队列。`
      : `智能决策已提交：${result.decisionType}。`);
  }));

  server.registerTool("mc_control_companion", {
    title: "Control in-world companion NPC",
    description: "Summon the visible companion, recall it to its owner, or switch between following and staying in place.",
    inputSchema: {
      companionId: z.string().min(1),
      action: companionActionSchema,
    },
    outputSchema: { companion: companionSchema },
    annotations: MUTATING,
  }, async ({ companionId, action }) => handleTool(async () => {
    const companion = await control.controlCompanion(companionId, action);
    return success({ companion }, `${companion.name} 已执行 NPC 控制动作：${action}。`);
  }));

  server.registerTool("mc_assign_task", {
    title: "Assign Minecraft task",
    description: "Queue a typed Minecraft task for one companion and return immediately. Acquire its controller lease first and observe the world before risky actions. Do not poll mc_get_task in the same turn: the game keeps running independently and sends the terminal result back to Minecraft chat.",
    inputSchema: {
      companionId: z.string().min(1),
      spec: taskSpecSchema,
      owner: z.string().min(1).default("codex"),
    },
    outputSchema: { task: taskRecordSchema },
    annotations: MUTATING,
  }, async ({ companionId, spec, owner }) => handleTool(async () => {
    const task = await control.assignTask(companionId, spec, owner);
    return success({ task }, `任务 ${task.id} 已加入队列：${task.spec.kind}。`);
  }));

  server.registerTool("mc_get_task", {
    title: "Get Minecraft task",
    description: "Read the current status, progress, result, or structured error for a task.",
    inputSchema: { taskId: z.string().uuid() },
    outputSchema: { task: taskRecordSchema },
    annotations: READ_ONLY,
  }, async ({ taskId }) => handleTool(async () => {
    const task = await control.getTask(taskId);
    return success({ task }, `任务 ${task.id}: ${task.status}，${Math.round(task.progress * 100)}%。`);
  }));

  server.registerTool("mc_cancel_task", {
    title: "Cancel Minecraft task",
    description: "Cancel one queued or running task without disconnecting the companion.",
    inputSchema: {
      taskId: z.string().uuid(),
      reason: z.string().min(1).max(200).default("Codex 取消任务"),
    },
    outputSchema: { task: taskRecordSchema },
    annotations: DESTRUCTIVE,
  }, async ({ taskId, reason }) => handleTool(async () => {
    const task = await control.cancelTask(taskId, reason);
    return success({ task }, `任务 ${task.id} 已取消。`);
  }));

  server.registerTool("mc_preview_build", {
    title: "Preview Minecraft build",
    description: "Create an unconfirmed build preview from normalized blocks and calculate its bounds and material list. This does not place blocks.",
    inputSchema: {
      name: z.string().min(1).max(120),
      source: buildSourceSchema,
      origin: vec3Schema,
      blocks: z.array(buildBlockSchema).min(1).max(250_000),
    },
    outputSchema: { plan: buildPlanSchema },
    annotations: { ...READ_ONLY, idempotentHint: false },
  }, async ({ name, source, origin, blocks }) => handleTool(async () => {
    const plan = await control.previewBuild({ name, source, origin, blocks });
    return success({ plan }, `已生成建筑预览 ${plan.name}，共 ${plan.blocks.length} 方块，尚未确认。`);
  }));

  server.registerTool("mc_import_build", {
    title: "Import Minecraft build file",
    description: "Parse uploaded JSON, Sponge .schem, Litematica .litematic, or PNG bytes into an unconfirmed build preview. MCP callers must use dataBase64; local file paths are deliberately unavailable.",
    inputSchema: buildImportRequestSchema.omit({ filePath: true }).shape,
    outputSchema: { plan: buildPlanSchema },
    annotations: { ...READ_ONLY, idempotentHint: false },
  }, async (request) => handleTool(async () => {
    const plan = await control.importBuild(request);
    return success({ plan }, `Imported ${plan.name}: ${plan.blocks.length} blocks; confirmation is still required.`);
  }));

  server.registerTool("mc_confirm_build", {
    title: "Confirm Minecraft build",
    description: "Confirm a previously reviewed build plan so it may be assigned. Confirmation alone does not start construction.",
    inputSchema: { planId: z.string().uuid() },
    outputSchema: { plan: buildPlanSchema },
    annotations: MUTATING,
  }, async ({ planId }) => handleTool(async () => {
    const plan = await control.confirmBuild(planId);
    return success({ plan }, `建筑计划 ${plan.name} 已确认，可以开始建造。`);
  }));

  server.registerTool("mc_acquire_control", {
    title: "Acquire companion control",
    description: "Acquire the single-writer controller lease for one companion. Do not force takeover unless the user explicitly asks.",
    inputSchema: {
      companionId: z.string().min(1),
      owner: z.string().min(1).default("codex"),
      force: z.boolean().default(false),
    },
    outputSchema: { companion: companionSchema },
    annotations: MUTATING,
  }, async ({ companionId, owner, force }) => handleTool(async () => {
    const companion = await control.acquireLease(companionId, owner, force);
    return success({ companion }, `${owner} 已取得 ${companion.name} 的控制权。`);
  }));

  server.registerTool("mc_release_control", {
    title: "Release companion control",
    description: "Release a companion's controller lease after completing or handing off work.",
    inputSchema: {
      companionId: z.string().min(1),
      owner: z.string().min(1).default("codex"),
    },
    outputSchema: { companion: companionSchema },
    annotations: { ...MUTATING, idempotentHint: true },
  }, async ({ companionId, owner }) => handleTool(async () => {
    const companion = await control.releaseLease(companionId, owner);
    return success({ companion }, `${owner} 已释放 ${companion.name} 的控制权。`);
  }));

  server.registerTool("mc_emergency_stop", {
    title: "Emergency stop Minecraft companions",
    description: "Immediately cancel every queued/running task and stop all companions. Use for direct stop commands or unsafe conditions.",
    inputSchema: { disconnect: z.boolean().default(false) },
    outputSchema: { ok: z.boolean(), disconnected: z.boolean() },
    annotations: DESTRUCTIVE,
  }, async ({ disconnect }) => handleTool(async () => {
    await control.emergencyStop(disconnect);
    return success({ ok: true, disconnected: disconnect }, disconnect ? "所有角色已急停并断开。" : "所有角色已急停。 ");
  }));

  return server;
}
