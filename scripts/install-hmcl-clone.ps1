[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$MinecraftRoot = (Join-Path $env:APPDATA ".minecraft"),
    [string]$SourceVersion = "",
    [string]$TargetVersion = "",
    [ValidateSet("auto", "forge", "neoforge")]
    [string]$Loader = "auto",
    [string]$BridgeJar = "",
    [string]$BaritoneJar = "",
    [switch]$SkipBaritone,
    [string]$StateDirectory = (Join-Path $env:LOCALAPPDATA "MinecraftCodexCompanion"),
    [string]$ServerUrl = "ws://127.0.0.1:8765/bridge",
    [string]$CompanionId = "codex-forge",
    [string]$CompanionName = "Codex",
    [string]$OwnerName = "",
    [string]$LauncherPath = $env:MC_LAUNCHER_PATH
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $SourceVersion) {
    throw "SourceVersion is required. Select an instance on this computer."
}
if (-not $OwnerName -or $OwnerName -notmatch '^[A-Za-z0-9_]{1,64}$') {
    throw "OwnerName is required and must be a valid Minecraft player name."
}
if (-not $LauncherPath) {
    throw "LauncherPath is required. Select a launcher on this computer."
}

$versionsRoot = [System.IO.Path]::GetFullPath((Join-Path $MinecraftRoot "versions"))
$versionsPrefix = $versionsRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$sourcePath = [System.IO.Path]::GetFullPath((Join-Path $versionsRoot $SourceVersion))
if (-not $TargetVersion) {
    $TargetVersion = "$SourceVersion-Codex"
}
$targetPath = [System.IO.Path]::GetFullPath((Join-Path $versionsRoot $TargetVersion))
if (-not $sourcePath.StartsWith($versionsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Source instance escapes the Minecraft versions directory: $sourcePath"
}
if (-not $targetPath.StartsWith($versionsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Target instance escapes the Minecraft versions directory: $targetPath"
}
if ($sourcePath.Equals($targetPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Source and target instances must be different."
}
$sourceJson = Join-Path $sourcePath "$SourceVersion.json"
$sourceJar = Join-Path $sourcePath "$SourceVersion.jar"

function Write-Utf8NoBom([string]$Path, [string]$Content) {
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Copy-DirectoryContents(
    [string]$Source,
    [string]$Destination,
    [string[]]$ExcludeNames = @(),
    [string[]]$ExcludePatterns = @()
) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        return
    }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | Where-Object {
        $name = $_.Name
        $excluded = $ExcludeNames -contains $name
        foreach ($pattern in $ExcludePatterns) {
            if ($name -like $pattern) {
                $excluded = $true
                break
            }
        }
        -not $excluded
    } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Resolve-BridgeJar([string]$RequestedPath, [string]$ResolvedLoader) {
    if ($RequestedPath) {
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }
    $projectName = if ($ResolvedLoader -eq "neoforge") { "neoforge-1.21.1" } else { "forge-1.20.1" }
    $jarPattern = if ($ResolvedLoader -eq "neoforge") { "minecraft_codex_bridge-neoforge-1.21.1-*.jar" } else { "minecraft_codex_bridge-forge-1.20.1-*.jar" }
    $libs = Join-Path $projectRoot "mods\$projectName\build\libs"
    $candidate = Get-ChildItem -LiteralPath $libs -Filter $jarPattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "-(sources|javadoc)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $candidate) {
        throw "$ResolvedLoader bridge JAR not found. Run npm run build:$ResolvedLoader or pass -BridgeJar."
    }
    return $candidate.FullName
}

function Resolve-BaritoneJar([string]$RequestedPath, [string]$ResolvedLoader) {
    if ($SkipBaritone) {
        return $null
    }
    if ($RequestedPath) {
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }
    $vendor = Join-Path $projectRoot "vendor\baritone"
    $pattern = if ($ResolvedLoader -eq "neoforge") { "*neoforge*1.21.1*.jar" } else { "*forge*1.20.1*.jar" }
    $candidate = Get-ChildItem -LiteralPath $vendor -Filter $pattern -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $candidate -and $ResolvedLoader -eq "forge") {
        throw "A Forge 1.20.1 Baritone JAR is required. Put it in vendor\baritone or pass -BaritoneJar."
    }
    if ($candidate) {
        return $candidate.FullName
    }
    return $null
}

function Get-OrCreateBridgeToken([string]$Directory) {
    $tokenPath = Join-Path $Directory "bridge-token.txt"
    if (Test-Path -LiteralPath $tokenPath -PathType Leaf) {
        $existing = (Get-Content -Raw -Encoding UTF8 -LiteralPath $tokenPath).Trim()
        if ($existing.Length -ge 16) {
            return @{ Token = $existing; Path = $tokenPath }
        }
    }

    $bytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    $token = -join ($bytes | ForEach-Object { $_.ToString("x2") })
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    Write-Utf8NoBom -Path $tokenPath -Content "$token`n"
    return @{ Token = $token; Path = $tokenPath }
}

if (-not (Test-Path -LiteralPath $sourcePath -PathType Container)) {
    throw "Source HMCL instance does not exist: $sourcePath"
}
if (-not (Test-Path -LiteralPath $sourceJson -PathType Leaf)) {
    throw "Source version JSON does not exist: $sourceJson"
}
if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
    throw "Source version JAR does not exist: $sourceJar"
}
if (Test-Path -LiteralPath $targetPath) {
    throw "Target instance already exists; no files were changed: $targetPath"
}
if (-not (Test-Path -LiteralPath $LauncherPath -PathType Leaf)) {
    throw "HMCL launcher does not exist: $LauncherPath"
}

$versionDocument = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceJson | ConvertFrom-Json
$versionFingerprint = ($versionDocument | ConvertTo-Json -Depth 100 -Compress).ToLowerInvariant()
$resolvedLoader = if ($Loader -ne "auto") {
    $Loader
} elseif ($SourceVersion.ToLowerInvariant().Contains("neoforge") -or $versionFingerprint.Contains("net.neoforged")) {
    "neoforge"
} else {
    "forge"
}
$resolvedCompanionId = if ($PSBoundParameters.ContainsKey("CompanionId")) {
    $CompanionId
} elseif ($resolvedLoader -eq "neoforge") {
    "codex-neoforge"
} else {
    "codex-forge"
}

$resolvedBridgeJar = Resolve-BridgeJar $BridgeJar $resolvedLoader
$resolvedBaritoneJar = Resolve-BaritoneJar $BaritoneJar $resolvedLoader

if (-not $PSCmdlet.ShouldProcess($targetPath, "Create isolated HMCL companion instance")) {
    return
}

$tokenState = Get-OrCreateBridgeToken $StateDirectory
New-Item -ItemType Directory -Path $targetPath | Out-Null

foreach ($directoryName in @("BODGeneticsPacks", "config", "defaultconfigs", "resourcepacks")) {
    Copy-DirectoryContents -Source (Join-Path $sourcePath $directoryName) -Destination (Join-Path $targetPath $directoryName)
}

Copy-DirectoryContents `
    -Source (Join-Path $sourcePath "mods") `
    -Destination (Join-Path $targetPath "mods") `
    -ExcludePatterns @("minecraft_codex_bridge-*.jar", "*baritone*.jar")

$targetMods = Join-Path $targetPath "mods"
New-Item -ItemType Directory -Path $targetMods -Force | Out-Null
Get-ChildItem -LiteralPath $targetMods -Filter "*baritone*.jar" -File -ErrorAction SilentlyContinue | ForEach-Object {
    throw "Unexpected Baritone duplicate copied from source: $($_.FullName)"
}
Copy-Item -LiteralPath $resolvedBridgeJar -Destination (Join-Path $targetMods (Split-Path -Leaf $resolvedBridgeJar))
if ($resolvedBaritoneJar) {
    Copy-Item -LiteralPath $resolvedBaritoneJar -Destination (Join-Path $targetMods (Split-Path -Leaf $resolvedBaritoneJar))
}

foreach ($fileName in @("options.txt", "optionsshaders.txt", "servers.dat", "log4j2.xml", "modpack.cfg", "modrinth.index.json")) {
    $file = Join-Path $sourcePath $fileName
    if (Test-Path -LiteralPath $file -PathType Leaf) {
        Copy-Item -LiteralPath $file -Destination (Join-Path $targetPath $fileName)
    }
}

$sourceSettings = Join-Path $sourcePath ".hmcl\config\instance-game-settings.json"
if (Test-Path -LiteralPath $sourceSettings -PathType Leaf) {
    $targetSettings = Join-Path $targetPath ".hmcl\config\instance-game-settings.json"
    New-Item -ItemType Directory -Path (Split-Path -Parent $targetSettings) -Force | Out-Null
    Copy-Item -LiteralPath $sourceSettings -Destination $targetSettings
}

$versionDocument.id = $TargetVersion
if ($versionDocument.PSObject.Properties.Name -contains "jar") {
    $versionDocument.jar = $TargetVersion
}
$targetJson = Join-Path $targetPath "$TargetVersion.json"
Write-Utf8NoBom -Path $targetJson -Content ($versionDocument | ConvertTo-Json -Depth 100)
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $targetPath "$TargetVersion.jar")

$bridgeConfig = [ordered]@{
    serverUrl = $ServerUrl
    token = $tokenState.Token
    companionId = $resolvedCompanionId
    name = $CompanionName
    ownerName = $OwnerName
    autoReconnect = $true
    snapshotIntervalTicks = 10
    observeRadius = 32
    allowPvp = $false
    allowBreakingContainers = $false
    hostileEntityAllowlist = @()
    npcMaterialMode = "survival"
}
Write-Utf8NoBom `
    -Path (Join-Path $targetPath "config\minecraft-codex-companion.json") `
    -Content ($bridgeConfig | ConvertTo-Json -Depth 10)

$marker = [ordered]@{
    sourceVersion = $SourceVersion
    targetVersion = $TargetVersion
    loader = $resolvedLoader
    createdAt = [DateTime]::UtcNow.ToString("o")
    bridgeJar = Split-Path -Leaf $resolvedBridgeJar
    baritoneJar = if ($resolvedBaritoneJar) { Split-Path -Leaf $resolvedBaritoneJar } else { $null }
    originalWorldsCopied = $false
    originalLogsCopied = $false
    originalScreenshotsCopied = $false
}
Write-Utf8NoBom -Path (Join-Path $targetPath "CODEX-CLONE.json") -Content ($marker | ConvertTo-Json -Depth 10)

New-Item -ItemType Directory -Path (Join-Path $targetPath "saves") -Force | Out-Null

[PSCustomObject]@{
    TargetPath = $targetPath
    TargetVersion = $TargetVersion
    Loader = $resolvedLoader
    TokenPath = $tokenState.Path
    LauncherPath = $LauncherPath
    WorldsCopied = $false
    ReadyToLaunch = $true
}
