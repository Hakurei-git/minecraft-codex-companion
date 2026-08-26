import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("Minecraft screenshots require a manual UI step and cannot mutate the desktop", async () => {
  const source = await readFile(new URL("./capture-minecraft-window.ps1", import.meta.url), "utf8");

  assert.match(source, /MANUAL_UI_REQUIRED/u);
  assert.match(source, /automatic Minecraft screenshots are disabled/u);
  assert.match(source, /Capture '\$Name' manually/u);
  assert.doesNotMatch(
    source,
    /\b(?:PrintWindow|PostMessage|ShowWindow(?:Async)?|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event)\s*\(/u,
  );
});
