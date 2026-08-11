[CmdletBinding()]
param(
    [switch]$ForcePauseMenu
)

$ErrorActionPreference = "Stop"
$baseUri = [Uri]"http://127.0.0.1:8765/"
if ($baseUri.Host -notin @("127.0.0.1", "localhost", "::1")) {
    throw "The live check must use the loopback control service"
}

function Get-SnapshotSequence {
    $response = Invoke-RestMethod -Uri ([Uri]::new($baseUri, "api/companions")) -TimeoutSec 5
    $companion = @($response.companions) |
        Where-Object { $_.connected -eq $true -and $_.embodiment -eq "in-world-npc" } |
        Select-Object -First 1
    if ($null -eq $companion) { throw "No connected Forge in-world NPC was found" }
    return [long]$companion.snapshot.sequence
}

$before = Get-SnapshotSequence
Start-Sleep -Milliseconds 1400
$after = Get-SnapshotSequence
if (-not $ForcePauseMenu -and $after -gt $before) {
    [PSCustomObject]@{
        Running = $true
        Resumed = $false
        SnapshotAdvanced = $true
        MouseOrKeyboardInputUsed = $false
        ClipboardUsed = $false
        ScreenshotUsed = $false
    }
    return
}

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if (-not ("MinecraftResumePost" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class MinecraftResumePost
{
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")]
    public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")]
    public static extern bool PostMessage(IntPtr hWnd, uint message, IntPtr wParam, IntPtr lParam);
    public static void Key(IntPtr hWnd, int key)
    {
        PostMessage(hWnd, 0x0100, (IntPtr)key, IntPtr.Zero);
        PostMessage(hWnd, 0x0101, (IntPtr)key, IntPtr.Zero);
    }
    public static void Click(IntPtr hWnd, int x, int y)
    {
        IntPtr point = (IntPtr)((y << 16) | (x & 0xffff));
        PostMessage(hWnd, 0x0200, IntPtr.Zero, point);
        PostMessage(hWnd, 0x0201, (IntPtr)1, point);
        PostMessage(hWnd, 0x0202, IntPtr.Zero, point);
    }
}
'@
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
if ($null -eq $minecraft) { throw "Minecraft Forge window is unavailable" }
$handle = [IntPtr]$minecraft.Current.NativeWindowHandle
if ($ForcePauseMenu) {
    $rect = [MinecraftResumePost+RECT]::new()
    if (-not [MinecraftResumePost]::GetClientRect($handle, [ref]$rect)) {
        throw "Minecraft client bounds are unavailable"
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -lt 640 -or $height -lt 360) { throw "Minecraft client window is unexpectedly small" }
    # The top centered button in the vanilla pause menu is Return to Game.
    [MinecraftResumePost]::Click($handle, [Math]::Round($width * 0.50), [Math]::Round($height * 0.29))
} else {
    [MinecraftResumePost]::Key($handle, 0x1B)
}

Start-Sleep -Milliseconds 1800
$resumed = Get-SnapshotSequence
if ($resumed -le $after) { throw "Minecraft did not resume after the targeted Escape message" }

[PSCustomObject]@{
    Running = $true
    Resumed = $true
    PauseMenuClickPosted = [bool]$ForcePauseMenu
    SnapshotAdvanced = $true
    MinecraftProcessId = $minecraft.Current.ProcessId
    MouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
