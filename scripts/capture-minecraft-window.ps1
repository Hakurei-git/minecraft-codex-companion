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
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }
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
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")]
    public static extern bool GetCursorPos(out POINT point);
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern bool ClipCursor(IntPtr rect);
    [DllImport("user32.dll")]
    public static extern bool ReleaseCapture();
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern bool PrintWindow(IntPtr hWnd, IntPtr deviceContext, uint flags);
    public static IntPtr FindLargestWindowForProcess(uint processId)
    {
        IntPtr best = IntPtr.Zero;
        long bestArea = 0;
        EnumWindows((candidate, ignored) =>
        {
            uint owner;
            RECT rect;
            if (GetWindowThreadProcessId(candidate, out owner) == 0 || owner != processId
                || !GetWindowRect(candidate, out rect)) return true;
            long width = Math.Max(0, rect.Right - rect.Left);
            long height = Math.Max(0, rect.Bottom - rect.Top);
            long area = width * height;
            if (width >= 640 && height >= 360 && area > bestArea)
            {
                best = candidate;
                bestArea = area;
            }
            return true;
        }, IntPtr.Zero);
        return best;
    }
    public static void ReleaseCursorCapture(IntPtr hWnd)
    {
        // Synthetic focus loss minimizes fullscreen GLFW before PrintWindow.
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
    }
}
'@
}

function Get-DesktopInteractionSnapshot {
    $point = [MinecraftOnlyPrintWindow+POINT]::new()
    if (-not [MinecraftOnlyPrintWindow]::GetCursorPos([ref]$point)) {
        throw "The physical cursor position could not be audited"
    }
    return [PSCustomObject]@{
        ForegroundWindow = [MinecraftOnlyPrintWindow]::GetForegroundWindow().ToInt64()
        CursorX = $point.X
        CursorY = $point.Y
    }
}

function Test-MinecraftWindowOwnership([IntPtr]$Candidate, [int]$ExpectedProcessId) {
    if ($Candidate -eq [IntPtr]::Zero -or
        -not [MinecraftOnlyPrintWindow]::IsWindow($Candidate)) {
        return $false
    }
    [uint32]$ownerProcessId = 0
    return [MinecraftOnlyPrintWindow]::GetWindowThreadProcessId(
        $Candidate,
        [ref]$ownerProcessId
    ) -ne 0 -and $ownerProcessId -eq $ExpectedProcessId
}

function Resolve-VerifiedMinecraftWindowHandle(
    [int]$ExpectedProcessId,
    [IntPtr]$PreferredHandle
) {
    $process = Get-Process -Id $ExpectedProcessId -ErrorAction Stop
    if ($process.ProcessName -notin @("java", "javaw")) {
        throw "The verified Minecraft process is not a Java runtime"
    }
    if (Test-MinecraftWindowOwnership $PreferredHandle $ExpectedProcessId) {
        return $PreferredHandle
    }
    $replacement = [MinecraftOnlyPrintWindow]::FindLargestWindowForProcess(
        [uint32]$ExpectedProcessId
    )
    if (-not (Test-MinecraftWindowOwnership $replacement $ExpectedProcessId)) {
        throw "Minecraft did not expose a current verified window handle"
    }
    return $replacement
}

function Assert-DesktopInteractionPreserved($Before, [IntPtr]$MinecraftHandle) {
    $after = Get-DesktopInteractionSnapshot
    if ($before.ForegroundWindow -ne $after.ForegroundWindow -or
        $before.CursorX -ne $after.CursorX -or
        $before.CursorY -ne $after.CursorY) {
        throw "Minecraft-only capture changed the foreground window or physical cursor position"
    }
    if ($after.ForegroundWindow -eq $MinecraftHandle.ToInt64()) {
        throw "Minecraft-only capture activated the Minecraft window"
    }
}

function Set-MinecraftWindowStateWithoutInteraction(
    [IntPtr]$CurrentHandle,
    [int]$ExpectedProcessId,
    [int]$ShowCommand,
    [bool]$ExpectedIconic
) {
    $verifiedHandle = Resolve-VerifiedMinecraftWindowHandle `
        $ExpectedProcessId `
        $CurrentHandle
    $before = Get-DesktopInteractionSnapshot
    if ($before.ForegroundWindow -eq $verifiedHandle.ToInt64()) {
        throw "Minecraft is foreground; background capture stopped to avoid user interaction"
    }
    [void][MinecraftOnlyPrintWindow]::ShowWindowAsync($verifiedHandle, $ShowCommand)
    [MinecraftOnlyPrintWindow]::ReleaseCursorCapture($verifiedHandle)
    $deadline = (Get-Date).AddSeconds(3)
    do {
        Start-Sleep -Milliseconds 50
        $verifiedHandle = Resolve-VerifiedMinecraftWindowHandle `
            $ExpectedProcessId `
            $verifiedHandle
        $isIconic = [MinecraftOnlyPrintWindow]::IsIconic($verifiedHandle)
    } while ($isIconic -ne $ExpectedIconic -and (Get-Date) -lt $deadline)
    if ($isIconic -ne $ExpectedIconic) {
        throw "Minecraft window did not reach the requested background state"
    }
    Start-Sleep -Milliseconds 100
    Assert-DesktopInteractionPreserved $before $verifiedHandle
    return $verifiedHandle
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
            if ([string]$window.Current.Name -match '^Minecraft.*(?:Forge 1\.20\.1|NeoForge|1\.21\.1)') {
                $minecraft = $window
                break
            }
        } catch {}
    }
    if ($null -eq $minecraft) { throw "Minecraft Forge window is unavailable" }
    $handle = [IntPtr]$minecraft.Current.NativeWindowHandle
    $resolvedMinecraftProcessId = $minecraft.Current.ProcessId
}
$handle = Resolve-VerifiedMinecraftWindowHandle $resolvedMinecraftProcessId $handle
$initialInteraction = Get-DesktopInteractionSnapshot
if ($initialInteraction.ForegroundWindow -eq $handle.ToInt64()) {
    throw "Minecraft is foreground; background capture stopped to avoid user interaction"
}
$wasMinimized = [MinecraftOnlyPrintWindow]::IsIconic($handle)
if ($wasMinimized) {
    $handle = Set-MinecraftWindowStateWithoutInteraction `
        $handle `
        $resolvedMinecraftProcessId `
        4 `
        $false # SW_SHOWNOACTIVATE
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
    $captureInteraction = Get-DesktopInteractionSnapshot
    try {
        if (-not [MinecraftOnlyPrintWindow]::PrintWindow($handle, $deviceContext, 2)) {
            throw "Minecraft PrintWindow failed"
        }
        Assert-DesktopInteractionPreserved $captureInteraction $handle
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
    if ($wasMinimized) {
        $handle = Set-MinecraftWindowStateWithoutInteraction `
            $handle `
            $resolvedMinecraftProcessId `
            6 `
            $true # SW_MINIMIZE
    }
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
    ForegroundWindowPreserved = $true
    CursorPositionPreserved = $true
    ForegroundInteractionUsed = $false
    PhysicalMouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
}
