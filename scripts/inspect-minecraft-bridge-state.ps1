[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$MinecraftProcessId
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\minecraft-bridge-diagnostic-agent"
$sourceRoot = Join-Path $PSScriptRoot "minecraft-bridge-diagnostic-agent"
$attachSource = Join-Path $PSScriptRoot "hmcl-background-agent\AttachHmclAgent.java"
$process = Get-Process -Id $MinecraftProcessId -ErrorAction Stop
if ($process.ProcessName -notin @("java", "javaw")) {
    throw "The requested process is not a Java runtime"
}

$jcmd = (Get-Command "jcmd.exe" -ErrorAction Stop).Source
$identity = (& $jcmd $MinecraftProcessId `
    "VM.class_hierarchy" "net.minecraft.client.main.Main" 2>$null | Out-String)
if ($identity -notmatch "net\.minecraft\.client\.main\.Main") {
    throw "The requested Java process is not Minecraft"
}

[IO.Directory]::CreateDirectory($runtimeRoot) | Out-Null
$classesRoot = Join-Path $runtimeRoot "classes"
[IO.Directory]::CreateDirectory($classesRoot) | Out-Null
$java = (Get-Command "java.exe" -ErrorAction Stop).Source
$javac = (Get-Command "javac.exe" -ErrorAction Stop).Source
$jar = (Get-Command "jar.exe" -ErrorAction Stop).Source
$agentSource = Join-Path $sourceRoot "MinecraftBridgeDiagnosticAgentV2.java"
$sourceHash = (Get-FileHash -LiteralPath $agentSource -Algorithm SHA256).Hash.Substring(0, 16)
$agentJar = Join-Path $runtimeRoot ("minecraft-bridge-diagnostic-agent-v2-bundle-java17-" + $sourceHash + ".jar")

& $javac --release 17 --add-modules jdk.attach -encoding UTF-8 -d $classesRoot $agentSource $attachSource
if ($LASTEXITCODE -ne 0) { throw "Failed to compile the Minecraft bridge diagnostic agent" }
if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    & $jar --create --file $agentJar --manifest (Join-Path $sourceRoot "MANIFEST.MF") `
        -C $classesRoot "MinecraftBridgeDiagnosticAgentV2.class" `
        -C $classesRoot 'MinecraftBridgeDiagnosticAgentV2$ProbeResult.class'
    if ($LASTEXITCODE -ne 0) { throw "Failed to package the Minecraft bridge diagnostic agent" }
}

$statusPath = Join-Path $runtimeRoot (
    "minecraft-bridge-state-" + $MinecraftProcessId + "-" +
    [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".status"
)
& $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
    ([string]$MinecraftProcessId) $agentJar $statusPath
if ($LASTEXITCODE -ne 0) { throw "Failed to attach the Minecraft bridge diagnostic agent" }

$deadline = (Get-Date).AddSeconds(8)
while (-not (Test-Path -LiteralPath $statusPath -PathType Leaf) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 100
}
if (-not (Test-Path -LiteralPath $statusPath -PathType Leaf)) {
    throw "Minecraft did not return a bridge diagnostic state"
}

$allowed = @(
    "format", "eventsClassLoaded", "clientPresent", "configReady", "autoReconnect",
    "sessionActive", "connecting", "socketPresent", "announcedSocketPresent",
    "ticksPositive", "connectionAttemptsPositive", "lastConnectionFailureCategory", "errorType",
    "tcpConnectSucceeded", "webSocketUpgradeSucceeded", "probeFailureCategory"
)
$state = @{}
foreach ($line in Get-Content -LiteralPath $statusPath -Encoding ascii) {
    $record = [regex]::Match(
        $line,
        '^(?<key>[A-Za-z][A-Za-z0-9]+)=(?<value>[-A-Za-z0-9_$]+)$',
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if (-not $record.Success) {
        throw "Minecraft returned an invalid bridge diagnostic record"
    }
    $key = $record.Groups["key"].Value
    if ($key -notin $allowed) { throw "Minecraft returned an unknown bridge diagnostic field" }
    $state[$key] = $record.Groups["value"].Value
}
foreach ($required in $allowed) {
    if (-not $state.ContainsKey($required)) { throw "Minecraft bridge diagnostic state is incomplete" }
}

[PSCustomObject]@{
    MinecraftIdentityVerified = $true
    EventsClassLoaded = $state.eventsClassLoaded -eq "true"
    ClientPresent = $state.clientPresent -eq "true"
    ConfigReady = $state.configReady -eq "true"
    AutoReconnect = $state.autoReconnect -eq "true"
    SessionActive = $state.sessionActive -eq "true"
    Connecting = $state.connecting -eq "true"
    SocketPresent = $state.socketPresent -eq "true"
    AnnouncedSocketPresent = $state.announcedSocketPresent -eq "true"
    TicksPositive = $state.ticksPositive -eq "true"
    ConnectionAttemptsPositive = $state.connectionAttemptsPositive -eq "true"
    LastConnectionFailureCategory = $state.lastConnectionFailureCategory
    TcpConnectSucceeded = $state.tcpConnectSucceeded -eq "true"
    WebSocketUpgradeSucceeded = $state.webSocketUpgradeSucceeded -eq "true"
    ProbeFailureCategory = $state.probeFailureCategory
    ErrorType = $state.errorType
    SensitiveValuesRead = $false
    RawMessagesRead = $false
}
