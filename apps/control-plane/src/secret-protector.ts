import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";

export interface SecretProtector {
  protect(value: string): Promise<string>;
  unprotect(value: string): Promise<string>;
}

function defaultHelperPath(): string {
  const configured = process.env.MC_COMPANION_SECRET_HELPER?.trim();
  if (configured) return path.resolve(configured);
  return path.join(path.dirname(process.execPath), "MinecraftCodexSecret.exe");
}

async function runHelper(helperPath: string, operation: "protect" | "unprotect", input: string): Promise<string> {
  if (process.platform !== "win32") {
    throw new Error("Windows DPAPI secret storage is only available on Windows");
  }
  if (!existsSync(helperPath)) {
    throw new Error(`The local DPAPI helper is missing: ${helperPath}`);
  }

  return new Promise<string>((resolve, reject) => {
    const child = spawn(helperPath, [operation], {
      windowsHide: true,
      shell: false,
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let settled = false;
    let timer: NodeJS.Timeout;
    const finish = (error?: Error, value?: string) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) reject(error);
      else resolve(value ?? "");
    };
    timer = setTimeout(() => {
      child.kill();
      finish(new Error("DPAPI operation timed out"));
    }, 10_000);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      if (stdout.length < 128 * 1024) stdout += chunk;
    });
    child.stderr.on("data", (chunk: string) => {
      if (stderr.length < 16 * 1024) stderr += chunk;
    });
    child.once("error", (error) => finish(error));
    child.once("close", (code) => {
      const output = stdout.trim();
      if (code === 0 && output) finish(undefined, output);
      else finish(new Error(`DPAPI operation failed${stderr.trim() ? `: ${stderr.trim()}` : ""}`));
    });
    child.stdin.end(input, "utf8");
  });
}

export class WindowsDpapiSecretProtector implements SecretProtector {
  readonly #helperPath: string;

  constructor(options: { helperPath?: string } = {}) {
    this.#helperPath = path.resolve(options.helperPath ?? defaultHelperPath());
  }

  async protect(value: string): Promise<string> {
    const encoded = Buffer.from(value, "utf8").toString("base64");
    const cipher = await runHelper(this.#helperPath, "protect", encoded);
    return `dpapi:v1:${cipher}`;
  }

  async unprotect(value: string): Promise<string> {
    const prefix = "dpapi:v1:";
    if (!value.startsWith(prefix)) throw new Error("Unsupported secret payload");
    const encoded = await runHelper(this.#helperPath, "unprotect", value.slice(prefix.length));
    return Buffer.from(encoded, "base64").toString("utf8");
  }
}
