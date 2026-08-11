import { mkdtemp, readFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import type { ChatSettingsDraft, TaskRecord, WorldSnapshot } from "@mc/protocol";
import { describe, expect, it, vi } from "vitest";
import { BUILTIN_BUILD_IDS } from "./builtin-content.js";
import { CodexDriver } from "./codex-driver.js";
import { ControlService } from "./control-service.js";
import { SimulatorBackend } from "./simulator-backend.js";

const inheritedPersona = {
  mode: "inherit" as const,
  displayName: "",
  personality: "",
  speakingStyle: "",
  memoryNotes: "",
};

class FakeThread {
  readonly id: string;
  readonly prompts: unknown[] = [];
  readonly roles: Array<"codex" | "claude"> = [];
  readonly advisorPrompts: unknown[] = [];
  readonly advisorRoles: Array<"codex" | "claude"> = [];
  readonly coordinatorPrompts: unknown[] = [];
  readonly plannerPrompts: unknown[] = [];
  readonly plannerRoles: Array<"codex" | "claude"> = [];
  readonly plannerTokenBudgets: number[] = [];
  readonly policies: Array<{ toolPolicy: "minecraft" | "none"; tokenBudget: number }> = [];
  readonly advisorTokenBudgets: number[] = [];
  readonly coordinatorTokenBudgets: number[] = [];
  onRun: (() => void | Promise<void>) | null = null;
  onAdvisorRun: ((role: "codex" | "claude") => void | Promise<void>) | null = null;
  failingAdvisorRole: "codex" | "claude" | null = null;
  coordinatorDecision: Record<string, unknown> = {
    reply: "Ready to help.",
    summary: "Coordinated reply.",
    action: { type: "none" },
  };
  plannerDecision: Record<string, unknown> = {
    type: "chat",
    reply: "Ready to help.",
    summary: "Replied to the player.",
  };
  plannerRawResponse: string | null = null;

  constructor(id: string) {
    this.id = id;
  }

  async run(input: unknown): Promise<{ finalResponse: string }> {
    this.prompts.push(input);
    await this.onRun?.();
    return {
      finalResponse: JSON.stringify({
        reply: "Ready to help.",
        acted: false,
        summary: "Replied to the player.",
      }),
    };
  }

  async runForRole(role: "codex" | "claude", input: unknown): Promise<{ finalResponse: string }> {
    this.roles.push(role);
    return this.run(input);
  }

  async runWithPolicy(
    input: unknown,
    _options: unknown,
    policy: { toolPolicy: "minecraft" | "none"; tokenBudget: number },
  ): Promise<{ finalResponse: string }> {
    this.policies.push({ ...policy });
    return this.run(input);
  }

  async runForRoleWithPolicy(
    role: "codex" | "claude",
    input: unknown,
    options: unknown,
    policy: { toolPolicy: "minecraft" | "none"; tokenBudget: number },
  ): Promise<{ finalResponse: string }> {
    this.roles.push(role);
    return this.runWithPolicy(input, options, policy);
  }

  async runAdvisoryForRole(
    role: "codex" | "claude",
    input: unknown,
    _options?: unknown,
    tokenBudget?: number,
  ): Promise<{ finalResponse: string }> {
    this.advisorRoles.push(role);
    this.advisorPrompts.push(input);
    if (tokenBudget !== undefined) this.advisorTokenBudgets.push(tokenBudget);
    await this.onAdvisorRun?.(role);
    if (this.failingAdvisorRole === role) throw new Error(`${role} advisor unavailable`);
    return {
      finalResponse: JSON.stringify({
        analysis: `${role} analysis`,
        recommendation: `${role} proposal`,
        risks: [],
      }),
    };
  }

  async runCoordinator(
    input: unknown,
    _options?: unknown,
    tokenBudget?: number,
  ): Promise<{ finalResponse: string }> {
    this.coordinatorPrompts.push(input);
    if (tokenBudget !== undefined) this.coordinatorTokenBudgets.push(tokenBudget);
    return { finalResponse: JSON.stringify(this.coordinatorDecision) };
  }

  async runPlanning(
    input: unknown,
    _options?: unknown,
    tokenBudget?: number,
  ): Promise<{ finalResponse: string }> {
    this.plannerPrompts.push(input);
    if (tokenBudget !== undefined) this.plannerTokenBudgets.push(tokenBudget);
    return { finalResponse: this.plannerRawResponse ?? JSON.stringify(this.plannerDecision) };
  }

  async runPlanningForRole(
    role: "codex" | "claude",
    input: unknown,
    options?: unknown,
    tokenBudget?: number,
  ): Promise<{ finalResponse: string }> {
    this.plannerRoles.push(role);
    return this.runPlanning(input, options, tokenBudget);
  }
}

class FakeCodex {
  readonly started = new FakeThread("thread-new");
  resumedId: string | null = null;
  startedOptions: unknown = null;

  startThread(options?: unknown): FakeThread {
    this.startedOptions = options;
    return this.started;
  }

  resumeThread(id: string): FakeThread {
    this.resumedId = id;
    return new FakeThread(id);
  }
}

class PerNpcFakeCodex {
  readonly threads: FakeThread[] = [];

  startThread(): FakeThread {
    const thread = new FakeThread(`thread-${this.threads.length + 1}`);
    this.threads.push(thread);
    return thread;
  }

  resumeThread(id: string): FakeThread {
    const thread = new FakeThread(id);
    this.threads.push(thread);
    return thread;
  }
}

class RecordingSimulatorBackend extends SimulatorBackend {
  readonly ranSpecs: TaskRecord["spec"][] = [];

  override async runTask(...args: Parameters<SimulatorBackend["runTask"]>): Promise<string> {
    this.ranSpecs.push(structuredClone(args[0].spec));
    return super.runTask(...args);
  }
}

class LedgerSimulatorBackend extends SimulatorBackend {
  override snapshot(): WorldSnapshot {
    return {
      ...super.snapshot(),
      recentItemTransactions: [
        {
          sequence: 1,
          gameTime: 120,
          taskId: "gather-coal",
          action: "gather",
          itemId: "minecraft:coal",
          delta: 26,
          balanceAfter: 26,
        },
        {
          sequence: 2,
          gameTime: 240,
          taskId: "craft-axe",
          action: "craft",
          itemId: "minecraft:coal",
          delta: -26,
          balanceAfter: 0,
        },
      ],
    };
  }
}

class HoldingCountProgressBackend extends SimulatorBackend {
  override async runTask(...args: Parameters<SimulatorBackend["runTask"]>): Promise<string> {
    const [task, callbacks, signal] = args;
    if (task.spec.kind !== "gather") return super.runTask(...args);
    callbacks.onProgress(53 / 64, "已采集并实际保有 53/64", "active", {
      completedCount: 53,
      targetCount: 64,
      retainedCount: 53,
    });
    await new Promise<void>((_resolve, reject) => {
      const abort = () => reject(signal.reason instanceof Error ? signal.reason : new Error("cancelled"));
      if (signal.aborted) abort();
      else signal.addEventListener("abort", abort, { once: true });
    });
    return "unreachable";
  }
}

async function waitForRequest(driver: CodexDriver, id: string) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const request = driver.getRequest(id);
    if (request && ["succeeded", "failed", "stopped"].includes(request.status)) return request;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("Codex request did not finish");
}

async function waitForTaskTerminal(service: ControlService, id: string) {
  for (let attempt = 0; attempt < 300; attempt += 1) {
    const task = service.getTask(id);
    if (["succeeded", "failed", "cancelled"].includes(task.status)) return task;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error("Minecraft task did not finish");
}

async function createHarness(prefix = "mc-codex-driver-") {
  const stateDirectory = await mkdtemp(path.join(os.tmpdir(), prefix));
  const service = new ControlService();
  service.registerBackend(new SimulatorBackend());
  const fake = new FakeCodex();
  const driver = new CodexDriver({
    control: service,
    events: service.events,
    codex: fake,
    projectRoot: process.cwd(),
    stateDirectory,
  });
  return { stateDirectory, service, fake, driver };
}

async function configureChat(
  service: ControlService,
  overrides: Partial<ChatSettingsDraft> = {},
) {
  return service.updateChatSettings({
    freeChatEnabled: true,
    playerName: "PlayerOne",
    companionName: "Codex",
    target: "active-provider",
    persona: inheritedPersona,
    ...overrides,
  });
}

describe("CodexDriver", () => {
  it("runs directed chat through a persistent Codex thread", async () => {
    const { stateDirectory, service, fake, driver } = await createHarness();

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "@Codex，讲个笑话",
    });
    expect(routed.handled).toBe(true);
    const finished = await waitForRequest(driver, routed.request!.id);
    expect(finished.status).toBe("succeeded");
    expect(finished.reply).toBe("Ready to help.");
    expect(fake.started.prompts).toHaveLength(1);
    expect(fake.startedOptions).toMatchObject({
      approvalPolicy: "never",
      sandboxMode: "read-only",
      networkAccessEnabled: false,
    });

    const state = JSON.parse(await readFile(path.join(stateDirectory, "codex-thread.json"), "utf8")) as {
      version: number;
      threads: Record<string, string>;
    };
    expect(state).toMatchObject({ version: 2, threads: { "codex-sim": "thread-new" } });

    const resumed = new FakeCodex();
    const nextDriver = new CodexDriver({
      control: service,
      codex: resumed,
      projectRoot: process.cwd(),
      stateDirectory,
    });
    const next = await nextDriver.enqueue({ companionId: "codex-sim", sender: "PlayerOne", message: "继续" });
    await waitForRequest(nextDriver, next.id);
    expect(resumed.resumedId).toBe("thread-new");
  });

  it("routes ordinary chat from the configured player to the active provider", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-free-chat-");
    await configureChat(service);

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "  pLaYeRoNe ",
      message: "今天一起看看风景吧",
    });

    expect(routed.handled).toBe(true);
    expect(routed.request?.message).toBe("今天一起看看风景吧");
    await waitForRequest(driver, routed.request!.id);
    expect(fake.started.prompts).toHaveLength(1);
    expect(fake.started.policies).toEqual([{ toolPolicy: "none", tokenBudget: 512 }]);
    expect(String(fake.started.prompts[0])).toContain("Action mode is STABLE");
  });

  it("uses local actions when disabled and one structured planner decision when enabled", async () => {
    const stable = await createHarness("mc-codex-stable-mode-");
    await configureChat(stable.service, { actionMode: "stable", tokenBudget: 768 });

    const local = await stable.driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "撸点木头",
    });
    expect(local.status).toBe("succeeded");
    expect(stable.fake.started.prompts).toHaveLength(0);
    expect(stable.service.listTasks()).toHaveLength(1);

    const fallback = await stable.driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "去探索一下你觉得最有价值的地方",
    });
    await waitForRequest(stable.driver, fallback.id);
    expect(stable.fake.started.policies).toEqual([{ toolPolicy: "none", tokenBudget: 768 }]);
    expect(String(stable.fake.started.prompts[0])).toContain("Action mode is STABLE");

    const smart = await createHarness("mc-codex-smart-mode-");
    await configureChat(smart.service, { actionMode: "smart", tokenBudget: 1_536 });
    smart.fake.started.plannerDecision = {
      type: "skill",
      reply: "我去采集并带回来。",
      summary: "采集木头并交付",
      skillId: "life.gather-and-deliver",
      arguments: { itemId: "#minecraft:logs", count: 8, player: "PlayerOne" },
    };
    const planned = await smart.driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "撸点木头",
    });
    await waitForRequest(smart.driver, planned.id);
    expect(smart.service.listTasks()).toHaveLength(1);
    expect(smart.fake.started.plannerTokenBudgets).toEqual([1_536]);
    expect(String(smart.fake.started.plannerPrompts[0])).toContain("single-turn Minecraft intent planner");
    expect(smart.fake.started.policies).toHaveLength(0);
  });

  it("keeps recall local in smart mode and passes token budgets to explicit multi-agent turns", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-smart-safety-");
    await configureChat(service, { actionMode: "smart", tokenBudget: 640 });
    const control = vi.spyOn(service, "controlCompanion").mockResolvedValue(service.getCompanion("codex-sim"));

    const recall = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "召回",
    });
    expect(recall.status).toBe("succeeded");
    expect(control).toHaveBeenCalledWith("codex-sim", "recall");
    expect(fake.started.prompts).toHaveLength(0);

    const status = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "Codex，汇报完整状态",
    });
    expect(status.status).toBe("succeeded");
    expect(status.reply).toContain("生命 20/20");
    expect(fake.started.plannerPrompts).toHaveLength(0);

    const collaboration = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "@多代理 规划明天的采集",
    });
    await waitForRequest(driver, collaboration.request!.id);
    expect(fake.started.advisorTokenBudgets).toEqual([160, 160]);
    expect(fake.started.coordinatorTokenBudgets).toEqual([320]);
  });

  it("rejects malformed smart planner JSON without executing a task", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-smart-malformed-");
    await configureChat(service, { actionMode: "smart", tokenBudget: 512 });
    fake.started.plannerRawResponse = "{not-valid-json";

    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "帮我安排一次复杂远征",
    });
    const finished = await waitForRequest(driver, request.id);

    expect(finished.status).toBe("failed");
    expect(service.listTasks()).toHaveLength(0);
    expect(fake.started.plannerPrompts).toHaveLength(1);
  });

  it("documents the common craft, meat, building, and resume mappings in each smart planner turn", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-smart-catalog-");
    await configureChat(service, { actionMode: "smart", tokenBudget: 512 });

    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "给我来个钻石镐，再做 64 个火把，然后给我 16 个肉",
    });
    await waitForRequest(driver, request.id);
    const prompt = String(fake.started.plannerPrompts[0]);

    expect(prompt).toContain("kind=craft with the exact namespaced itemId and count");
    expect(prompt).toContain("foodCategory=meat and source=hunt");
    expect(prompt).toContain("For an unspecified building, return clarify");
    expect(prompt).toContain("use type=retry-build");
    expect(prompt).toContain("commits at most one root action");
    expect(prompt).toContain("using the language of the player's current message");
  });

  it("ignores ordinary chat from other players", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-other-player-");
    await configureChat(service, { playerName: "PlayerOne" });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "Alex",
      message: "Can you follow me?",
    });

    expect(routed).toEqual({ handled: false, request: null });
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("ignores ordinary chat while free chat is disabled", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-free-chat-off-");
    await configureChat(service, { freeChatEnabled: false });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "随便聊聊天",
    });

    expect(routed).toEqual({ handled: false, request: null });
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("always routes an explicit @codex message regardless of free-chat settings", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-directed-");
    await configureChat(service, {
      freeChatEnabled: false,
      target: "antigravity-mcp",
      playerName: "SomeoneElse",
    });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "@codex: tell me a joke",
    });

    expect(routed.handled).toBe(true);
    expect(routed.request?.message).toBe("tell me a joke");
    await waitForRequest(driver, routed.request!.id);
    expect(fake.started.prompts).toHaveLength(1);
  });

  it("routes an explicit @claude message to the Claude provider role", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-directed-claude-");
    await configureChat(service, { freeChatEnabled: false });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "@Claude：陪我随便聊聊",
    });

    expect(routed.handled).toBe(true);
    expect(routed.request).toMatchObject({
      message: "陪我随便聊聊",
      providerRole: "claude",
    });
    await waitForRequest(driver, routed.request!.id);
    expect(fake.started.roles).toEqual(["claude"]);
  });

  it("runs Codex and Claude advisors in parallel and sends one coordinated reply", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-multi-agent-");
    await configureChat(service, { target: "multi-agent" });
    const sendChat = vi.spyOn(service, "sendChat");
    let advisorStarts = 0;
    let releaseAdvisors!: () => void;
    let reportBothStarted!: () => void;
    const advisorGate = new Promise<void>((resolve) => {
      releaseAdvisors = resolve;
    });
    const bothStarted = new Promise<void>((resolve) => {
      reportBothStarted = resolve;
    });
    fake.started.onAdvisorRun = async () => {
      advisorStarts += 1;
      if (advisorStarts === 2) reportBothStarted();
      await advisorGate;
    };

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "我们接下来做什么？",
    });
    await bothStarted;

    expect(routed).toMatchObject({ handled: true, request: { providerRole: "multi-agent" } });
    expect(fake.started.advisorRoles).toEqual(expect.arrayContaining(["codex", "claude"]));
    expect(fake.started.coordinatorPrompts).toHaveLength(0);
    releaseAdvisors();
    const finished = await waitForRequest(driver, routed.request!.id);

    expect(finished).toMatchObject({ status: "succeeded", reply: "Ready to help." });
    expect(fake.started.roles).toEqual([]);
    expect(fake.started.coordinatorPrompts).toHaveLength(1);
    expect(String(fake.started.coordinatorPrompts[0])).toContain("codex proposal");
    expect(String(fake.started.coordinatorPrompts[0])).toContain("claude proposal");
    expect(sendChat.mock.calls.map(([, message]) => message)).toEqual(["Ready to help."]);
  });

  it("supports explicit @多代理 routing and degrades when one advisor is unavailable", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-directed-team-");
    await configureChat(service, { freeChatEnabled: false, target: "antigravity-mcp" });
    fake.started.failingAdvisorRole = "claude";

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "@多代理：规划一次短途采集",
    });
    const finished = await waitForRequest(driver, routed.request!.id);

    expect(routed).toMatchObject({
      handled: true,
      request: { message: "规划一次短途采集", providerRole: "multi-agent" },
    });
    expect(finished.status).toBe("succeeded");
    expect(String(fake.started.coordinatorPrompts[0])).toContain("Claude critic: unavailable for this turn.");
    expect(fake.started.roles).toEqual([]);
  });

  it("forces explicit @多代理 deterministic phrases through collaboration", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-forced-team-");
    await configureChat(service, { freeChatEnabled: false });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "@多代理 撸点木头",
    });
    const finished = await waitForRequest(driver, routed.request!.id);

    expect(finished.status).toBe("succeeded");
    expect(routed.request).toMatchObject({
      providerRole: "multi-agent",
      collaborationRequested: true,
    });
    expect(fake.started.advisorRoles).toEqual(expect.arrayContaining(["codex", "claude"]));
    expect(fake.started.coordinatorPrompts).toHaveLength(1);
    expect(service.listTasks()).toHaveLength(0);
  });

  it("does not let an unconfigured player trigger explicit multi-agent providers", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-team-player-guard-");
    await configureChat(service, { playerName: "PlayerOne" });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "Alex",
      message: "@多代理 帮我建房子",
    });

    expect(routed).toEqual({ handled: true, request: null });
    expect(fake.started.advisorPrompts).toHaveLength(0);
    expect(fake.started.coordinatorPrompts).toHaveLength(0);
  });

  it("commits one validated multi-agent task with local companion and requester binding", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-team-single-commit-");
    await configureChat(service, { target: "multi-agent" });
    const assignTask = vi.spyOn(service, "assignTask");
    fake.started.coordinatorDecision = {
      reply: "我去收集并交给你。",
      summary: "one task",
      action: {
        type: "task",
        spec: {
          kind: "deliver",
          itemId: "#minecraft:logs",
          count: 8,
          player: "WrongPlayer",
          requestedBy: "WrongRequester",
        },
      },
    };

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "请协作规划一次补给",
    });
    const finished = await waitForRequest(driver, routed.request!.id);

    expect(finished).toMatchObject({ status: "succeeded", reply: expect.stringContaining("任务 ID") });
    expect(assignTask).toHaveBeenCalledTimes(1);
    expect(assignTask).toHaveBeenCalledWith(
      "codex-sim",
      expect.objectContaining({ player: "PlayerOne", requestedBy: "PlayerOne" }),
      expect.any(String),
      { replaceConflictingDelivery: false },
    );
  });

  it("rebinds multi-agent craft delivery and macro player arguments to the requester", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-team-requester-bindings-");
    await configureChat(service, { target: "multi-agent" });
    const assignTask = vi.spyOn(service, "assignTask");

    fake.started.coordinatorDecision = {
      reply: "我来制作。",
      summary: "craft",
      action: {
        type: "task",
        spec: {
          kind: "craft",
          itemId: "minecraft:stone_pickaxe",
          count: 1,
          deliverTo: "WrongPlayer",
          requestedBy: "WrongRequester",
        },
      },
    };
    const craft = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "协作制作一把石镐给我",
      providerRole: "multi-agent",
    });
    await waitForRequest(driver, craft.id);

    fake.started.coordinatorDecision = {
      reply: "我去远征。",
      summary: "expedition",
      action: {
        type: "task",
        spec: {
          kind: "macro",
          skillId: "life.expedition-and-deliver",
          arguments: { itemId: "#minecraft:logs", count: 4, player: "WrongPlayer" },
          requestedBy: "WrongRequester",
        },
      },
    };
    const expedition = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "协作远征砍四个原木给我",
      providerRole: "multi-agent",
    });
    await waitForRequest(driver, expedition.id);

    service.saveSkill({
      id: "custom.dynamic-delivery",
      name: "Dynamic delivery",
      description: "Deliver an item to a parameterized player.",
      parameters: [
        { name: "itemId", description: "Item", type: "string", required: true },
        { name: "beneficiary", description: "Player", type: "string", required: true },
      ],
      steps: [{
        label: "Deliver",
        task: { kind: "deliver", itemId: "${itemId}", count: 1, player: "${beneficiary}" },
      }],
    });
    fake.started.coordinatorDecision = {
      reply: "我来交付。",
      summary: "custom macro",
      action: {
        type: "task",
        spec: {
          kind: "macro",
          skillId: "custom.dynamic-delivery",
          arguments: { itemId: "minecraft:bread", beneficiary: "WrongPlayer" },
          requestedBy: "WrongRequester",
        },
      },
    };
    const custom = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "协作执行自定义交付",
      providerRole: "multi-agent",
    });
    await waitForRequest(driver, custom.id);

    expect(assignTask).toHaveBeenNthCalledWith(
      1,
      "codex-sim",
      expect.objectContaining({ deliverTo: "PlayerOne", requestedBy: "PlayerOne" }),
      expect.any(String),
      { replaceConflictingDelivery: false },
    );
    expect(assignTask).toHaveBeenNthCalledWith(
      2,
      "codex-sim",
      expect.objectContaining({
        requestedBy: "PlayerOne",
        arguments: expect.objectContaining({ player: "PlayerOne" }),
      }),
      expect.any(String),
      { replaceConflictingDelivery: false },
    );
    expect(assignTask).toHaveBeenNthCalledWith(
      3,
      "codex-sim",
      expect.objectContaining({
        requestedBy: "PlayerOne",
        arguments: expect.objectContaining({ beneficiary: "PlayerOne" }),
      }),
      expect.any(String),
      { replaceConflictingDelivery: false },
    );
  });

  it("rejects a multi-agent image before starting any provider turn", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-team-image-reject-");
    await configureChat(service, { target: "multi-agent" });

    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "协作看看这张图",
      imagePath: "C:\\private\\screen.png",
      providerRole: "multi-agent",
    });
    const finished = await waitForRequest(driver, request.id);

    expect(finished).toMatchObject({ status: "failed", error: expect.stringContaining("不接受本地图片") });
    expect(fake.started.advisorPrompts).toHaveLength(0);
    expect(fake.started.coordinatorPrompts).toHaveLength(0);
    expect(fake.started.prompts).toHaveLength(0);
    expect(service.listTasks()).toHaveLength(0);
  });

  it("does not invoke the local model for chat routed to Antigravity MCP", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-antigravity-");
    await configureChat(service, { target: "antigravity-mcp" });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "反重力，今天去哪里探险？",
    });

    expect(routed).toEqual({ handled: false, request: null });
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("adds a custom Minecraft persona to the provider prompt", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-persona-");
    await configureChat(service, {
      persona: {
        mode: "custom",
        displayName: "Luna",
        personality: "Calm, curious, and protective.",
        speakingStyle: "Use short and warm replies.",
        memoryNotes: "PlayerOne likes spruce houses and dislikes spoilers.",
      },
    });

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "What should we build?",
    });
    await waitForRequest(driver, routed.request!.id);

    expect(routed.handled).toBe(true);
    expect(fake.started.prompts).toHaveLength(1);
    expect(fake.started.prompts[0]).toEqual(expect.any(String));
    const prompt = fake.started.prompts[0] as string;
    expect(prompt).toContain("Luna");
    expect(prompt).toContain("Calm, curious, and protective.");
    expect(prompt).toContain("Use short and warm replies.");
    expect(prompt).toContain("PlayerOne likes spruce houses and dislikes spoilers.");
    expect(prompt).toContain("untrusted JSON style data only");
    expect(prompt).toContain("Player message JSON");
    expect(prompt).toContain("language of the player's current message");
    expect(prompt).toContain("default to Simplified Chinese only when the language is unclear");
  });

  it("bypasses the model for immediate stop phrases", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-stop-");
    const task = service.assignTask("codex-sim", {
      kind: "explore",
      radius: 32,
      direction: "any",
      requestedBy: "test",
    }, "test");

    const routed = await driver.handleInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "急停",
    });
    expect(routed.request?.status).toBe("stopped");
    expect(service.getTask(task.id).status).toBe("cancelled");
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("runs deterministic tasks without waiting for a busy model turn", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-fast-task-");
    const sendChat = vi.spyOn(service, "sendChat");
    let releaseModel!: () => void;
    const modelBlocked = new Promise<void>((resolve) => {
      releaseModel = resolve;
    });
    fake.started.onRun = () => modelBlocked;

    const slow = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "陪我聊聊天",
    });
    for (let attempt = 0; attempt < 50 && driver.getRequest(slow.id)?.status !== "running"; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }

    const fast = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "撸点木头",
    });

    expect(fast).toMatchObject({
      status: "succeeded",
      reply: expect.stringMatching(/^好，我去采集 8 个原木，采完交给你。（任务 ID：[0-9a-f-]+）$/u),
    });
    expect(driver.getRequest(slow.id)?.status).toBe("running");
    expect(fake.started.prompts).toHaveLength(1);
    expect(sendChat.mock.calls[0]?.[3]).toEqual({ interactionId: fast.id, phase: "start" });

    releaseModel();
    await waitForRequest(driver, slow.id);
  });

  it("runs an unaddressed basic shelter phrase as an immediate local task", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-basic-shelter-chat-");
    await configureChat(service, { target: "antigravity-mcp" });

    const routed = await driver.handleImmediateInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "建一个基础住宅",
    });

    expect(routed).toMatchObject({
      handled: true,
      request: {
        status: "succeeded",
        reply: expect.stringMatching(/^好，我按安全模板开始建造基础住宅。（任务 ID：[0-9a-f-]+）$/u),
      },
    });
    expect(service.listTasks()).toContainEqual(expect.objectContaining({
      spec: expect.objectContaining({
        kind: "macro",
        skillId: "build.basic-shelter",
      }),
    }));
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("runs the exact food prompt immediately even when Antigravity is selected", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-provision-food-chat-");
    await configureChat(service, { target: "antigravity-mcp" });

    const routed = await driver.handleImmediateInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "去找些食物",
    });

    expect(routed).toMatchObject({
      handled: true,
      request: {
        status: "succeeded",
        reply: expect.stringMatching(/保留 8 份口粮。（任务 ID：[0-9a-f-]+）$/u),
      },
    });
    expect(service.listTasks()).toContainEqual(expect.objectContaining({
      spec: expect.objectContaining({
        kind: "provision-food",
        count: 8,
        source: "auto",
        requestedBy: "PlayerOne",
      }),
    }));
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("keeps the animal-pen build and isolates only the ranch step for armed Minecraft T chat", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-codex-ranch-chat-"));
    const service = new ControlService();
    const backend = new RecordingSimulatorBackend();
    service.registerBackend(backend);
    const fake = new FakeCodex();
    const driver = new CodexDriver({
      control: service,
      events: service.events,
      codex: fake,
      projectRoot: process.cwd(),
      stateDirectory,
    });
    await configureChat(service, { target: "antigravity-mcp" });
    service.armNextRanchChatFixture(backend.id);

    const routed = await driver.handleImmediateInGameChat({
      companionId: backend.id,
      sender: "PlayerOne",
      message: "建个围栏养两只牛",
    });
    expect(routed).toMatchObject({ handled: true, request: { status: "succeeded" } });
    const task = service.listTasks()[0]!;
    expect(await waitForTaskTerminal(service, task.id)).toMatchObject({
      status: "succeeded",
      spec: { kind: "macro", skillId: "life.establish-ranch" },
    });
    expect(backend.ranSpecs).toEqual([
      expect.objectContaining({
        kind: "build",
        planId: BUILTIN_BUILD_IDS.animalPen,
      }),
      expect.objectContaining({
        kind: "ranch",
        action: "establish",
        animalType: "minecraft:cow",
        count: 2,
        fixtureTag: "CodexAcceptanceRanchAnimal",
      }),
    ]);
    expect(backend.ranSpecs[0]).not.toHaveProperty("fixtureTag");

    backend.ranSpecs.length = 0;
    await driver.handleImmediateInGameChat({
      companionId: backend.id,
      sender: "PlayerOne",
      message: "建个围栏养两只牛",
    });
    const ordinaryTask = service.listTasks().find((candidate) => candidate.id !== task.id)!;
    expect(await waitForTaskTerminal(service, ordinaryTask.id)).toMatchObject({ status: "succeeded" });
    expect(backend.ranSpecs.map((spec) => spec.kind)).toEqual(["build", "ranch"]);
    expect(backend.ranSpecs.every((spec) => !("fixtureTag" in spec))).toBe(true);
    expect(fake.started.prompts).toHaveLength(0);
  }, 15_000);

  it("keeps build-menu selection in Minecraft and accepts a bare option number", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-build-menu-");
    await configureChat(service, { target: "antigravity-mcp" });

    const menu = await driver.handleImmediateInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "建造",
    });
    expect(menu).toMatchObject({
      handled: true,
      request: { status: "succeeded", reply: expect.stringContaining("8黑暗刷怪塔") },
    });

    const selected = await driver.handleImmediateInGameChat({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "8",
    });
    expect(selected).toMatchObject({
      handled: true,
      request: { status: "succeeded", reply: expect.stringContaining("黑暗刷怪塔") },
    });
    expect(service.listTasks()).toContainEqual(expect.objectContaining({
      spec: expect.objectContaining({ kind: "macro", skillId: "build.mob-farm" }),
    }));
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("answers status and inventory questions from the exact local snapshot", async () => {
    const { fake, driver } = await createHarness("mc-codex-local-inspection-");
    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "Codex，汇报完整状态",
    });

    expect(request.status).toBe("succeeded");
    expect(request.reply).toContain("生命 20/20");
    expect(request.reply).toContain("饱食度 18/20");
    expect(request.reply).toContain("面包×12（背包）");
    expect(request.reply).toContain("铁剑×1（背包）");
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("answers current-action questions locally without starting an AI turn", async () => {
    const { fake, driver } = await createHarness("mc-codex-local-activity-");
    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "AI 在干什么呀",
    });

    expect(request.status).toBe("succeeded");
    expect(request.reply).toContain("没有执行任务");
    expect(request.reply).toContain("待命");
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("reports authoritative macro child counts instead of multiplying the parent percentage", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-codex-macro-counts-"));
    const service = new ControlService();
    const backend = new HoldingCountProgressBackend();
    service.registerBackend(backend);
    const fake = new FakeCodex();
    const driver = new CodexDriver({
      control: service,
      events: service.events,
      codex: fake,
      projectRoot: process.cwd(),
      stateDirectory,
    });
    const task = service.assignTask(backend.id, {
      kind: "macro",
      skillId: "life.gather-and-deliver",
      arguments: { itemId: "minecraft:coal", count: 64, player: "PlayerOne" },
      requestedBy: "PlayerOne",
    }, "codex-driver");
    for (let attempt = 0; attempt < 100 && service.getTask(task.id).completedCount === undefined; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }

    try {
      const request = await driver.enqueue({
        companionId: backend.id,
        sender: "PlayerOne",
        message: "AI 在干什么呀",
      });

      expect(request.status).toBe("succeeded");
      expect(request.reply).toContain("进度 41%");
      expect(request.reply).toContain("步骤进度 83%");
      expect(request.reply).toContain("已完成 53/64");
      expect(request.reply).toContain("实际保有 53/64");
      expect(request.reply).not.toContain("26/64");
      expect(fake.started.prompts).toHaveLength(0);
    } finally {
      service.cancelTask(task.id, "test cleanup");
    }
  });

  it("answers item whereabouts only from the bounded local transaction ledger", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-codex-item-history-"));
    const service = new ControlService();
    service.registerBackend(new LedgerSimulatorBackend());
    const fake = new FakeCodex();
    const driver = new CodexDriver({
      control: service,
      events: service.events,
      codex: fake,
      projectRoot: process.cwd(),
      stateDirectory,
    });

    const coal = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "煤炭去哪了，是不是扔掉了？",
    });
    expect(coal.status).toBe("succeeded");
    expect(coal.reply).toContain("煤炭当前在背包中有 0 个");
    expect(coal.reply).toContain("采集收入 +26");
    expect(coal.reply).toContain("制作期间消耗或移出 -26");
    expect(coal.reply).toContain("没有明确的丢出记录，不能说是被扔掉了");

    const diamond = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "钻石去哪了？",
    });
    expect(diamond.status).toBe("succeeded");
    expect(diamond.reply).toContain("有限物品账本没有匹配记录");
    expect(diamond.reply).toContain("无法判断更早的去向");
    expect(fake.started.prompts).toHaveLength(0);
  });

  it("does not repeat a final reply already sent through mc_chat", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-chat-dedup-");
    const sendChat = vi.spyOn(service, "sendChat");
    fake.started.onRun = async () => {
      await service.sendChat("codex-sim", "Ready to help.", "codex-driver");
    };

    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "Can you help?",
    });
    const finished = await waitForRequest(driver, request.id);

    expect(finished.status).toBe("succeeded");
    expect(sendChat.mock.calls.map(([, message]) => message)).toEqual(["Ready to help."]);
  });

  it("preserves a distinct mc_chat progress update before the final reply", async () => {
    const { service, fake, driver } = await createHarness("mc-codex-chat-progress-");
    const sendChat = vi.spyOn(service, "sendChat");
    fake.started.onRun = async () => {
      await service.sendChat("codex-sim", "Checking the area.", "codex-driver");
    };

    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "PlayerOne",
      message: "Can you help?",
    });
    const finished = await waitForRequest(driver, request.id);

    expect(finished.status).toBe("succeeded");
    expect(sendChat.mock.calls.map(([, message]) => message)).toEqual([
      "Checking the area.",
      "Ready to help.",
    ]);
  });

  it("uses the persona profile matching each NPC name", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-codex-persona-by-npc-"));
    const service = new ControlService({ stateDirectory });
    service.registerBackend(new SimulatorBackend("npc-aster", "Aster"));
    service.registerBackend(new SimulatorBackend("npc-luna", "Luna"));
    for (const [companionName, personality] of [["Aster", "安静可靠"], ["Luna", "活泼好奇"]] as const) {
      await service.updateChatSettings({
        freeChatEnabled: true,
        playerName: "Player",
        companionName,
        target: "active-provider",
        persona: { ...inheritedPersona, mode: "custom", displayName: companionName, personality },
      });
    }
    const fake = new PerNpcFakeCodex();
    const driver = new CodexDriver({
      control: service,
      codex: fake,
      projectRoot: process.cwd(),
      stateDirectory,
    });

    for (const companionId of ["npc-aster", "npc-luna"]) {
      const routed = await driver.handleInGameChat({ companionId, sender: "Player", message: "聊聊天" });
      expect(routed.handled).toBe(true);
      await waitForRequest(driver, routed.request!.id);
    }

    expect(fake.threads).toHaveLength(2);
    expect(String(fake.threads[0]?.prompts[0])).toContain("安静可靠");
    expect(String(fake.threads[0]?.prompts[0])).not.toContain("活泼好奇");
    expect(String(fake.threads[1]?.prompts[0])).toContain("活泼好奇");
    expect(String(fake.threads[1]?.prompts[0])).not.toContain("安静可靠");
  });

  it("times out a stuck model turn and continues the same request queue", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-codex-timeout-"));
    const service = new ControlService();
    service.registerBackend(new SimulatorBackend());
    await configureChat(service, { actionMode: "stable" });
    let calls = 0;
    const signals: AbortSignal[] = [];
    const thread = {
      id: "timeout-thread",
      run: (_input: unknown, options?: { signal?: AbortSignal }) => {
        calls += 1;
        if (options?.signal) signals.push(options.signal);
        if (calls === 1) return new Promise<{ finalResponse: string }>(() => undefined);
        return Promise.resolve({
          finalResponse: JSON.stringify({ reply: "队列已恢复。", acted: false, summary: "recovered" }),
        });
      },
      runWithPolicy(input: unknown, options?: { signal?: AbortSignal }) {
        return this.run(input, options);
      },
    };
    const codex = { startThread: () => thread, resumeThread: () => thread };
    const driver = new CodexDriver({
      control: service,
      codex,
      projectRoot: process.cwd(),
      stateDirectory,
      modelTurnTimeoutMs: 25,
    });

    const stuck = await driver.enqueue({ companionId: "codex-sim", sender: "Player", message: "第一条" });
    const next = await driver.enqueue({ companionId: "codex-sim", sender: "Player", message: "第二条" });
    const failed = await waitForRequest(driver, stuck.id);
    const recovered = await waitForRequest(driver, next.id);

    expect(failed).toMatchObject({ status: "failed", error: expect.stringContaining("超时") });
    expect(signals[0]?.aborted).toBe(true);
    expect(recovered).toMatchObject({ status: "succeeded", reply: "队列已恢复。" });
    expect(calls).toBe(2);
  });

  it("redacts labeled credentials and Base URLs before any provider prompt", async () => {
    const { fake, driver } = await createHarness("mc-codex-redacted-chat-");
    const request = await driver.enqueue({
      companionId: "codex-sim",
      sender: "Player",
      message: "api_key=private-value base_url=https://private.example.test/v1 帮我看看",
    });
    const finished = await waitForRequest(driver, request.id);
    const prompt = String(fake.started.prompts[0]);

    expect(finished.message).not.toContain("private-value");
    expect(finished.message).not.toContain("private.example.test");
    expect(prompt).not.toContain("private-value");
    expect(prompt).not.toContain("private.example.test");
    expect(prompt).toContain("[REDACTED_SECRET]");
  });
});
