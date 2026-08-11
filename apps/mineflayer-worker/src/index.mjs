import path from "node:path";
import mineflayer from "mineflayer";
import collectBlockPackage from "mineflayer-collectblock";
import pathfinderPackage from "mineflayer-pathfinder";
import pvpPackage from "mineflayer-pvp";
import toolPackage from "mineflayer-tool";
import { BridgeSession } from "./bridge-session.mjs";
import { loadWorkerConfig } from "./config.mjs";
import { MineflayerTaskRunner } from "./task-runner.mjs";

const pathfinderPlugin = pathfinderPackage.pathfinder ?? pathfinderPackage.default?.pathfinder;
const collectBlockPlugin = collectBlockPackage.plugin ?? collectBlockPackage.default?.plugin ?? collectBlockPackage;
const pvpPlugin = pvpPackage.plugin ?? pvpPackage.default?.plugin ?? pvpPackage;
const toolPlugin = toolPackage.plugin ?? toolPackage.default?.plugin ?? toolPackage;
const createBot = mineflayer.createBot ?? mineflayer.default?.createBot;

if (![createBot, pathfinderPlugin, collectBlockPlugin, pvpPlugin, toolPlugin].every((value) => typeof value === "function")) {
  throw new Error("A Mineflayer plugin did not expose the expected API");
}

const config = loadWorkerConfig();
const live = new Map();
let shuttingDown = false;

function profilesFolder(botConfig) {
  if (botConfig.profilesFolder) return path.resolve(botConfig.profilesFolder, botConfig.id);
  const base = process.env.LOCALAPPDATA ?? process.cwd();
  return path.join(base, "MinecraftCodexCompanion", "mineflayer-auth", botConfig.id);
}

function startBot(botConfig) {
  if (shuttingDown) return;
  const bot = createBot({
    host: config.server.host,
    port: config.server.port,
    version: config.server.version,
    username: botConfig.username,
    ...(botConfig.password ? { password: botConfig.password } : {}),
    auth: config.server.auth,
    profilesFolder: profilesFolder(botConfig),
    hideErrors: false,
  });
  bot.loadPlugin(pathfinderPlugin);
  bot.loadPlugin(toolPlugin);
  bot.loadPlugin(collectBlockPlugin);
  bot.loadPlugin(pvpPlugin);

  let session = null;
  let restarted = false;
  const restart = () => {
    if (restarted) return;
    restarted = true;
    session?.close();
    live.delete(botConfig.id);
    if (!shuttingDown) setTimeout(() => startBot(botConfig), config.reconnectDelayMs).unref();
  };

  bot.once("spawn", () => {
    console.log(`[${botConfig.id}] ${bot.username} joined ${config.server.host}:${config.server.port}`);
    const runner = new MineflayerTaskRunner(bot, config);
    session = new BridgeSession(bot, botConfig, config, runner);
    live.set(botConfig.id, { bot, session });
    session.start();
  });
  bot.on("kicked", (reason) => console.error(`[${botConfig.id}] kicked: ${String(reason).slice(0, 500)}`));
  bot.on("error", (error) => console.error(`[${botConfig.id}] Minecraft error: ${error.message}`));
  bot.once("end", restart);
}

async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`Mineflayer workers stopping (${signal})`);
  for (const { bot, session } of live.values()) {
    session.close();
    try { bot.quit("Minecraft Codex Companion stopped"); } catch { /* already closed */ }
  }
  live.clear();
  setTimeout(() => process.exit(0), 500).unref();
}

process.on("SIGINT", () => void shutdown("SIGINT"));
process.on("SIGTERM", () => void shutdown("SIGTERM"));
process.on("uncaughtException", (error) => {
  console.error("Uncaught Mineflayer worker error", error);
  void shutdown("uncaughtException");
});
process.on("unhandledRejection", (error) => {
  console.error("Unhandled Mineflayer worker rejection", error);
});

console.log(`Starting ${config.bots.length} Mineflayer worker(s); chat leader: ${config.bots.find((bot) => bot.chatLeader)?.id ?? "none"}`);
config.bots.forEach(startBot);
