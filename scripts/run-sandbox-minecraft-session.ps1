[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_-]{8,64}$')]
    [string]$SessionId,

    [ValidateRange(120, 3600)]
    [int]$WaitSeconds = 300,

    [ValidateRange(300, 7200)]
    [int]$MaximumSessionSeconds = 1800,

    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')]
    [string]$WorldId = "Codex-Test",

    [switch]$CaptureBeforeEntry,

    [switch]$CaptureBeforeSelection,

    [switch]$UseInstalledBridgeForDiagnostic,

    [switch]$RunFinalDiamondAcceptance,

    [ValidateRange(120, 1200)]
    [int]$FinalAcceptanceWaitSeconds = 900
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$stopSignal = Join-Path $projectRoot (".runtime\sandbox-session-" + $SessionId + ".stop")
$jcmd = (Get-Command "jcmd.exe" -ErrorAction Stop).Source
$launchResult = $null

function Stop-VerifiedJavaProcess([int]$ProcessId, [string]$ClassName) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return }
    $probe = (& $jcmd $ProcessId "VM.class_hierarchy" $ClassName 2>$null | Out-String)
    if ($probe -notmatch [regex]::Escape($ClassName)) {
        throw "Refusing to stop a Java process whose identity changed"
    }
    [void]$process.CloseMainWindow()
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 250
    }
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
        $probe = (& $jcmd $ProcessId "VM.class_hierarchy" $ClassName 2>$null | Out-String)
        if ($probe -notmatch [regex]::Escape($ClassName)) {
            throw "Refusing to force-stop a Java process whose identity changed"
        }
        Stop-Process -Id $ProcessId -Force
        Start-Sleep -Milliseconds 500
    }
}

if (Test-Path "R:\") { throw "R: is already in use" }
if (Test-Path -LiteralPath $stopSignal) { throw "Sandbox session stop signal already exists" }
subst R: $projectRoot
if ($LASTEXITCODE -ne 0) { throw "Failed to create the sandbox session mapping" }

try {
    Set-Location "R:\"
    $launchParameters = @{
        WaitSeconds = $WaitSeconds
        SandboxProfileRoot = "R:\runtime\live-hmcl-profile"
    }
    if ($UseInstalledBridgeForDiagnostic) {
        $launchParameters.UseInstalledBridgeForDiagnostic = $true
    }
    $launchResult = @(
        & "R:\scripts\launch-hmcl-background.ps1" @launchParameters
    ) | Where-Object { $_.GameProcessStarted -eq $true } | Select-Object -Last 1
    if ($null -eq $launchResult) { throw "Sandbox HMCL launch returned no process evidence" }

    if ($CaptureBeforeEntry) {
        [void](& "R:\scripts\capture-minecraft-window.ps1" `
            -Name "minecraft-only-before-entry.png" `
            -MinecraftProcessId $launchResult.MinecraftProcessId `
            -NativeWindowHandle $launchResult.GameWindowHandle)
    }

    $entryParameters = @{
        WaitSeconds = $WaitSeconds
        WorldId = $WorldId
        MinecraftProcessId = $launchResult.MinecraftProcessId
        NativeWindowHandle = $launchResult.GameWindowHandle
        SkipLogInspection = $true
        NoLogMenuGraceSeconds = 90
    }
    if ($CaptureBeforeSelection) {
        $entryParameters.CaptureBeforeSelection = $true
    }
    $entryResult = & "R:\scripts\enter-hmcl-test-world.ps1" @entryParameters
    if ($entryResult.Connected -ne $true -or $entryResult.WorldId -ne $WorldId) {
        throw "Sandbox Minecraft world did not connect as $WorldId"
    }
    if ($entryResult.PhysicalMouseOrKeyboardInputUsed -or $entryResult.ScreenshotUsed) {
        throw "Sandbox Minecraft entry used a forbidden interaction method"
    }

    $readyMessage = "SANDBOX_SESSION_READY:ID={0}:MINECRAFT_PID={1}:HMCL_PID={2}:WORLD={3}:BRIDGE=true" -f @(
        $SessionId,
        $launchResult.MinecraftProcessId,
        $launchResult.HmclProcessId,
        $WorldId
    )
    [Console]::Out.WriteLine($readyMessage)
    [Console]::Out.Flush()

    if ($RunFinalDiamondAcceptance) {
        $node = (Get-Command "node.exe" -ErrorAction Stop).Source
        & $node "R:\scripts\final-smart-tchat-acceptance.mjs" `
            ("--wait-seconds=" + $FinalAcceptanceWaitSeconds)
        if ($LASTEXITCODE -ne 0) {
            throw "Final smart T-chat acceptance failed"
        }
    } else {
        $deadline = (Get-Date).AddSeconds($MaximumSessionSeconds)
        while (-not (Test-Path -LiteralPath $stopSignal) -and (Get-Date) -lt $deadline) {
            if (-not (Get-Process -Id $launchResult.MinecraftProcessId -ErrorAction SilentlyContinue)) {
                throw "Sandbox Minecraft process exited before the stop signal"
            }
            Start-Sleep -Seconds 1
        }
        if (-not (Test-Path -LiteralPath $stopSignal)) {
            throw "Sandbox Minecraft session reached its maximum duration"
        }
    }
} finally {
    if ($null -ne $launchResult) {
        Stop-VerifiedJavaProcess $launchResult.MinecraftProcessId "net.minecraft.client.main.Main"
        Stop-VerifiedJavaProcess $launchResult.HmclProcessId "org.jackhuang.hmcl.Launcher"
    }
    Set-Location $projectRoot
    subst R: /D
    [Console]::Out.WriteLine("SANDBOX_SESSION_CLOSED:ID=" + $SessionId)
    [Console]::Out.Flush()
}
