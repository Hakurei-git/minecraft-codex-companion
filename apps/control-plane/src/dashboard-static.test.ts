import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import Fastify from "fastify";
import { afterEach, describe, expect, it } from "vitest";
import { registerDashboardAssets } from "./dashboard-static.js";

const temporaryDirectories: string[] = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe("registerDashboardAssets", () => {
  it("serves built assets with their real content types", async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), "mc-dashboard-static-"));
    temporaryDirectories.push(root);
    await mkdir(path.join(root, "assets"));
    await writeFile(path.join(root, "index.html"), '<div id="root"></div>', "utf8");
    await writeFile(path.join(root, "assets", "app.js"), "globalThis.dashboardLoaded = true;", "utf8");

    const app = Fastify();
    await registerDashboardAssets(app, root);
    try {
      const index = await app.inject({ method: "GET", url: "/" });
      expect(index.statusCode).toBe(200);
      expect(index.headers["content-type"]).toContain("text/html");
      expect(index.body).toContain('id="root"');

      const asset = await app.inject({ method: "GET", url: "/assets/app.js" });
      expect(asset.statusCode).toBe(200);
      expect(asset.headers["content-type"]).toContain("javascript");
      expect(asset.body).toContain("dashboardLoaded");
    } finally {
      await app.close();
    }
  });
});
