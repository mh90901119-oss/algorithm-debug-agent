param()

$ErrorActionPreference = "Stop"
$RepositoryRoot = Split-Path -Parent $PSScriptRoot
$installer = Join-Path $PSScriptRoot "install-opencode.ps1"
$uninstaller = Join-Path $PSScriptRoot "uninstall-opencode.ps1"
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ada-opencode-installer-" + [guid]::NewGuid().ToString("N"))
$previousUserProfile = $env:USERPROFILE
$previousLocalAppData = $env:LOCALAPPDATA
$env:USERPROFILE = Join-Path $temporaryRoot "profile"
$env:LOCALAPPDATA = Join-Path $temporaryRoot "local-app-data"
$configRoot = Join-Path $env:USERPROFILE ".config\opencode"

try {
    $strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
    $assetPaths = @(
        (Join-Path $RepositoryRoot "skills\algorithm-debug\SKILL.md"),
        (Join-Path $RepositoryRoot "integrations\opencode\agents\algorithm-debug.md"),
        (Join-Path $RepositoryRoot "integrations\opencode\lib\case-interaction-recorder.mjs")
    )
    foreach ($assetPath in $assetPaths) {
        $asset = [System.IO.File]::ReadAllText($assetPath, $strictUtf8)
        if ($asset -match '[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]') {
            throw "OpenCode text asset contains a forbidden control character: $assetPath"
        }
    }
    New-Item -ItemType Directory -Path (Join-Path $configRoot "agents") -Force | Out-Null
    $existingAgent = Join-Path $configRoot "agents\algorithm-debug.md"
    [System.IO.File]::WriteAllText($existingAgent, "existing user agent", [System.Text.UTF8Encoding]::new($false))

    & $installer -Mode Install

    $backups = @(Get-ChildItem -LiteralPath (Join-Path $configRoot "agents") -Filter "algorithm-debug.md.ada-backup-*" -File)
    if ($backups.Count -ne 0) { throw "Installer left a random backup after a successful atomic install" }

    & $installer -Mode Install
    $backupsAfterSecondInstall = @(Get-ChildItem -LiteralPath (Join-Path $configRoot "agents") -Filter "algorithm-debug.md.ada-backup-*" -File)
    if ($backupsAfterSecondInstall.Count -ne 0) { throw "Idempotent install left a random backup" }

    & $installer -Mode Check

    $manifestPath = Join-Path $configRoot ".algorithm-debug-agent\install-manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Installer did not create the ownership manifest"
    }

    $installationModule = [System.IO.File]::ReadAllText((Join-Path $configRoot "lib\installation.mjs"))
    $expectedLauncher = (Join-Path $RepositoryRoot "bin\ada.cmd").Replace("\", "\\")
    if (-not $installationModule.Contains($expectedLauncher)) {
        throw "Installed launcher module does not point at the repository launcher"
    }
    foreach ($exportName in @(
            "openCodeConfigDirectory", "workspaceDirectory", "dfxDirectory",
            "evalDirectory", "resultJsonDirectory", "agentJavaHome", "targetJavaHome",
            "mavenExecutable", "dfxEnabled")) {
        if (-not $installationModule.Contains("export const $exportName =")) {
            throw "Installed launcher module is missing export: $exportName"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $configRoot "lib\case-interaction-recorder.mjs") -PathType Leaf)) {
        throw "Installed Case interaction recorder is missing"
    }

    $sentinel = Join-Path $configRoot "unrelated-user-config.txt"
    [System.IO.File]::WriteAllText($sentinel, "preserve", [System.Text.UTF8Encoding]::new($false))
    $workspaceSentinel = Join-Path $env:LOCALAPPDATA "algorithm-debug-agent\workspace\preserve.txt"
    New-Item -ItemType Directory -Path (Split-Path -Parent $workspaceSentinel) -Force | Out-Null
    [System.IO.File]::WriteAllText($workspaceSentinel, "preserve", [System.Text.UTF8Encoding]::new($false))

    $installedAgent = Join-Path $configRoot "agents\algorithm-debug.md"
    [System.IO.File]::AppendAllText($installedAgent, "`nmodified", [System.Text.UTF8Encoding]::new($false))
    $conflictDetected = $false
    try { & $uninstaller } catch { $conflictDetected = $_.Exception.Message -match "modified" }
    if (-not $conflictDetected) { throw "Uninstaller did not reject a modified managed file" }
    if (-not (Test-Path -LiteralPath (Join-Path $configRoot "tools\algorithm-debug.ts") -PathType Leaf)) {
        throw "Conflict preflight partially deleted managed files"
    }
    [System.IO.File]::WriteAllBytes(
        $installedAgent,
        [System.IO.File]::ReadAllBytes((Join-Path $RepositoryRoot "integrations\opencode\agents\algorithm-debug.md")))
    & $installer -Mode Check

    & $uninstaller
    foreach ($relative in @(
            "agents\algorithm-debug.md", "commands\debug-case.md", "skills\algorithm-debug\SKILL.md",
            "tools\algorithm-debug.ts", "lib\ada-cli.mjs", "lib\case-interaction-recorder.mjs",
            "lib\tool-runtime.mjs", "lib\installation.mjs", ".algorithm-debug-agent\install-manifest.json")) {
        if (Test-Path -LiteralPath (Join-Path $configRoot $relative)) {
            throw "Uninstaller left a managed file: $relative"
        }
    }
    if (-not (Test-Path -LiteralPath $sentinel -PathType Leaf)) { throw "Uninstaller removed unrelated OpenCode data" }
    if (-not (Test-Path -LiteralPath $workspaceSentinel -PathType Leaf)) { throw "Uninstaller removed Workspace data" }
    & $uninstaller

    & $installer -Mode Install
    & $installer -Mode Check

    Write-Output "OPENCODE_INSTALLER_VERIFIED"
}
finally {
    $env:USERPROFILE = $previousUserProfile
    $env:LOCALAPPDATA = $previousLocalAppData
    $resolvedTemporary = [System.IO.Path]::GetFullPath($temporaryRoot)
    $resolvedSystemTemporary = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemporary.StartsWith($resolvedSystemTemporary, [System.StringComparison]::OrdinalIgnoreCase) `
            -and (Test-Path -LiteralPath $resolvedTemporary)) {
        Remove-Item -LiteralPath $resolvedTemporary -Recurse -Force
    }
}
