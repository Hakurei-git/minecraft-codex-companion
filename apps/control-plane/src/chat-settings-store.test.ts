import { mkdtemp, readFile, readdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { ChatSettingsStore } from "./chat-settings-store.js";

const inheritedPersona = {
  mode: "inherit" as const,
  displayName: "",
  personality: "",
  speakingStyle: "",
  memoryNotes: "",
};

describe("ChatSettingsStore", () => {
  it("starts disabled with the configured player and inherited persona", async () => {
    const store = new ChatSettingsStore(undefined, "  PlayerOne  ");

    expect(await store.get()).toMatchObject({
      freeChatEnabled: false,
      playerName: "PlayerOne",
      companionName: "Companion",
      target: "active-provider",
      actionMode: "stable",
      tokenBudget: 512,
      persona: inheritedPersona,
    });
  });

  it("atomically persists a complete free-chat configuration", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-chat-settings-"));
    const store = new ChatSettingsStore(stateDirectory);
    const draft = {
      freeChatEnabled: true,
      playerName: "PlayerOne",
      companionName: "Luna",
      target: "antigravity-mcp" as const,
      persona: {
        mode: "custom" as const,
        displayName: "Luna",
        personality: "Calm, curious, and protective.",
        speakingStyle: "Short, warm Simplified Chinese replies.",
        memoryNotes: "PlayerOne prefers building before exploration.",
      },
    };

    const saved = await store.update(draft);
    const persisted = JSON.parse(
      await readFile(path.join(stateDirectory, "chat-settings.json"), "utf8"),
    ) as Record<string, unknown>;

    expect(saved).toMatchObject(draft);
    expect(persisted).toMatchObject({
      version: 2,
      selectedCompanionName: "Luna",
      profiles: expect.arrayContaining([expect.objectContaining(draft)]),
    });
    expect(await readdir(stateDirectory)).toEqual(["chat-settings.json"]);

    const reloaded = new ChatSettingsStore(stateDirectory, "SomeoneElse");
    expect(await reloaded.get()).toEqual(saved);
  });

  it("loads version 1 settings written before persona support", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-chat-settings-legacy-"));
    const updatedAt = new Date().toISOString();
    await writeFile(path.join(stateDirectory, "chat-settings.json"), JSON.stringify({
      version: 1,
      freeChatEnabled: true,
      playerName: "PlayerOne",
      target: "active-provider",
      updatedAt,
    }), "utf8");

    const store = new ChatSettingsStore(stateDirectory);

    expect(await store.get()).toEqual({
      freeChatEnabled: true,
      playerName: "PlayerOne",
      companionName: "Companion",
      target: "active-provider",
      actionMode: "stable",
      tokenBudget: 512,
      persona: inheritedPersona,
      updatedAt,
    });
  });

  it("loads version 2 profiles written before action modes and token budgets", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-chat-settings-v2-"));
    const updatedAt = new Date().toISOString();
    await writeFile(path.join(stateDirectory, "chat-settings.json"), JSON.stringify({
      version: 2,
      selectedCompanionName: "Aster",
      profiles: [{
        freeChatEnabled: true,
        playerName: "PlayerOne",
        companionName: "Aster",
        target: "active-provider",
        persona: inheritedPersona,
        updatedAt,
      }],
    }), "utf8");

    const store = new ChatSettingsStore(stateDirectory);

    expect(await store.get("Aster")).toMatchObject({
      actionMode: "stable",
      tokenBudget: 512,
    });
  });

  it("migrates legacy hybrid profiles to the binary smart mode", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-chat-settings-hybrid-"));
    const updatedAt = new Date().toISOString();
    await writeFile(path.join(stateDirectory, "chat-settings.json"), JSON.stringify({
      version: 2,
      selectedCompanionName: "Aster",
      profiles: [{
        freeChatEnabled: true,
        playerName: "PlayerOne",
        companionName: "Aster",
        target: "active-provider",
        actionMode: "hybrid",
        tokenBudget: 768,
        persona: inheritedPersona,
        updatedAt,
      }],
    }), "utf8");

    const store = new ChatSettingsStore(stateDirectory);
    expect(await store.get("Aster")).toMatchObject({ actionMode: "smart", tokenBudget: 768 });
  });

  it("keeps independent persona profiles keyed by NPC name across restart", async () => {
    const stateDirectory = await mkdtemp(path.join(os.tmpdir(), "mc-chat-settings-profiles-"));
    const store = new ChatSettingsStore(stateDirectory);
    const base = {
      freeChatEnabled: true,
      playerName: "Player",
      target: "active-provider" as const,
    };
    await store.update({
      ...base,
      companionName: "Aster",
      persona: { ...inheritedPersona, mode: "custom", displayName: "Aster", personality: "安静" },
    });
    await store.update({
      ...base,
      companionName: "Luna",
      persona: { ...inheritedPersona, mode: "custom", displayName: "Luna", personality: "活泼" },
    });

    const reloaded = new ChatSettingsStore(stateDirectory);
    expect((await reloaded.get("Aster")).persona).toMatchObject({ displayName: "Aster", personality: "安静" });
    expect((await reloaded.get("lUnA")).persona).toMatchObject({ displayName: "Luna", personality: "活泼" });
  });
});
