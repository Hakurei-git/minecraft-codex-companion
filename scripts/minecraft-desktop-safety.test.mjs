import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("desktop safety audit observes topmost and cursor confinement without mutating UI", async () => {
  const source = await readFile(
    new URL("./audit-minecraft-desktop-safety.ps1", import.meta.url),
    "utf8",
  );

  assert.match(source, /GetClipCursor/u);
  assert.match(source, /GetWindowLongPtrW/u);
  assert.match(source, /WS_EX_TOPMOST/u);
  assert.match(source, /BackgroundCursorConfinementObserved/u);
  assert.match(source, /WindowTopmostObserved/u);
  assert.match(source, /ReadOnlyAudit\s*=\s*\$true/u);
  assert.match(source, /WindowMutationUsed\s*=\s*\$false/u);
  assert.match(source, /CursorMutationUsed\s*=\s*\$false/u);
  assert.doesNotMatch(
    source,
    /\b(?:ClipCursor|SetWindowPos|ShowWindow(?:Async)?|SetForegroundWindow|PostMessage|PrintWindow|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event)\s*\(/u,
  );
});
