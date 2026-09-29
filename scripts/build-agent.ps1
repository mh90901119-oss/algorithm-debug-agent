param()

$ErrorActionPreference = "Stop"
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settings = Get-Content -LiteralPath (Join-Path $repository "config\agent-settings.json") `
    -Raw -Encoding UTF8 | ConvertFrom-Json
$hashAlgorithm = "SHA256"
$hashPattern = '^[a-f0-9]{64}$'

function Expand-Setting([string]$value) {
    return $value.Replace("%USERPROFILE%", $env:USERPROFILE).Replace("%LOCALAPPDATA%", $env:LOCALAPPDATA)
}

function Resolve-SingleArtifact([string]$directory, [string]$filter, [string]$role) {
    $matches = @(Get-ChildItem -LiteralPath $directory -Filter $filter -File `
        -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "original-*" })
    if ($matches.Count -ne 1) {
        throw "$role build must produce exactly one matching artifact; found $($matches.Count)"
    }
    if ($matches[0].Length -le 0) { throw "$role build artifact is empty" }
    return $matches[0]
}

function Get-VerifiedHash([string]$path, [string]$role) {
    $hash = (Get-FileHash -LiteralPath $path -Algorithm $hashAlgorithm).Hash.ToLowerInvariant()
    if ($hash -notmatch $hashPattern) { throw "$role SHA-256 is invalid" }
    return $hash
}

function Get-RepositoryRelativePath([string]$path) {
    $root = $repository.TrimEnd('\', '/')
    $full = [System.IO.Path]::GetFullPath($path)
    $prefix = $root + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Build artifact escapes the repository"
    }
    return $full.Substring($prefix.Length).Replace('\', '/')
}

function Assert-AgentAssetHash([string]$relativePath, [string]$expectedHash, [string]$role) {
    $path = Join-Path $repository $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$role is missing" }
    $actual = Get-VerifiedHash -Path $path -Role $role
    if ($actual -ne $expectedHash) { throw "$role hash does not match Agent Definition" }
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
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Maven and libraries may write non-fatal warnings to stderr. Preserve them and trust the exit code.
        $ErrorActionPreference = "Continue"
        & $maven -Pcodepath-launcher clean package
        $mavenExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($mavenExitCode -ne 0) { throw "Agent Maven build failed with exit code $mavenExitCode" }
    $sourceCollector = Join-Path $repository "tools\jdwp-batch-collector\target\jdwp-batch-collector.jar"
    $collectorDirectory = Join-Path $repository "tools\jdwp-collector"
    if (-not (Test-Path -LiteralPath $sourceCollector -PathType Leaf)) {
        throw "JDWP Collector build artifact is missing: $sourceCollector"
    }
    New-Item -ItemType Directory -Path $collectorDirectory -Force | Out-Null
    Copy-Item -LiteralPath $sourceCollector `
        -Destination (Join-Path $collectorDirectory "jdwp-batch-collector.jar") -Force

    $cliJar = Resolve-SingleArtifact `
        (Join-Path $repository "algorithm-debug-cli\target") `
        "algorithm-debug-cli-*-all.jar" "Java CLI"
    $codePathJar = Resolve-SingleArtifact `
        (Join-Path $repository "tools\code-path-tracer-junit-launcher\target") `
        "code-path-tracer-junit-launcher-*.jar" "CodePath Launcher"
    $jdwpJar = Get-Item -LiteralPath (Join-Path $collectorDirectory "jdwp-batch-collector.jar")
    $mcpJar = Resolve-SingleArtifact `
        (Join-Path $repository "algorithm-debug-mcp-server\target") `
        "algorithm-debug-mcp-server-*-all.jar" "MCP Server"

    $definition = Get-Content -LiteralPath `
        (Join-Path $repository "agent-definition\algorithm-debug-agent-v1.json") `
        -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-AgentAssetHash $definition.prompt.path $definition.prompt.sha256 "Canonical Prompt"
    Assert-AgentAssetHash $definition.capabilityManifest.path `
        $definition.capabilityManifest.sha256 "Capability Manifest"

    $jarTool = Join-Path $agentJavaHome "bin\jar.exe"
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) {
        throw "JDK jar executable is missing"
    }
    $mcpEntries = @(& $jarTool tf $mcpJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw "MCP Server JAR could not be inspected" }
    $requiredEntries = @(
        "org/example/algorithmdebug/mcp/AlgorithmDebugMcpMain.class",
        "agent-definition/algorithm-debug-agent-v1.json",
        "agent-definition/system-prompt-v1.md",
        "schemas/agent/algorithm-debug-agent-v1.schema.json",
        "schemas/coordination/conclusion-candidate-v2.schema.json",
        "schemas/coordination/conclusion-decision-v2.schema.json",
        "schemas/coordination/conclusion-finalization-v1.schema.json",
        "schemas/config/mcp-agent-settings-v1.schema.json",
        "META-INF/THIRD_PARTY_NOTICES.md"
    )
    foreach ($entry in $requiredEntries) {
        if ($mcpEntries -notcontains $entry) { throw "MCP Server JAR is missing $entry" }
    }

    $artifacts = @(
        [ordered]@{
            role = "JAVA_CLI"
            path = Get-RepositoryRelativePath -path $cliJar.FullName
            sha256 = Get-VerifiedHash -path $cliJar.FullName -role "Java CLI"
        },
        [ordered]@{
            role = "CODEPATH_LAUNCHER"
            path = Get-RepositoryRelativePath -path $codePathJar.FullName
            sha256 = Get-VerifiedHash -path $codePathJar.FullName -role "CodePath Launcher"
        },
        [ordered]@{
            role = "JDWP_COLLECTOR"
            path = Get-RepositoryRelativePath -path $jdwpJar.FullName
            sha256 = Get-VerifiedHash -path $jdwpJar.FullName -role "JDWP Collector"
        },
        [ordered]@{
            role = "MCP_SERVER"
            path = Get-RepositoryRelativePath -path $mcpJar.FullName
            sha256 = Get-VerifiedHash -path $mcpJar.FullName -role "MCP Server"
        }
    )
    $distributionDirectory = Join-Path $repository "target\agent-distribution"
    New-Item -ItemType Directory -Path $distributionDirectory -Force | Out-Null
    $manifestPath = Join-Path $distributionDirectory "artifact-manifest.json"
    $temporaryManifest = "$manifestPath.tmp"
    [ordered]@{ schemaVersion = "1.0"; artifacts = $artifacts } |
        ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $temporaryManifest -Encoding UTF8
    Move-Item -LiteralPath $temporaryManifest -Destination $manifestPath -Force
}
finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
    Pop-Location
}

Write-Output "AGENT_BUILD_OK"
Write-Output "AGENT_JAVA_HOME=$agentJavaHome"
Write-Output "MAVEN_EXECUTABLE=$maven"
Write-Output "ARTIFACT_MANIFEST=target\agent-distribution\artifact-manifest.json"
