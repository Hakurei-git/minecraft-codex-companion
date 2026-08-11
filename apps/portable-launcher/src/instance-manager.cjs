"use strict";

const crypto = require("node:crypto");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");

function isPathInside(parent, candidate) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}

function parseJavaTasklist(output) {
  const images = new Set();
  for (const line of String(output || "").split(/\r?\n/gu)) {
    const match = /^"([^"]+)"(?:,|$)/u.exec(line.trim());
    if (match && /^javaw?\.exe$/iu.test(match[1])) images.add(match[1].toLowerCase());
  }
  return [...images].sort();
}

function inspectJavaProcesses(environment = process.env) {
  if (process.platform !== "win32") return Promise.resolve([]);
  const windowsRoot = String(environment.SystemRoot || environment.WINDIR || "").trim();
  if (!windowsRoot) return Promise.reject(new Error("Windows system root is unavailable; refusing to update the bridge JAR"));
  const executable = path.join(windowsRoot, "System32", "tasklist.exe");
  return new Promise((resolve, reject) => {
    const child = spawn(executable, ["/FO", "CSV", "/NH"], {
      windowsHide: true,
      shell: false,
      env: environment,
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk.toString("utf8"); });
    child.stderr.on("data", (chunk) => { stderr += chunk.toString("utf8"); });
    child.once("error", reject);
    child.once("exit", (code) => {
      if (code !== 0) {
        reject(new Error(`Unable to inspect Java processes before bridge update: ${stderr.trim() || `tasklist exit ${code}`}`));
        return;
      }
      resolve(parseJavaTasklist(stdout));
    });
  });
}

async function copyFileVerified(source, destination) {
  await fsp.mkdir(path.dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.${crypto.randomUUID()}.tmp`;
  try {
    await fsp.copyFile(source, temporary, fs.constants.COPYFILE_EXCL);
    const [sourceHash, temporaryHash] = await Promise.all([hashFile(source), hashFile(temporary)]);
    if (sourceHash !== temporaryHash) throw new Error(`SHA-256 verification failed while copying ${path.basename(source)}`);
    await fsp.rename(temporary, destination);
  } finally {
    await fsp.rm(temporary, { force: true });
  }
}

async function copyDirectory(source, destination, options = {}) {
  let sourceInfo;
  try {
    sourceInfo = await fsp.lstat(source);
  } catch (error) {
    if (error && error.code === "ENOENT") return;
    throw error;
  }
  if (!sourceInfo.isDirectory() || sourceInfo.isSymbolicLink()) throw new Error(`Refusing unsafe source directory: ${source}`);
  await fsp.mkdir(destination, { recursive: true });
  for (const entry of await fsp.readdir(source, { withFileTypes: true })) {
    if (options.exclude?.(entry.name)) continue;
    const sourcePath = path.join(source, entry.name);
    const destinationPath = path.join(destination, entry.name);
    const info = await fsp.lstat(sourcePath);
    if (info.isSymbolicLink()) throw new Error(`Refusing filesystem link in Minecraft instance: ${sourcePath}`);
    if (info.isDirectory()) await copyDirectory(sourcePath, destinationPath, options);
    else if (info.isFile()) await copyFileVerified(sourcePath, destinationPath);
  }
}

async function readJson(file) {
  return JSON.parse(await fsp.readFile(file, "utf8"));
}

async function writeJsonAtomic(file, value) {
  await fsp.mkdir(path.dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.${crypto.randomUUID()}.tmp`;
  try {
    await fsp.writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, "utf8");
    await fsp.rename(temporary, file);
  } finally {
    await fsp.rm(temporary, { force: true });
  }
}

function hashFile(file) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("sha256");
    const input = fs.createReadStream(file);
    input.on("error", reject);
    input.on("data", (chunk) => hash.update(chunk));
    input.on("end", () => resolve(hash.digest("hex")));
  });
}

async function installClone(options) {
  const versionsRoot = path.resolve(options.minecraftRoot, "versions");
  const sourcePath = path.resolve(versionsRoot, options.sourceVersion);
  const targetPath = path.resolve(versionsRoot, options.targetVersion);
  if (!isPathInside(versionsRoot, sourcePath) || !isPathInside(versionsRoot, targetPath)) {
    throw new Error("Minecraft instance path escapes the versions directory");
  }
  if (sourcePath === targetPath) throw new Error("Source and target instances must be different");
  if (!fs.existsSync(options.launcherPath)) throw new Error("Selected HMCL launcher does not exist");
  if (fs.existsSync(targetPath)) throw new Error("Target instance already exists; no files were changed");

  const sourceJson = path.join(sourcePath, `${options.sourceVersion}.json`);
  const sourceJar = path.join(sourcePath, `${options.sourceVersion}.jar`);
  if (!fs.existsSync(sourceJson) || !fs.existsSync(sourceJar)) throw new Error("Source instance is missing its version JSON or JAR");
  const version = await readJson(sourceJson);
  const fingerprint = JSON.stringify(version).toLowerCase();
  if (!fingerprint.includes("net.minecraftforge") || !fingerprint.includes("1.20.1")) {
    throw new Error("This portable package supports Forge 1.20.1 source instances only");
  }

  await fsp.mkdir(targetPath);
  let completed = false;
  try {
    for (const name of ["BODGeneticsPacks", "config", "defaultconfigs", "resourcepacks"]) {
      await copyDirectory(path.join(sourcePath, name), path.join(targetPath, name));
    }
    await copyDirectory(path.join(sourcePath, "mods"), path.join(targetPath, "mods"), {
      exclude: (name) => /^minecraft_codex_bridge-.*\.jar$/iu.test(name) || /baritone.*\.jar$/iu.test(name),
    });
    for (const name of ["options.txt", "optionsshaders.txt", "servers.dat", "log4j2.xml", "modpack.cfg", "modrinth.index.json"]) {
      const source = path.join(sourcePath, name);
      if (fs.existsSync(source)) await copyFileVerified(source, path.join(targetPath, name));
    }
    const instanceSettings = path.join(".hmcl", "config", "instance-game-settings.json");
    if (fs.existsSync(path.join(sourcePath, instanceSettings))) {
      await copyFileVerified(path.join(sourcePath, instanceSettings), path.join(targetPath, instanceSettings));
    }

    version.id = options.targetVersion;
    if (Object.prototype.hasOwnProperty.call(version, "jar")) version.jar = options.targetVersion;
    await writeJsonAtomic(path.join(targetPath, `${options.targetVersion}.json`), version);
    await copyFileVerified(sourceJar, path.join(targetPath, `${options.targetVersion}.jar`));
    await copyFileVerified(options.bridgeJar, path.join(targetPath, "mods", path.basename(options.bridgeJar)));
    if (options.baritoneJar) await copyFileVerified(options.baritoneJar, path.join(targetPath, "mods", path.basename(options.baritoneJar)));
    await fsp.mkdir(path.join(targetPath, "saves"), { recursive: true });
    await writeJsonAtomic(path.join(targetPath, "CODEX-CLONE.json"), {
      sourceVersion: options.sourceVersion,
      targetVersion: options.targetVersion,
      loader: "forge",
      createdAt: new Date().toISOString(),
      bridgeJar: path.basename(options.bridgeJar),
      baritoneJar: options.baritoneJar ? path.basename(options.baritoneJar) : null,
      originalWorldsCopied: false,
      originalLogsCopied: false,
      originalScreenshotsCopied: false,
    });
    completed = true;
    return targetPath;
  } finally {
    if (!completed && isPathInside(versionsRoot, targetPath)) await fsp.rm(targetPath, { recursive: true, force: true });
  }
}

async function updateClone(options) {
  const versionsRoot = path.resolve(options.minecraftRoot, "versions");
  const instancePath = path.resolve(versionsRoot, options.targetVersion);
  if (!isPathInside(versionsRoot, instancePath)) throw new Error("Target instance escapes the versions directory");
  const markerPath = path.join(instancePath, "CODEX-CLONE.json");
  if (!fs.existsSync(markerPath)) throw new Error("Refusing to update an instance without CODEX-CLONE.json");
  const marker = await readJson(markerPath);
  if (marker.targetVersion !== options.targetVersion || marker.loader !== "forge") {
    throw new Error("Clone marker does not identify the requested Forge instance");
  }
  const sourceName = path.basename(options.bridgeJar);
  if (!/^minecraft_codex_bridge-forge-1\.20\.1-[^/\\]+\.jar$/iu.test(sourceName)) {
    throw new Error(`Unexpected Forge bridge filename: ${sourceName}`);
  }

  const runningJava = await (options.inspectJavaProcesses || inspectJavaProcesses)(options.environment || process.env);
  if (!Array.isArray(runningJava)) throw new Error("Java process inspection returned an invalid result");
  if (runningJava.length > 0) {
    throw new Error(`Exit Minecraft and HMCL before updating the bridge JAR (${runningJava.join(", ")})`);
  }

  const mods = path.join(instancePath, "mods");
  await fsp.mkdir(mods, { recursive: true });
  const oldNames = (await fsp.readdir(mods)).filter((name) => /^minecraft_codex_bridge-.*\.jar$/iu.test(name));
  const stamp = new Date().toISOString().replace(/[:.]/gu, "-");
  const backup = path.join(instancePath, "bridge-backups", stamp);
  if (oldNames.length) await fsp.mkdir(backup, { recursive: true });
  for (const name of oldNames) await fsp.rename(path.join(mods, name), path.join(backup, name));
  try {
    await copyFileVerified(options.bridgeJar, path.join(mods, sourceName));
    marker.bridgeJar = sourceName;
    marker.updatedAt = new Date().toISOString();
    await writeJsonAtomic(markerPath, marker);
  } catch (error) {
    await fsp.rm(path.join(mods, sourceName), { force: true });
    for (const name of oldNames) await fsp.rename(path.join(backup, name), path.join(mods, name));
    throw error;
  }
  return instancePath;
}

module.exports = {
  copyDirectory,
  copyFileVerified,
  hashFile,
  inspectJavaProcesses,
  installClone,
  isPathInside,
  parseJavaTasklist,
  updateClone,
};
