import { describe, expect, it } from "vitest";
import { declarativeSkillDraftSchema } from "@mc/protocol";
import {
  assertNetworkTargetAllowed,
  auditSkillDraft,
  redactSensitiveData,
  redactSensitiveText,
} from "./skill-security.js";

describe("skill and MCP security", () => {
  it("redacts secret fields, token-shaped values, and local paths", () => {
    const fakeToken = ["sk", "private-token-1234567890"].join("-");
    const fakePath = ["C:", "Users", "PlayerOne", "key.txt"].join("\\");
    const fakeBearer = ["Bearer", "abcdefghijklmnop"].join(" ");
    const redacted = redactSensitiveData({
      apiKey: "plain-secret",
      message: `token ${fakeToken} at ${fakePath}`,
      nested: { authorization: fakeBearer },
    });
    const serialized = JSON.stringify(redacted);
    expect(serialized).not.toContain("plain-secret");
    expect(serialized).not.toContain(fakeToken);
    expect(serialized).not.toContain("Users\\\\PlayerOne");
    expect(serialized).toContain("REDACTED");
    expect(redactSensitiveText(["", "home", "test-user", ".config", "key"].join("/"))).toContain("LOCAL_PATH");
  });

  it("redacts arbitrary labeled keys and Base URLs from unstructured chat text", () => {
    const redacted = redactSensitiveText(
      "api_key=arbitrary-private-value base_url=https://private.example.test/v1",
    );
    expect(redacted).not.toContain("arbitrary-private-value");
    expect(redacted).not.toContain("private.example.test");
    expect(redacted.match(/\[REDACTED_SECRET\]/gu)).toHaveLength(2);
  });

  it("redacts unlabeled provider URLs from errors and player-visible text", () => {
    expect(redactSensitiveText("upstream failed at https://private.example.test/responses"))
      .toBe("upstream failed at [REDACTED_URL]");
  });

  it("defaults network access to local-only policy enforcement", () => {
    expect(assertNetworkTargetAllowed("http://127.0.0.1:8765/mcp").hostname).toBe("127.0.0.1");
    expect(() => assertNetworkTargetAllowed("https://telemetry.example.test/collect")).toThrow(/allowlist/i);
    expect(assertNetworkTargetAllowed("https://api.example.test/v1", "api.example.test").hostname).toBe("api.example.test");
    expect(() => assertNetworkTargetAllowed("https://user:password@api.example.test", "api.example.test")).toThrow(/credentials/i);
  });

  it("requires explicit approval for safe external skills and rejects unsafe metadata", () => {
    const base = declarativeSkillDraftSchema.parse({
      id: "public.test",
      name: "Public test",
      description: "Typed local Minecraft task",
      parameters: [],
      steps: [{ label: "Move", task: { kind: "move", target: { x: 0, y: 64, z: 0 } } }],
      manifest: {
        version: "1.0.0",
        source: { kind: "external" as const, author: "Tester", license: "MIT", url: "https://example.test/skill.json" },
        permissions: { tools: ["mc_assign_task"], network: "none" },
      },
    });
    expect(auditSkillDraft(base).security.status).toBe("pending");
    expect(auditSkillDraft(base, { explicitlyApproved: true }).security.status).toBe("approved");
    const unsafe = declarativeSkillDraftSchema.parse({
      ...base,
      manifest: {
        version: "1.0.0",
        source: { kind: "external", author: "Tester", license: "MIT", url: "https://example.test/skill.json?token=secret" },
        permissions: { tools: ["mc_assign_task"], network: "none" },
      },
    });
    expect(auditSkillDraft(unsafe).security.status).toBe("rejected");
  });

  it("does not grant built-in trust when the content audit has findings", () => {
    const fakeAbsolutePath = ["C:", "Users", "Private", "secret.txt"].join("\\");
    const unsafeBuiltIn = declarativeSkillDraftSchema.parse({
      id: "builtin.unsafe",
      name: "Unsafe",
      description: `Read ${fakeAbsolutePath}`,
      parameters: [],
      steps: [{ label: "Move", task: { kind: "move", target: { x: 0, y: 64, z: 0 } } }],
      manifest: {
        version: "1.0.0",
        source: { kind: "built-in", author: "test", license: "CC0-1.0" },
        permissions: { tools: ["mc_assign_task"], network: "none" },
      },
    });
    const review = auditSkillDraft(unsafeBuiltIn, { builtIn: true });
    expect(review.security.status).toBe("rejected");
    expect(review.security.findings.join(" ")).toMatch(/absolute local path/i);
  });
});
