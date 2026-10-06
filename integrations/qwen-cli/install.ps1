param(
    [Parameter(Mandatory = $true)][string]$RepositoryRoot,
    [ValidateSet("User", "TestProfile")][string]$Scope = "User",
    [string]$ProfileRoot = ""
)

$ErrorActionPreference = "Stop"
Import-Module (Join-Path $PSScriptRoot "AdapterLifecycle.psm1") -Force
$result = Install-QwenAdapter -RepositoryRoot $RepositoryRoot -Scope $Scope -ProfileRoot $ProfileRoot
Write-Output "QWEN_ADAPTER_INSTALL_OK"
Write-Output "QWEN_PROFILE_ROOT=$($result.profileRoot)"
Write-Output "QWEN_EXTENSION_ROOT=$($result.extensionRoot)"
