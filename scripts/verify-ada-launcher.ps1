param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$DemoProject = ""
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
if ([string]::IsNullOrWhiteSpace($DemoProject)) {
    $DemoProject = Join-Path (Split-Path -Parent $RepositoryRoot) "hellomvn"
}
$DemoProject = [IO.Path]::GetFullPath($DemoProject)
$launcher = Join-Path $RepositoryRoot "bin\ada.cmd"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "ADA launcher not found: $launcher"
}
if (-not (Test-Path -LiteralPath (Join-Path $DemoProject "pom.xml") -PathType Leaf)) {
    throw "Demo Maven module not found: $DemoProject"
}

$verificationRoot = Join-Path ([IO.Path]::GetTempPath()) ("ada-launcher-" + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $verificationRoot | Out-Null
    $workspace = Join-Path $verificationRoot "workspace"
    $collector = Join-Path $verificationRoot "jdwp-collector.jar"
    [IO.File]::WriteAllBytes($collector, [byte[]](1, 2, 3))
    $previousCollector = $env:ADA_JDWP_COLLECTOR_JAR
    $env:ADA_JDWP_COLLECTOR_JAR = $collector

    $initialized = (& $launcher workspace init --root $workspace) -join "`n" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $initialized.success) {
        throw "ada workspace init failed"
    }

    $doctor = (& $launcher doctor --workspace $workspace --project $DemoProject) -join "`n" |
        ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $doctor.success) {
        throw "ada doctor failed"
    }
    $codes = @($doctor.data.checks | ForEach-Object { $_.code })
    foreach ($required in @("JAVA_OK", "MAVEN_OK", "PROJECT_OK", "CODEPATH_TOOL_OK", "JDWP_TOOL_OK")) {
        if ($required -notin $codes) {
            throw "ada doctor missing required check: $required"
        }
    }
    Write-Output "ADA launcher verification passed"
}
finally {
    $env:ADA_JDWP_COLLECTOR_JAR = $previousCollector
    if (Test-Path -LiteralPath $verificationRoot) {
        $resolvedVerificationRoot = (Resolve-Path -LiteralPath $verificationRoot).Path
        $resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolvedVerificationRoot.StartsWith(
                $resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to delete verification directory outside the system temp root"
        }
        Remove-Item -LiteralPath $resolvedVerificationRoot -Recurse -Force
    }
}
