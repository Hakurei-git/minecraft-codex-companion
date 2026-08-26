[CmdletBinding()]
param(
    [ValidateRange(10, 120)]
    [int]$WaitSeconds = 60,

    [ValidateLength(1, 256)]
    [string]$ControlBaseUri = "http://127.0.0.1:8765",

    [ValidateLength(0, 128)]
    [string]$CompanionId = "",

    [ValidateRange(0, 16)]
    [int]$GuiScale = 0,

    [bool]$ForceUnicodeFont = $true,

    [switch]$AsJson
)

$ErrorActionPreference = "Stop"
$controlUri = [Uri]$ControlBaseUri
if ($controlUri.Scheme -ne "http" -or
    $controlUri.Host.ToLowerInvariant() -notin @("127.0.0.1", "localhost", "::1", "[::1]")) {
    throw "ControlBaseUri must be an HTTP loopback URL"
}
throw (
    "MANUAL_UI_REQUIRED: use Minecraft Save and Quit and close the game yourself. " +
    "Automatic pause, menu clicks, window-close messages, minimization, and cursor changes are disabled."
)
