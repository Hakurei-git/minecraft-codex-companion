[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('mc-codex-single-exe-test-' + [Guid]::NewGuid().ToString('N'))
$tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$testRoot = [System.IO.Path]::GetFullPath($testRoot)
if (-not ($testRoot + '\').StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Offline single-EXE test escaped the system temporary directory.'
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$LiteralPath)

    $resolved = [System.IO.Path]::GetFullPath($LiteralPath)
    $stream = [System.IO.File]::OpenRead($resolved)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha256.ComputeHash($stream)
        return (($bytes | ForEach-Object { $_.ToString("x2") }) -join "")
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

function Write-Utf8([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function New-PortableFixtureManifest([string]$PayloadRoot) {
    $files = @(Get-ChildItem -LiteralPath $PayloadRoot -File -Recurse | Where-Object {
        $_.Name -ne 'portable-manifest.json'
    } | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($PayloadRoot.Length).TrimStart('\').Replace('\', '/')
            size = $_.Length
            sha256 = Get-Sha256Hex -LiteralPath $_.FullName
        }
    })
    $manifest = [ordered]@{
        format = 2
        name = 'Minecraft Codex Companion Offline Fixture'
        version = 'test'
        platform = 'win32-x64'
        packaging = [ordered]@{
            model = 'transparent-multi-file'
            selfExtracting = $false
            executableInjection = $false
            runtimePowerShell = $false
            runtimeCommandShell = $false
        }
        privacy = [ordered]@{
            containsApiKeys = $false
            containsBridgeToken = $false
            containsLocalState = $false
            containsMinecraftWorlds = $false
            containsBuildMachinePaths = $false
        }
        files = $files
    }
    Write-Utf8 (Join-Path $PayloadRoot 'portable-manifest.json') ($manifest | ConvertTo-Json -Depth 8)
}

function Invoke-Builder([string]$Payload, [string]$Output) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $projectRoot 'scripts\build-single-exe.ps1') `
        -PayloadRoot $Payload `
        -OutputRoot $Output `
        -AllowTestPayload | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Single-EXE fixture build failed with code $LASTEXITCODE"
    }
    return Join-Path $Output 'MinecraftCodexCompanion-Setup.exe'
}

function Invoke-Installer(
    [string]$Installer,
    [string]$ApplicationRoot,
    [string]$ResultFile,
    [switch]$InstallOnly
) {
    $arguments = @('--quiet', '--test-root', ('"' + $ApplicationRoot + '"'), '--result-file', ('"' + $ResultFile + '"'))
    if ($InstallOnly) { $arguments += '--install-only' }
    $process = Start-Process -FilePath $Installer -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    $failureDetail = if (Test-Path -LiteralPath $ResultFile -PathType Leaf) { (Get-Content -Raw -Encoding UTF8 -LiteralPath $ResultFile) } else { '' }
    Assert-True ($process.ExitCode -eq 0) "installer exited with code $($process.ExitCode): $failureDetail"
    Assert-True (Test-Path -LiteralPath $ResultFile -PathType Leaf) 'installer did not write its offline result'
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $ResultFile | ConvertFrom-Json
}

function Wait-ForFile([string]$Path, [int]$Seconds = 10) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $Path -PathType Leaf) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Timed out waiting for fixture launcher: $Path"
}

$installerSource = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $projectRoot 'apps\single-exe-installer\Program.cs')
$mainIndex = $installerSource.IndexOf('private static int Main(string[] args)', [System.StringComparison]::Ordinal)
$textRenderingIndex = $installerSource.IndexOf('Application.SetCompatibleTextRenderingDefault(false);', $mainIndex, [System.StringComparison]::Ordinal)
$optionsIndex = $installerSource.IndexOf('Options options;', $mainIndex, [System.StringComparison]::Ordinal)
$textRenderingCalls = [regex]::Matches($installerSource, [regex]::Escape('Application.SetCompatibleTextRenderingDefault(false);')).Count
Assert-True ($mainIndex -ge 0 -and $textRenderingIndex -gt $mainIndex -and $textRenderingIndex -lt $optionsIndex) `
    'WinForms text rendering must be configured at the start of Main before any error window or progress form can be created'
Assert-True ($textRenderingCalls -eq 1) 'WinForms text rendering must be configured exactly once per installer process'

$singleBuildSource = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $projectRoot 'scripts\build-single-exe.ps1')
$iconPath = Join-Path $projectRoot 'assets\branding\app-icon.ico'
Assert-True (Test-Path -LiteralPath $iconPath -PathType Leaf) 'multi-resolution application icon is missing'
Assert-True ($singleBuildSource.Contains('/win32icon:$appIcon')) 'single-EXE compiler must embed the project icon'
Assert-True ($singleBuildSource.Contains('$iconHash')) 'single-EXE deterministic seed must include the icon hash'

$savedSentinel = $env:MC_SINGLE_EXE_TEST_LAUNCH_SENTINEL
try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    $payload = Join-Path $testRoot 'payload'
    $outputA = Join-Path $testRoot 'output-a'
    $outputB = Join-Path $testRoot 'output-b'
    $outputV2 = Join-Path $testRoot 'output-v2'
    $applicationRoot = Join-Path $testRoot 'installed\Application'
    New-Item -ItemType Directory -Path (Join-Path $payload 'runtime') -Force | Out-Null

    $fixtureSource = Join-Path $testRoot 'FixtureLauncher.cs'
    Write-Utf8 $fixtureSource @'
using System;
using System.IO;
public static class FixtureLauncher
{
    public static void Main()
    {
        string target = Environment.GetEnvironmentVariable("MC_SINGLE_EXE_TEST_LAUNCH_SENTINEL");
        if (!String.IsNullOrEmpty(target))
        {
            File.WriteAllText(target, AppDomain.CurrentDomain.BaseDirectory);
        }
    }
}
'@
    $fixtureLauncher = Join-Path $payload 'MinecraftCodexCompanion.exe'
    $csc = @(
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    Assert-True ($null -ne $csc) 'offline fixture requires the local .NET Framework compiler'
    & $csc /nologo /target:winexe /optimize+ "/out:$fixtureLauncher" $fixtureSource
    Assert-True ($LASTEXITCODE -eq 0) 'fixture launcher compilation failed'

    Write-Utf8 (Join-Path $payload 'runtime\payload-version.txt') 'v1'
    Write-Utf8 (Join-Path $payload 'README.txt') 'offline fixture without credentials, configuration, logs, saves, or screenshots'
    New-PortableFixtureManifest $payload

    $installerA = Invoke-Builder $payload $outputA
    $hashA = Get-Sha256Hex -LiteralPath $installerA
    $installerAReplaced = Invoke-Builder $payload $outputA
    $hashAReplaced = Get-Sha256Hex -LiteralPath $installerAReplaced
    Assert-True ($hashA -eq $hashAReplaced) 'replacing an existing build changed an identical deterministic EXE'
    $replacementArtifacts = @(Get-ChildItem -LiteralPath $outputA -Force -File | Where-Object {
        $_.Name -like '.MinecraftCodexCompanion-Setup.exe.*.tmp' -or
        $_.Name -like '.MinecraftCodexCompanion-Setup.exe.*.bak'
    })
    Assert-True ($replacementArtifacts.Count -eq 0) 'existing-output replacement left temporary or backup files'

    $installerB = Invoke-Builder $payload $outputB
    $hashB = Get-Sha256Hex -LiteralPath $installerB
    Assert-True ($hashA -eq $hashB) 'identical staged payloads did not produce byte-identical EXEs'

    $selfTest = Start-Process -FilePath $installerA -ArgumentList @('--self-test', '--quiet') -WindowStyle Hidden -Wait -PassThru
    Assert-True ($selfTest.ExitCode -eq 0) 'published installer self-test failed'

    $sentinel = Join-Path $testRoot 'fixture-launched.txt'
    $env:MC_SINGLE_EXE_TEST_LAUNCH_SENTINEL = $sentinel
    $result1 = Invoke-Installer $installerA $applicationRoot (Join-Path $testRoot 'result-1.json')
    Wait-ForFile $sentinel
    Assert-True ($result1.status -eq 'installed') 'first installation did not report installed'
    Assert-True ($result1.packageId -match '^[a-f0-9]{64}$') 'first installation package id is invalid'
    Assert-True ($result1.releaseName -eq $result1.packageId.Substring(0, 24)) 'first installation did not use the canonical release directory'
    $release1 = Join-Path (Join-Path $applicationRoot 'releases') $result1.releaseName
    Assert-True (Test-Path -LiteralPath (Join-Path $release1 'MinecraftCodexCompanion.exe') -PathType Leaf) 'installed launcher is missing'
    Assert-True ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $release1 'runtime\payload-version.txt')) -eq 'v1') 'installed v1 payload is incorrect'

    $releaseCountBefore = @(Get-ChildItem -LiteralPath (Join-Path $applicationRoot 'releases') -Directory | Where-Object { $_.Name -notlike '.staging-*' }).Count
    $resultRepeat = Invoke-Installer $installerA $applicationRoot (Join-Path $testRoot 'result-repeat.json') -InstallOnly
    $releaseCountAfter = @(Get-ChildItem -LiteralPath (Join-Path $applicationRoot 'releases') -Directory | Where-Object { $_.Name -notlike '.staging-*' }).Count
    Assert-True ($resultRepeat.releaseName -eq $result1.releaseName) 'idempotent install did not reuse the verified release'
    Assert-True ($releaseCountBefore -eq $releaseCountAfter) 'idempotent install created another release'

    Write-Utf8 (Join-Path $payload 'runtime\payload-version.txt') 'v2'
    New-PortableFixtureManifest $payload
    $installerV2 = Invoke-Builder $payload $outputV2
    $result2 = Invoke-Installer $installerV2 $applicationRoot (Join-Path $testRoot 'result-2.json') -InstallOnly
    Assert-True ($result2.packageId -ne $result1.packageId) 'updated payload did not receive a new content package id'
    Assert-True (Test-Path -LiteralPath $release1 -PathType Container) 'atomic update removed the old release'
    $release2 = Join-Path (Join-Path $applicationRoot 'releases') $result2.releaseName
    Assert-True ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $release2 'runtime\payload-version.txt')) -eq 'v2') 'installed v2 payload is incorrect'
    $pointer = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $applicationRoot 'current.json') | ConvertFrom-Json
    Assert-True ($pointer.packageId -eq $result2.packageId) 'atomic current pointer did not select v2'
    Assert-True ($pointer.releaseName -eq $result2.releaseName) 'atomic current pointer selected the wrong release'

    $unknown = Join-Path $release2 'user-unknown-file.txt'
    Write-Utf8 $unknown 'must survive repair'
    $repair = Invoke-Installer $installerV2 $applicationRoot (Join-Path $testRoot 'result-repair.json') -InstallOnly
    Assert-True (Test-Path -LiteralPath $unknown -PathType Leaf) 'repair deleted an unknown file'
    Assert-True ($repair.releaseName -ne $result2.releaseName) 'modified release was reused instead of isolated'
    Assert-True ($repair.releaseName.StartsWith($result2.packageId.Substring(0, 24) + '-repair-')) 'repair release name is not content-addressed'
    $repairRoot = Join-Path (Join-Path $applicationRoot 'releases') $repair.releaseName
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $repairRoot 'user-unknown-file.txt'))) 'unknown file leaked into the clean repair release'
    Assert-True ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repairRoot 'runtime\payload-version.txt')) -eq 'v2') 'repair release payload is incorrect'

    $temporaryPointers = @(Get-ChildItem -LiteralPath $applicationRoot -File -Filter '.current-*.tmp' -ErrorAction SilentlyContinue)
    Assert-True ($temporaryPointers.Count -eq 0) 'atomic pointer update left temporary files'

    Write-Host 'Single-EXE offline tests passed: WinForms initialization order, deterministic build, verified extraction, launch, idempotence, atomic update, and no unknown-file deletion.'
} finally {
    $env:MC_SINGLE_EXE_TEST_LAUNCH_SENTINEL = $savedSentinel
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        $resolved = [System.IO.Path]::GetFullPath($testRoot)
        if (($resolved + '\').StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
