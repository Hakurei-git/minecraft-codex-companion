$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "launch-hmcl-background.ps1"
$tokens = $null
$errors = $null
[void][Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "PowerShell parser rejected ${scriptPath}: $($errors[0].Message)" }

$source = Get-Content -LiteralPath $scriptPath -Raw -Encoding UTF8
if (-not $source.Contains("MANUAL_UI_REQUIRED")) {
    throw "HMCL test launcher does not fail closed for manual UI"
}
if ($source -match '\b(?:PostMessage|ShowWindow(?:Async)?|SetWindowPos|SetForegroundWindow|ClipCursor|ReleaseCapture|SendInput|SetCursorPos|mouse_event|keybd_event)\s*\(') {
    throw "HMCL test launcher still contains desktop mutation code"
}
Write-Output "HMCL manual-launch safety tests passed"
