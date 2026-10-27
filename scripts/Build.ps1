param(
    [string]$TaskId = 'local', [string]$Owner = $env:USERNAME,
    [string]$ToolRoot = (Join-Path $env:LOCALAPPDATA 'BookCoverBuild'),
    [string]$StateRoot = (Join-Path $env:USERPROFILE '.bookcover-coordination'),
    [string]$BuildRoot = '', [string]$ProjectCache = '', [string]$ApkName = '', [switch]$Plan
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Task-Operations.ps1')
$context = Get-BookCoverTaskContext -CheckoutRoot (Split-Path -Parent $PSScriptRoot) -TaskId $TaskId -ToolRoot $ToolRoot -StateRoot $StateRoot
Assert-BookCoverLegacyPaths -Context $context -BuildRoot $BuildRoot -ProjectCache $ProjectCache -ApkName $ApkName
if ($Plan) { $context | ConvertTo-Json -Depth 5; return }
$result = Invoke-BookCoverBuild -Context $context -Owner $Owner
Write-Host "Finished. APK: $($context.ApkPath)"
Write-Host "Verified evidence: $($result.EvidenceManifest)"
$result
