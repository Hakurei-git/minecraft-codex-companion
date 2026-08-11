[CmdletBinding()]
param(
    [ValidateRange(30, 600)]
    [int]$WaitSeconds = 300,

    [switch]$AnyWorld,

    [ValidateLength(1, 128)]
    [string]$WorldId,

    [switch]$SkipLogInspection,

    [ValidateRange(0, 180)]
    [int]$NoLogMenuGraceSeconds = 75,

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0,

    [switch]$CaptureBeforeSelection
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if (-not ("MinecraftBackgroundInput" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftBackgroundInput
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")]
    public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);

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
    public static extern bool SetWindowPos(
        IntPtr hWnd,
        IntPtr hWndInsertAfter,
        int x,
        int y,
        int width,
        int height,
        uint flags
    );

    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

    public static void Key(IntPtr hWnd, int key)
    {
        PostMessage(hWnd, 0x0100, (IntPtr)key, IntPtr.Zero); // WM_KEYDOWN
        PostMessage(hWnd, 0x0101, (IntPtr)key, IntPtr.Zero); // WM_KEYUP
    }

    public static void Chord(IntPtr hWnd, int modifier, int key)
    {
        PostMessage(hWnd, 0x0100, (IntPtr)modifier, IntPtr.Zero);
        PostMessage(hWnd, 0x0100, (IntPtr)key, IntPtr.Zero);
        PostMessage(hWnd, 0x0101, (IntPtr)key, IntPtr.Zero);
        PostMessage(hWnd, 0x0101, (IntPtr)modifier, IntPtr.Zero);
    }

    public static void Text(IntPtr hWnd, string value)
    {
        foreach (char character in value)
        {
            PostMessage(hWnd, 0x0102, (IntPtr)character, IntPtr.Zero); // WM_CHAR
        }
    }

    public static void Click(IntPtr hWnd, int x, int y)
    {
        IntPtr point = (IntPtr)((y << 16) | (x & 0xffff));
        PostMessage(hWnd, 0x0200, IntPtr.Zero, point); // WM_MOUSEMOVE
        PostMessage(hWnd, 0x0201, (IntPtr)1, point);   // WM_LBUTTONDOWN
        PostMessage(hWnd, 0x0202, IntPtr.Zero, point);// WM_LBUTTONUP
    }

    public static void ReleaseCursorCapture(IntPtr hWnd)
    {
        PostMessage(hWnd, 0x0008, IntPtr.Zero, IntPtr.Zero); // WM_KILLFOCUS
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
    }
}
'@
}

$baseUri = [Uri]"http://127.0.0.1:8765/"
if ($baseUri.Host -notin @("127.0.0.1", "localhost", "::1")) {
    throw "The Minecraft entry check must use the loopback control service"
}

if ($AnyWorld -and $PSBoundParameters.ContainsKey("WorldId")) {
    throw "-AnyWorld and -WorldId cannot be used together"
}
if ($PSBoundParameters.ContainsKey("WorldId") -and $WorldId -match '[\x00-\x1f\x7f]') {
    throw "-WorldId cannot contain control characters"
}
$targetWorldId = if ($AnyWorld) { $null } elseif ($PSBoundParameters.ContainsKey("WorldId")) { $WorldId } else { "Codex-Test" }

function Get-ConnectedCompanions {
    try {
        $response = Invoke-RestMethod -Uri ([Uri]::new($baseUri, "api/companions")) -TimeoutSec 3
        return @($response.companions) | Where-Object {
            $_.connected -eq $true -and $_.embodiment -eq "in-world-npc"
        }
    } catch {
        return @()
    }
}

function Test-WorldIdMatches($snapshot) {
    return $null -eq $targetWorldId -or [String]::Equals(
        [string]$snapshot.worldId,
        [string]$targetWorldId,
        [StringComparison]::Ordinal
    )
}

function Get-TargetCompanion {
    return @(Get-ConnectedCompanions) |
        Where-Object { Test-WorldIdMatches $_.snapshot } |
        Select-Object -First 1
}

function Find-MinecraftWindow {
    $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    foreach ($window in $windows) {
        try {
            if ([string]$window.Current.Name -match '^Minecraft.*Forge 1\.20\.1') { return $window }
        } catch {
            # Ignore windows that close while sampling the UI Automation tree.
        }
    }
    return $null
}

$providedWindow = $MinecraftProcessId -gt 0 -or $NativeWindowHandle -gt 0
$window = $null
$handle = [IntPtr]::Zero
$resolvedMinecraftProcessId = 0
if ($providedWindow) {
    if ($MinecraftProcessId -le 0 -or $NativeWindowHandle -le 0) {
        throw "MinecraftProcessId and NativeWindowHandle must be provided together"
    }
    $handle = [IntPtr]$NativeWindowHandle
    if (-not [MinecraftBackgroundInput]::IsWindow($handle)) {
        throw "The verified Minecraft window handle is no longer valid"
    }
    [uint32]$windowProcessId = 0
    [void][MinecraftBackgroundInput]::GetWindowThreadProcessId($handle, [ref]$windowProcessId)
    if ($windowProcessId -ne $MinecraftProcessId) {
        throw "The verified Minecraft window handle changed process ownership"
    }
    $minecraftProcess = Get-Process -Id $MinecraftProcessId -ErrorAction Stop
    if ($minecraftProcess.ProcessName -notin @("java", "javaw")) {
        throw "The verified Minecraft process is no longer a Java runtime"
    }
    $resolvedMinecraftProcessId = $MinecraftProcessId
} else {
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ($null -eq $window -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        $window = Find-MinecraftWindow
    }
    if ($null -eq $window) { throw "Minecraft Forge window was not ready before timeout" }
    $handle = [IntPtr]$window.Current.NativeWindowHandle
    $resolvedMinecraftProcessId = $window.Current.ProcessId
}

if (-not $SkipLogInspection) {
    $configPath = Join-Path $env:LOCALAPPDATA "MinecraftCodexCompanion\launcher-config.json"
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw "Minecraft Codex Companion launcher configuration is missing"
    }
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding utf8 | ConvertFrom-Json
    $instancePath = Join-Path ([string]$config.minecraftRoot) ("versions\" + [string]$config.targetVersion)
    $latestLog = Join-Path $instancePath "logs\latest.log"
    $minecraftProcess = Get-Process -Id $resolvedMinecraftProcessId -ErrorAction Stop
    $menuDeadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $logInfo = Get-Item -LiteralPath $latestLog -ErrorAction SilentlyContinue
        $menuReady = $null -ne $logInfo -and
            $logInfo.LastWriteTime -ge $minecraftProcess.StartTime -and
            [bool](Select-String -LiteralPath $latestLog -Pattern "Sound engine started" -SimpleMatch -Quiet)
    } while (-not $menuReady -and (Get-Date) -lt $menuDeadline)
    if (-not $menuReady) { throw "Minecraft Forge main menu was not ready before timeout" }
}

$rect = [MinecraftBackgroundInput+RECT]::new()
if ($handle -eq [IntPtr]::Zero) {
    throw "Minecraft client bounds are unavailable"
}
$wasMinimized = [MinecraftBackgroundInput]::IsIconic($handle)
$minimizedAfterWorldSelection = $false
if ($wasMinimized) {
    # SW_SHOWNOACTIVATE restores GLFW's client size without taking focus from
    # the application the user is currently working in.
    [void][MinecraftBackgroundInput]::ShowWindowAsync($handle, 4)
    [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
}
try {
    [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
    $boundsDeadline = (Get-Date).AddSeconds(5)
    do {
        Start-Sleep -Milliseconds 100
        $boundsAvailable = [MinecraftBackgroundInput]::GetClientRect($handle, [ref]$rect)
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
    } while (($width -lt 640 -or $height -lt 360) -and (Get-Date) -lt $boundsDeadline)
    if (-not $boundsAvailable) {
        throw "Minecraft client bounds are unavailable"
    }
    if ($width -lt 640 -or $height -lt 360) { throw "Minecraft client window is unexpectedly small" }

    function Click-Normalized([double]$xFraction, [double]$yFraction) {
        $x = [Math]::Round($width * $xFraction)
        $y = [Math]::Round($height * $yFraction)
        [MinecraftBackgroundInput]::Click($handle, $x, $y)
    }

    # A short grace period avoids disturbing a world that is already loading.
    $graceDeadline = (Get-Date).AddSeconds(3)
    $companion = $null
    do {
        $companion = Get-TargetCompanion
        if ($null -ne $companion) { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $graceDeadline)

    if ($null -eq $companion -and $null -ne $targetWorldId) {
        $wrongWorld = @(Get-ConnectedCompanions) | Select-Object -First 1
        if ($null -ne $wrongWorld) {
            throw "Minecraft is already connected to world '$([string]$wrongWorld.snapshot.worldId)', expected '$targetWorldId'"
        }
    }

    $noLogGraceApplied = 0
    if ($SkipLogInspection -and $null -eq $companion -and $NoLogMenuGraceSeconds -gt 0) {
        # Without reading latest.log there is no semantic signal for the title
        # screen. Delay background clicks until a freshly launched Forge client
        # has had a conservative startup window; keep polling the bridge so an
        # already-loaded world still returns immediately.
        $minecraftProcess = Get-Process -Id $resolvedMinecraftProcessId -ErrorAction Stop
        $menuReadyAfter = $minecraftProcess.StartTime.AddSeconds($NoLogMenuGraceSeconds)
        while ($null -eq $companion -and (Get-Date) -lt $menuReadyAfter) {
            Start-Sleep -Milliseconds 500
            $companion = Get-TargetCompanion
        }
        $noLogGraceApplied = [Math]::Max(
            0,
            [Math]::Min(
                $NoLogMenuGraceSeconds,
                [int][Math]::Ceiling(((Get-Date) - $minecraftProcess.StartTime).TotalSeconds)
            )
        )
    }

    $worldSelectionPosted = $false
    if ($null -eq $companion) {
        if (-not [MinecraftBackgroundInput]::IsWindow($handle)) {
            $minecraftProcess = Get-Process -Id $resolvedMinecraftProcessId -ErrorAction Stop
            $minecraftProcess.Refresh()
            $replacementHandle = [IntPtr]$minecraftProcess.MainWindowHandle
            if ($replacementHandle -eq [IntPtr]::Zero -or
                -not [MinecraftBackgroundInput]::IsWindow($replacementHandle)) {
                $replacementWindow = Find-MinecraftWindow
                if ($null -ne $replacementWindow -and
                    $replacementWindow.Current.ProcessId -eq $resolvedMinecraftProcessId) {
                    $replacementHandle = [IntPtr]$replacementWindow.Current.NativeWindowHandle
                }
            }
            if ($replacementHandle -eq [IntPtr]::Zero -or
                -not [MinecraftBackgroundInput]::IsWindow($replacementHandle)) {
                throw "Minecraft replaced its loading window without exposing a verified main-menu HWND"
            }
            [uint32]$replacementProcessId = 0
            [void][MinecraftBackgroundInput]::GetWindowThreadProcessId(
                $replacementHandle,
                [ref]$replacementProcessId
            )
            if ($replacementProcessId -ne $resolvedMinecraftProcessId) {
                throw "The replacement Minecraft window changed process ownership"
            }
            $handle = $replacementHandle
        }
        if ([MinecraftBackgroundInput]::IsIconic($handle)) {
            [void][MinecraftBackgroundInput]::ShowWindowAsync($handle, 4)
            [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
        }
        $boundsDeadline = (Get-Date).AddSeconds(5)
        do {
            Start-Sleep -Milliseconds 100
            $boundsAvailable = [MinecraftBackgroundInput]::GetClientRect($handle, [ref]$rect)
            $width = $rect.Right - $rect.Left
            $height = $rect.Bottom - $rect.Top
        } while (($width -lt 640 -or $height -lt 360) -and (Get-Date) -lt $boundsDeadline)
        if (-not $boundsAvailable -or $width -lt 640 -or $height -lt 360) {
            throw "Minecraft main-menu client bounds are unavailable"
        }
        if ($CaptureBeforeSelection) {
            [void](& (Join-Path $PSScriptRoot "capture-minecraft-window.ps1") `
                -Name "minecraft-only-before-selection.png" `
                -MinecraftProcessId $resolvedMinecraftProcessId `
                -NativeWindowHandle $handle.ToInt64())
        }

        # Canonicalize any nested title-screen page (Options, Language, world
        # list, etc.) back to the main menu. Escape is harmless on the main
        # menu and is posted only while the Forge bridge is disconnected.
        1..3 | ForEach-Object {
            [MinecraftBackgroundInput]::Key($handle, 0x1B)
            Start-Sleep -Milliseconds 300
        }

        # Main menu: Singleplayer. Coordinates are normalized to the GLFW
        # client, so window borders and DPI are not hardcoded.
        Click-Normalized 0.50 0.515
        Start-Sleep -Seconds 3

        # The search box is drawn near 13% of the GLFW client height. Minecraft
        # accepts background WM_CHAR reliably for ASCII, but non-ASCII world
        # names are corrupted on some GLFW/Windows combinations. Clear the
        # search in both cases; for non-ASCII names select the most-recent row
        # and let the exact bridge worldId check below fail closed if it is not
        # the requested world.
        if ($null -ne $targetWorldId) {
            Click-Normalized 0.50 0.13
            Start-Sleep -Milliseconds 250
            # Posted modifier chords do not update GLFW's foreground keyboard
            # state consistently; Ctrl+A can therefore insert a literal "a".
            # A bounded series of Backspace messages is deterministic and
            # never needs the foreground keyboard or clipboard.
            1..128 | ForEach-Object {
                [MinecraftBackgroundInput]::Key($handle, 0x08)
            }
            Start-Sleep -Milliseconds 250
            if ($targetWorldId -cmatch '^[\x20-\x7e]+$') {
                [MinecraftBackgroundInput]::Text($handle, $targetWorldId)
            }
            Start-Sleep -Seconds 1
        }

        # Select the first filtered result, then press Play Selected World.
        # The real cursor, focus and clipboard are untouched.
        Click-Normalized 0.46 0.32
        Start-Sleep -Seconds 1
        Click-Normalized 0.31 0.825
        $worldSelectionPosted = $true

        $connectDeadline = (Get-Date).AddSeconds($WaitSeconds)
        do {
            Start-Sleep -Milliseconds 500
            [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
            $companion = Get-TargetCompanion
            if ($null -eq $companion -and $null -ne $targetWorldId) {
                $wrongWorld = @(Get-ConnectedCompanions) | Select-Object -First 1
                if ($null -ne $wrongWorld) {
                    if (Test-WorldIdMatches $wrongWorld.snapshot) {
                        $companion = $wrongWorld
                    } else {
                        throw "Selected world '$([string]$wrongWorld.snapshot.worldId)' did not match expected world '$targetWorldId'"
                    }
                }
            }
        } while ($null -eq $companion -and (Get-Date) -lt $connectDeadline)
        if ($null -eq $companion) {
            throw "The selected world did not connect to the control service before timeout"
        }
    }

    # A restored GLFW window can retain cursor capture even when it was moved
    # behind the user's foreground application. Minimize without activation so
    # Windows releases that capture. The local bridge remains the authoritative
    # liveness check; chat and Save/Quit helpers temporarily restore the window
    # with SW_SHOWNOACTIVATE and minimize it again when their work is complete.
    [void][MinecraftBackgroundInput]::ShowWindowAsync($handle, 6) # SW_MINIMIZE
    [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
    $cursorReleaseDeadline = (Get-Date).AddSeconds(3)
    do {
        Start-Sleep -Milliseconds 100
        $minimizedAfterWorldSelection = [MinecraftBackgroundInput]::IsIconic($handle)
    } while (-not $minimizedAfterWorldSelection -and (Get-Date) -lt $cursorReleaseDeadline)
    if (-not $minimizedAfterWorldSelection) {
        throw "Minecraft window could not be minimized to release cursor capture"
    }

    [PSCustomObject]@{
        MinecraftProcessId = $resolvedMinecraftProcessId
        WorldSelectionPosted = $worldSelectionPosted
        Connected = $true
        CompanionId = [string]$companion.id
        RequestedWorldId = $targetWorldId
        WorldId = [string]$companion.snapshot.worldId
        SnapshotSequence = [long]$companion.snapshot.sequence
        RestoredWithoutActivation = $wasMinimized
        MinimizedAfterWorldSelection = $true
        SentToBottomAfterWorldSelection = $false
        CursorCaptureReleased = $true
        ForegroundInteractionUsed = $false
        PhysicalMouseOrKeyboardInputUsed = $false
        ClipboardUsed = $false
        ScreenshotUsed = $false
        LogInspected = (-not $SkipLogInspection)
        NoLogMenuGraceSeconds = $noLogGraceApplied
    }
} finally {
    [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
    if (-not $minimizedAfterWorldSelection) {
        [void][MinecraftBackgroundInput]::ShowWindowAsync($handle, 6) # SW_MINIMIZE
    }
    [MinecraftBackgroundInput]::ReleaseCursorCapture($handle)
}
