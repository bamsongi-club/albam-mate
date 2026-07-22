[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

Get-Command git -ErrorAction Stop | Out-Null

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'SilentlyContinue'
    $repoRoot = & git -C $PSScriptRoot rev-parse --show-toplevel 2>$null
    $gitExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

if ($gitExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($repoRoot)) {
    throw 'Git repository not found. Initialize or clone the repository first.'
}

$repoRoot = $repoRoot.Trim()

$hookPath = Join-Path $repoRoot '.githooks\pre-commit'
if (-not (Test-Path -LiteralPath $hookPath -PathType Leaf)) {
    throw "Pre-commit hook not found: $hookPath"
}

$gradleWrapperPath = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradleWrapperPath -PathType Leaf)) {
    throw "Gradle Wrapper not found: $gradleWrapperPath"
}

& git -C $repoRoot config --local core.hooksPath .githooks
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to configure core.hooksPath.'
}

$configuredHooksPath = (& git -C $repoRoot config --local --get core.hooksPath).Trim()
if ($configuredHooksPath -ne '.githooks') {
    throw "Unexpected core.hooksPath: $configuredHooksPath"
}

Write-Host 'Git hooks enabled: core.hooksPath=.githooks'
