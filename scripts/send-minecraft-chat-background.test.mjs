import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("Minecraft T chat requires manual input and cannot mutate the desktop", async () => {
  const source = await readFile(new URL("./send-minecraft-chat-background.ps1", import.meta.url), "utf8");

  assert.match(source, /MANUAL_UI_REQUIRED/u);
  assert.match(source, /automatic Minecraft T-chat, respawn, and UI normalization are disabled/u);
  assert.match(source, /Press T, type the requested message yourself/u);
  assert.match(source, /Encoding\]::UTF8\.GetString\(\[Convert\]::FromBase64String/u);
  assert.match(source, /NormalizeOnly/u);
  assert.match(source, /RespawnIfDead/u);
  assert.match(source, /RecoverPlayerNearNpcForTest/u);
  assert.match(source, /ControlBaseUri/u);
  assert.match(source, /ControlBaseUri must be an HTTP loopback URL/u);
  assert.match(source, /127\.0\.0\.1.*localhost.*::1/u);
  assert.match(source, /MouseOrKeyboardInputUsed\s*=\s*\$false/u);
  assert.match(source, /WindowMutationUsed\s*=\s*\$false/u);
  assert.match(source, /CursorMutationUsed\s*=\s*\$false/u);
  assert.doesNotMatch(
    source,
    /PostMessage|ShowWindow|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event|Set-Clipboard|Get-Clipboard/u,
  );
});
