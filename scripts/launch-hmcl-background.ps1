[CmdletBinding()]
param(
    [ValidateRange(30, 300)]
    [int]$WaitSeconds = 120,

    [ValidateRange(0, 1)]
    [int]$EarlyExitRetry = 0,

    [string]$InstallGameRootAlias = "",

    [string]$SandboxProfileRoot = "",

    [string]$QuickPlayWorld = "",

    [switch]$UseInstalledBridgeForDiagnostic
)

$ErrorActionPreference = "Stop"
throw (
    "MANUAL_UI_REQUIRED: start HMCL and Minecraft yourself. " +
    "Automatic game launch, Quick Play, launcher restoration, game-window backgrounding, and cursor release are disabled."
)
