[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Suite,

    [Parameter(Mandatory = $true)]
    [string]$Mode,

    [hashtable]$Arguments = @{},

    [ValidateRange(1, 30)]
    [int]$WaitSeconds = 8
)

$ErrorActionPreference = "Stop"
$baseUri = [Uri]"http://127.0.0.1:8765/"
if ($baseUri.Host -notin @("127.0.0.1", "localhost", "::1")) {
    throw "Live fixtures must use the loopback control service"
}

$companions = @(Invoke-RestMethod -Uri ([Uri]::new($baseUri, "api/companions")) -TimeoutSec 5).companions
$companion = $companions |
    Where-Object { $_.connected -eq $true -and $_.embodiment -eq "in-world-npc" } |
    Select-Object -First 1
if ($null -eq $companion) { throw "No connected Forge in-world NPC was found" }

$body = [ordered]@{ suite = $Suite; mode = $Mode }
foreach ($entry in $Arguments.GetEnumerator()) {
    if ([string]$entry.Key -eq "command") { throw "Arbitrary Minecraft commands are not accepted" }
    $body[[string]$entry.Key] = $entry.Value
}
$before = [long]$companion.snapshot.sequence
$response = Invoke-RestMethod `
    -Uri ([Uri]::new($baseUri, "api/companions/$([Uri]::EscapeDataString([string]$companion.id))/live-fixtures")) `
    -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Body ($body | ConvertTo-Json -Compress) `
    -TimeoutSec 5

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$after = $before
do {
    Start-Sleep -Milliseconds 150
    $current = Invoke-RestMethod `
        -Uri ([Uri]::new($baseUri, "api/companions/$([Uri]::EscapeDataString([string]$companion.id))/snapshot")) `
        -TimeoutSec 5
    $after = [long]$current.sequence
} while ($after -le $before -and (Get-Date) -lt $deadline)
if ($after -le $before) { throw "Minecraft did not acknowledge the live fixture before timeout" }

[PSCustomObject]@{
    FixtureAccepted = [bool]$response.ok
    Suite = [string]$response.suite
    Mode = [string]$response.mode
    CompanionId = [string]$companion.id
    SnapshotAdvanced = $true
    ForegroundInteractionUsed = $false
    MouseOrKeyboardInputUsed = $false
    ClipboardUsed = $false
    ScreenshotUsed = $false
}
