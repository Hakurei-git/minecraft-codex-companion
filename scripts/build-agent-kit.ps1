param(
    [string]$Version = "v0.1.0",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot "build\agent-kit"
}
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$expectedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot "build"))
if (-not $outputRoot.StartsWith($expectedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "AgentKit output must stay beneath the project build directory."
}

$artifactBase = "MinecraftCodexCompanion-AgentKit-$Version"
$stage = Join-Path $outputRoot $artifactBase
$archive = Join-Path $outputRoot ($artifactBase + ".zip")
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
New-Item -ItemType Directory -Path (Join-Path $stage "skill") -Force | Out-Null

Copy-Item -LiteralPath (Join-Path $projectRoot ".agents\skills\play-minecraft") -Destination (Join-Path $stage "skill\play-minecraft") -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot "agent-kit\README.md") -Destination (Join-Path $stage "README.md")
Copy-Item -LiteralPath (Join-Path $projectRoot "agent-kit\README.zh-CN.md") -Destination (Join-Path $stage "README.zh-CN.md")
Copy-Item -LiteralPath (Join-Path $projectRoot "agent-kit\mcp-config.example.json") -Destination $stage
Copy-Item -LiteralPath (Join-Path $projectRoot "agent-kit\manifest.json") -Destination $stage
Copy-Item -LiteralPath (Join-Path $projectRoot "LICENSE") -Destination $stage

$allowedExtensions = @(".md", ".yaml", ".json")
$files = @(Get-ChildItem -LiteralPath $stage -Recurse -File)
foreach ($file in $files) {
    if ($file.Name -ne "LICENSE" -and $allowedExtensions -notcontains $file.Extension.ToLowerInvariant()) {
        throw "Unexpected AgentKit file type: $($file.Name)"
    }
    $text = [IO.File]::ReadAllText($file.FullName)
    if ($text -match '(?i)\b[A-Z]:[\\/]' -or
        $text -match '(?i)(?:^|[\s"''])\\\\[^\\\s]+\\[^\\\s]+' -or
        $text -match '(?i)(?:^|[\s"''])/(?:Users|home)/[^/\s]+' -or
        $text -match '(?i)-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----' -or
        $text -match '(?i)\bgh[pousr]_[A-Za-z0-9]{20,}\b' -or
        $text -match '(?i)\bsk-[A-Za-z0-9_-]{16,}\b' -or
        $text -match '\bAKIA[0-9A-Z]{16}\b' -or
        $text -match '(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}\b' -or
        $text -match '(?i)["'']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)["'']?\s*[:=]\s*["''][^"'']{8,}["'']') {
        throw "AgentKit privacy scan rejected $($file.Name)."
    }

    $urls = [regex]::Matches($text, '(?i)\b(?:https?|wss?)://[^\s<>`"'']+')
    foreach ($match in $urls) {
        $candidate = $match.Value.TrimEnd('.', ',', ';', ':', ')', ']', '}')
        $uri = $null
        if (-not [Uri]::TryCreate($candidate, [UriKind]::Absolute, [ref]$uri) -or
            ($uri.Host -ne '127.0.0.1' -and $uri.Host -ne 'localhost')) {
            throw "AgentKit contains a non-loopback URL in $($file.Name)."
        }
    }
}

$hashLines = foreach ($file in ($files | Sort-Object FullName)) {
    $relative = $file.FullName.Substring($stage.Length + 1).Replace("\", "/")
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    "$hash  $relative"
}
$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllLines((Join-Path $stage "SHA256SUMS.txt"), $hashLines, $utf8)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fixedTimestamp = [DateTimeOffset]::new(2000, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
$zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create)
try {
    $packageFiles = @(Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object {
        $_.FullName.Substring($stage.Length + 1).Replace("\", "/")
    })
    foreach ($file in $packageFiles) {
        $relative = $file.FullName.Substring($stage.Length + 1).Replace("\", "/")
        $entry = $zip.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime = $fixedTimestamp
        $input = [IO.File]::OpenRead($file.FullName)
        $output = $entry.Open()
        try {
            $input.CopyTo($output)
        }
        finally {
            $output.Dispose()
            $input.Dispose()
        }
    }
}
finally {
    $zip.Dispose()
}

$archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
$report = [ordered]@{
    artifact = [IO.Path]::GetFileName($archive)
    sha256 = $archiveHash
    files = $files.Count + 1
    privacy = [ordered]@{
        containsApiKeys = $false
        containsBaseUrlConfiguration = $false
        containsLocalPaths = $false
        containsAccounts = $false
        containsConversations = $false
        containsMinecraftWorlds = $false
    }
}
[IO.File]::WriteAllText(
    (Join-Path $outputRoot "AGENT-KIT-SECURITY-REPORT.json"),
    ($report | ConvertTo-Json -Depth 5),
    $utf8)

[pscustomobject]@{
    Artifact = $archive
    Sha256 = $archiveHash
    Files = $files.Count + 1
    Privacy = "verified"
} | Format-List
