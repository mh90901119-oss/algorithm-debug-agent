param(
    [ValidateSet("Install", "Check")]
    [string]$Mode = "Install",
    [string]$ConfigRoot = (Join-Path $HOME ".config\opencode"),
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
$repository = [System.IO.Path]::GetFullPath($RepositoryRoot)
$configuration = [System.IO.Path]::GetFullPath($ConfigRoot)
$launcher = Join-Path $repository "bin\ada.cmd"

$assets = @(
    @{ Source = "skills\algorithm-debug\SKILL.md"; Destination = "skills\algorithm-debug\SKILL.md" },
    @{ Source = "integrations\opencode\agents\algorithm-debug.md"; Destination = "agents\algorithm-debug.md" },
    @{ Source = "integrations\opencode\commands\debug-case.md"; Destination = "commands\debug-case.md" },
    @{ Source = "integrations\opencode\tools\algorithm-debug.ts"; Destination = "tools\algorithm-debug.ts" },
    @{ Source = "integrations\opencode\lib\ada-cli.mjs"; Destination = "lib\ada-cli.mjs" },
    @{ Source = "integrations\opencode\lib\tool-runtime.mjs"; Destination = "lib\tool-runtime.mjs" }
)

function Invoke-Main {
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "Repository launcher is missing: bin\ada.cmd"
    }
    foreach ($asset in $assets) {
        $source = Join-Path $repository $asset.Source
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Repository OpenCode asset is missing: $($asset.Source)"
        }
    }

    $launcherJson = ConvertTo-Json -Compress $launcher
    $installationBytes = $utf8WithoutBom.GetBytes("export const defaultLauncher = $launcherJson`n")

    if ($Mode -eq "Install") {
        New-Item -ItemType Directory -Path $configuration -Force | Out-Null
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
        $suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmssfff") + "-" + [guid]::NewGuid().ToString("N")
        $backup = "$Destination.ada-backup-$suffix"
        Copy-Item -LiteralPath $Destination -Destination $backup
        Write-Output "BACKED_UP $Destination -> $backup"
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

    $opencode = Get-Command opencode -ErrorAction SilentlyContinue
    if ($null -eq $opencode) { throw "opencode is not installed" }
    $version = (& opencode --version).Trim()
    if ($LASTEXITCODE -ne 0 -or $version -ne "1.18.15") {
        throw "Expected OpenCode 1.18.15 but found '$version'"
    }
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
            if ($LASTEXITCODE -ne 0) { throw "opencode debug skill failed" }
            $skills = $skillsText | ConvertFrom-Json
            if (-not @($skills).Where({ $_.name -eq "algorithm-debug" }, "First")) {
                throw "algorithm-debug Skill was not discovered"
            }

            $agentText = (& opencode debug agent algorithm-debug | Out-String)
            if ($LASTEXITCODE -ne 0) { throw "algorithm-debug Agent was not discovered" }
            $agent = $agentText | ConvertFrom-Json
            $expectedTools = @(
                "algorithm-debug_analysis_begin", "algorithm-debug_case_inspect",
                "algorithm-debug_run_test", "algorithm-debug_static_analyze",
                "algorithm-debug_codepath_plan_create", "algorithm-debug_codepath_collect",
                "algorithm-debug_jdwp_plan_create", "algorithm-debug_jdwp_collect",
                "algorithm-debug_artifact_read", "algorithm-debug_analysis_complete"
            )
            foreach ($toolName in $expectedTools) {
                if ($agent.tools.$toolName -ne $true) { throw "OpenCode Tool was not discovered: $toolName" }
            }

            $configText = (& opencode debug config | Out-String)
            if ($LASTEXITCODE -ne 0) { throw "opencode debug config failed" }
            $config = $configText | ConvertFrom-Json
            if ($null -eq $config.command.'debug-case') { throw "debug-case Command was not discovered" }
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
