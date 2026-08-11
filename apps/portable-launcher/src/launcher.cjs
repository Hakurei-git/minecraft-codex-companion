"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { TextDecoder } = require("node:util");
const { spawn } = require("node:child_process");
const { installClone, updateClone } = require("./instance-manager.cjs");

const APP_NAME = "Minecraft Codex Companion";
const DEFAULT_PORT = 8765;
const DEFAULT_COMPANION_NAME = "Codex";
const MAX_BODY_BYTES = 1024 * 1024;
const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });
const CONFIG_KEYS = new Set([
  "launcherPath",
  "launcherArguments",
  "minecraftRoot",
  "sourceVersion",
  "targetVersion",
  "playerName",
  "companionName",
  "port",
  "freeChatEnabled",
  "chatTarget",
  "actionMode",
  "tokenBudget",
  "persona",
  "npcSkinMode",
  "antigravityConfigPath",
  "antigravityConversationTitle",
]);

function resolvePayloadRoot() {
  return path.resolve(__dirname, "../../..");
}

function resolveStateDirectory(environment = process.env) {
  if (environment.MC_COMPANION_STATE_DIR) return path.resolve(environment.MC_COMPANION_STATE_DIR);
  const base = environment.LOCALAPPDATA || path.join(os.homedir(), ".local", "share");
  return path.join(base, "MinecraftCodexCompanion");
}

function environmentHome(environment = process.env) {
  return environment.USERPROFILE || environment.HOME || os.homedir();
}

function existingDirectory(value) {
  try {
    return Boolean(value) && fs.statSync(value).isDirectory();
  } catch {
    return false;
  }
}

function existingFile(value) {
  try {
    return Boolean(value) && fs.statSync(value).isFile();
  } catch {
    return false;
  }
}

function discoverHmclLauncherPath(environment = process.env) {
  const explicit = String(environment.MC_HMCL_PATH || environment.MC_LAUNCHER_PATH || "").trim();
  if (existingFile(explicit) && [".exe", ".jar"].includes(path.extname(explicit).toLowerCase())) {
    return path.resolve(explicit);
  }
  const home = environmentHome(environment);
  const searchDirectories = [
    path.join(home, "Desktop"),
    path.join(home, "Downloads"),
    path.join(home, "OneDrive", "Desktop"),
  ];
  const candidates = [];
  for (const directory of searchDirectories) {
    if (!existingDirectory(directory)) continue;
    try {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        if (!entry.isFile() || !/^HMCL.*\.(?:exe|jar)$/iu.test(entry.name)) continue;
        const fullPath = path.join(directory, entry.name);
        candidates.push({ fullPath, modifiedAt: fs.statSync(fullPath).mtimeMs });
      }
    } catch {
      // User folders may be unavailable or access-controlled.
    }
  }
  candidates.sort((left, right) => right.modifiedAt - left.modifiedAt
    || left.fullPath.localeCompare(right.fullPath, "en"));
  return candidates[0]?.fullPath ?? "";
}

function discoverMinecraftRoot(environment = process.env, launcherPath = "") {
  const home = environmentHome(environment);
  const appData = environment.APPDATA || path.join(home, "AppData", "Roaming");
  const launcherDirectory = launcherPath ? path.dirname(path.resolve(launcherPath)) : "";
  const candidates = [
    String(environment.MC_MINECRAFT_ROOT || "").trim(),
    launcherDirectory ? path.join(launcherDirectory, ".minecraft") : "",
    path.join(appData, ".minecraft"),
    path.join(home, ".minecraft"),
  ].filter(Boolean).map((candidate) => path.resolve(candidate));
  const unique = candidates.filter((candidate, index) => candidates.findIndex((item) => (
    item.localeCompare(candidate, "en", { sensitivity: "accent" }) === 0
  )) === index);
  return unique.find((candidate) => existingDirectory(path.join(candidate, "versions")))
    ?? unique.find(existingDirectory)
    ?? path.resolve(path.join(appData, ".minecraft"));
}

function discoverAntigravityConfigPath(environment = process.env) {
  const home = environmentHome(environment);
  const appData = environment.APPDATA || path.join(home, "AppData", "Roaming");
  const standard = path.join(home, ".gemini", "antigravity", "mcp_config.json");
  const candidates = [
    String(environment.MC_ANTIGRAVITY_CONFIG_PATH || "").trim(),
    standard,
    path.join(home, ".antigravity", "mcp_config.json"),
    path.join(appData, "Antigravity", "mcp_config.json"),
  ].filter(Boolean).map((candidate) => path.resolve(candidate));
  return candidates.find(existingFile) ?? path.resolve(standard);
}

function defaultConfig(environment = process.env) {
  const launcherPath = discoverHmclLauncherPath(environment);
  return {
    launcherPath,
    launcherArguments: "",
    minecraftRoot: discoverMinecraftRoot(environment, launcherPath),
    sourceVersion: "",
    targetVersion: "",
    playerName: "",
    companionName: DEFAULT_COMPANION_NAME,
    port: DEFAULT_PORT,
    freeChatEnabled: false,
    chatTarget: "active-provider",
    actionMode: "stable",
    tokenBudget: 512,
    persona: {
      mode: "inherit",
      displayName: "",
      personality: "",
      speakingStyle: "",
      memoryNotes: "",
    },
    npcSkinMode: "default",
    antigravityConfigPath: discoverAntigravityConfigPath(environment),
    antigravityConversationTitle: "Execute Minecraft Woodcutting Task",
  };
}

function normalizePersona(input) {
  const source = input && typeof input === "object" && !Array.isArray(input) ? input : {};
  const clean = (value, maximum) => (typeof value === "string" ? value.trim().slice(0, maximum) : "");
  return {
    mode: source.mode === "custom" ? "custom" : "inherit",
    displayName: clean(source.displayName, 64),
    personality: clean(source.personality, 1_200),
    speakingStyle: clean(source.speakingStyle, 600),
    memoryNotes: clean(source.memoryNotes, 2_000),
  };
}

function companionProfileKey(value) {
  return String(value || "").trim().toLocaleLowerCase("en-US");
}

function chatProfilesFrom(document) {
  if (!document || typeof document !== "object" || Array.isArray(document)) return [];
  if (document.version === 2 && Array.isArray(document.profiles)) {
    return document.profiles.filter((profile) => (
      profile && typeof profile === "object" && !Array.isArray(profile)
    ));
  }
  if (document.version === 1 && document.persona) {
    return [{ ...document, companionName: "Companion" }];
  }
  return [];
}

function normalizeConfig(input, environment = process.env) {
  const defaults = defaultConfig(environment);
  const source = input && typeof input === "object" && !Array.isArray(input) ? input : {};
  const result = { ...defaults };
  for (const key of CONFIG_KEYS) {
    if (Object.prototype.hasOwnProperty.call(source, key)) result[key] = source[key];
  }
  for (const key of [
    "launcherPath",
    "launcherArguments",
    "minecraftRoot",
    "sourceVersion",
    "targetVersion",
    "playerName",
    "companionName",
    "antigravityConfigPath",
    "antigravityConversationTitle",
  ]) {
    result[key] = typeof result[key] === "string" ? result[key].trim() : "";
  }
  if (/^(?:\?|？|\uFFFD)+$/u.test(result.companionName)) {
    result.companionName = DEFAULT_COMPANION_NAME;
  }
  result.port = Number(result.port);
  result.freeChatEnabled = result.freeChatEnabled !== false;
  result.chatTarget = result.chatTarget === "antigravity-mcp" ? "antigravity-mcp" : "active-provider";
  result.actionMode = result.actionMode === "smart" || result.actionMode === "hybrid"
    ? "smart"
    : "stable";
  const requestedTokenBudget = Number(result.tokenBudget);
  result.tokenBudget = Number.isFinite(requestedTokenBudget)
    ? Math.max(128, Math.min(4096, Math.trunc(requestedTokenBudget)))
    : 512;
  result.persona = normalizePersona(result.persona);
  result.npcSkinMode = result.npcSkinMode === "custom" ? "custom" : "default";
  return result;
}

function validateVersionName(value, label) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${label}不能为空`);
  const name = value.trim();
  if (name === "." || name === ".." || name.length > 120) throw new Error(`${label}无效`);
  if (/[<>:"/\\|?*\x00-\x1f]/u.test(name) || /[. ]$/u.test(name)) throw new Error(`${label}包含 Windows 不允许的字符`);
  return name;
}

function isPathInside(parent, candidate) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function validatePort(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("服务端口必须是 1024 到 65535 的整数");
  return port;
}

function validatePlayerName(value) {
  const playerName = typeof value === "string" ? value.trim() : "";
  if (!/^[A-Za-z0-9_]{1,64}$/u.test(playerName)) throw new Error("玩家名只能包含字母、数字和下划线");
  return playerName;
}

function validateCompanionName(value) {
  const name = typeof value === "string" ? value.trim() : "";
  if (!name || name.length > 64 || /[\x00-\x1f\x7f]/u.test(name)) throw new Error("NPC 名称必须是 1 到 64 个可见字符");
  return name;
}

function validateAntigravityConversationTitle(value) {
  const title = typeof value === "string" ? value.trim() : "";
  if (!title || title.length > 240 || /[\x00-\x1f\x7f]/u.test(title)) {
    throw new Error("反重力会话标题必须是 1 到 240 个可见字符");
  }
  return title;
}

function validateLauncherPath(value) {
  const launcherPath = path.resolve(String(value || ""));
  if (!fs.existsSync(launcherPath) || !fs.statSync(launcherPath).isFile()) throw new Error("请选择存在的启动器文件");
  if (![".exe", ".jar"].includes(path.extname(launcherPath).toLowerCase())) throw new Error("启动器只支持 .exe 或 .jar 文件");
  return launcherPath;
}

function validateRuntimeConfig(input, environment = process.env) {
  const config = normalizeConfig(input, environment);
  config.launcherPath = validateLauncherPath(config.launcherPath);
  config.minecraftRoot = path.resolve(config.minecraftRoot);
  if (!fs.existsSync(config.minecraftRoot) || !fs.statSync(config.minecraftRoot).isDirectory()) throw new Error("Minecraft 根目录不存在");
  config.sourceVersion = validateVersionName(config.sourceVersion, "源实例");
  config.targetVersion = validateVersionName(config.targetVersion, "目标实例");
  if (config.sourceVersion.toLowerCase() === config.targetVersion.toLowerCase()) throw new Error("目标实例不能与源实例同名");
  const versionsRoot = path.join(config.minecraftRoot, "versions");
  for (const name of [config.sourceVersion, config.targetVersion]) {
    if (!isPathInside(versionsRoot, path.join(versionsRoot, name))) throw new Error("实例路径越出了 versions 目录");
  }
  config.playerName = validatePlayerName(config.playerName);
  config.companionName = validateCompanionName(config.companionName);
  config.antigravityConversationTitle = validateAntigravityConversationTitle(config.antigravityConversationTitle);
  config.port = validatePort(config.port);
  config.antigravityConfigPath = path.resolve(config.antigravityConfigPath);
  return config;
}

class InvalidJsonFileError extends Error {
  constructor(filePath, cause) {
    super(`${path.basename(filePath)} 不是有效的 UTF-8 JSON 文件`, { cause });
    this.name = "InvalidJsonFileError";
  }
}

async function readJsonIfPresent(filePath, fallback) {
  let contents;
  try {
    contents = await fsp.readFile(filePath);
  } catch (error) {
    if (error && error.code === "ENOENT") return fallback;
    throw error;
  }
  try {
    const text = UTF8_DECODER.decode(contents).replace(/^\uFEFF/u, "");
    return JSON.parse(text);
  } catch (error) {
    throw new InvalidJsonFileError(filePath, error);
  }
}

async function writeFileAtomic(filePath, contents) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  const temporaryPath = `${filePath}.${process.pid}.${crypto.randomUUID()}.tmp`;
  let handle;
  try {
    handle = await fsp.open(temporaryPath, "wx", 0o600);
    await handle.writeFile(contents);
    await handle.sync();
    await handle.close();
    handle = undefined;
    await fsp.rename(temporaryPath, filePath);
  } finally {
    if (handle) await handle.close().catch(() => {});
    await fsp.rm(temporaryPath, { force: true }).catch(() => {});
  }
}

async function writeJsonAtomic(filePath, value) {
  const contents = Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
  await writeFileAtomic(filePath, contents);
}

async function quarantineInvalidJson(filePath) {
  const extension = path.extname(filePath);
  const baseName = path.basename(filePath, extension);
  const stamp = new Date().toISOString().replace(/\D/gu, "");
  const quarantinePath = path.join(
    path.dirname(filePath),
    `${baseName}.corrupt-${stamp}-${crypto.randomUUID()}${extension}`,
  );
  await fsp.rename(filePath, quarantinePath);
  return quarantinePath;
}

function importedSkinPath(stateDirectory) {
  return path.join(stateDirectory, "assets", "npc-skin.png");
}

function validateNpcSkin(filePath) {
  const stat = fs.statSync(filePath);
  if (!stat.isFile() || stat.size < 45 || stat.size > 16 * 1024 * 1024) throw new Error("皮肤必须是有效的 PNG 文件");
  const contents = fs.readFileSync(filePath);
  const pngSignature = "89504e470d0a1a0a";
  if (contents.subarray(0, 8).toString("hex") !== pngSignature || contents.subarray(12, 16).toString("ascii") !== "IHDR") {
    throw new Error("皮肤必须是 PNG 格式");
  }
  const width = contents.readUInt32BE(16);
  const height = contents.readUInt32BE(20);
  if (width !== 128 || height !== 64) throw new Error(`当前猫娘模型需要 128×64 PNG，所选图片为 ${width}×${height}`);
  let offset = 8;
  let firstChunk = true;
  let hasImageData = false;
  let hasEnd = false;
  while (offset + 12 <= contents.length) {
    const dataLength = contents.readUInt32BE(offset);
    const chunkEnd = offset + 12 + dataLength;
    if (chunkEnd > contents.length) throw new Error("皮肤 PNG 文件不完整");
    const chunkType = contents.subarray(offset + 4, offset + 8).toString("ascii");
    if (firstChunk && (chunkType !== "IHDR" || dataLength !== 13)) throw new Error("皮肤 PNG 缺少有效 IHDR");
    firstChunk = false;
    if (chunkType === "IDAT") hasImageData = true;
    if (chunkType === "IEND") {
      if (dataLength !== 0) throw new Error("皮肤 PNG 的 IEND 无效");
      hasEnd = true;
      break;
    }
    offset = chunkEnd;
  }
  if (!hasImageData || !hasEnd) throw new Error("皮肤 PNG 文件不完整");
  return { width, height, size: stat.size };
}

async function importNpcSkin(sourcePath, stateDirectory) {
  const metadata = validateNpcSkin(sourcePath);
  const destination = importedSkinPath(stateDirectory);
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.${crypto.randomUUID()}.tmp`;
  try {
    await fsp.copyFile(sourcePath, temporary);
    validateNpcSkin(temporary);
    await fsp.rename(temporary, destination);
  } finally {
    await fsp.rm(temporary, { force: true });
  }
  return metadata;
}

async function syncNpcSkin(config, stateDirectory) {
  const destination = path.join(
    config.minecraftRoot,
    "versions",
    config.targetVersion,
    "config",
    "minecraft-codex-companion-skin.png",
  );
  if (config.npcSkinMode !== "custom") {
    await fsp.rm(destination, { force: true });
    return { custom: false, destination };
  }
  const source = importedSkinPath(stateDirectory);
  validateNpcSkin(source);
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.${crypto.randomUUID()}.tmp`;
  try {
    await fsp.copyFile(source, temporary);
    await fsp.rename(temporary, destination);
  } finally {
    await fsp.rm(temporary, { force: true });
  }
  return { custom: true, destination };
}

async function loadConfig(stateDirectory, environment = process.env) {
  const configPath = path.join(stateDirectory, "launcher-config.json");
  let storedConfig;
  try {
    storedConfig = await readJsonIfPresent(configPath, {});
  } catch (error) {
    if (!(error instanceof InvalidJsonFileError)) throw error;
    await quarantineInvalidJson(configPath);
    storedConfig = normalizeConfig({}, environment);
    await writeJsonAtomic(configPath, storedConfig);
  }
  const config = normalizeConfig(storedConfig, environment);
  const chatSettings = await readJsonIfPresent(path.join(stateDirectory, "chat-settings.json"), null);
  const profiles = chatProfilesFrom(chatSettings);
  const matching = profiles.find((profile) => (
    companionProfileKey(profile.companionName) === companionProfileKey(config.companionName)
  ));
  const selected = matching ?? profiles.find((profile) => (
    companionProfileKey(profile.companionName) === companionProfileKey(chatSettings?.selectedCompanionName)
  )) ?? profiles[0];
  if (selected?.persona) {
    config.persona = normalizePersona(selected.persona);
  }
  if (selected) {
    config.freeChatEnabled = selected.freeChatEnabled === true;
    config.chatTarget = selected.target === "antigravity-mcp" ? "antigravity-mcp" : "active-provider";
    config.actionMode = selected.actionMode === "smart" || selected.actionMode === "hybrid"
      ? "smart"
      : "stable";
    const selectedBudget = Number(selected.tokenBudget);
    config.tokenBudget = Number.isFinite(selectedBudget)
      ? Math.max(128, Math.min(4096, Math.trunc(selectedBudget)))
      : 512;
  }
  return config;
}

async function saveConfig(stateDirectory, input, environment = process.env) {
  const config = validateRuntimeConfig(input, environment);
  if (config.npcSkinMode === "custom") validateNpcSkin(importedSkinPath(stateDirectory));
  await writeJsonAtomic(path.join(stateDirectory, "launcher-config.json"), config);
  return config;
}

async function listInstances(minecraftRoot) {
  const versionsRoot = path.join(path.resolve(minecraftRoot), "versions");
  let entries;
  try {
    entries = await fsp.readdir(versionsRoot, { withFileTypes: true });
  } catch (error) {
    if (error && error.code === "ENOENT") return [];
    throw error;
  }
  const instances = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const name = entry.name;
    const instancePath = path.join(versionsRoot, name);
    const hasJson = fs.existsSync(path.join(instancePath, `${name}.json`));
    const hasJar = fs.existsSync(path.join(instancePath, `${name}.jar`));
    if (!hasJson || !hasJar) continue;
    const marker = await readJsonIfPresent(path.join(instancePath, "CODEX-CLONE.json"), null).catch(() => null);
    instances.push({
      name,
      isCompanionClone: Boolean(marker),
      sourceVersion: marker && typeof marker.sourceVersion === "string" ? marker.sourceVersion : null,
    });
  }
  return instances.sort((left, right) => left.name.localeCompare(right.name, "zh-CN", { numeric: true }));
}

async function getOrCreateBridgeToken(stateDirectory) {
  const tokenPath = path.join(stateDirectory, "bridge-token.txt");
  try {
    const existing = (await fsp.readFile(tokenPath, "utf8")).trim();
    if (/^[a-f0-9]{32,}$/iu.test(existing)) return existing;
  } catch (error) {
    if (!error || error.code !== "ENOENT") throw error;
  }
  const token = crypto.randomBytes(32).toString("hex");
  await fsp.mkdir(stateDirectory, { recursive: true });
  await fsp.writeFile(tokenPath, `${token}\n`, { encoding: "utf8", mode: 0o600 });
  return token;
}

function findSingleFile(directory, matcher, label) {
  if (!fs.existsSync(directory)) throw new Error(`${label}目录不存在，便携包可能不完整`);
  const candidates = fs.readdirSync(directory)
    .filter((name) => matcher.test(name))
    .map((name) => path.join(directory, name));
  if (candidates.length !== 1) throw new Error(`${label}数量应为 1，实际为 ${candidates.length}`);
  return candidates[0];
}

function payloadPaths(payloadRoot) {
  return {
    manifest: path.join(payloadRoot, "portable-manifest.json"),
    node: path.join(payloadRoot, "runtime", "node.exe"),
    client: path.join(payloadRoot, "runtime", "MinecraftCodexClient.exe"),
    picker: path.join(payloadRoot, "runtime", "MinecraftCodexPicker.exe"),
    secretHelper: path.join(payloadRoot, "runtime", "MinecraftCodexSecret.exe"),
    launcherSource: path.join(payloadRoot, "apps", "portable-launcher", "src", "launcher.cjs"),
    instanceManager: path.join(payloadRoot, "apps", "portable-launcher", "src", "instance-manager.cjs"),
    controlServer: path.join(payloadRoot, "apps", "control-plane", "dist", "server.js"),
    mcpStdio: path.join(payloadRoot, "apps", "control-plane", "dist", "mcp-stdio.js"),
    dashboard: path.join(payloadRoot, "apps", "dashboard", "dist", "index.html"),
    mcpSmoke: path.join(payloadRoot, "scripts", "mcp-portable-smoke.mjs"),
    bridgeJar: findSingleFile(
      path.join(payloadRoot, "mods", "forge-1.20.1", "build", "libs"),
      /^minecraft_codex_bridge-forge-1\.20\.1-[^/\\]+\.jar$/iu,
      "Forge 桥接模组",
    ),
    baritoneJar: findSingleFile(
      path.join(payloadRoot, "vendor", "baritone"),
      /^baritone-api-forge-1\.20\.1-[^/\\]+\.jar$/iu,
      "Baritone",
    ),
  };
}

function assertPayload(payloadRoot) {
  const paths = payloadPaths(payloadRoot);
  for (const [name, filePath] of Object.entries(paths)) {
    if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) throw new Error(`便携包缺少 ${name}: ${filePath}`);
  }
  const manifest = JSON.parse(fs.readFileSync(paths.manifest, "utf8"));
  if (manifest?.format !== 2 || manifest?.packaging?.model !== "transparent-multi-file"
    || manifest.packaging.selfExtracting || manifest.packaging.executableInjection
    || manifest.packaging.runtimePowerShell || manifest.packaging.runtimeCommandShell) {
    throw new Error("Portable package security manifest is missing or invalid");
  }
  const entries = new Map((Array.isArray(manifest.files) ? manifest.files : [])
    .map((entry) => [String(entry.path || "").replaceAll("/", path.sep), entry]));
  for (const name of [
    "node", "client", "picker", "secretHelper", "launcherSource", "instanceManager",
    "controlServer", "mcpStdio", "dashboard", "mcpSmoke", "bridgeJar", "baritoneJar",
  ]) {
    const filePath = paths[name];
    const relative = path.relative(payloadRoot, filePath);
    const entry = entries.get(relative);
    if (!entry || typeof entry.sha256 !== "string") throw new Error(`Portable manifest does not cover ${relative}`);
    const actual = crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
    if (actual !== entry.sha256.toLowerCase()) throw new Error(`Portable file failed SHA-256 verification: ${relative}`);
  }
  return paths;
}

function runProcess(executable, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd: options.cwd,
      env: options.env || process.env,
      windowsHide: true,
      shell: false,
    });
    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
      if (stdout.length > 2 * 1024 * 1024) stdout = stdout.slice(-2 * 1024 * 1024);
    });
    child.stderr?.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
      if (stderr.length > 2 * 1024 * 1024) stderr = stderr.slice(-2 * 1024 * 1024);
    });
    child.once("error", reject);
    child.once("exit", (code) => {
      if (code === 0) resolve({ stdout: stdout.trim(), stderr: stderr.trim() });
      else reject(new Error((stderr || stdout || `${path.basename(executable)} 退出码 ${code}`).trim()));
    });
  });
}

function hashFile(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("sha256");
    const stream = fs.createReadStream(filePath);
    stream.on("error", reject);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

async function configureBridge(config, stateDirectory) {
  const token = await getOrCreateBridgeToken(stateDirectory);
  const instancePath = path.join(config.minecraftRoot, "versions", config.targetVersion);
  const configPath = path.join(instancePath, "config", "minecraft-codex-companion.json");
  const existing = await readJsonIfPresent(configPath, {});
  const bridgeConfig = {
    ...(existing && typeof existing === "object" && !Array.isArray(existing) ? existing : {}),
    serverUrl: `ws://127.0.0.1:${config.port}/bridge`,
    token,
    companionId: "codex-forge",
    name: config.companionName,
    ownerName: config.playerName,
    autoReconnect: true,
    snapshotIntervalTicks: 10,
    observeRadius: 32,
    allowPvp: false,
    allowBreakingContainers: false,
    hostileEntityAllowlist: [],
    npcSkinPath: "config/minecraft-codex-companion-skin.png",
  };
  await syncNpcSkin(config, stateDirectory);
  await writeJsonAtomic(configPath, bridgeConfig);
  return configPath;
}

async function configureChat(config, stateDirectory) {
  const configPath = path.join(stateDirectory, "chat-settings.json");
  const draft = {
    freeChatEnabled: config.freeChatEnabled,
    playerName: config.playerName,
    companionName: config.companionName,
    target: config.chatTarget,
    actionMode: config.actionMode,
    tokenBudget: config.tokenBudget,
    persona: normalizePersona(config.persona),
  };
  const status = await serviceStatus(config.port);
  if (status.running) {
    await httpJson(`http://127.0.0.1:${config.port}/api/chat/settings`, {
      method: "PUT",
      body: draft,
      timeout: 3_000,
    });
    return;
  }
  const existing = await readJsonIfPresent(configPath, null);
  const profiles = chatProfilesFrom(existing).filter((profile) => (
    companionProfileKey(profile.companionName) !== companionProfileKey(draft.companionName)
  ));
  profiles.push({ ...draft, updatedAt: new Date().toISOString() });
  await writeJsonAtomic(configPath, {
    version: 2,
    selectedCompanionName: draft.companionName,
    profiles,
  });
}

async function detectForge1201(config) {
  const sourceJsonPath = path.join(config.minecraftRoot, "versions", config.sourceVersion, `${config.sourceVersion}.json`);
  const document = await readJsonIfPresent(sourceJsonPath, null);
  if (!document) throw new Error("源实例缺少版本 JSON");
  const fingerprint = JSON.stringify(document).toLowerCase();
  if (!fingerprint.includes("net.minecraftforge") || !fingerprint.includes("1.20.1")) {
    throw new Error("当前便携包只支持 Forge 1.20.1 源实例");
  }
}

async function installOrUpdate(config, payloadRoot, stateDirectory) {
  const paths = assertPayload(payloadRoot);
  await detectForge1201(config);
  const targetPath = path.join(config.minecraftRoot, "versions", config.targetVersion);
  const markerPath = path.join(targetPath, "CODEX-CLONE.json");
  let action = "installed";
  if (!fs.existsSync(targetPath)) {
    await installClone({
      minecraftRoot: config.minecraftRoot,
      sourceVersion: config.sourceVersion,
      targetVersion: config.targetVersion,
      launcherPath: config.launcherPath,
      bridgeJar: paths.bridgeJar,
      baritoneJar: paths.baritoneJar,
    });
  } else {
    if (!fs.existsSync(markerPath)) throw new Error("Target instance exists and is not an isolated Codex clone");
    const installedJars = fs.existsSync(path.join(targetPath, "mods"))
      ? fs.readdirSync(path.join(targetPath, "mods"))
          .filter((name) => /^minecraft_codex_bridge-forge-1\.20\.1-[^/\\]+\.jar$/iu.test(name))
          .map((name) => path.join(targetPath, "mods", name))
      : [];
    const current = installedJars.length === 1 && (await hashFile(installedJars[0])) === (await hashFile(paths.bridgeJar));
    if (current) {
      action = "current";
    } else {
      await updateClone({
        minecraftRoot: config.minecraftRoot,
        targetVersion: config.targetVersion,
        bridgeJar: paths.bridgeJar,
      });
      action = "updated";
    }
  }
  await configureBridge(config, stateDirectory);
  await configureChat(config, stateDirectory);
  return { action, targetPath };
}
function httpJson(url, options = {}) {
  return new Promise((resolve, reject) => {
    const request = http.request(url, {
      method: options.method || "GET",
      headers: options.body ? { "content-type": "application/json" } : {},
      timeout: options.timeout || 1200,
    }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => {
        try {
          const parsed = body ? JSON.parse(body) : null;
          if ((response.statusCode || 500) >= 400) reject(new Error(parsed?.error?.message || `HTTP ${response.statusCode}`));
          else resolve(parsed);
        } catch (error) {
          reject(error);
        }
      });
    });
    request.on("timeout", () => request.destroy(new Error("请求超时")));
    request.on("error", reject);
    if (options.body) request.end(JSON.stringify(options.body));
    else request.end();
  });
}

async function serviceStatus(port) {
  try {
    const health = await httpJson(`http://127.0.0.1:${port}/api/health`);
    return health && health.ok && health.service === "minecraft-codex-companion"
      ? { running: true, companions: Number(health.companions || 0) }
      : { running: false, error: "端口被其他程序占用" };
  } catch (error) {
    const code = error && error.code;
    if (["ECONNREFUSED", "ECONNRESET", "ETIMEDOUT"].includes(code)) return { running: false };
    return { running: false, error: error instanceof Error ? error.message : String(error) };
  }
}

async function waitForService(port, attempts = 40) {
  for (let index = 0; index < attempts; index += 1) {
    const status = await serviceStatus(port);
    if (status.running) return status;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error("控制服务未能在 10 秒内启动，请检查运行日志");
}

async function startService(config, payloadRoot, stateDirectory) {
  const existing = await serviceStatus(config.port);
  if (existing.running) return { ...existing, alreadyRunning: true };
  if (existing.error) throw new Error(existing.error);
  const paths = assertPayload(payloadRoot);
  await configureChat(config, stateDirectory);
  await fsp.mkdir(path.join(stateDirectory, "logs"), { recursive: true });
  const logPath = path.join(stateDirectory, "logs", "control.log");
  const logHandle = fs.openSync(logPath, "a");
  let child;
  try {
    child = spawn(paths.node, [paths.controlServer], {
      cwd: payloadRoot,
      detached: true,
      windowsHide: true,
      stdio: ["ignore", logHandle, logHandle],
      env: {
        ...process.env,
        PORT: String(config.port),
        MC_COMPANION_STATE_DIR: stateDirectory,
        MC_MCP_URL: `http://127.0.0.1:${config.port}/mcp`,
        MC_ANTIGRAVITY_HOME: path.dirname(config.antigravityConfigPath),
        MC_ANTIGRAVITY_CONVERSATION_TITLE: config.antigravityConversationTitle,
        MC_COMPANION_SECRET_HELPER: paths.secretHelper,
      },
    });
    await new Promise((resolve, reject) => {
      child.once("spawn", resolve);
      child.once("error", reject);
    });
    child.on("error", () => undefined);
    child.unref();
  } finally {
    fs.closeSync(logHandle);
  }
  await writeJsonAtomic(path.join(stateDirectory, "control-process.json"), {
    pid: child.pid,
    serverScript: paths.controlServer,
    port: config.port,
    startedAt: new Date().toISOString(),
  });
  return { ...(await waitForService(config.port)), pid: child.pid, logPath };
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return Boolean(error && error.code === "EPERM");
  }
}
async function stopService(stateDirectory) {
  const metadataPath = path.join(stateDirectory, "control-process.json");
  const metadata = await readJsonIfPresent(metadataPath, null);
  if (!metadata || !Number.isInteger(metadata.pid) || !Number.isInteger(metadata.port)) {
    return { stopped: false, message: "No portable control-service process is recorded" };
  }
  const status = await serviceStatus(metadata.port);
  if (!processExists(metadata.pid) || !status.running) {
    await fsp.rm(metadataPath, { force: true });
    return { stopped: false, message: "The recorded service process has already exited" };
  }
  process.kill(metadata.pid, "SIGTERM");
  await fsp.rm(metadataPath, { force: true });
  return { stopped: true };
}
function splitArguments(commandLine) {
  const args = [];
  let current = "";
  let quote = null;
  let escaped = false;
  for (const character of String(commandLine || "")) {
    if (escaped) {
      current += character;
      escaped = false;
    } else if (character === "\\") {
      escaped = true;
    } else if (quote) {
      if (character === quote) quote = null;
      else current += character;
    } else if (character === '"' || character === "'") {
      quote = character;
    } else if (/\s/u.test(character)) {
      if (current) { args.push(current); current = ""; }
    } else {
      current += character;
    }
  }
  if (escaped) current += "\\";
  if (quote) throw new Error("启动参数存在未闭合的引号");
  if (current) args.push(current);
  return args;
}

function launchGame(config) {
  const configuredArgs = splitArguments(config.launcherArguments)
    .map((argument) => argument.replaceAll("{instance}", config.targetVersion));
  const extension = path.extname(config.launcherPath).toLowerCase();
  const executable = extension === ".jar" ? "javaw.exe" : config.launcherPath;
  const args = extension === ".jar" ? ["-jar", config.launcherPath, ...configuredArgs] : configuredArgs;
  const child = spawn(executable, args, {
    cwd: path.dirname(config.launcherPath),
    detached: true,
    windowsHide: false,
    stdio: "ignore",
    shell: false,
  });
  child.unref();
  return { launched: true, pid: child.pid, targetVersion: config.targetVersion };
}

function mergeAntigravityConfig(existing, entry) {
  const root = existing && typeof existing === "object" && !Array.isArray(existing) ? { ...existing } : {};
  const servers = root.mcpServers && typeof root.mcpServers === "object" && !Array.isArray(root.mcpServers)
    ? { ...root.mcpServers }
    : {};
  servers.minecraft_codex_companion = entry;
  return { ...root, mcpServers: servers };
}

const ANTIGRAVITY_MCP_PERMISSION_RULES = Object.freeze([
  "mcp(minecraft_codex_companion/mc_submit_ai_decision)",
  "mcp(minecraft_codex_companion/mc_chat)",
]);

function resolveAntigravityPermissionConfigPath(mcpConfigPath) {
  const resolved = path.resolve(mcpConfigPath);
  const antigravityDirectory = path.dirname(resolved);
  const geminiDirectory = path.dirname(antigravityDirectory);
  if (
    path.basename(resolved).toLowerCase() !== "mcp_config.json"
    || path.basename(antigravityDirectory).toLowerCase() !== "antigravity"
    || path.basename(geminiDirectory).toLowerCase() !== ".gemini"
  ) return null;
  return path.join(geminiDirectory, "config", "config.json");
}

function mergeAntigravityPermissions(existing) {
  const root = existing && typeof existing === "object" && !Array.isArray(existing) ? { ...existing } : {};
  const userSettings = root.userSettings && typeof root.userSettings === "object" && !Array.isArray(root.userSettings)
    ? { ...root.userSettings }
    : {};
  const grants = userSettings.globalPermissionGrants
    && typeof userSettings.globalPermissionGrants === "object"
    && !Array.isArray(userSettings.globalPermissionGrants)
    ? { ...userSettings.globalPermissionGrants }
    : {};
  if (grants.allow !== undefined && !Array.isArray(grants.allow)) {
    throw new Error("Antigravity globalPermissionGrants.allow must be an array");
  }
  const allow = [...(grants.allow ?? [])];
  for (const rule of ANTIGRAVITY_MCP_PERMISSION_RULES) {
    if (!allow.includes(rule)) allow.push(rule);
  }
  return {
    ...root,
    userSettings: {
      ...userSettings,
      globalPermissionGrants: { ...grants, allow },
    },
  };
}

async function installAntigravity(config, payloadRoot) {
  const paths = assertPayload(payloadRoot);
  const configPath = config.antigravityConfigPath;
  const permissionConfigPath = resolveAntigravityPermissionConfigPath(configPath);
  let existing = {};
  let backupPath = null;
  if (fs.existsSync(configPath)) {
    existing = await readJsonIfPresent(configPath, {});
    const stamp = new Date().toISOString().replace(/[:.]/gu, "-");
    backupPath = `${configPath}.${stamp}.bak`;
    await fsp.copyFile(configPath, backupPath);
  }
  const merged = mergeAntigravityConfig(existing, {
    command: paths.node,
    args: [paths.mcpStdio],
    env: { MC_COMPANION_URL: `http://127.0.0.1:${config.port}` },
  });
  await writeJsonAtomic(configPath, merged);
  let permissionBackupPath = null;
  if (permissionConfigPath) {
    const existingPermissions = await readJsonIfPresent(permissionConfigPath, {});
    if (fs.existsSync(permissionConfigPath)) {
      const stamp = new Date().toISOString().replace(/[:.]/gu, "-");
      permissionBackupPath = `${permissionConfigPath}.${stamp}.bak`;
      await fsp.copyFile(permissionConfigPath, permissionBackupPath);
    }
    await writeJsonAtomic(permissionConfigPath, mergeAntigravityPermissions(existingPermissions));
  }
  return {
    installed: true,
    configPath,
    backupCreated: Boolean(backupPath),
    permissionsConfigured: Boolean(permissionConfigPath),
    permissionBackupCreated: Boolean(permissionBackupPath),
  };
}

async function testMcp(config, payloadRoot, stateDirectory) {
  const paths = assertPayload(payloadRoot);
  await startService(config, payloadRoot, stateDirectory);
  const result = await runProcess(paths.node, [paths.mcpSmoke], {
    cwd: payloadRoot,
    env: { ...process.env, MC_MCP_URL: `http://127.0.0.1:${config.port}/mcp` },
  });
  const parsed = JSON.parse(result.stdout);
  if (!parsed.ok || !parsed.replyRequirementVerified) throw new Error("MCP 回复规则测试未通过");
  return parsed;
}

async function picker(payloadRoot, kind, currentPath) {
  const pickerPath = path.join(payloadRoot, "runtime", "MinecraftCodexPicker.exe");
  if (!fs.existsSync(pickerPath)) throw new Error("便携包缺少原生路径选择器");
  const outputPath = path.join(os.tmpdir(), `minecraft-codex-picker-${crypto.randomUUID()}.txt`);
  try {
    await new Promise((resolve, reject) => {
      const child = spawn(pickerPath, [kind, outputPath, String(currentPath || "")], {
        cwd: payloadRoot,
        windowsHide: false,
        stdio: "ignore",
      });
      child.once("error", reject);
      child.once("exit", (code, signal) => {
        if (code === 0) {
          resolve();
          return;
        }
        reject(new Error(signal
          ? `路径选择器被信号 ${signal} 终止`
          : `路径选择器退出码 ${code}`));
      });
    });
    return fs.existsSync(outputPath) ? fs.readFileSync(outputPath, "utf8").trim() : "";
  } finally {
    fs.rmSync(outputPath, { force: true });
  }
}

function companionPrompt() {
  return [
    "这是自动触发不可用时的手动备用方式。保留并使用你当前已经设定的人格，进入 Minecraft 长期陪玩模式。",
    "持续调用 minecraft_codex_companion MCP 的 mc_list_chat_messages，首次 afterSequence=0，之后使用上次返回的 nextSequence，waitSeconds=30。",
    "收到玩家消息后，根据内容聊天或调用 Minecraft 工具执行任务。凡是要让玩家看到的话，包括人格闲聊、开始提示、进度、失败说明和最终回答，都必须调用 mc_chat 发进游戏；只写在反重力对话窗口里的文字玩家看不到。",
    "同一段话只调用一次 mc_chat，不要重复发送。任务完成后继续长轮询，直到我明确让你停止。",
  ].join("\n");
}

function openUrl(url) {
  const child = spawn("explorer.exe", [url], {
    detached: true,
    windowsHide: true,
    stdio: "ignore",
  });
  child.unref();
}

function createApiContext(options = {}) {
  const payloadRoot = options.payloadRoot || resolvePayloadRoot();
  const stateDirectory = options.stateDirectory || resolveStateDirectory();
  return { payloadRoot, stateDirectory, operation: null, events: [] };
}

function addEvent(context, level, message) {
  context.events.push({ at: new Date().toISOString(), level, message: String(message).slice(0, 500) });
  if (context.events.length > 80) context.events.shift();
}

async function bootstrap(context) {
  const config = await loadConfig(context.stateDirectory);
  let payload = { valid: true };
  try { assertPayload(context.payloadRoot); } catch (error) { payload = { valid: false, error: error.message }; }
  return {
    appName: APP_NAME,
    config,
    stateDirectory: context.stateDirectory,
    skin: { customAvailable: fs.existsSync(importedSkinPath(context.stateDirectory)) },
    payload,
    instances: await listInstances(config.minecraftRoot),
    service: await serviceStatus(validatePort(config.port || DEFAULT_PORT)),
    prompt: companionPrompt(),
    operation: context.operation,
    events: context.events,
  };
}

async function readBody(request) {
  let body = "";
  for await (const chunk of request) {
    body += chunk.toString("utf8");
    if (Buffer.byteLength(body, "utf8") > MAX_BODY_BYTES) throw new Error("请求内容过大");
  }
  return body ? JSON.parse(body) : {};
}

function sendJson(response, statusCode, value) {
  const body = JSON.stringify(value);
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(body);
}

async function sendPng(response, filePath) {
  const body = await fsp.readFile(filePath);
  response.writeHead(200, {
    "content-type": "image/png",
    "content-length": body.length,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  response.end(body);
}

async function withOperation(context, name, action) {
  if (context.operation) throw new Error(`正在执行“${context.operation}”，请等待完成`);
  context.operation = name;
  addEvent(context, "info", `开始：${name}`);
  try {
    const result = await action();
    addEvent(context, "success", `完成：${name}`);
    return result;
  } catch (error) {
    addEvent(context, "error", `${name}失败：${error instanceof Error ? error.message : String(error)}`);
    throw error;
  } finally {
    context.operation = null;
  }
}

async function handleApi(request, response, context, pathname) {
  if (request.method === "GET" && pathname === "/api/bootstrap") return sendJson(response, 200, await bootstrap(context));
  if (request.method === "GET" && pathname === "/api/skin-preview") {
    const skinPath = importedSkinPath(context.stateDirectory);
    validateNpcSkin(skinPath);
    return sendPng(response, skinPath);
  }
  if (request.method !== "POST") return sendJson(response, 405, { error: "Method not allowed" });
  const body = await readBody(request);
  if (pathname === "/api/app/exit") {
    sendJson(response, 200, { closing: true });
    context.closeApp?.();
    return;
  }
  if (pathname === "/api/browse-launcher") return sendJson(response, 200, { path: await picker(context.payloadRoot, "file", body.current) });
  if (pathname === "/api/browse-folder") return sendJson(response, 200, { path: await picker(context.payloadRoot, "folder", body.current) });
  if (pathname === "/api/browse-skin") {
    const selected = await picker(context.payloadRoot, "skin", path.dirname(importedSkinPath(context.stateDirectory)));
    if (!selected) return sendJson(response, 200, { selected: false });
    const metadata = await importNpcSkin(selected, context.stateDirectory);
    addEvent(context, "success", "已导入自定义 NPC 皮肤");
    return sendJson(response, 200, { selected: true, metadata });
  }
  if (pathname === "/api/skin/import") {
    const selected = typeof body.path === "string" ? body.path.trim() : "";
    if (!selected) throw new Error("请选择 NPC 皮肤 PNG");
    const metadata = await importNpcSkin(path.resolve(selected), context.stateDirectory);
    addEvent(context, "success", "已从独立客户端导入自定义 NPC 皮肤");
    return sendJson(response, 200, { selected: true, metadata });
  }
  if (pathname === "/api/instances") {
    const minecraftRoot = path.resolve(String(body.minecraftRoot || ""));
    return sendJson(response, 200, { instances: await listInstances(minecraftRoot) });
  }
  if (pathname === "/api/save") {
    const config = await saveConfig(context.stateDirectory, body.config);
    await configureChat(config, context.stateDirectory);
    const markerPath = path.join(config.minecraftRoot, "versions", config.targetVersion, "CODEX-CLONE.json");
    if (fs.existsSync(markerPath)) await configureBridge(config, context.stateDirectory);
    addEvent(context, "success", "配置已保存到本机状态目录");
    return sendJson(response, 200, { config, instances: await listInstances(config.minecraftRoot) });
  }
  if (pathname === "/api/service/stop") {
    const result = await withOperation(context, "停止控制服务", () => stopService(context.stateDirectory));
    return sendJson(response, 200, result);
  }
  const saved = await loadConfig(context.stateDirectory);
  if (pathname === "/api/dashboard/open") {
    const port = validatePort(saved.port);
    const status = await serviceStatus(port);
    if (!status.running) throw new Error("请先启动控制服务");
    openUrl(`http://127.0.0.1:${port}/`);
    return sendJson(response, 200, { opened: true });
  }
  const config = validateRuntimeConfig(saved);
  if (pathname === "/api/install") {
    const result = await withOperation(context, "安装或更新 Codex 实例", () => installOrUpdate(config, context.payloadRoot, context.stateDirectory));
    return sendJson(response, 200, result);
  }
  if (pathname === "/api/service/start") {
    const result = await withOperation(context, "启动控制服务", () => startService(config, context.payloadRoot, context.stateDirectory));
    return sendJson(response, 200, result);
  }
  if (pathname === "/api/launcher/start") return sendJson(response, 200, launchGame(config));
  if (pathname === "/api/antigravity/install") {
    const result = await withOperation(context, "写入反重力 MCP 配置", () => installAntigravity(config, context.payloadRoot));
    return sendJson(response, 200, result);
  }
  if (pathname === "/api/mcp/test") {
    const result = await withOperation(context, "测试反重力 MCP", () => testMcp(config, context.payloadRoot, context.stateDirectory));
    return sendJson(response, 200, result);
  }
  if (pathname === "/api/antigravity/bind") {
    await startService(config, context.payloadRoot, context.stateDirectory);
    const result = await httpJson(`http://127.0.0.1:${config.port}/api/antigravity/bind`, {
      method: "POST",
      body: { title: config.antigravityConversationTitle },
      timeout: 20_000,
    });
    addEvent(context, "success", `已按标题精确绑定反重力会话“${config.antigravityConversationTitle}”`);
    return sendJson(response, 200, result);
  }
  if (pathname === "/api/antigravity/recover") {
    await startService(config, context.payloadRoot, context.stateDirectory);
    const result = await httpJson(`http://127.0.0.1:${config.port}/api/antigravity/recover`, {
      method: "POST",
      body: {},
      timeout: 30_000,
    });
    addEvent(context, "success", "已解除绑定反重力会话的假忙状态");
    return sendJson(response, 200, result);
  }
  if (pathname === "/api/prepare") {
    const result = await withOperation(context, "一键准备并启动", async () => {
      const installation = await installOrUpdate(config, context.payloadRoot, context.stateDirectory);
      const service = await startService(config, context.payloadRoot, context.stateDirectory);
      const launcher = launchGame(config);
      return { installation, service, launcher };
    });
    return sendJson(response, 200, result);
  }
  return sendJson(response, 404, { error: "Not found" });
}

function safeAssetPath(payloadRoot, pathname) {
  const routes = {
    "/": path.join(payloadRoot, "apps", "portable-launcher", "ui", "index.html"),
    "/app.css": path.join(payloadRoot, "apps", "portable-launcher", "ui", "app.css"),
    "/app.js": path.join(payloadRoot, "apps", "portable-launcher", "ui", "app.js"),
    "/assets/companion.png": path.join(payloadRoot, "assets", "third_party", "queen-cats-dogs", "humanoid_cat_white.png"),
  };
  return routes[pathname] || null;
}

async function serveAsset(response, filePath) {
  const extension = path.extname(filePath).toLowerCase();
  const contentType = extension === ".html" ? "text/html; charset=utf-8"
    : extension === ".css" ? "text/css; charset=utf-8"
      : extension === ".js" ? "text/javascript; charset=utf-8"
        : extension === ".png" ? "image/png" : "application/octet-stream";
  const body = await fsp.readFile(filePath);
  response.writeHead(200, {
    "content-type": contentType,
    "content-length": body.length,
    "cache-control": "no-store",
    "content-security-policy": "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
  });
  response.end(body);
}

function createServer(options = {}) {
  const context = options.context || createApiContext(options);
  const sessionToken = options.sessionToken || crypto.randomBytes(24).toString("hex");
  const server = http.createServer(async (request, response) => {
    try {
      const remote = request.socket.remoteAddress || "";
      if (!["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(remote)) return sendJson(response, 403, { error: "Local access only" });
      const url = new URL(request.url || "/", "http://127.0.0.1");
      const suppliedToken = request.headers["x-companion-session"] || url.searchParams.get("session");
      const isStaticAsset = url.pathname !== "/" && Boolean(safeAssetPath(context.payloadRoot, url.pathname));
      if (!isStaticAsset && suppliedToken !== sessionToken) return sendJson(response, 403, { error: "Invalid local session" });
      if (url.pathname.startsWith("/api/")) return await handleApi(request, response, context, url.pathname);
      const asset = safeAssetPath(context.payloadRoot, url.pathname);
      if (!asset) return sendJson(response, 404, { error: "Not found" });
      await serveAsset(response, asset);
    } catch (error) {
      sendJson(response, 400, { error: error instanceof Error ? error.message : String(error) });
    }
  });
  context.closeApp = () => {
    setTimeout(() => server.close(() => process.exit(0)), 80).unref();
  };
  return { server, sessionToken, context };
}

function startNativeClient(payloadRoot, endpoint, sessionToken, onExit) {
  const clientPath = path.join(payloadRoot, "runtime", "MinecraftCodexClient.exe");
  if (!fs.existsSync(clientPath)) throw new Error("便携包缺少原生独立客户端");
  const child = spawn(clientPath, [], {
    cwd: payloadRoot,
    windowsHide: false,
    stdio: "ignore",
    env: {
      ...process.env,
      MC_COMPANION_CLIENT_ENDPOINT: endpoint,
      MC_COMPANION_CLIENT_SESSION: sessionToken,
    },
  });
  child.once("exit", onExit);
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("spawn", () => resolve(child));
  });
}

async function main() {
  const payloadRoot = resolvePayloadRoot();
  if (process.argv.includes("--self-test")) {
    assertPayload(payloadRoot);
    process.stdout.write(`${JSON.stringify({ ok: true, payloadRoot })}\n`);
    return;
  }
  const { server, sessionToken, context } = createServer({ payloadRoot });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  const endpoint = `http://127.0.0.1:${address.port}`;
  const url = `${endpoint}/?session=${sessionToken}`;
  const endpointIndex = process.argv.indexOf("--endpoint-file");
  const requested = endpointIndex >= 0 ? process.argv[endpointIndex + 1] : "";
  if (requested || process.argv.includes("--no-open") || process.argv.includes("--no-client")) {
    const endpointFile = requested ? path.resolve(requested) : path.join(resolveStateDirectory(), "launcher-endpoint.json");
    await writeJsonAtomic(endpointFile, { url, pid: process.pid, createdAt: new Date().toISOString() });
  }
  if (process.argv.includes("--no-open") || process.argv.includes("--no-client")) return;
  try {
    await startNativeClient(payloadRoot, endpoint, sessionToken, () => context.closeApp?.());
  } catch (error) {
    await new Promise((resolve) => server.close(resolve));
    throw error;
  }
}

module.exports = {
  companionPrompt,
  configureBridge,
  createApiContext,
  createServer,
  defaultConfig,
  discoverAntigravityConfigPath,
  discoverHmclLauncherPath,
  discoverMinecraftRoot,
  installAntigravity,
  isPathInside,
  listInstances,
  loadConfig,
  mergeAntigravityConfig,
  mergeAntigravityPermissions,
  normalizeConfig,
  normalizePersona,
  resolveAntigravityPermissionConfigPath,
  resolvePayloadRoot,
  resolveStateDirectory,
  saveConfig,
  splitArguments,
  validateRuntimeConfig,
  validateAntigravityConversationTitle,
  validateCompanionName,
  validateNpcSkin,
  validateVersionName,
};

if (require.main === module) {
  main().catch((error) => {
    const message = error instanceof Error ? error.stack || error.message : String(error);
    process.stderr.write(`${message}\n`);
    process.exitCode = 1;
  });
}
