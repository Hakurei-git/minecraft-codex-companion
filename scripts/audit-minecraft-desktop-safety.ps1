[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$MinecraftProcessId,

    [long]$NativeWindowHandle = 0,

    [ValidateRange(0, 3600)]
    [int]$DurationSeconds = 0,

    [ValidateRange(50, 5000)]
    [int]$SampleMilliseconds = 250,

    [switch]$RequireMinecraftBackground,

    [switch]$AsJson
)

$ErrorActionPreference = "Stop"

if (-not ("MinecraftDesktopReadOnlyAudit" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftDesktopReadOnlyAudit
{
    public delegate bool EnumWindowsProc(IntPtr window, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern bool IsWindow(IntPtr window);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr window, out RECT rectangle);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    public static extern bool GetClipCursor(out RECT rectangle);

    [DllImport("user32.dll")]
    public static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll")]
    public static extern int GetSystemMetrics(int index);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr64(IntPtr window, int index);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongW")]
    private static extern int GetWindowLong32(IntPtr window, int index);

    public static IntPtr FindLargestWindowForProcess(uint processId)
    {
        IntPtr best = IntPtr.Zero;
        long bestArea = 0;
        EnumWindows((candidate, ignored) =>
        {
            uint owner;
            RECT rectangle;
            if (!IsWindowVisible(candidate)
                || GetWindowThreadProcessId(candidate, out owner) == 0
                || owner != processId
                || !GetWindowRect(candidate, out rectangle)) return true;
            long width = Math.Max(0, rectangle.Right - rectangle.Left);
            long height = Math.Max(0, rectangle.Bottom - rectangle.Top);
            long area = width * height;
            if (area > bestArea)
            {
                best = candidate;
                bestArea = area;
            }
            return true;
        }, IntPtr.Zero);
        return best;
    }

    public static bool IsTopmost(IntPtr window)
    {
        const int GWL_EXSTYLE = -20;
        const long WS_EX_TOPMOST = 0x00000008L;
        long style = IntPtr.Size == 8
            ? GetWindowLongPtr64(window, GWL_EXSTYLE).ToInt64()
            : GetWindowLong32(window, GWL_EXSTYLE);
        return (style & WS_EX_TOPMOST) != 0;
    }
}
'@
}

$process = Get-Process -Id $MinecraftProcessId -ErrorAction Stop
if ($process.ProcessName -notin @("java", "javaw")) {
    throw "The audited Minecraft process is not a Java runtime"
}

$window = if ($NativeWindowHandle -ne 0) {
    [IntPtr]$NativeWindowHandle
} elseif ($process.MainWindowHandle -ne [IntPtr]::Zero) {
    [IntPtr]$process.MainWindowHandle
} else {
    [MinecraftDesktopReadOnlyAudit]::FindLargestWindowForProcess([uint32]$MinecraftProcessId)
}
if ($window -eq [IntPtr]::Zero -or -not [MinecraftDesktopReadOnlyAudit]::IsWindow($window)) {
    throw "The audited Minecraft window is unavailable"
}
[uint32]$windowProcessId = 0
if ([MinecraftDesktopReadOnlyAudit]::GetWindowThreadProcessId($window, [ref]$windowProcessId) -eq 0 -or
    $windowProcessId -ne $MinecraftProcessId) {
    throw "The audited window does not belong to the Minecraft process"
}

$virtualLeft = [MinecraftDesktopReadOnlyAudit]::GetSystemMetrics(76)
$virtualTop = [MinecraftDesktopReadOnlyAudit]::GetSystemMetrics(77)
$virtualRight = $virtualLeft + [MinecraftDesktopReadOnlyAudit]::GetSystemMetrics(78)
$virtualBottom = $virtualTop + [MinecraftDesktopReadOnlyAudit]::GetSystemMetrics(79)
$deadline = [DateTime]::UtcNow.AddSeconds($DurationSeconds)
$samples = 0
$minecraftForegroundObserved = $false
$backgroundCursorConfinementObserved = $false
$topmostObserved = $false
$firstCursor = $null
$lastCursor = $null

do {
    $clip = [MinecraftDesktopReadOnlyAudit+RECT]::new()
    $cursor = [MinecraftDesktopReadOnlyAudit+POINT]::new()
    if (-not [MinecraftDesktopReadOnlyAudit]::GetClipCursor([ref]$clip)) {
        throw "The desktop cursor confinement could not be audited"
    }
    if (-not [MinecraftDesktopReadOnlyAudit]::GetCursorPos([ref]$cursor)) {
        throw "The desktop cursor position could not be audited"
    }
    if ($null -eq $firstCursor) {
        $firstCursor = [PSCustomObject]@{ X = $cursor.X; Y = $cursor.Y }
    }
    $lastCursor = [PSCustomObject]@{ X = $cursor.X; Y = $cursor.Y }
    $foreground = [MinecraftDesktopReadOnlyAudit]::GetForegroundWindow()
    $minecraftForeground = $foreground -eq $window
    $cursorConfined = -not (
        $clip.Left -eq $virtualLeft -and
        $clip.Top -eq $virtualTop -and
        $clip.Right -eq $virtualRight -and
        $clip.Bottom -eq $virtualBottom
    )
    $minecraftForegroundObserved = $minecraftForegroundObserved -or $minecraftForeground
    $backgroundCursorConfinementObserved = $backgroundCursorConfinementObserved -or (
        $cursorConfined -and -not $minecraftForeground
    )
    $topmostObserved = $topmostObserved -or [MinecraftDesktopReadOnlyAudit]::IsTopmost($window)
    $samples++
    if ([DateTime]::UtcNow -lt $deadline) {
        Start-Sleep -Milliseconds $SampleMilliseconds
    }
} while ([DateTime]::UtcNow -lt $deadline)

$result = [PSCustomObject]@{
    Safe = -not $topmostObserved -and
        -not $backgroundCursorConfinementObserved -and
        (-not $RequireMinecraftBackground -or -not $minecraftForegroundObserved)
    ReadOnlyAudit = $true
    MinecraftProcessId = $MinecraftProcessId
    NativeWindowHandle = $window.ToInt64()
    Samples = $samples
    WindowTopmostObserved = $topmostObserved
    MinecraftForegroundObserved = $minecraftForegroundObserved
    BackgroundCursorConfinementObserved = $backgroundCursorConfinementObserved
    RequireMinecraftBackground = [bool]$RequireMinecraftBackground
    FirstCursor = $firstCursor
    LastCursor = $lastCursor
    MouseOrKeyboardInputUsed = $false
    WindowMutationUsed = $false
    CursorMutationUsed = $false
}

if ($AsJson) {
    $result | ConvertTo-Json -Compress
} else {
    $result
}
if (-not $result.Safe) {
    throw "Minecraft desktop safety audit failed"
}
