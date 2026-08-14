import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const endpoint = new URL(process.env.MC_MCP_URL ?? "http://127.0.0.1:8765/mcp");
const client = new Client({ name: "minecraft-companion-smoke", version: "0.1.6" });

try {
  await client.connect(new StreamableHTTPClientTransport(endpoint));
  const tools = await client.listTools();
  const companions = await client.callTool({ name: "mc_list_companions", arguments: {} });
  const first = companions.structuredContent?.companions?.[0];
  if (!first?.id) throw new Error("MCP returned no companion id");
  const observed = await client.callTool({
    name: "mc_observe",
    arguments: { companionId: first.id },
  });
  console.log(JSON.stringify({
    endpoint: endpoint.href,
    tools: tools.tools.map((tool) => tool.name),
    companion: {
      id: first.id,
      name: first.name,
      backend: first.backend,
    },
    world: observed.structuredContent?.snapshot?.worldId,
  }, null, 2));
} finally {
  await client.close();
}
