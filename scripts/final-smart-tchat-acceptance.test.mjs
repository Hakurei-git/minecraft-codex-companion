import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  FINAL_DIAMOND_PROMPT,
  finalDiamondTaskSpec,
} from "./final-smart-tchat-acceptance.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("final Antigravity acceptance requests one delivered diamond pickaxe", () => {
  assert.equal(FINAL_DIAMOND_PROMPT, "给我制作一把钻石镐并交给我");
  assert.deepEqual(finalDiamondTaskSpec("PlayerOne"), {
    requestedBy: "PlayerOne",
    kind: "craft",
    itemId: "minecraft:diamond_pickaxe",
    count: 1,
    deliverTo: "PlayerOne",
  });
  assert.throws(() => finalDiamondTaskSpec(""));
});

test("final Antigravity acceptance reuses the reversible deep-mining evidence", async () => {
  const source = await readFile(
    path.join(projectRoot, "scripts", "final-smart-tchat-acceptance.mjs"),
    "utf8",
  );
  assert.match(source, /deepMiningFixture\(base, companion\.id, "setup"\)/);
  assert.match(source, /validateInspection\(parseInspection/);
  assert.match(source, /deepMiningFixture\(base, companion\.id, "cleanup"\)/);
  assert.match(source, /chatSettings\.target !== "antigravity-mcp"/);
  assert.match(source, /chatSettings\.actionMode !== "smart"/);
  assert.match(source, /baseUrlInjected: false/);
  assert.doesNotMatch(source, /spruce_planks|云杉木板/u);
});
