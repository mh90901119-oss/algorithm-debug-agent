$ErrorActionPreference = "Stop"

$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$sourceJar = Join-Path $repository "tools\jdwp-batch-collector\target\jdwp-batch-collector.jar"
$distributionDirectory = Join-Path $repository "tools\jdwp-collector"
$distributionJar = Join-Path $distributionDirectory "jdwp-batch-collector.jar"

Push-Location $repository
try {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell surfaces native stderr as ErrorRecord. Maven warnings are
        # non-fatal, so the native process exit code is the authoritative result.
        $ErrorActionPreference = "Continue"
        & mvn -pl tools/jdwp-batch-collector -am package
        $mavenExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($mavenExitCode -ne 0) {
        throw "JDWP Collector Maven package failed with exit code $mavenExitCode."
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf)) {
    throw "Packaged JDWP Collector JAR was not produced at the repository-relative target path."
}

New-Item -ItemType Directory -Path $distributionDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceJar -Destination $distributionJar -Force
Write-Output "JDWP_COLLECTOR_PACKAGED tools\jdwp-collector\jdwp-batch-collector.jar"
