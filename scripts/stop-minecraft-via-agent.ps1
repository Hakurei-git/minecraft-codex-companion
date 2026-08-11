[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$MinecraftProcessId,

    [ValidateRange(10, 120)]
    [int]$WaitSeconds = 90
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $PSScriptRoot "minecraft-graceful-stop-agent"
$runtimeRoot = Join-Path $projectRoot ".runtime\minecraft-graceful-stop-agent"
$classesRoot = Join-Path $runtimeRoot "classes"
$invocationId = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$agentJar = Join-Path $runtimeRoot ("minecraft-graceful-stop-agent-" + $invocationId + ".jar")
$statusPath = Join-Path $runtimeRoot (
    "minecraft-graceful-stop-" + $invocationId + ".status"
)

$target = Get-Process -Id $MinecraftProcessId -ErrorAction Stop
if ($target.ProcessName -notin @("java", "javaw")) {
    throw "Target process is not a Java runtime"
}

$java = (Get-Command java.exe -ErrorAction Stop).Source
$javac = (Get-Command javac.exe -ErrorAction Stop).Source
$jar = (Get-Command jar.exe -ErrorAction Stop).Source
$jcmd = (Get-Command jcmd.exe -ErrorAction Stop).Source
$hierarchy = (& $jcmd $MinecraftProcessId "VM.class_hierarchy" "net.minecraft.client.Minecraft" 2>$null | Out-String)
if ($LASTEXITCODE -ne 0 -or $hierarchy -notmatch "net\.minecraft\.client\.Minecraft") {
    throw "Target Java process is not a loaded Minecraft client"
}

[IO.Directory]::CreateDirectory($classesRoot) | Out-Null
& $javac --release 17 --add-modules jdk.attach -encoding UTF-8 -d $classesRoot `
    (Join-Path $sourceRoot "MinecraftGracefulStopAgentV3.java") `
    (Join-Path $PSScriptRoot "hmcl-background-agent\AttachHmclAgent.java")
if ($LASTEXITCODE -ne 0) { throw "Failed to compile the Minecraft graceful-stop agent" }

& $jar --create --file $agentJar --manifest (Join-Path $sourceRoot "MANIFEST.MF") `
    -C $classesRoot "MinecraftGracefulStopAgentV3.class"
if ($LASTEXITCODE -ne 0) { throw "Failed to package the Minecraft graceful-stop agent" }

& $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
    ([string]$MinecraftProcessId) $agentJar $statusPath
if ($LASTEXITCODE -ne 0) { throw "Failed to attach the Minecraft graceful-stop agent" }

$statusDeadline = (Get-Date).AddSeconds(10)
do {
    Start-Sleep -Milliseconds 100
    $statusFile = Get-Item -LiteralPath $statusPath -ErrorAction SilentlyContinue
} while ($null -eq $statusFile -and (Get-Date) -lt $statusDeadline)
if ($null -eq $statusFile) { throw "Minecraft graceful-stop agent returned no status" }
$status = (Get-Content -LiteralPath $statusPath -Raw -Encoding ASCII).Trim()
if ($status.StartsWith("ERROR:")) {
    throw "Minecraft graceful-stop agent failed: $status"
}
if ($status -notin @("STOP_SCHEDULED", "STOP_REQUESTED")) {
    throw "Minecraft graceful-stop agent returned an unexpected status"
}

$exitDeadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Process -Id $MinecraftProcessId -ErrorAction SilentlyContinue) -and
    (Get-Date) -lt $exitDeadline) {
    Start-Sleep -Milliseconds 250
}
if (Get-Process -Id $MinecraftProcessId -ErrorAction SilentlyContinue) {
    throw "Minecraft did not close after the graceful client stop request"
}

[PSCustomObject]@{
    SavedAndClosed = $true
    MinecraftProcessId = $MinecraftProcessId
    AgentFallbackUsed = $true
    VanillaClientStopRequested = $true
    ForcedTerminationUsed = $false
    MouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
