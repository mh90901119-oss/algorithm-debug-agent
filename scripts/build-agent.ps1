param()

$ErrorActionPreference = "Stop"
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settings = Get-Content -LiteralPath (Join-Path $repository "config\agent-settings.json") `
    -Raw -Encoding UTF8 | ConvertFrom-Json

function Expand-Setting([string]$value) {
    return $value.Replace("%USERPROFILE%", $env:USERPROFILE).Replace("%LOCALAPPDATA%", $env:LOCALAPPDATA)
}

$agentJavaHome = if ([string]::IsNullOrWhiteSpace($settings.agentJavaHome)) {
    $env:JAVA_HOME
} else {
    [System.IO.Path]::GetFullPath((Expand-Setting $settings.agentJavaHome))
}
if ([string]::IsNullOrWhiteSpace($agentJavaHome)) {
    throw "agentJavaHome is empty and JAVA_HOME is not configured"
}
$agentJava = Join-Path $agentJavaHome "bin\java.exe"
if (-not (Test-Path -LiteralPath $agentJava -PathType Leaf)) {
    throw "Agent Java executable is missing: $agentJava"
}
$previousErrorActionPreference = $ErrorActionPreference
try {
    # java -version writes normal version text to stderr; Windows PowerShell must not promote it.
    $ErrorActionPreference = "Continue"
    $versionOutput = & $agentJava -version 2>&1
    $versionExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($versionExitCode -ne 0) { throw "Agent Java version check failed with exit code $versionExitCode" }
$versionText = ($versionOutput | ForEach-Object { $_.ToString() } | Select-Object -First 1).Trim()
if ($versionText -notmatch 'version "(?<major>[0-9]+)') {
    throw "Unable to determine Agent Java version: $versionText"
}
if ([int]$Matches.major -lt 21) {
    throw "Agent build requires Java 21 or newer; detected: $versionText"
}

if ([string]::IsNullOrWhiteSpace($settings.mavenExecutable)) {
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -eq $mavenCommand) { throw "Maven executable was not found" }
    $maven = $mavenCommand.Source
}
else {
    $maven = [System.IO.Path]::GetFullPath((Expand-Setting $settings.mavenExecutable))
    if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
        throw "Configured Maven executable is missing: $maven"
    }
}

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
Push-Location $repository
try {
    $env:JAVA_HOME = $agentJavaHome
    $env:PATH = (Join-Path $agentJavaHome "bin") + [System.IO.Path]::PathSeparator + $oldPath
    & $maven -Pcodepath-launcher package
    if ($LASTEXITCODE -ne 0) { throw "Agent Maven build failed with exit code $LASTEXITCODE" }
    $sourceCollector = Join-Path $repository "tools\jdwp-batch-collector\target\jdwp-batch-collector.jar"
    $collectorDirectory = Join-Path $repository "tools\jdwp-collector"
    if (-not (Test-Path -LiteralPath $sourceCollector -PathType Leaf)) {
        throw "JDWP Collector build artifact is missing: $sourceCollector"
    }
    New-Item -ItemType Directory -Path $collectorDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourceCollector `
        -Destination (Join-Path $collectorDirectory "jdwp-batch-collector.jar") -Force
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
    Pop-Location
}

Write-Output "AGENT_BUILD_OK"
Write-Output "AGENT_JAVA_HOME=$agentJavaHome"
Write-Output "MAVEN_EXECUTABLE=$maven"
