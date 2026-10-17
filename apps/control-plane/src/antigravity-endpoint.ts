import { execFile as execFileCallback } from "node:child_process";
import { open } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";
import { ControlError } from "./errors.js";

const execFile = promisify(execFileCallback);

export interface AntigravityEndpoint {
  webAddress: string;
  grpcAddress: string;
  csrfToken: string;
  version: string;
}

export interface AntigravityProcess {
  ProcessId: number;
  ExecutablePath: string;
  CsrfToken: string;
}

/** The desktop rewrites this log on each LS launch; never combine different PIDs. */
export function endpointFromRunningProcess(
  log: string,
  processes: AntigravityProcess[],
  executable: string,
): AntigravityEndpoint | null {
  const launches = [...log.matchAll(/Starting language server process with pid\s+(\d+)/gu)];
  const launch = launches.at(-1);
  if (!launch) return null;
  const current = log.slice(launch.index);
  const port = Number(current.match(/Language server listening on random port at\s+(\d+)\s+for HTTPS\s+\(gRPC\)/u)?.[1]);
  const agentPort = Number(current.match(/Language server listening on random port at\s+(\d+)\s+for HTTP\b/u)?.[1]);
  if (![port, agentPort].every((value) => Number.isInteger(value) && value >= 1 && value <= 65_535)) return null;
  const process = processes.find((candidate) => candidate.ProcessId === Number(launch[1])
    && typeof candidate.ExecutablePath === "string"
    && path.win32.normalize(candidate.ExecutablePath).toLowerCase() === path.win32.normalize(executable).toLowerCase());
  if (!process || typeof process.CsrfToken !== "string" || !/^[0-9a-f-]{16,}$/iu.test(process.CsrfToken)) return null;
  return {
    webAddress: `127.0.0.1:${port}`,
    // agentapi uses plaintext gRPC, while Connect JSON uses the TLS listener.
    grpcAddress: `127.0.0.1:${agentPort}`,
    csrfToken: process.CsrfToken,
    version: current.match(/Language server version:\s*([^\s]+)/u)?.[1] ?? "unknown",
  };
}

export function antigravityConnectionError(): ControlError {
  return new ControlError({
    code: "ANTIGRAVITY_UNAVAILABLE",
    statusCode: 503,
    retryable: true,
    message: "无法连接反重力本地服务；程序可能已关闭、重启或更换了端口",
    suggestedRecovery: "请打开反重力并等待启动完成，再点击连接；已保存的会话绑定会保留。",
  });
}

export function normalizeAntigravityConnectionError(error: unknown): unknown {
  if (error instanceof ControlError) return error;
  const code = error && typeof error === "object" && "code" in error ? error.code : null;
  if (["ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "EHOSTUNREACH"].includes(String(code))) {
    return antigravityConnectionError();
  }
  return error;
}

/** New desktop releases omit the CSRF argument from main.log. Read it only
 * from the exact installed LS process, and match that PID to its startup log.
 * Neither command lines nor local credentials may be logged or persisted. */
export async function discoverRunningAntigravityEndpoint(
  logRoot: string,
  executable: string,
): Promise<AntigravityEndpoint | null> {
  if (process.platform !== "win32") return null;
  const handle = await open(path.join(logRoot, "language_server.log"), "r").catch(() => null);
  if (!handle) return null;
  let log: string;
  try {
    const buffer = Buffer.alloc(64 * 1024);
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    log = buffer.subarray(0, bytesRead).toString("utf8");
  } finally {
    await handle.close();
  }
  const pid = log.match(/Starting language server process with pid\s+(\d+)/u)?.[1];
  if (!pid) return null;
  const helper = process.env.MC_COMPANION_SECRET_HELPER?.trim()
    || path.join(path.dirname(process.execPath), "MinecraftCodexSecret.exe");
  try {
    const { stdout } = await execFile(helper, ["antigravity-process", pid, executable], {
      windowsHide: true, timeout: 8_000, maxBuffer: 128 * 1024, encoding: "utf8",
    });
    const parsed: unknown = stdout.trim() ? JSON.parse(stdout.replace(/^\uFEFF/u, "")) : [];
    const processes = (Array.isArray(parsed) ? parsed : [parsed])
      .filter((item): item is AntigravityProcess => item !== null && typeof item === "object");
    return endpointFromRunningProcess(log, processes, executable);
  } catch {
    // An exec error can contain stdout with a local credential; do not forward it.
    throw new ControlError({
      code: "ANTIGRAVITY_DISCOVERY_FAILED",
      statusCode: 503,
      retryable: true,
      message: "无法读取反重力当前进程的本地连接信息",
      suggestedRecovery: "请使用包含新版本地连接助手的 Companion，并确认它与反重力由同一 Windows 用户运行后重试。",
    });
  }
}
