[CmdletBinding()]
param(
    [ValidateRange(10, 120)]
    [int]$WaitSeconds = 60,

    [ValidateLength(1, 256)]
    [string]$ControlBaseUri = "http://127.0.0.1:8765",

    [ValidateLength(0, 128)]
    [string]$CompanionId = "",

    [ValidateRange(0, 16)]
    [int]$GuiScale = 0,

    [bool]$ForceUnicodeFont = $true,

    [switch]$AsJson
)

$ErrorActionPreference = "Stop"
$controlUri = [Uri]$ControlBaseUri
if ($controlUri.Scheme -ne "http" -or
    $controlUri.Host.ToLowerInvariant() -notin @("127.0.0.1", "localhost", "::1", "[::1]")) {
    throw "ControlBaseUri must be an HTTP loopback URL"
}
$companionsUri = [Uri]::new($controlUri, "/api/companions").AbsoluteUri

function Get-TargetCompanion {
    $response = Invoke-RestMethod -Uri $companionsUri -TimeoutSec 3
    return @($response.companions) | Where-Object {
        $_.connected -eq $true -and
        $_.embodiment -eq "in-world-npc" -and
        ([string]::IsNullOrWhiteSpace($CompanionId) -or $_.id -eq $CompanionId)
    } | Select-Object -First 1
}

function Get-TargetSnapshot {
    $companion = Get-TargetCompanion
    if ($null -eq $companion) { return $null }
    $id = [Uri]::EscapeDataString([string]$companion.id)
    $snapshotUri = [Uri]::new($controlUri, "/api/companions/$id/snapshot").AbsoluteUri
    return Invoke-RestMethod -Uri $snapshotUri -TimeoutSec 3
}

function Get-ClientUiState {
    $snapshot = Get-TargetSnapshot
    if ($null -eq $snapshot) { return "disconnected" }
    $state = [string]$snapshot.clientUiState
    if ($state -notin @("gameplay", "chat", "pause", "death", "other")) {
        throw "Minecraft client UI state is unavailable"
    }
    return $state
}

function Arm-BackgroundPauseLease {
    $companion = Get-TargetCompanion
    if ($null -eq $companion) {
        throw "Minecraft bridge disconnected before the background pause lease could be armed"
    }
    $before = Get-TargetSnapshot
    $beforeSequence = if ($null -eq $before.liveFixtureAck) {
        0L
    } else {
        [long]$before.liveFixtureAck.sequence
    }
    $id = [Uri]::EscapeDataString([string]$companion.id)
    $fixtureUri = [Uri]::new($controlUri, "/api/companions/$id/live-fixtures").AbsoluteUri
    $body = @{ suite = "save-and-quit"; mode = "arm" } | ConvertTo-Json -Compress
    [void](Invoke-RestMethod -Uri $fixtureUri -Method Post -ContentType "application/json" `
        -Body $body -TimeoutSec 5)

    $deadline = (Get-Date).AddSeconds(8)
    do {
        Start-Sleep -Milliseconds 100
        $current = Get-TargetSnapshot
        $acknowledgement = $current.liveFixtureAck
        if ($null -ne $acknowledgement -and
            [long]$acknowledgement.sequence -gt $beforeSequence -and
            [string]$acknowledgement.suite -eq "save-and-quit" -and
            [string]$acknowledgement.mode -eq "arm" -and
            [string]$acknowledgement.status -eq "save-and-quit:armed leaseMs=30000") {
            return [long]$acknowledgement.sequence
        }
    } while ((Get-Date) -lt $deadline)
    throw "Minecraft did not acknowledge the background pause lease before timeout"
}

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if (-not ("MinecraftGracefulClosePost" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftGracefulClosePost
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
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
    public static bool Key(IntPtr hWnd, int key, int scanCode)
    {
        long down = 1L | ((long)scanCode << 16);
        long up = down | (1L << 30) | (1L << 31);
        return PostMessage(hWnd, 0x0100, (IntPtr)key, (IntPtr)down)
            && PostMessage(hWnd, 0x0101, (IntPtr)key, (IntPtr)up);
    }
    public static void Click(IntPtr hWnd, int x, int y)
    {
        IntPtr point = (IntPtr)((y << 16) | (x & 0xffff));
        PostMessage(hWnd, 0x0200, IntPtr.Zero, point);
        PostMessage(hWnd, 0x0201, (IntPtr)1, point);
        PostMessage(hWnd, 0x0202, IntPtr.Zero, point);
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

function Get-MinecraftGuiScale(
    [int]$ClientWidth,
    [int]$ClientHeight,
    [int]$RequestedGuiScale = 0,
    [bool]$UseForceUnicodeFont = $true
) {
    if ($ClientWidth -lt 1 -or $ClientHeight -lt 1) {
        throw "Minecraft client dimensions must be positive"
    }
    if ($RequestedGuiScale -lt 0 -or $RequestedGuiScale -gt 16) {
        throw "Minecraft GUI scale must be between 0 and 16"
    }

    # Mirrors Window.calculateScale. A requested value of zero means the
    # vanilla Auto setting; force-Unicode then promotes an odd result to the
    # next even scale exactly as the 1.20.1 client does.
    $scale = 1
    $widthLimit = [Math]::Floor($ClientWidth / 320)
    $heightLimit = [Math]::Floor($ClientHeight / 240)
    while ($scale -ne $RequestedGuiScale -and
        $scale -lt $widthLimit -and
        $scale -lt $heightLimit) {
        $scale += 1
    }
    if ($UseForceUnicodeFont -and $scale % 2 -ne 0) {
        $scale += 1
    }
    return $scale
}

function Get-PauseMenuDisconnectPoint(
    [int]$ClientWidth,
    [int]$ClientHeight,
    [int]$RequestedGuiScale = 0,
    [bool]$UseForceUnicodeFont = $true
) {
    $scale = Get-MinecraftGuiScale `
        $ClientWidth $ClientHeight $RequestedGuiScale $UseForceUnicodeFont
    $guiWidth = [Math]::Ceiling($ClientWidth / $scale)
    $guiHeight = [Math]::Ceiling($ClientHeight / $scale)

    # Forge 1.20.1 PauseScreen uses a two-column GridLayout. Its first row has
    # 50 px top padding, each button is 20 px high, subsequent rows add 4 px
    # top padding, and Forge inserts a full-width Mods row. The resulting grid
    # is 190 GUI px high; the disconnect/save-and-quit button center is 180 px
    # below the grid origin. FrameLayout aligns that grid at 25% vertically.
    $pauseMenuHeight = 190
    $disconnectCenterOffset = 180
    # Java Math.round(x) is floor(x + 0.5), including negative half values.
    $gridTop = [Math]::Floor((($guiHeight - $pauseMenuHeight) * 0.25) + 0.5)
    $disconnectCenterGuiY = $gridTop + $disconnectCenterOffset

    # MouseHandler converts client pixels back into GUI coordinates with the
    # actual rounded GUI dimensions, so use the inverse conversion rather than
    # multiplying by scale and accumulating a ceil-rounding error.
    return [PSCustomObject]@{
        X = [int][Math]::Round($ClientWidth * 0.5, [MidpointRounding]::AwayFromZero)
        Y = [int][Math]::Round(
            $disconnectCenterGuiY * $ClientHeight / $guiHeight,
            [MidpointRounding]::AwayFromZero
        )
        Scale = $scale
        GuiWidth = [int]$guiWidth
        GuiHeight = [int]$guiHeight
    }
}

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
    } catch {}
}
$handle = [IntPtr]::Zero
$processId = 0
$fallbackProcessId = 0
if ($null -ne $minecraft) {
    $handle = [IntPtr]$minecraft.Current.NativeWindowHandle
    $processId = [int]$minecraft.Current.ProcessId
} else {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $windowStatusPath = Join-Path $projectRoot ".runtime\hmcl-background-agent\active-minecraft-window.status"
    if (Test-Path -LiteralPath $windowStatusPath -PathType Leaf) {
        $windowStatus = (Get-Content -LiteralPath $windowStatusPath -Raw -Encoding ASCII).Trim()
        if ($windowStatus -match '^PID=(?<pid>[1-9][0-9]*);HWND=(?<hwnd>[1-9][0-9]*)$') {
            $candidateProcessId = [int]$Matches.pid
            $candidateHandle = [IntPtr][long]$Matches.hwnd
            $candidateProcess = Get-Process -Id $candidateProcessId -ErrorAction SilentlyContinue
            $windowProcessId = [uint32]0
            if ($null -ne $candidateProcess -and $candidateProcess.ProcessName -in @("java", "javaw")) {
                $fallbackProcessId = $candidateProcessId
            }
            if ($fallbackProcessId -gt 0 -and
                [MinecraftGracefulClosePost]::IsWindow($candidateHandle) -and
                [MinecraftGracefulClosePost]::GetWindowThreadProcessId(
                    $candidateHandle,
                    [ref]$windowProcessId
                ) -ne 0 -and
                $windowProcessId -eq [uint32]$candidateProcessId) {
                $handle = $candidateHandle
                $processId = $candidateProcessId
            }
        }
    }
}
if ($handle -eq [IntPtr]::Zero -or $processId -le 0) {
    if ($fallbackProcessId -gt 0) {
        $pauseLeaseAckSequence = Arm-BackgroundPauseLease
        $fallback = & (Join-Path $PSScriptRoot "stop-minecraft-via-agent.ps1") `
            -MinecraftProcessId $fallbackProcessId `
            -WaitSeconds $WaitSeconds
        $result = [PSCustomObject]@{
            SavedAndClosed = [bool]$fallback.SavedAndClosed
            LeftWorldBeforeWindowClose = $true
            RestoredWithoutActivation = $false
            MinecraftProcessId = $fallbackProcessId
            GuiScale = $null
            ForceUnicodeFont = $ForceUnicodeFont
            PauseMenuClickX = $null
            PauseMenuClickY = $null
            PauseMenuConfirmed = $false
            BackgroundPauseLeaseArmed = $true
            BackgroundPauseLeaseAckSequence = $pauseLeaseAckSequence
            CursorCaptureReleased = $true
            PauseOpenAttempts = 0
            AgentFallbackUsed = $true
            VanillaClientStopRequested = $true
            ForcedTerminationUsed = $false
            MouseOrKeyboardInputUsed = $false
            ClipboardUsed = $false
            ScreenshotUsed = $false
        }
        if ($AsJson) {
            $result | ConvertTo-Json -Compress
        } else {
            $result
        }
        return
    }
    throw "Minecraft Forge window is unavailable"
}

$rect = [MinecraftGracefulClosePost+RECT]::new()
if ($handle -eq [IntPtr]::Zero) {
    throw "Minecraft client bounds are unavailable"
}
try {
$pauseLeaseAckSequence = Arm-BackgroundPauseLease
$wasMinimized = [MinecraftGracefulClosePost]::IsIconic($handle)
if ($wasMinimized) {
    [void][MinecraftGracefulClosePost]::ShowWindowAsync($handle, 4) # SW_SHOWNOACTIVATE
    [MinecraftGracefulClosePost]::ReleaseCursorCapture($handle)
    $restoreDeadline = (Get-Date).AddSeconds(3)
    while ([MinecraftGracefulClosePost]::IsIconic($handle) -and (Get-Date) -lt $restoreDeadline) {
        Start-Sleep -Milliseconds 100
    }
    if ([MinecraftGracefulClosePost]::IsIconic($handle)) {
        throw "Minecraft window could not be restored without activation"
    }
    [MinecraftGracefulClosePost]::ReleaseCursorCapture($handle)
}
$boundsDeadline = (Get-Date).AddSeconds(5)
do {
    Start-Sleep -Milliseconds 100
    $boundsAvailable = [MinecraftGracefulClosePost]::GetClientRect($handle, [ref]$rect)
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
} while (($width -lt 640 -or $height -lt 360) -and (Get-Date) -lt $boundsDeadline)
if (-not $boundsAvailable) {
    throw "Minecraft client bounds are unavailable"
}
if ($width -lt 640 -or $height -lt 360) { throw "Minecraft client window is unexpectedly small" }

# Reach a semantically confirmed pause menu before clicking. Blindly posting
# one Escape can close an already-open pause menu and turn the later click into
# an in-world mouse event. Chat needs one Escape to close and another to pause.
$initialClientUiState = Get-ClientUiState
if ($initialClientUiState -eq "disconnected") {
    throw "Minecraft bridge disconnected before Save and Quit"
}
if ($initialClientUiState -eq "death") {
    throw "Minecraft cannot Save and Quit while the client is on the death screen"
}
$clientUiState = $initialClientUiState
$pauseOpenAttempts = 0
while ($clientUiState -ne "pause" -and $pauseOpenAttempts -lt 3) {
    [MinecraftGracefulClosePost]::ReleaseCursorCapture($handle)
    if (-not [MinecraftGracefulClosePost]::Key($handle, 0x1B, 0x01)) {
        throw "Minecraft rejected the background Escape key message"
    }
    $pauseOpenAttempts += 1
    $stateDeadline = (Get-Date).AddSeconds(2)
    do {
        Start-Sleep -Milliseconds 100
        $clientUiState = Get-ClientUiState
    } while ($clientUiState -eq $initialClientUiState -and (Get-Date) -lt $stateDeadline)
    $initialClientUiState = $clientUiState
}
if ($clientUiState -ne "pause") {
    throw "Minecraft pause menu could not be confirmed before Save and Quit"
}

# Invoke the vanilla Save and Quit button. The client and its integrated server
# own the save lifecycle; /save-all requires a higher command permission than
# ordinary single-player cheats provide.
$pauseMenuPoint = Get-PauseMenuDisconnectPoint `
    $width $height $GuiScale $ForceUnicodeFont
[MinecraftGracefulClosePost]::Click(
    $handle,
    $pauseMenuPoint.X,
    $pauseMenuPoint.Y
)

$disconnectDeadline = (Get-Date).AddSeconds([Math]::Min(30, $WaitSeconds))
$leftWorldBeforeWindowClose = $false
do {
    Start-Sleep -Milliseconds 500
    [MinecraftGracefulClosePost]::ReleaseCursorCapture($handle)
    try {
        $response = Invoke-RestMethod -Uri $companionsUri -TimeoutSec 3
        $connected = @($response.companions) | Where-Object {
            $_.connected -eq $true -and
            $_.embodiment -eq "in-world-npc" -and
            ([string]::IsNullOrWhiteSpace($CompanionId) -or $_.id -eq $CompanionId)
        } | Select-Object -First 1
        if ($null -eq $connected) {
            $leftWorldBeforeWindowClose = $true
            break
        }
    } catch {}
} while ((Get-Date) -lt $disconnectDeadline)

# WM_CLOSE lets Minecraft and the integrated server run their normal shutdown
# and save path when the background pause-menu click was ignored.
[void][MinecraftGracefulClosePost]::PostMessage($handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
    if ((Get-Date) -ge $deadline) { throw "Minecraft did not close gracefully before timeout" }
    Start-Sleep -Milliseconds 500
}

$result = [PSCustomObject]@{
    SavedAndClosed = $true
    LeftWorldBeforeWindowClose = $leftWorldBeforeWindowClose
    RestoredWithoutActivation = $wasMinimized
    MinecraftProcessId = $processId
    GuiScale = $pauseMenuPoint.Scale
    ForceUnicodeFont = $ForceUnicodeFont
    PauseMenuClickX = $pauseMenuPoint.X
    PauseMenuClickY = $pauseMenuPoint.Y
    PauseMenuConfirmed = $true
    BackgroundPauseLeaseArmed = $true
    BackgroundPauseLeaseAckSequence = $pauseLeaseAckSequence
    CursorCaptureReleased = $true
    PauseOpenAttempts = $pauseOpenAttempts
    ForcedTerminationUsed = $false
    MouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
} catch {
    [MinecraftGracefulClosePost]::ReleaseCursorCapture($handle)
    if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
        [void][MinecraftGracefulClosePost]::ShowWindowAsync($handle, 6) # SW_MINIMIZE
    }
    [MinecraftGracefulClosePost]::ReleaseCursorCapture($handle)
    throw
}
if ($AsJson) {
    $result | ConvertTo-Json -Compress
} else {
    $result
}
