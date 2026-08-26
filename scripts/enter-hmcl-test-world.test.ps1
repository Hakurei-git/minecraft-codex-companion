$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "enter-hmcl-test-world.ps1"

$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    $scriptPath,
    [ref]$tokens,
    [ref]$errors
)
if ($errors.Count -gt 0) {
    throw "PowerShell parser rejected ${scriptPath}: $($errors[0].Message)"
}

$source = Get-Content -LiteralPath $scriptPath -Raw -Encoding UTF8
foreach ($required in @(
    'MANUAL_UI_REQUIRED',
    'api/companions',
    'api/companions/$id/snapshot',
    'ControlBaseUri must be an HTTP loopback URL',
    'ManualWorldEntryObserved = $true',
    'WorldSelectionPosted = $false',
    'MouseOrKeyboardInputUsed = $false',
    'WindowMutationUsed = $false',
    'CursorMutationUsed = $false'
)) {
    if (-not $source.Contains($required)) {
        throw "Manual world-entry waiter is missing invariant: $required"
    }
}
if ($source -match '\b(?:PostMessage|ShowWindow(?:Async)?|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event)\s*\(') {
    throw "Manual world-entry waiter still mutates the Minecraft desktop"
}

Write-Output "Minecraft manual world-entry safety tests passed"
