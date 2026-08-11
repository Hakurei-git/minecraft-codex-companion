[CmdletBinding()]
param(
    [ValidateRange(30, 300)]
    [int]$WaitSeconds = 120,

    [ValidateRange(0, 1)]
    [int]$EarlyExitRetry = 0,

    [string]$InstallGameRootAlias = "",

    [string]$SandboxProfileRoot = "",

    [string]$QuickPlayWorld = "",

    [switch]$UseInstalledBridgeForDiagnostic
)

$ErrorActionPreference = "Stop"

if (-not ("MinecraftLaunchWindowState" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftLaunchWindowState
{
    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool SetWindowPos(
        IntPtr hWnd,
        IntPtr hWndInsertAfter,
        int x,
        int y,
        int width,
        int height,
        uint flags
    );
    [DllImport("user32.dll")]
    public static extern bool ClipCursor(IntPtr rect);
    [DllImport("user32.dll")]
    public static extern bool ReleaseCapture();
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

    public static bool BackgroundWithoutFocus(IntPtr hWnd)
    {
        PostMessage(hWnd, 0x0008, IntPtr.Zero, IntPtr.Zero); // WM_KILLFOCUS
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
        const uint flags = 0x0001 | 0x0002 | 0x0010; // NOSIZE | NOMOVE | NOACTIVATE
        return SetWindowPos(hWnd, (IntPtr)1, 0, 0, 0, 0, flags); // HWND_BOTTOM
    }
}
'@
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $projectRoot ".runtime\hmcl-background-agent"
$sourceRoot = Join-Path $PSScriptRoot "hmcl-background-agent"
$statusPath = Join-Path $runtimeRoot (
    "launch-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".status"
)
$launchStatePath = Join-Path $runtimeRoot (
    "launch-state-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".status"
)
$configPath = Join-Path $env:LOCALAPPDATA "MinecraftCodexCompanion\launcher-config.json"

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Minecraft Codex Companion launcher configuration is missing"
}
$config = Get-Content -LiteralPath $configPath -Raw -Encoding utf8 | ConvertFrom-Json
if (-not (Test-Path -LiteralPath ([string]$config.launcherPath) -PathType Leaf)) {
    throw "Configured HMCL launcher is unavailable"
}
foreach ($requiredValue in @("minecraftRoot", "targetVersion", "playerName")) {
    if ([string]::IsNullOrWhiteSpace([string]$config.$requiredValue)) {
        throw "Minecraft Codex Companion launcher configuration is incomplete"
    }
}
if (-not (Test-Path -LiteralPath ([string]$config.minecraftRoot) -PathType Container)) {
    throw "Configured Minecraft game directory is unavailable"
}

$launchMinecraftRoot = [string]$config.minecraftRoot
$launchTargetVersion = [string]$config.targetVersion
$launchPlayerName = [string]$config.playerName
$launcherWorkingDirectory = Split-Path -Parent ([string]$config.launcherPath)
$sandboxTempDirectory = $null
if (-not [string]::IsNullOrWhiteSpace($QuickPlayWorld)) {
    if ([string]::IsNullOrWhiteSpace($SandboxProfileRoot)) {
        throw "QuickPlayWorld is restricted to an isolated SandboxProfileRoot"
    }
    if ($QuickPlayWorld.Length -gt 128 -or
        $QuickPlayWorld -in @(".", "..") -or
        $QuickPlayWorld -match '[\\/\x00-\x1f\x7f]') {
        throw "QuickPlayWorld must be one safe world-folder name"
    }
}
if ($UseInstalledBridgeForDiagnostic -and [string]::IsNullOrWhiteSpace($SandboxProfileRoot)) {
    throw "UseInstalledBridgeForDiagnostic is restricted to an isolated SandboxProfileRoot"
}
if (-not [string]::IsNullOrWhiteSpace($SandboxProfileRoot)) {
    $sandboxRoot = [IO.Path]::GetFullPath($SandboxProfileRoot).TrimEnd('\')
    $projectPrefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + "\"
    if (-not ($sandboxRoot + "\").StartsWith($projectPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "SandboxProfileRoot must remain inside the project"
    }
    $realUserHome = $sandboxRoot
    $appDataDirectory = Join-Path $sandboxRoot "AppData"
    $localAppDataDirectory = Join-Path $appDataDirectory "Local"
    $launcherWorkingDirectory = Join-Path $sandboxRoot "launcher"
    $launchMinecraftRoot = Join-Path $appDataDirectory "Roaming\.minecraft"
    # Keep the configured offline player name so the isolated clone still
    # satisfies the bridge's owner binding. Account and launcher state remain
    # isolated beneath SandboxProfileRoot.
    $sandboxTempDirectory = Join-Path $sandboxRoot "Temp"
    foreach ($requiredDirectory in @(
        $appDataDirectory,
        $localAppDataDirectory,
        $launcherWorkingDirectory,
        $launchMinecraftRoot,
        $sandboxTempDirectory
    )) {
        if ($requiredDirectory -eq $sandboxTempDirectory -and
            -not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
            [IO.Directory]::CreateDirectory($requiredDirectory) | Out-Null
        }
        if (-not (Test-Path -LiteralPath $requiredDirectory -PathType Container)) {
            throw "Sandbox HMCL profile is incomplete"
        }
    }
    $sandboxInstance = Join-Path $launchMinecraftRoot ("versions\" + $launchTargetVersion)
    if (-not (Test-Path -LiteralPath (Join-Path $sandboxInstance "CODEX-CLONE.json") -PathType Leaf)) {
        throw "Sandbox HMCL profile does not contain the configured Codex clone"
    }
    if (-not [string]::IsNullOrWhiteSpace($QuickPlayWorld)) {
        $quickPlaySave = Join-Path (Join-Path $sandboxInstance "saves") $QuickPlayWorld
        if (-not (Test-Path -LiteralPath $quickPlaySave -PathType Container) -or
            ((Get-Item -LiteralPath $quickPlaySave -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "QuickPlayWorld is unavailable in the isolated instance"
        }
    }
} else {
    # Anchor the real profile to the local launcher configuration. Minecraft roots
    # may legitimately live on another drive and cannot identify the user profile.
    $configDirectory = Split-Path -Parent $configPath
    $localAppDataDirectory = Split-Path -Parent $configDirectory
    $appDataDirectory = Split-Path -Parent $localAppDataDirectory
    $realUserHome = Split-Path -Parent $appDataDirectory
    if ([string]::IsNullOrWhiteSpace($realUserHome) -or
        -not (Split-Path -Leaf $localAppDataDirectory).Equals("Local", [StringComparison]::OrdinalIgnoreCase) -or
        -not (Split-Path -Leaf $appDataDirectory).Equals("AppData", [StringComparison]::OrdinalIgnoreCase)) {
        throw "The launcher configuration does not resolve to a user profile"
    }
}

function Get-JavaTool([Diagnostics.Process]$Process, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Process.Path)) { return $null }
    $candidate = Join-Path (Split-Path -Parent $Process.Path) ($Name + ".exe")
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    return $null
}

function Test-HmclProcess([Diagnostics.Process]$Process) {
    try {
        $jcmd = Get-JavaTool $Process "jcmd"
        if ($null -eq $jcmd) { return $false }
        $hierarchy = & $jcmd $Process.Id "VM.class_hierarchy" "org.jackhuang.hmcl.Launcher" 2>$null
        return [string]::Join("`n", @($hierarchy)) -match "org\.jackhuang\.hmcl\.Launcher"
    } catch {
        return $false
    }
}

function Test-HmclReady([Diagnostics.Process]$Process) {
    try {
        $jcmd = Get-JavaTool $Process "jcmd"
        if ($null -eq $jcmd) { return $false }
        $hierarchy = & $jcmd $Process.Id "VM.class_hierarchy" `
            "org.jackhuang.hmcl.ui.main.MainPage" 2>$null
        return [string]::Join("`n", @($hierarchy)) -match "org\.jackhuang\.hmcl\.ui\.main\.MainPage"
    } catch {
        return $false
    }
}

function Get-HmclProcess {
    foreach ($candidate in @(Get-Process -Name "java", "javaw" -ErrorAction SilentlyContinue)) {
        if (Test-HmclProcess $candidate) { return $candidate }
    }
    return $null
}

function Test-MinecraftProcess([Diagnostics.Process]$Process) {
    try {
        $jcmd = Get-JavaTool $Process "jcmd"
        if ($null -eq $jcmd) { return $false }
        foreach ($className in @(
            "cpw.mods.bootstraplauncher.BootstrapLauncher",
            "net.minecraft.client.main.Main"
        )) {
            $hierarchy = & $jcmd $Process.Id "VM.class_hierarchy" $className 2>$null
            if ([string]::Join("`n", @($hierarchy)) -match [regex]::Escape($className)) {
                return $true
            }
        }
    } catch {
        return $false
    }
    return $false
}

function Test-MinecraftFallbackProcess(
    [object]$Process,
    [int[]]$KnownProcessIds = @(),
    [int]$HmclProcessId = 0
) {
    return $Process.Id -ne $HmclProcessId -and
        $Process.ProcessName -eq "javaw" -and
        $Process.Id -notin $KnownProcessIds -and
        $Process.Responding -and
        $Process.MainWindowHandle -ne [IntPtr]::Zero -and
        $Process.MainWindowTitle -match '^Minecraft.*Forge 1\.20\.1'
}

function Get-MinecraftProcess(
    [int[]]$KnownProcessIds = @(),
    [int]$HmclProcessId = 0
) {
    foreach ($candidate in @(Get-Process -Name "java", "javaw" -ErrorAction SilentlyContinue)) {
        if (Test-MinecraftProcess $candidate) { return $candidate }
        if (Test-MinecraftFallbackProcess `
            -Process $candidate `
            -KnownProcessIds $KnownProcessIds `
            -HmclProcessId $HmclProcessId) {
            # Minecraft often uses a compact JRE without jcmd. HMCL launches
            # it as a new javaw process. Require the stable Forge window here;
            # HMCL also starts short-lived javaw probes while selecting a JVM.
            return $candidate
        }
    }
    return $null
}

function Get-ReplacementProcessDeadline(
    [DateTime]$WindowDeadline,
    [DateTime]$Now,
    [int]$WaitSeconds
) {
    # A launcher/bootstrap JVM can exit several seconds before the real game
    # JVM becomes enumerable. Bound the hand-off grace period by both the main
    # readiness deadline and a meaningful fraction of the launch timeout.
    $graceSeconds = [Math]::Min(30, [Math]::Max(10, $WaitSeconds))
    $graceDeadline = $Now.AddSeconds($graceSeconds)
    if ($graceDeadline -lt $WindowDeadline) { return $graceDeadline }
    return $WindowDeadline
}

$hmcl = Get-HmclProcess
if ($null -ne $hmcl) {
    $jcmd = Get-JavaTool $hmcl "jcmd"
    $homeProperty = & $jcmd $hmcl.Id "VM.system_properties" 2>$null |
        Where-Object { $_ -like "user.home=*" } |
        Select-Object -First 1
    $reportedHome = ([string]$homeProperty -replace '^user\.home=', '').Replace("\", "")
    $expectedHome = $realUserHome.Replace("\", "")
    if (-not $reportedHome.Equals($expectedHome, [StringComparison]::OrdinalIgnoreCase)) {
        # This is an HMCL instance created under the isolated profile. It
        # cannot reach the configured launcher state and is safe to replace.
        Stop-Process -Id $hmcl.Id -Force
        Start-Sleep -Milliseconds 700
        $hmcl = $null
    }
}

if ($null -eq $hmcl) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = [string]$config.launcherPath
    $startInfo.Arguments = [string]$config.launcherArguments
    $startInfo.WorkingDirectory = $launcherWorkingDirectory
    $startInfo.UseShellExecute = $true
    $startInfo.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $userHomeOption = '-Duser.home="' + $realUserHome + '"'
    $launchEnvironment = @{
        USERPROFILE = $realUserHome
        HOME = $realUserHome
        HOMEDRIVE = Split-Path -Qualifier $realUserHome
        HOMEPATH = $realUserHome.Substring(2)
        APPDATA = Join-Path $appDataDirectory "Roaming"
        LOCALAPPDATA = $localAppDataDirectory
        HMCL_JAVA_OPTS = (([Environment]::GetEnvironmentVariable("HMCL_JAVA_OPTS", "Process")) +
            " " + $userHomeOption).Trim()
    }
    if ($null -ne $sandboxTempDirectory) {
        $launchEnvironment.TEMP = $sandboxTempDirectory
        $launchEnvironment.TMP = $sandboxTempDirectory
    }
    $savedEnvironment = @{}
    try {
        foreach ($entry in $launchEnvironment.GetEnumerator()) {
            $savedEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
            [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
        }
        [void][Diagnostics.Process]::Start($startInfo)
    } finally {
        foreach ($entry in $savedEnvironment.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
        }
    }

    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        Start-Sleep -Milliseconds 600
        $hmcl = Get-HmclProcess
    } while ($null -eq $hmcl -and (Get-Date) -lt $deadline)
    if ($null -eq $hmcl) { throw "HMCL did not initialize before timeout" }
}

$java = Get-JavaTool $hmcl "java"
$javac = Get-JavaTool $hmcl "javac"
$jar = Get-JavaTool $hmcl "jar"
if ($null -eq $java -or $null -eq $javac -or $null -eq $jar) {
    throw "The HMCL Java runtime does not provide the attach build tools"
}

[IO.Directory]::CreateDirectory($runtimeRoot) | Out-Null
$classesRoot = Join-Path $runtimeRoot "classes"
[IO.Directory]::CreateDirectory($classesRoot) | Out-Null
$agentSourcePath = Join-Path $sourceRoot "HmclBackgroundLaunchAgent.java"
$manifestPath = Join-Path $sourceRoot "MANIFEST.MF"
$sourceFingerprint = (Get-FileHash -LiteralPath $agentSourcePath -Algorithm SHA256).Hash.Substring(0, 16)
$agentJar = Join-Path $runtimeRoot ("hmcl-background-agent-" + $sourceFingerprint + ".jar")

& $javac --add-modules jdk.attach -encoding UTF-8 -d $classesRoot `
    $agentSourcePath `
    (Join-Path $sourceRoot "AttachHmclAgent.java")
if ($LASTEXITCODE -ne 0) { throw "Failed to compile the HMCL background agent" }

if (-not (Test-Path -LiteralPath $agentJar -PathType Leaf)) {
    & $jar --create --file $agentJar --manifest $manifestPath `
        -C $classesRoot "HmclBackgroundLaunchAgent.class"
    if ($LASTEXITCODE -ne 0) { throw "Failed to package the HMCL background agent" }
}

if (-not (Test-HmclReady $hmcl)) {
    # A newly started HMCL can expose its JVM before MainPage has finished
    # loading. Give the normal startup path a chance before assuming that a
    # first-run confirmation dialog is blocking initialization.
    $normalStartupDeadline = (Get-Date).AddSeconds([Math]::Min(15, $WaitSeconds))
    while (-not (Test-HmclReady $hmcl) -and (Get-Date) -lt $normalStartupDeadline) {
        Start-Sleep -Milliseconds 600
    }
}

if (-not (Test-HmclReady $hmcl)) {
    $ownerStatusPath = Join-Path $runtimeRoot "startup-dialog.status"
    $previousOwnerStatusWrite = (Get-Item -LiteralPath $ownerStatusPath -ErrorAction SilentlyContinue).LastWriteTimeUtc
    & $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
        ([string]$hmcl.Id) $agentJar ("confirm-startup|" + $ownerStatusPath)
    if ($LASTEXITCODE -ne 0) { throw "Failed to attach the HMCL startup-dialog agent" }

    $ownerDeadline = (Get-Date).AddSeconds([Math]::Min(20, $WaitSeconds))
    do {
        Start-Sleep -Milliseconds 250
        $ownerStatusFile = Get-Item -LiteralPath $ownerStatusPath -ErrorAction SilentlyContinue
    } while (($null -eq $ownerStatusFile -or
        $ownerStatusFile.LastWriteTimeUtc -eq $previousOwnerStatusWrite) -and
        (Get-Date) -lt $ownerDeadline)
    if ($null -eq $ownerStatusFile -or
        $ownerStatusFile.LastWriteTimeUtc -eq $previousOwnerStatusWrite) {
        throw "HMCL did not acknowledge the startup-dialog confirmation"
    }
    $ownerStatus = Get-Content -LiteralPath $ownerStatusPath -Raw -Encoding ascii
    if ($ownerStatus -ne "STARTUP_DIALOG_CONFIRMED" -and
        $ownerStatus -notlike "ERROR:STARTUP_DIALOG_NOT_FOUND:*") {
        throw "HMCL startup-dialog confirmation failed: $ownerStatus"
    }
}

$readyDeadline = (Get-Date).AddSeconds($WaitSeconds)
while (-not (Test-HmclReady $hmcl) -and (Get-Date) -lt $readyDeadline) {
    Start-Sleep -Milliseconds 600
}
if (-not (Test-HmclReady $hmcl)) {
    throw "HMCL did not finish loading the selected game repository before timeout"
}

if (-not [string]::IsNullOrWhiteSpace($SandboxProfileRoot)) {
    $javaScanStatusPath = Join-Path $runtimeRoot (
        "java-scan-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".status"
    )
    & $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
        ([string]$hmcl.Id) $agentJar ("unblock-java-scan|" + $javaScanStatusPath)
    if ($LASTEXITCODE -ne 0) { throw "Failed to attach the sandbox Java-scan initializer" }
    $javaScanDeadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 200
        $javaScanStatusFile = Get-Item -LiteralPath $javaScanStatusPath -ErrorAction SilentlyContinue
    } while ($null -eq $javaScanStatusFile -and (Get-Date) -lt $javaScanDeadline)
    if ($null -eq $javaScanStatusFile) {
        throw "HMCL did not acknowledge the sandbox Java-scan initialization"
    }
    $javaScanStatus = Get-Content -LiteralPath $javaScanStatusPath -Raw -Encoding ascii
    if ($javaScanStatus -notin @("JAVA_SCAN_UNBLOCKED", "JAVA_SCAN_READY")) {
        throw "Sandbox Java-scan initialization failed: $javaScanStatus"
    }
}

function ConvertTo-UrlSafeBase64([string]$Value) {
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

$installGameDirectory = $launchMinecraftRoot
if (-not [string]::IsNullOrWhiteSpace($InstallGameRootAlias)) {
    if ($InstallGameRootAlias -notmatch '^(?<drive>[A-Za-z]):\\?$') {
        throw "InstallGameRootAlias must be a substituted drive root"
    }
    $aliasDrive = $Matches.drive.ToUpperInvariant() + ":"
    $mapping = @(& subst 2>$null | ForEach-Object {
        if ([string]$_ -match '^\s*(?<drive>[A-Za-z]):\\:\s*=>\s*(?<target>.+?)\s*$') {
            [PSCustomObject]@{ Drive = $Matches.drive.ToUpperInvariant() + ":"; Target = $Matches.target }
        }
    }) | Where-Object { $_.Drive -eq $aliasDrive } | Select-Object -First 1
    if ($null -eq $mapping) {
        throw "InstallGameRootAlias is not an active substituted drive"
    }
    $mappedTarget = [IO.Path]::GetFullPath([string]$mapping.Target).TrimEnd('\')
    $configuredTarget = [IO.Path]::GetFullPath($launchMinecraftRoot).TrimEnd('\')
    if (-not $mappedTarget.Equals($configuredTarget, [StringComparison]::OrdinalIgnoreCase)) {
        throw "InstallGameRootAlias does not map to the configured Minecraft root"
    }
    $installGameDirectory = $aliasDrive + "\"
}

$bridgeJar = Get-ChildItem -LiteralPath (Join-Path $projectRoot "mods\forge-1.20.1\build\libs") `
        -Filter "minecraft_codex_bridge-forge-1.20.1-*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "-(sources|javadoc)\.jar$" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $bridgeJar) {
    throw "The rebuilt Forge bridge JAR is unavailable"
}
$bridgeHash = (Get-FileHash -LiteralPath $bridgeJar.FullName -Algorithm SHA256).Hash
$configuredInstance = Join-Path $launchMinecraftRoot ("versions\" + $launchTargetVersion)
$configuredMods = Join-Path $configuredInstance "mods"
$installedBridge = Get-ChildItem -LiteralPath $configuredMods `
        -Filter "minecraft_codex_bridge-forge-1.20.1-*.jar" -File -ErrorAction SilentlyContinue |
    Select-Object -First 1
$installedBridgeHash = if ($null -eq $installedBridge) {
    ""
} else {
    (Get-FileHash -LiteralPath $installedBridge.FullName -Algorithm SHA256).Hash
}
$bridgeUpdated = $false
if ($installedBridgeHash -ne $bridgeHash -and -not $UseInstalledBridgeForDiagnostic) {
    $installStatusPath = Join-Path $runtimeRoot (
        "bridge-install-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".status"
    )
    $installArguments = [string]::Join("|", @(
        "install-bridge",
        $installStatusPath,
        (ConvertTo-UrlSafeBase64 $bridgeJar.FullName),
        (ConvertTo-UrlSafeBase64 $installGameDirectory),
        (ConvertTo-UrlSafeBase64 $launchTargetVersion),
        $bridgeHash
    ))
    & $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
        ([string]$hmcl.Id) $agentJar $installArguments
    if ($LASTEXITCODE -ne 0) { throw "Failed to attach the restricted bridge installer" }

    $installDeadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 250
        $installStatusFile = Get-Item -LiteralPath $installStatusPath -ErrorAction SilentlyContinue
    } while ($null -eq $installStatusFile -and (Get-Date) -lt $installDeadline)
    if ($null -eq $installStatusFile) {
        throw "HMCL did not acknowledge the restricted bridge installation"
    }
    $installStatus = Get-Content -LiteralPath $installStatusPath -Raw -Encoding ascii
    if ($installStatus -notlike "BRIDGE_INSTALLED:*:SHA256=$bridgeHash") {
        throw "Restricted bridge installation failed: $installStatus"
    }
    $installedBridge = Get-ChildItem -LiteralPath $configuredMods `
            -Filter $bridgeJar.Name -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $installedBridge -or
        (Get-FileHash -LiteralPath $installedBridge.FullName -Algorithm SHA256).Hash -ne $bridgeHash) {
        throw "Installed bridge did not pass the caller-side SHA-256 verification"
    }
    $bridgeUpdated = $true
}
$effectiveBridgeHash = if ($UseInstalledBridgeForDiagnostic) {
    $installedBridgeHash
} else {
    $bridgeHash
}
if ([string]::IsNullOrWhiteSpace($effectiveBridgeHash)) {
    throw "The isolated diagnostic profile has no installed Forge bridge"
}

$gameDirectoryArgument = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($launchMinecraftRoot)
).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$targetVersionArgument = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($launchTargetVersion)
).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$playerNameArgument = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($launchPlayerName)
).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$launchAgentRequest = "launch-configured|" + $statusPath + "|" + $gameDirectoryArgument + "|" +
    $targetVersionArgument + "|" + $playerNameArgument
if (-not [string]::IsNullOrWhiteSpace($QuickPlayWorld)) {
    $quickPlayWorldArgument = ConvertTo-UrlSafeBase64 $QuickPlayWorld
    $launchAgentRequest += "|" + $quickPlayWorldArgument
}
$javaProcessIdsBeforeLaunch = @(
    Get-Process -Name "java", "javaw" -ErrorAction SilentlyContinue |
        ForEach-Object { $_.Id }
)
$launchDeadline = (Get-Date).AddSeconds($WaitSeconds)
$status = $null
do {
    $previousStatusWrite = (Get-Item -LiteralPath $statusPath -ErrorAction SilentlyContinue).LastWriteTimeUtc
    & $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
        ([string]$hmcl.Id) $agentJar $launchAgentRequest
    if ($LASTEXITCODE -ne 0) { throw "Failed to attach the HMCL background launch agent" }

    $acknowledgementDeadline = (Get-Date).AddSeconds(20)
    if ($acknowledgementDeadline -gt $launchDeadline) {
        $acknowledgementDeadline = $launchDeadline
    }
    do {
        Start-Sleep -Milliseconds 250
        $statusFile = Get-Item -LiteralPath $statusPath -ErrorAction SilentlyContinue
    } while (($null -eq $statusFile -or $statusFile.LastWriteTimeUtc -eq $previousStatusWrite) -and
        (Get-Date) -lt $acknowledgementDeadline)
    if ($null -eq $statusFile -or $statusFile.LastWriteTimeUtc -eq $previousStatusWrite) {
        throw "HMCL did not acknowledge the background launch request"
    }
    $status = Get-Content -LiteralPath $statusPath -Raw -Encoding ascii
    if ($status -eq "ERROR:CONFIGURED_REPOSITORY_NOT_LOADED") {
        Start-Sleep -Milliseconds 750
    }
} while ($status -eq "ERROR:CONFIGURED_REPOSITORY_NOT_LOADED" -and
    (Get-Date) -lt $launchDeadline)
if ($status -ne "LAUNCH_REQUESTED") { throw "HMCL background launch failed: $status" }

$gameProcess = Get-MinecraftProcess `
    -KnownProcessIds $javaProcessIdsBeforeLaunch `
    -HmclProcessId $hmcl.Id
$gameDeadline = (Get-Date).AddSeconds($WaitSeconds)
$nextLaunchStateCheck = Get-Date
while ($null -eq $gameProcess -and (Get-Date) -lt $gameDeadline) {
    Start-Sleep -Milliseconds 750
    $gameProcess = Get-MinecraftProcess `
        -KnownProcessIds $javaProcessIdsBeforeLaunch `
        -HmclProcessId $hmcl.Id
    if ($null -ne $gameProcess -or (Get-Date) -lt $nextLaunchStateCheck) {
        continue
    }

    $previousLaunchStateWrite = (
        Get-Item -LiteralPath $launchStatePath -ErrorAction SilentlyContinue
    ).LastWriteTimeUtc
    & $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
        ([string]$hmcl.Id) $agentJar ("launch-state|" + $launchStatePath)
    if ($LASTEXITCODE -ne 0) { throw "Failed to inspect the HMCL launch task" }
    $stateDeadline = (Get-Date).AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 200
        $launchStateFile = Get-Item -LiteralPath $launchStatePath -ErrorAction SilentlyContinue
    } while (($null -eq $launchStateFile -or
        $launchStateFile.LastWriteTimeUtc -eq $previousLaunchStateWrite) -and
        (Get-Date) -lt $stateDeadline)
    if ($null -ne $launchStateFile -and
        $launchStateFile.LastWriteTimeUtc -ne $previousLaunchStateWrite) {
        $launchState = Get-Content -LiteralPath $launchStatePath -Raw -Encoding ascii
        if ($launchState.StartsWith("ERROR:")) {
            throw "HMCL launch task failed: $launchState"
        }
    }
    $nextLaunchStateCheck = (Get-Date).AddSeconds(2)
}
if ($null -eq $gameProcess) {
    throw "HMCL accepted the launch request but the Minecraft process did not start before timeout"
}

# A newly-created javaw process is not sufficient proof that Forge reached a
# usable window. HMCL can report a transient launch and then show its crash
# page while the child exits. Wait for the same privacy-preserving window
# title used by the world-entry automation and retry one clean launch only.
$windowReady = $false
$readySince = $null
$earlyExit = $false
$windowDeadline = (Get-Date).AddSeconds($WaitSeconds)
while (-not $windowReady -and (Get-Date) -lt $windowDeadline) {
    $current = Get-Process -Id $gameProcess.Id -ErrorAction SilentlyContinue
    if ($null -eq $current) {
        $replacementDeadline = Get-ReplacementProcessDeadline `
            -WindowDeadline $windowDeadline `
            -Now (Get-Date) `
            -WaitSeconds $WaitSeconds
        do {
            Start-Sleep -Milliseconds 500
            $replacement = Get-MinecraftProcess `
                -KnownProcessIds $javaProcessIdsBeforeLaunch `
                -HmclProcessId $hmcl.Id
        } while ($null -eq $replacement -and (Get-Date) -lt $replacementDeadline)
        if ($null -eq $replacement) {
            $earlyExit = $true
            break
        }
        $gameProcess = $replacement
        $readySince = $null
        continue
    }

    $current.Refresh()
    $gameProcess = $current
    if ($current.Responding -and $current.MainWindowTitle -match '^Minecraft.*Forge 1\.20\.1') {
        if ($null -eq $readySince) { $readySince = Get-Date }
        if ((Get-Date) -ge $readySince.AddSeconds(5)) { $windowReady = $true }
    } else {
        $readySince = $null
    }
    if (-not $windowReady) { Start-Sleep -Milliseconds 500 }
}

if ($earlyExit) {
    if ($EarlyExitRetry -ge 1) {
        throw "Minecraft exited before its Forge window became ready after the single clean retry"
    }
    $closeReadyDeadline = (Get-Date).AddSeconds(15)
    do {
        $hmcl.Refresh()
        if ($hmcl.HasExited -or $hmcl.MainWindowHandle -ne [IntPtr]::Zero) { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $closeReadyDeadline)
    if ($hmcl.HasExited -or $hmcl.MainWindowHandle -eq [IntPtr]::Zero -or -not $hmcl.CloseMainWindow()) {
        throw "Minecraft exited early and the HMCL result window could not be closed normally"
    }
    $hmclCloseDeadline = (Get-Date).AddSeconds(15)
    while (-not $hmcl.HasExited -and (Get-Date) -lt $hmclCloseDeadline) {
        Start-Sleep -Milliseconds 250
        $hmcl.Refresh()
    }
    if (-not $hmcl.HasExited) {
        throw "Minecraft exited early and HMCL did not close normally before retry"
    }
    $retryParameters = @{
        WaitSeconds = $WaitSeconds
        EarlyExitRetry = 1
    }
    if (-not [string]::IsNullOrWhiteSpace($InstallGameRootAlias)) {
        $retryParameters.InstallGameRootAlias = $InstallGameRootAlias
    }
    if (-not [string]::IsNullOrWhiteSpace($SandboxProfileRoot)) {
        $retryParameters.SandboxProfileRoot = $SandboxProfileRoot
    }
    & $PSCommandPath @retryParameters
    return
}
if (-not $windowReady) {
    throw "Minecraft process started but its Forge window did not become ready before timeout"
}

# A newly-created GLFW window can become foreground even though HMCL was
# launched hidden. Put it at the bottom without activation and release cursor
# capture. Keeping the window non-minimized preserves its HWND for the entry
# helper; that helper minimizes it after the bridge connects.
$gameProcess.Refresh()
$gameWindowHandle = $gameProcess.MainWindowHandle
if ($gameWindowHandle -eq [IntPtr]::Zero) {
    throw "Minecraft Forge window handle disappeared before cursor release"
}
if (-not [MinecraftLaunchWindowState]::BackgroundWithoutFocus($gameWindowHandle)) {
    throw "Minecraft Forge window could not be backgrounded without focus"
}
$activeWindowStatusPath = Join-Path $runtimeRoot "active-minecraft-window.status"
[IO.File]::WriteAllText(
    $activeWindowStatusPath,
    ("PID={0};HWND={1}" -f $gameProcess.Id, $gameWindowHandle.ToInt64()),
    [Text.Encoding]::ASCII
)

$minimizeStatusPath = Join-Path $runtimeRoot (
    "minimize-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + ".status"
)
& $java --add-modules jdk.attach -cp $classesRoot AttachHmclAgent `
    ([string]$hmcl.Id) $agentJar ("minimize|" + $minimizeStatusPath)

[PSCustomObject]@{
    LaunchRequested = $true
    TargetVersion = $launchTargetVersion
    HmclProcessId = $hmcl.Id
    MinecraftProcessId = $gameProcess.Id
    GameProcessStarted = $true
    GameWindowReady = $true
    GameWindowHandle = $gameWindowHandle.ToInt64()
    ActiveWindowStatusWritten = $true
    GameWindowMinimized = $false
    GameWindowBackgrounded = $true
    CursorCaptureReleased = $true
    EarlyExitRetryUsed = $EarlyExitRetry -gt 0
    ConfiguredSelectionRestored = $true
    BridgeUpdated = $bridgeUpdated
    BridgeSha256 = $effectiveBridgeHash
    ExpectedBridgeSha256 = $bridgeHash
    InstalledBridgeDiagnosticUsed = [bool]$UseInstalledBridgeForDiagnostic
    RealProfileDerivedFromConfiguration = $true
    FullJavaCommandLineRead = $false
    MouseOrKeyboardInputUsed = $false
    ScreenshotUsed = $false
    QuickPlayWorld = $QuickPlayWorld
    SandboxShortTempUsed = $null -ne $sandboxTempDirectory
}
