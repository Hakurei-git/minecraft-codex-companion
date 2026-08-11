import { readFileSync } from "node:fs";
import path from "node:path";
import { z } from "zod";

const botSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9._-]{2,64}$/),
  username: z.string().trim().min(1).max(120),
  password: z.string().optional(),
  chatLeader: z.boolean().default(false),
  profilesFolder: z.string().optional(),
});

const configSchema = z.object({
  controlUrl: z.string().url().refine((value) => value.startsWith("http://") || value.startsWith("https://")),
  bridgeToken: z.string().min(16),
  server: z.object({
    host: z.string().min(1),
    port: z.number().int().min(1).max(65_535).default(25_565),
    version: z.string().min(1).default("1.21.1"),
    auth: z.enum(["offline", "microsoft"]).default("offline"),
  }),
  ownerName: z.string().default(""),
  bots: z.array(botSchema).min(1).max(3),
  observeRadius: z.number().min(8).max(128).default(32),
  reconnectDelayMs: z.number().int().min(1_000).max(60_000).default(5_000),
});

function fromEnvironment() {
  const names = (process.env.MC_BOT_NAMES ?? "CodexWorker1")
    .split(",")
    .map((name) => name.trim())
    .filter(Boolean)
    .slice(0, 3);
  return {
    controlUrl: process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765",
    bridgeToken: process.env.MC_BRIDGE_TOKEN ?? "",
    server: {
      host: process.env.MC_SERVER_HOST ?? "127.0.0.1",
      port: Number(process.env.MC_SERVER_PORT ?? 25_565),
      version: process.env.MC_SERVER_VERSION ?? "1.21.1",
      auth: process.env.MC_BOT_AUTH ?? "offline",
    },
    ownerName: process.env.MC_OWNER_NAME ?? "",
    bots: names.map((username, index) => ({
      id: `worker-${index + 1}`,
      username,
      chatLeader: index === 0,
      profilesFolder: process.env.MC_BOT_PROFILES_DIR,
    })),
  };
}

export function loadWorkerConfig() {
  const configPath = process.env.MC_BOTS_CONFIG?.trim();
  const raw = configPath
    ? JSON.parse(readFileSync(path.resolve(configPath), "utf8"))
    : fromEnvironment();
  const config = configSchema.parse(raw);
  const ids = new Set(config.bots.map((bot) => bot.id));
  const usernames = new Set(config.bots.map((bot) => bot.username.toLowerCase()));
  if (ids.size !== config.bots.length || usernames.size !== config.bots.length) {
    throw new Error("Mineflayer bot IDs and usernames must be unique");
  }
  if (config.bots.filter((bot) => bot.chatLeader).length > 1) {
    throw new Error("At most one Mineflayer bot may forward player chat to the AI driver");
  }
  return config;
}
