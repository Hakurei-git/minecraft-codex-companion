import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { getDefaultEnvironment, StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import path from "node:path";

const controlUrl = process.env.MC_COMPANION_URL
  ?? process.env.MC_MCP_URL?.replace(/\/mcp\/?$/u, "")
  ?? "http://127.0.0.1:8765";
const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const mcpServer = path.join(packageRoot, "apps", "control-plane", "dist", "mcp-stdio.js");
const client = new Client({ name: "minecraft-companion-portable-smoke", version: "0.1.3" });
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [mcpServer],
  cwd: packageRoot,
  env: { ...getDefaultEnvironment(), MC_COMPANION_URL: controlUrl },
  stderr: "pipe",
});

try {
  await client.connect(transport);
  const tools = await client.listTools();
  const inbox = await client.callTool({
    name: "mc_list_chat_messages",
    arguments: { afterSequence: 0, waitSeconds: 0 },
  });
  const names = tools.tools.map((tool) => tool.name);
  const replyRequirement = inbox.structuredContent?.replyRequirement;
  console.log(JSON.stringify({
    ok: names.includes("mc_list_chat_messages") && names.includes("mc_chat"),
    endpoint: controlUrl,
    transport: "stdio",
    toolCount: names.length,
    hasInbox: names.includes("mc_list_chat_messages"),
    hasGameChat: names.includes("mc_chat"),
    replyRequirementVerified: typeof replyRequirement === "string"
      && replyRequirement.includes("mc_chat")
      && replyRequirement.includes("Minecraft"),
  }));
} finally {
  await client.close();
}
