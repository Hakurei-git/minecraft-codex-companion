[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$MinecraftRoot = (Join-Path $env:APPDATA ".minecraft"),
    [string]$TargetVersion = "",
    [string]$BridgeJar = "",
    [string]$CompanionName = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $TargetVersion) {
    throw "TargetVersion is required. Select the Codex clone on this computer."
}
$versionsRoot = [System.IO.Path]::GetFullPath((Join-Path $MinecraftRoot "versions"))
$instancePath = [System.IO.Path]::GetFullPath((Join-Path $versionsRoot $TargetVersion))
$versionsPrefix = $versionsRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if (-not $instancePath.StartsWith($versionsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Target instance escapes the Minecraft versions directory: $instancePath"
}
if (-not (Test-Path -LiteralPath $instancePath -PathType Container)) {
    throw "Target HMCL clone does not exist: $instancePath"
}

$markerPath = Join-Path $instancePath "CODEX-CLONE.json"
if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf)) {
    throw "Refusing to update an instance without CODEX-CLONE.json: $instancePath"
}
$marker = Get-Content -Raw -Encoding UTF8 -LiteralPath $markerPath | ConvertFrom-Json
if ($marker.targetVersion -ne $TargetVersion -or $marker.loader -ne "forge") {
    throw "Clone marker does not identify the requested Forge instance"
}

$normalizedCompanionName = $CompanionName.Trim()
$updateCompanionName = -not [string]::IsNullOrWhiteSpace($normalizedCompanionName)
$bridgeConfigPath = Join-Path $instancePath "config\minecraft-codex-companion.json"
$bridgeConfig = $null
if ($updateCompanionName) {
    if ($normalizedCompanionName.Length -gt 64 -or
        @($normalizedCompanionName.ToCharArray() | Where-Object { [char]::IsControl($_) }).Count -gt 0) {
        throw "CompanionName must contain 1 to 64 visible characters"
    }
    if (-not (Test-Path -LiteralPath $bridgeConfigPath -PathType Leaf)) {
        throw "Companion configuration does not exist in the target clone"
    }
    $bridgeConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath $bridgeConfigPath | ConvertFrom-Json
}

if (-not $BridgeJar) {
    $libs = Join-Path $projectRoot "mods\forge-1.20.1\build\libs"
    $candidate = Get-ChildItem -LiteralPath $libs -Filter "minecraft_codex_bridge-forge-1.20.1-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "-(sources|javadoc)\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $candidate) {
        throw "Forge bridge JAR not found. Run npm run build:forge or pass -BridgeJar."
    }
    $BridgeJar = $candidate.FullName
}
$sourceJar = (Resolve-Path -LiteralPath $BridgeJar).Path
$sourceInfo = Get-Item -LiteralPath $sourceJar
if ($sourceInfo.Name -notlike "minecraft_codex_bridge-forge-1.20.1-*.jar") {
    throw "Unexpected Forge bridge filename: $($sourceInfo.Name)"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($sourceJar)
try {
    $entryNames = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    foreach ($required in @(
        "META-INF/mods.toml",
        "cn/codex/minecraftbridge/forge/CodexNpcEntity.class",
        "cn/codex/minecraftbridge/forge/FollowResilienceLiveFixture.class",
        "cn/codex/minecraftbridge/forge/NoCheatExpeditionLiveFixture.class",
        "cn/codex/minecraftbridge/forge/ResourcePriorityLiveFixture.class",
        "assets/minecraft_codex_bridge/textures/entity/codex_catgirl.png"
    )) {
        if ($entryNames -notcontains $required) {
            throw "Bridge JAR is missing required NPC content: $required"
        }
    }
} finally {
    $archive.Dispose()
}

$running = @(Get-Process -Name "java", "javaw" -ErrorAction SilentlyContinue)
if ($running) {
    $identities = ($running | ForEach-Object { "$($_.ProcessName):$($_.Id)" }) -join ", "
    throw "A Java process is still running ($identities). Exit Minecraft and HMCL before updating the bridge JAR. Process command lines are intentionally not inspected."
}

$modsPath = Join-Path $instancePath "mods"
New-Item -ItemType Directory -Path $modsPath -Force | Out-Null
$oldJars = @(Get-ChildItem -LiteralPath $modsPath -Filter "minecraft_codex_bridge-*.jar" -File -ErrorAction SilentlyContinue)
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupPath = Join-Path $instancePath "bridge-backups\$timestamp"
$temporaryJar = Join-Path $modsPath (".codex-bridge-" + [Guid]::NewGuid().ToString("N") + ".tmp")
$temporaryConfig = Join-Path (Split-Path -Parent $bridgeConfigPath) (".codex-config-" + [Guid]::NewGuid().ToString("N") + ".tmp")
$destinationJar = Join-Path $modsPath $sourceInfo.Name
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceJar).Hash

if (-not $PSCmdlet.ShouldProcess($instancePath, "Replace the Codex bridge JAR with $($sourceInfo.Name)")) {
    return
}

Copy-Item -LiteralPath $sourceJar -Destination $temporaryJar -Force
$temporaryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryJar).Hash
if ($temporaryHash -ne $sourceHash) {
    Remove-Item -LiteralPath $temporaryJar -Force
    throw "Copied bridge JAR failed SHA-256 verification"
}

$movedOldJars = @()
$configBackup = $null
try {
    if ($oldJars.Count -gt 0 -or $updateCompanionName) {
        New-Item -ItemType Directory -Path $backupPath -Force | Out-Null
    }
    if ($updateCompanionName) {
        $configBackup = Join-Path $backupPath "minecraft-codex-companion.json"
        Copy-Item -LiteralPath $bridgeConfigPath -Destination $configBackup
    }
    if ($oldJars.Count -gt 0) {
        foreach ($oldJar in $oldJars) {
            $backupJar = Join-Path $backupPath $oldJar.Name
            Move-Item -LiteralPath $oldJar.FullName -Destination $backupJar
            $movedOldJars += [PSCustomObject]@{ Original = $oldJar.FullName; Backup = $backupJar }
        }
    }
    Move-Item -LiteralPath $temporaryJar -Destination $destinationJar

    if ($updateCompanionName) {
        $bridgeConfig.name = $normalizedCompanionName
        [System.IO.File]::WriteAllText(
            $temporaryConfig,
            ($bridgeConfig | ConvertTo-Json -Depth 20),
            [System.Text.UTF8Encoding]::new($false)
        )
        Move-Item -LiteralPath $temporaryConfig -Destination $bridgeConfigPath -Force
    }

    $marker.bridgeJar = $sourceInfo.Name
    if ($marker.PSObject.Properties.Name -contains "updatedAt") {
        $marker.updatedAt = (Get-Date).ToUniversalTime().ToString("o")
    } else {
        $marker | Add-Member -NotePropertyName updatedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString("o"))
    }
    [System.IO.File]::WriteAllText(
        $markerPath,
        ($marker | ConvertTo-Json -Depth 20),
        [System.Text.UTF8Encoding]::new($false)
    )
} catch {
    if (Test-Path -LiteralPath $temporaryJar -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryJar -Force
    }
    if (Test-Path -LiteralPath $temporaryConfig -PathType Leaf) {
        Remove-Item -LiteralPath $temporaryConfig -Force
    }
    if ($configBackup -and (Test-Path -LiteralPath $configBackup -PathType Leaf)) {
        Copy-Item -LiteralPath $configBackup -Destination $bridgeConfigPath -Force
    }
    if (Test-Path -LiteralPath $destinationJar -PathType Leaf) {
        Remove-Item -LiteralPath $destinationJar -Force
    }
    foreach ($item in $movedOldJars) {
        if (Test-Path -LiteralPath $item.Backup -PathType Leaf) {
            Move-Item -LiteralPath $item.Backup -Destination $item.Original
        }
    }
    throw
}

[PSCustomObject]@{
    Instance = $instancePath
    InstalledJar = $destinationJar
    Sha256 = $sourceHash
    Replaced = @($oldJars | ForEach-Object Name)
    Backup = if ($oldJars.Count -gt 0) { $backupPath } else { $null }
    SavesPreserved = $true
    ConfigPreserved = $true
    CompanionNameUpdated = $updateCompanionName
    OtherModsPreserved = $true
}
