import { describe, expect, it } from "vitest";
import {
  endpointFromRunningProcess,
  normalizeAntigravityConnectionError,
} from "./antigravity-endpoint.js";

const executable = "C:\\Apps\\反重力\\resources\\bin\\language_server.exe";
const token = "22222222-2222-2222-2222-222222222222";
const log = `
Starting language server process with pid 55276
Language server version: 2.23.1
Language server listening on random port at 51212 for HTTPS (gRPC)
Language server listening on random port at 51213 for HTTP
`;
const process = {
  ProcessId: 55276,
  ExecutablePath: executable,
  CsrfToken: token,
};

describe("Antigravity desktop process discovery", () => {
  it("uses the exact process and explicit TLS and plaintext ports for the corresponding APIs", () => {
    expect(endpointFromRunningProcess(log, [process], executable)).toEqual({
      webAddress: "127.0.0.1:51212",
      grpcAddress: "127.0.0.1:51213",
      csrfToken: token,
      version: "2.23.1",
    });
  });

  it("does not infer an adjacent port and waits for both listeners to start", () => {
    expect(endpointFromRunningProcess(log.replace("51213", "60000"), [process], executable)?.grpcAddress).toBe("127.0.0.1:60000");
    expect(endpointFromRunningProcess(log.replace(/^.*51213.*$/mu, ""), [process], executable)).toBeNull();
    expect(endpointFromRunningProcess(log.replace("51213", "65536"), [process], executable)).toBeNull();
  });

  it.each([
    { ...process, ProcessId: 123 },
    { ...process, ExecutablePath: "C:\\Other\\language_server.exe" },
    { ...process, CsrfToken: "" },
  ])("rejects an unrelated or incomplete process: %j", (candidate) => {
    expect(endpointFromRunningProcess(log, [candidate], executable)).toBeNull();
  });

  it("accepts Windows path casing without changing the credential", () => {
    expect(endpointFromRunningProcess(log, [{
      ...process,
      ExecutablePath: executable.toUpperCase(),
    }], executable)?.csrfToken).toBe(token);
  });

  it("does not reuse an old port while the new process is starting", () => {
    expect(endpointFromRunningProcess(`${log}\nStarting language server process with pid 99999`, [process], executable)).toBeNull();
  });

  it.each(["0", "65536", "NaN"])("rejects invalid port %s", (port) => {
    expect(endpointFromRunningProcess(log.replace("51212", port), [process], executable)).toBeNull();
  });
});

describe("Antigravity connection errors", () => {
  it.each(["ECONNREFUSED", "ECONNRESET", "ETIMEDOUT", "EHOSTUNREACH"])("reports %s as a recoverable upstream failure", (code) => {
    const error = normalizeAntigravityConnectionError(Object.assign(new Error("raw detail"), { code }));
    expect(error).toMatchObject({ code: "ANTIGRAVITY_UNAVAILABLE", statusCode: 503, retryable: true });
    expect(String(error)).not.toContain("raw detail");
  });

  it("preserves programming errors for the normal internal error handler", () => {
    const error = new TypeError("invalid state");
    expect(normalizeAntigravityConnectionError(error)).toBe(error);
  });
});
