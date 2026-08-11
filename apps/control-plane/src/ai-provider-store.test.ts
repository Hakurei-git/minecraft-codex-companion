import { mkdtemp, readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { AiProviderStore } from "./ai-provider-store.js";
import type { SecretProtector } from "./secret-protector.js";
import { WindowsDpapiSecretProtector } from "./secret-protector.js";

const packagedDpapiHelper = process.env.MC_COMPANION_SECRET_HELPER
  ?? path.resolve("build/portable/MinecraftCodexCompanion-Portable/runtime/MinecraftCodexSecret.exe");

class TestProtector implements SecretProtector {
  async protect(value: string): Promise<string> {
    return `test:${Buffer.from(value, "utf8").toString("base64")}`;
  }

  async unprotect(value: string): Promise<string> {
    return Buffer.from(value.slice("test:".length), "base64").toString("utf8");
  }
}

describe("AiProviderStore", () => {
  it("persists provider metadata without exposing the API key", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-ai-provider-"));
    const store = new AiProviderStore({
      stateDirectory,
      mcpUrl: "http://127.0.0.1:8765/mcp",
      protector: new TestProtector(),
    });
    const created = await store.create({
      kind: "codex-api",
      name: "Private Codex",
      baseUrl: "https://gateway.example.test/v1/",
      model: "codex-private",
      apiKey: "sk-private-value",
    });

    expect(created.hasApiKey).toBe(true);
    expect(created.baseUrl).toBe("https://gateway.example.test/v1");
    const persisted = await readFile(path.join(stateDirectory, "ai-providers.json"), "utf8");
    expect(persisted).not.toContain("sk-private-value");
    expect(persisted).toContain("test:");
    expect((await store.runtime(created.id)).apiKey).toBe("sk-private-value");

    await store.update(created.id, {
      kind: "codex-api",
      name: "Renamed Codex",
      baseUrl: "https://gateway.example.test/v1",
      model: "codex-private-v2",
    });
    expect((await store.runtime(created.id)).apiKey).toBe("sk-private-value");
    await store.activate(created.id);

    const reloaded = new AiProviderStore({
      stateDirectory,
      mcpUrl: "http://127.0.0.1:8765/mcp",
      protector: new TestProtector(),
    });
    expect((await reloaded.list()).find((profile) => profile.id === created.id)?.active).toBe(true);
    await reloaded.remove(created.id);
    expect((await reloaded.list()).find((profile) => profile.id === "codex-cli")?.active).toBe(true);
  });

  it("keeps Antigravity as an external MCP controller", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-ai-antigravity-"));
    const store = new AiProviderStore({
      stateDirectory,
      mcpUrl: "http://127.0.0.1:8765/mcp",
      protector: new TestProtector(),
    });
    const antigravity = await store.get("antigravity-mcp");
    expect(antigravity.executable).toBe(false);
    expect(antigravity.mcpUrl).toBe("http://127.0.0.1:8765/mcp");
    await expect(store.activate(antigravity.id)).rejects.toThrow(/外部 MCP/);
  });

  it("rejects embedded credentials, insecure remote APIs, and non-allowlisted remote MCP hosts", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-ai-network-policy-"));
    const store = new AiProviderStore({
      stateDirectory,
      mcpUrl: "http://127.0.0.1:8765/mcp",
      protector: new TestProtector(),
    });
    await expect(store.create({
      kind: "codex-api",
      name: "Embedded credential",
      baseUrl: "https://user:password@gateway.example.test/v1",
      model: "model",
    })).rejects.toThrow(/credentials/i);
    await expect(store.create({
      kind: "claude-api",
      name: "Plain HTTP",
      baseUrl: "http://gateway.example.test/v1",
      model: "model",
    })).rejects.toThrow(/HTTPS/i);
    await expect(store.create({
      kind: "antigravity-mcp",
      name: "Remote MCP",
      mcpUrl: "https://mcp.example.test/mcp",
    })).rejects.toThrow(/allowlist/i);
  });

  it.runIf(process.platform === "win32" && existsSync(packagedDpapiHelper))("round-trips secrets through Windows DPAPI", async () => {
    const protector = new WindowsDpapiSecretProtector({ helperPath: packagedDpapiHelper });
    const encrypted = await protector.protect("dpapi-test-secret");
    expect(encrypted).toMatch(/^dpapi:v1:/);
    expect(encrypted).not.toContain("dpapi-test-secret");
    expect(await protector.unprotect(encrypted)).toBe("dpapi-test-secret");
  }, 20_000);
});
