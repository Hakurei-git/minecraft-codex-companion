[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9._-]+\.png$')]
    [string]$Name = "minecraft-window.png",

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0
)

$ErrorActionPreference = "Stop"

throw (
    "MANUAL_UI_REQUIRED: automatic Minecraft screenshots are disabled. " +
    "PrintWindow, window restoration, minimization, focus changes, and synthetic F2 input can make GLFW recapture the mouse. " +
    "Capture '$Name' manually while Minecraft is already in the state you want to document."
)
