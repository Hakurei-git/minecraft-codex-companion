import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  bridgeTokenFingerprint,
  loadOrCreateInstallationId,
} from "./runtime-config.js";

describe("runtime service identity", () => {
  const directories: string[] = [];

  afterEach(async () => {
    await Promise.all(directories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
  });

  it("persists one installation id across service restarts", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-companion-identity-"));
    directories.push(stateDirectory);

    const first = await loadOrCreateInstallationId(stateDirectory);
    const second = await loadOrCreateInstallationId(stateDirectory);

    expect(second).toBe(first);
    expect((await readFile(path.join(stateDirectory, "installation-id.txt"), "utf8")).trim()).toBe(first);
    expect(first).toMatch(/^[0-9a-f-]{36}$/u);
  });

  it("repairs an invalid installation id without exposing bridge tokens", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-companion-identity-"));
    directories.push(stateDirectory);
    await writeFile(path.join(stateDirectory, "installation-id.txt"), "not-an-installation-id\n", "utf8");

    const repaired = await loadOrCreateInstallationId(stateDirectory);

    expect(repaired).not.toBe("not-an-installation-id");
    expect(bridgeTokenFingerprint("0123456789abcdef0123456789abcdef")).toMatch(/^[0-9a-f]{16}$/u);
    expect(bridgeTokenFingerprint("0123456789abcdef0123456789abcdef")).not.toContain("0123456789abcdef0123456789abcdef");
  });
});
