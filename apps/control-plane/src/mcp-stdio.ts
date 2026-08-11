#!/usr/bin/env node
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import { HttpControlClient } from "./control-api.js";
import { createMinecraftMcpServer } from "./mcp-server.js";
import { resolveStateDirectory } from "./runtime-config.js";
import { assertNetworkTargetAllowed, redactSensitiveData, redactSensitiveText } from "./skill-security.js";

const controlUrl = process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765";
assertNetworkTargetAllowed(controlUrl, process.env.MC_MCP_ALLOW_HOSTS);
const server = createMinecraftMcpServer(new HttpControlClient(controlUrl));
const transport = new StdioServerTransport();

await server.connect(transport as unknown as Transport);
console.error(redactSensitiveText(`Minecraft Codex Companion MCP connected to ${controlUrl}`));

// Keep a small, secret-free capability snapshot so the desktop client can
// distinguish a normal tool-only MCP host from one that can service
// server-initiated sampling requests. This is especially useful for explaining
// whether an Antigravity session can reply without a manually started agent turn.
try {
  const stateDirectory = resolveStateDirectory();
  await mkdir(stateDirectory, { recursive: true });
  await writeFile(path.join(stateDirectory, "mcp-client-capabilities.json"), `${JSON.stringify({
    connectedAt: new Date().toISOString(),
    capabilities: redactSensitiveData(server.server.getClientCapabilities() ?? {}),
  }, null, 2)}\n`, "utf8");
} catch (caught) {
  console.error(redactSensitiveText(`Unable to persist MCP client capabilities: ${caught instanceof Error ? caught.message : String(caught)}`));
}

const shutdown = async () => {
  await server.close();
  process.exit(0);
};

process.once("SIGINT", () => void shutdown());
process.once("SIGTERM", () => void shutdown());
