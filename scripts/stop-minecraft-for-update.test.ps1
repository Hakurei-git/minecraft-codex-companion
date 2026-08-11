$ErrorActionPreference = "Stop"
$scriptPath = Join-Path $PSScriptRoot "stop-minecraft-for-update.ps1"

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) {
        throw "Assertion failed: $Message (expected=$Expected actual=$Actual)"
    }
}

function Get-ScriptFunctionText([string]$Path, [string]$Name) {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        $Path,
        [ref]$tokens,
        [ref]$errors
    )
    if ($errors.Count -gt 0) {
        throw "PowerShell parser rejected ${Path}: $($errors[0].Message)"
    }
    $functionAst = $ast.Find({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $Name
    }, $true)
    if ($null -eq $functionAst) { throw "Function $Name was not found in $Path" }
    return $functionAst.Extent.Text
}

. ([ScriptBlock]::Create((Get-ScriptFunctionText $scriptPath "Get-MinecraftGuiScale")))
. ([ScriptBlock]::Create((Get-ScriptFunctionText $scriptPath "Get-PauseMenuDisconnectPoint")))

$fullHdAuto = Get-PauseMenuDisconnectPoint 1920 1080 0 $true
Assert-Equal $fullHdAuto.Scale 4 "Full HD Auto GUI scale"
Assert-Equal $fullHdAuto.GuiWidth 480 "Full HD GUI width"
Assert-Equal $fullHdAuto.GuiHeight 270 "Full HD GUI height"
Assert-Equal $fullHdAuto.X 960 "Full HD disconnect X"
Assert-Equal $fullHdAuto.Y 800 "Full HD disconnect Y"

$hdAuto = Get-PauseMenuDisconnectPoint 1280 720 0 $false
Assert-Equal $hdAuto.Scale 3 "720p Auto GUI scale without forced Unicode"
Assert-Equal $hdAuto.GuiWidth 427 "720p rounded GUI width"
Assert-Equal $hdAuto.GuiHeight 240 "720p GUI height"
Assert-Equal $hdAuto.Y 579 "720p positive half alignment rounds up"

$hdUnicode = Get-PauseMenuDisconnectPoint 1280 720 0 $true
Assert-Equal $hdUnicode.Scale 4 "forced Unicode promotes an odd Auto scale"
Assert-Equal $hdUnicode.GuiWidth 320 "forced-Unicode GUI width"
Assert-Equal $hdUnicode.GuiHeight 180 "forced-Unicode GUI height"
Assert-Equal $hdUnicode.Y 712 "negative half alignment uses Java Math.round semantics"

$fullHdScaleTwo = Get-PauseMenuDisconnectPoint 1920 1080 2 $false
Assert-Equal $fullHdScaleTwo.Scale 2 "explicit GUI scale"
Assert-Equal $fullHdScaleTwo.GuiHeight 540 "explicit-scale GUI height"
Assert-Equal $fullHdScaleTwo.Y 536 "positive half alignment rounds up"

$limitedScale = Get-MinecraftGuiScale 640 360 4 $false
Assert-Equal $limitedScale 1 "vanilla minimum GUI dimensions cap explicit scale"

$limitedUnicodeScale = Get-MinecraftGuiScale 640 360 4 $true
Assert-Equal $limitedUnicodeScale 2 "forced Unicode promotes a capped odd scale"

$source = Get-Content -LiteralPath $scriptPath -Raw -Encoding UTF8
if (-not $source.Contains('function Get-ClientUiState')) {
    throw "Save and Quit does not inspect the Minecraft client UI state"
}
if (-not $source.Contains('if ($clientUiState -ne "pause")')) {
    throw "Save and Quit does not require a confirmed pause menu"
}
if (-not $source.Contains('PauseMenuConfirmed = $true')) {
    throw "Save and Quit evidence omits the confirmed pause menu"
}
if (-not $source.Contains('CursorCaptureReleased = $true')) {
    throw "Save and Quit evidence omits cursor-capture release"
}
if (-not $source.Contains('Arm-BackgroundPauseLease') -or
    -not $source.Contains('suite = "save-and-quit"; mode = "arm"') -or
    -not $source.Contains('BackgroundPauseLeaseArmed = $true')) {
    throw "Save and Quit does not require an acknowledged background pause lease"
}
if (-not $source.Contains('Key($handle, 0x1B, 0x01)')) {
    throw "Save and Quit Escape does not include the GLFW scan code"
}
if (-not $source.Contains('ShowWindowAsync($handle, 6) # SW_MINIMIZE')) {
    throw "Save and Quit failures do not release cursor capture"
}
if (-not $source.Contains('ClipCursor(IntPtr.Zero)') -or
    -not $source.Contains('ReleaseCursorCapture($handle)')) {
    throw "Save and Quit does not actively release GLFW cursor confinement"
}
if (-not $source.Contains('active-minecraft-window.status') -or
    -not $source.Contains('[MinecraftGracefulClosePost]::IsWindow($candidateHandle)') -or
    -not $source.Contains('[MinecraftGracefulClosePost]::GetWindowThreadProcessId(')) {
    throw "Save and Quit does not safely recover a minimized background Minecraft window"
}
if (-not $source.Contains('stop-minecraft-via-agent.ps1') -or
    -not $source.Contains('AgentFallbackUsed = $true') -or
    -not $source.Contains('VanillaClientStopRequested = $true')) {
    throw "Save and Quit does not gracefully stop a windowless Minecraft client"
}

Write-Output "Minecraft PauseScreen coordinate tests passed"
