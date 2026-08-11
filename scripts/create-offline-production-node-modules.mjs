import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const productionDependencies = [
  ["@fastify/cors", "11.3.0"],
  ["@fastify/static", "8.3.0"],
  ["@fastify/websocket", "11.3.0"],
  ["@mc/protocol", "file:packages/protocol"],
  ["@modelcontextprotocol/sdk", "1.30.0"],
  ["@openai/codex-sdk", "0.146.0"],
  ["fastify", "5.10.0"],
  ["ws", "8.21.1"],
  ["zod", "4.4.3"],
];

function parseArgs(argv) {
  const value = (name) => {
    const prefix = `--${name}=`;
    const argument = argv.find((item) => item.startsWith(prefix));
    if (!argument) throw new Error(`Missing --${name}`);
    return argument.slice(prefix.length);
  };
  return { source: path.resolve(value("source")), destination: path.resolve(value("destination")) };
}

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, "utf8"));
}

function packageRelativePath(packageName) {
  return packageName.startsWith("@")
    ? path.join(...packageName.split("/"))
    : packageName;
}

async function isReparse(file) {
  try {
    return (await fs.lstat(file)).isSymbolicLink();
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

async function resolvePackage(sourceRoot, packageName, fromPackageDir) {
  let directory = fromPackageDir;
  while (true) {
    const candidate = path.join(directory, "node_modules", packageRelativePath(packageName));
    if (await fs.stat(candidate).then(() => true, () => false)) return candidate;
    const parent = path.dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  const rootCandidate = path.join(sourceRoot, packageRelativePath(packageName));
  if (await fs.stat(rootCandidate).then(() => true, () => false)) return rootCandidate;
  return null;
}

async function copyPackage(sourceRoot, destinationRoot, sourceDir, destinationRelative, copied) {
  const resolvedSource = await fs.realpath(sourceDir);
  if (copied.has(resolvedSource)) return;
  copied.add(resolvedSource);
  if (await isReparse(sourceDir)) {
    throw new Error(`Refusing reparse-point production dependency: ${sourceDir}`);
  }
  const destinationDir = path.join(destinationRoot, destinationRelative);
  await fs.mkdir(destinationDir, { recursive: true });
  await fs.cp(sourceDir, destinationDir, {
    recursive: true,
    force: true,
    filter: (source) => path.basename(source) !== "node_modules",
  });
  const manifest = await readJson(path.join(sourceDir, "package.json"));
  const dependencies = new Map(Object.entries(manifest.dependencies ?? {}));
  for (const [name, version] of Object.entries(manifest.optionalDependencies ?? {})) dependencies.set(name, version);
  for (const [name] of Object.entries(manifest.peerDependencies ?? {})) {
    if (manifest.peerDependenciesMeta?.[name]?.optional) continue;
    dependencies.set(name, manifest.peerDependencies[name]);
  }
  for (const name of manifest.bundledDependencies ?? []) dependencies.set(name, "bundled");
  for (const [name] of dependencies) {
    const dependencyDir = await resolvePackage(sourceRoot, name, sourceDir);
    if (!dependencyDir) {
      if (manifest.optionalDependencies?.[name] || manifest.peerDependenciesMeta?.[name]?.optional) continue;
      throw new Error(`Missing installed production dependency ${name} required by ${manifest.name}`);
    }
    const dependencyRelative = path.relative(sourceRoot, dependencyDir);
    await copyPackage(sourceRoot, destinationRoot, dependencyDir, dependencyRelative, copied);
  }
}

async function main() {
  const { source, destination } = parseArgs(process.argv.slice(2));
  const sourceRoot = path.join(source, "node_modules");
  const destinationRoot = path.resolve(destination);
  if (!path.basename(sourceRoot).toLowerCase().includes("node_modules")) throw new Error("Invalid source node_modules");
  if (await fs.stat(destinationRoot).then(() => true, () => false)) {
    const existing = await fs.readdir(destinationRoot);
    if (existing.length > 0) throw new Error("Destination must be a new or empty directory");
  }
  await fs.mkdir(destinationRoot, { recursive: true });
  const copied = new Set();
  for (const [name] of productionDependencies) {
    const sourceDir = name === "@mc/protocol"
      ? path.join(projectRoot, "packages", "protocol")
      : await resolvePackage(sourceRoot, name, projectRoot);
    if (!sourceDir) throw new Error(`Missing direct production dependency ${name}`);
    await copyPackage(sourceRoot, destinationRoot, sourceDir, packageRelativePath(name), copied);
  }
  await fs.copyFile(path.join(sourceRoot, ".package-lock.json"), path.join(destinationRoot, ".package-lock.json"));
  const reparses = [];
  async function scan(directory) {
    for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
      const full = path.join(directory, entry.name);
      if (await isReparse(full)) reparses.push(path.relative(destinationRoot, full));
      else if (entry.isDirectory()) await scan(full);
    }
  }
  await scan(destinationRoot);
  if (reparses.length) throw new Error(`Offline production tree contains reparse points: ${reparses.join(", ")}`);
  process.stdout.write(`${JSON.stringify({
    source: path.relative(projectRoot, source),
    destination: path.relative(projectRoot, destination),
    packages: copied.size,
    reparseCount: reparses.length,
  })}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});
