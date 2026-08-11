import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import { afterEach, describe, expect, it } from "vitest";
import { BUILTIN_BUILD_IDS } from "./builtin-content.js";
import { ControlService } from "./control-service.js";
import { createMinecraftMcpServer } from "./mcp-server.js";
import { SimulatorBackend } from "./simulator-backend.js";

const clients: Client[] = [];

afterEach(async () => {
  await Promise.all(clients.splice(0).map((client) => client.close()));
});

async function createClient(service: ControlService): Promise<Client> {
  const server = createMinecraftMcpServer(service);
  const client = new Client({ name: "mcp-test", version: "1.0.0" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await Promise.all([
    server.connect(serverTransport as unknown as Transport),
    client.connect(clientTransport as unknown as Transport),
  ]);
  clients.push(client);
  return client;
}

describe("Minecraft MCP server", () => {
  it("exposes the agreed tool contract", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const client = await createClient(service);
    const result = await client.listTools();

    expect(result.tools.map((tool) => tool.name).sort()).toEqual([
      "mc_acquire_control",
      "mc_assign_task",
      "mc_cancel_task",
      "mc_chat",
      "mc_confirm_build",
      "mc_control_companion",
      "mc_delete_skill",
      "mc_emergency_stop",
      "mc_get_task",
      "mc_import_build",
      "mc_list_build_plans",
      "mc_list_chat_messages",
      "mc_list_companions",
      "mc_list_skills",
      "mc_observe",
      "mc_preview_build",
      "mc_release_control",
      "mc_save_skill",
      "mc_submit_ai_decision",
    ]);
  });

  it("submits one bound smart decision and rejects a duplicate interaction", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const interactionId = service.beginAiDecision({
      sequence: 1,
      at: new Date().toISOString(),
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "跟随我",
    });
    const client = await createClient(service);
    const first = await client.callTool({
      name: "mc_submit_ai_decision",
      arguments: {
        interactionId,
        decision: { type: "chat", reply: "我听见了。", summary: "普通聊天" },
      },
    });
    expect(first.isError).not.toBe(true);
    expect(first.structuredContent).toMatchObject({
      ok: true,
      interactionId,
      decisionType: "chat",
    });

    const duplicate = await client.callTool({
      name: "mc_submit_ai_decision",
      arguments: {
        interactionId,
        decision: { type: "chat", reply: "重复。", summary: "重复" },
      },
    });
    expect(duplicate.isError).toBe(true);
    expect(JSON.stringify(duplicate.content)).toContain("AI_DECISION_NOT_PENDING");
  });

  it("blocks direct mutating MCP tools while a smart decision is pending", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const interactionId = service.beginAiDecision({
      sequence: 2,
      at: new Date().toISOString(),
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "给我 16 个肉",
    });
    const client = await createClient(service);

    const directChat = await client.callTool({
      name: "mc_chat",
      arguments: {
        companionId: "codex-sim",
        message: "我先直接回复。",
        owner: "antigravity-autoplay",
        interactionId,
        phase: "start",
      },
    });
    const directTask = await client.callTool({
      name: "mc_assign_task",
      arguments: {
        companionId: "codex-sim",
        owner: "antigravity-autoplay",
        spec: { kind: "gather", itemId: "#minecraft:logs", count: 4, requestedBy: "PlayerOne" },
      },
    });
    const directControl = await client.callTool({
      name: "mc_control_companion",
      arguments: { companionId: "codex-sim", action: "recall" },
    });

    for (const blocked of [directChat, directTask, directControl]) {
      expect(blocked.isError).toBe(true);
      expect(JSON.stringify(blocked.content)).toContain("AI_DECISION_TOOL_BLOCKED");
    }

    const submitted = await client.callTool({
      name: "mc_submit_ai_decision",
      arguments: {
        interactionId,
        decision: {
          type: "task",
          reply: "我去准备 16 个肉并交给你。",
          summary: "猎食并交付肉类",
          spec: {
            kind: "provision-food",
            count: 16,
            source: "hunt",
            foodCategory: "meat",
            destination: "player",
            player: "WrongPlayer",
            requestedBy: "WrongRequester",
          },
        },
      },
    });
    expect(submitted.isError).not.toBe(true);
    const taskId = (submitted.structuredContent as { taskId: string }).taskId;
    expect(service.getTask(taskId).spec).toMatchObject({
      kind: "provision-food",
      count: 16,
      source: "hunt",
      foodCategory: "meat",
      destination: "player",
      player: "PlayerOne",
      requestedBy: "PlayerOne",
    });
  });

  it("rejects an unknown skill without consuming the pending smart interaction", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const interactionId = service.beginAiDecision({
      sequence: 3,
      at: new Date().toISOString(),
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "执行一个不存在的技能",
    });
    const client = await createClient(service);
    const unknown = await client.callTool({
      name: "mc_submit_ai_decision",
      arguments: {
        interactionId,
        decision: {
          type: "skill",
          reply: "开始执行。",
          summary: "unknown skill",
          skillId: "unknown.external-skill",
          arguments: {},
        },
      },
    });
    expect(unknown.isError).toBe(true);

    const recovery = await client.callTool({
      name: "mc_submit_ai_decision",
      arguments: {
        interactionId,
        decision: { type: "clarify", reply: "这个技能未安装，请选择已有任务。", summary: "clarify" },
      },
    });
    expect(recovery.isError).not.toBe(true);
  });

  it("exposes offline build templates with provenance, hash and zero-privilege manifests", async () => {
    const service = new ControlService();
    const client = await createClient(service);
    const result = await client.callTool({ name: "mc_list_build_plans", arguments: {} });
    expect(result.isError).not.toBe(true);
    const plans = (result.structuredContent as { plans: Array<{
      name: string;
      builtIn: boolean;
      confirmed: boolean;
      manifest: {
        sha256: string;
        source: { kind: string; license?: string };
        permissions: Record<string, unknown>;
      };
    }> }).plans;
    expect(plans).toHaveLength(Object.keys(BUILTIN_BUILD_IDS).length);
    expect(plans.every((plan) => plan.builtIn && plan.confirmed)).toBe(true);
    expect(plans.every((plan) => /^[0-9a-f]{64}$/u.test(plan.manifest.sha256))).toBe(true);
    expect(plans.every((plan) => plan.manifest.source.kind === "built-in" && plan.manifest.source.license === "CC0-1.0")).toBe(true);
    expect(plans.every((plan) => plan.manifest.permissions.network === "none"
      && plan.manifest.permissions.systemCommands === false
      && plan.manifest.permissions.commandBlocks === false)).toBe(true);
  });

  it("returns the Antigravity chat inbox together with persona settings", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    await service.updateChatSettings({
      freeChatEnabled: true,
      playerName: "PlayerOne",
      target: "antigravity-mcp",
      persona: {
        mode: "custom",
        displayName: "Luna",
        personality: "Calm and curious.",
        speakingStyle: "Warm, concise Simplified Chinese.",
        memoryNotes: "PlayerOne likes building spruce houses.",
      },
    });
    const at = "2026-07-31T08:00:00.000Z";
    await service.recordIncomingChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "今天一起盖房子吧",
      at,
    });
    const client = await createClient(service);

    const result = await client.callTool({
      name: "mc_list_chat_messages",
      arguments: { afterSequence: 0, limit: 10 },
    });

    expect(result.isError).not.toBe(true);
    const content = result.structuredContent as {
      settings: {
        freeChatEnabled: boolean;
        playerName: string;
        target: string;
        persona: Record<string, string>;
      };
      messages: Array<{
        sequence: number;
        at: string;
        companionId: string;
        sender: string;
        message: string;
      }>;
      nextSequence: number;
      replyRequirement: string;
    };
    expect(content.settings).toMatchObject({
      freeChatEnabled: true,
      playerName: "PlayerOne",
      target: "antigravity-mcp",
      persona: {
        mode: "custom",
        displayName: "Luna",
        personality: "Calm and curious.",
        speakingStyle: "Warm, concise Simplified Chinese.",
        memoryNotes: "PlayerOne likes building spruce houses.",
      },
    });
    expect(content.messages).toEqual([{
      sequence: 1,
      at,
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "今天一起盖房子吧",
    }]);
    expect(content.nextSequence).toBe(1);
    expect(content.replyRequirement).toContain("mc_chat");
    expect(content.replyRequirement).toContain("不会显示在 Minecraft");
  });

  it("coalesces duplicate Antigravity start replies but preserves a later progress reply", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const delivered: Array<{ message: string; phase?: string }> = [];
    const unsubscribe = service.events.subscribe((event) => {
      if (event.type !== "chat" || event.data?.owner !== "antigravity-autoplay") return;
      delivered.push({
        message: String(event.data.message),
        ...(typeof event.data.phase === "string" ? { phase: event.data.phase } : {}),
      });
    });
    const client = await createClient(service);
    const base = {
      companionId: "codex-sim",
      owner: "antigravity-autoplay",
      interactionId: "mc-chat-9",
    };

    await client.callTool({
      name: "mc_chat",
      arguments: { ...base, phase: "start", message: "我开始准备材料了。" },
    });
    await client.callTool({
      name: "mc_chat",
      arguments: { ...base, phase: "start", message: "任务已经分配成功。" },
    });
    await client.callTool({
      name: "mc_chat",
      arguments: { ...base, phase: "progress", message: "材料已备齐，正在制作。" },
    });
    unsubscribe();

    expect(delivered).toEqual([
      { message: "我开始准备材料了。", phase: "start" },
      { message: "材料已备齐，正在制作。", phase: "progress" },
    ]);
  });

  it("does not expose local file-path imports or secret-bearing skill payloads", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const client = await createClient(service);

    const privateBuildPath = ["C:", "Users", "PlayerOne", "private-build.json"].join("\\");
    const localImport = await client.callTool({
      name: "mc_import_build",
      arguments: {
        name: "Private file",
        source: "json",
        origin: { x: 0, y: 64, z: 0 },
        filePath: privateBuildPath,
      },
    });
    expect(localImport.isError).toBe(true);
    expect(JSON.stringify(localImport)).not.toContain("Users\\\\PlayerOne");

    const fakeToken = ["sk", "private-token-1234567890"].join("-");
    const leakingSkill = await client.callTool({
      name: "mc_save_skill",
      arguments: {
        id: "custom.leak",
        name: "Leak",
        description: `Use ${fakeToken}`,
        parameters: [],
        steps: [{ label: "Move", task: { kind: "move", target: { x: 0, y: 64, z: 0 } } }],
      },
    });
    expect(leakingSkill.isError).toBe(true);
    expect(JSON.stringify(leakingSkill)).not.toContain(fakeToken);
  });

  it("long-polls until a new Antigravity chat message arrives", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    await service.updateChatSettings({
      freeChatEnabled: true,
      playerName: "PlayerOne",
      target: "antigravity-mcp",
      persona: {
        mode: "inherit",
        displayName: "",
        personality: "",
        speakingStyle: "",
        memoryNotes: "",
      },
    });
    const client = await createClient(service);
    const startedAt = Date.now();
    let settled = false;
    const pending = client.callTool({
      name: "mc_list_chat_messages",
      arguments: { afterSequence: 0, limit: 10, waitSeconds: 1 },
    }).finally(() => {
      settled = true;
    });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(settled).toBe(false);
    const at = "2026-07-31T08:15:00.000Z";
    await service.recordIncomingChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "长轮询收到我了吗？",
      at,
    });

    const result = await pending;
    expect(Date.now() - startedAt).toBeLessThan(1_000);
    expect(result.isError).not.toBe(true);
    const content = result.structuredContent as {
      messages: Array<{
        sequence: number;
        at: string;
        companionId: string;
        sender: string;
        message: string;
      }>;
      nextSequence: number;
      replyRequirement: string;
    };
    expect(content.messages).toEqual([{
      sequence: 1,
      at,
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "长轮询收到我了吗？",
    }]);
    expect(content.nextSequence).toBe(1);
    expect(content.replyRequirement).toContain("mc_chat");
  });

  it("returns structured observations and enforces build confirmation", async () => {
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    const client = await createClient(service);

    const observed = await client.callTool({
      name: "mc_observe",
      arguments: { companionId: "codex-sim" },
    });
    expect(observed.isError).not.toBe(true);
    expect((observed.structuredContent as { snapshot: { worldId: string } }).snapshot.worldId).toBe("simulated-dragon-world");

    const previewed = await client.callTool({
      name: "mc_preview_build",
      arguments: {
        name: "MCP 平台",
        source: "json",
        origin: { x: 5, y: 70, z: 5 },
        blocks: [
          { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:oak_planks", properties: {} },
          { position: { x: 1, y: 0, z: 0 }, blockId: "minecraft:oak_planks", properties: {} },
        ],
      },
    });
    const plan = (previewed.structuredContent as { plan: { id: string; confirmed: boolean; requiredItems: Record<string, number> } }).plan;
    expect(plan.confirmed).toBe(false);
    expect(plan.requiredItems["minecraft:oak_planks"]).toBe(2);

    const imported = await client.callTool({
      name: "mc_import_build",
      arguments: {
        name: "Imported JSON",
        source: "json",
        origin: { x: 12, y: 70, z: 12 },
        fileName: "platform.json",
        dataBase64: Buffer.from(JSON.stringify({
          blocks: [{ position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:stone", properties: {} }],
        })).toString("base64"),
      },
    });
    expect(imported.isError).not.toBe(true);
    expect((imported.structuredContent as { plan: { confirmed: boolean; source: string } }).plan).toMatchObject({
      confirmed: false,
      source: "json",
    });

    const blocked = await client.callTool({
      name: "mc_assign_task",
      arguments: {
        companionId: "codex-sim",
        owner: "test",
        spec: { kind: "build", planId: plan.id, requestedBy: "test" },
      },
    });
    expect(blocked.isError).toBe(true);

    const confirmed = await client.callTool({
      name: "mc_confirm_build",
      arguments: { planId: plan.id },
    });
    expect((confirmed.structuredContent as { plan: { confirmed: boolean } }).plan.confirmed).toBe(true);
  });
});
