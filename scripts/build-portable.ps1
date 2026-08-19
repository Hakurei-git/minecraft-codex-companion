[CmdletBinding()]
param(
    [string]$OutputRoot = "",
    [switch]$SkipSourceBuild,
    [switch]$SkipArchive,
    [string]$PinnedForgeJarSha256 = "",
    [string]$OfflineNodeModulesRoot = "",
    [string]$SigningCertificateThumbprint = $env:MC_COMPANION_SIGNING_CERT_SHA1,
    [string]$TimestampUrl = "http://timestamp.digicert.com",
    [switch]$RequireSignature
)

$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot "build\portable"))
if (-not $OutputRoot) {
    $OutputRoot = $buildRoot
} else {
    $OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
}
$allowedPrefix = $buildRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $OutputRoot.Equals($buildRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
    -not $OutputRoot.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Portable output must remain inside $buildRoot"
}

$stage = Join-Path $OutputRoot "MinecraftCodexCompanion-Portable"
$archive = Join-Path $OutputRoot "MinecraftCodexCompanion-Portable-win-x64.zip"

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

function Copy-File([string]$Source, [string]$Destination) {
    $parent = Split-Path -Parent $Destination
    if ($parent) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Copy-Tree([string]$Source, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "Required directory is missing: $Source"
    }
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Assert-OfflineNodeModules(
    [string]$NodeModulesRoot,
    [System.Collections.IDictionary]$ExpectedDependencies
) {
    if (-not (Test-Path -LiteralPath $NodeModulesRoot -PathType Container)) {
        throw "Offline node_modules directory is unavailable: $NodeModulesRoot"
    }
    $resolvedRoot = [System.IO.Path]::GetFullPath($NodeModulesRoot)
    $rootItem = Get-Item -LiteralPath $resolvedRoot -Force
    $links = @($rootItem) + @(Get-ChildItem -LiteralPath $resolvedRoot -Recurse -Force) |
        Where-Object { $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint }
    if (@($links).Count -gt 0) {
        throw "Offline node_modules contains filesystem links or reparse points."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot ".package-lock.json") -PathType Leaf)) {
        throw "Offline node_modules is missing npm installation metadata."
    }
    foreach ($dependency in $ExpectedDependencies.GetEnumerator()) {
        if ([string]$dependency.Value -like "file:*") {
            continue
        }
        $packageRoot = Join-Path $resolvedRoot ([string]$dependency.Key).Replace('/', '\')
        $packageJson = Join-Path $packageRoot "package.json"
        if (-not (Test-Path -LiteralPath $packageJson -PathType Leaf)) {
            throw "Offline dependency is missing: $($dependency.Key)"
        }
        $metadata = Get-Content -Raw -LiteralPath $packageJson | ConvertFrom-Json
        if ([string]$metadata.version -ne [string]$dependency.Value) {
            throw "Offline dependency version mismatch for $($dependency.Key)."
        }
    }
    return $resolvedRoot
}

function Get-SingleFile([string]$Directory, [string]$Filter, [string]$Label) {
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
    if ($files.Count -ne 1) {
        throw "$Label count must be exactly one; found $($files.Count) in $Directory"
    }
    return $files[0].FullName
}

function Assert-CleanPayload([string]$PayloadRoot) {
    $links = @(Get-ChildItem -LiteralPath $PayloadRoot -Recurse -Force | Where-Object {
        $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint
    })
    if ($links.Count -gt 0) {
        throw "Portable payload contains filesystem links or reparse points: $($links.FullName -join ', ')"
    }

    $forbiddenNames = @(
        '.env', 'bridge-token.txt', 'launcher-config.json', 'chat-settings.json',
        'ai-providers.json', 'mcp_config.json', 'control-process.json'
    )
    $badFiles = @(Get-ChildItem -LiteralPath $PayloadRoot -File -Recurse -Force | Where-Object {
        $relative = $_.FullName.Substring($PayloadRoot.Length).TrimStart('\')
        $outsideDependencies = -not $relative.StartsWith('node_modules\', [System.StringComparison]::OrdinalIgnoreCase)
        $outsideDependencies -and (
            $forbiddenNames -contains $_.Name -or
            $_.Name -like '*.log' -or
            $_.Name -like '*.pem' -or
            $_.Name -like '*.key'
        )
    })
    if ($badFiles.Count -gt 0) {
        throw "Portable payload contains forbidden state/secret files: $($badFiles.FullName -join ', ')"
    }

    $badDirectories = @(Get-ChildItem -LiteralPath $PayloadRoot -Directory -Recurse -Force | Where-Object {
        $relative = $_.FullName.Substring($PayloadRoot.Length).TrimStart('\')
        -not $relative.StartsWith('node_modules\', [System.StringComparison]::OrdinalIgnoreCase) -and
        $_.Name -in @('saves', 'screenshots', 'logs')
    })
    if ($badDirectories.Count -gt 0) {
        throw "Portable payload contains private runtime directories: $($badDirectories.FullName -join ', ')"
    }

    $textExtensions = @('.json', '.js', '.mjs', '.cjs', '.css', '.html', '.md', '.ps1', '.txt')
    $textFiles = @(Get-ChildItem -LiteralPath $PayloadRoot -File -Recurse | Where-Object {
        $relative = $_.FullName.Substring($PayloadRoot.Length).TrimStart('\')
        -not $relative.StartsWith('node_modules\', [System.StringComparison]::OrdinalIgnoreCase) -and
        $textExtensions -contains $_.Extension.ToLowerInvariant()
    })
    $forbiddenText = @(
        [Regex]::Escape($env:USERPROFILE),
        [Regex]::Escape($projectRoot),
        '(?i)(?<![a-z0-9])[a-z]:\\(?:users|documents|desktop|downloads?|appdata|projects?|workspace)(?:\\|$)',
        '(?i)sk-[a-z0-9_-]{16,}',
        '(?i)bearer\s+[a-z0-9._-]{20,}'
    )
    foreach ($pattern in $forbiddenText) {
        if (-not $pattern) { continue }
        $matches = @(Select-String -LiteralPath @($textFiles.FullName) -Pattern $pattern -ErrorAction SilentlyContinue)
        if ($matches.Count -gt 0) {
            throw "Portable payload contains forbidden local or secret text '$pattern' in $($matches[0].Path)"
        }
    }
}

function Invoke-CodeSign([string[]]$Executables) {
    if (-not $SigningCertificateThumbprint) {
        if ($RequireSignature) {
            throw "A trusted code-signing certificate thumbprint is required for this release build."
        }
        return
    }
    $signtool = (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source
    if (-not $signtool) {
        throw "signtool.exe is required when a signing certificate is configured."
    }
    foreach ($executable in $Executables) {
        $arguments = @("sign", "/sha1", $SigningCertificateThumbprint, "/fd", "SHA256")
        if ($TimestampUrl) {
            $arguments += @("/tr", $TimestampUrl, "/td", "SHA256")
        }
        $arguments += $executable
        Invoke-Checked $signtool $arguments $projectRoot
        $signature = Get-AuthenticodeSignature -LiteralPath $executable
        if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
            throw "Authenticode verification failed for $executable`: $($signature.StatusMessage)"
        }
    }
}

function Assert-TransparentRuntime([string]$PayloadRoot) {
    $powerShellFiles = @(Get-ChildItem -LiteralPath $PayloadRoot -Recurse -File -Filter "*.ps1" -ErrorAction SilentlyContinue)
    if ($powerShellFiles.Count -gt 0) {
        throw "Portable runtime must not contain PowerShell scripts: $($powerShellFiles[0].FullName)"
    }
    $runtimeText = @(Get-ChildItem -LiteralPath $PayloadRoot -Recurse -File | Where-Object {
        $_.FullName -notlike "*\node_modules\*" -and $_.Extension -in @(".js", ".cjs", ".mjs", ".json")
    })
    foreach ($pattern in @("powershell.exe", "pwsh.exe", "cmd.exe", "NODE_SEA_BLOB", "--experimental-sea-config", "postject")) {
        $found = @(Select-String -LiteralPath @($runtimeText.FullName) -SimpleMatch -Pattern $pattern -ErrorAction SilentlyContinue)
        if ($found.Count -gt 0) {
            throw "Portable runtime contains prohibited shell/injection behavior '$pattern' in $($found[0].Path)"
        }
    }
}

function Get-SignatureEvidence([string]$ExecutablePath, [string]$PayloadRoot) {
    $relative = $ExecutablePath.Substring($PayloadRoot.Length).TrimStart('\').Replace('\', '/')
    try {
        $signature = Get-AuthenticodeSignature -LiteralPath $ExecutablePath -ErrorAction Stop
        return [ordered]@{
            path = $relative
            status = $signature.Status.ToString()
            signer = if ($signature.SignerCertificate) { $signature.SignerCertificate.Subject } else { $null }
        }
    } catch {
        if ($RequireSignature) {
            throw "Authenticode inspection is required but unavailable for $relative`: $($_.Exception.Message)"
        }
        return [ordered]@{
            path = $relative
            status = 'Unavailable'
            signer = $null
        }
    }
}

if (Test-Path -LiteralPath $OutputRoot) {
    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputRoot)
    if (-not $resolvedOutput.Equals($buildRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        -not $resolvedOutput.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove unsafe output path: $resolvedOutput"
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null

if (-not $SkipSourceBuild) {
    Invoke-Checked "npm.cmd" @("run", "build") $projectRoot
}

$forgeBuildStartedAt = [DateTime]::UtcNow
$forgeBuildForced = [string]::IsNullOrWhiteSpace($PinnedForgeJarSha256)
if ($forgeBuildForced) {
    Invoke-Checked "powershell.exe" @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "run-forge-gradle.ps1"),
        "clean",
        "build",
        "--rerun-tasks"
    ) $projectRoot
} elseif ($PinnedForgeJarSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
    throw "PinnedForgeJarSha256 must be one complete SHA-256 digest."
}

$nodeCommand = (Get-Command node.exe -ErrorAction Stop).Source

$bridgeJar = Get-SingleFile (Join-Path $projectRoot "mods\forge-1.20.1\build\libs") "minecraft_codex_bridge-forge-1.20.1-*.jar" "Forge bridge JAR"
$bridgeJarInfo = Get-Item -LiteralPath $bridgeJar
if ($forgeBuildForced -and $bridgeJarInfo.LastWriteTimeUtc -lt $forgeBuildStartedAt) {
    throw "Forge bridge JAR is stale: it was not rebuilt by the current portable build."
}
$bridgeJarHash = Get-Sha256Hex -LiteralPath $bridgeJar
if ($bridgeJarHash -notmatch '^[a-f0-9]{64}$') {
    throw "Forge bridge JAR did not produce a valid SHA-256 digest."
}
if (-not $forgeBuildForced) {
    if (-not $bridgeJarHash.Equals($PinnedForgeJarSha256, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Pinned Forge bridge JAR SHA-256 does not match the requested digest."
    }
    Invoke-Checked "powershell.exe" @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $PSScriptRoot "run-forge-tests-in-process.ps1"),
        "-ExpectedTestCount", "443"
    ) $projectRoot
}
$baritoneJar = Get-SingleFile (Join-Path $projectRoot "vendor\baritone") "baritone-api-forge-1.20.1-*.jar" "Baritone JAR"

Copy-Tree (Join-Path $projectRoot "apps\portable-launcher\ui") (Join-Path $stage "apps\portable-launcher\ui")
Copy-File (Join-Path $projectRoot "apps\portable-launcher\src\launcher.cjs") (Join-Path $stage "apps\portable-launcher\src\launcher.cjs")
Copy-File (Join-Path $projectRoot "apps\portable-launcher\src\instance-manager.cjs") (Join-Path $stage "apps\portable-launcher\src\instance-manager.cjs")
New-Item -ItemType Directory -Path (Join-Path $stage "apps\control-plane\dist") -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $projectRoot "apps\control-plane\dist") -Filter "*.js" -File |
    Where-Object { $_.Name -notlike '*.test.js' } |
    ForEach-Object { Copy-File $_.FullName (Join-Path $stage "apps\control-plane\dist\$($_.Name)") }
Copy-Tree (Join-Path $projectRoot "apps\dashboard\dist") (Join-Path $stage "apps\dashboard\dist")

Copy-File (Join-Path $projectRoot "packages\protocol\package.json") (Join-Path $stage "packages\protocol\package.json")
Copy-File (Join-Path $projectRoot "packages\protocol\dist\index.js") (Join-Path $stage "packages\protocol\dist\index.js")
Copy-File (Join-Path $projectRoot "scripts\mcp-portable-smoke.mjs") (Join-Path $stage "scripts\mcp-portable-smoke.mjs")
$packagedBridgeJar = Join-Path $stage "mods\forge-1.20.1\build\libs\$(Split-Path -Leaf $bridgeJar)"
Copy-File $bridgeJar $packagedBridgeJar
$packagedBridgeJarHash = Get-Sha256Hex -LiteralPath $packagedBridgeJar
if ($packagedBridgeJarHash -ne $bridgeJarHash) {
    throw "Packaged Forge bridge JAR hash does not match the freshly rebuilt artifact."
}
Copy-File $baritoneJar (Join-Path $stage "vendor\baritone\$(Split-Path -Leaf $baritoneJar)")
Copy-File (Join-Path $projectRoot "assets\third_party\queen-cats-dogs\humanoid_cat_white.png") (Join-Path $stage "assets\third_party\queen-cats-dogs\humanoid_cat_white.png")
Copy-File (Join-Path $projectRoot "assets\third_party\queen-cats-dogs\README.md") (Join-Path $stage "assets\third_party\queen-cats-dogs\README.md")
Copy-File (Join-Path $projectRoot "portable-docs\README.md") (Join-Path $stage "README.md")
Copy-File (Join-Path $projectRoot "portable-docs\README.zh-CN.md") (Join-Path $stage "README.zh-CN.md")
Copy-File $nodeCommand (Join-Path $stage "runtime\node.exe")

$cscCandidates = @(
    (Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"),
    (Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe")
)
$csc = $cscCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $csc) {
    throw "The .NET Framework C# compiler is required to build the native desktop client."
}
$appIcon = Join-Path $projectRoot "assets\branding\app-icon.ico"
if (-not (Test-Path -LiteralPath $appIcon -PathType Leaf)) {
    throw "The application icon is missing: assets/branding/app-icon.ico"
}
$clientExe = Join-Path $stage "runtime\MinecraftCodexClient.exe"
Invoke-Checked $csc @(
    "/nologo",
    "/target:winexe",
    "/optimize+",
    "/win32icon:$appIcon",
    "/reference:System.dll",
    "/reference:System.Core.dll",
    "/reference:System.Drawing.dll",
    "/reference:System.Windows.Forms.dll",
    "/reference:System.Web.Extensions.dll",
    "/out:$clientExe",
    (Join-Path $projectRoot "apps\portable-launcher\native\Client.cs"),
    (Join-Path $projectRoot "apps\portable-launcher\native\AssemblyInfo.cs")
) $projectRoot
$clientSelfTest = Join-Path $OutputRoot "client-self-test.txt"
$clientSelfTestProcess = Start-Process `
    -FilePath $clientExe `
    -ArgumentList ('self-test "' + $clientSelfTest + '"') `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if ($clientSelfTestProcess.ExitCode -ne 0) {
    throw "Native desktop client self-test exited with code $($clientSelfTestProcess.ExitCode)"
}
if (-not (Test-Path -LiteralPath $clientSelfTest -PathType Leaf) -or
    (Get-Content -Raw -LiteralPath $clientSelfTest).Trim() -ne "ok") {
    throw "Native desktop client self-test failed"
}
Remove-Item -LiteralPath $clientSelfTest -Force

$clientLayoutTest = Join-Path $OutputRoot "client-layout-self-test.txt"
$clientLayoutTestProcess = Start-Process `
    -FilePath $clientExe `
    -ArgumentList ('layout-self-test "' + $clientLayoutTest + '"') `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if ($clientLayoutTestProcess.ExitCode -ne 0) {
    throw "Native desktop client layout self-test exited with code $($clientLayoutTestProcess.ExitCode)"
}
if (-not (Test-Path -LiteralPath $clientLayoutTest -PathType Leaf) -or
    (Get-Content -Raw -LiteralPath $clientLayoutTest).Trim() -ne "ok") {
    throw "Native desktop client layout self-test failed"
}
Remove-Item -LiteralPath $clientLayoutTest -Force

$pickerExe = Join-Path $stage "runtime\MinecraftCodexPicker.exe"
Invoke-Checked $csc @(
    "/nologo",
    "/target:winexe",
    "/optimize+",
    "/win32icon:$appIcon",
    "/reference:System.dll",
    "/reference:System.Windows.Forms.dll",
    "/out:$pickerExe",
    (Join-Path $projectRoot "apps\portable-launcher\native\Picker.cs"),
    (Join-Path $projectRoot "apps\portable-launcher\native\AssemblyInfo.cs")
) $projectRoot
$pickerSelfTest = Join-Path $OutputRoot "picker-self-test.txt"
$pickerSelfTestProcess = Start-Process `
    -FilePath $pickerExe `
    -ArgumentList ('self-test "' + $pickerSelfTest + '"') `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if ($pickerSelfTestProcess.ExitCode -ne 0) {
    throw "Native path picker self-test exited with code $($pickerSelfTestProcess.ExitCode)"
}
if (-not (Test-Path -LiteralPath $pickerSelfTest -PathType Leaf) -or
    (Get-Content -Raw -LiteralPath $pickerSelfTest).Trim() -ne "ok") {
    throw "Native path picker self-test failed"
}
Remove-Item -LiteralPath $pickerSelfTest -Force

$secretExe = Join-Path $stage "runtime\MinecraftCodexSecret.exe"
Invoke-Checked $csc @(
    "/nologo", "/target:exe", "/optimize+",
    "/reference:System.dll", "/reference:System.Security.dll", "/out:$secretExe",
    (Join-Path $projectRoot "apps\portable-launcher\native\SecretHelper.cs"),
    (Join-Path $projectRoot "apps\portable-launcher\native\AssemblyInfo.cs")
) $projectRoot
$secretSelfTest = Start-Process -FilePath $secretExe -ArgumentList @("self-test") -Wait -PassThru -WindowStyle Hidden
if ($secretSelfTest.ExitCode -ne 0) {
    throw "Native DPAPI helper self-test failed with code $($secretSelfTest.ExitCode)"
}

$launcherExe = Join-Path $stage "MinecraftCodexCompanion.exe"
Invoke-Checked $csc @(
    "/nologo", "/target:winexe", "/optimize+",
    "/win32icon:$appIcon",
    "/reference:System.dll", "/reference:System.Core.dll", "/reference:System.Windows.Forms.dll", "/out:$launcherExe",
    (Join-Path $projectRoot "apps\portable-launcher\native\Bootstrap.cs"),
    (Join-Path $projectRoot "apps\portable-launcher\native\AssemblyInfo.cs")
) $projectRoot

$productionPackage = [ordered]@{
    name = 'minecraft-codex-companion-portable-runtime'
    version = '0.1.8'
    private = $true
    type = 'module'
    dependencies = [ordered]@{
        '@fastify/cors' = '11.3.0'
        '@fastify/static' = '8.3.0'
        '@fastify/websocket' = '11.3.0'
        '@mc/protocol' = 'file:packages/protocol'
        '@modelcontextprotocol/sdk' = '1.30.0'
        '@openai/codex-sdk' = '0.146.0'
        fastify = '5.10.0'
        ws = '8.21.1'
        zod = '4.4.3'
    }
}
[System.IO.File]::WriteAllText(
    (Join-Path $stage "package.json"),
    ($productionPackage | ConvertTo-Json -Depth 10),
    [System.Text.UTF8Encoding]::new($false)
)

$dependencyInstallMode = "npm-install-no-scripts"
if ($OfflineNodeModulesRoot) {
    $verifiedNodeModulesRoot = Assert-OfflineNodeModules $OfflineNodeModulesRoot $productionPackage.dependencies
    Copy-Tree $verifiedNodeModulesRoot (Join-Path $stage "node_modules")
    $dependencyInstallMode = "offline-version-checked-copy"
} else {
    Invoke-Checked "npm.cmd" @("install", "--omit=dev", "--no-audit", "--no-fund", "--ignore-scripts") $stage
}

# npm uses a Junction for a local file dependency on Windows. ZIP archives do not
# carry its target, so replace it with real files before portability checks.
$installedProtocol = Join-Path $stage "node_modules\@mc\protocol"
if (Test-Path -LiteralPath $installedProtocol) {
    $protocolItem = Get-Item -LiteralPath $installedProtocol -Force
    if ($protocolItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        [System.IO.Directory]::Delete($installedProtocol, $false)
    }
}
Copy-Tree (Join-Path $stage "packages\protocol") $installedProtocol

# npm creates PowerShell command shims even when the application never uses them.
# Remove every .ps1 so the published package has no PowerShell execution surface.
Get-ChildItem -LiteralPath $stage -Recurse -File -Filter "*.ps1" -ErrorAction SilentlyContinue |
    Remove-Item -Force

Invoke-CodeSign @($launcherExe, $clientExe, $pickerExe, $secretExe)
Assert-CleanPayload $stage
Assert-TransparentRuntime $stage

$manifest = [ordered]@{
    format = 2
    name = 'Minecraft Codex Companion Portable'
    version = '0.1.8'
    platform = 'win32-x64'
    packaging = [ordered]@{
        model = 'transparent-multi-file'
        selfExtracting = $false
        executableInjection = $false
        runtimePowerShell = $false
        runtimeCommandShell = $false
        dependencyInstallMode = $dependencyInstallMode
    }
    createdAt = [DateTime]::UtcNow.ToString('o')
    forgeArtifact = [ordered]@{
        path = "mods/forge-1.20.1/build/libs/$(Split-Path -Leaf $bridgeJar)"
        buildStartedAt = $forgeBuildStartedAt.ToString('o')
        builtAt = $bridgeJarInfo.LastWriteTimeUtc.ToString('o')
        sha256 = $bridgeJarHash
        packagedSha256 = $packagedBridgeJarHash
        forcedRerun = $forgeBuildForced
        verificationMode = if ($forgeBuildForced) { 'fresh-gradle-clean-rerun' } else { 'pinned-sha256-and-443-tests' }
    }
    signatures = @(@($launcherExe, $clientExe, $pickerExe, $secretExe) | ForEach-Object {
        Get-SignatureEvidence $_ $stage
    })
    buildInputs = @(@(
        'apps/portable-launcher/src/launcher.cjs',
        'apps/portable-launcher/src/instance-manager.cjs',
        'apps/portable-launcher/native/Bootstrap.cs',
        'apps/portable-launcher/native/Client.cs',
        'apps/portable-launcher/native/Picker.cs',
        'apps/portable-launcher/native/SecretHelper.cs',
        'apps/portable-launcher/native/AssemblyInfo.cs',
        'assets/branding/app-icon.ico',
        'scripts/build-portable.ps1',
        'scripts/run-forge-gradle.ps1',
        'scripts/run-forge-tests-in-process.ps1',
        'scripts/forge-in-process-test/InProcessJUnitRunner.java'
    ) | ForEach-Object {
        $source = Join-Path $projectRoot $_
        [ordered]@{
            path = $_.Replace('\', '/')
            sha256 = Get-Sha256Hex -LiteralPath $source
        }
    })
    privacy = [ordered]@{
        containsApiKeys = $false
        containsBridgeToken = $false
        containsLocalState = $false
        containsMinecraftWorlds = $false
        containsBuildMachinePaths = $false
    }
    files = @(Get-ChildItem -LiteralPath $stage -File -Recurse | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($stage.Length).TrimStart('\').Replace('\', '/')
            size = $_.Length
            sha256 = Get-Sha256Hex -LiteralPath $_.FullName
        }
    })
}
[System.IO.File]::WriteAllText(
    (Join-Path $stage "portable-manifest.json"),
    ($manifest | ConvertTo-Json -Depth 10),
    [System.Text.UTF8Encoding]::new($false)
)

$selfTest = Start-Process -FilePath $launcherExe -ArgumentList @("--self-test") -Wait -PassThru -WindowStyle Hidden
if ($selfTest.ExitCode -ne 0) {
    throw "Transparent portable EXE self-test failed with code $($selfTest.ExitCode)"
}

if (-not $SkipArchive) {
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $stage,
        $archive,
        [System.IO.Compression.CompressionLevel]::Optimal,
        $true
    )
    $archiveHash = Get-Sha256Hex -LiteralPath $archive
    [System.IO.File]::WriteAllText(
        (Join-Path $OutputRoot "SHA256SUMS.txt"),
        "$($archiveHash.ToLowerInvariant()) *$(Split-Path -Leaf $archive)`n",
        [System.Text.UTF8Encoding]::new($false)
    )
}

$result = [PSCustomObject]@{
    Directory = $stage
    Executable = $launcherExe
    Archive = if ($SkipArchive) { $null } else { $archive }
    ArchiveSha256 = if ($SkipArchive) { $null } else { $archiveHash }
    ForgeJarSha256 = $bridgeJarHash
    PayloadBytes = (Get-ChildItem -LiteralPath $stage -File -Recurse | Measure-Object -Property Length -Sum).Sum
}
$result | Format-List
