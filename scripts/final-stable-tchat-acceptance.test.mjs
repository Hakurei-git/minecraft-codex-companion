import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  STABLE_DIAMOND_PROMPT,
  stableChatSettings,
} from "./final-stable-tchat-acceptance.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("stable T-chat acceptance uses the local deterministic diamond-pickaxe phrase", () => {
  assert.equal(STABLE_DIAMOND_PROMPT, "给我做一把钻石镐");
  const original = {
    freeChatEnabled: false,
    playerName: "PlayerOne",
    companionName: "TestCompanion",
    target: "antigravity-mcp",
    actionMode: "smart",
    tokenBudget: 512,
    persona: { mode: "inherit", displayName: "", personality: "", speakingStyle: "", memoryNotes: "" },
  };
  assert.deepEqual(stableChatSettings(original), { ...original, actionMode: "stable" });
});

test("stable T-chat acceptance rejects external routing and restores local state", async () => {
  const source = await readFile(
    path.join(projectRoot, "scripts", "final-stable-tchat-acceptance.mjs"),
    "utf8",
  );
  assert.match(source, /actionMode: "stable"/);
  assert.match(source, /externalModelRequired: false/);
  assert.match(source, /localChatHistoryRequired: false/);
  assert.match(source, /Sent\\s\*:\\s\*True/);
  assert.match(source, /smartDecisionEvents !== 0 \|\| antigravityEvents !== 0/);
  assert.match(source, /deepMiningFixture\(base, companion\.id, "setup"\)/);
  assert.match(source, /deepMiningFixture\(base, companion\.id, "cleanup"\)/);
  assert.match(source, /settingsRestored: true/);
  assert.match(source, /baseUrlInjected: false/);
});
