param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArguments
)

$ErrorActionPreference = "Stop"
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settingsPath = Join-Path $repository "config\agent-settings.json"

function Expand-Setting([string]$value) {
    return $value.Replace("%USERPROFILE%", $env:USERPROFILE).Replace("%LOCALAPPDATA%", $env:LOCALAPPDATA)
}

function Resolve-Java([string]$javaHome, [string]$role) {
    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        $candidate = Join-Path (Expand-Setting $javaHome) "bin\java.exe"
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "$role Java executable is missing: $candidate"
        }
        return [System.IO.Path]::GetFullPath($candidate)
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw "$role Java executable was not found" }
    return $command.Source
}

if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    throw "Agent settings file is missing: config\agent-settings.json"
}
$settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
$agentJava = Resolve-Java $settings.agentJavaHome "Agent"
$targetJava = Resolve-Java $settings.targetJavaHome "Target"

$cliJar = Get-ChildItem -LiteralPath (Join-Path $repository "algorithm-debug-cli\target") `
    -Filter "algorithm-debug-cli-*-all.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $cliJar) { throw "ADA CLI JAR was not found. Run scripts\build-agent.ps1 first." }
$codePathJar = Get-ChildItem -LiteralPath (Join-Path $repository "tools\code-path-tracer-junit-launcher\target") `
    -Filter "code-path-tracer-junit-launcher-*.jar" -File -ErrorAction SilentlyContinue | `
    Where-Object { $_.Name -notlike "original-*" } | Select-Object -First 1
$jdwpJar = Join-Path $repository "tools\jdwp-collector\jdwp-batch-collector.jar"
if ($null -eq $codePathJar) { throw "CodePath Launcher JAR was not found. Run scripts\build-agent.ps1 first." }
if (-not (Test-Path -LiteralPath $jdwpJar -PathType Leaf)) {
    throw "JDWP Collector JAR was not found. Run scripts\build-agent.ps1 first."
}

$env:ADA_CODEPATH_LAUNCHER_JAR = $codePathJar.FullName
$env:ADA_JDWP_COLLECTOR_JAR = $jdwpJar
$env:ADA_TARGET_JAVA_HOME = Split-Path -Parent (Split-Path -Parent $targetJava)
if (-not [string]::IsNullOrWhiteSpace($settings.mavenExecutable)) {
    $maven = [System.IO.Path]::GetFullPath((Expand-Setting $settings.mavenExecutable))
    if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
        throw "Configured Maven executable is missing: $maven"
    }
    $env:ADA_MAVEN_EXECUTABLE = $maven
}
else {
    Remove-Item Env:ADA_MAVEN_EXECUTABLE -ErrorAction SilentlyContinue
}

# The Agent starts with its absolute Java executable. Maven/JUnit inherits the target JDK only.
$targetHome = $env:ADA_TARGET_JAVA_HOME
$env:JAVA_HOME = $targetHome
$env:PATH = (Join-Path $targetHome "bin") + [System.IO.Path]::PathSeparator + $env:PATH

& $agentJava -jar $cliJar.FullName @CliArguments
exit $LASTEXITCODE
