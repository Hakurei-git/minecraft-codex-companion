import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  loopbackBase,
  parseOptions,
  respawnMinecraftForTest,
} from "./respawn-minecraft-for-test.mjs";

test("test respawn accepts loopback control services only", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8776/api/health").href, "http://127.0.0.1:8776/");
  assert.throws(() => loopbackBase("https://example.com"), /loopback HTTP/u);
});

test("test respawn uses the fixed client fixture and waits for gameplay", async () => {
  let posted = false;
  const methods = [];
  const fetchImpl = async (url, options = {}) => {
    const method = options.method ?? "GET";
    methods.push(method);
    if (url.pathname === "/api/companions") {
      return Response.json({
        companions: [{ id: "codex-forge", backend: "forge-1.20.1", connected: true }],
      });
    }
    if (url.pathname === "/api/companions/codex-forge/snapshot") {
      return Response.json({ clientUiState: posted ? "gameplay" : "death" });
    }
    if (url.pathname === "/api/companions/codex-forge/live-fixtures" && method === "POST") {
      assert.deepEqual(JSON.parse(options.body), { suite: "client-recovery", mode: "respawn" });
      posted = true;
      return Response.json({ ok: true, suite: "client-recovery", mode: "respawn" }, { status: 202 });
    }
    return new Response(null, { status: 404 });
  };

  const result = await respawnMinecraftForTest({
    baseUrl: "http://localhost:8776",
    companionId: "codex-forge",
    waitSeconds: 1,
  }, {
    fetchImpl,
    sleep: async () => {},
  });

  assert.equal(result.initialClientUiState, "death");
  assert.equal(result.finalClientUiState, "gameplay");
  assert.equal(result.respawnRequested, true);
  assert.deepEqual(result.httpMethodsUsed, ["GET", "POST"]);
  assert.deepEqual(new Set(methods), new Set(["GET", "POST"]));
  assert.equal(result.mouseOrKeyboardInputUsed, false);
  assert.equal(result.windowMutationUsed, false);
  assert.equal(result.cursorMutationUsed, false);
});

test("test respawn is a no-op when Minecraft is already in gameplay", async () => {
  const methods = [];
  const fetchImpl = async (url, options = {}) => {
    methods.push(options.method ?? "GET");
    if (url.pathname === "/api/companions") {
      return Response.json({
        companions: [{ id: "codex-neoforge", backend: "neoforge-1.21.1", connected: true }],
      });
    }
    if (url.pathname.endsWith("/snapshot")) return Response.json({ clientUiState: "gameplay" });
    return new Response(null, { status: 404 });
  };

  const result = await respawnMinecraftForTest({
    baseUrl: "http://127.0.0.1:8776",
    companionId: "codex-neoforge",
    waitSeconds: 1,
  }, { fetchImpl });

  assert.equal(result.respawnRequested, false);
  assert.deepEqual(new Set(methods), new Set(["GET"]));
});

test("test respawn options are bounded and desktop automation stays absent", async () => {
  assert.deepEqual(parseOptions([
    "--url=http://127.0.0.1:8776",
    "--companion-id=codex-forge",
    "--wait-seconds=30",
  ]), {
    baseUrl: "http://127.0.0.1:8776",
    companionId: "codex-forge",
    waitSeconds: 30,
  });
  assert.throws(() => parseOptions(["--wait-seconds=0"]), /1 through 60/u);

  const source = await readFile(new URL("./respawn-minecraft-for-test.mjs", import.meta.url), "utf8");
  assert.doesNotMatch(
    source,
    /PostMessage|ShowWindow|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event/u,
  );

  const clientSource = await readFile(new URL(
    "../mods/forge-1.20.1/src/main/java/cn/codex/minecraftbridge/client/ClientRecoveryLiveFixture.java",
    import.meta.url,
  ), "utf8");
  assert.match(clientSource, /minecraft\.player\.respawn\(\)/u);
  assert.doesNotMatch(
    clientSource,
    /GLFW|java\.awt\.Robot|User32|PostMessage|SetForegroundWindow|ClipCursor|SendInput/u,
  );

  const desktopSafetySource = await readFile(new URL(
    "../mods/forge-1.20.1/src/main/java/cn/codex/minecraftbridge/client/ClientDesktopSafetyLiveFixture.java",
    import.meta.url,
  ), "utf8");
  const mouseMixinSource = await readFile(new URL(
    "../mods/forge-1.20.1/src/main/java/cn/codex/minecraftbridge/client/mixin/ClientMouseHandlerMixin.java",
    import.meta.url,
  ), "utf8");
  assert.match(desktopSafetySource, /"MC_ENABLE_LIVE_FIXTURES"/u);
  assert.match(desktopSafetySource, /return "1"\.equals\(configuredValue\)/u);
  assert.match(
    mouseMixinSource,
    /@Inject\(method = "grabMouse", at = @At\("HEAD"\), cancellable = true\)/u,
  );
  assert.match(mouseMixinSource, /ClientDesktopSafetyLiveFixture\.suppressMouseGrab\(\)/u);
  assert.match(mouseMixinSource, /callback\.cancel\(\)/u);
  assert.doesNotMatch(
    `${desktopSafetySource}\n${mouseMixinSource}`,
    /GLFW|java\.awt\.Robot|User32|PostMessage|SetWindowPos|ShowWindow|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event/u,
  );
});
