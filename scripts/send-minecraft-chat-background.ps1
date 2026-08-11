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

    [switch]$RespawnIfDead
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
if (-not $NormalizeOnly -and $Message.IndexOfAny([char[]]@("`r", "`n", [char]0)) -ge 0) {
    throw "Minecraft chat text cannot contain control characters"
}

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if (-not ("MinecraftBackgroundChatPost" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftBackgroundChatPost
{
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")]
    public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);

    [DllImport("user32.dll")]
    public static extern bool IsWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern bool IsIconic(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    public static extern bool ClipCursor(IntPtr rect);

    [DllImport("user32.dll")]
    public static extern bool ReleaseCapture();

    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "PostMessageW")]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);

    public static bool Key(IntPtr hWnd, int key, int scanCode)
    {
        long down = 1L | ((long)scanCode << 16);
        long up = down | (1L << 30) | (1L << 31);
        return PostMessage(hWnd, 0x0100, (IntPtr)key, (IntPtr)down)
            && PostMessage(hWnd, 0x0101, (IntPtr)key, (IntPtr)up);
    }

    public static bool Text(IntPtr hWnd, string value)
    {
        foreach (char character in value)
        {
            if (!PostMessage(hWnd, 0x0102, (IntPtr)character, (IntPtr)1)) return false;
            System.Threading.Thread.Sleep(1);
        }
        return true;
    }

    public static bool Click(IntPtr hWnd, int x, int y)
    {
        IntPtr point = (IntPtr)((y << 16) | (x & 0xffff));
        return PostMessage(hWnd, 0x0200, IntPtr.Zero, point)
            && PostMessage(hWnd, 0x0201, (IntPtr)1, point)
            && PostMessage(hWnd, 0x0202, IntPtr.Zero, point);
    }

    public static IntPtr FindLargestWindowForProcess(uint processId)
    {
        IntPtr best = IntPtr.Zero;
        long bestArea = 0;
        EnumWindows((candidate, ignored) =>
        {
            uint owner;
            RECT rect;
            if (GetWindowThreadProcessId(candidate, out owner) == 0 || owner != processId
                || !GetClientRect(candidate, out rect)) return true;
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
        PostMessage(hWnd, 0x0008, IntPtr.Zero, IntPtr.Zero); // WM_KILLFOCUS
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
    }
}
'@
}

$baseUri = [Uri]"http://127.0.0.1:8765/"
function Get-ClientUiState {
    $response = Invoke-RestMethod -Uri ([Uri]::new($baseUri, "api/companions")) -TimeoutSec 2
    $companions = @(
        @($response.companions) | Where-Object {
            $_.connected -eq $true -and $_.embodiment -eq "in-world-npc"
        }
    )
    if ($companions.Count -ne 1) {
        throw "Background T chat requires exactly one connected Forge NPC"
    }
    $id = [Uri]::EscapeDataString([string]$companions[0].id)
    $snapshot = Invoke-RestMethod -Uri ([Uri]::new($baseUri, "api/companions/$id/snapshot")) -TimeoutSec 2
    $state = [string]$snapshot.clientUiState
    if ($state -notin @("gameplay", "chat", "pause", "death", "other")) {
        throw "Minecraft client UI state is unavailable"
    }
    return $state
}

function Wait-ClientUiState([string]$Expected, [int]$TimeoutMilliseconds) {
    $deadline = (Get-Date).AddMilliseconds($TimeoutMilliseconds)
    do {
        if ((Get-ClientUiState) -eq $Expected) { return $true }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    return $false
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$activeWindowStatusPath = Join-Path $projectRoot ".runtime\hmcl-background-agent\active-minecraft-window.status"
$handle = [IntPtr]::Zero
$resolvedMinecraftProcessId = 0
if (Test-Path -LiteralPath $activeWindowStatusPath -PathType Leaf) {
    $statusFile = Get-Item -LiteralPath $activeWindowStatusPath -Force
    if (-not ($statusFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        $status = [regex]::Match(
            [IO.File]::ReadAllText($statusFile.FullName, [Text.Encoding]::ASCII),
            '^PID=(?<pid>[1-9][0-9]{0,9});HWND=(?<hwnd>[1-9][0-9]{0,19})$',
            [Text.RegularExpressions.RegexOptions]::CultureInvariant
        )
        if ($status.Success) {
            $candidateProcessId = [int]$status.Groups["pid"].Value
            $candidateHandle = [IntPtr][long]$status.Groups["hwnd"].Value
            $candidateProcess = Get-Process -Id $candidateProcessId -ErrorAction SilentlyContinue
            if ($null -ne $candidateProcess -and
                $candidateProcess.ProcessName -in @("java", "javaw")) {
                $identityVerified = $true
                $jcmd = Get-Command "jcmd.exe" -ErrorAction SilentlyContinue
                if ($null -ne $jcmd) {
                    $hierarchy = (& $jcmd.Source $candidateProcessId `
                        "VM.class_hierarchy" "net.minecraft.client.main.Main" 2>$null | Out-String)
                    $identityVerified = $hierarchy -match "net\.minecraft\.client\.main\.Main"
                }
                if ($identityVerified) {
                    [uint32]$windowProcessId = 0
                    if (-not [MinecraftBackgroundChatPost]::IsWindow($candidateHandle) -or
                        [MinecraftBackgroundChatPost]::GetWindowThreadProcessId(
                            $candidateHandle,
                            [ref]$windowProcessId
                        ) -eq 0 -or
                        $windowProcessId -ne $candidateProcessId) {
                        $candidateHandle = [MinecraftBackgroundChatPost]::FindLargestWindowForProcess(
                            [uint32]$candidateProcessId
                        )
                        $windowProcessId = 0
                    }
                    if ($candidateHandle -ne [IntPtr]::Zero -and
                        [MinecraftBackgroundChatPost]::IsWindow($candidateHandle) -and
                        [MinecraftBackgroundChatPost]::GetWindowThreadProcessId(
                            $candidateHandle,
                            [ref]$windowProcessId
                        ) -ne 0 -and
                        $windowProcessId -eq $candidateProcessId) {
                        $handle = $candidateHandle
                        $resolvedMinecraftProcessId = $candidateProcessId
                    }
                }
            }
        }
    }
}

if ($handle -eq [IntPtr]::Zero) {
    $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    $minecraft = $null
    foreach ($window in $windows) {
        try {
            if ([string]$window.Current.Name -match '^Minecraft.*Forge 1\.20\.1') {
                $minecraft = $window
                break
            }
        } catch {
            # Ignore windows that close while the UI Automation tree is sampled.
        }
    }
    if ($null -eq $minecraft) { throw "Minecraft Forge window is unavailable" }
    $handle = [IntPtr]$minecraft.Current.NativeWindowHandle
    $resolvedMinecraftProcessId = [int]$minecraft.Current.ProcessId
}
if ($handle -eq [IntPtr]::Zero -or $resolvedMinecraftProcessId -le 0) {
    throw "Minecraft window handle is unavailable"
}
$wasMinimized = [MinecraftBackgroundChatPost]::IsIconic($handle)
if ($wasMinimized) {
    [void][MinecraftBackgroundChatPost]::ShowWindowAsync($handle, 4) # SW_SHOWNOACTIVATE
    [MinecraftBackgroundChatPost]::ReleaseCursorCapture($handle)
    Start-Sleep -Milliseconds 250
    [MinecraftBackgroundChatPost]::ReleaseCursorCapture($handle)
}

$initialUiState = Get-ClientUiState
$normalizedUiState = $initialUiState
$chatOpenAttempts = 0
$respawned = $false
try {
    if ($initialUiState -eq "death") {
        if (-not $RespawnIfDead) {
            throw "Minecraft is on the death screen; pass -RespawnIfDead to continue"
        }
        $rect = [MinecraftBackgroundChatPost+RECT]::new()
        if (-not [MinecraftBackgroundChatPost]::GetClientRect($handle, [ref]$rect)) {
            throw "Minecraft client bounds are unavailable for respawn"
        }
        $width = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        if ($width -lt 640 -or $height -lt 360) {
            throw "Minecraft client window is unexpectedly small for respawn"
        }
        if (-not [MinecraftBackgroundChatPost]::Click(
            $handle,
            [Math]::Round($width * 0.50),
            [Math]::Round($height * 0.58)
        )) {
            throw "Minecraft rejected the background respawn click"
        }
        if (-not (Wait-ClientUiState "gameplay" 10000)) {
            throw "Minecraft did not return to gameplay after respawn"
        }
        $respawned = $true
        $normalizedUiState = "gameplay"
    } elseif ($initialUiState -ne "gameplay") {
        if (-not [MinecraftBackgroundChatPost]::Key($handle, 0x1B, 0x01)) { # Escape
            throw "Minecraft rejected the background UI normalization message"
        }
        if (-not (Wait-ClientUiState "gameplay" 3000)) {
            throw "Minecraft did not return to gameplay before background T chat"
        }
        $normalizedUiState = "gameplay"
    }

    if (-not $NormalizeOnly) {
        do {
            $chatOpenAttempts++
            if (-not [MinecraftBackgroundChatPost]::Key($handle, 0x54, 0x14)) { # T
                throw "Minecraft rejected the background T key message"
            }
            $chatOpened = Wait-ClientUiState "chat" 2500
            if (-not $chatOpened -and (Get-ClientUiState) -ne "gameplay") {
                if (-not [MinecraftBackgroundChatPost]::Key($handle, 0x1B, 0x01)) {
                    throw "Minecraft rejected the background chat recovery message"
                }
                if (-not (Wait-ClientUiState "gameplay" 3000)) {
                    throw "Minecraft UI could not be recovered after T chat failed to open"
                }
            }
        } while (-not $chatOpened -and $chatOpenAttempts -lt 3)
        if (-not $chatOpened) { throw "Minecraft T chat did not open after three background attempts" }

        if (-not [MinecraftBackgroundChatPost]::Text($handle, $Message)) {
            throw "Minecraft rejected background chat characters"
        }
        if (-not [MinecraftBackgroundChatPost]::Key($handle, 0x0D, 0x1C)) { # Enter
            throw "Minecraft rejected the background Enter key message"
        }
        if (-not (Wait-ClientUiState "gameplay" 4000)) {
            throw "Minecraft chat did not close after the background message was submitted"
        }
    }
} finally {
    [MinecraftBackgroundChatPost]::ReleaseCursorCapture($handle)
    if ($wasMinimized) {
        [void][MinecraftBackgroundChatPost]::ShowWindowAsync($handle, 6) # SW_MINIMIZE
    }
    [MinecraftBackgroundChatPost]::ReleaseCursorCapture($handle)
}

[PSCustomObject]@{
    Sent = -not $NormalizeOnly
    CharacterCount = if ($NormalizeOnly) { 0 } else { $Message.Length }
    MinecraftProcessId = $resolvedMinecraftProcessId
    InitialUiState = $initialUiState
    NormalizedUiState = $normalizedUiState
    ChatOpenAttempts = $chatOpenAttempts
    ChatOpenConfirmed = -not $NormalizeOnly
    GameplayRestored = $true
    Respawned = $respawned
    RestoredWithoutActivation = $wasMinimized
    MinimizedAfterSend = $wasMinimized
    CursorCaptureReleased = $true
    ForegroundInteractionUsed = $false
    PhysicalMouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
