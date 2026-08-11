import path from "node:path";
import { fileURLToPath } from "node:url";
import Fastify from "fastify";
import cors from "@fastify/cors";
import websocket from "@fastify/websocket";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import { z } from "zod";
import { aiProviderDraftSchema, aiTaskDecisionSchema, buildImportRequestSchema, buildPlanDraftSchema, chatSettingsDraftSchema, companionActionSchema, declarativeSkillDraftSchema, taskSpecSchema } from "@mc/protocol";
import { AiProviderStore } from "./ai-provider-store.js";
import {
  AntigravityAgentBridge,
  normalizeAntigravityAutoTriggerFailure,
} from "./antigravity-agent-bridge.js";
import { CodexDriver } from "./codex-driver.js";
import { BridgeManager } from "./bridge-manager.js";
import { ControlService } from "./control-service.js";
import { registerDashboardAssets } from "./dashboard-static.js";
import { ControlError } from "./errors.js";
import { asHttpClientError } from "./http-error.js";
import { liveFixturesEnabled, registerLiveFixtureRoute } from "./live-fixture-route.js";
import { createMinecraftMcpServer } from "./mcp-server.js";
import { ProviderAwareCodexClient } from "./provider-aware-codex.js";
import { loadOrCreateBridgeToken, resolveStateDirectory } from "./runtime-config.js";
import { WindowsDpapiSecretProtector } from "./secret-protector.js";
import { SimulatorBackend } from "./simulator-backend.js";
import { redactSensitiveData, redactSensitiveText } from "./skill-security.js";

const currentDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(currentDir, "../../..");
const port = Number(process.env.PORT ?? 8765);
const stateDirectory = resolveStateDirectory();
const bridgeToken = await loadOrCreateBridgeToken(stateDirectory);
const mcpUrl = process.env.MC_MCP_URL ?? `http://127.0.0.1:${port}/mcp`;
const app = Fastify({
  logger: {
    level: process.env.LOG_LEVEL ?? "info",
    redact: {
      paths: [
        "req.headers.authorization",
        "req.headers.cookie",
        "req.headers.x-api-key",
        "headers.authorization",
        "headers.cookie",
        "apiKey",
        "token",
        "password",
        "secret",
      ],
      censor: "[REDACTED]",
    },
  },
  bodyLimit: 64 * 1024 * 1024,
});
const service = new ControlService({ stateDirectory });
if (process.env.MC_ENABLE_SIMULATOR === "1") service.registerBackend(new SimulatorBackend());
const aiProviders = new AiProviderStore({
  stateDirectory,
  mcpUrl,
  protector: new WindowsDpapiSecretProtector(),
});
const providerClient = new ProviderAwareCodexClient({
  store: aiProviders,
  control: service,
  mcpUrl,
});
const codexDriver = new CodexDriver({
  control: service,
  events: service.events,
  codex: providerClient,
  projectRoot,
  mcpUrl,
  stateDirectory,
});
const antigravityAgent = new AntigravityAgentBridge({
  stateDirectory,
  ...(process.env.MC_ANTIGRAVITY_HOME ? { antigravityHome: process.env.MC_ANTIGRAVITY_HOME } : {}),
  ...(process.env.MC_ANTIGRAVITY_LOG_PATH ? { antigravityLogPath: process.env.MC_ANTIGRAVITY_LOG_PATH } : {}),
  ...(process.env.MC_ANTIGRAVITY_CONVERSATION_TITLE
    ? { requiredConversationTitle: process.env.MC_ANTIGRAVITY_CONVERSATION_TITLE }
    : {}),
});
const ANTIGRAVITY_DIRECTED_MESSAGE = /^\s*@?(?:反重力|antigravity)(?:\s*[,，:：]\s*|\s+)(.+)$/iu;
const bridgeManager = new BridgeManager({
  service,
  token: bridgeToken,
  onDiagnostic: (message) => app.log.warn({ component: "minecraft-bridge" }, redactSensitiveText(message)),
  onChat: async (message) => {
    const directedAntigravity = message.message.match(ANTIGRAVITY_DIRECTED_MESSAGE);
    const routedMessage = directedAntigravity?.[1]
      ? { ...message, message: directedAntigravity[1] }
      : message;
    const immediate = await codexDriver.handleImmediateInGameChat(routedMessage);
    if (immediate.handled) return true;
    if (!directedAntigravity?.[1]) {
      const localResult = await codexDriver.handleInGameChat({
        companionId: message.companionId,
        sender: message.sender,
        message: message.message,
      });
      if (localResult.handled) return true;
    }
    const settings = await service.getChatSettings(message.companionId);
    if (directedAntigravity?.[1] || settings.target === "antigravity-mcp") {
      const recorded = await service.recordIncomingChat(
        routedMessage,
        Boolean(directedAntigravity?.[1]),
      );
      if (!recorded) return false;
      const interactionId = settings.actionMode === "smart"
        ? service.beginAiDecision(recorded, "antigravity-autoplay")
        : undefined;
      const capabilityCatalog = interactionId ? [
        ...service.listSkills().map((skill) => (
          `skill ${skill.id}: ${skill.name}; parameters=${skill.parameters.map((parameter) => parameter.name).join(",") || "none"}`
        )),
        ...service.listBuildPlans().filter((plan) => plan.confirmed).map((plan) => (
          `build ${plan.id}: ${plan.name}; blocks=${plan.blocks.length}`
        )),
      ] : undefined;
      void antigravityAgent.trigger(recorded, settings, interactionId
        ? { interactionId, ...(capabilityCatalog ? { capabilityCatalog } : {}) }
        : undefined).catch(async (caught) => {
        if (interactionId) service.cancelAiDecision(interactionId);
        const normalized = normalizeAntigravityAutoTriggerFailure(caught);
        const failure = redactSensitiveText(normalized.message);
        if (!normalized.notifyPlayer) {
          app.log.debug({ component: "antigravity-autoplay", code: normalized.code }, "Antigravity retry suppressed");
          return;
        }
        app.log.warn({ component: "antigravity-autoplay", code: normalized.code, failure }, "Antigravity auto trigger failed");
        service.events.publish({
          type: "warning",
          companionId: recorded.companionId,
          message: failure,
        });
        await service.sendChat(recorded.companionId, failure, "antigravity-autoplay").catch(() => undefined);
      });
      return true;
    }
    return false;
  },
});

await app.register(cors, {
  origin: [/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/],
});
await app.register(websocket);

app.get("/api/health", async () => ({
  ok: true,
  service: "minecraft-codex-companion",
  now: new Date().toISOString(),
  companions: service.listCompanions().length,
}));

app.get("/api/antigravity/status", async () => antigravityAgent.status());
app.post<{ Body: { title?: string } }>("/api/antigravity/bind", async (request) => (
  antigravityAgent.bindConversationByTitle(request.body?.title ?? "")
));
app.post("/api/antigravity/bind-latest", async () => antigravityAgent.bindLatestConversation());
app.post("/api/antigravity/recover", async () => antigravityAgent.recoverBoundConversation());

app.get("/api/companions", async () => ({ companions: service.listCompanions() }));
app.get<{ Params: { id: string } }>("/api/companions/:id", async (request) => service.getCompanion(request.params.id));
app.get<{ Params: { id: string } }>("/api/companions/:id/snapshot", async (request) => service.getSnapshot(request.params.id));
if (liveFixturesEnabled()) registerLiveFixtureRoute(app, service);
app.get("/api/tasks", async () => ({ tasks: service.listTasks() }));
app.get<{ Params: { id: string } }>("/api/tasks/:id", async (request) => service.getTask(request.params.id));
app.get<{ Querystring: { companionId?: string } }>("/api/chat/settings", async (request) => (
  service.getChatSettings(request.query.companionId)
));
app.put<{ Body: unknown }>("/api/chat/settings", async (request) => (
  service.updateChatSettings(chatSettingsDraftSchema.parse(request.body))
));
app.post<{ Params: { interactionId: string }; Body: unknown }>(
  "/api/ai/decisions/:interactionId",
  async (request) => {
    const body = z.object({ decision: aiTaskDecisionSchema }).parse(request.body);
    return service.submitAiDecision(request.params.interactionId, body.decision);
  },
);
app.get<{ Querystring: { afterSequence?: string; limit?: string } }>("/api/chat/messages", async (request) => {
  const query = z.object({
    afterSequence: z.coerce.number().int().nonnegative().default(0),
    limit: z.coerce.number().int().min(1).max(100).default(50),
  }).parse(request.query);
  const [settings, messages] = await Promise.all([
    service.getChatSettings(),
    Promise.resolve(service.listChatMessages(query.afterSequence, query.limit)),
  ]);
  return {
    settings,
    messages,
    nextSequence: messages.at(-1)?.sequence ?? query.afterSequence,
  };
});
app.get("/api/build-plans", async () => ({ plans: service.listBuildPlans() }));
app.get<{ Params: { id: string } }>("/api/build-plans/:id", async (request) => service.getBuildPlan(request.params.id));
app.get("/api/skills", async () => ({ skills: service.listSkills() }));
app.get<{ Params: { id: string } }>("/api/skills/:id", async (request) => service.getSkill(request.params.id));

app.put<{ Params: { id: string }; Body: unknown }>("/api/skills/:id", async (request) => {
  const draft = declarativeSkillDraftSchema.parse(request.body);
  if (draft.id !== request.params.id) {
    throw new ControlError({ code: "SKILL_ID_MISMATCH", message: "路径中的技能 ID 与配置不一致", statusCode: 400 });
  }
  return service.saveSkill(draft);
});

app.delete<{ Params: { id: string } }>("/api/skills/:id", async (request, reply) => {
  service.removeSkill(request.params.id);
  return reply.code(204).send();
});

app.post<{ Params: { id: string }; Body: unknown }>("/api/skills/:id/review", async (request) => {
  const body = z.object({ approved: z.boolean() }).parse(request.body);
  return service.reviewSkill(request.params.id, body.approved);
});

app.get("/api/ai/providers", async () => ({ providers: await aiProviders.list() }));
app.get<{ Params: { id: string } }>("/api/ai/providers/:id", async (request) => aiProviders.get(request.params.id));

app.post<{ Body: unknown }>("/api/ai/providers", async (request, reply) => {
  const draft = aiProviderDraftSchema.parse(request.body);
  return reply.code(201).send(await aiProviders.create(draft));
});

app.put<{ Params: { id: string }; Body: unknown }>("/api/ai/providers/:id", async (request) => {
  const body = z.object({
    profile: aiProviderDraftSchema,
    clearApiKey: z.boolean().default(false),
  }).parse(request.body);
  return aiProviders.update(request.params.id, body.profile, body.clearApiKey);
});

app.delete<{ Params: { id: string } }>("/api/ai/providers/:id", async (request, reply) => {
  await aiProviders.remove(request.params.id);
  return reply.code(204).send();
});

app.post<{ Params: { id: string } }>("/api/ai/providers/:id/activate", async (request) => aiProviders.activate(request.params.id));
app.post<{ Params: { id: string } }>("/api/ai/providers/:id/test", async (request) => providerClient.testProvider(request.params.id));

app.get("/api/ai/mcp-config", async () => ({
  url: mcpUrl,
  antigravity: {
    mcpServers: {
      minecraft_codex_companion: {
        command: process.execPath,
        args: [path.resolve(currentDir, "mcp-stdio.js")],
        env: { MC_COMPANION_URL: `http://127.0.0.1:${port}` },
      },
    },
  },
}));

app.post<{ Body: unknown }>("/api/build-plans/preview", async (request, reply) => {
  const draft = buildPlanDraftSchema.parse(request.body);
  return reply.code(201).send(service.previewBuild(draft));
});

app.post<{ Body: unknown }>("/api/build-plans/import", async (request, reply) => {
  const input = buildImportRequestSchema.parse(request.body);
  return reply.code(201).send(await service.importBuild(input));
});

app.post<{ Params: { id: string } }>("/api/build-plans/:id/confirm", async (request) => service.confirmBuild(request.params.id));

app.post<{ Params: { id: string }; Body: unknown }>("/api/companions/:id/tasks", async (request, reply) => {
  const body = z.object({
    spec: taskSpecSchema,
    owner: z.string().min(1).default("dashboard"),
    replaceConflictingDelivery: z.boolean().default(false),
    aiDecisionInteractionId: z.string().trim().min(1).max(128).optional(),
  }).parse(request.body);
  return reply.code(202).send(service.assignTask(request.params.id, body.spec, body.owner, {
    replaceConflictingDelivery: body.replaceConflictingDelivery,
    ...(body.aiDecisionInteractionId
      ? { aiDecisionInteractionId: body.aiDecisionInteractionId }
      : {}),
  }));
});

app.post<{ Params: { id: string }; Body: unknown }>("/api/companions/:id/tasks/retry-build", async (request, reply) => {
  const body = z.object({
    owner: z.string().min(1).default("dashboard"),
    requestedBy: z.string().trim().min(1).max(64).optional(),
    aiDecisionInteractionId: z.string().trim().min(1).max(128).optional(),
  }).parse(request.body ?? {});
  return reply.code(202).send(service.retryLatestBuildTask(
    request.params.id,
    body.owner,
    body.requestedBy,
    body.aiDecisionInteractionId
      ? { aiDecisionInteractionId: body.aiDecisionInteractionId }
      : {},
  ));
});

app.post<{ Params: { id: string }; Body: unknown }>("/api/tasks/:id/cancel", async (request) => {
  const body = z.object({ reason: z.string().min(1).default("用户取消") }).parse(request.body ?? {});
  return service.cancelTask(request.params.id, body.reason);
});

app.post<{ Params: { id: string }; Body: unknown }>("/api/companions/:id/chat", async (request, reply) => {
  const body = z.object({
    message: z.string().min(1).max(256),
    owner: z.string().min(1).default("dashboard"),
    interactionId: z.string().trim().min(1).max(128).optional(),
    phase: z.enum(["start", "progress", "terminal", "chat"]).optional(),
  }).parse(request.body);
  await service.sendChat(request.params.id, body.message, body.owner, {
    ...(body.interactionId ? { interactionId: body.interactionId } : {}),
    ...(body.phase ? { phase: body.phase } : {}),
  });
  return reply.code(202).send({ ok: true });
});

app.post<{ Params: { id: string }; Body: unknown }>("/api/companions/:id/actions", async (request, reply) => {
  const body = z.object({
    action: companionActionSchema,
    aiDecisionInteractionId: z.string().trim().min(1).max(128).optional(),
  }).parse(request.body);
  return reply.code(202).send(await service.controlCompanion(
    request.params.id,
    body.action,
    body.aiDecisionInteractionId
      ? { aiDecisionInteractionId: body.aiDecisionInteractionId }
      : {},
  ));
});

app.post<{ Params: { id: string }; Body: unknown }>("/api/companions/:id/lease", async (request) => {
  const body = z.object({ owner: z.string().min(1), force: z.boolean().default(false) }).parse(request.body);
  return service.acquireLease(request.params.id, body.owner, body.force);
});

app.delete<{ Params: { id: string }; Body: unknown }>("/api/companions/:id/lease", async (request) => {
  const body = z.object({ owner: z.string().min(1) }).parse(request.body);
  return service.releaseLease(request.params.id, body.owner);
});

app.post<{ Body: unknown }>("/api/emergency-stop", async (request, reply) => {
  const body = z.object({ disconnect: z.boolean().default(false) }).parse(request.body ?? {});
  await service.emergencyStop(body.disconnect);
  return reply.code(202).send({ ok: true });
});

app.get("/api/codex/status", async () => codexDriver.status());
app.get<{ Params: { id: string } }>("/api/codex/requests/:id", async (request, reply) => {
  const item = codexDriver.getRequest(request.params.id);
  if (!item) return reply.code(404).send({ error: { code: "CODEX_REQUEST_NOT_FOUND", message: "找不到 Codex 请求" } });
  return item;
});

app.post<{ Body: unknown }>("/api/codex/message", async (request, reply) => {
  const body = z.object({
    companionId: z.string().min(1),
    sender: z.string().min(1).max(64),
    message: z.string().min(1).max(1_000),
    imagePath: z.string().min(1).optional(),
    inGame: z.boolean().default(true),
  }).parse(request.body);
  const driverInput = {
    companionId: body.companionId,
    sender: body.sender,
    message: body.message,
    ...(body.imagePath ? { imagePath: body.imagePath } : {}),
  };
  const result = body.inGame
    ? await codexDriver.handleImmediateInGameChat(driverInput).then(
      async (immediate) => immediate.handled ? immediate : codexDriver.handleInGameChat(driverInput),
    )
    : { handled: true, request: await codexDriver.enqueue(driverInput) };
  return reply.code(result.handled ? 202 : 200).send(result);
});

app.get("/api/events", { websocket: true }, (socket) => {
  socket.send(JSON.stringify({ type: "bootstrap", events: service.events.recent(100) }));
  const unsubscribe = service.events.subscribe((event) => {
    if (socket.readyState === socket.OPEN) socket.send(JSON.stringify({ type: "event", event }));
  });
  socket.on("close", unsubscribe);
});

app.get("/bridge", { websocket: true }, (socket) => bridgeManager.attach(socket));

app.all<{ Body: unknown }>("/mcp", async (request, reply) => {
  const mcpServer = createMinecraftMcpServer(service);
  const transport = new StreamableHTTPServerTransport();
  reply.hijack();
  try {
    await mcpServer.connect(transport as unknown as Transport);
    await transport.handleRequest(request.raw, reply.raw, request.body);
  } catch (error) {
    app.log.error({ error: redactSensitiveData(error instanceof Error ? { name: error.name, message: error.message } : error) }, "MCP request failed");
    if (!reply.raw.headersSent) {
      reply.raw.writeHead(500, { "content-type": "application/json" });
      reply.raw.end(JSON.stringify({
        jsonrpc: "2.0",
        error: { code: -32603, message: "Internal MCP error" },
        id: null,
      }));
    }
  } finally {
    await transport.close().catch(() => undefined);
    await mcpServer.close().catch(() => undefined);
  }
});

app.setErrorHandler((error, _request, reply) => {
  if (error instanceof ControlError) {
    return reply.code(error.statusCode).send({
      error: {
        code: error.code,
        message: error.message,
        retryable: error.retryable,
        suggestedRecovery: error.suggestedRecovery,
      },
    });
  }
  if (error instanceof z.ZodError) {
    return reply.code(400).send({
      error: { code: "INVALID_INPUT", message: z.prettifyError(error), retryable: false },
    });
  }
  const clientError = asHttpClientError(error);
  if (clientError) {
    return reply.code(clientError.statusCode).send({
      error: { code: "INVALID_INPUT", message: clientError.message, retryable: false },
    });
  }
  app.log.error({ error: redactSensitiveData(error instanceof Error ? { name: error.name, message: error.message } : error) });
  return reply.code(500).send({ error: { code: "INTERNAL_ERROR", message: "控制服务发生内部错误", retryable: true } });
});

const dashboardDist = path.resolve(currentDir, "../../dashboard/dist");
try {
  await registerDashboardAssets(app, dashboardDist);
} catch (error) {
  app.log.debug({ error: redactSensitiveData(error instanceof Error ? { name: error.name, message: error.message } : error) }, "Dashboard assets are not built yet");
}

await app.listen({ host: "127.0.0.1", port });
app.log.info(`Minecraft Codex Companion: http://127.0.0.1:${port}`);

export { aiProviders, app, codexDriver, providerClient, service };
