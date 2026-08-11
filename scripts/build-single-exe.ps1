[CmdletBinding()]
param(
    [string]$PayloadRoot = "",
    [string]$OutputRoot = "",
    [switch]$BuildPortable,
    [switch]$AllowTestPayload,
    [string]$SigningCertificateThumbprint = $env:MC_COMPANION_SIGNING_CERT_SHA1,
    [string]$TimestampUrl = "",
    [switch]$RequireSignature
)

$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$portableBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "build\portable"))
$portableRoot = [System.IO.Path]::GetFullPath((Join-Path $portableBuildRoot "MinecraftCodexCompanion-Portable"))
$singleBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "build\single-exe"))
$systemTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$targetName = "MinecraftCodexCompanion-Setup.exe"

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-Checked([string]$Command, [string[]]$Arguments, [string]$WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Command exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Assert-PathUnder([string]$Path, [string]$Root, [string]$Label) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not ($fullPath + '\').StartsWith($fullRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must remain under $fullRoot"
    }
}

function Get-RelativePayloadPath([string]$Root, [string]$FullName) {
    return $FullName.Substring($Root.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
}

function Assert-SafeRelativePath([string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or $Relative.Length -gt 1024) {
        throw "Payload contains an empty or overlong path."
    }
    $candidate = $Relative.Replace('\', '/')
    if ($candidate.StartsWith('/') -or $candidate.EndsWith('/') -or $candidate.Contains(':')) {
        throw "Payload contains an unsafe path: $Relative"
    }
    $invalid = [System.IO.Path]::GetInvalidFileNameChars()
    foreach ($part in $candidate.Split('/')) {
        if ([string]::IsNullOrEmpty($part) -or $part -in @('.', '..') -or
            $part.EndsWith('.') -or $part.EndsWith(' ') -or $part.IndexOfAny($invalid) -ge 0) {
            throw "Payload contains an unsafe path segment: $Relative"
        }
        $device = $part.Split('.')[0].ToUpperInvariant()
        if ($device -in @('CON', 'PRN', 'AUX', 'NUL') -or $device -match '^(COM|LPT)[1-9]$') {
            throw "Payload contains a reserved Windows path: $Relative"
        }
    }
    return $candidate
}

function Assert-CleanPayload([string]$Root) {
    $links = @(Get-ChildItem -LiteralPath $Root -Recurse -Force | Where-Object {
        $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint
    })
    if ($links.Count -gt 0) {
        throw "Single-EXE payload contains a reparse point: $($links[0].FullName)"
    }

    $forbiddenNames = @(
        '.env', 'bridge-token.txt', 'launcher-config.json', 'chat-settings.json',
        'ai-providers.json', 'mcp_config.json', 'control-process.json'
    )
    $badFiles = @(Get-ChildItem -LiteralPath $Root -File -Recurse -Force | Where-Object {
        $relative = Get-RelativePayloadPath $Root $_.FullName
        $outsideDependencies = -not $relative.StartsWith('node_modules/', [System.StringComparison]::OrdinalIgnoreCase)
        $outsideDependencies -and (
            $forbiddenNames -contains $_.Name -or
            $_.Name -like '*.log' -or
            $_.Name -like '*.pem' -or
            $_.Name -like '*.key'
        )
    })
    if ($badFiles.Count -gt 0) {
        throw "Single-EXE payload contains a forbidden state or secret file: $($badFiles[0].FullName)"
    }

    $badDirectories = @(Get-ChildItem -LiteralPath $Root -Directory -Recurse -Force | Where-Object {
        $relative = Get-RelativePayloadPath $Root $_.FullName
        -not $relative.StartsWith('node_modules/', [System.StringComparison]::OrdinalIgnoreCase) -and
        $_.Name -in @('saves', 'screenshots', 'logs')
    })
    if ($badDirectories.Count -gt 0) {
        throw "Single-EXE payload contains a private runtime directory: $($badDirectories[0].FullName)"
    }

    $textExtensions = @('.json', '.js', '.mjs', '.cjs', '.css', '.html', '.md', '.ps1', '.txt')
    $textFiles = @(Get-ChildItem -LiteralPath $Root -File -Recurse | Where-Object {
        $relative = Get-RelativePayloadPath $Root $_.FullName
        -not $relative.StartsWith('node_modules/', [System.StringComparison]::OrdinalIgnoreCase) -and
        $textExtensions -contains $_.Extension.ToLowerInvariant()
    })
    $patterns = @(
        [Regex]::Escape($env:USERPROFILE),
        [Regex]::Escape($projectRoot),
        '(?i)(?<![a-z0-9])[a-z]:\\(?:users|documents|desktop|downloads?|appdata|projects?|workspace)(?:\\|$)',
        '(?i)sk-[a-z0-9_-]{16,}',
        '(?i)bearer\s+[a-z0-9._-]{20,}'
    )
    foreach ($pattern in $patterns) {
        if ([string]::IsNullOrEmpty($pattern) -or $textFiles.Count -eq 0) { continue }
        $found = @(Select-String -LiteralPath @($textFiles.FullName) -Pattern $pattern -ErrorAction SilentlyContinue)
        if ($found.Count -gt 0) {
            throw "Single-EXE payload contains forbidden local or secret text in $($found[0].Path)"
        }
    }
}

function Assert-PortableManifest([string]$Root) {
    $manifestPath = Join-Path $Root 'portable-manifest.json'
    Assert-True (Test-Path -LiteralPath $manifestPath -PathType Leaf) "Portable manifest is missing: $manifestPath"
    $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
    Assert-True ($manifest.format -eq 2) "Portable manifest format must be 2."
    Assert-True ($manifest.packaging.model -eq 'transparent-multi-file') "Portable payload model is not recognized."
    Assert-True (-not $manifest.packaging.selfExtracting) "The inner payload manifest is unexpectedly self-extracting."
    Assert-True (-not $manifest.packaging.executableInjection) "The inner payload declares executable injection."
    Assert-True (-not $manifest.packaging.runtimePowerShell) "The inner payload declares runtime PowerShell."
    Assert-True (-not $manifest.packaging.runtimeCommandShell) "The inner payload declares a runtime command shell."
    Assert-True (-not $manifest.privacy.containsApiKeys) "The inner payload may contain API keys."
    Assert-True (-not $manifest.privacy.containsBridgeToken) "The inner payload may contain a bridge token."
    Assert-True (-not $manifest.privacy.containsLocalState) "The inner payload may contain local state."
    Assert-True (-not $manifest.privacy.containsMinecraftWorlds) "The inner payload may contain Minecraft worlds."
    Assert-True (-not $manifest.privacy.containsBuildMachinePaths) "The inner payload may contain build-machine paths."

    $covered = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) {
        $relative = Assert-SafeRelativePath ([string]$entry.path)
        Assert-True ($covered.Add($relative)) "Portable manifest contains a duplicate path: $relative"
        Assert-True (([string]$entry.sha256) -match '^[a-fA-F0-9]{64}$') "Portable manifest contains an invalid SHA-256: $relative"
        $file = Join-Path $Root $relative.Replace('/', '\')
        Assert-True (Test-Path -LiteralPath $file -PathType Leaf) "Portable manifest file is missing: $relative"
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
        Assert-True ($actual -eq ([string]$entry.sha256).ToLowerInvariant()) "Portable manifest hash mismatch: $relative"
    }
    $extra = @(Get-ChildItem -LiteralPath $Root -File -Recurse | Where-Object {
        $relative = Get-RelativePayloadPath $Root $_.FullName
        $relative -ne 'portable-manifest.json' -and -not $covered.Contains($relative)
    })
    if ($extra.Count -gt 0) {
        throw "Portable payload contains a file outside its own manifest: $($extra[0].FullName)"
    }
}

function New-PayloadEntries([string]$Root) {
    $relativePaths = [System.Collections.Generic.List[string]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $Root -File -Recurse) {
        $relativePaths.Add((Assert-SafeRelativePath (Get-RelativePayloadPath $Root $file.FullName)))
    }
    $relativeArray = $relativePaths.ToArray()
    [System.Array]::Sort($relativeArray, [System.StringComparer]::Ordinal)

    $entries = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($relative in $relativeArray) {
        Assert-True ($seen.Add($relative)) "Payload contains a case-insensitive duplicate path: $relative"
        $source = Join-Path $Root $relative.Replace('/', '\')
        $item = Get-Item -LiteralPath $source
        $entries.Add([PSCustomObject][ordered]@{
            path = $relative
            size = [long]$item.Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()
            source = $source
        })
    }
    return @($entries)
}

function Get-PackageId([object[]]$Entries) {
    $memory = [System.IO.MemoryStream]::new()
    try {
        $encoding = [System.Text.UTF8Encoding]::new($false)
        foreach ($entry in $Entries) {
            foreach ($value in @([string]$entry.path, ([long]$entry.size).ToString([System.Globalization.CultureInfo]::InvariantCulture), [string]$entry.sha256)) {
                $bytes = $encoding.GetBytes($value)
                $memory.Write($bytes, 0, $bytes.Length)
                $memory.WriteByte(0)
            }
            # The runtime canonical form terminates the SHA field with LF, not NUL.
            $memory.SetLength($memory.Length - 1)
            $memory.Position = $memory.Length
            $memory.WriteByte(10)
        }
        $memory.Position = 0
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            return ([System.BitConverter]::ToString($sha.ComputeHash($memory))).Replace('-', '').ToLowerInvariant()
        } finally {
            $sha.Dispose()
        }
    } finally {
        $memory.Dispose()
    }
}

function Write-PayloadIndex([object[]]$Entries, [string]$PackageId, [string]$Destination) {
    $publicEntries = @($Entries | ForEach-Object {
        [ordered]@{
            path = $_.path
            size = [long]$_.size
            sha256 = $_.sha256
        }
    })
    $index = [ordered]@{
        format = 1
        packageId = $PackageId
        targetExecutable = 'MinecraftCodexCompanion.exe'
        privacy = [ordered]@{
            containsApiKeys = $false
            containsBaseUrlConfiguration = $false
            containsBridgeToken = $false
            containsLocalState = $false
            containsMinecraftWorlds = $false
            containsBuildMachinePaths = $false
        }
        files = $publicEntries
    }
    [System.IO.File]::WriteAllText(
        $Destination,
        ($index | ConvertTo-Json -Depth 8 -Compress),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Write-DeterministicArchive([object[]]$Entries, [string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression
    $stream = [System.IO.FileStream]::new(
        $Destination,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $true
        )
        try {
            $fixedTime = [System.DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [System.TimeSpan]::Zero)
            foreach ($item in $Entries) {
                $entry = $archive.CreateEntry([string]$item.path, [System.IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $fixedTime
                $entry.ExternalAttributes = 0
                $input = [System.IO.File]::OpenRead([string]$item.source)
                $output = $entry.Open()
                try {
                    $input.CopyTo($output)
                } finally {
                    $output.Dispose()
                    $input.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
        $stream.Flush($true)
    } finally {
        $stream.Dispose()
    }
}

function Assert-Archive([object[]]$Entries, [string]$ArchivePath) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        Assert-True ($archive.Entries.Count -eq $Entries.Count) "Deterministic archive entry count is incorrect."
        $byPath = @{}
        foreach ($entry in $archive.Entries) {
            Assert-True (-not $byPath.ContainsKey($entry.FullName.ToLowerInvariant())) "Deterministic archive contains duplicate paths."
            $byPath[$entry.FullName.ToLowerInvariant()] = $entry
        }
        foreach ($expected in $Entries) {
            $entry = $byPath[[string]$expected.path.ToLowerInvariant()]
            Assert-True ($null -ne $entry) "Deterministic archive is missing $($expected.path)"
            Assert-True ($entry.Length -eq [long]$expected.size) "Deterministic archive size mismatch: $($expected.path)"
            $stream = $entry.Open()
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                $actual = ([System.BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
                Assert-True ($actual -eq [string]$expected.sha256) "Deterministic archive hash mismatch: $($expected.path)"
            } finally {
                $sha.Dispose()
                $stream.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
    }
}

function Find-ByteSequence([byte[]]$Haystack, [byte[]]$Needle) {
    $matches = [System.Collections.Generic.List[int]]::new()
    for ($offset = 0; $offset -le $Haystack.Length - $Needle.Length; $offset++) {
        $same = $true
        for ($index = 0; $index -lt $Needle.Length; $index++) {
            if ($Haystack[$offset + $index] -ne $Needle[$index]) {
                $same = $false
                break
            }
        }
        if ($same) { $matches.Add($offset) }
    }
    return @($matches)
}

function Normalize-ManagedExecutable([string]$Source, [string]$Destination, [string]$DeterministicSeed) {
    $bytes = [System.IO.File]::ReadAllBytes($Source)
    Assert-True ($bytes.Length -gt 512) "Compiled installer is unexpectedly small."
    Assert-True ($bytes[0] -eq 0x4d -and $bytes[1] -eq 0x5a) "Compiled installer is not a PE file."
    $peOffset = [System.BitConverter]::ToInt32($bytes, 0x3c)
    Assert-True ($peOffset -gt 0 -and $peOffset + 12 -lt $bytes.Length) "Compiled installer has an invalid PE header."
    Assert-True ($bytes[$peOffset] -eq 0x50 -and $bytes[$peOffset + 1] -eq 0x45) "Compiled installer lacks a PE signature."

    $assembly = [System.Reflection.Assembly]::ReflectionOnlyLoad($bytes)
    $oldMvid = $assembly.ManifestModule.ModuleVersionId.ToByteArray()
    $mvidOffsets = @(Find-ByteSequence $bytes $oldMvid)
    Assert-True ($mvidOffsets.Count -eq 1) "Could not uniquely locate the managed module identifier."

    # PE/COFF timestamp.
    for ($index = 0; $index -lt 4; $index++) { $bytes[$peOffset + 8 + $index] = 0 }

    $seedBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($DeterministicSeed)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $newMvid = $sha.ComputeHash($seedBytes)[0..15] } finally { $sha.Dispose() }
    # RFC 4122 version/variant bits. Guid.ToByteArray stores the version nibble in byte 7.
    $newMvid[7] = [byte](($newMvid[7] -band 0x0f) -bor 0x40)
    $newMvid[8] = [byte](($newMvid[8] -band 0x3f) -bor 0x80)
    [System.Array]::Copy($newMvid, 0, $bytes, $mvidOffsets[0], 16)
    [System.IO.File]::WriteAllBytes($Destination, $bytes)
}

function Test-BytePattern([byte[]]$Haystack, [byte[]]$Needle) {
    if ($Needle.Length -eq 0) { return $false }
    return @(Find-ByteSequence $Haystack $Needle).Count -gt 0
}

function Assert-NoEmbeddedBuildPaths([string]$Executable) {
    $bytes = [System.IO.File]::ReadAllBytes($Executable)
    $encodings = @([System.Text.Encoding]::UTF8, [System.Text.Encoding]::Unicode)
    foreach ($value in @($projectRoot, $env:USERPROFILE)) {
        if ([string]::IsNullOrWhiteSpace($value)) { continue }
        foreach ($encoding in $encodings) {
            if (Test-BytePattern $bytes $encoding.GetBytes($value)) {
                throw "Single EXE embeds a build-machine path."
            }
        }
    }
}

function Invoke-CodeSign([string]$Executable) {
    if ([string]::IsNullOrWhiteSpace($SigningCertificateThumbprint)) {
        if ($RequireSignature) { throw "A trusted code-signing certificate thumbprint is required." }
        return
    }
    $signtool = (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source
    if (-not $signtool) { throw "signtool.exe is required when signing is configured." }
    $arguments = @('sign', '/sha1', $SigningCertificateThumbprint, '/fd', 'SHA256')
    if (-not [string]::IsNullOrWhiteSpace($TimestampUrl)) {
        $arguments += @('/tr', $TimestampUrl, '/td', 'SHA256')
    }
    $arguments += $Executable
    Invoke-Checked $signtool $arguments $projectRoot
    $signature = Get-AuthenticodeSignature -LiteralPath $Executable
    Assert-True ($signature.Status -eq [System.Management.Automation.SignatureStatus]::Valid) "Installer Authenticode verification failed."
}

if ($BuildPortable) {
    if ($AllowTestPayload) { throw '-BuildPortable cannot be combined with -AllowTestPayload.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-portable.ps1') -SkipArchive
    if ($LASTEXITCODE -ne 0) { throw "Portable build failed with code $LASTEXITCODE" }
}

if ([string]::IsNullOrWhiteSpace($PayloadRoot)) { $PayloadRoot = $portableRoot }
if ([string]::IsNullOrWhiteSpace($OutputRoot)) { $OutputRoot = $singleBuildRoot }
$PayloadRoot = [System.IO.Path]::GetFullPath($PayloadRoot)
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)

if ($AllowTestPayload) {
    Assert-PathUnder $PayloadRoot $systemTempRoot 'Test payload root'
    Assert-PathUnder $OutputRoot $systemTempRoot 'Test output root'
} else {
    Assert-PathUnder $PayloadRoot $portableBuildRoot 'Production payload root'
    Assert-True ((Split-Path -Leaf $PayloadRoot) -eq 'MinecraftCodexCompanion-Portable') "Production payload must use the fixed portable staging directory name."
    Assert-PathUnder $OutputRoot $singleBuildRoot 'Single-EXE output root'
}

Assert-True (Test-Path -LiteralPath $PayloadRoot -PathType Container) "Payload root does not exist: $PayloadRoot"
Assert-CleanPayload $PayloadRoot
Assert-PortableManifest $PayloadRoot
$targetLauncher = Join-Path $PayloadRoot 'MinecraftCodexCompanion.exe'
Assert-True (Test-Path -LiteralPath $targetLauncher -PathType Leaf) "Payload launcher is missing: $targetLauncher"

$entries = @(New-PayloadEntries $PayloadRoot)
Assert-True ($entries.Count -gt 0) "Payload contains no files."
$packageId = Get-PackageId $entries

New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$workRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('mc-codex-single-build-' + [Guid]::NewGuid().ToString('N'))
Assert-PathUnder $workRoot $systemTempRoot 'Temporary build root'
New-Item -ItemType Directory -Path $workRoot -Force | Out-Null

try {
    $indexPath = Join-Path $workRoot 'payload-index.json'
    $archivePath = Join-Path $workRoot 'payload.zip'
    $compiledPath = Join-Path $workRoot $targetName
    $normalizedPath = Join-Path $workRoot 'normalized.exe'
    Write-PayloadIndex $entries $packageId $indexPath
    Write-DeterministicArchive $entries $archivePath
    Assert-Archive $entries $archivePath

    # Ensure no source file changed while it was being archived.
    foreach ($entry in $entries) {
        $currentHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $entry.source).Hash.ToLowerInvariant()
        Assert-True ($currentHash -eq $entry.sha256) "Payload changed during packaging: $($entry.path)"
    }

    $cscCandidates = @(
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
        (Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
    )
    $csc = $cscCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $csc) {
        throw 'No local .NET SDK or .NET Framework C# compiler is available for the one-file bootstrap.'
    }

    $programSource = Join-Path $projectRoot 'apps\single-exe-installer\Program.cs'
    $assemblySource = Join-Path $projectRoot 'apps\single-exe-installer\AssemblyInfo.cs'
    Invoke-Checked $csc @(
        '/nologo', '/target:winexe', '/optimize+', '/debug-',
        '/reference:System.dll',
        '/reference:System.Core.dll',
        '/reference:System.Drawing.dll',
        '/reference:System.Web.Extensions.dll',
        '/reference:System.Windows.Forms.dll',
        '/reference:System.IO.Compression.dll',
        '/reference:System.IO.Compression.FileSystem.dll',
        "/resource:$indexPath,MinecraftCodexCompanion.SingleExe.PayloadIndex,private",
        "/resource:$archivePath,MinecraftCodexCompanion.SingleExe.PayloadArchive,private",
        "/out:$compiledPath",
        $programSource,
        $assemblySource
    ) $projectRoot

    $programHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $programSource).Hash.ToLowerInvariant()
    $assemblyHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $assemblySource).Hash.ToLowerInvariant()
    $indexHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $indexPath).Hash.ToLowerInvariant()
    $archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    $seed = "mc-codex-single-exe-v1`0$packageId`0$programHash`0$assemblyHash`0$indexHash`0$archiveHash"
    Normalize-ManagedExecutable $compiledPath $normalizedPath $seed
    Assert-NoEmbeddedBuildPaths $normalizedPath

    $selfTest = Start-Process -FilePath $normalizedPath -ArgumentList @('--self-test', '--quiet') -WindowStyle Hidden -Wait -PassThru
    Assert-True ($selfTest.ExitCode -eq 0) "Single-EXE embedded payload self-test failed with code $($selfTest.ExitCode)."

    $outputExe = Join-Path $OutputRoot $targetName
    $outputTemp = Join-Path $OutputRoot ('.' + $targetName + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $outputBackup = Join-Path $OutputRoot ('.' + $targetName + '.' + [Guid]::NewGuid().ToString('N') + '.bak')
    $replacementSucceeded = $false
    Copy-Item -LiteralPath $normalizedPath -Destination $outputTemp -Force
    try {
        Invoke-CodeSign $outputTemp
        if (Test-Path -LiteralPath $outputExe -PathType Container) {
            throw "Output executable path is occupied by a directory: $outputExe"
        }
        if (Test-Path -LiteralPath $outputExe -PathType Leaf) {
            $existing = Get-Item -LiteralPath $outputExe -Force
            if ($existing.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                throw "Refusing to replace a reparse point: $outputExe"
            }
            # Windows PowerShell 5.1 rejects a null backup path for this
            # overload. Keep the backup beside the destination so replacement
            # remains atomic on the same volume, then remove it after success.
            [System.IO.File]::Replace($outputTemp, $outputExe, $outputBackup, $true)
            $replacementSucceeded = $true
        } else {
            [System.IO.File]::Move($outputTemp, $outputExe)
            $replacementSucceeded = $true
        }
    } finally {
        if (Test-Path -LiteralPath $outputTemp -PathType Leaf) {
            Remove-Item -LiteralPath $outputTemp -Force
        }
        if ($replacementSucceeded -and (Test-Path -LiteralPath $outputBackup -PathType Leaf)) {
            Remove-Item -LiteralPath $outputBackup -Force
        }
    }

    $finalSelfTest = Start-Process -FilePath $outputExe -ArgumentList @('--self-test', '--quiet') -WindowStyle Hidden -Wait -PassThru
    Assert-True ($finalSelfTest.ExitCode -eq 0) "Published single EXE self-test failed with code $($finalSelfTest.ExitCode)."

    $outputHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputExe).Hash.ToLowerInvariant()
    $signature = Get-AuthenticodeSignature -LiteralPath $outputExe
    $report = [ordered]@{
        format = 1
        artifact = $targetName
        sha256 = $outputHash
        bytes = (Get-Item -LiteralPath $outputExe).Length
        packageId = $packageId
        payloadFiles = $entries.Count
        payloadBytes = ($entries | Measure-Object -Property size -Sum).Sum
        payloadArchiveSha256 = $archiveHash
        installation = [ordered]@{
            scope = 'current-user'
            root = '%LOCALAPPDATA%/MinecraftCodexCompanion/Application'
            atomicReleases = $true
            verifiesEveryFile = $true
            preservesUnknownFiles = $true
            launches = 'MinecraftCodexCompanion.exe'
        }
        privacy = [ordered]@{
            containsApiKeys = $false
            containsBaseUrlConfiguration = $false
            containsBridgeToken = $false
            containsLocalState = $false
            containsMinecraftWorlds = $false
            containsBuildMachinePaths = $false
        }
        reproducibility = [ordered]@{
            deterministicArchiveOrder = $true
            fixedArchiveTimestamps = $true
            normalizedPeTimestamp = $true
            deterministicMvid = $true
        }
        authenticodeStatus = $signature.Status.ToString()
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $OutputRoot 'single-exe-build.json'),
        ($report | ConvertTo-Json -Depth 8),
        [System.Text.UTF8Encoding]::new($false)
    )
    [System.IO.File]::WriteAllText(
        (Join-Path $OutputRoot 'SHA256SUMS.txt'),
        "$outputHash *$targetName`n",
        [System.Text.UTF8Encoding]::new($false)
    )

    [PSCustomObject]@{
        Executable = $outputExe
        Sha256 = $outputHash
        PackageId = $packageId
        PayloadFiles = $entries.Count
        PayloadBytes = ($entries | Measure-Object -Property size -Sum).Sum
        Signature = $signature.Status.ToString()
    } | Format-List
} finally {
    if (Test-Path -LiteralPath $workRoot -PathType Container) {
        $resolvedWork = [System.IO.Path]::GetFullPath($workRoot)
        Assert-PathUnder $resolvedWork $systemTempRoot 'Temporary cleanup root'
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
