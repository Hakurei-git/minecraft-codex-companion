$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ("runtime\install-test-" + [Guid]::NewGuid().ToString("N"))
$minecraftRoot = Join-Path $testRoot ".minecraft"
$stateDirectory = Join-Path $testRoot "state"
$launcher = Join-Path $testRoot "HMCL.exe"

function Write-TestFile([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

function Get-TreeHashes([string]$Path) {
    return Get-ChildItem -LiteralPath $Path -File -Recurse | Sort-Object FullName | ForEach-Object {
        "$($_.FullName.Substring($Path.Length))=$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash)"
    }
}

function Invoke-CloneCase(
    [string]$SourceVersion,
    [string]$ExpectedLoader,
    [string]$BridgeName,
    [bool]$InstallBaritone
) {
    $expectedCompanionName = [string][char]0x552F
    $targetVersion = "$SourceVersion-Codex"
    $sourcePath = Join-Path $minecraftRoot "versions\$SourceVersion"
    $targetPath = Join-Path $minecraftRoot "versions\$targetVersion"
    $bridgeJar = Join-Path $testRoot $BridgeName
    $baritoneJar = Join-Path $testRoot "baritone-forge-1.20.1.jar"
    $libraries = if ($ExpectedLoader -eq "neoforge") {
        @(@{ name = "net.neoforged:neoforge:21.1.182" })
    } else {
        @(@{ name = "net.minecraftforge:forge:1.20.1-47.4.21" })
    }

    Write-TestFile -Path $bridgeJar -Content "fixture $ExpectedLoader bridge"
    if ($InstallBaritone) {
        Write-TestFile -Path $baritoneJar -Content "fixture baritone"
    }
    Write-TestFile -Path (Join-Path $sourcePath "$SourceVersion.jar") -Content "fixture minecraft jar"
    Write-TestFile -Path (Join-Path $sourcePath "$SourceVersion.json") -Content (@{
        id = $SourceVersion
        jar = $SourceVersion
        type = "release"
        mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher"
        libraries = $libraries
    } | ConvertTo-Json -Depth 10)
    Write-TestFile -Path (Join-Path $sourcePath "mods\base-mod.jar") -Content "base mod"
    Write-TestFile -Path (Join-Path $sourcePath "mods\minecraft_codex_bridge-old.jar") -Content "old bridge"
    Write-TestFile -Path (Join-Path $sourcePath "mods\baritone-old.jar") -Content "old baritone"
    Write-TestFile -Path (Join-Path $sourcePath "config\base.json") -Content "{}"
    Write-TestFile -Path (Join-Path $sourcePath "defaultconfigs\loader-server.toml") -Content "fixture=true"
    Write-TestFile -Path (Join-Path $sourcePath "resourcepacks\fixture.txt") -Content "resource pack"
    Write-TestFile -Path (Join-Path $sourcePath ".hmcl\config\instance-game-settings.json") -Content '{"javaType":"CUSTOM"}'
    Write-TestFile -Path (Join-Path $sourcePath "options.txt") -Content "lang:zh_cn"
    Write-TestFile -Path (Join-Path $sourcePath "saves\original-world\level.dat") -Content "original world"
    Write-TestFile -Path (Join-Path $sourcePath "logs\latest.log") -Content "private log"
    Write-TestFile -Path (Join-Path $sourcePath "screenshots\private.png") -Content "private screenshot"

    $sourceHashesBefore = Get-TreeHashes $sourcePath
    $parameters = @{
        MinecraftRoot = $minecraftRoot
        SourceVersion = $SourceVersion
        TargetVersion = $targetVersion
        BridgeJar = $bridgeJar
        StateDirectory = $stateDirectory
        LauncherPath = $launcher
        OwnerName = "FixturePlayer"
        CompanionName = $expectedCompanionName
    }
    if ($InstallBaritone) {
        $parameters.BaritoneJar = $baritoneJar
    } else {
        $parameters.SkipBaritone = $true
    }
    & (Join-Path $PSScriptRoot "install-hmcl-clone.ps1") @parameters | Out-Null

    Assert-True (Test-Path -LiteralPath $targetPath -PathType Container) "$ExpectedLoader target instance was not created"
    Assert-True (Test-Path -LiteralPath (Join-Path $targetPath "mods\base-mod.jar")) "$ExpectedLoader base mod was not copied"
    Assert-True (Test-Path -LiteralPath (Join-Path $targetPath "mods\$BridgeName")) "$ExpectedLoader bridge mod was not installed"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $targetPath "mods\minecraft_codex_bridge-old.jar"))) "$ExpectedLoader old bridge was copied"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $targetPath "mods\baritone-old.jar"))) "$ExpectedLoader old Baritone was copied"
    if ($InstallBaritone) {
        Assert-True (Test-Path -LiteralPath (Join-Path $targetPath "mods\baritone-forge-1.20.1.jar")) "Forge Baritone was not installed"
    } else {
        Assert-True ((Get-ChildItem -LiteralPath (Join-Path $targetPath "mods") -Filter "*baritone*.jar" | Measure-Object).Count -eq 0) "NeoForge unexpectedly contains Baritone"
    }
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $targetPath "saves\original-world"))) "$ExpectedLoader original world was copied"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $targetPath "logs"))) "$ExpectedLoader logs were copied"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $targetPath "screenshots"))) "$ExpectedLoader screenshots were copied"
    Assert-True ((Get-ChildItem -LiteralPath (Join-Path $targetPath "saves") -Force | Measure-Object).Count -eq 0) "$ExpectedLoader clone saves directory is not empty"

    $version = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $targetPath "$targetVersion.json") | ConvertFrom-Json
    Assert-True ($version.id -eq $targetVersion) "$ExpectedLoader version JSON id was not renamed"
    Assert-True ($version.jar -eq $targetVersion) "$ExpectedLoader version JSON jar was not renamed"

    $token = (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $stateDirectory "bridge-token.txt")).Trim()
    $bridgeConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $targetPath "config\minecraft-codex-companion.json") | ConvertFrom-Json
    $expectedCompanionId = if ($ExpectedLoader -eq "neoforge") { "codex-neoforge" } else { "codex-forge" }
    Assert-True ($token.Length -eq 64) "bridge token is not 256-bit hex"
    Assert-True ($bridgeConfig.token -eq $token) "$ExpectedLoader server and mod token files disagree"
    Assert-True ($bridgeConfig.companionId -eq $expectedCompanionId) "$ExpectedLoader companion id is incorrect"
    Assert-True ($bridgeConfig.name -ceq $expectedCompanionName) "$ExpectedLoader UTF-8 companion name was corrupted"

    $marker = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $targetPath "CODEX-CLONE.json") | ConvertFrom-Json
    Assert-True ($marker.loader -eq $ExpectedLoader) "$ExpectedLoader clone marker loader is incorrect"
    Assert-True ($marker.originalWorldsCopied -eq $false) "$ExpectedLoader clone marker does not record world isolation"

    $sourceHashesAfter = Get-TreeHashes $sourcePath
    Assert-True (($sourceHashesBefore -join "`n") -eq ($sourceHashesAfter -join "`n")) "$ExpectedLoader source instance was modified"
}

Write-TestFile -Path $launcher -Content "fixture launcher"
Invoke-CloneCase -SourceVersion "Fixture_Forge_1.20.1" -ExpectedLoader "forge" -BridgeName "minecraft_codex_bridge-forge-1.20.1-0.1.0.jar" -InstallBaritone $true
Invoke-CloneCase -SourceVersion "Fixture_1.21.1-NeoForge" -ExpectedLoader "neoforge" -BridgeName "minecraft_codex_bridge-neoforge-1.21.1-0.1.0.jar" -InstallBaritone $false

Write-Output "HMCL Forge and NeoForge clone installer integration tests passed: $testRoot"
