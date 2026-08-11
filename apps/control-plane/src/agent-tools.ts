import {
  buildBlockSchema,
  buildImportRequestSchema,
  buildSourceSchema,
  declarativeSkillDraftSchema,
  taskSpecSchema,
  vec3Schema,
} from "@mc/protocol";
import { z } from "zod";
import type { MinecraftControlApi } from "./control-api.js";
import { assertNoSensitiveData, redactSensitiveData, redactSensitiveText } from "./skill-security.js";

interface AgentTool<T extends z.ZodType = z.ZodType> {
  description: string;
  schema: T;
  execute(input: z.infer<T>): Promise<unknown>;
}

function defineTool<T extends z.ZodType>(tool: AgentTool<T>): AgentTool<T> {
  return tool;
}

export function createAgentTools(control: MinecraftControlApi) {
  return {
    mc_list_companions: defineTool({
      description: "List connected Minecraft companion players, capabilities, leases, and current status.",
      schema: z.object({}),
      execute: async () => ({ companions: await control.listCompanions() }),
    }),
    mc_list_chat_messages: defineTool({
      description: "Read free-chat messages routed to Antigravity and current routing settings. Preserve the active agent's existing persona when replying. Every player-facing response must be sent through mc_chat because text written only in the agent conversation is invisible in Minecraft.",
      schema: z.object({
        afterSequence: z.number().int().nonnegative().default(0),
        limit: z.number().int().min(1).max(100).default(50),
      }),
      execute: async ({ afterSequence, limit }) => {
        const [settings, messages] = await Promise.all([
          control.getChatSettings(),
          control.listChatMessages(afterSequence, limit),
        ]);
        return {
          settings,
          messages,
          nextSequence: messages.at(-1)?.sequence ?? afterSequence,
          replyRequirement: "对玩家说的每一句话都必须通过 mc_chat 发送；只写在 Agent 对话中的文本不会显示在 Minecraft。",
        };
      },
    }),
    mc_list_skills: defineTool({
      description: "List built-in and learned declarative Minecraft routines and their typed parameters.",
      schema: z.object({}),
      execute: async () => ({ skills: await control.listSkills() }),
    }),
    mc_save_skill: defineTool({
      description: "Save a declarative routine made only of validated Minecraft task templates. Arbitrary code and nested macros are forbidden.",
      schema: declarativeSkillDraftSchema,
      execute: async (draft) => ({ skill: await control.saveSkill(draft) }),
    }),
    mc_delete_skill: defineTool({
      description: "Delete one learned declarative routine. Built-in routines cannot be deleted.",
      schema: z.object({ skillId: z.string().min(1) }),
      execute: async ({ skillId }) => {
        await control.removeSkill(skillId);
        return { ok: true };
      },
    }),
    mc_observe: defineTool({
      description: "Observe one companion's owner-relative position, health, inventory, recent bounded item transaction history, nearby entities, and world state. Use recentItemTransactions as evidence when explaining where materials went.",
      schema: z.object({ companionId: z.string().min(1) }),
      execute: async ({ companionId }) => ({
        companion: await control.getCompanion(companionId),
        snapshot: await control.getSnapshot(companionId),
      }),
    }),
    mc_chat: defineTool({
      description: "Send a concise chat message through one Minecraft companion.",
      schema: z.object({
        companionId: z.string().min(1),
        message: z.string().min(1).max(256),
        owner: z.string().min(1).default("codex-driver"),
      }),
      execute: async ({ companionId, message, owner }) => {
        await control.sendChat(companionId, message, owner);
        return { ok: true };
      },
    }),
    mc_control_companion: defineTool({
      description: "Summon, recall, follow, or park the visible in-world companion NPC.",
      schema: z.object({
        companionId: z.string().min(1),
        action: z.enum(["summon", "recall", "follow", "stay"]),
      }),
      execute: async ({ companionId, action }) => ({ companion: await control.controlCompanion(companionId, action) }),
    }),
    mc_assign_task: defineTool({
      description: "Queue a typed Minecraft task after observing and acquiring the companion's controller lease, then return immediately. Do not poll for completion in the same model turn; the game continues independently and reports the terminal result.",
      schema: z.object({
        companionId: z.string().min(1),
        spec: taskSpecSchema,
        owner: z.string().min(1).default("codex-driver"),
      }),
      execute: async ({ companionId, spec, owner }) => ({ task: await control.assignTask(companionId, spec, owner) }),
    }),
    mc_get_task: defineTool({
      description: "Read status, progress, result, or error details for a Minecraft task.",
      schema: z.object({ taskId: z.string().uuid() }),
      execute: async ({ taskId }) => ({ task: await control.getTask(taskId) }),
    }),
    mc_cancel_task: defineTool({
      description: "Cancel one queued or running Minecraft task.",
      schema: z.object({
        taskId: z.string().uuid(),
        reason: z.string().min(1).max(200).default("AI provider cancelled task"),
      }),
      execute: async ({ taskId, reason }) => ({ task: await control.cancelTask(taskId, reason) }),
    }),
    mc_preview_build: defineTool({
      description: "Create an unconfirmed construction preview and material list without placing blocks.",
      schema: z.object({
        name: z.string().min(1).max(120),
        source: buildSourceSchema,
        origin: vec3Schema,
        blocks: z.array(buildBlockSchema).min(1).max(250_000),
      }),
      execute: async (draft) => ({ plan: await control.previewBuild(draft) }),
    }),
    mc_import_build: defineTool({
      description: "Import uploaded JSON, Sponge .schem, Litematica .litematic, or PNG bytes into an unconfirmed construction preview. Local file paths are not exposed to AI providers.",
      schema: buildImportRequestSchema.omit({ filePath: true }),
      execute: async (request) => ({ plan: await control.importBuild(request) }),
    }),
    mc_confirm_build: defineTool({
      description: "Confirm a build plan only after the human has explicitly approved its preview.",
      schema: z.object({ planId: z.string().uuid() }),
      execute: async ({ planId }) => ({ plan: await control.confirmBuild(planId) }),
    }),
    mc_acquire_control: defineTool({
      description: "Acquire the single-writer control lease. Never force takeover without explicit human instruction.",
      schema: z.object({
        companionId: z.string().min(1),
        owner: z.string().min(1).default("codex-driver"),
        force: z.boolean().default(false),
      }),
      execute: async ({ companionId, owner, force }) => ({
        companion: await control.acquireLease(companionId, owner, force),
      }),
    }),
    mc_release_control: defineTool({
      description: "Release the single-writer control lease after completing or handing off work.",
      schema: z.object({
        companionId: z.string().min(1),
        owner: z.string().min(1).default("codex-driver"),
      }),
      execute: async ({ companionId, owner }) => ({ companion: await control.releaseLease(companionId, owner) }),
    }),
    mc_emergency_stop: defineTool({
      description: "Immediately cancel every Minecraft task and stop all companions.",
      schema: z.object({ disconnect: z.boolean().default(false) }),
      execute: async ({ disconnect }) => {
        await control.emergencyStop(disconnect);
        return { ok: true, disconnected: disconnect };
      },
    }),
  };
}

export type AgentTools = ReturnType<typeof createAgentTools>;
export type AgentToolName = keyof AgentTools;

export function agentToolDefinitions(tools: AgentTools): Array<{
  name: AgentToolName;
  description: string;
  input_schema: Record<string, unknown>;
}> {
  return (Object.entries(tools) as Array<[AgentToolName, AgentTools[AgentToolName]]>).map(([name, tool]) => {
    const converted = z.toJSONSchema(tool.schema) as Record<string, unknown>;
    delete converted.$schema;
    return { name, description: tool.description, input_schema: converted };
  });
}

export async function executeAgentTool(
  tools: AgentTools,
  name: string,
  input: unknown,
): Promise<{ ok: true; result: unknown } | { ok: false; error: { code: string; message: string; recovery?: string } }> {
  if (!(name in tools)) {
    return { ok: false, error: { code: "UNKNOWN_TOOL", message: `Unknown Minecraft tool: ${name}` } };
  }
  const tool = tools[name as AgentToolName] as AgentTool;
  try {
    const parsed = tool.schema.parse(input);
    assertNoSensitiveData(parsed, `${name} arguments`);
    return { ok: true, result: redactSensitiveData(await tool.execute(parsed)) };
  } catch (caught) {
    const error = caught instanceof Error ? caught : new Error(String(caught));
    const code = "code" in error ? String((error as Error & { code?: string }).code ?? "TOOL_ERROR") : "TOOL_ERROR";
    const recovery = "suggestedRecovery" in error
      ? (error as Error & { suggestedRecovery?: string }).suggestedRecovery
      : undefined;
    return {
      ok: false,
      error: {
        code,
        message: redactSensitiveText(error.message),
        ...(recovery ? { recovery: redactSensitiveText(recovery) } : {}),
      },
    };
  }
}
