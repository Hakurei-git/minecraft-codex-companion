[CmdletBinding()]
param(
    [string]$ArtifactRoot = "",
    [switch]$RequireSignature,
    [switch]$SkipDefender,
    [switch]$AllowUnavailableScanner,
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

$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))

function Get-AbsoluteInputPath([string]$Value, [string]$BasePath) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    if ([System.IO.Path]::IsPathRooted($Value)) {
        return [System.IO.Path]::GetFullPath($Value)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Value))
}

if (-not $ArtifactRoot) {
    $ArtifactRoot = Join-Path $projectRoot "build\portable\MinecraftCodexCompanion-Portable"
}
$ArtifactRoot = [System.IO.Path]::GetFullPath($ArtifactRoot)
$manifestPath = Join-Path $ArtifactRoot "portable-manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Portable manifest not found: $manifestPath"
}

$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
$archivePath = Join-Path (Split-Path -Parent $ArtifactRoot) "MinecraftCodexCompanion-Portable-win-x64.zip"
if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    throw "Portable archive not found: $archivePath"
}
$scanTargets = @($ArtifactRoot, $archivePath)
$archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
$checksumPath = Join-Path (Split-Path -Parent $ArtifactRoot) "SHA256SUMS.txt"
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Portable checksum file not found: $checksumPath"
}
$expectedArchiveHash = ((Get-Content -Raw -Encoding UTF8 -LiteralPath $checksumPath).Trim() -split '\s+')[0].ToLowerInvariant()
if ($expectedArchiveHash -notmatch '^[a-f0-9]{64}$' -or $archiveHash -ne $expectedArchiveHash) {
    throw "Portable archive SHA-256 does not match SHA256SUMS.txt."
}
if ($manifest.format -ne 2 -or $manifest.packaging.model -ne 'transparent-multi-file') {
    throw "Artifact does not use the audited transparent packaging format."
}
if ($manifest.packaging.selfExtracting -or $manifest.packaging.executableInjection -or
    $manifest.packaging.runtimePowerShell -or $manifest.packaging.runtimeCommandShell) {
    throw "Portable manifest declares prohibited executable or shell behavior."
}

$hashFailures = @()
$coveredPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $manifest.files) {
    [void]$coveredPaths.Add(([string]$entry.path).Replace('\', '/'))
    $file = Join-Path $ArtifactRoot ([string]$entry.path).Replace('/', '\')
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        $hashFailures += "missing: $($entry.path)"
        continue
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
    if ($actual -ne ([string]$entry.sha256).ToLowerInvariant()) {
        $hashFailures += "hash mismatch: $($entry.path)"
    }
}
$extraFiles = @(Get-ChildItem -LiteralPath $ArtifactRoot -Recurse -File | Where-Object {
    $relative = $_.FullName.Substring($ArtifactRoot.Length).TrimStart('\').Replace('\', '/')
    $relative -ne 'portable-manifest.json' -and -not $coveredPaths.Contains($relative)
})
if ($extraFiles.Count -gt 0) {
    throw "Payload contains files that are not covered by the manifest: $($extraFiles[0].FullName)"
}
if ($hashFailures.Count -gt 0) {
    throw "Payload integrity verification failed: $($hashFailures -join '; ')"
}

$powerShellFiles = @(Get-ChildItem -LiteralPath $ArtifactRoot -Recurse -File -Filter "*.ps1")
if ($powerShellFiles.Count -gt 0) {
    throw "Published runtime contains PowerShell scripts: $($powerShellFiles[0].FullName)"
}
$textFiles = @(Get-ChildItem -LiteralPath $ArtifactRoot -Recurse -File | Where-Object {
    $_.FullName -notlike "*\node_modules\*" -and $_.Extension -in @('.js', '.cjs', '.mjs', '.json')
})
foreach ($pattern in @('powershell.exe', 'pwsh.exe', 'cmd.exe', 'NODE_SEA_BLOB', '--experimental-sea-config', 'postject')) {
    $found = @(Select-String -LiteralPath @($textFiles.FullName) -SimpleMatch -Pattern $pattern -ErrorAction SilentlyContinue)
    if ($found.Count -gt 0) {
        throw "Published runtime contains prohibited behavior '$pattern': $($found[0].Path)"
    }
}

$executables = @(Get-ChildItem -LiteralPath $ArtifactRoot -Recurse -File -Filter "*.exe")
$signatureReport = @($executables | ForEach-Object {
    $signature = Get-AuthenticodeSignature -LiteralPath $_.FullName
    $relative = $_.FullName.Substring($ArtifactRoot.Length).TrimStart('\').Replace('\', '/')
    [ordered]@{
        path = $relative
        firstParty = $relative -in @(
            'MinecraftCodexCompanion.exe',
            'runtime/MinecraftCodexClient.exe',
            'runtime/MinecraftCodexPicker.exe',
            'runtime/MinecraftCodexSecret.exe'
        )
        status = $signature.Status.ToString()
        signer = if ($signature.SignerCertificate) { $signature.SignerCertificate.Subject } else { $null }
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    }
})
if ($RequireSignature -and @($signatureReport | Where-Object { $_.firstParty -and $_.status -ne 'Valid' }).Count -gt 0) {
    throw "One or more first-party executables do not have a valid Authenticode signature."
}

$clamSecurityRoot = Join-Path $projectRoot ".runtime\security"
$clamAutoRoot = Join-Path $clamSecurityRoot "clamav"
$clamPathExplicit = -not [string]::IsNullOrWhiteSpace($ClamScanPath)
$clamDatabaseExplicit = -not [string]::IsNullOrWhiteSpace($ClamDatabaseRoot)
$clamPathCandidates = if ($clamPathExplicit) {
    @(Get-AbsoluteInputPath $ClamScanPath $projectRoot)
} else {
    $versionedClamRoots = @(Get-ChildItem -LiteralPath $clamSecurityRoot -Directory -Filter "clamav-*" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne "clamav-db" } |
        Sort-Object Name -Descending)
    $autoCandidates = [System.Collections.Generic.List[string]]::new()
    @(
        (Join-Path $clamAutoRoot "clamscan.exe"),
        (Join-Path $clamAutoRoot "bin\clamscan.exe")
    ) | ForEach-Object { $autoCandidates.Add($_) }
    foreach ($versionedRoot in $versionedClamRoots) {
        $autoCandidates.Add((Join-Path $versionedRoot.FullName "clamscan.exe"))
        $autoCandidates.Add((Join-Path $versionedRoot.FullName "bin\clamscan.exe"))
        Get-ChildItem -LiteralPath $versionedRoot.FullName -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object {
                $autoCandidates.Add((Join-Path $_.FullName "clamscan.exe"))
                $autoCandidates.Add((Join-Path $_.FullName "bin\clamscan.exe"))
            }
    }
    @($autoCandidates)
}
$resolvedClamScanPath = $clamPathCandidates |
    Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
    Select-Object -First 1
$clamDetected = $clamPathExplicit -or $clamDatabaseExplicit -or $null -ne $resolvedClamScanPath
$clamSelectionError = $null
if ($clamPathExplicit -and -not $resolvedClamScanPath) {
    $clamSelectionError = "Requested ClamAV clamscan executable does not exist."
} elseif ($resolvedClamScanPath -and (Split-Path -Leaf $resolvedClamScanPath) -notin @('clamscan.exe', 'clamscan')) {
    $clamSelectionError = "ClamScanPath must point to clamscan.exe or clamscan."
}

$resolvedClamDatabaseRoot = $null
$clamDatabaseFiles = @()
if ($clamDetected -and -not $clamSelectionError) {
    $clamBinaryRoot = if ($resolvedClamScanPath) { Split-Path -Parent $resolvedClamScanPath } else { $clamAutoRoot }
    $clamDatabaseCandidates = if ($clamDatabaseExplicit) {
        @(Get-AbsoluteInputPath $ClamDatabaseRoot $projectRoot)
    } else {
        @(
            (Join-Path $clamSecurityRoot "clamav-db"),
            (Join-Path $clamAutoRoot "database"),
            (Join-Path $clamAutoRoot "db"),
            $clamAutoRoot,
            (Join-Path $clamBinaryRoot "database"),
            (Join-Path $clamBinaryRoot "db")
        )
    }
    $databaseExtensions = @('.cvd', '.cld', '.cud', '.ndb', '.ldb', '.hdb', '.hsb', '.mdb', '.msb', '.sfp', '.ign2')
    foreach ($candidate in @($clamDatabaseCandidates | Select-Object -Unique)) {
        if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Container)) { continue }
        $candidateFiles = @(Get-ChildItem -LiteralPath $candidate -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $databaseExtensions -contains $_.Extension.ToLowerInvariant() })
        if ($candidateFiles.Count -gt 0) {
            $resolvedClamDatabaseRoot = [System.IO.Path]::GetFullPath($candidate)
            $clamDatabaseFiles = $candidateFiles
            break
        }
    }
    if (-not $resolvedClamScanPath) {
        $clamSelectionError = "ClamAV database was requested but clamscan could not be found."
    } elseif (-not $resolvedClamDatabaseRoot) {
        $clamSelectionError = "ClamAV signature database is missing or contains no recognized local signature files."
    }
}

$scanner = [ordered]@{
    engine = $null
    status = 'not-run'
    reason = $null
    targets = @()
    engineEvidence = $null
    cleanEvidence = $null
    privacyProof = [ordered]@{
        verified = $false
        method = $null
        networkCallsInScript = $false
        mapsReporting = $null
        submitSamplesConsent = $null
    }
}
$scanFailure = $null

if ($SkipDefender -or $AllowUnavailableScanner) {
    $scanner.engine = 'none'
    $scanner.status = 'rejected'
    $scanner.reason = 'Release antivirus and privacy verification cannot be skipped or downgraded.'
    $scanFailure = $scanner.reason
} else {
    if ($clamDetected) {
        $scanner.engine = 'ClamAV'
        $scanner.privacyProof.method = 'clamav-local-cli-and-static-database'
        if ($clamSelectionError) {
            $scanner.status = 'unavailable'
            $scanner.reason = $clamSelectionError
            $scanFailure = $scanner.reason
        } else {
            try {
                $clamExecutableHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedClamScanPath).Hash.ToLowerInvariant()
                $databaseEvidence = @($clamDatabaseFiles | Sort-Object FullName | ForEach-Object {
                    [ordered]@{
                        name = $_.FullName.Substring($resolvedClamDatabaseRoot.Length).TrimStart('\').Replace('\', '/')
                        size = $_.Length
                        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
                    }
                })
                $versionResult = Invoke-NativeCapture $resolvedClamScanPath @('--version')
                $versionOutput = @($versionResult.Output)
                $versionExitCode = $versionResult.ExitCode
                $clamVersion = (($versionOutput -join "`n").Trim() `
                    -replace [Regex]::Escape($resolvedClamDatabaseRoot), '%CLAM_DATABASE%' `
                    -replace [Regex]::Escape($resolvedClamScanPath), 'clamscan' `
                    -replace '(?i)[a-z]:\\users\\[^\\]+', '%USERPROFILE%')
                $scanner.engineEvidence = [ordered]@{
                    executable = Split-Path -Leaf $resolvedClamScanPath
                    executableSha256 = $clamExecutableHash
                    version = $clamVersion
                    versionExitCode = $versionExitCode
                    database = [ordered]@{
                        root = Split-Path -Leaf $resolvedClamDatabaseRoot
                        fileCount = $databaseEvidence.Count
                        files = $databaseEvidence
                    }
                }
                if ($versionExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($clamVersion)) {
                    $scanner.status = 'unavailable'
                    $scanner.reason = "ClamAV version check failed with exit code $versionExitCode."
                    $scanFailure = $scanner.reason
                }
            } catch {
                $scanner.status = 'unavailable'
                $scanner.reason = 'ClamAV executable or database evidence could not be read and hashed.'
                $scanFailure = $scanner.reason
            }

            if (-not $scanFailure) {
                $targetReports = @()
                foreach ($scanTarget in $scanTargets) {
                    $scanResult = Invoke-NativeCapture $resolvedClamScanPath @(
                        "--database=$resolvedClamDatabaseRoot",
                        '--recursive=yes',
                        '--stdout',
                        $scanTarget
                    )
                    $scanOutput = @($scanResult.Output)
                    $scanExitCode = $scanResult.ExitCode
                    $sanitizedOutput = (($scanOutput -join "`n").Trim() `
                        -replace [Regex]::Escape($ArtifactRoot), '%ARTIFACT_ROOT%' `
                        -replace [Regex]::Escape($archivePath), '%ARCHIVE%' `
                        -replace [Regex]::Escape($resolvedClamDatabaseRoot), '%CLAM_DATABASE%' `
                        -replace [Regex]::Escape($resolvedClamScanPath), 'clamscan' `
                        -replace '(?i)[a-z]:\\users\\[^\\]+', '%USERPROFILE%')
                    if ($sanitizedOutput.Length -gt 8000) {
                        $sanitizedOutput = $sanitizedOutput.Substring(0, 8000) + "`n[truncated]"
                    }
                    $targetStatus = switch ($scanExitCode) {
                        0 { 'clean' }
                        1 { 'infected' }
                        2 { 'error' }
                        default { 'unexpected-exit' }
                    }
                    $targetReports += [ordered]@{
                        target = if ($scanTarget -eq $ArtifactRoot) { 'payload-directory' } else { 'release-archive' }
                        exitCode = $scanExitCode
                        status = $targetStatus
                        output = $sanitizedOutput
                    }
                }
                $scanner.targets = @($targetReports)
                $cleanTargetCount = @($targetReports | Where-Object { $_.exitCode -eq 0 }).Count
                $infectedTargetCount = @($targetReports | Where-Object { $_.exitCode -eq 1 }).Count
                $scanner.cleanEvidence = [ordered]@{
                    requiredTargetCount = 2
                    scannedTargetCount = $targetReports.Count
                    cleanTargetCount = $cleanTargetCount
                    allTargetsClean = $targetReports.Count -eq 2 -and $cleanTargetCount -eq 2
                }
                if ($scanner.cleanEvidence.allTargetsClean) {
                    $scanner.status = 'clean'
                    $scanner.reason = 'Payload directory and release archive both returned ClamAV exit code 0 using the recorded local database.'
                    $scanner.privacyProof.verified = $true
                } elseif ($infectedTargetCount -gt 0) {
                    $scanner.status = 'threat-detected'
                    $scanner.reason = 'ClamAV returned exit code 1 for one or more release targets.'
                    $scanFailure = $scanner.reason
                } else {
                    $scanner.status = 'unavailable'
                    $scanner.reason = 'ClamAV returned exit code 2 or an unexpected exit code for one or more release targets.'
                    $scanFailure = $scanner.reason
                }
            }
        }
    } else {
    $kasperskyService = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'AVP*' -and $_.State -eq 'Running' -and $_.PathName -match '(?i)avp\.exe' } |
        Select-Object -First 1
    if ($kasperskyService) {
        $scanner.engine = 'Kaspersky'
        $scanner.status = 'privacy-unverified'
        # The official CLI can mutate KSN consent with AcceptEULA ksnoff, but
        # cannot read it back. Do not change a machine-wide antivirus setting.
        $scanner.reason = 'Kaspersky avp.com exposes AcceptEULA ksnoff only as a state-changing command and provides no read-only KSN status check; local-only scanning cannot be proven without changing antivirus settings.'
        $scanner.privacyProof.method = 'fail-closed-no-read-only-ksn-proof'
        $scanFailure = $scanner.reason
    } else {
        $scanner.engine = 'Microsoft Defender'
        $scanner.privacyProof.method = 'defender-policy-and-local-cli'
        $defenderService = Get-Service -Name WinDefend -ErrorAction SilentlyContinue
        if (-not $defenderService -or $defenderService.Status -ne 'Running') {
            $scanner.status = 'unavailable'
            $scanner.reason = 'Microsoft Defender service is not running.'
            $scanFailure = $scanner.reason
        } elseif (-not (Get-Command Get-MpPreference -ErrorAction SilentlyContinue)) {
            $scanner.status = 'unavailable'
            $scanner.reason = 'Get-MpPreference is unavailable, so Defender cloud and sample-submission policy cannot be verified.'
            $scanFailure = $scanner.reason
        } else {
            try {
                $preference = Get-MpPreference -ErrorAction Stop
                $mapsReporting = [int]$preference.MAPSReporting
                $submitSamplesConsent = [int]$preference.SubmitSamplesConsent
                $scanner.privacyProof.mapsReporting = $mapsReporting
                $scanner.privacyProof.submitSamplesConsent = $submitSamplesConsent
                if ($mapsReporting -ne 0 -or $submitSamplesConsent -ne 2) {
                    $scanner.status = 'privacy-unverified'
                    $scanner.reason = 'Defender local-only policy is not enforced: MAPSReporting must be Disabled (0) and SubmitSamplesConsent must be NeverSend (2).'
                    $scanFailure = $scanner.reason
                }
            } catch {
                $scanner.status = 'privacy-unverified'
                $scanner.reason = 'Defender privacy policy could not be read without error.'
                $scanFailure = $scanner.reason
            }
        }

        $platformRoot = Join-Path $env:ProgramData "Microsoft\Windows Defender\Platform"
        $mpCmdRun = Get-ChildItem -LiteralPath $platformRoot -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "MpCmdRun.exe" } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if (-not $scanFailure -and -not $mpCmdRun) {
            $scanner.status = 'unavailable'
            $scanner.reason = 'Microsoft Defender command-line scanner is unavailable.'
            $scanFailure = $scanner.reason
        }

        if (-not $scanFailure) {
            $targetReports = @()
            foreach ($scanTarget in $scanTargets) {
                $scanOutput = @(& $mpCmdRun -Scan -ScanType 3 -File $scanTarget -DisableRemediation 2>&1)
                $scanExitCode = $LASTEXITCODE
                $sanitizedOutput = (($scanOutput -join "`n").Trim() `
                    -replace [Regex]::Escape($ArtifactRoot), '%ARTIFACT_ROOT%' `
                    -replace [Regex]::Escape($archivePath), '%ARCHIVE%' `
                    -replace '(?i)[a-z]:\\users\\[^\\]+', '%USERPROFILE%')
                $targetReports += [ordered]@{
                    target = if ($scanTarget -eq $ArtifactRoot) { 'payload-directory' } else { 'release-archive' }
                    exitCode = $scanExitCode
                    output = $sanitizedOutput
                }
                if ($scanExitCode -eq 0) { continue }
                if ($scanExitCode -eq 2 -and $sanitizedOutput -notmatch '(?i)failed\s+with\s+hr|error\s+0x') {
                    $scanner.status = 'threat-detected'
                    $scanner.reason = 'Microsoft Defender detected a threat in the portable artifact.'
                } else {
                    $scanner.status = 'unavailable'
                    $scanner.reason = "Microsoft Defender scan failed with exit code $scanExitCode."
                }
                $scanFailure = $scanner.reason
                break
            }
            $scanner.targets = @($targetReports)
            if (-not $scanFailure) {
                $scanner.status = 'clean'
                $scanner.reason = 'Directory and release archive passed Defender with cloud reporting disabled and sample submission set to NeverSend.'
                $scanner.privacyProof.verified = $true
            }
        }
    }
    }
}

$localOnlyVerified = $scanner.status -eq 'clean' -and [bool]$scanner.privacyProof.verified

$report = [ordered]@{
    format = 1
    scannedAt = [DateTime]::UtcNow.ToString('o')
    artifact = Split-Path -Leaf $ArtifactRoot
    archive = [ordered]@{
        name = Split-Path -Leaf $archivePath
        sha256 = $archiveHash
    }
    privacy = [ordered]@{
        localOnly = $localOnlyVerified
        uploadedFiles = if ($localOnlyVerified) { $false } else { $null }
        uploadedHashes = if ($localOnlyVerified) { $false } else { $null }
        proof = $scanner.privacyProof
    }
    integrity = [ordered]@{ status = 'verified'; files = @($manifest.files).Count }
    runtimeAudit = [ordered]@{ powerShell = $false; commandShell = $false; selfExtracting = $false; executableInjection = $false }
    signatures = $signatureReport
    antivirus = $scanner
}
$reportPath = Join-Path (Split-Path -Parent $ArtifactRoot) "SECURITY-REPORT.json"
$reportJson = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText(
    $reportPath,
    $reportJson,
    [System.Text.UTF8Encoding]::new($false)
)
$reportJson
if ($scanFailure) {
    throw "Portable antivirus gate failed closed: $scanFailure Report: $reportPath"
}
