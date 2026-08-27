import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("Minecraft screenshots read only the selected client area without desktop mutation", async () => {
  const source = await readFile(new URL("./capture-minecraft-window.ps1", import.meta.url), "utf8");

  assert.match(source, /GetClientRect/u);
  assert.match(source, /GetWindowThreadProcessId/u);
  assert.match(source, /GetDC/u);
  assert.match(source, /BitBlt/u);
  assert.match(source, /ClientAreaOnly\s*=\s*\$true/u);
  assert.match(source, /ReadOnlyCapture\s*=\s*\$true/u);
  assert.match(source, /ForegroundUnchanged/u);
  assert.match(source, /CursorClipUnchanged/u);
  assert.match(source, /MouseOrKeyboardInputUsed\s*=\s*\$false/u);
  assert.match(source, /WindowMutationUsed\s*=\s*\$false/u);
  assert.match(source, /CursorMutationUsed\s*=\s*\$false/u);
  assert.doesNotMatch(
    source,
    /\b(?:PrintWindow|PostMessage|ShowWindow(?:Async)?|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event)\s*\(/u,
  );
});
