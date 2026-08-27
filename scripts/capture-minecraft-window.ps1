[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9._-]+\.png$')]
    [string]$Name = "minecraft-window.png",

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0,

    [string]$OutputDirectory = "",

    [switch]$RequireMinecraftBackground,

    [switch]$AsJson
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot ".runtime\manual-safe-screenshots"
}

if (-not ("MinecraftClientAreaCapture" -as [type])) {
    Add-Type -ReferencedAssemblies System.Drawing @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftClientAreaCapture
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")]
    public static extern IntPtr GetDC(IntPtr window);

    [DllImport("user32.dll")]
    public static extern int ReleaseDC(IntPtr window, IntPtr deviceContext);

    [DllImport("user32.dll")]
    public static extern bool GetClientRect(IntPtr window, out RECT rectangle);

    [DllImport("user32.dll")]
    public static extern bool IsWindow(IntPtr window);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    public static extern bool GetClipCursor(out RECT rectangle);

    [DllImport("gdi32.dll", SetLastError = true)]
    public static extern bool BitBlt(
        IntPtr destination,
        int destinationX,
        int destinationY,
        int width,
        int height,
        IntPtr source,
        int sourceX,
        int sourceY,
        int rasterOperation
    );
}
'@
}

if ($MinecraftProcessId -le 0) {
    $candidates = @(Get-Process -Name "java", "javaw" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.MainWindowHandle -ne [IntPtr]::Zero -and
            $_.MainWindowTitle -match '^Minecraft(?:\*|\s|$)'
        })
    if ($candidates.Count -ne 1) {
        throw "Exactly one visible Minecraft Java process is required when MinecraftProcessId is omitted"
    }
    $MinecraftProcessId = [int]$candidates[0].Id
}

$process = Get-Process -Id $MinecraftProcessId -ErrorAction Stop
if ($process.ProcessName -notin @("java", "javaw")) {
    throw "The screenshot target is not a Java process"
}
$window = if ($NativeWindowHandle -ne 0) {
    [IntPtr]$NativeWindowHandle
} else {
    [IntPtr]$process.MainWindowHandle
}
if ($window -eq [IntPtr]::Zero -or -not [MinecraftClientAreaCapture]::IsWindow($window)) {
    throw "The Minecraft window is unavailable"
}
[uint32]$windowProcessId = 0
if ([MinecraftClientAreaCapture]::GetWindowThreadProcessId($window, [ref]$windowProcessId) -eq 0 -or
    $windowProcessId -ne $MinecraftProcessId) {
    throw "The screenshot window does not belong to the selected Minecraft process"
}

$client = [MinecraftClientAreaCapture+RECT]::new()
if (-not [MinecraftClientAreaCapture]::GetClientRect($window, [ref]$client)) {
    throw "The Minecraft client area could not be measured"
}
$width = $client.Right - $client.Left
$height = $client.Bottom - $client.Top
if ($width -lt 64 -or $height -lt 64 -or $width -gt 16384 -or $height -gt 16384) {
    throw "The Minecraft client area has invalid dimensions: ${width}x${height}"
}

$foregroundBefore = [MinecraftClientAreaCapture]::GetForegroundWindow()
if ($RequireMinecraftBackground -and $foregroundBefore -eq $window) {
    throw "Minecraft must already be in the background before a safe evidence capture"
}
$clipBefore = [MinecraftClientAreaCapture+RECT]::new()
if (-not [MinecraftClientAreaCapture]::GetClipCursor([ref]$clipBefore)) {
    throw "The desktop cursor confinement could not be audited before capture"
}
$clipBeforeText = "$($clipBefore.Left),$($clipBefore.Top),$($clipBefore.Right),$($clipBefore.Bottom)"

$resolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null
$outputPath = Join-Path $resolvedOutputDirectory $Name
$bitmap = New-Object System.Drawing.Bitmap(
    $width,
    $height,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$destination = $graphics.GetHdc()
$source = [MinecraftClientAreaCapture]::GetDC($window)
try {
    if ($source -eq [IntPtr]::Zero) {
        throw "The Minecraft client device context is unavailable"
    }
    # SRCCOPY | CAPTUREBLT. Reading the existing client DC does not activate,
    # restore, resize, reorder, focus, or send input to the game window.
    if (-not [MinecraftClientAreaCapture]::BitBlt(
        $destination,
        0,
        0,
        $width,
        $height,
        $source,
        0,
        0,
        0x40CC0020
    )) {
        throw "The Minecraft client-area copy failed"
    }
} finally {
    if ($source -ne [IntPtr]::Zero) {
        [void][MinecraftClientAreaCapture]::ReleaseDC($window, $source)
    }
    $graphics.ReleaseHdc($destination)
    $graphics.Dispose()
}

$sampleColors = [Collections.Generic.HashSet[int]]::new()
$stepX = [Math]::Max(1, [int]($width / 32))
$stepY = [Math]::Max(1, [int]($height / 32))
for ($y = 0; $y -lt $height; $y += $stepY) {
    for ($x = 0; $x -lt $width; $x += $stepX) {
        [void]$sampleColors.Add($bitmap.GetPixel($x, $y).ToArgb())
    }
}
if ($sampleColors.Count -lt 2) {
    $bitmap.Dispose()
    throw "The captured Minecraft client area is blank or uniform"
}
$bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bitmap.Dispose()

$foregroundAfter = [MinecraftClientAreaCapture]::GetForegroundWindow()
$clipAfter = [MinecraftClientAreaCapture+RECT]::new()
if (-not [MinecraftClientAreaCapture]::GetClipCursor([ref]$clipAfter)) {
    throw "The desktop cursor confinement could not be audited after capture"
}
$clipAfterText = "$($clipAfter.Left),$($clipAfter.Top),$($clipAfter.Right),$($clipAfter.Bottom)"
$foregroundUnchanged = $foregroundBefore -eq $foregroundAfter
$cursorClipUnchanged = $clipBeforeText -eq $clipAfterText
if (-not $foregroundUnchanged -or -not $cursorClipUnchanged) {
    throw "Desktop focus or cursor confinement changed during the evidence capture"
}

$result = [PSCustomObject]@{
    ScreenshotPath = $outputPath
    Width = $width
    Height = $height
    MinecraftProcessId = $MinecraftProcessId
    NativeWindowHandle = $window.ToInt64()
    ClientAreaOnly = $true
    ReadOnlyCapture = $true
    ForegroundUnchanged = $foregroundUnchanged
    CursorClipUnchanged = $cursorClipUnchanged
    MouseOrKeyboardInputUsed = $false
    WindowMutationUsed = $false
    CursorMutationUsed = $false
    ClipboardUsed = $false
    PrintWindowUsed = $false
}
if ($AsJson) {
    $result | ConvertTo-Json -Compress
} else {
    $result
}
