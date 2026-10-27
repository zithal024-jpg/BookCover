<#
Host-only checks for Task-Resources.ps1. Requires Windows PowerShell 5.1 or newer;
does not use Gradle, Java, the Android SDK, adb, a phone, or a test framework.
All checkouts, resource state, APKs and reports below are disposable fake files.

Run from any directory:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/Test-TaskResources.ps1
#>
[CmdletBinding()]
param(
    [string]$HelperPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

if ([string]::IsNullOrWhiteSpace($HelperPath)) {
    $HelperPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'Task-Resources.ps1'
}
if (-not (Test-Path -LiteralPath $HelperPath -PathType Leaf)) {
    throw "Task resource helper was not found: $HelperPath"
}
. $HelperPath

$script:Passed = 0
$script:Failed = 0
$script:CaseNumber = 0
$script:KeepTempRoot = $false
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding -ArgumentList $false
$script:TempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$script:TempLeaf = 'BookCover-task-resources-tests-' + [guid]::NewGuid().ToString('N')
$script:TempRoot = Join-Path $script:TempParent $script:TempLeaf
New-Item -ItemType Directory -Path $script:TempRoot | Out-Null

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw ("{0}; expected '{1}', got '{2}'." -f $Message, $Expected, $Actual)
    }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Message)
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw $Message }
}

function Invoke-HostCheck {
    param([string]$Name, [scriptblock]$Body)
    try {
        & $Body | Out-Null
        $script:Passed++
        Write-Host ("PASS {0}" -f $Name)
    } catch {
        $script:Failed++
        Write-Host ("FAIL {0}: {1}" -f $Name, $_.Exception.Message)
    }
}

function Copy-TestObject {
    param($Value)
    $copy = [pscustomobject]@{}
    foreach ($property in $Value.PSObject.Properties) {
        Add-Member -InputObject $copy -MemberType NoteProperty -Name $property.Name -Value $property.Value
    }
    return $copy
}

function Write-TestText {
    param([string]$Path, [string]$Text)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Text, $script:Utf8NoBom)
}

function New-TestCase {
    $script:CaseNumber++
    $caseRoot = Join-Path $script:TempRoot ('case-{0:d3}' -f $script:CaseNumber)
    $checkout = Join-Path $caseRoot 'checkout'
    $otherCheckout = Join-Path $caseRoot 'other-checkout'
    $toolRoot = Join-Path $caseRoot 'tools'
    $stateRoot = Join-Path $caseRoot 'state'
    foreach ($path in @($caseRoot, $checkout, $otherCheckout, $toolRoot)) {
        New-Item -ItemType Directory -Path $path -Force | Out-Null
    }
    $context = Get-BookCoverTaskContext -CheckoutRoot $checkout -TaskId 'host-check' -ToolRoot $toolRoot -StateRoot $stateRoot
    return [pscustomobject]@{
        Root = $caseRoot
        Checkout = $checkout
        OtherCheckout = $otherCheckout
        ToolRoot = $toolRoot
        StateRoot = $stateRoot
        Context = $context
    }
}

function Initialize-TestResources {
    param($Case)
    Initialize-BookCoverResources -StateRoot $Case.StateRoot -Coordinator 'host-test-coordinator' `
        -PreviousReleaseEvidence 'Disposable fixture: no Gradle process or device operation has ever used these paths.' | Out-Null
}

function Enter-TestBuild {
    param($Context)
    return Enter-BookCoverResource -Context $Context -Resource BUILD -Owner 'host-test-owner' -Purpose 'Host-only fake artifact verification'
}

function New-EvidenceFixture {
    $case = New-TestCase
    Initialize-TestResources $case
    $context = $case.Context
    $lease = Enter-TestBuild $context
    Write-TestText -Path (Join-Path $case.Checkout 'build.gradle.kts') -Text '// Stable host-only build source.'
    $buildRun = Start-BookCoverRun -Context $context -BuildLease $lease -Kind Build
    Write-TestText -Path $context.BuiltApkPath -Text 'This is a fake APK; no Android package is built or installed.'
    Write-TestText -Path $context.ApkPath -Text ([IO.File]::ReadAllText($context.BuiltApkPath))
    $hash = (Get-BCFileRecord $context.ApkPath).Sha256
    Write-TestText -Path $context.ChecksumPath -Text ("{0}  {1}`n" -f $hash, $context.ApkName)
    $unitFile = Join-Path $context.UnitResultsPath 'TEST-host-unit.xml'
    Write-TestText -Path $unitFile -Text @'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="host-unit" tests="3" failures="0" errors="0" skipped="1">
  <testcase classname="HostUnit" name="passes-one" />
  <testcase classname="HostUnit" name="passes-two" />
  <testcase classname="HostUnit" name="skipped"><skipped message="Host fixture" /></testcase>
</testsuite>
'@
    foreach ($lintFile in @($context.LintDebugPath, $context.LintQaPath)) {
        Write-TestText -Path $lintFile -Text '<issues format="6" by="lint"><issue id="HostWarning" severity="Warning" message="Warnings are recorded without becoming errors." /></issues>'
    }
    Complete-BookCoverRun -Context $context -BuildLease $lease -Run $buildRun -ExitCode 0 -OperationStopped | Out-Null
    $deviceFile = Join-Path $context.DeviceResultsPath 'TEST-host-qa.xml'
    $phone = Enter-BookCoverResource -Context $context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Host-only fake QA receipt' `
        -Serial 'HOST-ONLY' -BuildLeaseId $lease.LeaseId
    try {
        $deviceRun = Start-BookCoverRun -Context $context -BuildLease $lease -Kind Device -Serial 'HOST-ONLY'
        Write-TestText -Path $deviceFile -Text @'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.bookcover.diagnostic.qa.HostFixture" tests="2" failures="0" errors="0" skipped="1">
  <testcase classname="HostQa" name="passes" />
  <testcase classname="HostQa" name="skipped"><skipped message="Host fixture" /></testcase>
</testsuite>
'@
        Complete-BookCoverRun -Context $context -BuildLease $lease -Run $deviceRun -ExitCode 0 -OperationStopped | Out-Null
    } finally {
        Exit-BookCoverResource -Context $context -Lease $phone -ReleaseEvidence 'Fake QA report generation completed synchronously; no device operation ran.' -OperationStopped | Out-Null
    }
    return [pscustomobject]@{
        Case = $case
        Context = $context
        Lease = $lease
        Snapshot = $buildRun.SourceSnapshotId
        UnitFile = $unitFile
        DeviceFile = $deviceFile
    }
}

function Write-TestManifest {
    param($Fixture, [switch]$IncludeDeviceResults)
    return Write-BookCoverEvidence -Context $Fixture.Context -BuildLease $Fixture.Lease `
        -SourceSnapshotId $Fixture.Snapshot -IncludeDeviceResults:$IncludeDeviceResults
}

function Get-TestPackage {
    param($Fixture, [string]$ManifestPath)
    return Get-BookCoverPackageInputs -Context $Fixture.Context -ManifestPath $ManifestPath -SourceSnapshotId $Fixture.Snapshot
}

try {
    Invoke-HostCheck 'file records hash known bytes without the Get-FileHash command' {
        $case = New-TestCase
        $path = Join-Path $case.Root 'known-hash.txt'
        Write-TestText -Path $path -Text 'abc'
        $priorHashFunction = Get-Item -LiteralPath Function:\Get-FileHash -ErrorAction SilentlyContinue
        $priorHashScriptBlock = if ($null -ne $priorHashFunction) { $priorHashFunction.ScriptBlock } else { $null }
        try {
            Set-Item -LiteralPath Function:\Get-FileHash -Value { throw 'Get-FileHash is deliberately unavailable in this host regression.' }
            Assert-Throws { Get-FileHash -LiteralPath $path -Algorithm SHA256 } 'The Get-FileHash shadow was not active'
            $record = Get-BCFileRecord $path
            Assert-Equal $record.Sha256 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad' 'The file record returned an incorrect SHA256 digest'
            Assert-Equal $record.Bytes 3 'The file record returned an incorrect byte count'
        } finally {
            if ($null -eq $priorHashFunction) { Remove-Item -LiteralPath Function:\Get-FileHash }
            else { Set-Item -LiteralPath Function:\Get-FileHash -Value $priorHashScriptBlock }
        }
    }

    Invoke-HostCheck 'context paths are stable, isolated and side-effect free' {
        $case = New-TestCase
        $context = $case.Context
        $same = Get-BookCoverTaskContext -CheckoutRoot (Join-Path $case.Checkout '.') -TaskId 'host-check' `
            -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        $sameWindowsAlias = Get-BookCoverTaskContext -CheckoutRoot $case.Checkout.ToUpperInvariant() -TaskId 'host-check' `
            -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        $otherTask = Get-BookCoverTaskContext -CheckoutRoot $case.Checkout -TaskId 'other-task' `
            -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        $otherCheckout = Get-BookCoverTaskContext -CheckoutRoot $case.OtherCheckout -TaskId 'host-check' `
            -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        foreach ($field in @('CheckoutRoot', 'TaskId', 'TaskKey', 'TaskRoot', 'BuildRoot', 'ProjectCache', 'GradleUserHome',
                'ApkName', 'ApkPath', 'ChecksumPath', 'BuiltApkPath', 'UnitResultsPath', 'LintDebugPath', 'LintQaPath', 'DeviceResultsPath',
                'BuildReceiptPath', 'DeviceReceiptPath', 'StateRoot')) {
            Assert-True (-not [string]::IsNullOrWhiteSpace([string]$context.$field)) "Missing context field $field"
            Assert-Equal $same.$field $context.$field "Normalized checkout changed $field"
            Assert-Equal $sameWindowsAlias.$field $context.$field "Windows case alias changed $field"
        }
        foreach ($field in @('TaskKey', 'TaskRoot', 'BuildRoot', 'ProjectCache', 'GradleUserHome', 'ApkName', 'ApkPath', 'ChecksumPath')) {
            Assert-True ($context.$field -ne $otherTask.$field) "Different tasks share $field"
            Assert-True ($context.$field -ne $otherCheckout.$field) "Different checkouts share $field"
        }
        Assert-True (-not (Test-Path -LiteralPath $case.StateRoot)) 'Getting a context created resource state'
        Assert-True (-not (Test-Path -LiteralPath $context.TaskRoot)) 'Getting a context created task output'
    }

    Invoke-HostCheck 'task IDs reject traversal, separators and invalid slugs' {
        $case = New-TestCase
        foreach ($taskId in @('', '..', '../escape', '..\escape', 'a/b', 'a\b', 'Uppercase', 'two words', 'a:b', '-leading', 'trailing-', 'a..b')) {
            Assert-Throws {
                Get-BookCoverTaskContext -CheckoutRoot $case.Checkout -TaskId $taskId -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
            } "Invalid task ID was accepted: '$taskId'"
        }
        Assert-Throws {
            Get-BookCoverTaskContext -CheckoutRoot (Join-Path $case.Root 'does-not-exist') -TaskId 'valid-task' `
                -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        } 'A missing checkout was accepted'
    }

    Invoke-HostCheck 'absent state fails closed and needs explicit bootstrap evidence' {
        $case = New-TestCase
        Assert-Throws { Enter-TestBuild $case.Context } 'Acquisition initialized absent state'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $case.StateRoot 'resources.json'))) 'Failed acquisition created resource state'
        Assert-Throws {
            Initialize-BookCoverResources -StateRoot $case.StateRoot -Coordinator '' -PreviousReleaseEvidence 'known free'
        } 'Bootstrap accepted an empty coordinator'
        Assert-Throws {
            Initialize-BookCoverResources -StateRoot $case.StateRoot -Coordinator 'host-test-coordinator' -PreviousReleaseEvidence ''
        } 'Bootstrap accepted empty previous-release evidence'
        Initialize-TestResources $case
        $statePath = Join-Path $case.StateRoot 'resources.json'
        $before = [IO.File]::ReadAllText($statePath)
        Assert-Throws { Initialize-TestResources $case } 'Bootstrap overwrote initialized state'
        Assert-Equal ([IO.File]::ReadAllText($statePath)) $before 'Rejected bootstrap changed state'
    }

    foreach ($badState in @('{', '{}', 'null', '[]')) {
        $stateText = $badState
        Invoke-HostCheck ("malformed or unknown state fails closed: {0}" -f $stateText) {
            $case = New-TestCase
            $statePath = Join-Path $case.StateRoot 'resources.json'
            Write-TestText -Path $statePath -Text $stateText
            Assert-Throws { Enter-TestBuild $case.Context } 'Acquisition accepted invalid resource state'
            Assert-Throws { Initialize-TestResources $case } 'Bootstrap replaced invalid existing resource state'
            Assert-Equal ([IO.File]::ReadAllText($statePath)) $stateText 'Rejected operation changed invalid state'
        }
    }

    Invoke-HostCheck 'unknown transaction guard fails closed and is retained' {
        $case = New-TestCase
        Initialize-TestResources $case
        $guardPath = Join-Path $case.StateRoot 'state.guard'
        $statePath = Join-Path $case.StateRoot 'resources.json'
        $stateBefore = [IO.File]::ReadAllText($statePath)
        Write-TestText -Path $guardPath -Text 'Unknown or interrupted holder; host tests must never clear this guard.'
        $sameContext = Get-BookCoverTaskContext -CheckoutRoot $case.Checkout -TaskId 'host-check' `
            -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        Assert-Equal $sameContext.TaskKey $case.Context.TaskKey 'A state guard prevented ordinary context lookup'
        Assert-Throws { Enter-TestBuild $case.Context } 'Acquisition ignored an existing transaction guard'
        Assert-True (Test-Path -LiteralPath $guardPath -PathType Leaf) 'Rejected acquisition removed the existing guard'
        Assert-Equal ([IO.File]::ReadAllText($guardPath)) 'Unknown or interrupted holder; host tests must never clear this guard.' 'Existing guard was altered'
        Assert-Equal ([IO.File]::ReadAllText($statePath)) $stateBefore 'Guard rejection altered resource state'
    }

    Invoke-HostCheck 'leases enforce conflicts, context, identity and release ordering' {
        $case = New-TestCase
        Initialize-TestResources $case
        $context = $case.Context
        $other = Get-BookCoverTaskContext -CheckoutRoot $case.OtherCheckout -TaskId 'host-check' -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        $otherTask = Get-BookCoverTaskContext -CheckoutRoot $case.Checkout -TaskId 'other-task' -ToolRoot $case.ToolRoot -StateRoot $case.StateRoot
        $build = Enter-TestBuild $context
        Assert-True (-not [string]::IsNullOrWhiteSpace($build.LeaseId)) 'BUILD did not return a lease ID'
        Assert-Throws { Enter-TestBuild $context } 'BUILD allowed a second lease for the same task'
        Assert-Throws { Enter-TestBuild $other } 'BUILD allowed a conflicting checkout'
        Assert-Throws { Enter-TestBuild $otherTask } 'BUILD allowed a conflicting task in the same checkout'
        Assert-Throws {
            Enter-BookCoverResource -Context $context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Fake phone' -BuildLeaseId $build.LeaseId
        } 'PHONE accepted an implicit serial'
        Assert-Throws {
            Enter-BookCoverResource -Context $context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Fake phone' -Serial 'HOST-ONLY' -BuildLeaseId 'wrong-id'
        } 'PHONE accepted a different BUILD lease ID'
        Assert-Throws {
            Enter-BookCoverResource -Context $other -Resource PHONE -Owner 'host-test-owner' -Purpose 'Fake phone' -Serial 'HOST-ONLY' -BuildLeaseId $build.LeaseId
        } 'PHONE accepted a BUILD lease from another checkout'
        $phone = Enter-BookCoverResource -Context $context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Host-only state test' `
            -Serial 'HOST-ONLY' -BuildLeaseId $build.LeaseId
        Assert-BookCoverResource -Context $context -Lease $build | Out-Null
        Assert-BookCoverResource -Context $context -Lease $phone | Out-Null
        Assert-Throws {
            Enter-BookCoverResource -Context $context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Fake phone' -Serial 'HOST-ONLY' -BuildLeaseId $build.LeaseId
        } 'PHONE allowed a conflicting serial lease'
        Assert-Throws {
            Exit-BookCoverResource -Context $context -Lease $build -ReleaseEvidence 'Host fixture is stopped.' -OperationStopped
        } 'BUILD released while PHONE remained held'
        Assert-Throws {
            Exit-BookCoverResource -Context $context -Lease $phone -ReleaseEvidence 'No stopped assertion.'
        } 'Release omitted the explicit stopped assertion'
        Assert-Throws {
            Exit-BookCoverResource -Context $context -Lease $phone -ReleaseEvidence '' -OperationStopped
        } 'Release omitted evidence'
        foreach ($field in @('Owner', 'LeaseId')) {
            $wrongLease = Copy-TestObject $phone
            $wrongLease.$field = 'not-the-persisted-value'
            Assert-Throws { Assert-BookCoverResource -Context $context -Lease $wrongLease } "Assertion accepted a wrong $field"
            Assert-Throws {
                Exit-BookCoverResource -Context $context -Lease $wrongLease -ReleaseEvidence 'Host fixture is stopped.' -OperationStopped
            } "Release accepted a wrong $field"
        }
        Assert-Throws { Assert-BookCoverResource -Context $other -Lease $phone } 'Assertion accepted the wrong checkout/task'
        Assert-Throws { Assert-BookCoverResource -Context $otherTask -Lease $phone } 'Assertion accepted another task in the same checkout'
        Assert-Throws {
            Exit-BookCoverResource -Context $other -Lease $phone -ReleaseEvidence 'Host fixture is stopped.' -OperationStopped
        } 'Release accepted the wrong checkout/task'
        Assert-Throws {
            Exit-BookCoverResource -Context $otherTask -Lease $phone -ReleaseEvidence 'Host fixture is stopped.' -OperationStopped
        } 'Release accepted another task in the same checkout'
        Exit-BookCoverResource -Context $context -Lease $phone -ReleaseEvidence 'Host-only fake phone operation has stopped.' -OperationStopped | Out-Null
        Assert-Throws { Assert-BookCoverResource -Context $context -Lease $phone } 'A released PHONE lease was still active'
        Assert-Throws {
            Exit-BookCoverResource -Context $context -Lease $phone -ReleaseEvidence 'Already released.' -OperationStopped
        } 'The same PHONE lease released twice'
        Assert-BookCoverResource -Context $context -Lease $build | Out-Null
        Exit-BookCoverResource -Context $context -Lease $build -ReleaseEvidence 'All host-only fake operations have stopped.' -OperationStopped | Out-Null
        Assert-Throws { Assert-BookCoverResource -Context $context -Lease $build } 'A released BUILD lease was still active'
        $nextBuild = Enter-TestBuild $other
        Assert-True ($nextBuild.LeaseId -ne $build.LeaseId) 'Reacquisition reused a released lease ID'
        Exit-BookCoverResource -Context $other -Lease $nextBuild -ReleaseEvidence 'Host-only reacquisition check stopped.' -OperationStopped | Out-Null
    }

    Invoke-HostCheck 'PHONE needs an active BUILD owned by the same owner' {
        $case = New-TestCase
        Initialize-TestResources $case
        Assert-Throws {
            Enter-BookCoverResource -Context $case.Context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Fake phone' -Serial 'HOST-ONLY' -BuildLeaseId 'missing-build'
        } 'PHONE was acquired without BUILD'
        $build = Enter-TestBuild $case.Context
        Assert-Throws {
            Enter-BookCoverResource -Context $case.Context -Resource PHONE -Owner 'different-owner' -Purpose 'Fake phone' -Serial 'HOST-ONLY' -BuildLeaseId $build.LeaseId
        } 'PHONE accepted a BUILD belonging to another owner'
        Exit-BookCoverResource -Context $case.Context -Lease $build -ReleaseEvidence 'Host-only operation stopped.' -OperationStopped | Out-Null
        Assert-Throws {
            Enter-BookCoverResource -Context $case.Context -Resource PHONE -Owner 'host-test-owner' -Purpose 'Fake phone' -Serial 'HOST-ONLY' -BuildLeaseId $build.LeaseId
        } 'PHONE accepted a released BUILD'
    }

    Invoke-HostCheck 'two concurrent host processes yield exactly one BUILD lease' {
        $case = New-TestCase
        Initialize-TestResources $case
        $barrierPath = Join-Path $case.Root 'race-start'
        $readyPaths = @((Join-Path $case.Root 'worker-1-ready'), (Join-Path $case.Root 'worker-2-ready'))
        $contextJson = $case.Context | ConvertTo-Json -Depth 12
        $helperFullPath = (Resolve-Path -LiteralPath $HelperPath).ProviderPath
        $deadlineTicks = [DateTime]::UtcNow.AddSeconds(15).Ticks
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $jobs = @()
        $worker = {
            param($Helper, $ContextJson, $BarrierPath, $ReadyPath, [long]$DeadlineTicks, [int]$Number)
            $ErrorActionPreference = 'Stop'
            Set-StrictMode -Version 2.0
            if ([DateTime]::UtcNow.Ticks -ge $DeadlineTicks) {
                return [pscustomobject]@{ Status = 'TimedOut'; Worker = $Number }
            }
            . $Helper
            $context = $ContextJson | ConvertFrom-Json
            if ([DateTime]::UtcNow.Ticks -ge $DeadlineTicks) {
                return [pscustomobject]@{ Status = 'TimedOut'; Worker = $Number }
            }
            [IO.File]::WriteAllText($ReadyPath, 'Ready after loading the host-only helper.')
            while (-not [IO.File]::Exists($BarrierPath)) {
                if ([DateTime]::UtcNow.Ticks -ge $DeadlineTicks) {
                    return [pscustomobject]@{ Status = 'TimedOut'; Worker = $Number }
                }
                [Threading.Thread]::Sleep(10)
            }
            if ([DateTime]::UtcNow.Ticks -ge $DeadlineTicks) {
                return [pscustomobject]@{ Status = 'TimedOut'; Worker = $Number }
            }
            try {
                $lease = Enter-BookCoverResource -Context $context -Resource BUILD -Owner ("host-race-{0}" -f $Number) `
                    -Purpose 'Competing host-only fake resource acquisition'
                return [pscustomobject]@{ Status = 'Acquired'; Worker = $Number; Lease = $lease }
            } catch {
                return [pscustomobject]@{ Status = 'Rejected'; Worker = $Number; Reason = $_.Exception.Message }
            }
        }
        try {
            foreach ($number in @(1, 2)) {
                $jobs += Start-Job -ScriptBlock $worker -ArgumentList @($helperFullPath, $contextJson, $barrierPath, $readyPaths[$number - 1], $deadlineTicks, $number)
            }
            while (-not ((Test-Path -LiteralPath $readyPaths[0]) -and (Test-Path -LiteralPath $readyPaths[1]))) {
                if ($watch.Elapsed.TotalSeconds -ge 15) { throw 'Host race workers did not reach the barrier within 15 seconds.' }
                Start-Sleep -Milliseconds 20
            }
            Write-TestText -Path $barrierPath -Text 'Both host workers may now attempt the same fake BUILD.'
            $remainingSeconds = [int][Math]::Floor(15 - $watch.Elapsed.TotalSeconds)
            if ($remainingSeconds -gt 0) { Wait-Job -Job $jobs -Timeout $remainingSeconds | Out-Null }
            if (@($jobs | Where-Object { $_.State -ne 'Completed' }).Count -gt 0) {
                throw 'Host race workers did not complete within their shared 15-second deadline.'
            }
            $results = @(Receive-Job -Job $jobs)
            $winners = @($results | Where-Object { $_.Status -eq 'Acquired' })
            $rejections = @($results | Where-Object { $_.Status -eq 'Rejected' })
            Assert-Equal $results.Count 2 'Each host race worker must return one result'
            Assert-Equal $winners.Count 1 'The same fake BUILD must have exactly one concurrent winner'
            Assert-Equal $rejections.Count 1 'One competing host race worker must be rejected'
            $winningLease = $winners[0].Lease
            Assert-BookCoverResource -Context $case.Context -Lease $winningLease | Out-Null
            Exit-BookCoverResource -Context $case.Context -Lease $winningLease `
                -ReleaseEvidence 'Both host-only race workers completed; no fake operation remains running.' -OperationStopped | Out-Null
            Assert-Throws { Assert-BookCoverResource -Context $case.Context -Lease $winningLease } 'The concurrent winner remained active after explicit release'
        } finally {
            $watch.Stop()
            foreach ($job in $jobs) {
                if ($job.State -eq 'Completed') { Remove-Job -Job $job }
                else {
                    # Never stop or kill a worker. Its absolute deadline prevents
                    # late acquisition; retain its files if completion is unknown.
                    $script:KeepTempRoot = $true
                }
            }
        }
    }

    Invoke-HostCheck 'orphaned PHONE state fails closed without acquiring or rewriting state' {
        $case = New-TestCase
        Initialize-TestResources $case
        $build = Enter-TestBuild $case.Context
        $phone = Enter-BookCoverResource -Context $case.Context -Resource PHONE -Owner 'host-test-owner' `
            -Purpose 'Host-only orphan-state check' -Serial 'HOST-ONLY' -BuildLeaseId $build.LeaseId
        $statePath = Join-Path $case.StateRoot 'resources.json'
        $state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
        $state.resources.PSObject.Properties.Remove('BUILD')
        Write-TestText -Path $statePath -Text ($state | ConvertTo-Json -Depth 12)
        $before = [IO.File]::ReadAllText($statePath)
        Assert-Throws { Enter-TestBuild $case.Context } 'Acquisition ignored an orphaned PHONE lease'
        Assert-Throws { Assert-BookCoverResource -Context $case.Context -Lease $phone } 'Assertion accepted PHONE with no BUILD parent'
        Assert-Throws { Assert-BookCoverResource -Context $case.Context -Lease $build } 'Assertion accepted the missing BUILD parent'
        Assert-Throws {
            Enter-BookCoverResource -Context $case.Context -Resource PHONE -Owner 'host-test-owner' `
                -Purpose 'Host-only orphan-state check' -Serial 'HOST-OTHER' -BuildLeaseId $build.LeaseId
        } 'PHONE acquisition ignored an orphaned resource state'
        Assert-Equal ([IO.File]::ReadAllText($statePath)) $before 'Rejected orphan-state checks changed resource state'
    }

    Invoke-HostCheck 'explicit evidence selects exact task APK and records skipped unit tests' {
        $fixture = New-EvidenceFixture
        $manifest = Write-TestManifest $fixture
        $secondManifest = Write-TestManifest $fixture
        Assert-True ($manifest -is [string]) 'Evidence did not return one manifest path string'
        Assert-True (Test-Path -LiteralPath $manifest -PathType Leaf) 'Evidence manifest was not written'
        Assert-True ($manifest -ne $secondManifest) 'A second evidence write reused a manifest filename'
        $package = Get-TestPackage $fixture $manifest
        Assert-Equal $package.ApkPath $fixture.Context.ApkPath 'Packaging selected a different APK'
        Assert-Equal $package.ChecksumPath $fixture.Context.ChecksumPath 'Packaging selected a different checksum'
        Assert-Equal $package.SourceSnapshotId $fixture.Snapshot 'Packaging returned a different source assertion'
        Assert-Equal @($package.UnitReports).Count 1 'Unit report selection changed'
        Assert-Equal $package.UnitReports[0] $fixture.UnitFile 'Packaging selected a different unit report'
        Assert-Equal @($package.LintReports).Count 2 'Both lint variants were not selected'
        Assert-Equal @($package.DeviceReports).Count 0 'Unrequested device results were included'
        Assert-Equal $package.UnitTests.Tests 3 'Total unit test count was not preserved'
        Assert-Equal $package.UnitTests.Skipped 1 'Skipped unit test count was not preserved'
        Assert-Equal $package.UnitTests.Failures 0 'Passing unit reports recorded failures'
        Assert-Equal $package.UnitTests.Errors 0 'Passing unit reports recorded errors'
    }

    Invoke-HostCheck 'optional isolated QA evidence records passing and skipped tests' {
        $fixture = New-EvidenceFixture
        $manifest = Write-TestManifest $fixture -IncludeDeviceResults
        $package = Get-TestPackage $fixture $manifest
        Assert-Equal @($package.DeviceReports).Count 1 'The isolated QA report was not selected'
        Assert-Equal $package.DeviceReports[0] $fixture.DeviceFile 'Packaging selected a different device report'
        Assert-Equal $package.DeviceTests.Tests 2 'Total QA test count was not preserved'
        Assert-Equal $package.DeviceTests.Skipped 1 'Skipped QA test count was not preserved'
        Assert-Equal $package.DeviceTests.Failures 0 'Passing QA report recorded failures'
        Assert-Equal $package.DeviceTests.Errors 0 'Passing QA report recorded errors'
    }

    Invoke-HostCheck 'evidence requires an active BUILD lease and the automatic source snapshot' {
        $fixture = New-EvidenceFixture
        foreach ($snapshot in @('', 'not-a-source-snapshot', ('a' * 39), ('a' * 40), ('a' * 64), ('a' * 65), ('g' * 64))) {
            Assert-Throws {
                Write-BookCoverEvidence -Context $fixture.Context -BuildLease $fixture.Lease -SourceSnapshotId $snapshot
            } 'Evidence accepted an invalid source snapshot assertion'
        }
        $wrongLease = Copy-TestObject $fixture.Lease
        $wrongLease.LeaseId = 'not-the-active-build'
        Assert-Throws {
            Write-BookCoverEvidence -Context $fixture.Context -BuildLease $wrongLease -SourceSnapshotId $fixture.Snapshot
        } 'Evidence accepted a different BUILD lease'
        Exit-BookCoverResource -Context $fixture.Context -Lease $fixture.Lease -ReleaseEvidence 'Host-only fake build stopped.' -OperationStopped | Out-Null
        Assert-Throws { Write-TestManifest $fixture } 'Evidence accepted a released BUILD lease'
    }

    Invoke-HostCheck 'automatic source identity cannot replace a missing successful build receipt' {
        $fixture = New-EvidenceFixture
        Remove-Item -LiteralPath $fixture.Context.BuildReceiptPath
        Assert-Throws { Write-TestManifest $fixture } 'Automatic source identity fabricated a completed build receipt'
    }

    Invoke-HostCheck 'a new running or failed operation invalidates earlier build evidence' {
        $fixture = New-EvidenceFixture
        $manifest = Write-TestManifest $fixture
        $running = Start-BookCoverRun -Context $fixture.Context -BuildLease $fixture.Lease -Kind Build
        Assert-Throws { Write-TestManifest $fixture } 'A running operation accepted earlier successful artifacts'
        Assert-Throws { Get-TestPackage $fixture $manifest } 'An earlier manifest survived a new running operation'
        Assert-Throws {
            Complete-BookCoverRun -Context $fixture.Context -BuildLease $fixture.Lease -Run $running -ExitCode 7 -OperationStopped
        } 'A failed native result produced a successful receipt'
        Assert-Throws { Get-TestPackage $fixture $manifest } 'An earlier manifest survived a failed operation'
    }

    Invoke-HostCheck 'source byte changes reject old receipts and evidence' {
        $fixture = New-EvidenceFixture
        $manifest = Write-TestManifest $fixture
        Write-TestText -Path (Join-Path $fixture.Case.Checkout 'build.gradle.kts') -Text '// Changed source after the completed fake build.'
        Assert-Throws { Write-TestManifest $fixture } 'Evidence accepted changed build inputs'
        Assert-Throws { Get-TestPackage $fixture $manifest } 'Package selection accepted changed build inputs'
    }

    Invoke-HostCheck 'workflow source is frozen and edits invalidate prior package evidence' {
        $fixture = New-EvidenceFixture
        $workflowPath = Join-Path $fixture.Case.Checkout '.github\workflows\android.yml'
        Write-TestText -Path $workflowPath -Text "name: Host fixture CI`non: workflow_dispatch`n"
        $run = Start-BookCoverRun -Context $fixture.Context -BuildLease $fixture.Lease -Kind Build
        Complete-BookCoverRun -Context $fixture.Context -BuildLease $fixture.Lease -Run $run -ExitCode 0 -OperationStopped | Out-Null
        $fixture.Snapshot = $run.SourceSnapshotId
        $workflowFiles = @($run.SourceSnapshot.Files | Where-Object { $_.Path -ceq '.github/workflows/android.yml' })
        Assert-Equal $workflowFiles.Count 1 'The workflow was omitted from the automatic source snapshot'
        $manifest = Write-TestManifest $fixture
        $package = Get-TestPackage $fixture $manifest
        Assert-Equal @($package.SourceSnapshot.Files | Where-Object { $_.Path -ceq '.github/workflows/android.yml' }).Count 1 'Package source evidence omitted the workflow'
        Write-TestText -Path $workflowPath -Text "name: Changed host fixture CI`non: workflow_dispatch`n"
        $changed = Get-BookCoverSourceSnapshot -Context $fixture.Context
        Assert-True ($changed.SourceSnapshotId -cne $fixture.Snapshot) 'Changing the workflow did not change the source digest'
        Assert-Throws { Get-TestPackage $fixture $manifest } 'A workflow edit left earlier package evidence selectable'
    }

    Invoke-HostCheck 'evidence rejects distribution APK that differs from the built APK' {
        $fixture = New-EvidenceFixture
        Write-TestText -Path $fixture.Context.BuiltApkPath -Text 'Different fake built APK.'
        Assert-Throws { Write-TestManifest $fixture } 'Evidence accepted an unrelated distribution APK'
    }

    foreach ($checksumFault in @('missing', 'wrong-hash', 'wrong-filename', 'malformed')) {
        $fault = $checksumFault
        Invoke-HostCheck ("evidence rejects checksum {0}" -f $fault) {
            $fixture = New-EvidenceFixture
            switch ($fault) {
                'missing' { Remove-Item -LiteralPath $fixture.Context.ChecksumPath }
                'wrong-hash' { Write-TestText -Path $fixture.Context.ChecksumPath -Text ("{0}  {1}`n" -f ('0' * 64), $fixture.Context.ApkName) }
                'wrong-filename' {
                    $hash = (Get-BCFileRecord $fixture.Context.ApkPath).Sha256
                    Write-TestText -Path $fixture.Context.ChecksumPath -Text ("{0}  BookCover-prototype.apk`n" -f $hash)
                }
                'malformed' { Write-TestText -Path $fixture.Context.ChecksumPath -Text 'not a SHA-256 sidecar' }
            }
            Assert-Throws { Write-TestManifest $fixture } "Evidence accepted $fault checksum"
        }
    }

    foreach ($unitFault in @('missing', 'empty-suite', 'failure', 'error', 'malformed', 'test-count-mismatch', 'skip-count-mismatch', 'dtd-entity')) {
        $fault = $unitFault
        Invoke-HostCheck ("evidence rejects unit results {0}" -f $fault) {
            $fixture = New-EvidenceFixture
            $run = Start-BookCoverRun -Context $fixture.Context -BuildLease $fixture.Lease -Kind Build
            switch ($fault) {
                'missing' { Remove-Item -LiteralPath $fixture.UnitFile }
                'empty-suite' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite name="empty" tests="0" failures="0" errors="0" skipped="0" />' }
                'failure' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite name="failed" tests="1" failures="1" errors="0" skipped="0"><testcase name="failed"><failure message="Host fixture" /></testcase></testsuite>' }
                'error' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite name="error" tests="1" failures="0" errors="1" skipped="0"><testcase name="error"><error message="Host fixture" /></testcase></testsuite>' }
                'malformed' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite' }
                'test-count-mismatch' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite name="wrong-total" tests="2" failures="0" errors="0" skipped="0"><testcase name="only-one" /></testsuite>' }
                'skip-count-mismatch' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite name="wrong-skips" tests="2" failures="0" errors="0" skipped="0"><testcase name="passes" /><testcase name="skips"><skipped /></testcase></testsuite>' }
                'dtd-entity' { Write-TestText -Path $fixture.UnitFile -Text '<!DOCTYPE testsuite [<!ENTITY host "host-fixture">]><testsuite name="dtd" tests="1" failures="0" errors="0" skipped="0"><testcase name="&host;" /></testsuite>' }
            }
            Assert-Throws {
                Complete-BookCoverRun -Context $fixture.Context -BuildLease $fixture.Lease -Run $run -ExitCode 0 -OperationStopped
            } "A completed receipt accepted $fault unit results"
            Assert-Throws { Write-TestManifest $fixture } "Evidence accepted $fault unit results"
        }
    }

    foreach ($lintVariant in @('LintDebugPath', 'LintQaPath')) {
        $variant = $lintVariant
        Invoke-HostCheck ("evidence rejects errors in {0}" -f $variant) {
            $fixture = New-EvidenceFixture
            Write-TestText -Path $fixture.Context.$variant -Text '<issues format="6"><issue id="HostError" severity="Error" message="Host fixture" /></issues>'
            Assert-Throws { Write-TestManifest $fixture } "Evidence accepted lint errors in $variant"
        }
    }

    Invoke-HostCheck 'evidence rejects missing lint and failing optional QA results' {
        $fixture = New-EvidenceFixture
        Remove-Item -LiteralPath $fixture.Context.LintDebugPath
        Assert-Throws { Write-TestManifest $fixture } 'Evidence accepted a missing lint report'
        Write-TestText -Path $fixture.Context.LintDebugPath -Text '<issues format="6" />'
        Write-TestText -Path $fixture.DeviceFile -Text '<testsuite name="com.bookcover.diagnostic.qa.Failed" tests="1" failures="1" errors="0" skipped="0"><testcase name="failed"><failure message="Host fixture" /></testcase></testsuite>'
        Assert-Throws { Write-TestManifest $fixture -IncludeDeviceResults } 'Evidence accepted failing optional QA results'
    }

    Invoke-HostCheck 'evidence rejects a substituted non-QA result path' {
        $fixture = New-EvidenceFixture
        $wrongContext = Copy-TestObject $fixture.Context
        $wrongContext.DeviceResultsPath = Join-Path $fixture.Context.BuildRoot 'app\outputs\androidTest-results\connected\debug'
        Write-TestText -Path (Join-Path $wrongContext.DeviceResultsPath 'TEST-main.xml') -Text '<testsuite name="main" tests="1" failures="0" errors="0" skipped="0"><testcase name="passes" /></testsuite>'
        Assert-Throws {
            Write-BookCoverEvidence -Context $wrongContext -BuildLease $fixture.Lease -SourceSnapshotId $fixture.Snapshot -IncludeDeviceResults
        } 'Evidence accepted a substituted main-app device result directory'
    }

    Invoke-HostCheck 'package selection rejects missing, malformed and cross-checkout manifests' {
        $fixture = New-EvidenceFixture
        $manifest = Write-TestManifest $fixture
        Assert-Throws {
            Get-BookCoverPackageInputs -Context $fixture.Context -ManifestPath (Join-Path $fixture.Case.Root 'missing.json') -SourceSnapshotId $fixture.Snapshot
        } 'Packaging accepted a missing manifest'
        $malformed = Join-Path $fixture.Context.TaskRoot 'malformed-test-manifest.json'
        Write-TestText -Path $malformed -Text '{'
        Assert-Throws { Get-TestPackage $fixture $malformed } 'Packaging accepted a malformed manifest'
        $legacy = Join-Path $fixture.Context.TaskRoot 'legacy-v1-test-manifest.json'
        $legacyMetadata = [IO.File]::ReadAllText($manifest) | ConvertFrom-Json
        $legacyMetadata.SchemaVersion = 1
        Write-TestText -Path $legacy -Text ($legacyMetadata | ConvertTo-Json -Depth 12)
        Assert-Throws { Get-TestPackage $fixture $legacy } 'Packaging accepted legacy caller-asserted source evidence'
        $other = Get-BookCoverTaskContext -CheckoutRoot $fixture.Case.OtherCheckout -TaskId 'host-check' `
            -ToolRoot $fixture.Case.ToolRoot -StateRoot $fixture.Case.StateRoot
        Assert-Throws {
            Get-BookCoverPackageInputs -Context $other -ManifestPath $manifest -SourceSnapshotId $fixture.Snapshot
        } 'Packaging accepted evidence from another checkout'
        $otherTask = Get-BookCoverTaskContext -CheckoutRoot $fixture.Case.Checkout -TaskId 'other-task' `
            -ToolRoot $fixture.Case.ToolRoot -StateRoot $fixture.Case.StateRoot
        Assert-Throws {
            Get-BookCoverPackageInputs -Context $otherTask -ManifestPath $manifest -SourceSnapshotId $fixture.Snapshot
        } 'Packaging accepted evidence from another task in the same checkout'
        Assert-Throws {
            Get-BookCoverPackageInputs -Context $fixture.Context -ManifestPath $manifest -SourceSnapshotId ('b' * 64)
        } 'Packaging accepted a different source snapshot assertion'
    }

    foreach ($tamperTarget in @('apk', 'checksum', 'unit', 'lint-debug', 'lint-qa', 'device', 'build-receipt', 'device-receipt')) {
        $target = $tamperTarget
        Invoke-HostCheck ("package selection rejects changed {0} evidence" -f $target) {
            $fixture = New-EvidenceFixture
            $manifest = Write-TestManifest $fixture -IncludeDeviceResults
            switch ($target) {
                'apk' { Write-TestText -Path $fixture.Context.ApkPath -Text 'A different fake APK after evidence was recorded.' }
                'checksum' { Write-TestText -Path $fixture.Context.ChecksumPath -Text 'An altered checksum after evidence was recorded.' }
                'unit' { Write-TestText -Path $fixture.UnitFile -Text '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="different" /></testsuite>' }
                'lint-debug' { Write-TestText -Path $fixture.Context.LintDebugPath -Text '<issues format="6" />' }
                'lint-qa' { Write-TestText -Path $fixture.Context.LintQaPath -Text '<issues format="6" />' }
                'device' { Write-TestText -Path $fixture.DeviceFile -Text '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="different" /></testsuite>' }
                'build-receipt' { Write-TestText -Path $fixture.Context.BuildReceiptPath -Text '{}' }
                'device-receipt' { Write-TestText -Path $fixture.Context.DeviceReceiptPath -Text '{}' }
            }
            Assert-Throws { Get-TestPackage $fixture $manifest } "Packaging accepted changed $target evidence"
        }
    }

    foreach ($countTarget in @('UnitTests', 'DeviceTests')) {
        $summaryName = $countTarget
        Invoke-HostCheck ("package selection rejects altered manifest {0} counts" -f $summaryName) {
            $fixture = New-EvidenceFixture
            $manifest = Write-TestManifest $fixture -IncludeDeviceResults
            $metadata = [IO.File]::ReadAllText($manifest) | ConvertFrom-Json
            $metadata.$summaryName.Tests = 99
            $metadata.$summaryName.Skipped = 0
            Write-TestText -Path $manifest -Text ($metadata | ConvertTo-Json -Depth 12)
            Assert-Throws { Get-TestPackage $fixture $manifest } "Packaging accepted altered $summaryName counts"
        }
    }

    Invoke-HostCheck 'package selection rejects missing APK without falling back to prototype' {
        $fixture = New-EvidenceFixture
        $manifest = Write-TestManifest $fixture
        Write-TestText -Path (Join-Path $fixture.Case.Checkout 'output\BookCover-prototype.apk') -Text 'Unrelated fake prototype APK.'
        Remove-Item -LiteralPath $fixture.Context.ApkPath
        Assert-Throws { Get-TestPackage $fixture $manifest } 'Packaging fell back to the unrelated prototype APK'
    }
} finally {
    # Recursive cleanup is limited to this exact GUID-named child of OS temp.
    # Verify the existing resolved target rather than trusting a computed path.
    if ($script:KeepTempRoot) {
        Write-Host ("Retained host fixtures because a job was not confirmed complete: {0}" -f $script:TempRoot)
    } elseif (Test-Path -LiteralPath $script:TempRoot) {
        $resolvedRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $script:TempRoot).ProviderPath).TrimEnd('\', '/')
        $resolvedParent = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $script:TempParent).ProviderPath).TrimEnd('\', '/')
        $expectedRoot = Join-Path $resolvedParent $script:TempLeaf
        if ($script:TempLeaf -notmatch '^BookCover-task-resources-tests-[0-9a-f]{32}$' -or
                -not $resolvedRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase) -or
                -not $resolvedRoot.StartsWith($resolvedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove a test directory outside the verified OS temp location: $resolvedRoot"
        }
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}

Write-Host ("Task resource host checks: {0} passed; {1} failed." -f $script:Passed, $script:Failed)
if ($script:Failed -gt 0) { throw "$($script:Failed) task resource host check(s) failed." }
