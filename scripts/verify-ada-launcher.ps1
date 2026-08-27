param()

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
$TargetProject = [IO.Path]::GetFullPath((Get-Location).Path)
$launcher = Join-Path $RepositoryRoot "bin\ada.cmd"
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "ADA launcher not found: $launcher"
}
if (-not (Test-Path -LiteralPath (Join-Path $TargetProject "pom.xml") -PathType Leaf)) {
    throw "Target Maven module was not found in the current directory. Run this script from the target algorithm module: $TargetProject"
}

$verificationRoot = Join-Path ([IO.Path]::GetTempPath()) ("ada-launcher-" + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $verificationRoot | Out-Null
    $workspace = Join-Path $verificationRoot "workspace"
    $initialized = (& $launcher workspace init --root $workspace) -join "`n" | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $initialized.success) {
        throw "ada workspace init failed"
    }

    $doctor = (& $launcher doctor --workspace $workspace --project $TargetProject) -join "`n" |
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
    Write-Output "ADA launcher verification passed for current target module: $TargetProject"
}
finally {
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
