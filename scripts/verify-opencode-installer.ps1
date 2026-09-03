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
$packagePath = Join-Path $configRoot "package.json"

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
    [System.IO.File]::WriteAllText(
        $packagePath,
        "{`n  `"private`": true`n}`n",
        [System.Text.UTF8Encoding]::new($false))
    $existingAgent = Join-Path $configRoot "agents\algorithm-debug.md"
    [System.IO.File]::WriteAllText($existingAgent, "existing user agent", [System.Text.UTF8Encoding]::new($false))

    & $uninstaller
    if (Test-Path -LiteralPath $existingAgent) {
        throw "Legacy uninstall left the known Agent file"
    }
    & $uninstaller

    & $installer -Mode Install

    $installedPackage = Get-Content -LiteralPath $packagePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($installedPackage.private -ne $true) {
        throw "Installer did not preserve the existing OpenCode package.json"
    }
    if ($null -eq $installedPackage.dependencies.'@opencode-ai/plugin') {
        throw "Installer did not declare @opencode-ai/plugin"
    }

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

    # 模拟旧版本曾安装、但当前版本已经删除的 Agent 专属 Skill 文件。
    # 新版本必须依据旧清单安全清理它，不能要求旧清单与当前文件集合完全相同。
    $removedLegacyAsset = Join-Path $configRoot "skills\algorithm-debug\references\removed-v1.md"
    New-Item -ItemType Directory -Path (Split-Path -Parent $removedLegacyAsset) -Force | Out-Null
    [System.IO.File]::WriteAllText($removedLegacyAsset, "legacy managed asset", [System.Text.UTF8Encoding]::new($false))
    $legacyManifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $legacyManifest.managedFiles += [pscustomobject]@{
        relativePath = "skills/algorithm-debug/references/removed-v1.md"
        sha256 = (Get-FileHash -LiteralPath $removedLegacyAsset -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    [System.IO.File]::WriteAllText(
        $manifestPath,
        ($legacyManifest | ConvertTo-Json -Depth 8),
        [System.Text.UTF8Encoding]::new($false))

    & $installer -Mode Install
    if (Test-Path -LiteralPath $removedLegacyAsset) {
        throw "Installer did not remove an obsolete managed asset during upgrade"
    }
    & $installer -Mode Check

    $foreignAsset = Join-Path $configRoot "unrelated-user-config.txt"
    [System.IO.File]::WriteAllText($foreignAsset, "preserve", [System.Text.UTF8Encoding]::new($false))
    $validManifestBytes = [System.IO.File]::ReadAllBytes($manifestPath)
    $foreignManifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $foreignManifest.managedFiles += [pscustomobject]@{
        relativePath = "unrelated-user-config.txt"
        sha256 = (Get-FileHash -LiteralPath $foreignAsset -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    [System.IO.File]::WriteAllText(
        $manifestPath,
        ($foreignManifest | ConvertTo-Json -Depth 8),
        [System.Text.UTF8Encoding]::new($false))
    $foreignPathRejected = $false
    try { & $uninstaller } catch { $foreignPathRejected = $_.Exception.Message -match "outside the Agent-owned namespace" }
    if (-not $foreignPathRejected) { throw "Uninstaller accepted a managed path outside the Agent-owned namespace" }
    if (-not (Test-Path -LiteralPath $foreignAsset -PathType Leaf)) { throw "Uninstaller removed an unrelated user file" }
    [System.IO.File]::WriteAllBytes($manifestPath, $validManifestBytes)

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
    $packageAfterUninstall = Get-Content -LiteralPath $packagePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($packageAfterUninstall.private -ne $true `
            -or $null -eq $packageAfterUninstall.dependencies.'@opencode-ai/plugin') {
        throw "Uninstaller changed the shared OpenCode package.json"
    }
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
