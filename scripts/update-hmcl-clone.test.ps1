$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $projectRoot ("runtime\update-test-" + [Guid]::NewGuid().ToString("N"))
$minecraftRoot = Join-Path $testRoot ".minecraft"
$version = "Fixture_Forge_1.20.1-Codex"
$instance = Join-Path $minecraftRoot "versions\$version"
$mods = Join-Path $instance "mods"
$sourceTree = Join-Path $testRoot "jar-content"
$sourceJar = Join-Path $testRoot "minecraft_codex_bridge-forge-1.20.1-0.2.0.jar"
$sourceZip = Join-Path $testRoot "minecraft_codex_bridge-forge-1.20.1-0.2.0.zip"

function Write-TestFile([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

Write-TestFile (Join-Path $sourceTree "META-INF\mods.toml") 'version="0.2.0"'
Write-TestFile (Join-Path $sourceTree "cn\codex\minecraftbridge\forge\CodexNpcEntity.class") "npc"
Write-TestFile (Join-Path $sourceTree "cn\codex\minecraftbridge\forge\FollowResilienceLiveFixture.class") "fixture"
Write-TestFile (Join-Path $sourceTree "cn\codex\minecraftbridge\forge\NoCheatExpeditionLiveFixture.class") "fixture"
Write-TestFile (Join-Path $sourceTree "cn\codex\minecraftbridge\forge\ResourcePriorityLiveFixture.class") "fixture"
Write-TestFile (Join-Path $sourceTree "assets\minecraft_codex_bridge\textures\entity\codex_catgirl.png") "skin"
Compress-Archive -Path (Join-Path $sourceTree "*") -DestinationPath $sourceZip
Move-Item -LiteralPath $sourceZip -Destination $sourceJar

Write-TestFile (Join-Path $instance "CODEX-CLONE.json") (@{
    sourceVersion = "Fixture_Forge_1.20.1"
    targetVersion = $version
    loader = "forge"
    bridgeJar = "minecraft_codex_bridge-forge-1.20.1-0.1.0.jar"
} | ConvertTo-Json)
Write-TestFile (Join-Path $mods "minecraft_codex_bridge-forge-1.20.1-0.1.0.jar") "old bridge"
Write-TestFile (Join-Path $mods "unrelated-mod.jar") "other mod"
Write-TestFile (Join-Path $instance "saves\test-world\level.dat") "world data"
Write-TestFile (Join-Path $instance "config\other-mod.toml") "enabled=true"
$expectedCompanionName = [string][char]0x552F
Write-TestFile (Join-Path $instance "config\minecraft-codex-companion.json") (@{
    name = "?"
    token = "fixture-secret-preserved"
    observeRadius = 32
} | ConvertTo-Json)

$worldHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $instance "saves\test-world\level.dat")).Hash
$configHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $instance "config\other-mod.toml")).Hash
$otherModHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $mods "unrelated-mod.jar")).Hash

$result = & (Join-Path $PSScriptRoot "update-hmcl-clone.ps1") `
    -MinecraftRoot $minecraftRoot `
    -TargetVersion $version `
    -BridgeJar $sourceJar `
    -CompanionName $expectedCompanionName
$installed = Join-Path $mods (Split-Path -Leaf $sourceJar)

Assert-True (Test-Path -LiteralPath $installed -PathType Leaf) "new bridge was not installed"
Assert-True (-not (Test-Path -LiteralPath (Join-Path $mods "minecraft_codex_bridge-forge-1.20.1-0.1.0.jar"))) "old bridge remains in mods"
Assert-True ((Get-FileHash -Algorithm SHA256 -LiteralPath $installed).Hash -eq (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceJar).Hash) "installed bridge hash differs"
Assert-True ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $instance "saves\test-world\level.dat")).Hash -eq $worldHash) "save was modified"
Assert-True ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $instance "config\other-mod.toml")).Hash -eq $configHash) "config was modified"
Assert-True ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $mods "unrelated-mod.jar")).Hash -eq $otherModHash) "other mod was modified"
Assert-True ((Get-ChildItem -LiteralPath (Join-Path $instance "bridge-backups") -Filter "minecraft_codex_bridge-*.jar" -File -Recurse | Measure-Object).Count -eq 1) "old bridge backup was not created"
Assert-True ($result.InstalledJar -eq $installed) "script result reports the wrong installed JAR"
Assert-True ($result.CompanionNameUpdated) "script did not report the companion name update"

$bridgeConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $instance "config\minecraft-codex-companion.json") | ConvertFrom-Json
Assert-True ($bridgeConfig.name -ceq $expectedCompanionName) "UTF-8 companion name was corrupted"
Assert-True ($bridgeConfig.token -ceq "fixture-secret-preserved") "unrelated bridge secret was modified"
Assert-True ($bridgeConfig.observeRadius -eq 32) "unrelated bridge configuration was modified"
$configBackups = @(Get-ChildItem -LiteralPath (Join-Path $instance "bridge-backups") -Filter "minecraft-codex-companion.json" -File -Recurse)
Assert-True ($configBackups.Count -eq 1) "bridge configuration backup was not created"

$marker = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $instance "CODEX-CLONE.json") | ConvertFrom-Json
Assert-True ($marker.bridgeJar -eq (Split-Path -Leaf $sourceJar)) "clone marker was not updated"

Write-Output "HMCL clone bridge updater integration test passed: $testRoot"
