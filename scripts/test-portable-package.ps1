[CmdletBinding()]
param(
    [string]$Archive = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $Archive) {
    $Archive = Join-Path $projectRoot "build\portable\MinecraftCodexCompanion-Portable-win-x64.zip"
}
$Archive = (Resolve-Path -LiteralPath $Archive).Path
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("mc-codex-portable-test-" + [Guid]::NewGuid().ToString("N"))
$testRoot = [System.IO.Path]::GetFullPath($testRoot)
$tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
if (-not $testRoot.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary test directory escaped the system temp directory"
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Assertion failed: $Message" }
}

function Remove-TestTreeWithRetry([string]$Path) {
    for ($attempt = 1; $attempt -le 20; $attempt++) {
        try {
            Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop
            return
        } catch {
            if ($attempt -eq 20) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
}

function Stop-TestProcessesUnderRoot([string]$Root) {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $processes = @(Get-CimInstance Win32_Process | Where-Object {
            $_.ExecutablePath -and
            $_.ExecutablePath.StartsWith($Root, [System.StringComparison]::OrdinalIgnoreCase)
        })
        foreach ($process in $processes) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($processes.Count -gt 0) { Start-Sleep -Milliseconds 200 }
    } while ($processes.Count -gt 0 -and [DateTime]::UtcNow -lt $deadline)
}

function Write-TestFile([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $Path) -Force | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port } finally { $listener.Stop() }
}

function Wait-ForFile([string]$Path, [int]$Seconds = 15) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $Path -PathType Leaf) { return }
        Start-Sleep -Milliseconds 150
    }
    throw "Timed out waiting for file: $Path"
}

function Wait-ForHealth([int]$Port, [System.Diagnostics.Process]$Process, [string]$ErrorLog, [int]$Seconds = 90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            $detail = if (Test-Path -LiteralPath $ErrorLog) { (Get-Content -Raw -LiteralPath $ErrorLog).Trim() } else { "" }
            throw "Portable control service exited with code $($Process.ExitCode): $detail"
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 1
            if ($health.ok -and $health.service -eq 'minecraft-codex-companion') { return $health }
        } catch {}
        Start-Sleep -Milliseconds 200
    }
    throw "Portable control service did not become healthy"
}

$launcherProcess = $null
$desktopLauncherProcess = $null
$controlProcess = $null
$browseClient = $null
$browseContent = $null
$pickerProcessId = $null
$clientProcessId = $null
$savedState = $env:MC_COMPANION_STATE_DIR
$savedClientTestMutexSuffix = $env:MC_COMPANION_CLIENT_TEST_MUTEX_SUFFIX
try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Archive, $testRoot)
    $packageRoot = Join-Path $testRoot "MinecraftCodexCompanion-Portable"
    $launcherExe = Join-Path $packageRoot "MinecraftCodexCompanion.exe"
    $runtimeNode = Join-Path $packageRoot "runtime\node.exe"
    $nativeClient = Join-Path $packageRoot "runtime\MinecraftCodexClient.exe"
    $nativePicker = Join-Path $packageRoot "runtime\MinecraftCodexPicker.exe"
    Assert-True (Test-Path -LiteralPath $launcherExe -PathType Leaf) "portable EXE is missing"
    Assert-True (Test-Path -LiteralPath $runtimeNode -PathType Leaf) "portable Node runtime is missing"
    Assert-True (Test-Path -LiteralPath $nativeClient -PathType Leaf) "native desktop client is missing"
    Assert-True (Test-Path -LiteralPath $nativePicker -PathType Leaf) "native path picker is missing"

    $clientTestOutput = Join-Path $testRoot "client-self-test.txt"
    $clientSelfTestProcess = Start-Process `
        -FilePath $nativeClient `
        -ArgumentList ('self-test "' + $clientTestOutput + '"') `
        -Wait `
        -PassThru
    Assert-True ($clientSelfTestProcess.ExitCode -eq 0) "native desktop client self-test exited with an error"
    Assert-True (Test-Path -LiteralPath $clientTestOutput -PathType Leaf) "native desktop client did not write its self-test result"
    Assert-True ((Get-Content -Raw -Encoding UTF8 -LiteralPath $clientTestOutput).Trim() -eq 'ok') "native desktop client self-test failed"

    $pickerTestOutput = Join-Path $testRoot "picker-self-test.txt"
    $pickerSelfTestProcess = Start-Process `
        -FilePath $nativePicker `
        -ArgumentList ('self-test "' + $pickerTestOutput + '"') `
        -Wait `
        -PassThru
    Assert-True ($pickerSelfTestProcess.ExitCode -eq 0) "native path picker self-test exited with an error"
    Assert-True (Test-Path -LiteralPath $pickerTestOutput -PathType Leaf) "native path picker did not write its self-test result"
    Assert-True ((Get-Content -Raw -Encoding UTF8 -LiteralPath $pickerTestOutput).Trim() -eq 'ok') "native path picker self-test failed"

    $minecraftRoot = Join-Path $testRoot ".minecraft"
    $sourceVersion = "Fixture-Forge-1.20.1"
    $targetVersion = "$sourceVersion-Codex"
    $source = Join-Path $minecraftRoot "versions\$sourceVersion"
    $fakeLauncher = Join-Path $testRoot "HMCL.exe"
    Write-TestFile $fakeLauncher "fixture launcher"
    Write-TestFile (Join-Path $source "$sourceVersion.jar") "fixture minecraft jar"
    Write-TestFile (Join-Path $source "$sourceVersion.json") (@{
        id = $sourceVersion
        jar = $sourceVersion
        libraries = @(@{ name = 'net.minecraftforge:forge:1.20.1-47.4.21' })
    } | ConvertTo-Json -Depth 10)
    Write-TestFile (Join-Path $source "mods\base-mod.jar") "fixture base mod"
    Write-TestFile (Join-Path $source "saves\private-world\level.dat") "private world"
    Write-TestFile (Join-Path $source "logs\latest.log") "private log"
    Write-TestFile (Join-Path $source "screenshots\private.png") "private screenshot"

    $stateDirectory = Join-Path $testRoot "state"
    New-Item -ItemType Directory -Path (Join-Path $stateDirectory "assets") -Force | Out-Null
    Copy-Item `
        -LiteralPath (Join-Path $packageRoot "assets\third_party\queen-cats-dogs\humanoid_cat_white.png") `
        -Destination (Join-Path $stateDirectory "assets\npc-skin.png")
    $endpointFile = Join-Path $testRoot "launcher-endpoint.json"
    $env:MC_COMPANION_STATE_DIR = $stateDirectory
    $launcherProcess = Start-Process -FilePath $launcherExe -ArgumentList @(
        '--no-open', '--endpoint-file', ('"' + $endpointFile + '"')
    ) -PassThru
    Wait-ForFile $endpointFile
    $endpoint = Get-Content -Raw -Encoding UTF8 -LiteralPath $endpointFile | ConvertFrom-Json
    $page = Invoke-WebRequest -UseBasicParsing -Uri $endpoint.url -TimeoutSec 5
    Assert-True ($page.StatusCode -eq 200) "portable launcher page did not return HTTP 200"
    Assert-True ($page.Content.Contains('Minecraft Codex Companion')) "portable launcher page is not the expected app"
    Assert-True ($page.Content.Contains('id="persona-fields"')) "portable launcher page is missing persona settings"
    Assert-True ($page.Content.Contains('id="companionName"')) "portable launcher page is missing the NPC name field"
    Assert-True ($page.Content.Contains('data-action="choose-skin"')) "portable launcher page is missing the NPC skin picker"
    $session = ([Uri]$endpoint.url).Query.TrimStart('?').Split('&') | Where-Object { $_ -like 'session=*' } | Select-Object -First 1
    $sessionValue = [Uri]::UnescapeDataString($session.Substring('session='.Length))
    $headers = @{ 'x-companion-session' = $sessionValue }
    $baseUrl = ([Uri]$endpoint.url).GetLeftPart([System.UriPartial]::Authority)
    $bootstrap = Invoke-RestMethod -Uri ($baseUrl + '/api/bootstrap') -Headers $headers
    Assert-True $bootstrap.payload.valid "portable launcher rejected its packaged payload"
    Assert-True ($bootstrap.config.persona.mode -eq 'inherit') "portable launcher persona default is incorrect"

    Add-Type -AssemblyName System.Net.Http
    $browseClient = [System.Net.Http.HttpClient]::new()
    $browseClient.DefaultRequestHeaders.Add('x-companion-session', $sessionValue)
    $browseContent = [System.Net.Http.StringContent]::new(
        '{"current":""}',
        [System.Text.Encoding]::UTF8,
        'application/json'
    )
    $browseTask = $browseClient.PostAsync($baseUrl + '/api/browse-launcher', $browseContent)

    $pickerDeadline = [DateTime]::UtcNow.AddSeconds(10)
    $pickerInfo = $null
    while ([DateTime]::UtcNow -lt $pickerDeadline -and -not $pickerInfo) {
        $pickerInfo = Get-CimInstance Win32_Process | Where-Object {
            $_.Name -eq 'MinecraftCodexPicker.exe' -and
            $_.ExecutablePath -and
            $_.ExecutablePath.Equals($nativePicker, [System.StringComparison]::OrdinalIgnoreCase)
        } | Select-Object -First 1
        if (-not $pickerInfo) { Start-Sleep -Milliseconds 150 }
    }
    Assert-True ($null -ne $pickerInfo) "browse API did not start the native path picker"
    $pickerProcessId = $pickerInfo.ProcessId

    Add-Type -AssemblyName UIAutomationClient
    if (-not ("PortablePickerAutomation" -as [type])) {
        Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

public static class PortablePickerAutomation
{
    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
}
"@
    }
    $windowDeadline = [DateTime]::UtcNow.AddSeconds(10)
    $pickerWindow = $null
    $processCondition = New-Object System.Windows.Automation.PropertyCondition -ArgumentList @(
        [System.Windows.Automation.AutomationElement]::ProcessIdProperty,
        [int]$pickerProcessId
    )
    while ([DateTime]::UtcNow -lt $windowDeadline -and -not $pickerWindow) {
        $candidate = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Children, $processCondition)
        if ($candidate -and -not $candidate.Current.IsOffscreen) {
            $bounds = $candidate.Current.BoundingRectangle
            if ($bounds.Width -gt 0 -and $bounds.Height -gt 0) { $pickerWindow = $candidate }
        }
        if (-not $pickerWindow) { Start-Sleep -Milliseconds 150 }
    }
    Assert-True ($null -ne $pickerWindow) "native path picker did not expose a visible window"
    Assert-True (-not [string]::IsNullOrWhiteSpace($pickerWindow.Current.Name)) "native path picker window has no title"
    $pickerWindowHandle = [IntPtr]$pickerWindow.Current.NativeWindowHandle
    Assert-True ($pickerWindowHandle -ne [IntPtr]::Zero) "native path picker window has no native handle"
    $closePosted = [PortablePickerAutomation]::PostMessage(
        $pickerWindowHandle,
        0x0111,
        [IntPtr]2,
        [IntPtr]::Zero
    )
    Assert-True $closePosted "native path picker did not accept the cancel command"
    $pickerProcess = Get-Process -Id $pickerProcessId -ErrorAction SilentlyContinue
    if ($pickerProcess) {
        $pickerProcess.WaitForExit(5000) | Out-Null
        Assert-True $pickerProcess.HasExited "native path picker ignored the cancel command"
    }

    Assert-True ($browseTask.Wait(10000)) "browse API did not complete after the picker was cancelled"
    $browseResponse = $browseTask.Result
    $browseBody = $browseResponse.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    Assert-True $browseResponse.IsSuccessStatusCode "browse API returned an error"
    Assert-True ([string]::IsNullOrEmpty($browseBody.path)) "cancelled picker unexpectedly selected a path"
    $browseResponse.Dispose()
    $browseContent.Dispose()
    $browseContent = $null
    $browseClient.Dispose()
    $browseClient = $null
    $pickerProcessId = $null

    $launcherConfigPort = Get-FreePort
    $saveBody = @{
        config = @{
            launcherPath = $fakeLauncher
            launcherArguments = ""
            minecraftRoot = $minecraftRoot
            sourceVersion = $sourceVersion
            targetVersion = $targetVersion
            playerName = "FixturePlayer"
            companionName = "Luna"
            port = $launcherConfigPort
            freeChatEnabled = $true
            chatTarget = "antigravity-mcp"
            persona = @{
                mode = "custom"
                displayName = "Luna"
                personality = "Calm, curious, and protective."
                speakingStyle = "Warm and concise."
                memoryNotes = "Protect the player's builds."
            }
            npcSkinMode = "custom"
            antigravityConfigPath = (Join-Path $testRoot "antigravity\mcp_config.json")
        }
    } | ConvertTo-Json -Depth 10
    $saved = Invoke-RestMethod -Method Post -Uri ($baseUrl + '/api/save') -Headers $headers -ContentType 'application/json' -Body $saveBody
    Assert-True ($saved.config.companionName -eq 'Luna') "portable launcher did not save the NPC name"
    Assert-True ($saved.config.persona.mode -eq 'custom') "portable launcher did not save the persona mode"
    $chatSettings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $stateDirectory "chat-settings.json") | ConvertFrom-Json
    Assert-True ($chatSettings.version -eq 2) "portable launcher did not write the multi-NPC chat settings format"
    Assert-True ($chatSettings.selectedCompanionName -eq 'Luna') "portable launcher did not select the configured NPC profile"
    $chatProfile = @($chatSettings.profiles) |
        Where-Object { $_.companionName -eq 'Luna' } |
        Select-Object -First 1
    Assert-True ($null -ne $chatProfile) "portable launcher did not create the configured NPC chat profile"
    Assert-True ($chatProfile.target -eq 'antigravity-mcp') "portable launcher did not save the Antigravity chat target"
    Assert-True ($chatProfile.persona.displayName -eq 'Luna') "portable launcher did not write persona settings for the control service"

    $installation = Invoke-RestMethod -Method Post -Uri ($baseUrl + '/api/install') -Headers $headers -ContentType 'application/json' -Body '{}'
    Assert-True ($installation.action -eq 'installed') "portable launcher did not install the isolated instance"
    $target = Join-Path $minecraftRoot "versions\$targetVersion"
    Assert-True (Test-Path -LiteralPath (Join-Path $target "CODEX-CLONE.json")) "clone marker is missing"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $target "saves\private-world"))) "source world leaked into clone"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $target "logs"))) "source logs leaked into clone"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $target "screenshots"))) "source screenshots leaked into clone"
    Assert-True (Test-Path -LiteralPath (Join-Path $stateDirectory "bridge-token.txt")) "target-local bridge token was not generated"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $packageRoot "bridge-token.txt"))) "bridge token was written into the portable package"
    $bridgeSettings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $target "config\minecraft-codex-companion.json") | ConvertFrom-Json
    Assert-True ($bridgeSettings.name -eq 'Luna') "NPC name was not applied to the Forge bridge"
    Assert-True ($bridgeSettings.npcSkinPath -eq 'config/minecraft-codex-companion-skin.png') "custom skin path was not applied to the Forge bridge"
    Assert-True (Test-Path -LiteralPath (Join-Path $target "config\minecraft-codex-companion-skin.png")) "custom NPC skin was not copied into the isolated instance"
    $skinPreview = Invoke-WebRequest -UseBasicParsing -Uri ($baseUrl + '/api/skin-preview') -Headers $headers
    Assert-True ($skinPreview.StatusCode -eq 200 -and $skinPreview.RawContentLength -gt 0) "custom NPC skin preview is unavailable"

    Invoke-RestMethod `
        -Method Post `
        -Uri ($baseUrl + '/api/app/exit') `
        -Headers $headers `
        -ContentType 'application/json' `
        -Body '{}' | Out-Null
    $launcherProcess.WaitForExit(5000) | Out-Null
    Assert-True $launcherProcess.HasExited "portable launcher did not close cleanly"
    $launcherProcess = $null

    $desktopEndpointFile = Join-Path $testRoot "desktop-endpoint.json"
    $env:MC_COMPANION_CLIENT_TEST_MUTEX_SUFFIX = [Guid]::NewGuid().ToString("N")
    $desktopLauncherProcess = Start-Process -FilePath $launcherExe -ArgumentList @(
        '--endpoint-file', ('"' + $desktopEndpointFile + '"')
    ) -PassThru
    Wait-ForFile $desktopEndpointFile
    $clientDeadline = [DateTime]::UtcNow.AddSeconds(15)
    $clientInfo = $null
    while ([DateTime]::UtcNow -lt $clientDeadline -and -not $clientInfo) {
        $clientInfo = Get-CimInstance Win32_Process | Where-Object {
            $_.Name -eq 'MinecraftCodexClient.exe' -and
            $_.ExecutablePath -and
            $_.ExecutablePath.Equals($nativeClient, [System.StringComparison]::OrdinalIgnoreCase)
        } | Select-Object -First 1
        if (-not $clientInfo) { Start-Sleep -Milliseconds 150 }
    }
    Assert-True ($null -ne $clientInfo) "portable launcher did not start the native desktop client"
    $clientProcessId = $clientInfo.ProcessId

    $clientWindowDeadline = [DateTime]::UtcNow.AddSeconds(15)
    $clientWindow = $null
    $clientCondition = New-Object System.Windows.Automation.PropertyCondition -ArgumentList @(
        [System.Windows.Automation.AutomationElement]::ProcessIdProperty,
        [int]$clientProcessId
    )
    while ([DateTime]::UtcNow -lt $clientWindowDeadline -and -not $clientWindow) {
        $candidate = [System.Windows.Automation.AutomationElement]::RootElement.FindFirst([System.Windows.Automation.TreeScope]::Children, $clientCondition)
        if ($candidate -and -not $candidate.Current.IsOffscreen) {
            $bounds = $candidate.Current.BoundingRectangle
            if ($bounds.Width -ge 900 -and $bounds.Height -ge 600) { $clientWindow = $candidate }
        }
        if (-not $clientWindow) { Start-Sleep -Milliseconds 150 }
    }
    Assert-True ($null -ne $clientWindow) "native desktop client did not expose a usable window"
    Assert-True ($clientWindow.Current.Name -eq 'Minecraft Codex Companion') "native desktop client window title is incorrect"
    $clientWindowPattern = $clientWindow.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern)
    $clientWindowPattern.Close()
    $desktopLauncherProcess.WaitForExit(10000) | Out-Null
    Assert-True $desktopLauncherProcess.HasExited "portable launcher stayed alive after the native client closed"
    $desktopLauncherProcess = $null
    $clientProcessId = $null

    $port = Get-FreePort
    $controlState = Join-Path $testRoot "control-state"
    $env:PORT = $port.ToString()
    $env:MC_COMPANION_STATE_DIR = $controlState
    $env:MC_MCP_URL = "http://127.0.0.1:$port/mcp"
    $controlLog = Join-Path $testRoot "control.log"
    $controlErrorLog = Join-Path $testRoot "control-error.log"
    $controlProcess = Start-Process `
        -FilePath $runtimeNode `
        -ArgumentList @('apps/control-plane/dist/server.js') `
        -WorkingDirectory $packageRoot `
        -RedirectStandardOutput $controlLog `
        -RedirectStandardError $controlErrorLog `
        -WindowStyle Hidden `
        -PassThru
    $health = Wait-ForHealth $port $controlProcess $controlErrorLog
    Assert-True ($health.companions -eq 0) "clean control state unexpectedly contains companions"
    $mcpOutput = & $runtimeNode (Join-Path $packageRoot "scripts\mcp-portable-smoke.mjs")
    if ($LASTEXITCODE -ne 0) { throw "Portable MCP smoke test failed" }
    $mcp = $mcpOutput | ConvertFrom-Json
    Assert-True $mcp.ok "MCP tool list is incomplete"
    Assert-True $mcp.replyRequirementVerified "MCP does not enforce player-facing mc_chat replies"
    Stop-Process -Id $controlProcess.Id -Force
    $controlProcess.WaitForExit(5000) | Out-Null
    $controlProcess = $null

    [PSCustomObject]@{
        Archive = $Archive
        ExtractedExecutable = $launcherExe
        NativeDesktopClient = 'visible and passed'
        NativePathPicker = 'visible and passed'
        LauncherPage = 'passed'
        PersonaSettings = 'passed'
        NpcNameAndSkin = 'passed'
        ControlHealth = 'passed'
        McpReplyRule = 'passed'
        IsolatedInstanceInstall = 'passed'
        PrivateSourceDataCopied = $false
    } | Format-List
} finally {
    if ($browseContent) { $browseContent.Dispose() }
    if ($browseClient) { $browseClient.Dispose() }
    if ($pickerProcessId) {
        $pickerInfo = Get-CimInstance Win32_Process | Where-Object {
            $_.ProcessId -eq $pickerProcessId -and
            $_.Name -eq 'MinecraftCodexPicker.exe' -and
            $_.ExecutablePath -and
            $_.ExecutablePath.StartsWith($testRoot, [System.StringComparison]::OrdinalIgnoreCase)
        }
        if ($pickerInfo) { Stop-Process -Id $pickerProcessId -Force -ErrorAction SilentlyContinue }
    }
    if ($clientProcessId) {
        $clientInfo = Get-CimInstance Win32_Process | Where-Object {
            $_.ProcessId -eq $clientProcessId -and
            $_.Name -eq 'MinecraftCodexClient.exe' -and
            $_.ExecutablePath -and
            $_.ExecutablePath.StartsWith($testRoot, [System.StringComparison]::OrdinalIgnoreCase)
        }
        if ($clientInfo) { Stop-Process -Id $clientProcessId -Force -ErrorAction SilentlyContinue }
    }
    if ($launcherProcess -and -not $launcherProcess.HasExited) {
        Stop-Process -Id $launcherProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($desktopLauncherProcess -and -not $desktopLauncherProcess.HasExited) {
        Stop-Process -Id $desktopLauncherProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($controlProcess -and -not $controlProcess.HasExited) {
        Stop-Process -Id $controlProcess.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-TestProcessesUnderRoot $testRoot
    $env:MC_COMPANION_STATE_DIR = $savedState
    $env:MC_COMPANION_CLIENT_TEST_MUTEX_SUFFIX = $savedClientTestMutexSuffix
    if ($testRoot.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $testRoot)) {
        Remove-TestTreeWithRetry $testRoot
    }
}
