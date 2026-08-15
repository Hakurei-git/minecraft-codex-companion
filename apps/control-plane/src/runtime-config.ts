import { createHash, randomBytes, randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

export function resolveStateDirectory(): string {
  if (process.env.MC_COMPANION_STATE_DIR) return path.resolve(process.env.MC_COMPANION_STATE_DIR);
  const base = process.env.LOCALAPPDATA ?? path.join(os.homedir(), ".local", "share");
  return path.join(base, "MinecraftCodexCompanion");
}

export async function loadOrCreateBridgeToken(stateDirectory = resolveStateDirectory()): Promise<string> {
  const fromEnvironment = process.env.MC_BRIDGE_TOKEN?.trim();
  if (fromEnvironment && fromEnvironment.length >= 16) return fromEnvironment;

  const tokenPath = path.join(stateDirectory, "bridge-token.txt");
  try {
    const existing = (await readFile(tokenPath, "utf8")).trim();
    if (existing.length >= 16) return existing;
  } catch (caught) {
    const code = caught instanceof Error && "code" in caught ? (caught as NodeJS.ErrnoException).code : undefined;
    if (code !== "ENOENT") throw caught;
  }

  const token = randomBytes(32).toString("hex");
  await mkdir(stateDirectory, { recursive: true });
  await writeFile(tokenPath, `${token}\n`, { encoding: "utf8", mode: 0o600 });
  return token;
}

export function bridgeTokenFingerprint(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex").slice(0, 16);
}

export async function loadOrCreateInstallationId(stateDirectory = resolveStateDirectory()): Promise<string> {
  const fromEnvironment = process.env.MC_COMPANION_INSTALLATION_ID?.trim();
  if (fromEnvironment && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(fromEnvironment)) {
    return fromEnvironment.toLowerCase();
  }

  const installationPath = path.join(stateDirectory, "installation-id.txt");
  try {
    const existing = (await readFile(installationPath, "utf8")).trim();
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(existing)) {
      return existing.toLowerCase();
    }
  } catch (caught) {
    const code = caught instanceof Error && "code" in caught ? (caught as NodeJS.ErrnoException).code : undefined;
    if (code !== "ENOENT") throw caught;
  }

  const installationId = randomUUID();
  await mkdir(stateDirectory, { recursive: true });
  await writeFile(installationPath, `${installationId}\n`, { encoding: "utf8", mode: 0o600 });
  return installationId;
}
