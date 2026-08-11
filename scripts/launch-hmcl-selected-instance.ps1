[CmdletBinding()]
param(
    [ValidateRange(5, 180)]
    [int]$WaitSeconds = 60
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
if (-not ("HmclSelectedInstanceWindow" -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class HmclSelectedInstanceWindow
{
    [DllImport("user32.dll")]
    public static extern bool ShowWindowAsync(IntPtr hWnd, int nCmdShow);
}
'@
}

$configPath = Join-Path $env:LOCALAPPDATA "MinecraftCodexCompanion\launcher-config.json"
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Minecraft Codex Companion launcher configuration is missing"
}
$config = Get-Content -LiteralPath $configPath -Raw -Encoding utf8 | ConvertFrom-Json
if (([string]::IsNullOrWhiteSpace([string]$config.launcherPath)) -or
    (-not (Test-Path -LiteralPath ([string]$config.launcherPath) -PathType Leaf))) {
    throw "Configured HMCL launcher is unavailable"
}

function Find-HmclWindow {
    $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    foreach ($window in $windows) {
        try {
            if ([string]$window.Current.Name -match "HMCL|Hello Minecraft") { return $window }
        } catch {
            # A window can disappear while the UI Automation tree is sampled.
        }
    }
    return $null
}

$window = Find-HmclWindow
$launcherStarted = $false
$launcherRestored = $false
if ($null -eq $window) {
    # HMCL can remain minimized to the tray after Minecraft exits. Reuse a
    # windowed Java process only when restoring it makes a real HMCL window
    # visible to UI Automation; never inspect the Java command line.
    foreach ($candidate in @(Get-Process -Name "java" -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 })) {
        [void][HmclSelectedInstanceWindow]::ShowWindowAsync([IntPtr]$candidate.MainWindowHandle, 9) # SW_RESTORE
        Start-Sleep -Milliseconds 700
        $window = Find-HmclWindow
        if ($null -ne $window -and $window.Current.ProcessId -eq $candidate.Id) {
            $launcherRestored = $true
            break
        }
        [void][HmclSelectedInstanceWindow]::ShowWindowAsync([IntPtr]$candidate.MainWindowHandle, 6) # SW_MINIMIZE
        $window = $null
    }
}
if ($null -eq $window) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = [string]$config.launcherPath
    $info.Arguments = [string]$config.launcherArguments
    $info.WorkingDirectory = Split-Path -Parent ([string]$config.launcherPath)
    $info.UseShellExecute = $true
    # HMCL may minimize-to-tray when it inherits a minimized startup state.
    # Start normally, invoke through UI Automation without mouse input, then
    # minimize immediately after the launch command has been accepted.
    $info.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Normal
    [void][System.Diagnostics.Process]::Start($info)
    $launcherStarted = $true
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ($null -eq $window -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 400
    $window = Find-HmclWindow
}
if ($null -eq $window) { throw "HMCL main window was not exposed before timeout" }

$all = $window.FindAll(
    [System.Windows.Automation.TreeScope]::Descendants,
    [System.Windows.Automation.Condition]::TrueCondition
)
$launchElement = $null
foreach ($element in $all) {
    try {
        if ([string]$element.Current.Name -match "启动游戏|Launch Game") {
            $launchElement = $element
            break
        }
    } catch {
        # Ignore transient UI elements and continue searching.
    }
}
if ($null -eq $launchElement) { throw "HMCL launch control was not exposed" }

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
$invokable = $launchElement
$invokePattern = $null
for ($depth = 0; $depth -lt 6 -and $null -ne $invokable; $depth++) {
    try {
        $invokePattern = $invokable.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
        break
    } catch {
        $invokable = $walker.GetParent($invokable)
    }
}
if ($null -eq $invokePattern) { throw "HMCL launch control has no invoke pattern" }

$invokePattern.Invoke()
Start-Sleep -Seconds 1
$handle = [IntPtr]$window.Current.NativeWindowHandle
if ($handle -ne [IntPtr]::Zero) {
    # SW_MINIMIZE. No cursor movement, key injection, or foreground request.
    [void][HmclSelectedInstanceWindow]::ShowWindowAsync($handle, 6)
}

[PSCustomObject]@{
    LauncherStarted = $launcherStarted
    LauncherRestored = $launcherRestored
    LaunchInvoked = $true
    MinimizedAfterInvoke = $true
    TargetVersion = [string]$config.targetVersion
    MouseOrKeyboardInputUsed = $false
    ScreenshotUsed = $false
}
