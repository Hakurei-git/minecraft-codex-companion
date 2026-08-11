import { randomBytes } from "node:crypto";
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
