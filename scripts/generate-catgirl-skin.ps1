[CmdletBinding()]
param(
    [string]$SourceTexture = "",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $SourceTexture) {
    $SourceTexture = Join-Path $projectRoot "assets\third_party\queen-cats-dogs\humanoid_cat_white.png"
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $projectRoot "mods\forge-1.20.1\src\main\resources\assets\minecraft_codex_bridge\textures\entity"
}

$source = (Resolve-Path -LiteralPath $SourceTexture).Path
Add-Type -AssemblyName System.Drawing
$image = [System.Drawing.Image]::FromFile($source)
try {
    if ($image.Width -ne 128 -or $image.Height -ne 64) {
        throw "Queen Cats & Dogs texture must be 128x64, got $($image.Width)x$($image.Height)"
    }
} finally {
    $image.Dispose()
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$destination = Join-Path $OutputDirectory "codex_catgirl.png"
Copy-Item -LiteralPath $source -Destination $destination -Force

$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$destinationHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
if ($destinationHash -ne $sourceHash) {
    throw "Copied texture failed SHA-256 verification"
}

[PSCustomObject]@{
    Texture = $destination
    Source = $source
    Sha256 = $destinationHash
    Unmodified = $true
}
