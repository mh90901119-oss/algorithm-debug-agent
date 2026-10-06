Set-StrictMode -Version Latest

$script:AdapterName = "algorithm-debug-agent"
$script:OwnershipFileName = "ownership-manifest.json"
$script:OwnershipSchemaVersion = "1.0"
$script:TestProfileRelativePath = "target\qwen-test-profile"

function Expand-AdaSetting {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    $expanded = $Value
    $variables = @(
        @{ token = "%USERPROFILE%"; name = "USERPROFILE"; value = $env:USERPROFILE },
        @{ token = "%LOCALAPPDATA%"; name = "LOCALAPPDATA"; value = $env:LOCALAPPDATA }
    )
    foreach ($variable in $variables) {
        if ($expanded.Contains($variable.token)) {
            if ([string]::IsNullOrWhiteSpace($variable.value)) {
                throw "$($variable.name) is not configured"
            }
            $expanded = $expanded.Replace($variable.token, $variable.value)
        }
    }
    return $expanded
}

function Resolve-AdaRepositoryRoot {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)
    $resolved = [System.IO.Path]::GetFullPath($RepositoryRoot)
    $required = @(
        "agent-definition\algorithm-debug-agent-v1.json",
        "integrations\qwen-cli\adapter-manifest.json",
        "integrations\host-adapter-kit\scripts\render-host-config.mjs",
        "bin\ada-mcp.cmd",
        "config\mcp-agent-settings.json"
    )
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolved $relative) -PathType Leaf)) {
            throw "Repository asset is missing: $relative"
        }
    }
    return $resolved
}

function Resolve-QwenProfileRoot {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("User", "TestProfile")][string]$Scope,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [string]$ProfileRoot
    )
    if ($Scope -eq "User" -and -not [string]::IsNullOrWhiteSpace($ProfileRoot)) {
        throw "ProfileRoot is supported only with TestProfile scope"
    }
    if (-not [string]::IsNullOrWhiteSpace($ProfileRoot)) {
        return [System.IO.Path]::GetFullPath($ProfileRoot)
    }
    if ($Scope -eq "TestProfile") {
        return [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot $script:TestProfileRelativePath))
    }
    if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        throw "USERPROFILE is not configured"
    }
    return [System.IO.Path]::GetFullPath((Join-Path $env:USERPROFILE ".qwen"))
}

function Get-QwenExtensionRoot {
    param([Parameter(Mandatory = $true)][string]$ProfileRoot)
    return Join-Path (Join-Path $ProfileRoot "extensions") $script:AdapterName
}

function Get-AdaFileHash {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Invoke-QwenExtensionCommand {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("enable", "disable", "uninstall")][string]$Action
    )
    $qwen = Get-Command qwen -ErrorAction SilentlyContinue
    if ($null -eq $qwen) { throw "Qwen CLI executable was not found" }
    if ($Action -eq "uninstall") {
        & $qwen.Source extensions uninstall $script:AdapterName | Out-Null
    }
    else {
        & $qwen.Source extensions $Action $script:AdapterName | Out-Null
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Qwen CLI extension $Action failed with exit code $LASTEXITCODE"
    }
}

function Assert-ContainedPath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Candidate
    )
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $candidateFull = [System.IO.Path]::GetFullPath($Candidate)
    $prefix = $rootFull + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidateFull.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Managed path escapes the extension root"
    }
    return $candidateFull
}

function Read-OwnershipManifest {
    param([Parameter(Mandatory = $true)][string]$ExtensionRoot)
    $manifestPath = Join-Path $ExtensionRoot $script:OwnershipFileName
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        return $null
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schemaVersion -ne $script:OwnershipSchemaVersion -or
        $manifest.adapterId -ne "qwen-cli" -or
        $null -eq $manifest.files) {
        throw "Qwen Adapter ownership manifest is invalid"
    }
    return $manifest
}

function Test-OwnedFiles {
    param(
        [Parameter(Mandatory = $true)][string]$ExtensionRoot,
        [Parameter(Mandatory = $true)]$Manifest,
        [switch]$ThrowOnMismatch
    )
    $mismatches = [System.Collections.Generic.List[string]]::new()
    foreach ($file in $Manifest.files) {
        $managedPath = Assert-ContainedPath -Root $ExtensionRoot -Candidate (Join-Path $ExtensionRoot $file.path)
        if (-not (Test-Path -LiteralPath $managedPath -PathType Leaf)) {
            $mismatches.Add("MISSING:$($file.path)")
            continue
        }
        if ((Get-AdaFileHash -Path $managedPath) -ne $file.sha256) {
            $mismatches.Add("MODIFIED:$($file.path)")
        }
    }
    if ($ThrowOnMismatch -and $mismatches.Count -gt 0) {
        throw "Installed Qwen Adapter contains changed assets: $($mismatches -join ', ')"
    }
    return @($mismatches)
}

function Get-UnownedFiles {
    param(
        [Parameter(Mandatory = $true)][string]$ExtensionRoot,
        [Parameter(Mandatory = $true)]$Manifest
    )
    $normalizedRoot = [System.IO.Path]::GetFullPath($ExtensionRoot).TrimEnd('\', '/')
    $ownedPaths = @($Manifest.files | ForEach-Object { $_.path })
    return @(Get-ChildItem -LiteralPath $ExtensionRoot -Recurse -File |
        ForEach-Object {
            $relative = $_.FullName.Substring($normalizedRoot.Length)
            $relative = $relative.TrimStart('\', '/').Replace('\', '/')
            if ($relative -ne $script:OwnershipFileName -and $ownedPaths -notcontains $relative) {
                $relative
            }
        })
}

function Write-OwnershipManifest {
    param(
        [Parameter(Mandatory = $true)][string]$ExtensionRoot,
        [Parameter(Mandatory = $true)][string]$RepositoryRoot
    )
    $normalizedRoot = [System.IO.Path]::GetFullPath($ExtensionRoot).TrimEnd('\', '/')
    $files = Get-ChildItem -LiteralPath $ExtensionRoot -Recurse -File |
        Where-Object { $_.Name -ne $script:OwnershipFileName } |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($normalizedRoot.Length).TrimStart('\', '/').Replace('\', '/')
            [ordered]@{ path = $relative; sha256 = Get-AdaFileHash -Path $_.FullName }
        }
    $manifest = [ordered]@{
        schemaVersion = $script:OwnershipSchemaVersion
        adapterId = "qwen-cli"
        adapterName = $script:AdapterName
        repositoryRoot = $RepositoryRoot
        files = @($files)
    }
    $json = $manifest | ConvertTo-Json -Depth 8
    Set-Content -LiteralPath (Join-Path $ExtensionRoot $script:OwnershipFileName) `
        -Value $json -Encoding UTF8
}

function Install-QwenAdapter {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][ValidateSet("User", "TestProfile")][string]$Scope,
        [string]$ProfileRoot
    )
    $repository = Resolve-AdaRepositoryRoot -RepositoryRoot $RepositoryRoot
    $profile = Resolve-QwenProfileRoot -Scope $Scope -RepositoryRoot $repository -ProfileRoot $ProfileRoot
    $extensionRoot = Get-QwenExtensionRoot -ProfileRoot $profile
    $extensionParent = Split-Path -Parent $extensionRoot
    New-Item -ItemType Directory -Path $extensionParent -Force | Out-Null

    if (Test-Path -LiteralPath $extensionRoot) {
        $existing = Read-OwnershipManifest -ExtensionRoot $extensionRoot
        if ($null -eq $existing) {
            throw "Existing extension is not owned by this Adapter: $extensionRoot"
        }
        Test-OwnedFiles -ExtensionRoot $extensionRoot -Manifest $existing -ThrowOnMismatch | Out-Null
        $unowned = @(Get-UnownedFiles -ExtensionRoot $extensionRoot -Manifest $existing)
        if ($unowned.Count -gt 0) {
            throw "Existing Qwen Adapter contains unowned assets: $($unowned -join ', ')"
        }
    }

    $suffix = [Guid]::NewGuid().ToString("N")
    $staging = Join-Path $extensionParent (".$script:AdapterName.staging-$suffix")
    $previous = Join-Path $extensionParent (".$script:AdapterName.previous-$suffix")
    $switched = $false
    try {
        $node = Get-Command node -ErrorAction Stop
        $renderer = Join-Path $repository "integrations\host-adapter-kit\scripts\render-host-config.mjs"
        $adapter = Join-Path $repository "integrations\qwen-cli\adapter-manifest.json"
        $settings = Get-Content -LiteralPath (Join-Path $repository "config\mcp-agent-settings.json") `
            -Raw -Encoding UTF8 | ConvertFrom-Json
        $arguments = @($renderer, "--repository", $repository, "--adapter", $adapter, "--output", $staging)
        if (-not [string]::IsNullOrWhiteSpace($settings.knowledgeDirectory)) {
            $arguments += @("--knowledge", (Expand-AdaSetting -Value $settings.knowledgeDirectory))
        }
        & $node.Source @arguments | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Host Adapter renderer failed with exit code $LASTEXITCODE" }
        Write-OwnershipManifest -ExtensionRoot $staging -RepositoryRoot $repository

        if (Test-Path -LiteralPath $extensionRoot) {
            Move-Item -LiteralPath $extensionRoot -Destination $previous
        }
        Move-Item -LiteralPath $staging -Destination $extensionRoot
        $switched = $true
        if ($Scope -eq "User") {
            Invoke-QwenExtensionCommand -Action "enable"
        }
        if (Test-Path -LiteralPath $previous) {
            Remove-Item -LiteralPath $previous -Recurse -Force
        }
    }
    catch {
        if ($switched -and (Test-Path -LiteralPath $extensionRoot)) {
            Remove-Item -LiteralPath $extensionRoot -Recurse -Force
        }
        if (Test-Path -LiteralPath $previous) {
            Move-Item -LiteralPath $previous -Destination $extensionRoot
        }
        throw
    }
    finally {
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    }
    return [ordered]@{ profileRoot = $profile; extensionRoot = $extensionRoot }
}

function Test-QwenAdapterInstallation {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][ValidateSet("User", "TestProfile")][string]$Scope,
        [string]$ProfileRoot
    )
    $repository = Resolve-AdaRepositoryRoot -RepositoryRoot $RepositoryRoot
    $profile = Resolve-QwenProfileRoot -Scope $Scope -RepositoryRoot $repository -ProfileRoot $ProfileRoot
    $extensionRoot = Get-QwenExtensionRoot -ProfileRoot $profile
    $manifest = Read-OwnershipManifest -ExtensionRoot $extensionRoot
    if ($null -eq $manifest) { throw "Qwen Adapter is not installed" }
    Test-OwnedFiles -ExtensionRoot $extensionRoot -Manifest $manifest -ThrowOnMismatch | Out-Null
    $unowned = @(Get-UnownedFiles -ExtensionRoot $extensionRoot -Manifest $manifest)
    if ($unowned.Count -gt 0) {
        throw "Installed Qwen Adapter contains unowned assets: $($unowned -join ', ')"
    }
    if ([System.IO.Path]::GetFullPath($manifest.repositoryRoot) -ne $repository) {
        throw "Installed Qwen Adapter points to a different repository"
    }
    return [ordered]@{ profileRoot = $profile; extensionRoot = $extensionRoot }
}

function Uninstall-QwenAdapter {
    param(
        [Parameter(Mandatory = $true)][string]$RepositoryRoot,
        [Parameter(Mandatory = $true)][ValidateSet("User", "TestProfile")][string]$Scope,
        [string]$ProfileRoot
    )
    $repository = [System.IO.Path]::GetFullPath($RepositoryRoot)
    $profile = Resolve-QwenProfileRoot -Scope $Scope -RepositoryRoot $repository -ProfileRoot $ProfileRoot
    $extensionRoot = Get-QwenExtensionRoot -ProfileRoot $profile
    $manifest = Read-OwnershipManifest -ExtensionRoot $extensionRoot
    if ($null -eq $manifest) {
        return [ordered]@{ profileRoot = $profile; extensionRoot = $extensionRoot; preserved = @() }
    }
    $mismatches = @(Test-OwnedFiles -ExtensionRoot $extensionRoot -Manifest $manifest)
    $extraFiles = @(Get-UnownedFiles -ExtensionRoot $extensionRoot -Manifest $manifest)
    if ($Scope -eq "User" -and $mismatches.Count -eq 0 -and $extraFiles.Count -eq 0) {
        Invoke-QwenExtensionCommand -Action "uninstall"
        return [ordered]@{ profileRoot = $profile; extensionRoot = $extensionRoot; preserved = @() }
    }
    if ($Scope -eq "User") {
        Invoke-QwenExtensionCommand -Action "disable"
    }
    $preserved = [System.Collections.Generic.List[string]]::new()
    foreach ($file in $manifest.files) {
        $managedPath = Assert-ContainedPath -Root $extensionRoot -Candidate (Join-Path $extensionRoot $file.path)
        if (-not (Test-Path -LiteralPath $managedPath -PathType Leaf)) { continue }
        if ((Get-AdaFileHash -Path $managedPath) -eq $file.sha256) {
            Remove-Item -LiteralPath $managedPath -Force
        }
        else {
            $preserved.Add($file.path)
        }
    }
    foreach ($extra in $extraFiles) { $preserved.Add($extra) }
    Remove-Item -LiteralPath (Join-Path $extensionRoot $script:OwnershipFileName) -Force
    Get-ChildItem -LiteralPath $extensionRoot -Recurse -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Where-Object { @(Get-ChildItem -LiteralPath $_.FullName -Force).Count -eq 0 } |
        Remove-Item -Force
    if ((Test-Path -LiteralPath $extensionRoot) -and
        @(Get-ChildItem -LiteralPath $extensionRoot -Force).Count -eq 0) {
        Remove-Item -LiteralPath $extensionRoot -Force
    }
    return [ordered]@{
        profileRoot = $profile
        extensionRoot = $extensionRoot
        preserved = @($preserved)
    }
}

Export-ModuleMember -Function Install-QwenAdapter, Test-QwenAdapterInstallation, Uninstall-QwenAdapter
