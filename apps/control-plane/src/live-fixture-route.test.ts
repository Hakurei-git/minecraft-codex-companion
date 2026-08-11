import Fastify from "fastify";
import type { BridgeCommand } from "@mc/protocol";
import { afterEach, describe, expect, it } from "vitest";
import { ControlService } from "./control-service.js";
import { ControlError } from "./errors.js";
import {
  isLoopbackAddress,
  liveFixturesEnabled,
  registerLiveFixtureRoute,
} from "./live-fixture-route.js";
import { SimulatorBackend } from "./simulator-backend.js";

class LiveFixtureBackend extends SimulatorBackend {
  readonly commands: BridgeCommand[] = [];

  async sendBridgeCommand(command: BridgeCommand): Promise<void> {
    this.commands.push(command);
  }
}

function testApp(service: ControlService) {
  const app = Fastify();
  registerLiveFixtureRoute(app, service);
  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof ControlError) {
      return reply.code(error.statusCode).send({ error: { code: error.code } });
    }
    if (error && typeof error === "object" && "issues" in error) {
      return reply.code(400).send({ error: { code: "INVALID_INPUT" } });
    }
    return reply.code(500).send({ error: { code: "INTERNAL_ERROR" } });
  });
  return app;
}

describe("live fixture route", () => {
  const apps: ReturnType<typeof testApp>[] = [];

  afterEach(async () => {
    await Promise.all(apps.splice(0).map((app) => app.close()));
  });

  it("is enabled only by the exact opt-in value", () => {
    expect(liveFixturesEnabled({ MC_ENABLE_LIVE_FIXTURES: "1" })).toBe(true);
    expect(liveFixturesEnabled({ MC_ENABLE_LIVE_FIXTURES: "true" })).toBe(false);
    expect(liveFixturesEnabled({})).toBe(false);
  });

  it("recognizes IPv4, IPv6, and mapped loopback addresses only", () => {
    expect(isLoopbackAddress("127.0.0.1")).toBe(true);
    expect(isLoopbackAddress("::1")).toBe(true);
    expect(isLoopbackAddress("::ffff:127.0.0.1")).toBe(true);
    expect(isLoopbackAddress("192.0.2.10")).toBe(false);
  });

  it("forwards an enumerated fixture as a bridge command", async () => {
    const service = new ControlService();
    const backend = new LiveFixtureBackend();
    service.registerBackend(backend);
    const app = testApp(service);
    apps.push(app);

    const response = await app.inject({
      method: "POST",
      url: `/api/companions/${backend.id}/live-fixtures`,
      payload: { suite: "dragon", mode: "spawn-book" },
    });

    expect(response.statusCode).toBe(202);
    expect(response.json()).toEqual({ ok: true, suite: "dragon", mode: "spawn-book" });
    expect(backend.commands).toEqual([{ type: "live-fixture", suite: "dragon", mode: "spawn-book" }]);
  });

  it("forwards the strict crafting and sixteen-meat fixture modes", async () => {
    const service = new ControlService();
    const backend = new LiveFixtureBackend();
    service.registerBackend(backend);
    const app = testApp(service);
    apps.push(app);

    for (const payload of [
      { suite: "resource-priority", mode: "setup-fishing" },
      { suite: "resource-priority", mode: "setup-torches" },
      { suite: "resource-priority", mode: "inspect-craft" },
      { suite: "food-survival", mode: "setup-16" },
    ] as const) {
      const response = await app.inject({
        method: "POST",
        url: `/api/companions/${backend.id}/live-fixtures`,
        payload,
      });
      expect(response.statusCode).toBe(202);
    }

    expect(backend.commands).toEqual([
      { type: "live-fixture", suite: "resource-priority", mode: "setup-fishing" },
      { type: "live-fixture", suite: "resource-priority", mode: "setup-torches" },
      { type: "live-fixture", suite: "resource-priority", mode: "inspect-craft" },
      { type: "live-fixture", suite: "food-survival", mode: "setup-16" },
    ]);
  });

  it("arms the next ranch chat acceptance locally without forwarding a fake world command", async () => {
    const service = new ControlService();
    const backend = new LiveFixtureBackend();
    service.registerBackend(backend);
    const app = testApp(service);
    apps.push(app);

    const response = await app.inject({
      method: "POST",
      url: `/api/companions/${backend.id}/live-fixtures`,
      payload: { suite: "ranch", mode: "arm-chat-establish" },
    });

    expect(response.statusCode).toBe(202);
    expect(response.json()).toEqual({ ok: true, suite: "ranch", mode: "arm-chat-establish" });
    expect(backend.commands).toEqual([]);
  });

  it("rejects arbitrary fields and mismatched modes", async () => {
    const service = new ControlService();
    const backend = new LiveFixtureBackend();
    service.registerBackend(backend);
    const app = testApp(service);
    apps.push(app);

    const arbitrary = await app.inject({
      method: "POST",
      url: `/api/companions/${backend.id}/live-fixtures`,
      payload: { suite: "dragon", mode: "spawn-book", command: "/say no" },
    });
    const mismatch = await app.inject({
      method: "POST",
      url: `/api/companions/${backend.id}/live-fixtures`,
      payload: { suite: "combat", mode: "fishing" },
    });

    expect(arbitrary.statusCode).toBe(400);
    expect(mismatch.statusCode).toBe(400);
    expect(backend.commands).toEqual([]);
  });

  it("rejects non-loopback callers", async () => {
    const service = new ControlService();
    const backend = new LiveFixtureBackend();
    service.registerBackend(backend);
    const app = testApp(service);
    apps.push(app);

    const response = await app.inject({
      method: "POST",
      url: `/api/companions/${backend.id}/live-fixtures`,
      remoteAddress: "192.0.2.10",
      payload: { suite: "follow", mode: "move-ground" },
    });

    expect(response.statusCode).toBe(403);
    expect(backend.commands).toEqual([]);
  });

  it("rejects backends without bridge command support", async () => {
    const service = new ControlService();
    const backend = new SimulatorBackend();
    service.registerBackend(backend);
    const app = testApp(service);
    apps.push(app);

    const response = await app.inject({
      method: "POST",
      url: `/api/companions/${backend.id}/live-fixtures`,
      payload: { suite: "life-skill", mode: "fishing" },
    });

    expect(response.statusCode).toBe(422);
    expect(response.json()).toEqual({ error: { code: "LIVE_FIXTURE_UNSUPPORTED" } });
  });
});
