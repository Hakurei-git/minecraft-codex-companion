import fastifyStatic from "@fastify/static";
import type { FastifyInstance } from "fastify";

export async function registerDashboardAssets(app: FastifyInstance, dashboardDist: string): Promise<void> {
  await app.register(fastifyStatic, { root: dashboardDist });
}
