import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("background Minecraft chat posts UTF-16 characters through PostMessageW", async () => {
  const source = await readFile(new URL("./send-minecraft-chat-background.ps1", import.meta.url), "utf8");
  assert.match(source, /EntryPoint\s*=\s*"PostMessageW"/u);
  assert.match(source, /Encoding\]::UTF8\.GetString\(\[Convert\]::FromBase64String/u);
  assert.match(source, /clientUiState/u);
  assert.match(source, /RespawnIfDead/u);
  assert.match(source, /active-minecraft-window\.status/u);
  assert.match(source, /GetWindowThreadProcessId/u);
  assert.match(source, /FindLargestWindowForProcess/u);
  assert.match(source, /net\.minecraft\.client\.main\.Main/u);
  assert.match(source, /initialUiState\s+-eq\s+"death"/u);
  assert.match(source, /MinecraftBackgroundChatPost\]::Click/u);
  assert.match(source, /Wait-ClientUiState\s+"chat"/u);
  assert.match(source, /Wait-ClientUiState\s+"gameplay"/u);
  assert.match(source, /ChatOpenAttempts/u);
  assert.match(source, /ClipCursor\(IntPtr\.Zero\)/u);
  assert.match(source, /ReleaseCursorCapture\(\$handle\)/u);
  assert.match(source, /CursorCaptureReleased\s*=\s*\$true/u);
  assert.doesNotMatch(source, /Set-Clipboard|Get-Clipboard/u);
});
