$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "launch-hmcl-background.ps1"

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) {
        throw "Assertion failed: $Message (expected=$Expected actual=$Actual)"
    }
}

$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$tokens,
    [ref]$errors
)
if ($errors.Count -gt 0) {
    throw "PowerShell parser rejected ${scriptPath}: $($errors[0].Message)"
}
$functionAst = $ast.Find({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "Get-ReplacementProcessDeadline"
}, $true)
if ($null -eq $functionAst) { throw "Replacement deadline helper was not found" }
. ([ScriptBlock]::Create($functionAst.Extent.Text))
$fallbackFunctionAst = $ast.Find({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq "Test-MinecraftFallbackProcess"
}, $true)
if ($null -eq $fallbackFunctionAst) { throw "Minecraft fallback helper was not found" }
. ([ScriptBlock]::Create($fallbackFunctionAst.Extent.Text))

$now = [DateTime]::SpecifyKind([DateTime]::Parse("2026-08-09T00:00:00"), "Utc")
$longWindow = $now.AddSeconds(120)
$deadline = Get-ReplacementProcessDeadline $longWindow $now 120
Assert-Equal $deadline $now.AddSeconds(30) "replacement hand-off receives a 30-second grace period"

$shortWindow = $now.AddSeconds(12)
$deadline = Get-ReplacementProcessDeadline $shortWindow $now 120
Assert-Equal $deadline $shortWindow "replacement hand-off never exceeds the main window deadline"

$source = Get-Content -LiteralPath $scriptPath -Raw -Encoding UTF8
Assert-True (-not $source.Contains('$replacementDeadline = (Get-Date).AddSeconds(3)')) `
    "the obsolete three-second replacement race remains"
Assert-True ($source.Contains('-WindowDeadline $windowDeadline')) `
    "the replacement search is not bounded by the Forge window deadline"
Assert-True ($source.Contains('BackgroundWithoutFocus($gameWindowHandle)')) `
    "the Forge window is not backgrounded without focus before world entry"
Assert-True ($source.Contains('GameWindowBackgrounded = $true')) `
    "the launch evidence omits the backgrounded Forge window"
Assert-True ($source.Contains('GameWindowHandle = $gameWindowHandle.ToInt64()')) `
    "the launch evidence omits the verified Forge HWND"
Assert-True ($source.Contains('ActiveWindowStatusWritten = $true')) `
    "the launch evidence omits the local verified-window handoff"
Assert-True ($source.Contains('GameWindowMinimized = $false')) `
    "the launch evidence does not preserve the entry helper HWND contract"
Assert-True ($source.Contains('CursorCaptureReleased = $true')) `
    "the launch evidence omits cursor-capture release"
Assert-True ($source.Contains('QuickPlayWorld is restricted to an isolated SandboxProfileRoot')) `
    "Quick Play is not restricted to the isolated profile"
Assert-True ($source.Contains('UseInstalledBridgeForDiagnostic is restricted to an isolated SandboxProfileRoot')) `
    "the stale-bridge diagnostic escape hatch is not restricted to the isolated profile"
Assert-True ($source.Contains('$launchEnvironment.TEMP = $sandboxTempDirectory')) `
    "the isolated HMCL profile does not use a short TEMP path for Java NIO selectors"
Assert-True ($source.Contains('$launchEnvironment.TMP = $sandboxTempDirectory')) `
    "the isolated HMCL profile does not use a short TMP path for Java NIO selectors"
Assert-True (-not $source.Contains('$launchPlayerName = "CodexTest"')) `
    "the isolated HMCL profile breaks the bridge owner binding with a synthetic player name"
Assert-True ($source.Contains('$launchPlayerName = [string]$config.playerName')) `
    "the isolated HMCL profile does not preserve the configured offline player name"
Assert-True ($source.Contains('$launchAgentRequest += "|" + $quickPlayWorldArgument')) `
    "the validated Quick Play world is not passed to the HMCL agent"

$sandboxSessionSource = Get-Content -LiteralPath `
    (Join-Path $PSScriptRoot "run-sandbox-minecraft-session.ps1") -Raw -Encoding UTF8
Assert-True (-not $sandboxSessionSource.Contains('-QuickPlayWorld "Codex-Test"')) `
    "the isolated acceptance session still uses the crashing Quick Play path"
Assert-True ($sandboxSessionSource.Contains('[string]$WorldId = "Codex-Test"')) `
    "the isolated acceptance session does not default to Codex-Test"
Assert-True ($sandboxSessionSource.Contains('WorldId = $WorldId')) `
    "the isolated acceptance session does not select its validated world through the background entry helper"
Assert-True ($sandboxSessionSource.Contains('NativeWindowHandle = $launchResult.GameWindowHandle')) `
    "the isolated acceptance session does not hand the verified Forge HWND to the entry helper"
Assert-True ($sandboxSessionSource.Contains('[switch]$RunFinalDiamondAcceptance')) `
    "the isolated acceptance session cannot run T-chat acceptance on its own desktop"
Assert-True ($sandboxSessionSource.Contains('final-smart-tchat-acceptance.mjs')) `
    "the isolated acceptance session does not invoke the final diamond workflow"

$javaProbe = [PSCustomObject]@{
    Id = 102
    ProcessName = "javaw"
    Responding = $true
    MainWindowHandle = [IntPtr]::Zero
    MainWindowTitle = ""
}
Assert-True (-not (Test-MinecraftFallbackProcess $javaProbe @(101) 100)) `
    "a headless Java runtime probe is still classified as Minecraft"
$forgeWindow = [PSCustomObject]@{
    Id = 103
    ProcessName = "javaw"
    Responding = $true
    MainWindowHandle = [IntPtr]123
    MainWindowTitle = "Minecraft Forge 1.20.1"
}
Assert-True (Test-MinecraftFallbackProcess $forgeWindow @(101) 100) `
    "a stable Forge window is not accepted by the compact-JRE fallback"
Assert-True (-not (Test-MinecraftFallbackProcess $forgeWindow @(103) 100)) `
    "a pre-existing Forge process bypasses the launch PID boundary"

$projectRoot = Split-Path -Parent $PSScriptRoot
$agentSourceRoot = Join-Path $PSScriptRoot "hmcl-background-agent"
$agentTestClasses = Join-Path $projectRoot ".runtime\hmcl-background-agent-tests\classes"
[IO.Directory]::CreateDirectory($agentTestClasses) | Out-Null
$javac = (Get-Command "javac" -ErrorAction Stop).Source
$java = (Get-Command "java" -ErrorAction Stop).Source
& $javac -encoding UTF-8 -d $agentTestClasses `
    (Join-Path $agentSourceRoot "HmclBackgroundLaunchAgent.java") `
    (Join-Path $agentSourceRoot "HmclBackgroundLaunchAgentTest.java")
if ($LASTEXITCODE -ne 0) { throw "Failed to compile HMCL background-agent tests" }
& $java -cp $agentTestClasses HmclBackgroundLaunchAgentTest
if ($LASTEXITCODE -ne 0) { throw "HMCL background-agent tests failed" }
& $javac -encoding UTF-8 -d $agentTestClasses `
    (Join-Path $agentSourceRoot "HmclCrashProbeAgent.java") `
    (Join-Path $agentSourceRoot "HmclCrashProbeAgentTest.java")
if ($LASTEXITCODE -ne 0) { throw "Failed to compile HMCL crash-probe tests" }
& $java -cp $agentTestClasses HmclCrashProbeAgentTest
if ($LASTEXITCODE -ne 0) { throw "HMCL crash-probe tests failed" }

Write-Output "HMCL replacement-process race tests passed"
