[CmdletBinding()]
param(
    [ValidateRange(5, 180)]
    [int]$WaitSeconds = 60
)

$ErrorActionPreference = "Stop"
throw (
    "MANUAL_UI_REQUIRED: start the selected HMCL source instance yourself. " +
    "Automatic launcher restoration, invocation, minimization, and window control are disabled."
)
