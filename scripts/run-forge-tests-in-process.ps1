[CmdletBinding()]
param(
    [ValidateRange(1, 10000)]
    [int]$ExpectedTestCount = 375
)

$ErrorActionPreference = "Stop"
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$gradleCache = Join-Path $projectRoot "runtime\gradle-home\caches"
$forgeCache = Join-Path $gradleCache "forge_gradle"
$moduleCache = Join-Path $gradleCache "modules-2\files-2.1"
$runnerSource = Join-Path $PSScriptRoot "forge-in-process-test\InProcessJUnitRunner.java"
$runnerRoot = Join-Path $projectRoot ".runtime\forge-junit-runner"
$runnerClasses = Join-Path $runnerRoot "classes"
$junitRuntime = Join-Path $runnerRoot "junit-5.10.2"
$testClasses = Join-Path $projectRoot "mods\forge-1.20.1\build\classes\java\test"
$mainClasses = Join-Path $projectRoot "mods\forge-1.20.1\build\classes\java\main"
$mainResources = Join-Path $projectRoot "mods\forge-1.20.1\build\resources\main"

foreach ($required in @($forgeCache, $moduleCache, $runnerSource, $testClasses, $mainClasses, $mainResources)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Forge in-process test input is unavailable: $required"
    }
}

$java = (Get-Command java.exe -ErrorAction Stop).Source
$javac = (Get-Command javac.exe -ErrorAction Stop).Source
$junitSourceJars = @(Get-ChildItem -LiteralPath $moduleCache -Recurse -Filter "*.jar" -File | Where-Object {
    $_.Name -match '^(junit-(jupiter|platform).*-?(5\.10\.2|1\.10\.2)|opentest4j-1\.3\.0|apiguardian-api-1\.1\.2)\.jar$'
})
if ($junitSourceJars.Count -lt 8) {
    throw "The audited JUnit 5.10.2 runtime is incomplete"
}

[IO.Directory]::CreateDirectory($runnerClasses) | Out-Null
[IO.Directory]::CreateDirectory($junitRuntime) | Out-Null
foreach ($sourceJar in $junitSourceJars) {
    $destinationJar = Join-Path $junitRuntime $sourceJar.Name
    Copy-Item -LiteralPath $sourceJar.FullName -Destination $destinationJar -Force
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceJar.FullName).Hash
    $destinationHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash
    if ($sourceHash -ne $destinationHash) {
        throw "The isolated JUnit runtime copy failed integrity verification: $($sourceJar.Name)"
    }
}
$junitJars = @($junitSourceJars | ForEach-Object { Get-Item -LiteralPath (Join-Path $junitRuntime $_.Name) })
$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $compileOutput = @(& $javac -encoding UTF-8 -d $runnerClasses $runnerSource 2>&1)
    $compileExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($compileExitCode -ne 0 -or $compileOutput.Count -gt 0) {
    throw "Failed to compile the Forge in-process JUnit runner: $($compileOutput -join [Environment]::NewLine)"
}

$mappedForge = @(Get-ChildItem -LiteralPath (Join-Path $forgeCache "minecraft_user_repo") `
    -Recurse -Filter "forge-1.20.1-47.4.21_mapped_official_1.20.1.jar" -File)
if ($mappedForge.Count -ne 1) {
    throw "Expected exactly one mapped Forge 1.20.1 runtime"
}
$forgeLibraries = @(Get-ChildItem -LiteralPath (Join-Path $forgeCache "maven_downloader") `
    -Recurse -Filter "*.jar" -File | Where-Object { $_.Name -notmatch '-sources\.jar$' })

$classpathEntries = @(
    $runnerClasses,
    $testClasses,
    $mainClasses,
    $mainResources,
    $mappedForge[0].FullName
) + @($forgeLibraries.FullName) + @($junitJars.FullName)
$classpath = @($classpathEntries | Select-Object -Unique) -join [IO.Path]::PathSeparator
$output = @(& $java -cp $classpath InProcessJUnitRunner $testClasses 2>&1)
$exitCode = $LASTEXITCODE
$output | ForEach-Object { Write-Output $_ }
if ($exitCode -ne 0) {
    throw "Forge in-process JUnit runner failed with code $exitCode"
}

$summary = @{}
foreach ($line in $output) {
    if ([string]$line -match '^(TESTS_FOUND|TESTS_SUCCEEDED|TESTS_FAILED)=(\d+)$') {
        $summary[$Matches[1]] = [int]$Matches[2]
    }
}
if ($summary.TESTS_FOUND -ne $ExpectedTestCount -or
    $summary.TESTS_SUCCEEDED -ne $ExpectedTestCount -or
    $summary.TESTS_FAILED -ne 0) {
    throw "Forge test summary did not match the required $ExpectedTestCount successful tests"
}
