$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "stop-minecraft-for-update.ps1"
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "PowerShell parser rejected ${scriptPath}: $($errors[0].Message)" }

$source = Get-Content -LiteralPath $scriptPath -Raw -Encoding UTF8
foreach ($required in @(
    'MANUAL_UI_REQUIRED',
    'Save and Quit',
    'ControlBaseUri must be an HTTP loopback URL'
)) {
    if (-not $source.Contains($required)) {
        throw "Manual Save and Quit guard is missing invariant: $required"
    }
}
if ($source -match '\b(?:PostMessage|ShowWindow(?:Async)?|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event)\s*\(') {
    throw "Manual Save and Quit guard still contains desktop mutation code"
}
Write-Output "Minecraft manual Save and Quit safety tests passed"
