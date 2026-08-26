import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("Minecraft-only capture preserves foreground and physical cursor state", async () => {
  const source = await readFile(new URL("./capture-minecraft-window.ps1", import.meta.url), "utf8");
  assert.match(source, /PrintWindow/u);
  assert.match(source, /GetForegroundWindow/u);
  assert.match(source, /GetCursorPos/u);
  assert.match(source, /FindLargestWindowForProcess/u);
  assert.match(source, /Set-MinecraftWindowStateWithoutInteraction/u);
  assert.match(source, /SW_SHOWNOACTIVATE/u);
  assert.match(source, /ForegroundWindowPreserved\s*=\s*\$true/u);
  assert.match(source, /CursorPositionPreserved\s*=\s*\$true/u);
  assert.match(source, /ForegroundInteractionUsed\s*=\s*\$false/u);
  assert.match(source, /PhysicalMouseOrKeyboardInputUsed\s*=\s*\$false/u);
  assert.match(source, /\(\?:Forge 1\\\.20\\\.1\|NeoForge\|1\\\.21\\\.1\)/u);
  assert.doesNotMatch(source, /ShowWindowAsync\([^\r\n]*,\s*9\)/u);
  assert.doesNotMatch(source, /SetCursorPos|SendInput|mouse_event|keybd_event/u);
  assert.doesNotMatch(source, /Set-Clipboard|Get-Clipboard/u);
});
