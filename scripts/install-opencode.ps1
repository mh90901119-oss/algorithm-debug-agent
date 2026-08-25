param(
    [ValidateSet("Install", "Check")]
    [string]$Mode = "Install"
)

$ErrorActionPreference = "Stop"
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
$repository = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$settingsPath = Join-Path $repository "config\agent-settings.json"
$launcher = Join-Path $repository "bin\ada.cmd"
$jdwpCollector = Join-Path $repository "tools\jdwp-collector\jdwp-batch-collector.jar"

$assets = @(
    @{ Source = "skills\algorithm-debug\SKILL.md"; Destination = "skills\algorithm-debug\SKILL.md" },
    @{ Source = "skills\algorithm-debug\references\wafer-demo-v1.md"; Destination = "skills\algorithm-debug\references\wafer-demo-v1.md" },
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

    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "Repository launcher is missing: bin\ada.cmd"
    }
    if (-not (Test-Path -LiteralPath $jdwpCollector -PathType Leaf)) {
        throw "Repository JDWP Collector is missing: tools\jdwp-collector\jdwp-batch-collector.jar. Run scripts\package-jdwp-collector.ps1 before publishing the repository."
    }
    foreach ($asset in $assets) {
        $source = Join-Path $repository $asset.Source
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Repository OpenCode asset is missing: $($asset.Source)"
        }
    }

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

    if ($Mode -eq "Install") {
        foreach ($directory in @($configuration, $workspaceDirectory, $dfxDirectory, $evalDirectory)) {
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
        }
        foreach ($asset in $assets) {
            $source = Join-Path $repository $asset.Source
            $destination = Join-Path $configuration $asset.Destination
            Install-Bytes -Destination $destination -Content ([System.IO.File]::ReadAllBytes($source))
        }
        Install-Bytes -Destination (Join-Path $configuration "lib\installation.mjs") -Content $installationBytes
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

function Install-Bytes {
    param([string]$Destination, [byte[]]$Content)

    $parent = Split-Path -Parent $Destination
    $backup = $null
    $installed = $false
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if ((Test-Path -LiteralPath $Destination -PathType Leaf) -and (Bytes-Equal $Content ([System.IO.File]::ReadAllBytes($Destination)))) {
        return
    }

    if (Test-Path -LiteralPath $Destination) {
        if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
            throw "OpenCode asset destination is not a regular file: $Destination"
        }
        $suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmssfff") + "-" + [guid]::NewGuid().ToString("N")
        $backup = "$Destination.ada-backup-$suffix"
        Copy-Item -LiteralPath $Destination -Destination $backup
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
        $installed = $true
    }
    finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
        if ($installed -and $backup -and (Test-Path -LiteralPath $backup -PathType Leaf)) {
            Remove-Item -LiteralPath $backup -Force
        }
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

    $opencode = Get-Command opencode -ErrorAction SilentlyContinue
    if ($null -eq $opencode) {
        throw "OpenCode command 'opencode' was not found on PATH. Install or repair OpenCode, then open a new terminal."
    }
    $version = (& opencode --version | Out-String).Trim()
    $versionExitCode = $LASTEXITCODE
    if ($versionExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($version)) {
        throw "OpenCode capability check failed for 'opencode --version' (exit code $versionExitCode). Repair the OpenCode installation and retry."
    }
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
                "algorithm-debug_case_audit", "algorithm-debug_gantt_inspect",
                "algorithm-debug_run_test", "algorithm-debug_static_analyze",
                "algorithm-debug_codepath_plan_create", "algorithm-debug_codepath_collect",
                "algorithm-debug_jdwp_plan_create", "algorithm-debug_jdwp_collect",
                "algorithm-debug_artifact_read", "algorithm-debug_analysis_complete"
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
