import { randomUUID } from "node:crypto";
import { chatSettingsSchema, type GameEvent } from "@mc/protocol";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GameEventReactor, respondToGameEvents } from "./game-event-reactor.js";
import { ControlService } from "./control-service.js";
import { SimulatorBackend } from "./simulator-backend.js";
import type { AntigravityAgentBridge } from "./antigravity-agent-bridge.js";

const settings = () => chatSettingsSchema.parse({
  freeChatEnabled: true, playerName: "Crow", companionName: "唯", target: "antigravity-mcp",
  persona: { mode: "inherit", displayName: "", personality: "", speakingStyle: "", memoryNotes: "" },
  updatedAt: new Date().toISOString(),
});
const event = (changes: Partial<GameEvent> = {}): GameEvent => ({
  type: "game-event", companionId: "codex-sim", messageId: randomUUID(),
  kind: "death", playerName: "Crow", message: "Crow 摔死了", at: new Date().toISOString(), ...changes,
});

describe("game event reactions", () => {
  const reactors: GameEventReactor[] = [];
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-17T10:00:00Z")); });
  afterEach(() => { reactors.forEach((reactor) => reactor.close()); reactors.length = 0; vi.useRealTimers(); });
  function setup() {
    const current = settings();
    const react = vi.fn().mockResolvedValue(undefined);
    const onError = vi.fn();
    const reactor = new GameEventReactor({ settings: async () => current, react, onError });
    reactors.push(reactor);
    return { reactor, current, react, onError };
  }

  it("filters other players, stale broadcasts and disabled free chat", async () => {
    const { reactor, current, react } = setup();
    await reactor.handle(event({ playerName: "Crow2" }));
    await reactor.handle(event({ at: new Date(Date.now() - 61_000).toISOString() }));
    await reactor.handle(event({ at: new Date(Date.now() + 6_000).toISOString() }));
    current.freeChatEnabled = false;
    await reactor.handle(event());
    await vi.advanceTimersByTimeAsync(20_000);
    expect(react).not.toHaveBeenCalled();
  });

  it("deduplicates retries and merges an advancement burst into one response", async () => {
    const { reactor, react } = setup();
    const death = event();
    await reactor.handle(death);
    await reactor.handle(death);
    await reactor.handle(event());
    await reactor.handle(event({ kind: "advancement", message: "Crow 完成了挑战" }));
    await vi.advanceTimersByTimeAsync(1_500);
    expect(react).toHaveBeenCalledTimes(1);
    expect(react.mock.calls[0]![0]).toHaveLength(2);
  });

  it("retains the cooldown after an idle batch and rechecks settings before responding", async () => {
    const { reactor, current, react } = setup();
    await reactor.handle(event());
    await vi.advanceTimersByTimeAsync(1_500);
    await reactor.handle(event({ kind: "respawn", message: "Crow 已重生" }));
    await vi.advanceTimersByTimeAsync(7_000);
    expect(react).toHaveBeenCalledTimes(1);
    current.freeChatEnabled = false;
    await vi.advanceTimersByTimeAsync(1_000);
    expect(react).toHaveBeenCalledTimes(1);
  });

  it("bounds busy batches, preserves death and discards expired queued events", async () => {
    const { reactor, react } = setup();
    for (let index = 0; index < 20; index++) await reactor.handle(event({ kind: "advancement", message: `成就 ${index}` }));
    await reactor.handle(event());
    let finish!: () => void;
    react.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve; }));
    await vi.advanceTimersByTimeAsync(1_500);
    expect(react.mock.calls[0]![0]).toHaveLength(8);
    expect(react.mock.calls[0]![0].some((item: GameEvent) => item.kind === "death")).toBe(true);
    await reactor.handle(event({ kind: "respawn", message: "Crow 已重生" }));
    await vi.advanceTimersByTimeAsync(61_000);
    finish();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(react).toHaveBeenCalledTimes(1);
  });

  it("cancels pending callbacks when the service closes", async () => {
    const { reactor, react } = setup();
    await reactor.handle(event());
    reactor.close();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(react).not.toHaveBeenCalled();
  });

  it("constrains Antigravity notifications to a one-use chat decision", async () => {
    vi.useRealTimers();
    const control = new ControlService();
    control.registerBackend(new SimulatorBackend());
    const trigger = vi.fn<AntigravityAgentBridge["trigger"]>(async (_message, _settings, context) => {
      if (!context?.interactionId) throw new Error("missing notification context");
      expect(context.gameEvents).toHaveLength(1);
      await expect(control.submitAiDecision(context.interactionId, { type: "control", action: "follow", reply: "跟随", summary: "不允许" }))
        .rejects.toMatchObject({ code: "GAME_EVENT_REPLY_ONLY" });
      await control.submitAiDecision(context.interactionId, { type: "chat", reply: "别怕，我在呢", summary: "回应死亡通知" });
      await expect(control.submitAiDecision(context.interactionId, { type: "chat", reply: "重复", summary: "重复" }))
        .rejects.toMatchObject({ code: "AI_DECISION_NOT_PENDING" });
    });
    const startThread = vi.fn();
    await respondToGameEvents([event()], settings(), { control, antigravity: { trigger }, codex: { startThread } });
    expect(trigger).toHaveBeenCalledTimes(1);
    expect(startThread).not.toHaveBeenCalled();
    expect(control.listTasks()).toHaveLength(0);
  });

  it("uses a tool-free provider turn and returns its reply to Minecraft", async () => {
    vi.useRealTimers();
    const runWithPolicy = vi.fn(async () => ({ finalResponse: JSON.stringify({ reply: "恭喜你！", acted: false, summary: "成就回应" }) }));
    const control = new ControlService();
    control.registerBackend(new SimulatorBackend());
    const sendChat = vi.spyOn(control, "sendChat");
    const trigger = vi.fn();
    await respondToGameEvents([event({ kind: "advancement" })], { ...settings(), target: "active-provider" }, {
      control, antigravity: { trigger }, codex: { startThread: () => ({ id: null, run: vi.fn(), runWithPolicy }) },
    });
    expect(runWithPolicy).toHaveBeenCalledWith(expect.stringContaining("不是玩家指令"), expect.anything(), { toolPolicy: "none", tokenBudget: 512 });
    expect(sendChat).toHaveBeenCalledWith("codex-sim", "恭喜你！", "game-event-reaction", expect.objectContaining({ phase: "chat" }));
    expect(trigger).not.toHaveBeenCalled();
    expect(control.listTasks()).toHaveLength(0);
  });

  it("accepts a delayed MCP reply after Antigravity has already reported idle", async () => {
    vi.useRealTimers();
    const control = new ControlService();
    control.registerBackend(new SimulatorBackend());
    let interactionId = "";
    let finished = false;
    const trigger = vi.fn<AntigravityAgentBridge["trigger"]>(async (_message, _settings, context) => {
      interactionId = context!.interactionId!;
    });
    const reaction = respondToGameEvents([event()], settings(), {
      control, antigravity: { trigger }, codex: { startThread: vi.fn() },
    }).then(() => { finished = true; });
    await new Promise((resolve) => setTimeout(resolve, 25));
    expect(trigger).toHaveBeenCalledTimes(1);
    expect(finished).toBe(false);
    await control.submitAiDecision(interactionId, { type: "chat", reply: "小心脚下呀", summary: "回应死亡通知" });
    await reaction;
    expect(finished).toBe(true);
    expect(control.listTasks()).toHaveLength(0);
  });

  it("expires an empty Antigravity reply and releases its pending decision", async () => {
    const control = new ControlService();
    control.registerBackend(new SimulatorBackend());
    let interactionId = "";
    const reaction = respondToGameEvents([event()], settings(), {
      control,
      antigravity: { trigger: async (_message, _settings, context) => { interactionId = context!.interactionId!; } },
      codex: { startThread: vi.fn() },
    });
    const rejected = expect(reaction).rejects.toThrow("等待反重力游戏事件回复超时");
    await vi.advanceTimersByTimeAsync(90_000);
    await rejected;
    await expect(control.submitAiDecision(interactionId, { type: "chat", reply: "迟到", summary: "迟到" }))
      .rejects.toMatchObject({ code: "AI_DECISION_NOT_PENDING" });
  });

  it("cleans up the reply wait immediately when Antigravity fails", async () => {
    const control = new ControlService();
    control.registerBackend(new SimulatorBackend());
    let interactionId = "";
    await expect(respondToGameEvents([event()], settings(), {
      control,
      antigravity: { trigger: async (_message, _settings, context) => {
        interactionId = context!.interactionId!;
        throw new Error("upstream unavailable");
      } },
      codex: { startThread: vi.fn() },
    })).rejects.toThrow("upstream unavailable");
    expect(vi.getTimerCount()).toBe(0);
    await expect(control.submitAiDecision(interactionId, { type: "chat", reply: "迟到", summary: "迟到" }))
      .rejects.toMatchObject({ code: "AI_DECISION_NOT_PENDING" });
  });

  it("rejects an action claim from the event-only model", async () => {
    const control = new ControlService();
    control.registerBackend(new SimulatorBackend());
    const sendChat = vi.spyOn(control, "sendChat");
    await expect(respondToGameEvents([event()], { ...settings(), target: "active-provider" }, {
      control, antigravity: { trigger: vi.fn() }, codex: { startThread: () => ({ id: null, run: vi.fn(), runWithPolicy: async () => ({ finalResponse: '{"reply":"已复活","acted":true}' }) }) },
    })).rejects.toThrow("游戏事件回应格式无效");
    expect(sendChat).not.toHaveBeenCalled();
  });
});
