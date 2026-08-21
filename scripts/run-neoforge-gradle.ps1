[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @("build")
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$neoforgeRoot = Join-Path $projectRoot "mods\neoforge-1.21.1"

function Get-JavaMajorVersion([string]$JavaExe) {
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $text = (& $JavaExe -version 2>&1 | Out-String)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    if ($exitCode -ne 0 -or $text -notmatch 'version\s+"(?<version>\d+)(?:\.(?<minor>\d+))?') { return 0 }
    $major = [int]$Matches.version
    if ($major -eq 1 -and $Matches.minor) { return [int]$Matches.minor }
    return $major
}

$candidateHomes = [System.Collections.Generic.List[string]]::new()
if ($env:MC_COMPANION_JAVA21_HOME) { $candidateHomes.Add($env:MC_COMPANION_JAVA21_HOME) }
if ($env:MC_COMPANION_JAVA_HOME) { $candidateHomes.Add($env:MC_COMPANION_JAVA_HOME) }
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) { $candidateHomes.Add((Split-Path -Parent (Split-Path -Parent $pathJava.Source))) }
if ($env:JAVA_HOME) { $candidateHomes.Add($env:JAVA_HOME) }

$selected = $null
foreach ($candidateHome in $candidateHomes | Select-Object -Unique) {
    $javaExe = Join-Path $candidateHome "bin\java.exe"
    if ((Test-Path -LiteralPath $javaExe) -and (Get-JavaMajorVersion $javaExe) -ge 21) {
        $selected = (Resolve-Path -LiteralPath $candidateHome).Path
        break
    }
}
if (-not $selected) {
    throw "NeoForge 1.21.1 requires Java 21 or newer. Set MC_COMPANION_JAVA21_HOME."
}

$env:JAVA_HOME = $selected
$env:Path = "$(Join-Path $selected 'bin');$env:Path"
if (-not $env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME = Join-Path $projectRoot "runtime\gradle-home"
}
if (-not $GradleArguments -or $GradleArguments.Count -eq 0) { $GradleArguments = @("build") }

Push-Location $neoforgeRoot
try {
    & ".\gradlew.bat" "--no-daemon" @GradleArguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
