param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$installer = Join-Path $PSScriptRoot "install-opencode.ps1"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ada-opencode-installer-" + [guid]::NewGuid().ToString("N"))
$configRoot = Join-Path $temporaryRoot "opencode"

try {
    $canonicalSkill = [System.IO.File]::ReadAllText((Join-Path $RepositoryRoot "skills\algorithm-debug\SKILL.md"))
    if ($canonicalSkill -match '[^\x00-\x7F]') {
        throw "Canonical algorithm-debug Skill must remain ASCII to prevent Windows PowerShell encoding damage"
    }
    New-Item -ItemType Directory -Path (Join-Path $configRoot "agents") -Force | Out-Null
    $existingAgent = Join-Path $configRoot "agents\algorithm-debug.md"
    [System.IO.File]::WriteAllText($existingAgent, "existing user agent", [System.Text.UTF8Encoding]::new($false))

    & $installer -Mode Install -ConfigRoot $configRoot -RepositoryRoot $RepositoryRoot

    $backups = @(Get-ChildItem -LiteralPath (Join-Path $configRoot "agents") -Filter "algorithm-debug.md.ada-backup-*" -File)
    if ($backups.Count -ne 1) { throw "Installer did not preserve exactly one conflicting user asset" }
    if ([System.IO.File]::ReadAllText($backups[0].FullName) -ne "existing user agent") {
        throw "Installer backup content changed"
    }

    & $installer -Mode Install -ConfigRoot $configRoot -RepositoryRoot $RepositoryRoot
    $backupsAfterSecondInstall = @(Get-ChildItem -LiteralPath (Join-Path $configRoot "agents") -Filter "algorithm-debug.md.ada-backup-*" -File)
    if ($backupsAfterSecondInstall.Count -ne 1) { throw "Idempotent install created another backup" }

    & $installer -Mode Check -ConfigRoot $configRoot -RepositoryRoot $RepositoryRoot

    $installationModule = [System.IO.File]::ReadAllText((Join-Path $configRoot "lib\installation.mjs"))
    $expectedLauncher = (Join-Path $RepositoryRoot "bin\ada.cmd").Replace("\", "\\")
    if (-not $installationModule.Contains($expectedLauncher)) {
        throw "Installed launcher module does not point at the repository launcher"
    }

    Write-Output "OPENCODE_INSTALLER_VERIFIED"
}
finally {
    $resolvedTemporary = [System.IO.Path]::GetFullPath($temporaryRoot)
    $resolvedSystemTemporary = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemporary.StartsWith($resolvedSystemTemporary, [System.StringComparison]::OrdinalIgnoreCase) `
            -and (Test-Path -LiteralPath $resolvedTemporary)) {
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
