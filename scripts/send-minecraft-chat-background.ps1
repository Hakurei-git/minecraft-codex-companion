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
    [string]$ControlBaseUri = "",

    [int]$MinecraftProcessId = 0,

    [long]$NativeWindowHandle = 0,

    [switch]$DraftOnly,

    [switch]$RespawnIfDead,

    [switch]$RecoverPlayerNearNpcForTest
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($ControlBaseUri)) {
    $ControlBaseUri = if ([string]::IsNullOrWhiteSpace($env:MC_COMPANION_URL)) {
        "http://127.0.0.1:8765"
    } else {
        [string]$env:MC_COMPANION_URL
    }
}
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
if ($NormalizeOnly -and $DraftOnly) {
    throw "NormalizeOnly and DraftOnly cannot be combined"
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

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }

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
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    public static extern bool GetCursorPos(out POINT point);

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
        foreach (char codeUnit in value)
        {
            // GLFW's Windows callback consumes WM_CHAR. Supplementary Unicode
            // characters arrive as the original UTF-16 surrogate pair.
            if (!PostMessage(hWnd, 0x0102, (IntPtr)codeUnit, (IntPtr)1)) return false; // WM_CHAR
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
        // Do not synthesize WM_KILLFOCUS: fullscreen GLFW treats it as a real
        // focus loss and minimizes before the targeted T-chat messages arrive.
        ReleaseCapture();
        ClipCursor(IntPtr.Zero);
    }
}
'@
}

function Get-DesktopInteractionSnapshot {
    $point = [MinecraftBackgroundChatPost+POINT]::new()
    if (-not [MinecraftBackgroundChatPost]::GetCursorPos([ref]$point)) {
        throw "The physical cursor position could not be audited"
    }
    return [PSCustomObject]@{
        ForegroundWindow = [MinecraftBackgroundChatPost]::GetForegroundWindow().ToInt64()
        CursorX = $point.X
        CursorY = $point.Y
    }
}

function Test-MinecraftWindowOwnership([IntPtr]$Candidate, [int]$ExpectedProcessId) {
    if ($Candidate -eq [IntPtr]::Zero -or
        -not [MinecraftBackgroundChatPost]::IsWindow($Candidate)) {
        return $false
    }
    [uint32]$ownerProcessId = 0
    return [MinecraftBackgroundChatPost]::GetWindowThreadProcessId(
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
    $replacement = [MinecraftBackgroundChatPost]::FindLargestWindowForProcess(
        [uint32]$ExpectedProcessId
    )
    if (-not (Test-MinecraftWindowOwnership $replacement $ExpectedProcessId)) {
        throw "Minecraft did not expose a current verified window handle"
    }
    return $replacement
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
        throw "Minecraft is foreground; background chat stopped to avoid user interaction"
    }

    [void][MinecraftBackgroundChatPost]::ShowWindowAsync($verifiedHandle, $ShowCommand)
    [MinecraftBackgroundChatPost]::ReleaseCursorCapture($verifiedHandle)
    $deadline = (Get-Date).AddSeconds(3)
    do {
        Start-Sleep -Milliseconds 50
        $verifiedHandle = Resolve-VerifiedMinecraftWindowHandle `
            $ExpectedProcessId `
            $verifiedHandle
        $isIconic = [MinecraftBackgroundChatPost]::IsIconic($verifiedHandle)
    } while ($isIconic -ne $ExpectedIconic -and (Get-Date) -lt $deadline)
    if ($isIconic -ne $ExpectedIconic) {
        throw "Minecraft window did not reach the requested background state"
    }
    Start-Sleep -Milliseconds 100
    $after = Get-DesktopInteractionSnapshot
    $foregroundPreserved = $before.ForegroundWindow -eq $after.ForegroundWindow
    $cursorPreserved = $before.CursorX -eq $after.CursorX -and
        $before.CursorY -eq $after.CursorY
    if (-not $foregroundPreserved -or -not $cursorPreserved) {
        if (-not $ExpectedIconic) {
            [void][MinecraftBackgroundChatPost]::ShowWindowAsync($verifiedHandle, 6)
            [MinecraftBackgroundChatPost]::ReleaseCursorCapture($verifiedHandle)
        }
        throw "Minecraft background window transition changed the foreground window or physical cursor position"
    }
    return [PSCustomObject]@{
        Handle = $verifiedHandle
        ForegroundWindowPreserved = $true
        CursorPositionPreserved = $true
    }
}

function Assert-MinecraftIsNotForeground([IntPtr]$CurrentHandle) {
    if ([MinecraftBackgroundChatPost]::GetForegroundWindow() -eq $CurrentHandle) {
        throw "Minecraft became foreground; background chat stopped to avoid user interaction"
    }
}

$controlUri = [Uri]$ControlBaseUri
if ($controlUri.Scheme -ne "http" -or
    $controlUri.Host.ToLowerInvariant() -notin @("127.0.0.1", "localhost", "::1", "[::1]")) {
    throw "ControlBaseUri must be an HTTP loopback URL"
}
$baseUri = [Uri]($controlUri.GetLeftPart([UriPartial]::Authority) + "/")
function Get-ConnectedMinecraftCompanion {
    $response = Invoke-RestMethod -Uri ([Uri]::new($baseUri, "api/companions")) -TimeoutSec 2
    $companions = @(
        @($response.companions) | Where-Object {
            $_.connected -eq $true -and
            $_.backend -in @("forge-1.20.1", "neoforge-1.21.1") -and
            $_.embodiment -in @("in-world-npc", "remote-player")
        }
    )
    if ($companions.Count -ne 1) {
        throw "Background T chat requires exactly one connected Forge or NeoForge companion"
    }
    return $companions[0]
}

function Get-ClientUiState {
    $companion = Get-ConnectedMinecraftCompanion
    $id = [Uri]::EscapeDataString([string]$companion.id)
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

function Invoke-TestPlayerRecoveryNearNpc {
    $companion = Get-ConnectedMinecraftCompanion
    if ([string]$companion.embodiment -ne "in-world-npc" -or
        [string]$companion.backend -ne "forge-1.20.1") {
        return $false
    }
    $id = [Uri]::EscapeDataString([string]$companion.id)
    $beforeSnapshot = Invoke-RestMethod `
        -Uri ([Uri]::new($baseUri, "api/companions/$id/snapshot")) `
        -TimeoutSec 2
    $beforeAck = if ($null -eq $beforeSnapshot.liveFixtureAck) {
        0L
    } else {
        [long]$beforeSnapshot.liveFixtureAck.sequence
    }
    $body = @{ suite = "view-npc"; mode = "npc" } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod `
        -Uri ([Uri]::new($baseUri, "api/companions/$id/live-fixtures")) `
        -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Body $body `
        -TimeoutSec 5
    if (-not [bool]$response.ok) { throw "Test-only player recovery fixture was rejected" }

    $deadline = (Get-Date).AddSeconds(8)
    do {
        Start-Sleep -Milliseconds 150
        $snapshot = Invoke-RestMethod `
            -Uri ([Uri]::new($baseUri, "api/companions/$id/snapshot")) `
            -TimeoutSec 2
        $acknowledgement = $snapshot.liveFixtureAck
        if ($null -eq $acknowledgement -or
            [long]$acknowledgement.sequence -le $beforeAck -or
            [string]$acknowledgement.suite -ne "view-npc" -or
            [string]$acknowledgement.mode -ne "npc") {
            continue
        }
        $status = [string]$acknowledgement.status
        if ($status.StartsWith("live-fixture:denied ") -or
            $status.StartsWith("live-fixture:failed ")) {
            throw "Test-only player recovery fixture failed: $status"
        }
        if (-not $status.StartsWith("view-npc:npc applied ")) {
            throw "Test-only player recovery fixture returned an unexpected acknowledgement: $status"
        }
        if ([double]$snapshot.ownerDistance -gt 8.0) {
            throw "Test-only player recovery did not place the player near the NPC"
        }
        return $true
    } while ((Get-Date) -lt $deadline)
    throw "Test-only player recovery fixture was not acknowledged"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$activeWindowStatusPath = Join-Path $projectRoot ".runtime\hmcl-background-agent\active-minecraft-window.status"
$handle = [IntPtr]::Zero
$resolvedMinecraftProcessId = 0
$providedWindow = $MinecraftProcessId -gt 0 -or $NativeWindowHandle -gt 0
if ($providedWindow) {
    if ($MinecraftProcessId -le 0 -or $NativeWindowHandle -le 0) {
        throw "MinecraftProcessId and NativeWindowHandle must be provided together"
    }
    $candidateProcess = Get-Process -Id $MinecraftProcessId -ErrorAction Stop
    if ($candidateProcess.ProcessName -notin @("java", "javaw")) {
        throw "The verified Minecraft process is not a Java runtime"
    }
    $candidateHandle = [IntPtr]$NativeWindowHandle
    [uint32]$windowProcessId = 0
    if (-not [MinecraftBackgroundChatPost]::IsWindow($candidateHandle) -or
        [MinecraftBackgroundChatPost]::GetWindowThreadProcessId(
            $candidateHandle,
            [ref]$windowProcessId
        ) -eq 0 -or
        $windowProcessId -ne $MinecraftProcessId) {
        throw "The verified Minecraft window handle changed process ownership"
    }
    $jcmd = Get-Command "jcmd.exe" -ErrorAction SilentlyContinue
    if ($null -ne $jcmd) {
        $hierarchy = (& $jcmd.Source $MinecraftProcessId `
            "VM.class_hierarchy" "net.minecraft.client.main.Main" 2>$null | Out-String)
        if ($hierarchy -notmatch "net\.minecraft\.client\.main\.Main") {
            throw "The verified Java process is not a loaded Minecraft client"
        }
    }
    $handle = $candidateHandle
    $resolvedMinecraftProcessId = $MinecraftProcessId
}
if ($handle -eq [IntPtr]::Zero -and (Test-Path -LiteralPath $activeWindowStatusPath -PathType Leaf)) {
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
            if ([string]$window.Current.Name -match '^Minecraft.*(?:Forge 1\.20\.1|NeoForge|1\.21\.1)') {
                $minecraft = $window
                break
            }
        } catch {
            # Ignore windows that close while the UI Automation tree is sampled.
        }
    }
    if ($null -eq $minecraft) { throw "Minecraft Forge or NeoForge window is unavailable" }
    $handle = [IntPtr]$minecraft.Current.NativeWindowHandle
    $resolvedMinecraftProcessId = [int]$minecraft.Current.ProcessId
}
if ($handle -eq [IntPtr]::Zero -or $resolvedMinecraftProcessId -le 0) {
    throw "Minecraft window handle is unavailable"
}
$handle = Resolve-VerifiedMinecraftWindowHandle $resolvedMinecraftProcessId $handle
$wasMinimized = [MinecraftBackgroundChatPost]::IsIconic($handle)
$foregroundWindowPreserved = $true
$cursorPositionPreserved = $true
if ($wasMinimized) {
    $restored = Set-MinecraftWindowStateWithoutInteraction `
        $handle `
        $resolvedMinecraftProcessId `
        4 `
        $false # SW_SHOWNOACTIVATE
    $handle = [IntPtr]$restored.Handle
}

$initialUiState = Get-ClientUiState
$normalizedUiState = $initialUiState
$chatOpenAttempts = 0
$respawned = $false
$testPlayerRecoveryApplied = $false
try {
    Assert-MinecraftIsNotForeground $handle
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
        if ($RecoverPlayerNearNpcForTest) {
            $testPlayerRecoveryApplied = Invoke-TestPlayerRecoveryNearNpc
        }
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
            Assert-MinecraftIsNotForeground $handle
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

        Assert-MinecraftIsNotForeground $handle
        if (-not [MinecraftBackgroundChatPost]::Text($handle, $Message)) {
            throw "Minecraft rejected background chat characters"
        }
        if (-not $DraftOnly) {
            if (-not [MinecraftBackgroundChatPost]::Key($handle, 0x0D, 0x1C)) { # Enter
                throw "Minecraft rejected the background Enter key message"
            }
            if (-not (Wait-ClientUiState "gameplay" 4000)) {
                throw "Minecraft chat did not close after the background message was submitted"
            }
        } else {
            $normalizedUiState = "chat"
        }
    }
} finally {
    if ($wasMinimized) {
        $minimized = Set-MinecraftWindowStateWithoutInteraction `
            $handle `
            $resolvedMinecraftProcessId `
            6 `
            $true # SW_MINIMIZE
        $handle = [IntPtr]$minimized.Handle
    }
}

[PSCustomObject]@{
    Sent = -not $NormalizeOnly -and -not $DraftOnly
    DraftOnly = [bool]$DraftOnly
    CharacterCount = if ($NormalizeOnly) { 0 } else { $Message.Length }
    MinecraftProcessId = $resolvedMinecraftProcessId
    InitialUiState = $initialUiState
    NormalizedUiState = $normalizedUiState
    ChatOpenAttempts = $chatOpenAttempts
    ChatOpenConfirmed = -not $NormalizeOnly
    GameplayRestored = -not $DraftOnly
    Respawned = $respawned
    TestPlayerRecoveryNearNpcRequested = [bool]$RecoverPlayerNearNpcForTest
    TestPlayerRecoveryNearNpcApplied = $testPlayerRecoveryApplied
    RestoredWithoutActivation = $wasMinimized
    MinimizedAfterSend = $wasMinimized
    CursorCaptureReleased = $true
    ForegroundWindowPreserved = $foregroundWindowPreserved
    CursorPositionPreserved = $cursorPositionPreserved
    ForegroundInteractionUsed = $false
    PhysicalMouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
