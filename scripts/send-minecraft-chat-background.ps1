[CmdletBinding(DefaultParameterSetName = "PlainText")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "PlainText")]
    [ValidateLength(1, 256)]
    [string]$Message,

    [Parameter(Mandatory = $true, ParameterSetName = "Utf8Base64")]
    [ValidatePattern('^[A-Za-z0-9+/]+={0,2}$')]
    [string]$MessageUtf8Base64,

    [Parameter(Mandatory = $true, ParameterSetName = "NormalizeOnly")]
    [switch]$NormalizeOnly,

    [ValidateLength(1, 256)]
    [string]$ControlBaseUri = "http://127.0.0.1:8765",

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0,

    [switch]$DraftOnly,

    [switch]$RespawnIfDead,

    [switch]$RecoverPlayerNearNpcForTest
)

$ErrorActionPreference = "Stop"

if ($PSCmdlet.ParameterSetName -eq "Utf8Base64") {
    try {
        $Message = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($MessageUtf8Base64))
    } catch {
        throw "Minecraft chat text is not valid UTF-8 Base64"
    }
    if ($Message.Length -lt 1 -or $Message.Length -gt 256) {
        throw "Minecraft chat text must contain between 1 and 256 characters"
    }
}

$controlUri = [Uri]$ControlBaseUri
if ($controlUri.Scheme -ne "http" -or
    $controlUri.Host.ToLowerInvariant() -notin @("127.0.0.1", "localhost", "::1", "[::1]")) {
    throw "ControlBaseUri must be an HTTP loopback URL"
}

if ($NormalizeOnly -and -not $RespawnIfDead -and -not $RecoverPlayerNearNpcForTest) {
    [PSCustomObject]@{
        Sent = $false
        NormalizeOnly = $true
        ManualUiRequired = $false
        MouseOrKeyboardInputUsed = $false
        WindowMutationUsed = $false
        CursorMutationUsed = $false
    }
    return
}

$manualAction = if ($RespawnIfDead) {
    "If the death screen is visible, click Respawn yourself; then press T, type the requested message, and press Enter."
} elseif ($DraftOnly) {
    "Press T and type the requested message yourself without submitting it."
} else {
    "Press T, type the requested message yourself, and press Enter."
}

throw (
    "MANUAL_UI_REQUIRED: automatic Minecraft T-chat, respawn, and UI normalization are disabled because synthetic window messages can make GLFW recapture the mouse. " +
    $manualAction + " Requested text: " + $Message
)
