import assert from "node:assert/strict";
import test from "node:test";

import { loopbackBase, parseOptions, waitForManualChat } from "./wait-for-manual-minecraft-chat.mjs";

test("manual T-chat observer accepts loopback only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8776/api/health").href, "http://127.0.0.1:8776/");
  assert.throws(() => loopbackBase("https://example.com"), /loopback HTTP/u);
});

test("manual T-chat observer records a real incoming bridge count and new task using GET only", async () => {
  let healthReads = 0;
  const methods = [];
  const fetchImpl = async (url, init = {}) => {
    methods.push(init.method ?? "GET");
    if (url.pathname === "/api/health") {
      healthReads++;
      return Response.json({
        minecraftBridge: {
          connections: [{
            companionId: "codex-forge",
            backend: "forge-1.20.1",
            connected: true,
            incomingChatCount: healthReads >= 2 ? 8 : 7,
            deliveredChatCount: healthReads >= 2 ? 5 : 4,
            roundTripCount: healthReads >= 2 ? 5 : 4,
          }],
        },
      });
    }
    if (url.pathname === "/api/tasks") {
      return Response.json({
        tasks: healthReads >= 2
          ? [{ id: "task-new", companionId: "codex-forge", status: "running", spec: { kind: "move" } }]
          : [],
      });
    }
    return new Response(null, { status: 404 });
  };
  const instructions = [];
  const result = await waitForManualChat({
    baseUrl: "http://localhost:8776",
    companionId: "codex-forge",
    prompt: "向前走5格，不要传送",
    waitSeconds: 10,
    requireTask: true,
    expectedTaskKind: "move",
  }, {
    fetchImpl,
    sleep: async () => {},
    onInstruction: (message) => instructions.push(message),
  });

  assert.equal(result.manualSubmissionObserved, true);
  assert.equal(result.initialIncomingChatCount, 7);
  assert.equal(result.finalIncomingChatCount, 8);
  assert.deepEqual(result.newTasks, [{ id: "task-new", kind: "move", status: "running" }]);
  assert.deepEqual(result.httpMethodsUsed, ["GET"]);
  assert.deepEqual(new Set(methods), new Set(["GET"]));
  assert.match(instructions[0], /press T and submit exactly/u);
  assert.equal(result.mouseOrKeyboardInputUsedByObserver, false);
  assert.equal(result.windowMutationUsedByObserver, false);
  assert.equal(result.cursorMutationUsedByObserver, false);
});

test("manual T-chat options require a bounded explicit prompt", () => {
  assert.deepEqual(parseOptions([
    "--url=http://127.0.0.1:8776",
    "--prompt=测试",
    "--wait-seconds=30",
    "--expected-task-kind=move",
  ]), {
    baseUrl: "http://127.0.0.1:8776",
    companionId: "",
    prompt: "测试",
    waitSeconds: 30,
    requireTask: true,
    expectedTaskKind: "move",
  });
  assert.throws(() => parseOptions(["--prompt="]), /1-256/u);
});
