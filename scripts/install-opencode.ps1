param(
    [ValidateSet("Install", "Check", "Uninstall")]
    [string]$Mode = "Install"
)

$ErrorActionPreference = "Stop"
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settingsPath = Join-Path $repository "config\agent-settings.json"
$launcher = Join-Path $repository "bin\ada.cmd"
$cliTargetDirectory = Join-Path $repository "algorithm-debug-cli\target"
$codePathTargetDirectory = Join-Path $repository "tools\code-path-tracer-junit-launcher\target"
$jdwpCollector = Join-Path $repository "tools\jdwp-collector\jdwp-batch-collector.jar"
$manifestRelativePath = ".algorithm-debug-agent/install-manifest.json"

$assets = @(
    @{ Source = "skills\algorithm-debug\SKILL.md"; Destination = "skills\algorithm-debug\SKILL.md" },
    @{ Source = "integrations\opencode\agents\algorithm-debug.md"; Destination = "agents\algorithm-debug.md" },
    @{ Source = "integrations\opencode\commands\debug-case.md"; Destination = "commands\debug-case.md" },
    @{ Source = "integrations\opencode\tools\algorithm-debug.ts"; Destination = "tools\algorithm-debug.ts" },
    @{ Source = "integrations\opencode\lib\ada-cli.mjs"; Destination = "lib\ada-cli.mjs" },
    @{ Source = "integrations\opencode\lib\case-interaction-recorder.mjs"; Destination = "lib\case-interaction-recorder.mjs" },
    @{ Source = "integrations\opencode\lib\tool-runtime.mjs"; Destination = "lib\tool-runtime.mjs" }
)

function Invoke-Main {
    $settings = Get-AgentSettings -Path $settingsPath
    $script:configuration = Resolve-AgentPath -Value $settings.openCodeConfigDirectory -Name "openCodeConfigDirectory"
    $script:workspaceDirectory = Resolve-AgentPath -Value $settings.workspaceDirectory -Name "workspaceDirectory"
    $script:dfxDirectory = Resolve-AgentPath -Value $settings.dfxDirectory -Name "dfxDirectory"
    $script:evalDirectory = Resolve-AgentPath -Value $settings.evalDirectory -Name "evalDirectory"
    $script:resultJsonDirectory = Resolve-AgentPath -Value $settings.resultJsonDirectory -Name "resultJsonDirectory"
    $script:agentJavaHome = Resolve-OptionalAgentPath -Value $settings.agentJavaHome -Name "agentJavaHome"
    $script:targetJavaHome = Resolve-OptionalAgentPath -Value $settings.targetJavaHome -Name "targetJavaHome"
    $script:mavenExecutable = Resolve-OptionalAgentPath -Value $settings.mavenExecutable -Name "mavenExecutable"
    $script:dfxEnabled = [bool]$settings.dfxEnabled

    if ($Mode -eq "Uninstall") {
        Invoke-Uninstall -ConfigDirectory $configuration
        return
    }

    Assert-RepositoryRuntime
    foreach ($asset in $assets) {
        $source = Join-Path $repository $asset.Source
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Repository OpenCode asset is missing: $($asset.Source)"
        }
    }
    $script:detectedOpenCodeVersion = Get-OpenCodeVersion

    $installationLines = @(
        "export const defaultLauncher = $(ConvertTo-Json -Compress $launcher)"
        "export const openCodeConfigDirectory = $(ConvertTo-Json -Compress $configuration)"
        "export const workspaceDirectory = $(ConvertTo-Json -Compress $workspaceDirectory)"
        "export const dfxDirectory = $(ConvertTo-Json -Compress $dfxDirectory)"
        "export const evalDirectory = $(ConvertTo-Json -Compress $evalDirectory)"
        "export const resultJsonDirectory = $(ConvertTo-Json -Compress $resultJsonDirectory)"
        "export const agentJavaHome = $(ConvertTo-Json -Compress $agentJavaHome)"
        "export const targetJavaHome = $(ConvertTo-Json -Compress $targetJavaHome)"
        "export const mavenExecutable = $(ConvertTo-Json -Compress $mavenExecutable)"
        "export const dfxEnabled = $($dfxEnabled.ToString().ToLowerInvariant())"
    )
    $installationBytes = $utf8WithoutBom.GetBytes(($installationLines -join "`n") + "`n")
    $managedFiles = @()
    foreach ($asset in $assets) {
        $managedFiles += [pscustomobject]@{
            RelativePath = $asset.Destination.Replace("\", "/")
            Content = [System.IO.File]::ReadAllBytes((Join-Path $repository $asset.Source))
        }
    }
    $managedFiles += [pscustomobject]@{
        RelativePath = "lib/installation.mjs"
        Content = $installationBytes
    }
    $manifestPath = Join-Path $configuration $manifestRelativePath.Replace("/", "\")
    $manifestBytes = [byte[]](New-InstallManifestBytes -ManagedFiles $managedFiles)

    if ($Mode -eq "Install") {
        New-Item -ItemType Directory -Path $configuration -Force | Out-Null
        Ensure-OpenCodePluginDependency -ConfigDirectory $configuration `
            -OpenCodeVersion $detectedOpenCodeVersion
        Invoke-Uninstall -ConfigDirectory $configuration -Quiet
        foreach ($directory in @($workspaceDirectory, $dfxDirectory, $evalDirectory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        foreach ($asset in $assets) {
            $source = Join-Path $repository $asset.Source
            $destination = Join-Path $configuration $asset.Destination
            Install-Bytes -Destination $destination -Content ([System.IO.File]::ReadAllBytes($source))
        }
        Install-Bytes -Destination (Join-Path $configuration "lib\installation.mjs") -Content $installationBytes
        Install-Bytes -Destination $manifestPath -Content $manifestBytes
        # OpenCode 首次发现 Custom Tool 时会在配置目录安装自身 TypeScript 运行依赖；这是安装行为，
        # 必须在 Install 阶段完成，确保后续 Check 只读。
        Assert-OpenCodeDiscovery -ConfigDirectory $configuration
        Write-Output "OPENCODE_ADAPTER_INSTALLED $configuration"
        Write-EffectivePaths
        return
    }

    if (-not (Test-Path -LiteralPath $configuration -PathType Container)) {
        throw "OpenCode config directory does not exist"
    }
    foreach ($asset in $assets) {
        $source = Join-Path $repository $asset.Source
        $destination = Join-Path $configuration $asset.Destination
        Assert-SameBytes -Expected ([System.IO.File]::ReadAllBytes($source)) -Destination $destination
    }
    Assert-SameBytes -Expected $installationBytes -Destination (Join-Path $configuration "lib\installation.mjs")
    Assert-SameBytes -Expected $manifestBytes -Destination $manifestPath
    Assert-InstalledFilesUnmodified -ConfigDirectory $configuration -ManifestPath $manifestPath `
        -ExpectedRelativePaths @($managedFiles.RelativePath)
    Assert-OpenCodePluginDependency -ConfigDirectory $configuration

    Assert-OpenCodeDiscovery -ConfigDirectory $configuration
    foreach ($asset in $assets) {
        $source = Join-Path $repository $asset.Source
        $destination = Join-Path $configuration $asset.Destination
        Assert-SameBytes -Expected ([System.IO.File]::ReadAllBytes($source)) -Destination $destination
    }
    Assert-SameBytes -Expected $installationBytes -Destination (Join-Path $configuration "lib\installation.mjs")
    Write-Output "OPENCODE_ADAPTER_OK $configuration"
    Write-EffectivePaths
}

function Get-AgentSettings {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Agent settings file is missing: config\agent-settings.json"
    }
    try {
        $value = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "Agent settings JSON is invalid: $($_.Exception.Message)"
    }
    $required = @(
        "schemaVersion", "openCodeConfigDirectory", "workspaceDirectory", "dfxDirectory",
        "evalDirectory", "resultJsonDirectory", "agentJavaHome", "targetJavaHome",
        "mavenExecutable", "dfxEnabled"
    )
    $actual = @($value.PSObject.Properties.Name)
    foreach ($name in $required) {
        if ($actual -notcontains $name) { throw "Agent settings field is missing: $name" }
    }
    foreach ($name in $actual) {
        if ($required -notcontains $name) { throw "Agent settings field is not supported: $name" }
    }
    if ($value.schemaVersion -ne "1.0") {
        throw "Unsupported Agent settings schemaVersion: $($value.schemaVersion)"
    }
    if ($value.dfxEnabled -isnot [bool]) {
        throw "Agent settings field dfxEnabled must be a boolean"
    }
    return $value
}

function Resolve-AgentPath {
    param([object]$Value, [string]$Name)

    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) {
        throw "Agent settings field $Name must be a non-empty path"
    }
    $resolved = $Value
    if ($resolved.Contains("%USERPROFILE%")) {
        if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
            throw "USERPROFILE is required to resolve Agent settings field $Name"
        }
        $resolved = $resolved.Replace("%USERPROFILE%", $env:USERPROFILE)
    }
    if ($resolved.Contains("%LOCALAPPDATA%")) {
        if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
            throw "LOCALAPPDATA is required to resolve Agent settings field $Name"
        }
        $resolved = $resolved.Replace("%LOCALAPPDATA%", $env:LOCALAPPDATA)
    }
    if ($resolved -match "%[A-Za-z_][A-Za-z0-9_]*%") {
        throw "Agent settings field $Name contains an unsupported environment variable"
    }
    $driveRelative = $resolved -match '^[A-Za-z]:[^\\/]'
    $currentDriveRooted = $resolved.StartsWith("\") -and -not $resolved.StartsWith("\\")
    if (-not [System.IO.Path]::IsPathRooted($resolved) -or $driveRelative -or $currentDriveRooted) {
        throw "Agent settings field $Name must resolve to an absolute path"
    }
    return [System.IO.Path]::GetFullPath($resolved)
}

function Write-EffectivePaths {
    Write-Output "OPEN_CODE_CONFIG_DIRECTORY=$configuration"
    Write-Output "WORKSPACE_DIRECTORY=$workspaceDirectory"
    Write-Output "RESULT_JSON_DIRECTORY=$resultJsonDirectory"
    Write-Output "DFX_DIRECTORY=$dfxDirectory"
    Write-Output "EVAL_DIRECTORY=$evalDirectory"
    Write-Output "AGENT_JAVA_HOME=$(Format-OptionalSetting $agentJavaHome)"
    Write-Output "TARGET_JAVA_HOME=$(Format-OptionalSetting $targetJavaHome)"
    Write-Output "MAVEN_EXECUTABLE=$(Format-OptionalSetting $mavenExecutable)"
    Write-Output "DFX_ENABLED=$($dfxEnabled.ToString().ToLowerInvariant())"
}

function Resolve-OptionalAgentPath {
    param([object]$Value, [string]$Name)
    if ($Value -isnot [string]) { throw "Agent settings field $Name must be a string" }
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    return Resolve-AgentPath -Value $Value -Name $Name
}

function Format-OptionalSetting {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "CURRENT_ENVIRONMENT" }
    return $Value
}

function Assert-RepositoryRuntime {
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "Repository launcher is missing: bin\ada.cmd"
    }
    $cliArtifacts = @(Get-ChildItem -LiteralPath $cliTargetDirectory `
        -Filter "algorithm-debug-cli-*-all.jar" -File -ErrorAction SilentlyContinue)
    if ($cliArtifacts.Count -ne 1 -or $cliArtifacts[0].Length -le 0) {
        throw "Exactly one built ADA CLI JAR is required. Run scripts\build-agent.ps1 before installation."
    }
    $codePathArtifacts = @(Get-ChildItem -LiteralPath $codePathTargetDirectory `
        -Filter "code-path-tracer-junit-launcher-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "original-*" })
    if ($codePathArtifacts.Count -ne 1 -or $codePathArtifacts[0].Length -le 0) {
        throw "Exactly one built CodePath Launcher JAR is required. Run scripts\build-agent.ps1 before installation."
    }
    if (-not (Test-Path -LiteralPath $jdwpCollector -PathType Leaf) `
            -or (Get-Item -LiteralPath $jdwpCollector).Length -le 0) {
        throw "Built JDWP Collector JAR is missing. Run scripts\build-agent.ps1 before installation."
    }
}

function Get-OpenCodeVersion {
    $opencode = Get-Command opencode -ErrorAction SilentlyContinue
    if ($null -eq $opencode) {
        throw "OpenCode command 'opencode' was not found on PATH. Install or repair OpenCode, then open a new terminal."
    }
    $version = (& opencode --version | Out-String).Trim()
    $versionExitCode = $LASTEXITCODE
    if ($versionExitCode -ne 0 -or $version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
        throw "OpenCode capability check failed for 'opencode --version' (exit code $versionExitCode). Repair the OpenCode installation and retry."
    }
    return $version
}

function Ensure-OpenCodePluginDependency {
    param([string]$ConfigDirectory, [string]$OpenCodeVersion)

    $packagePath = Join-Path $ConfigDirectory "package.json"
    $modulePackagePath = Join-Path $ConfigDirectory "node_modules\@opencode-ai\plugin\package.json"
    $requiredVersion = $OpenCodeVersion
    if (Test-Path -LiteralPath $modulePackagePath -PathType Leaf) {
        try {
            $installedModule = Get-Content -LiteralPath $modulePackagePath -Raw -Encoding UTF8 |
                ConvertFrom-Json -ErrorAction Stop
            if ([string]$installedModule.version -match '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$') {
                $requiredVersion = [string]$installedModule.version
            }
        }
        catch {
            throw "Installed @opencode-ai/plugin package metadata is invalid: $($_.Exception.Message)"
        }
    }

    if (Test-Path -LiteralPath $packagePath -PathType Leaf) {
        try {
            $package = Get-Content -LiteralPath $packagePath -Raw -Encoding UTF8 |
                ConvertFrom-Json -ErrorAction Stop
        }
        catch {
            throw "OpenCode package.json is invalid: $($_.Exception.Message)"
        }
        if ($package -isnot [pscustomobject]) {
            throw "OpenCode package.json must contain a JSON object"
        }
    }
    else {
        $package = [pscustomobject]@{}
    }

    $dependenciesProperty = $package.PSObject.Properties["dependencies"]
    if ($null -eq $dependenciesProperty) {
        $dependencies = [pscustomobject]@{}
        $package | Add-Member -MemberType NoteProperty -Name "dependencies" -Value $dependencies
    }
    else {
        $dependencies = $dependenciesProperty.Value
        if ($dependencies -isnot [pscustomobject]) {
            throw "OpenCode package.json field dependencies must contain a JSON object"
        }
    }

    $pluginProperty = $dependencies.PSObject.Properties["@opencode-ai/plugin"]
    if ($null -ne $pluginProperty) {
        if ([string]::IsNullOrWhiteSpace([string]$pluginProperty.Value)) {
            throw "OpenCode package.json contains an empty @opencode-ai/plugin dependency"
        }
        return
    }

    $dependencies | Add-Member -MemberType NoteProperty -Name "@opencode-ai/plugin" `
        -Value $requiredVersion
    $packageBytes = $utf8WithoutBom.GetBytes(($package | ConvertTo-Json -Depth 32) + "`n")
    Install-Bytes -Destination $packagePath -Content $packageBytes
    Write-Output "OPENCODE_PLUGIN_DEPENDENCY_DECLARED @opencode-ai/plugin@$requiredVersion"
}

function Assert-OpenCodePluginDependency {
    param([string]$ConfigDirectory)

    $packagePath = Join-Path $ConfigDirectory "package.json"
    if (-not (Test-Path -LiteralPath $packagePath -PathType Leaf)) {
        throw "OpenCode package.json is missing. Run Install to declare @opencode-ai/plugin."
    }
    try {
        $package = Get-Content -LiteralPath $packagePath -Raw -Encoding UTF8 |
            ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "OpenCode package.json is invalid: $($_.Exception.Message)"
    }
    $dependency = $package.PSObject.Properties["dependencies"]
    if ($null -eq $dependency `
            -or $null -eq $dependency.Value.PSObject.Properties["@opencode-ai/plugin"]) {
        throw "OpenCode package.json does not declare @opencode-ai/plugin. Run Install again."
    }
}

function New-InstallManifestBytes {
    param([object[]]$ManagedFiles)

    $entries = @($ManagedFiles | ForEach-Object {
        [ordered]@{
            relativePath = $_.RelativePath
            sha256 = Get-Sha256 -Content ([byte[]]$_.Content)
        }
    })
    $manifest = [ordered]@{
        schemaVersion = "1.0"
        agentId = "algorithm-debug"
        managedFiles = $entries
    }
    return ,$utf8WithoutBom.GetBytes(($manifest | ConvertTo-Json -Depth 5) + "`n")
}

function Get-Sha256 {
    param([byte[]]$Content)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($Content))).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Read-InstallManifest {
    param([string]$Path, [string[]]$ExpectedRelativePaths)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "OpenCode installation manifest is missing: $Path"
    }
    try {
        $manifest = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "OpenCode installation manifest is invalid: $($_.Exception.Message)"
    }
    if ($manifest.schemaVersion -ne "1.0" -or $manifest.agentId -ne "algorithm-debug") {
        throw "OpenCode installation manifest identity is invalid"
    }
    $seen = @{}
    foreach ($entry in @($manifest.managedFiles)) {
        $relative = [string]$entry.relativePath
        $sha = [string]$entry.sha256
        if ([string]::IsNullOrWhiteSpace($relative) -or [System.IO.Path]::IsPathRooted($relative) `
                -or $relative.Contains("\") -or $relative -match '(^|/)\.\.(/|$)' `
                -or $sha -notmatch '^[0-9a-f]{64}$' -or $seen.ContainsKey($relative)) {
            throw "OpenCode installation manifest contains an invalid managed file"
        }
        if (-not (Test-AgentOwnedRelativePath -RelativePath $relative)) {
            throw "OpenCode installation manifest path is outside the Agent-owned namespace: $relative"
        }
        $seen[$relative] = $sha
    }
    if ($null -ne $ExpectedRelativePaths) {
        $expected = @($ExpectedRelativePaths | Sort-Object)
        $actual = @($seen.Keys | Sort-Object)
        if (($expected -join "`n") -cne ($actual -join "`n")) {
            throw "OpenCode installation manifest managed file set is incompatible with this Agent version"
        }
    }
    return $manifest
}

function Test-AgentOwnedRelativePath {
    param([string]$RelativePath)

    if ($RelativePath.StartsWith("skills/algorithm-debug/", [System.StringComparison]::Ordinal)) {
        return $true
    }
    return $RelativePath -in @(
        "agents/algorithm-debug.md",
        "commands/debug-case.md",
        "tools/algorithm-debug.ts",
        "lib/ada-cli.mjs",
        "lib/case-interaction-recorder.mjs",
        "lib/tool-runtime.mjs",
        "lib/installation.mjs"
    )
}

function Assert-InstalledFilesUnmodified {
    param([string]$ConfigDirectory, [string]$ManifestPath, [string[]]$ExpectedRelativePaths)

    $manifest = Read-InstallManifest -Path $ManifestPath -ExpectedRelativePaths $ExpectedRelativePaths
    foreach ($entry in @($manifest.managedFiles)) {
        $destination = Join-Path $ConfigDirectory ([string]$entry.relativePath).Replace("/", "\")
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            throw "Managed OpenCode asset is missing: $($entry.relativePath)"
        }
        $actual = Get-Sha256 -Content ([System.IO.File]::ReadAllBytes($destination))
        if ($actual -cne [string]$entry.sha256) {
            throw "Managed OpenCode asset was modified after installation: $($entry.relativePath)"
        }
    }
}

function Invoke-Uninstall {
    param([string]$ConfigDirectory, [switch]$Quiet)

    $knownRelativePaths = @($assets | ForEach-Object { $_.Destination.Replace("\", "/") }) + "lib/installation.mjs"
    $manifestPath = Join-Path $ConfigDirectory $manifestRelativePath.Replace("/", "\")
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        $legacyFiles = @($knownRelativePaths | Where-Object {
            Test-Path -LiteralPath (Join-Path $ConfigDirectory $_.Replace("/", "\"))
        })
        $invalidLegacyFiles = @($legacyFiles | Where-Object {
            -not (Test-Path -LiteralPath (Join-Path $ConfigDirectory $_.Replace("/", "\")) -PathType Leaf)
        })
        if ($invalidLegacyFiles.Count -gt 0) {
            throw "A legacy Agent path is not a regular file: $($invalidLegacyFiles -join ', ')"
        }
        foreach ($relative in $legacyFiles) {
            Remove-Item -LiteralPath (Join-Path $ConfigDirectory $relative.Replace("/", "\")) -Force
        }
        Remove-EmptyAgentDirectories -ConfigDirectory $ConfigDirectory
        if (-not $Quiet) {
            if ($legacyFiles.Count -gt 0) {
                Write-Output "OPENCODE_LEGACY_ADAPTER_UNINSTALLED $ConfigDirectory"
                Write-EffectivePaths
            }
            else {
                Write-Output "OPENCODE_ADAPTER_ALREADY_UNINSTALLED $ConfigDirectory"
            }
        }
        return
    }

    $manifest = Read-InstallManifest -Path $manifestPath
    $conflicts = @()
    foreach ($entry in @($manifest.managedFiles)) {
        $destination = Join-Path $ConfigDirectory ([string]$entry.relativePath).Replace("/", "\")
        if (-not (Test-Path -LiteralPath $destination)) { continue }
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
            $conflicts += [string]$entry.relativePath
            continue
        }
        $actual = Get-Sha256 -Content ([System.IO.File]::ReadAllBytes($destination))
        if ($actual -cne [string]$entry.sha256) { $conflicts += [string]$entry.relativePath }
    }
    if ($conflicts.Count -gt 0) {
        throw "Uninstall refused because managed OpenCode assets were modified: $($conflicts -join ', ')"
    }

    foreach ($entry in @($manifest.managedFiles)) {
        $destination = Join-Path $ConfigDirectory ([string]$entry.relativePath).Replace("/", "\")
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            Remove-Item -LiteralPath $destination -Force
        }
    }
    Remove-Item -LiteralPath $manifestPath -Force
    Remove-EmptyAgentDirectories -ConfigDirectory $ConfigDirectory
    if (-not $Quiet) {
        Write-Output "OPENCODE_ADAPTER_UNINSTALLED $ConfigDirectory"
        Write-EffectivePaths
    }
}

function Remove-EmptyAgentDirectories {
    param([string]$ConfigDirectory)

    $skillDirectory = Join-Path $ConfigDirectory "skills\algorithm-debug"
    if (Test-Path -LiteralPath $skillDirectory -PathType Container) {
        @(Get-ChildItem -LiteralPath $skillDirectory -Recurse -Directory -Force | Sort-Object FullName -Descending) |
            ForEach-Object {
                if (@(Get-ChildItem -LiteralPath $_.FullName -Force).Count -eq 0) {
                    Remove-Item -LiteralPath $_.FullName -Force
                }
            }
    }
    foreach ($relativeDirectory in @("skills\algorithm-debug", ".algorithm-debug-agent")) {
        $directory = Join-Path $ConfigDirectory $relativeDirectory
        if ((Test-Path -LiteralPath $directory -PathType Container) `
                -and @(Get-ChildItem -LiteralPath $directory -Force).Count -eq 0) {
            Remove-Item -LiteralPath $directory -Force
        }
    }
}

function Install-Bytes {
    param([string]$Destination, [byte[]]$Content)

    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if ((Test-Path -LiteralPath $Destination -PathType Leaf) -and (Bytes-Equal $Content ([System.IO.File]::ReadAllBytes($Destination)))) {
        return
    }

    if (Test-Path -LiteralPath $Destination) {
        if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
            throw "OpenCode asset destination is not a regular file: $Destination"
        }
    }

    $temporary = Join-Path $parent ("." + [System.IO.Path]::GetFileName($Destination) + ".ada-" + [guid]::NewGuid().ToString("N") + ".tmp")
    try {
        [System.IO.File]::WriteAllBytes($temporary, $Content)
        if (Test-Path -LiteralPath $Destination) {
            Move-Item -LiteralPath $temporary -Destination $Destination -Force
        }
        else {
            Move-Item -LiteralPath $temporary -Destination $Destination
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Assert-SameBytes {
    param([byte[]]$Expected, [string]$Destination)

    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        throw "Installed OpenCode asset is missing: $Destination"
    }
    if (-not (Bytes-Equal $Expected ([System.IO.File]::ReadAllBytes($Destination)))) {
        throw "Installed OpenCode asset differs from the repository: $Destination"
    }
}

function Bytes-Equal {
    param([byte[]]$Left, [byte[]]$Right)

    if ($Left.Length -ne $Right.Length) { return $false }
    for ($index = 0; $index -lt $Left.Length; $index++) {
        if ($Left[$index] -ne $Right[$index]) { return $false }
    }
    return $true
}

function Assert-OpenCodeDiscovery {
    param([string]$ConfigDirectory)

    $version = $script:detectedOpenCodeVersion
    Write-Verbose "Detected OpenCode $version; compatibility is determined by capability discovery"
    $discoveryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("ada-opencode-discovery-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $discoveryDirectory | Out-Null

    $hadConfigDirectory = Test-Path Env:OPENCODE_CONFIG_DIR
    $oldConfigDirectory = $env:OPENCODE_CONFIG_DIR
    $hadDisableProject = Test-Path Env:OPENCODE_DISABLE_PROJECT_CONFIG
    $oldDisableProject = $env:OPENCODE_DISABLE_PROJECT_CONFIG
    try {
        $env:OPENCODE_CONFIG_DIR = $ConfigDirectory
        $env:OPENCODE_DISABLE_PROJECT_CONFIG = "1"
        Push-Location -LiteralPath $discoveryDirectory
        try {
            $skillsText = (& opencode debug skill | Out-String)
            $skillsExitCode = $LASTEXITCODE
            if ($skillsExitCode -ne 0) {
                throw "OpenCode capability check failed for 'opencode debug skill' (version $version, exit code $skillsExitCode). This OpenCode build does not expose the required Skill discovery command or failed to load its configuration."
            }
            try {
                $skills = $skillsText | ConvertFrom-Json -ErrorAction Stop
            } catch {
                throw "OpenCode capability check failed: 'opencode debug skill' returned invalid JSON (version $version). $($_.Exception.Message)"
            }
            if (-not @($skills).Where({ $_.name -eq "algorithm-debug" }, "First")) {
                throw "OpenCode capability check succeeded but Skill 'algorithm-debug' was not discovered (version $version). Run this script with -Mode Install, restart OpenCode, and retry."
            }

            $agentText = (& opencode debug agent algorithm-debug | Out-String)
            $agentExitCode = $LASTEXITCODE
            if ($agentExitCode -ne 0) {
                throw "OpenCode capability check failed for 'opencode debug agent algorithm-debug' (version $version, exit code $agentExitCode). Agent discovery is unavailable or 'algorithm-debug' could not be loaded."
            }
            try {
                $agent = $agentText | ConvertFrom-Json -ErrorAction Stop
            } catch {
                throw "OpenCode capability check failed: 'opencode debug agent algorithm-debug' returned invalid JSON (version $version). $($_.Exception.Message)"
            }
            $expectedTools = @(
                "algorithm-debug_analysis_begin", "algorithm-debug_case_inspect",
                "algorithm-debug_algorithm_input_capture",
                "algorithm-debug_case_audit", "algorithm-debug_gantt_inspect",
                "algorithm-debug_run_test", "algorithm-debug_static_analyze",
                "algorithm-debug_codepath_plan_create", "algorithm-debug_codepath_collect",
                "algorithm-debug_jdwp_plan_create", "algorithm-debug_jdwp_collect",
                "algorithm-debug_artifact_read", "algorithm-debug_evidence_query"
            )
            foreach ($toolName in $expectedTools) {
                if ($agent.tools.$toolName -ne $true) {
                    throw "OpenCode capability check succeeded but Tool '$toolName' was not discovered for Agent 'algorithm-debug' (version $version). Reinstall the repository assets and restart OpenCode."
                }
            }

            $configText = (& opencode debug config | Out-String)
            $configExitCode = $LASTEXITCODE
            if ($configExitCode -ne 0) {
                throw "OpenCode capability check failed for 'opencode debug config' (version $version, exit code $configExitCode). Config discovery is unavailable or the installed configuration could not be loaded."
            }
            try {
                $config = $configText | ConvertFrom-Json -ErrorAction Stop
            } catch {
                throw "OpenCode capability check failed: 'opencode debug config' returned invalid JSON (version $version). $($_.Exception.Message)"
            }
            if ($null -eq $config.command.'debug-case') {
                throw "OpenCode capability check succeeded but Command 'debug-case' was not discovered (version $version). Reinstall the repository assets and restart OpenCode."
            }
        }
        finally {
            Pop-Location
        }
    }
    finally {
        if ($hadConfigDirectory) { $env:OPENCODE_CONFIG_DIR = $oldConfigDirectory }
        else { Remove-Item Env:OPENCODE_CONFIG_DIR -ErrorAction SilentlyContinue }
        if ($hadDisableProject) { $env:OPENCODE_DISABLE_PROJECT_CONFIG = $oldDisableProject }
        else { Remove-Item Env:OPENCODE_DISABLE_PROJECT_CONFIG -ErrorAction SilentlyContinue }
        $resolvedDiscovery = [System.IO.Path]::GetFullPath($discoveryDirectory)
        $resolvedTemporary = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if ($resolvedDiscovery.StartsWith($resolvedTemporary, [System.StringComparison]::OrdinalIgnoreCase) `
                -and (Test-Path -LiteralPath $resolvedDiscovery)) {
            Remove-Item -LiteralPath $resolvedDiscovery -Recurse -Force
        }
    }
}

Invoke-Main
$global:LASTEXITCODE = 0
