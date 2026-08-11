import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const buildScript = await readFile(path.join(projectRoot, "scripts", "build-portable.ps1"), "utf8");
const forgeTestScript = await readFile(
  path.join(projectRoot, "scripts", "run-forge-tests-in-process.ps1"),
  "utf8",
);
const scanScript = await readFile(path.join(projectRoot, "scripts", "scan-portable.ps1"), "utf8");
const singleBuildScript = await readFile(
  path.join(projectRoot, "scripts", "build-single-exe.ps1"),
  "utf8",
);
const singleScanScript = await readFile(
  path.join(projectRoot, "scripts", "scan-single-exe.ps1"),
  "utf8",
);
const startScript = await readFile(
  path.join(projectRoot, "scripts", "start-companion.ps1"),
  "utf8",
);

test("default portable build forcibly rebuilds and validates the Forge bridge", () => {
  assert.match(buildScript, /run-forge-gradle\.ps1/);
  assert.match(buildScript, /"build",\s*\r?\n\s*"--rerun-tasks"/);
  assert.match(buildScript, /\$forgeBuildForced = \[string\]::IsNullOrWhiteSpace\(\$PinnedForgeJarSha256\)/);
  assert.match(buildScript, /if \(\$forgeBuildForced\) \{\s*\r?\n\s*Invoke-Checked/);
  assert.match(buildScript, /forgeBuildStartedAt/);
  assert.match(buildScript, /LastWriteTimeUtc/);
  assert.match(buildScript, /Forge bridge JAR is stale/);
  assert.match(buildScript, /Get-FileHash -Algorithm SHA256 -LiteralPath \$bridgeJar/);
  assert.match(buildScript, /packagedBridgeJarHash -ne \$bridgeJarHash/);
  assert.match(buildScript, /forgeArtifact = \[ordered\]@\{/);
  assert.match(buildScript, /forcedRerun = \$forgeBuildForced/);
  assert.doesNotMatch(buildScript, /-NoNewWindow/);
});

test("offline pinned Forge packaging is explicit, hash-bound, and runs every Forge test", () => {
  assert.match(buildScript, /PinnedForgeJarSha256/);
  assert.match(buildScript, /\^\[A-Fa-f0-9\]\{64\}\$/);
  assert.match(buildScript, /Pinned Forge bridge JAR SHA-256 does not match/);
  assert.match(buildScript, /run-forge-tests-in-process\.ps1/);
  assert.match(buildScript, /pinned-sha256-and-375-tests/);
  assert.match(buildScript, /OfflineNodeModulesRoot/);
  assert.match(buildScript, /Assert-OfflineNodeModules/);
  assert.match(buildScript, /Offline node_modules contains filesystem links or reparse points/);
  assert.match(buildScript, /Offline dependency version mismatch/);
  assert.match(buildScript, /offline-version-checked-copy/);
});

test("Forge tests isolate audited JUnit jars and fail on compiler diagnostics", () => {
  assert.match(forgeTestScript, /junit-5\.10\.2/);
  assert.match(forgeTestScript, /Copy-Item -LiteralPath \$sourceJar\.FullName/);
  assert.match(forgeTestScript, /Get-FileHash -Algorithm SHA256/);
  assert.match(forgeTestScript, /& \$javac -encoding UTF-8 -d \$runnerClasses \$runnerSource/);
  assert.doesNotMatch(forgeTestScript, /& \$javac[^\r\n]*\s-cp\s/);
  assert.match(forgeTestScript, /\$compileOutput\.Count -gt 0/);
});

test("single-EXE release gates accept only fixed names beneath versioned build roots", () => {
  assert.match(singleBuildScript, /Assert-PathUnder \$PayloadRoot \$portableBuildRoot/);
  assert.match(singleBuildScript, /Split-Path -Leaf \$PayloadRoot\) -eq 'MinecraftCodexCompanion-Portable'/);
  assert.match(singleScanScript, /\$outputRoot\.StartsWith\(\$allowedOutputPrefix/);
  assert.match(singleScanScript, /Split-Path -Leaf \$ExecutablePath\) -ne "MinecraftCodexCompanion-Setup\.exe"/);
});

test("Windows launchers embed the versioned multi-resolution project icon", () => {
  assert.match(buildScript, /assets\\branding\\app-icon\.ico/);
  assert.match(buildScript, /\/win32icon:\$appIcon/);
  assert.match(buildScript, /'assets\/branding\/app-icon\.ico'/);
  assert.match(singleBuildScript, /\/win32icon:\$appIcon/);
  assert.match(singleBuildScript, /\$iconHash/);
});

test("local startup inherits the configured Antigravity conversation title", () => {
  assert.match(startScript, /launcherConfig\.antigravityConversationTitle/);
  assert.match(startScript, /MC_ANTIGRAVITY_CONVERSATION_TITLE/);
  assert.match(
    startScript,
    /IsNullOrWhiteSpace\(\[string\]\$env:MC_ANTIGRAVITY_CONVERSATION_TITLE\)/,
  );
  assert.match(
    startScript,
    /\$env:MC_ANTIGRAVITY_CONVERSATION_TITLE\s*=\s*\$configuredConversationTitle/,
  );
});

test("ClamAV is optional, project-local, and selected before installed antivirus", () => {
  assert.match(scanScript, /\[string\]\$ClamScanPath = ""/);
  assert.match(scanScript, /\[string\]\$ClamDatabaseRoot = ""/);
  assert.match(scanScript, /\.runtime\\security/);
  assert.match(scanScript, /\$clamAutoRoot = Join-Path \$clamSecurityRoot "clamav"/);
  assert.match(scanScript, /clamscan\.exe/);
  assert.match(scanScript, /Get-ChildItem -LiteralPath \$clamSecurityRoot -Directory -Filter "clamav-\*"/);
  assert.match(scanScript, /Join-Path \$clamSecurityRoot "clamav-db"/);
  assert.ok(scanScript.indexOf("engine = 'ClamAV'") < scanScript.indexOf("engine = 'Kaspersky'"));
  assert.doesNotMatch(scanScript, /freshclam/i);
});

test("ClamAV records engine and database evidence and requires two clean targets", () => {
  assert.match(scanScript, /Get-FileHash -Algorithm SHA256 -LiteralPath \$resolvedClamScanPath/);
  assert.match(scanScript, /& \$resolvedClamScanPath '--version'/);
  assert.match(scanScript, /databaseEvidence/);
  assert.match(scanScript, /sha256 = \(Get-FileHash -Algorithm SHA256 -LiteralPath \$_\.FullName\)/);
  assert.match(scanScript, /foreach \(\$scanTarget in \$scanTargets\)/);
  assert.match(scanScript, /0 \{ 'clean' \}/);
  assert.match(scanScript, /1 \{ 'infected' \}/);
  assert.match(scanScript, /2 \{ 'error' \}/);
  assert.match(scanScript, /requiredTargetCount = 2/);
  assert.match(scanScript, /allTargetsClean = \$targetReports\.Count -eq 2 -and \$cleanTargetCount -eq 2/);
  assert.match(scanScript, /clamav-local-cli-and-static-database/);
});

test("Kaspersky fails closed when read-only KSN proof is unavailable", () => {
  assert.match(scanScript, /engine = 'Kaspersky'/);
  assert.match(scanScript, /AcceptEULA ksnoff only as a state-changing command/);
  assert.match(scanScript, /status = 'privacy-unverified'/);
  assert.match(scanScript, /fail-closed-no-read-only-ksn-proof/);
  assert.doesNotMatch(scanScript, /& \$kaspersky\s+SCAN/);
});

test("Defender requires local-only policy and scans both payload and archive", () => {
  assert.match(scanScript, /Get-Service -Name WinDefend/);
  assert.match(scanScript, /Get-MpPreference/);
  assert.match(scanScript, /\$mapsReporting -ne 0/);
  assert.match(scanScript, /\$submitSamplesConsent -ne 2/);
  assert.match(scanScript, /\$scanTargets = @\(\$ArtifactRoot, \$archivePath\)/);
  assert.match(scanScript, /foreach \(\$scanTarget in \$scanTargets\)/);
  assert.match(scanScript, /-File \$scanTarget -DisableRemediation/);
});

test("privacy claims are derived from verified scanner evidence", () => {
  assert.match(scanScript, /\$localOnlyVerified = \$scanner\.status -eq 'clean'/);
  assert.match(scanScript, /localOnly = \$localOnlyVerified/);
  assert.match(scanScript, /uploadedFiles = if \(\$localOnlyVerified\)/);
  assert.match(scanScript, /uploadedHashes = if \(\$localOnlyVerified\)/);
  assert.match(scanScript, /proof = \$scanner\.privacyProof/);
  assert.match(scanScript, /Portable antivirus gate failed closed/);
  assert.doesNotMatch(scanScript, /localOnly = \$true/);
  assert.doesNotMatch(scanScript, /Invoke-WebRequest|Invoke-RestMethod|HttpClient|WebClient|https?:\/\//i);
});

test("archive integrity is verified before antivirus selection", () => {
  assert.match(scanScript, /SHA256SUMS\.txt/);
  assert.match(scanScript, /Portable archive SHA-256 does not match/);
  assert.match(scanScript, /sha256 = \$archiveHash/);
});
