param(
    [string]$TestClass = '', [string]$Serial = '', [string]$PhoneReleaseEvidence = '',
    [string]$TaskId = 'local', [string]$Owner = $env:USERNAME,
    [string]$ToolRoot = (Join-Path $env:LOCALAPPDATA 'BookCoverBuild'),
    [string]$StateRoot = (Join-Path $env:USERPROFILE '.bookcover-coordination'),
    [string]$BuildRoot = '', [string]$ProjectCache = '', [switch]$Plan
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Task-Operations.ps1')
$context = Get-BookCoverTaskContext -CheckoutRoot (Split-Path -Parent $PSScriptRoot) -TaskId $TaskId -ToolRoot $ToolRoot -StateRoot $StateRoot
Assert-BookCoverLegacyPaths -Context $context -BuildRoot $BuildRoot -ProjectCache $ProjectCache
if ($Plan) { $context | ConvertTo-Json -Depth 5; return }
$result = Invoke-BookCoverDeviceTests -Context $context -Owner $Owner -Serial $Serial -PhoneReleaseEvidence $PhoneReleaseEvidence -TestClass $TestClass
Write-Host "QA receipt: $($result.ReceiptPath)"
if ($result.EvidenceManifest) { Write-Host "Combined verified evidence: $($result.EvidenceManifest)" }
$result
