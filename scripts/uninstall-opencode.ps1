param()

$ErrorActionPreference = "Stop"
$installer = Join-Path $PSScriptRoot "install-opencode.ps1"
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "OpenCode installer is missing: $installer"
}

& $installer -Mode Uninstall
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$global:LASTEXITCODE = 0
