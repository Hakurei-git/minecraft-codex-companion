$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "enter-hmcl-test-world.ps1"

$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$tokens,
    [ref]$errors
)
if ($errors.Count -gt 0) {
    throw "PowerShell parser rejected ${scriptPath}: $($errors[0].Message)"
}

$source = Get-Content -LiteralPath $scriptPath -Raw -Encoding UTF8
foreach ($required in @(
    'EntryPoint = "PostMessageW"',
    '[MinecraftBackgroundInput]::Text($handle, $targetWorldId)',
    'ShowWindowAsync($handle, 6)',
    'ShowWindowAsync($handle, 9)',
    'ClipCursor(IntPtr.Zero)',
    'ReleaseCursorCapture($handle)',
    'IsWindow($handle)',
    'GetWindowThreadProcessId($handle, [ref]$windowProcessId)',
    '[string]$ControlBaseUri = "http://127.0.0.1:8765"',
    '$PSBoundParameters.ContainsKey("ControlBaseUri")',
    '$env:MC_COMPANION_URL',
    '$baseUri = [Uri]$configuredBaseUri',
    '$baseUri.Scheme -ne "http"',
    'Minecraft replaced its loading window without exposing a verified main-menu HWND',
    '$latestLogCandidates = @(',
    '(Join-Path $instancePath "logs\latest.log")',
    '(Join-Path ([string]$config.minecraftRoot) "logs\latest.log")',
    '$logInfo.LastWriteTime -ge $minecraftProcess.StartTime',
    '"neoforge-1.21.1"',
    '"remote-player"',
    "$minecraftProcess.MainWindowTitle -match 'NeoForge|1\.21\.1'",
    '0.425',
    '$playSelectedWorldX = if ($isNeoForgeLayout) { 0.42 } else { 0.31 }',
    'MinimizedAfterWorldSelection = $true',
    'CursorCaptureReleased = $true',
    'SentToBottomAfterWorldSelection = $false'
)) {
    if (-not $source.Contains($required)) {
        throw "Background world entry is missing cursor-release invariant: $required"
    }
}
if ($source.Contains("$targetWorldId -cmatch '^[\x20-\x7e]+$'")) {
    throw "Background world entry still skips localized world names"
}
if ($source.Contains('$keptRunningAtBottom = [MinecraftBackgroundInput]::SetWindowPos')) {
    throw "Background world entry still leaves a restored GLFW window able to retain cursor capture"
}
if ($source.Contains('0x0008')) {
    throw "Background world entry still sends synthetic focus loss to fullscreen GLFW"
}

Write-Output "Minecraft background cursor-release tests passed"
