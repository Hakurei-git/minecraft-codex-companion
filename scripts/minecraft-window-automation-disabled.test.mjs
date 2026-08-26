import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const guardedScripts = [
  "launch-hmcl-background.ps1",
  "launch-hmcl-selected-instance.ps1",
  "stop-minecraft-for-update.ps1",
];

for (const name of guardedScripts) {
  test(`${name} fails closed without desktop automation`, async () => {
    const source = await readFile(new URL(`./${name}`, import.meta.url), "utf8");
    const guard = source.indexOf("MANUAL_UI_REQUIRED:");

    assert.ok(guard >= 0, `${name} has no manual UI guard`);
    assert.doesNotMatch(
      source,
      /Add-Type|DllImport|PostMessage|ShowWindow|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos/u,
    );
  });
}

test("ensure-minecraft-running uses telemetry only unless a manual action is required", async () => {
  const source = await readFile(new URL("./ensure-minecraft-running.ps1", import.meta.url), "utf8");

  assert.match(source, /SnapshotAdvanced\s*=\s*\$true/u);
  assert.match(source, /MANUAL_UI_REQUIRED: return Minecraft to active gameplay yourself/u);
  assert.match(source, /if \(\$ForcePauseMenu\)[\s\S]*MANUAL_UI_REQUIRED/u);
  assert.doesNotMatch(source, /Add-Type|DllImport|PostMessage|ShowWindow|SetWindowPos|ClipCursor/u);
});
