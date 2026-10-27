# Standalone BC-25 functions. Dot-sourcing this file never reserves resources or runs tools.

function Get-BCFullPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'An explicit filesystem path is required.' }
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -ne [IO.Path]::GetPathRoot($full)) { $full = $full.TrimEnd([char[]]'\/') }
    return $full
}

function Assert-BCText([string]$Value, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Label must be explicit and nonempty." }
}

function Get-BookCoverTaskContext {
    param(
        [Parameter(Mandatory = $true)][string]$CheckoutRoot,
        [Parameter(Mandatory = $true)][string]$TaskId,
        [string]$ToolRoot = (Join-Path $env:LOCALAPPDATA 'BookCoverBuild'),
        [string]$StateRoot = (Join-Path $env:USERPROFILE '.bookcover-coordination')
    )
    $ErrorActionPreference = 'Stop'
    if ($TaskId -cnotmatch '^(?=.{1,40}\z)[a-z][a-z0-9]*(?:-[a-z0-9]+)*\z') { throw 'TaskId must be a lowercase slug, up to 40 characters.' }
    $checkout = (Get-BCFullPath $CheckoutRoot).ToLowerInvariant()
    if (-not [IO.Directory]::Exists($checkout)) { throw "Checkout does not exist: $checkout" }
    $tools = (Get-BCFullPath $ToolRoot).ToLowerInvariant()
    $state = (Get-BCFullPath $StateRoot).ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($checkout.Replace('\', '/').ToLowerInvariant())
        $identity = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant().Substring(0, 16)
    } finally { $sha.Dispose() }
    $key = "$TaskId-$identity"
    $taskRoot = Join-Path (Join-Path $tools 'tasks') $key
    $buildRoot = Join-Path $taskRoot 'project-output'
    $appRoot = Join-Path $buildRoot 'app'
    $apkName = "BookCover-$key.apk"
    [pscustomobject]@{
        SchemaVersion = 1; CheckoutRoot = $checkout; TaskId = $TaskId; TaskKey = $key
        ToolRoot = $tools; StateRoot = $state; TaskRoot = $taskRoot; BuildRoot = $buildRoot
        ProjectCache = (Join-Path $taskRoot 'project-cache')
        GradleUserHome = (Join-Path $taskRoot 'gradle-user-home')
        ApkName = $apkName; ApkPath = (Join-Path (Join-Path $checkout 'output') $apkName)
        ChecksumPath = (Join-Path (Join-Path $checkout 'output') ([IO.Path]::ChangeExtension($apkName, 'sha256')))
        BuiltApkPath = (Join-Path $appRoot 'outputs\apk\debug\app-debug.apk')
        UnitResultsPath = (Join-Path $appRoot 'test-results\testQaUnitTest')
        LintDebugPath = (Join-Path $appRoot 'reports\lint-results-debug.xml')
        LintQaPath = (Join-Path $appRoot 'reports\lint-results-qa.xml')
        DeviceResultsPath = (Join-Path $appRoot 'outputs\androidTest-results\connected\qa')
        BuildReceiptPath = (Join-Path $taskRoot 'receipts\build.json')
        DeviceReceiptPath = (Join-Path $taskRoot 'receipts\device.json')
    }
}

function Assert-BCContext($Context) {
    $ErrorActionPreference = 'Stop'
    if ($null -eq $Context) { throw 'A task context is required.' }
    $expected = Get-BookCoverTaskContext -CheckoutRoot $Context.CheckoutRoot -TaskId $Context.TaskId -ToolRoot $Context.ToolRoot -StateRoot $Context.StateRoot
    foreach ($property in $expected.PSObject.Properties) {
        $actual = $Context.PSObject.Properties[$property.Name]
        if ($null -eq $actual -or [string]$actual.Value -cne [string]$property.Value) {
            throw "Task context was changed or is incomplete: $($property.Name)"
        }
    }
}

function Read-BCJson([string]$Path) {
    $ErrorActionPreference = 'Stop'
    if (-not [IO.File]::Exists($Path)) { throw "Required metadata is missing: $Path" }
    if ((Get-Item -LiteralPath $Path).Length -gt 4MB) { throw "Metadata is unexpectedly large: $Path" }
    return ([IO.File]::ReadAllText($Path) | ConvertFrom-Json -ErrorAction Stop)
}

function Write-BCJsonAtomic([string]$Path, $Value, [switch]$NewOnly) {
    $ErrorActionPreference = 'Stop'
    $temporary = Join-Path ([IO.Path]::GetDirectoryName($Path)) ('.state-' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
        if ($NewOnly -or -not [IO.File]::Exists($Path)) { [IO.File]::Move($temporary, $Path) }
        # Windows PowerShell 5 marshals plain $null as an empty backup filename.
        else { [IO.File]::Replace($temporary, $Path, [NullString]::Value) }
    } finally {
        if ([IO.File]::Exists($temporary)) { Remove-Item -LiteralPath $temporary -ErrorAction Stop }
    }
}

function Invoke-BCState {
    param([string]$StateRoot, [scriptblock]$Action, [switch]$Initialize, [switch]$WriteBack)
    $ErrorActionPreference = 'Stop'
    $root = Get-BCFullPath $StateRoot
    if ($Initialize) { [IO.Directory]::CreateDirectory($root) | Out-Null }
    if (-not [IO.Directory]::Exists($root)) { throw 'Resource state is uninitialized; ownership is unknown.' }
    $guardPath = Join-Path $root 'state.guard'
    $statePath = Join-Path $root 'resources.json'
    $guard = $null
    try {
        try { $guard = [IO.File]::Open($guardPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None) }
        catch { throw 'Resource transaction is busy or its guard is stale. Do not remove or steal it; coordinator investigation is required.' }
        if ($Initialize) {
            if ([IO.File]::Exists($statePath)) { throw 'Resource state already exists; initialization cannot overwrite it.' }
            $newState = & $Action
            Write-BCJsonAtomic -Path $statePath -Value $newState -NewOnly
        } else {
            $state = Read-BCJson $statePath
            if ($null -eq $state -or $state.schemaVersion -ne 1 -or
                $state.resources -isnot [pscustomobject] -or $state.events -isnot [array] -or
                [string]::IsNullOrWhiteSpace($state.initialReleaseEvidence) -or
                [string]::IsNullOrWhiteSpace($state.initializedBy)) { throw 'Resource state is malformed; ownership is unknown.' }
            # Callbacks inherit this scope: never shadow their caller's $Lease argument.
            foreach ($entry in $state.resources.PSObject.Properties) {
                $persistedLease = $entry.Value
                $expectedKey = if ($persistedLease.Resource -ceq 'BUILD') { 'BUILD' } elseif ($persistedLease.Resource -ceq 'PHONE' -and $persistedLease.Serial -match '^[A-Za-z0-9_.:-]+$') { "PHONE:$($persistedLease.Serial)" } else { '' }
                $leaseGuid = [guid]::Empty
                if ($entry.Name -cne $expectedKey -or -not [guid]::TryParse([string]$persistedLease.LeaseId, [ref]$leaseGuid) -or
                    [string]::IsNullOrWhiteSpace($persistedLease.Owner) -or [string]::IsNullOrWhiteSpace($persistedLease.CheckoutRoot) -or
                    [string]::IsNullOrWhiteSpace($persistedLease.TaskKey) -or [string]::IsNullOrWhiteSpace($persistedLease.Purpose)) {
                    throw 'Resource lease is malformed; ownership is unknown.'
                }
                if ($persistedLease.Resource -ceq 'BUILD') {
                    if ($persistedLease.Serial -or $persistedLease.BuildLeaseId) { throw 'BUILD contains invalid phone/parent fields.' }
                } else {
                    $parentBuild = $state.resources.PSObject.Properties['BUILD']
                    if ($null -eq $parentBuild -or $parentBuild.Value.LeaseId -cne $persistedLease.BuildLeaseId -or
                        $parentBuild.Value.Owner -cne $persistedLease.Owner -or
                        $parentBuild.Value.CheckoutRoot -cne $persistedLease.CheckoutRoot -or
                        $parentBuild.Value.TaskKey -cne $persistedLease.TaskKey) { throw 'Orphaned PHONE reservation; ownership is uncertain.' }
                }
            }
            $result = & $Action $state
            if ($WriteBack) { Write-BCJsonAtomic -Path $statePath -Value $state }
            return $result
        }
    } finally {
        # An unsuccessful CreateNew never owns the other holder's guard.
        if ($null -ne $guard) {
            $guard.Dispose()
            Remove-Item -LiteralPath $guardPath -ErrorAction Stop
        }
    }
}

function Initialize-BookCoverResources {
    param([Parameter(Mandatory = $true)][string]$StateRoot,
        [Parameter(Mandatory = $true)][string]$Coordinator,
        [Parameter(Mandatory = $true)][string]$PreviousReleaseEvidence)
    $ErrorActionPreference = 'Stop'
    Assert-BCText $Coordinator 'Coordinator'
    Assert-BCText $PreviousReleaseEvidence 'Previous-holder release evidence'
    Invoke-BCState -StateRoot $StateRoot -Initialize -Action {
        [pscustomobject]@{
            schemaVersion = 1; initializedBy = $Coordinator; initializedUtc = [DateTime]::UtcNow.ToString('o')
            initialReleaseEvidence = $PreviousReleaseEvidence; resources = [pscustomobject]@{}; events = @()
        }
    }
}

function Get-BCLeaseKey($Lease) {
    if ($Lease.Resource -ceq 'BUILD') { return 'BUILD' }
    if ($Lease.Resource -ceq 'PHONE' -and $Lease.Serial -match '^[A-Za-z0-9_.:-]+$') { return "PHONE:$($Lease.Serial)" }
    throw 'Invalid resource or phone serial.'
}

function Assert-BCLease($State, $Context, $Lease) {
    $key = Get-BCLeaseKey $Lease
    $property = $State.resources.PSObject.Properties[$key]
    if ($null -eq $property) { throw 'This resource is not reserved by the supplied lease.' }
    $current = $property.Value
    foreach ($field in @('LeaseId', 'Owner', 'CheckoutRoot', 'TaskKey', 'Resource', 'Serial', 'BuildLeaseId')) {
        if ([string]$current.$field -cne [string]$Lease.$field) { throw "Resource ownership does not match: $field" }
    }
    if ($current.CheckoutRoot -cne $Context.CheckoutRoot -or $current.TaskKey -cne $Context.TaskKey) { throw 'Lease belongs to another checkout or task.' }
    if ($current.Resource -eq 'PHONE') {
        $build = $State.resources.PSObject.Properties['BUILD']
        if ($null -eq $build -or $build.Value.LeaseId -cne $current.BuildLeaseId -or
            $build.Value.Owner -cne $current.Owner -or $build.Value.TaskKey -cne $current.TaskKey -or
            $build.Value.CheckoutRoot -cne $current.CheckoutRoot) { throw 'PHONE no longer has its matching BUILD reservation.' }
    }
    return $current
}

function Enter-BookCoverResource {
    param([Parameter(Mandatory = $true)]$Context,
        [Parameter(Mandatory = $true)][ValidateSet('BUILD', 'PHONE')][string]$Resource,
        [Parameter(Mandatory = $true)][string]$Owner,
        [Parameter(Mandatory = $true)][string]$Purpose,
        [string]$Serial = '', [string]$BuildLeaseId = '')
    $ErrorActionPreference = 'Stop'
    Assert-BCContext $Context
    Assert-BCText $Owner 'Owner'
    Assert-BCText $Purpose 'Purpose'
    $Resource = $Resource.ToUpperInvariant()
    if ($Resource -eq 'PHONE' -and $Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'PHONE requires an explicit valid serial.' }
    if ($Resource -eq 'BUILD' -and ($Serial -or $BuildLeaseId)) { throw 'BUILD does not accept phone or parent-lease arguments.' }
    Invoke-BCState -StateRoot $Context.StateRoot -WriteBack -Action {
        $key = if ($Resource -eq 'BUILD') { 'BUILD' } else { "PHONE:$Serial" }
        if ($null -ne $state.resources.PSObject.Properties[$key]) { throw "$key is already reserved; explicit previous-holder release is required." }
        if ($Resource -eq 'PHONE') {
            $build = $state.resources.PSObject.Properties['BUILD']
            if ($null -eq $build -or $build.Value.LeaseId -cne $BuildLeaseId -or
                $build.Value.Owner -cne $Owner -or $build.Value.CheckoutRoot -cne $Context.CheckoutRoot -or
                $build.Value.TaskKey -cne $Context.TaskKey) { throw 'Acquire matching BUILD before PHONE.' }
        }
        $lease = [pscustomobject]@{
            LeaseId = [guid]::NewGuid().ToString('N'); Owner = $Owner; Resource = $Resource; Serial = $Serial
            CheckoutRoot = $Context.CheckoutRoot; TaskKey = $Context.TaskKey; BuildLeaseId = $BuildLeaseId
            Purpose = $Purpose; AcquiredUtc = [DateTime]::UtcNow.ToString('o')
        }
        $state.resources | Add-Member -MemberType NoteProperty -Name $key -Value $lease
        return $lease
    }
}

function Assert-BookCoverResource {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)]$Lease)
    $ErrorActionPreference = 'Stop'
    Assert-BCContext $Context
    Invoke-BCState -StateRoot $Context.StateRoot -Action { Assert-BCLease $state $Context $Lease }
}

function Exit-BookCoverResource {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)]$Lease,
        [Parameter(Mandatory = $true)][string]$ReleaseEvidence, [switch]$OperationStopped)
    $ErrorActionPreference = 'Stop'
    Assert-BCContext $Context
    Assert-BCText $ReleaseEvidence 'Release evidence'
    if (-not $OperationStopped) { throw 'Confirm the operation ended before releasing its resource.' }
    Invoke-BCState -StateRoot $Context.StateRoot -WriteBack -Action {
        $current = Assert-BCLease $state $Context $Lease
        if ($current.Resource -eq 'BUILD') {
            foreach ($entry in $state.resources.PSObject.Properties) {
                if ($entry.Value.Resource -eq 'PHONE' -and $entry.Value.BuildLeaseId -ceq $current.LeaseId) { throw 'Release PHONE before BUILD.' }
            }
        }
        $state.events = @($state.events) + [pscustomobject]@{
            LeaseId = $current.LeaseId; Resource = $current.Resource; Serial = $current.Serial; Owner = $current.Owner
            CheckoutRoot = $current.CheckoutRoot; TaskKey = $current.TaskKey
            ReleasedUtc = [DateTime]::UtcNow.ToString('o'); Evidence = $ReleaseEvidence
        }
        $state.resources.PSObject.Properties.Remove((Get-BCLeaseKey $current))
    }
}

function Get-BCFileRecord([string]$Path) {
    $ErrorActionPreference = 'Stop'
    $full = Get-BCFullPath $Path
    if (-not [IO.File]::Exists($full)) { throw "Required evidence file is missing: $full" }
    # .NET works in a Windows PowerShell child even when an inherited PS7
    # module path prevents Get-FileHash from being auto-loaded.
    $stream = [IO.File]::OpenRead($full)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $stream.Length
        $digest = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
    } finally { $stream.Dispose(); $sha.Dispose() }
    [pscustomobject]@{ Path = $full; Sha256 = $digest; Bytes = $bytes }
}

function Read-BCXml([string]$Path) {
    $ErrorActionPreference = 'Stop'
    if (-not [IO.File]::Exists($Path)) { throw "Required XML is missing: $Path" }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 16MB
    $reader = [Xml.XmlReader]::Create($Path, $settings)
    try {
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
        return ,$document
    } finally { $reader.Dispose() }
}

function Get-BCJUnitSummary([string]$Root) {
    $ErrorActionPreference = 'Stop'
    if (-not [IO.Directory]::Exists($Root)) { throw "Test evidence directory is missing: $Root" }
    $files = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.xml' | Sort-Object FullName)
    if ($files.Count -eq 0) { throw "No test XML found: $Root" }
    $counts = @{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
    foreach ($file in $files) {
        $document = Read-BCXml $file.FullName
        $rootNode = $document.DocumentElement
        if ($rootNode.LocalName -notin @('testsuite', 'testsuites')) { throw "Unrecognized JUnit root: $($file.FullName)" }
        $fileCounts = @{}
        foreach ($name in @('Tests', 'Failures', 'Errors', 'Skipped')) {
            $number = 0
            if (-not [int]::TryParse($rootNode.GetAttribute($name.ToLowerInvariant()), [ref]$number) -or $number -lt 0) { throw "Missing or invalid JUnit counter $name" }
            $counts[$name] += $number
            $fileCounts[$name] = $number
        }
        if ($document.SelectNodes('//*[local-name()="failure" or local-name()="error"]').Count -gt 0) { throw 'JUnit contains failure/error elements.' }
        if ($document.SelectNodes('//*[local-name()="testcase"]').Count -ne $fileCounts.Tests -or
            $document.SelectNodes('//*[local-name()="testcase"]/*[local-name()="skipped"]').Count -ne $fileCounts.Skipped) {
            throw 'JUnit counters do not match its test cases and skipped results.'
        }
    }
    if ($counts.Failures -ne 0 -or $counts.Errors -ne 0 -or $counts.Tests -le $counts.Skipped) { throw 'Test evidence has failures/errors or no executed tests.' }
    [pscustomobject]@{ Tests = $counts.Tests; Failures = $counts.Failures; Errors = $counts.Errors; Skipped = $counts.Skipped; Files = @($files.FullName) }
}

function Assert-BCLint([string]$Path) {
    $document = Read-BCXml $Path
    if ($document.DocumentElement.LocalName -ne 'issues') { throw 'Unrecognized lint XML root.' }
    foreach ($issue in $document.SelectNodes('//*[local-name()="issue"]')) {
        if ($issue.GetAttribute('severity') -notin @('Warning', 'Information', 'Informational', 'Ignore')) { throw 'Lint has errors or an unknown severity.' }
    }
}

function Get-BCValidatedArtifacts($Context) {
    $ErrorActionPreference = 'Stop'
    $apk = Get-BCFileRecord $Context.ApkPath
    $builtApk = Get-BCFileRecord $Context.BuiltApkPath
    if ($apk.Sha256 -cne $builtApk.Sha256) { throw 'Selected APK does not match this task build output.' }
    $checksum = Get-BCFileRecord $Context.ChecksumPath
    $checksumText = [IO.File]::ReadAllText($checksum.Path).Trim()
    if ($checksumText -notmatch '^([a-fA-F0-9]{64})\s+\*?([^\r\n]+)$' -or
        $Matches[1] -ine $apk.Sha256 -or $Matches[2] -cne $Context.ApkName) { throw 'APK checksum file has the wrong digest or filename.' }
    [pscustomobject]@{ Apk = $apk; BuiltApk = $builtApk; Checksum = $checksum }
}

function Assert-BCSummary($Recorded, $Actual) {
    if ($null -eq $Recorded -or $null -eq $Actual) { throw 'Missing recorded test counts.' }
    foreach ($name in @('Tests', 'Failures', 'Errors', 'Skipped')) {
        if ($null -eq $Recorded.PSObject.Properties[$name] -or [string]$Recorded.$name -cne [string]$Actual.$name) {
            throw "Recorded test count changed: $name"
        }
    }
}

function Get-BookCoverSourceSnapshot {
    param([Parameter(Mandatory = $true)]$Context)
    $ErrorActionPreference = 'Stop'
    Assert-BCContext $Context
    $excluded = @('build', '.gradle', '.kotlin', '__pycache__', 'node_modules', 'output', '.git', 'captures')
    $paths = [Collections.Generic.List[string]]::new()
    $directories = [Collections.Generic.Queue[string]]::new()
    foreach ($name in @('app', 'gradle', 'scripts')) {
        $directory = Join-Path $Context.CheckoutRoot $name
        if ([IO.Directory]::Exists($directory)) { $directories.Enqueue($directory) }
    }
    while ($directories.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $directories.Dequeue() -Force) {
            if ($item.Name -in $excluded) { continue }
            if ($item.PSObject.Properties['LinkType'] -and $item.LinkType) { throw 'Source input links require an explicit reviewed snapshot.' }
            if ($item.PSIsContainer) { $directories.Enqueue($item.FullName); continue }
            if ($item.Name -eq 'local.properties' -or $item.Extension -eq '.pyc') { continue }
            if ($item.Extension -in @('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key') -or $item.Name -like '.env*') {
                throw 'A private configuration/key file is inside the source input tree.'
            }
            $relative = $item.FullName.Substring($Context.CheckoutRoot.Length + 1).Replace('\', '/')
            if ($item.Extension -eq '.epub' -and $relative -notin @('app/src/main/assets/samples/lantern-keeper.epub', 'app/src/main/assets/samples/river-maps.epub')) {
                throw 'Only the two generated sample EPUBs may be build/package source inputs.'
            }
            $paths.Add($relative)
        }
    }
    foreach ($name in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat')) {
        if ([IO.File]::Exists((Join-Path $Context.CheckoutRoot $name))) { $paths.Add($name) }
    }
    if ($paths.Count -eq 0) { throw 'No build source inputs were found.' }
    $ordered = $paths.ToArray()
    [Array]::Sort($ordered, [StringComparer]::Ordinal)
    $records = @()
    $description = [Text.StringBuilder]::new()
    foreach ($relative in $ordered) {
        $record = Get-BCFileRecord (Join-Path $Context.CheckoutRoot $relative)
        $records += [pscustomobject]@{ Path = $relative; Sha256 = $record.Sha256; Bytes = $record.Bytes }
        $null = $description.Append($relative).Append("`t").Append([string]$record.Bytes).Append("`t").Append($record.Sha256).Append("`n")
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($description.ToString())))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    [pscustomobject]@{ SourceSnapshotId = $digest; Files = $records; CheckoutRoot = $Context.CheckoutRoot }
}

function Get-BCRunFiles($Context, [string]$Kind) {
    if ($Kind -ceq 'Build') {
        $null = Get-BCValidatedArtifacts $Context
        $units = Get-BCJUnitSummary $Context.UnitResultsPath
        Assert-BCLint $Context.LintDebugPath
        Assert-BCLint $Context.LintQaPath
        return @($Context.ApkPath, $Context.BuiltApkPath, $Context.ChecksumPath, $Context.LintDebugPath, $Context.LintQaPath) + @($units.Files)
    }
    if ($Kind -ceq 'Device') { return @((Get-BCJUnitSummary $Context.DeviceResultsPath).Files) }
    throw 'Unknown operation kind.'
}

function Start-BookCoverRun {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)]$BuildLease,
        [Parameter(Mandatory = $true)][ValidateSet('Build', 'Device')][string]$Kind, [string]$Serial = '')
    $ErrorActionPreference = 'Stop'
    $null = Assert-BookCoverResource -Context $Context -Lease $BuildLease
    if ($BuildLease.Resource -cne 'BUILD') { throw 'An operation requires its BUILD lease.' }
    $Kind = if ($Kind -ieq 'Build') { 'Build' } else { 'Device' }
    if ($Kind -ceq 'Device') {
        Invoke-BCState -StateRoot $Context.StateRoot -Action {
            $phoneEntry = $state.resources.PSObject.Properties["PHONE:$Serial"]
            if ($null -eq $phoneEntry -or $phoneEntry.Value.BuildLeaseId -cne $BuildLease.LeaseId -or
                $phoneEntry.Value.Owner -cne $BuildLease.Owner -or $phoneEntry.Value.TaskKey -cne $Context.TaskKey) {
                throw 'A device operation requires its matching PHONE reservation.'
            }
        }
    }
    $snapshot = Get-BookCoverSourceSnapshot -Context $Context
    $path = if ($Kind -ceq 'Build') { $Context.BuildReceiptPath } else { $Context.DeviceReceiptPath }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    $run = [pscustomobject]@{
        SchemaVersion = 2; Context = $Context; OperationId = [guid]::NewGuid().ToString('N'); Kind = $Kind
        BuildLeaseId = $BuildLease.LeaseId; Serial = $Serial; Status = 'Running'; ExitCode = $null
        StartedUtc = [DateTime]::UtcNow.ToString('o'); CompletedUtc = $null; Failure = $null
        SourceSnapshotId = $snapshot.SourceSnapshotId; SourceSnapshot = $snapshot; SourceAfterId = $null
        Files = @(); ReceiptPath = $path
    }
    Write-BCJsonAtomic -Path $path -Value $run
    return $run
}

function Complete-BookCoverRun {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)]$BuildLease,
        [Parameter(Mandatory = $true)]$Run, [Parameter(Mandatory = $true)][int]$ExitCode,
        [switch]$OperationStopped)
    $ErrorActionPreference = 'Stop'
    if (-not $OperationStopped) { throw 'An uncertain running process cannot produce a completed receipt.' }
    $null = Assert-BookCoverResource -Context $Context -Lease $BuildLease
    $expectedPath = if ($Run.Kind -ceq 'Build') { $Context.BuildReceiptPath } elseif ($Run.Kind -ceq 'Device') { $Context.DeviceReceiptPath } else { throw 'Unknown operation kind.' }
    $receipt = Read-BCJson $expectedPath
    if ($receipt.OperationId -cne $Run.OperationId -or $receipt.BuildLeaseId -cne $BuildLease.LeaseId -or $receipt.Status -cne 'Running') {
        throw 'Operation receipt does not match this running operation.'
    }
    $receipt.ExitCode = $ExitCode
    $receipt.CompletedUtc = [DateTime]::UtcNow.ToString('o')
    $receipt.Status = 'Failed'
    try {
        if ($ExitCode -ne 0) { throw "Native operation exited $ExitCode." }
        $after = Get-BookCoverSourceSnapshot -Context $Context
        $receipt.SourceAfterId = $after.SourceSnapshotId
        if ($after.SourceSnapshotId -cne $receipt.SourceSnapshotId) { throw 'Source changed during the operation; outputs are not verified.' }
        $receipt.Files = @(Get-BCRunFiles $Context $receipt.Kind | ForEach-Object { Get-BCFileRecord $_ })
        $receipt.Status = 'Passed'
    } catch { $receipt.Failure = $_.Exception.Message }
    Write-BCJsonAtomic -Path $expectedPath -Value $receipt
    if ($receipt.Status -cne 'Passed') { throw $receipt.Failure }
    return $receipt
}

function Get-BCSuccessfulRun($Context, [string]$Kind, $Snapshot) {
    $path = if ($Kind -ceq 'Build') { $Context.BuildReceiptPath } else { $Context.DeviceReceiptPath }
    $receipt = Read-BCJson $path
    if ($receipt.SchemaVersion -ne 2 -or $receipt.Kind -cne $Kind -or $receipt.Status -cne 'Passed' -or
        $receipt.ExitCode -ne 0 -or -not $receipt.CompletedUtc -or
        $receipt.SourceSnapshotId -cne $Snapshot.SourceSnapshotId -or $receipt.SourceAfterId -cne $Snapshot.SourceSnapshotId) {
        throw 'No successful operation receipt matches the current source snapshot.'
    }
    Assert-BCContext $receipt.Context
    if ($receipt.Context.TaskKey -cne $Context.TaskKey -or $receipt.Context.CheckoutRoot -cne $Context.CheckoutRoot -or
        $receipt.Context.ToolRoot -cne $Context.ToolRoot -or $receipt.Context.StateRoot -cne $Context.StateRoot) { throw 'Receipt belongs to another task context.' }
    $expectedFiles = @(Get-BCRunFiles $Context $Kind)
    Assert-BCFileSet $receipt.Files $expectedFiles
    return $receipt
}

function Assert-BCFileSet($Records, [string[]]$Paths) {
    if (@($Records).Count -ne $Paths.Count) { throw 'Evidence file set has changed.' }
    foreach ($path in $Paths) {
        $entries = @($Records | Where-Object { $_.Path -ceq $path })
        $actualFile = Get-BCFileRecord $path
        if ($entries.Count -ne 1 -or $actualFile.Sha256 -cne $entries[0].Sha256 -or $actualFile.Bytes -ne $entries[0].Bytes) {
            throw "Evidence file changed or is missing: $path"
        }
    }
}

function Write-BookCoverEvidence {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)]$BuildLease,
        [Parameter(Mandatory = $true)][string]$SourceSnapshotId, [switch]$IncludeDeviceResults)
    $ErrorActionPreference = 'Stop'
    if ($SourceSnapshotId -notmatch '^[a-fA-F0-9]{64}$') { throw 'Supply the automatic source snapshot digest.' }
    $null = Assert-BookCoverResource -Context $Context -Lease $BuildLease
    if ($BuildLease.Resource -cne 'BUILD') { throw 'Evidence requires a BUILD lease.' }
    $snapshot = Get-BookCoverSourceSnapshot -Context $Context
    if ($snapshot.SourceSnapshotId -ine $SourceSnapshotId) { throw 'Requested source snapshot differs from the current inputs.' }
    $null = Get-BCSuccessfulRun $Context 'Build' $snapshot
    $null = Get-BCValidatedArtifacts $Context
    $units = Get-BCJUnitSummary $Context.UnitResultsPath
    Assert-BCLint $Context.LintDebugPath
    Assert-BCLint $Context.LintQaPath
    $devices = $null
    if ($IncludeDeviceResults) { $null = Get-BCSuccessfulRun $Context 'Device' $snapshot; $devices = Get-BCJUnitSummary $Context.DeviceResultsPath }
    $paths = @(Get-BCRunFiles $Context 'Build') + @($Context.BuildReceiptPath)
    if ($null -ne $devices) { $paths += @(Get-BCRunFiles $Context 'Device') + @($Context.DeviceReceiptPath) }
    $records = @($paths | ForEach-Object { Get-BCFileRecord $_ })
    $manifest = [pscustomobject]@{
        SchemaVersion = 2; Context = $Context; SourceSnapshotId = $snapshot.SourceSnapshotId; SourceSnapshot = $snapshot
        SourceIdentity = 'Build input bytes captured before and after a completed operation'
        BuildLeaseId = $BuildLease.LeaseId; CreatedUtc = [DateTime]::UtcNow.ToString('o')
        UnitTests = $units; DeviceTests = $devices; Files = $records
    }
    $null = Assert-BookCoverResource -Context $Context -Lease $BuildLease
    $evidenceRoot = Join-Path $Context.TaskRoot 'evidence'
    [IO.Directory]::CreateDirectory($evidenceRoot) | Out-Null
    $path = Join-Path $evidenceRoot ($SourceSnapshotId.ToLowerInvariant() + '-' + [guid]::NewGuid().ToString('N') + '.json')
    Write-BCJsonAtomic -Path $path -Value $manifest -NewOnly
    return $path
}

function Get-BookCoverPackageInputs {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$SourceSnapshotId)
    $ErrorActionPreference = 'Stop'
    Assert-BCContext $Context
    $manifest = Read-BCJson (Get-BCFullPath $ManifestPath)
    if ($manifest.SchemaVersion -ne 2 -or $manifest.SourceSnapshotId -ine $SourceSnapshotId) { throw 'Evidence schema or source snapshot does not match; caller-asserted legacy evidence is not accepted.' }
    Assert-BCContext $manifest.Context
    foreach ($field in @('TaskKey', 'CheckoutRoot', 'ToolRoot', 'StateRoot')) {
        if ($manifest.Context.$field -cne $Context.$field) { throw 'Evidence belongs to a different checkout or task.' }
    }
    $snapshot = Get-BookCoverSourceSnapshot -Context $Context
    if ($snapshot.SourceSnapshotId -cne $manifest.SourceSnapshotId) { throw 'Current source no longer matches the verified build.' }
    $null = Get-BCSuccessfulRun $Context 'Build' $snapshot
    $null = Get-BCValidatedArtifacts $Context
    $units = Get-BCJUnitSummary $Context.UnitResultsPath
    Assert-BCSummary $manifest.UnitTests $units
    Assert-BCLint $Context.LintDebugPath
    Assert-BCLint $Context.LintQaPath
    $deviceReports = @()
    $devices = $null
    if ($null -ne $manifest.DeviceTests) {
        $null = Get-BCSuccessfulRun $Context 'Device' $snapshot
        $devices = Get-BCJUnitSummary $Context.DeviceResultsPath
        Assert-BCSummary $manifest.DeviceTests $devices
        $deviceReports = @($devices.Files)
    }
    $expectedPaths = @(Get-BCRunFiles $Context 'Build') + @($Context.BuildReceiptPath) + $deviceReports
    if ($null -ne $devices) { $expectedPaths += $Context.DeviceReceiptPath }
    Assert-BCFileSet $manifest.Files $expectedPaths
    [pscustomobject]@{
        ManifestPath = (Get-BCFullPath $ManifestPath); SourceSnapshotId = $manifest.SourceSnapshotId
        ApkPath = $Context.ApkPath; ChecksumPath = $Context.ChecksumPath; BuiltApkPath = $Context.BuiltApkPath
        UnitReports = @($units.Files); LintReports = @($Context.LintDebugPath, $Context.LintQaPath)
        DeviceReports = $deviceReports; UnitTests = $units; DeviceTests = $devices
        SourceIdentity = $manifest.SourceIdentity
        Context = $Context; BuildRoot = $Context.BuildRoot; SourceSnapshot = $snapshot
        FileRecords = @($manifest.Files)
        ReceiptPaths = @($Context.BuildReceiptPath) + $(if ($null -ne $devices) { @($Context.DeviceReceiptPath) } else { @() })
    }
}
