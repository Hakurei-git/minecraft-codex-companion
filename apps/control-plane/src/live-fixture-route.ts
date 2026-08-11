import { isIP } from "node:net";
import type { FastifyInstance } from "fastify";
import { liveFixtureRequestSchema } from "@mc/protocol";
import type { ControlService } from "./control-service.js";
import { ControlError } from "./errors.js";

export function liveFixturesEnabled(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.MC_ENABLE_LIVE_FIXTURES === "1";
}

export function isLoopbackAddress(address: string): boolean {
  if (address === "::1" || address === "0:0:0:0:0:0:0:1") return true;
  const ipv4 = address.startsWith("::ffff:") ? address.slice("::ffff:".length) : address;
  return isIP(ipv4) === 4 && ipv4.startsWith("127.");
}

export function registerLiveFixtureRoute(app: FastifyInstance, service: ControlService): void {
  app.post<{ Params: { id: string }; Body: unknown }>(
    "/api/companions/:id/live-fixtures",
    async (request, reply) => {
      if (!isLoopbackAddress(request.ip)) {
        throw new ControlError({
          code: "LIVE_FIXTURE_LOOPBACK_ONLY",
          message: "Live fixtures are restricted to loopback clients",
          statusCode: 403,
        });
      }
      const fixture = liveFixtureRequestSchema.parse(request.body);
      if (fixture.suite === "ranch" && fixture.mode === "arm-chat-establish") {
        service.armNextRanchChatFixture(request.params.id);
        return reply.code(202).send({ ok: true, suite: fixture.suite, mode: fixture.mode });
      }
      if (fixture.suite === "ranch" && fixture.mode === "cleanup") {
        service.clearRanchChatFixture(request.params.id);
      }
      await service.runLiveFixture(request.params.id, fixture);
      return reply.code(202).send({ ok: true, suite: fixture.suite, mode: fixture.mode });
    },
  );
}
