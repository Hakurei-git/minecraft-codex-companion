import { createHash } from "node:crypto";
import {
  skillManifestSchema,
  type DeclarativeSkillDraft,
  type SkillManifest,
  type SkillSecurityReview,
} from "@mc/protocol";
import { ControlError } from "./errors.js";

const SENSITIVE_KEY = /(?:^|[_-])(api[_-]?key|access[_-]?token|refresh[_-]?token|bridge[_-]?token|csrf[_-]?token|authorization|password|passwd|cookie|secret|session)(?:$|[_-])/iu;
const WINDOWS_PATH = /(?:^|[\s("'])((?:[a-z]:[\\/]|\\\\)[^\s"'<>|]*)/giu;
const PRIVATE_POSIX_PATH = /(?:^|[\s("'])((?:\/(?:home|users|private|var|tmp|etc)\/)[^\s"'<>]*)/giu;
const HTTP_URL = /\bhttps?:\/\/[^\s"'<>)}\]]+/giu;
const SECRET_VALUES: RegExp[] = [
  /\b(?:sk|rk|pk)-[a-z0-9_-]{16,}\b/giu,
  /\bgh[pousr]_[a-z0-9]{20,}\b/giu,
  /\bAIza[0-9A-Za-z_-]{24,}\b/gu,
  /\bBearer\s+[a-z0-9._~+/=-]{12,}\b/giu,
  /\beyJ[a-z0-9_-]{8,}\.[a-z0-9_-]{8,}\.[a-z0-9_-]{8,}\b/giu,
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/gu,
  /\b(?:api[ _-]?key|access[ _-]?token|refresh[ _-]?token|authorization|password|passwd|secret|base[ _-]?url)\b["']?\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;]+)/giu,
];

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => [key, canonicalize(child)]));
  }
  return value;
}

export function skillContentHash(draft: DeclarativeSkillDraft, manifest: SkillManifest): string {
  const content = canonicalize({
    id: draft.id,
    name: draft.name,
    description: draft.description,
    parameters: draft.parameters,
    steps: draft.steps,
    manifest,
  });
  return createHash("sha256").update(JSON.stringify(content), "utf8").digest("hex");
}

export function containsAbsoluteLocalPath(value: string): boolean {
  WINDOWS_PATH.lastIndex = 0;
  PRIVATE_POSIX_PATH.lastIndex = 0;
  return WINDOWS_PATH.test(value) || PRIVATE_POSIX_PATH.test(value);
}

export function containsSecret(value: string): boolean {
  return SECRET_VALUES.some((pattern) => {
    pattern.lastIndex = 0;
    return pattern.test(value);
  });
}

export function redactSensitiveText(value: string): string {
  let output = value;
  for (const pattern of SECRET_VALUES) {
    pattern.lastIndex = 0;
    output = output.replace(pattern, "[REDACTED_SECRET]");
  }
  WINDOWS_PATH.lastIndex = 0;
  PRIVATE_POSIX_PATH.lastIndex = 0;
  output = output
    .replace(WINDOWS_PATH, (match, path: string) => match.replace(path, "[LOCAL_PATH]"))
    .replace(PRIVATE_POSIX_PATH, (match, path: string) => match.replace(path, "[LOCAL_PATH]"));
  HTTP_URL.lastIndex = 0;
  output = output.replace(HTTP_URL, "[REDACTED_URL]");
  return output;
}

export function redactSensitiveData(value: unknown, key = ""): unknown {
  if (SENSITIVE_KEY.test(key)) return "[REDACTED]";
  if (typeof value === "string") return redactSensitiveText(value);
  if (Array.isArray(value)) return value.map((item) => redactSensitiveData(item));
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>)
      .map(([childKey, child]) => [childKey, redactSensitiveData(child, childKey)]));
  }
  return value;
}

export function sensitiveDataFindings(value: unknown, location = "payload"): string[] {
  const findings: string[] = [];
  const visit = (child: unknown, key: string, pointer: string): void => {
    if (SENSITIVE_KEY.test(key)) {
      findings.push(`${pointer}: sensitive field name is not allowed`);
      return;
    }
    if (typeof child === "string") {
      if (containsSecret(child)) findings.push(`${pointer}: possible credential or token`);
      if (containsAbsoluteLocalPath(child)) findings.push(`${pointer}: absolute local path`);
      return;
    }
    if (Array.isArray(child)) {
      child.forEach((item, index) => visit(item, "", `${pointer}[${index}]`));
      return;
    }
    if (child && typeof child === "object") {
      for (const [childKey, nested] of Object.entries(child as Record<string, unknown>)) {
        visit(nested, childKey, `${pointer}.${childKey}`);
      }
    }
  };
  visit(value, "", location);
  return [...new Set(findings)];
}

export function assertNoSensitiveData(value: unknown, location = "MCP arguments"): void {
  const findings = sensitiveDataFindings(value, location);
  if (findings.length > 0) {
    throw new ControlError({
      code: "SENSITIVE_DATA_BLOCKED",
      message: `Sensitive data was blocked: ${findings.join("; ")}`,
      statusCode: 400,
    });
  }
}

function validateSourceUrl(manifest: SkillManifest, findings: string[]): void {
  if (!manifest.source.url) return;
  try {
    const url = new URL(manifest.source.url);
    if (url.protocol !== "https:") findings.push("source.url: external skill sources must use HTTPS");
    if (url.username || url.password || url.search || url.hash) {
      findings.push("source.url: credentials, query strings, and fragments are forbidden");
    }
    if (["localhost", "127.0.0.1", "::1"].includes(url.hostname.toLowerCase())) {
      findings.push("source.url: external skill source cannot point to a local service");
    }
  } catch {
    findings.push("source.url: invalid URL");
  }
}

function validateHosts(manifest: SkillManifest, findings: string[]): void {
  for (const host of manifest.permissions.allowedHosts) {
    if (!/^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$/iu.test(host)) {
      findings.push(`permissions.allowedHosts: invalid host ${JSON.stringify(host)}`);
    }
    if (host.includes("*") || host.includes(":") || host.includes("/") || host.includes("@")) {
      findings.push("permissions.allowedHosts: wildcards, ports, paths, and credentials are forbidden");
    }
  }
}

export function auditSkillDraft(
  draft: DeclarativeSkillDraft,
  options: { builtIn?: boolean; explicitlyApproved?: boolean } = {},
): { manifest: SkillManifest; security: SkillSecurityReview } {
  const manifest = skillManifestSchema.parse(draft.manifest ?? {});
  const findings = sensitiveDataFindings({
    name: draft.name,
    description: draft.description,
    parameters: draft.parameters,
    steps: draft.steps,
  }, "skill");
  validateSourceUrl(manifest, findings);
  validateHosts(manifest, findings);
  if (!manifest.permissions.tools.includes("mc_assign_task")) {
    findings.push("permissions.tools: declarative task steps require mc_assign_task");
  }

  if (manifest.source.kind === "external" && manifest.permissions.network === "local-only") {
    findings.push("permissions.network: external skills cannot access local services");
  }
  const sha256 = skillContentHash(draft, manifest);
  const now = new Date().toISOString();
  const status: SkillSecurityReview["status"] = options.builtIn
    ? findings.length === 0 ? "trusted" : "rejected"
    : findings.length > 0
      ? "rejected"
      : (manifest.source.kind === "external" || manifest.permissions.network !== "none") && !options.explicitlyApproved
        ? "pending"
        : "approved";
  return {
    manifest,
    security: {
      status,
      sha256,
      reviewedAt: status === "pending" ? null : now,
      findings: [...new Set(findings)].slice(0, 64),
    },
  };
}

export function assertNetworkTargetAllowed(rawUrl: string, configuredHosts = ""): URL {
  let url: URL;
  try {
    url = new URL(rawUrl);
  } catch {
    throw new ControlError({ code: "NETWORK_TARGET_BLOCKED", message: "Invalid network target", statusCode: 400 });
  }
  if (url.username || url.password) {
    throw new ControlError({ code: "NETWORK_TARGET_BLOCKED", message: "Credentials in URLs are forbidden", statusCode: 400 });
  }
  const host = url.hostname.toLowerCase();
  const local = host === "127.0.0.1" || host === "localhost" || host === "::1";
  const allowed = new Set(configuredHosts.split(",").map((item) => item.trim().toLowerCase()).filter(Boolean));
  if (!local && !allowed.has(host)) {
    throw new ControlError({
      code: "NETWORK_TARGET_BLOCKED",
      message: `Network target ${host} is not on the allowlist`,
      statusCode: 403,
    });
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new ControlError({ code: "NETWORK_TARGET_BLOCKED", message: "Only HTTP(S) targets are allowed", statusCode: 403 });
  }
  return url;
}
