[CmdletBinding()]
param(
    [string]$ExecutablePath = "",
    [string]$ClamScanPath = "",
    [string]$ClamDatabaseRoot = ""
)

$ErrorActionPreference = "Stop"

function Invoke-NativeCapture([string]$Command, [string[]]$Arguments) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Command @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [PSCustomObject]@{ Output = $output; ExitCode = $exitCode }
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

$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$singleBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "build\single-exe"))
$expectedExecutable = Join-Path $singleBuildRoot "MinecraftCodexCompanion-Setup.exe"
if ([string]::IsNullOrWhiteSpace($ExecutablePath)) { $ExecutablePath = $expectedExecutable }
$ExecutablePath = [System.IO.Path]::GetFullPath($ExecutablePath)
$outputRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $ExecutablePath))
$allowedOutputPrefix = $singleBuildRoot.TrimEnd('\') + '\'

if ((Split-Path -Leaf $ExecutablePath) -ne "MinecraftCodexCompanion-Setup.exe" -or
    (-not $outputRoot.Equals($singleBuildRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
     -not $outputRoot.StartsWith($allowedOutputPrefix, [System.StringComparison]::OrdinalIgnoreCase))) {
    throw "Single-EXE security gate only accepts a versioned production artifact under the build root."
}
if (-not (Test-Path -LiteralPath $ExecutablePath -PathType Leaf)) {
    throw "Single-EXE release artifact is missing."
}

$buildReportPath = Join-Path $outputRoot "single-exe-build.json"
$checksumPath = Join-Path $outputRoot "SHA256SUMS.txt"
if (-not (Test-Path -LiteralPath $buildReportPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Single-EXE build evidence is incomplete."
}

$buildReport = Get-Content -Raw -Encoding UTF8 -LiteralPath $buildReportPath | ConvertFrom-Json
$sha256 = Get-Sha256Hex -LiteralPath $ExecutablePath
$expectedSha256 = ((Get-Content -Raw -Encoding UTF8 -LiteralPath $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
if ($expectedSha256 -notmatch '^[a-f0-9]{64}$' -or
    $sha256 -ne $expectedSha256 -or
    $sha256 -ne ([string]$buildReport.sha256).ToLowerInvariant()) {
    throw "Single-EXE SHA-256 does not match its build evidence."
}
if ($buildReport.artifact -ne (Split-Path -Leaf $ExecutablePath)) {
    throw "Single-EXE build evidence names a different artifact."
}
foreach ($property in @(
    'containsApiKeys',
    'containsBaseUrlConfiguration',
    'containsBridgeToken',
    'containsLocalState',
    'containsMinecraftWorlds',
    'containsBuildMachinePaths'
)) {
    if ($buildReport.privacy.$property -ne $false) {
        throw "Single-EXE privacy evidence is missing or unsafe: $property"
    }
}

function Resolve-InputPath([string]$Value) {
    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Value))
}

$securityRoot = Join-Path $projectRoot ".runtime\security"
$clamCandidates = if ([string]::IsNullOrWhiteSpace($ClamScanPath)) {
    @(Get-ChildItem -LiteralPath $securityRoot -Recurse -File -Filter "clamscan.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName |
        Select-Object -ExpandProperty FullName)
} else {
    @(Resolve-InputPath $ClamScanPath)
}
$resolvedClamScan = @($clamCandidates | Where-Object {
    Test-Path -LiteralPath $_ -PathType Leaf
}) | Select-Object -First 1
if (-not $resolvedClamScan -or (Split-Path -Leaf $resolvedClamScan) -ne 'clamscan.exe') {
    throw "Local ClamAV clamscan.exe is unavailable."
}

$databaseExtensions = @('.cvd', '.cld', '.cud', '.ndb', '.ldb', '.hdb', '.hsb', '.mdb', '.msb', '.sfp', '.ign2')
$databaseCandidates = if ([string]::IsNullOrWhiteSpace($ClamDatabaseRoot)) {
    @(
        (Join-Path $securityRoot 'clamav-db')
        (Get-ChildItem -LiteralPath $securityRoot -Recurse -Directory -ErrorAction SilentlyContinue |
            Sort-Object FullName |
            Select-Object -ExpandProperty FullName)
    )
} else {
    @(Resolve-InputPath $ClamDatabaseRoot)
}
$resolvedDatabase = $null
$databaseFiles = @()
foreach ($candidate in @($databaseCandidates | Select-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $candidate -PathType Container)) { continue }
    $candidateFiles = @(Get-ChildItem -LiteralPath $candidate -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $databaseExtensions -contains $_.Extension.ToLowerInvariant() })
    if ($candidateFiles.Count -gt 0) {
        $resolvedDatabase = [System.IO.Path]::GetFullPath($candidate)
        $databaseFiles = $candidateFiles
        break
    }
}
if (-not $resolvedDatabase) { throw "Local ClamAV signature database is unavailable." }

$versionResult = Invoke-NativeCapture $resolvedClamScan @('--version')
$versionOutput = @($versionResult.Output)
$versionExitCode = $versionResult.ExitCode
$version = (($versionOutput -join "`n").Trim() `
    -replace [Regex]::Escape($resolvedClamScan), 'clamscan' `
    -replace '(?i)[a-z]:\\users\\[^\\]+', '%USERPROFILE%')
if ($versionExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($version)) {
    throw "Local ClamAV version check failed."
}

$scanResult = Invoke-NativeCapture $resolvedClamScan @("--database=$resolvedDatabase", '--stdout', $ExecutablePath)
$scanOutput = @($scanResult.Output)
$scanExitCode = $scanResult.ExitCode
$sanitizedOutput = (($scanOutput -join "`n").Trim() `
    -replace [Regex]::Escape($ExecutablePath), '%SINGLE_EXE%' `
    -replace [Regex]::Escape($resolvedDatabase), '%CLAM_DATABASE%' `
    -replace [Regex]::Escape($resolvedClamScan), 'clamscan' `
    -replace '(?i)[a-z]:\\users\\[^\\]+', '%USERPROFILE%')
if ($sanitizedOutput.Length -gt 8000) {
    $sanitizedOutput = $sanitizedOutput.Substring(0, 8000) + "`n[truncated]"
}

$scanStatus = switch ($scanExitCode) {
    0 { 'clean' }
    1 { 'threat-detected' }
    default { 'error' }
}
$signatureStatus = 'Unavailable'
$signatureSigner = $null
try {
    $signature = Get-AuthenticodeSignature -LiteralPath $ExecutablePath -ErrorAction Stop
    $signatureStatus = $signature.Status.ToString()
    $signatureSigner = if ($signature.SignerCertificate) { $signature.SignerCertificate.Subject } else { $null }
} catch {
    $signatureStatus = 'Unavailable'
}
$report = [ordered]@{
    format = 1
    scannedAt = [DateTime]::UtcNow.ToString('o')
    artifact = Split-Path -Leaf $ExecutablePath
    sha256 = $sha256
    bytes = (Get-Item -LiteralPath $ExecutablePath).Length
    integrity = [ordered]@{
        status = 'verified'
        buildReport = 'single-exe-build.json'
        checksumFile = 'SHA256SUMS.txt'
    }
    privacy = [ordered]@{
        localOnly = $true
        uploadedFiles = $false
        uploadedHashes = $false
        containsApiKeys = $false
        containsBaseUrlConfiguration = $false
        containsBridgeToken = $false
        containsLocalState = $false
        containsMinecraftWorlds = $false
        containsBuildMachinePaths = $false
    }
    signature = [ordered]@{
        status = $signatureStatus
        signer = $signatureSigner
    }
    antivirus = [ordered]@{
        engine = 'ClamAV'
        version = $version
        executableSha256 = Get-Sha256Hex -LiteralPath $resolvedClamScan
        databaseFileCount = $databaseFiles.Count
        status = $scanStatus
        exitCode = $scanExitCode
        output = $sanitizedOutput
        localStaticDatabase = $true
        networkCallsInScript = $false
    }
}
$reportPath = Join-Path $outputRoot 'SECURITY-REPORT.json'
[System.IO.File]::WriteAllText(
    $reportPath,
    ($report | ConvertTo-Json -Depth 8),
    [System.Text.UTF8Encoding]::new($false)
)

[PSCustomObject]@{
    Artifact = $report.artifact
    Sha256 = $sha256
    Integrity = $report.integrity.status
    PrivacyLocalOnly = $report.privacy.localOnly
    Antivirus = $scanStatus
    Signature = $report.signature.status
    Report = $reportPath
} | Format-List

if ($scanExitCode -ne 0) {
    throw "Single-EXE antivirus gate failed with ClamAV exit code $scanExitCode."
}
