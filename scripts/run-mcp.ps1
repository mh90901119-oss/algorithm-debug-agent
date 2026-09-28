param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot
)

$ErrorActionPreference = "Stop"
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settingsPath = Join-Path $repository "config\mcp-agent-settings.json"
$minimumJavaVersion = 21
$supportedSettingsVersion = "1.0"
$settingsFields = @(
    "schemaVersion",
    "workspaceDirectory",
    "agentJavaHome",
    "targetJavaHome",
    "mavenExecutable",
    "knowledgeDirectory"
)
$pathSettingsFields = @(
    "workspaceDirectory",
    "agentJavaHome",
    "targetJavaHome",
    "mavenExecutable",
    "knowledgeDirectory"
)

function Expand-AdaSetting {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    $expanded = $Value
    $variables = @(
        @{ token = "%USERPROFILE%"; name = "USERPROFILE"; value = $env:USERPROFILE },
        @{ token = "%LOCALAPPDATA%"; name = "LOCALAPPDATA"; value = $env:LOCALAPPDATA }
    )
    foreach ($variable in $variables) {
        if ($expanded.Contains($variable.token)) {
            if ([string]::IsNullOrWhiteSpace($variable.value)) {
                throw "$($variable.name) is not configured"
            }
            $expanded = $expanded.Replace($variable.token, $variable.value)
        }
    }
    return $expanded
}

function Resolve-AdaJava {
    param([string]$JavaHome, [string]$Role)
    if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
        $candidate = Join-Path (Expand-AdaSetting $JavaHome) "bin\java.exe"
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "$Role Java executable is missing"
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
    if ($null -eq $command) { throw "$Role Java executable was not found" }
    return $command.Source
}

function Resolve-SingleArtifact {
    param([string]$Directory, [string]$Filter, [string]$Role)
    $matches = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File `
        -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "original-*" })
    if ($matches.Count -ne 1) {
        throw "$Role artifact resolution expected exactly one file"
    }
    return $matches[0].FullName
}

function Assert-McpSettings {
    param([Parameter(Mandatory = $true)]$Settings)
    $actualFields = @($Settings.PSObject.Properties.Name | Sort-Object)
    $expectedFields = @($settingsFields | Sort-Object)
    if (($actualFields -join "`n") -ne ($expectedFields -join "`n")) {
        throw "MCP settings fields do not match the supported contract"
    }
    if ($Settings.schemaVersion -ne $supportedSettingsVersion) {
        throw "MCP settings schemaVersion is unsupported"
    }
    foreach ($field in $settingsFields) {
        if ($Settings.$field -isnot [string]) {
            throw "MCP setting $field must be a string"
        }
    }
    if ([string]::IsNullOrWhiteSpace($Settings.workspaceDirectory)) {
        throw "MCP workspaceDirectory must not be empty"
    }
    foreach ($field in $pathSettingsFields) {
        $configured = Expand-AdaSetting $Settings.$field
        if (-not [string]::IsNullOrWhiteSpace($configured) -and
            -not [System.IO.Path]::IsPathRooted($configured)) {
            throw "MCP setting $field must resolve to an absolute path"
        }
    }
}

if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) {
    throw "MCP settings file is missing"
}
$settings = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-McpSettings -Settings $settings
if (-not [System.IO.Path]::IsPathRooted($ProjectRoot)) {
    throw "Target project directory must be absolute"
}
$project = [System.IO.Path]::GetFullPath($ProjectRoot)
if (-not (Test-Path -LiteralPath $project -PathType Container)) {
    throw "Target project directory does not exist"
}
$workspace = [System.IO.Path]::GetFullPath((Expand-AdaSetting $settings.workspaceDirectory))
New-Item -ItemType Directory -Path $workspace -Force | Out-Null

$agentJava = Resolve-AdaJava -JavaHome $settings.agentJavaHome -Role "Agent"
$targetJava = if ([string]::IsNullOrWhiteSpace($settings.targetJavaHome)) {
    $agentJava
}
else {
    Resolve-AdaJava -JavaHome $settings.targetJavaHome -Role "Target"
}
$mcpJar = Resolve-SingleArtifact `
    -Directory (Join-Path $repository "algorithm-debug-mcp-server\target") `
    -Filter "algorithm-debug-mcp-server-*-all.jar" -Role "MCP Server"
$codePathJar = Resolve-SingleArtifact `
    -Directory (Join-Path $repository "tools\code-path-tracer-junit-launcher\target") `
    -Filter "code-path-tracer-junit-launcher-*.jar" -Role "CodePath Launcher"
$jdwpJar = Join-Path $repository "tools\jdwp-collector\jdwp-batch-collector.jar"
if (-not (Test-Path -LiteralPath $jdwpJar -PathType Leaf)) {
    throw "JDWP Collector artifact is missing"
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    # java -version writes its normal version banner to stderr.
    $ErrorActionPreference = "Continue"
    $versionOutput = & $agentJava -version 2>&1
    $versionExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$versionText = ($versionOutput | ForEach-Object { $_.ToString() } | Select-Object -First 1).Trim()
if ($versionExitCode -ne 0 -or $versionText -notmatch 'version "(?<major>[0-9]+)') {
    throw "Unable to determine Agent Java version"
}
if ([int]$Matches.major -lt $minimumJavaVersion) {
    throw "MCP Server requires Java $minimumJavaVersion or newer"
}

$env:ADA_CODEPATH_LAUNCHER_JAR = $codePathJar
$env:ADA_JDWP_COLLECTOR_JAR = $jdwpJar
$env:ADA_TARGET_JAVA_HOME = Split-Path -Parent (Split-Path -Parent $targetJava)
if ([string]::IsNullOrWhiteSpace($settings.mavenExecutable)) {
    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -eq $mavenCommand -or
        -not (Test-Path -LiteralPath $mavenCommand.Source -PathType Leaf)) {
        throw "Maven executable was not found"
    }
    $env:ADA_MAVEN_EXECUTABLE = [System.IO.Path]::GetFullPath($mavenCommand.Source)
}
else {
    $maven = [System.IO.Path]::GetFullPath((Expand-AdaSetting $settings.mavenExecutable))
    if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
        throw "Configured Maven executable is missing"
    }
    $env:ADA_MAVEN_EXECUTABLE = $maven
}

& $agentJava -jar $mcpJar --workspace $workspace --project $project
exit $LASTEXITCODE
