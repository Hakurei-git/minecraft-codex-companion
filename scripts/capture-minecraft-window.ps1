[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9._-]+\.png$')]
    [string]$Name = "minecraft-window.png",

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Drawing
if (-not ("MinecraftOnlyPrintWindow" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftOnlyPrintWindow
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    public static extern bool IsWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")]
    public static extern bool ClipCursor(IntPtr rect);
    [DllImport("user32.dll")]
    public static extern bool ReleaseCapture();
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern bool PrintWindow(IntPtr hWnd, IntPtr deviceContext, uint flags);
    public static void ReleaseCursorCapture(IntPtr hWnd)
    {
        // Synthetic focus loss minimizes fullscreen GLFW before PrintWindow.
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
    }
}
'@
}

$providedWindow = $MinecraftProcessId -gt 0 -or $NativeWindowHandle -gt 0
$minecraft = $null
if ($providedWindow) {
    if ($MinecraftProcessId -le 0 -or $NativeWindowHandle -le 0) {
        throw "MinecraftProcessId and NativeWindowHandle must be provided together"
    }
    $handle = [IntPtr]$NativeWindowHandle
    if (-not [MinecraftOnlyPrintWindow]::IsWindow($handle)) {
        throw "The verified Minecraft window handle is unavailable"
    }
    [uint32]$windowProcessId = 0
    [void][MinecraftOnlyPrintWindow]::GetWindowThreadProcessId($handle, [ref]$windowProcessId)
    if ($windowProcessId -ne $MinecraftProcessId) {
        throw "The verified Minecraft window handle changed process ownership"
    }
    $resolvedMinecraftProcessId = $MinecraftProcessId
} else {
    $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    foreach ($window in $windows) {
        try {
            if ([string]$window.Current.Name -match '^Minecraft.*Forge 1\.20\.1') {
                $minecraft = $window
                break
            }
        } catch {}
    }
    if ($null -eq $minecraft) { throw "Minecraft Forge window is unavailable" }
    $handle = [IntPtr]$minecraft.Current.NativeWindowHandle
    $resolvedMinecraftProcessId = $minecraft.Current.ProcessId
}
$wasMinimized = [MinecraftOnlyPrintWindow]::IsIconic($handle)
if ($wasMinimized) {
    [void][MinecraftOnlyPrintWindow]::ShowWindowAsync($handle, 9) # SW_RESTORE
    [MinecraftOnlyPrintWindow]::ReleaseCursorCapture($handle)
    Start-Sleep -Milliseconds 500
    [MinecraftOnlyPrintWindow]::ReleaseCursorCapture($handle)
}
try {
    $rect = [MinecraftOnlyPrintWindow+RECT]::new()
    if (-not [MinecraftOnlyPrintWindow]::GetWindowRect($handle, [ref]$rect)) {
        throw "Minecraft window bounds are unavailable"
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -lt 100 -or $height -lt 100) { throw "Minecraft window bounds are invalid" }

    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $deviceContext = $graphics.GetHdc()
    try {
        if (-not [MinecraftOnlyPrintWindow]::PrintWindow($handle, $deviceContext, 2)) {
            throw "Minecraft PrintWindow failed"
        }
    } finally {
        $graphics.ReleaseHdc($deviceContext)
        $graphics.Dispose()
    }
    $runtime = Join-Path (Split-Path -Parent $PSScriptRoot) ".runtime"
    [void](New-Item -ItemType Directory -Path $runtime -Force)
    $output = Join-Path $runtime $Name
    try {
        $bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
} finally {
    [MinecraftOnlyPrintWindow]::ReleaseCursorCapture($handle)
    if ($wasMinimized) {
        [void][MinecraftOnlyPrintWindow]::ShowWindowAsync($handle, 6) # SW_MINIMIZE
    }
    [MinecraftOnlyPrintWindow]::ReleaseCursorCapture($handle)
}

[PSCustomObject]@{
    CapturedMinecraftOnly = $true
    Name = $Name
    Width = $width
    Height = $height
    MinecraftProcessId = $resolvedMinecraftProcessId
    RestoredWithoutActivation = $wasMinimized
    MinimizedAgain = $wasMinimized
    CursorCaptureReleased = $true
}
