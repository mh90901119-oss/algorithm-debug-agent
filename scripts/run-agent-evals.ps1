param(
    [string]$Suite = "Smoke",
    [string]$Case,
    [string]$Model,
    [int]$TimeoutSeconds = 600,
    [switch]$FailFast
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $repositoryRoot "agent-evals\run.mjs"
$nodeArguments = @(
    $runner,
    "--suite", $Suite,
    "--timeout-seconds", $TimeoutSeconds.ToString()
)

if ($Case) {
    $nodeArguments += @("--case", $Case)
}
if ($Model) {
    $nodeArguments += @("--model", $Model)
}
if ($FailFast) {
    $nodeArguments += "--fail-fast"
}

& node @nodeArguments
exit $LASTEXITCODE
