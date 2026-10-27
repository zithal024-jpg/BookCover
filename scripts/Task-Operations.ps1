# Production orchestration; NativeRunner is a host-test callback, never a CLI option.
. (Join-Path $PSScriptRoot 'Task-Resources.ps1')

function Invoke-BookCoverNative {
    param([string]$FilePath, [string[]]$Arguments, [scriptblock]$NativeRunner)
    $ErrorActionPreference = 'Stop'
    if ($null -ne $NativeRunner) {
        $response = & $NativeRunner $FilePath $Arguments
        if ($response -is [int]) { return [pscustomobject]@{ ExitCode = $response; Output = @() } }
        if ($null -eq $response -or $null -eq $response.PSObject.Properties['ExitCode']) { throw 'Native runner did not report a completed exit code.' }
        return $response
    }
    $lines = [Collections.Generic.List[string]]::new()
    & $FilePath @Arguments | ForEach-Object { $line = [string]$_; $lines.Add($line); Write-Host $line }
    $code = $LASTEXITCODE
    if ($null -eq $code) { throw 'Native process did not provide an exit code.' }
    [pscustomobject]@{ ExitCode = [int]$code; Output = @($lines.ToArray()) }
}

function Assert-BookCoverLegacyPaths {
    param($Context, [string]$BuildRoot = '', [string]$ProjectCache = '', [string]$ApkName = '')
    foreach ($entry in @(@('BuildRoot', $BuildRoot), @('ProjectCache', $ProjectCache))) {
        if ($entry[1] -and (Get-BCFullPath $entry[1]) -ine $Context.($entry[0])) {
            throw "$($entry[0]) differs from the isolated task context. Select -TaskId/-ToolRoot instead."
        }
    }
    if ($ApkName -and $ApkName -cne $Context.ApkName) { throw 'ApkName differs from the checkout/task-specific name; use the name printed by -Plan.' }
}

function Assert-BCQaConfiguration([string]$Configuration) {
    # Keep quoted strings while removing comments, so commented-out safeguards
    # cannot authorize instrumentation. Deliberately accept only the simple,
    # literal QA configuration used by this project; unfamiliar DSL fails closed.
    $code = [regex]::Replace($Configuration, '(?s)"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\r\n]*', [Text.RegularExpressions.MatchEvaluator]{
        param($match)
        if ($match.Value.StartsWith('/')) { return ' ' }
        return $match.Value
    })
    $qaDeclarations = [regex]::Matches($code, '\bcreate\s*\(\s*"qa"\s*\)')
    $qaBlocks = [regex]::Matches($code, '\bcreate\s*\(\s*"qa"\s*\)\s*\{([^{}]*)\}')
    $safe = [regex]::Matches($code, '\btestBuildType\b').Count -eq 1 -and
        $code -match '\btestBuildType\s*=\s*"qa"\s*(?=;|}|$|\r|\n)' -and
        $qaDeclarations.Count -eq 1 -and $qaBlocks.Count -eq 1
    if ($safe) {
        $body = $qaBlocks[0].Groups[1].Value
        $safe = [regex]::Matches($body, '\bapplicationIdSuffix\b').Count -eq 1 -and
            $body -match '\bapplicationIdSuffix\s*=\s*"\.qa"\s*(?=;|}|$|\r|\n)'
    }
    if (-not $safe) { throw 'Device testing requires the isolated .qa application. Never target the primary reader.' }
}

function Set-BCExecutionEnvironment($Context, [switch]$ReuseExistingCache) {
    $ErrorActionPreference = 'Stop'
    $jdk = @(Get-ChildItem -LiteralPath (Join-Path $Context.ToolRoot 'java') -Directory -ErrorAction Stop | Sort-Object Name | Select-Object -First 1)
    if ($jdk.Count -ne 1 -or -not [IO.File]::Exists((Join-Path $jdk[0].FullName 'bin\java.exe'))) { throw 'Java was not found in the selected tool root.' }
    $sdk = Join-Path $Context.ToolRoot 'sdk'
    if (-not [IO.Directory]::Exists($sdk)) { throw 'Android SDK was not found in the selected tool root.' }
    [IO.Directory]::CreateDirectory($Context.GradleUserHome) | Out-Null
    if ($ReuseExistingCache) {
        foreach ($name in @('caches', 'wrapper')) {
            $existing = Join-Path (Join-Path $env:USERPROFILE '.gradle') $name
            $destination = Join-Path $Context.GradleUserHome $name
            if ([IO.Directory]::Exists($existing) -and -not (Test-Path -LiteralPath $destination)) {
                New-Item -ItemType Junction -Path $destination -Target $existing -ErrorAction Stop | Out-Null
            }
        }
    }
    $env:JAVA_HOME = $jdk[0].FullName; $env:ANDROID_HOME = $sdk
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $env:GRADLE_USER_HOME = $Context.GradleUserHome
    $env:GRADLE_OPTS = '-Dorg.gradle.daemon=false'
    return (Join-Path $sdk 'platform-tools\adb.exe')
}

function Invoke-BookCoverTaskOperation {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)][string]$Owner,
        [ValidateSet('Build', 'Device')][string]$Kind,
        [string]$Serial = '', [string]$PhoneReleaseEvidence = '', [string]$TestClass = '', [scriptblock]$NativeRunner)
    $ErrorActionPreference = 'Stop'
    Assert-BCContext $Context; Assert-BCText $Owner 'Owner'
    foreach ($path in @($Context.CheckoutRoot, $Context.ToolRoot, $Context.BuildRoot, $Context.ProjectCache, $Context.GradleUserHome)) {
        if ($path -match '[&|<>^%!\r\n"]') { throw 'Batch execution paths contain unsupported command-shell characters.' }
    }
    if ($TestClass -and $TestClass -notmatch '^[A-Za-z0-9_.$,#]+\z') { throw 'TestClass must contain only class/method names and comma separators.' }
    if ($Kind -ceq 'Device') {
        if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'An explicit phone serial is required before any device operation.' }
        Assert-BCText $PhoneReleaseEvidence 'Explicit previous-holder/owner PHONE release evidence'
        $configuration = [IO.File]::ReadAllText((Join-Path $Context.CheckoutRoot 'app\build.gradle.kts'))
        Assert-BCQaConfiguration $configuration
    }
    $saved = @{}
    foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'PATH', 'GRADLE_USER_HOME', 'GRADLE_OPTS', 'ANDROID_SERIAL')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    $buildLease = $null; $phoneLease = $null; $run = $null
    $nativeStarted = $false; $nativeStopped = $false; $locationPushed = $false
    try {
        $buildLease = Enter-BookCoverResource -Context $Context -Resource BUILD -Owner $Owner -Purpose "$Kind operation"
        if ($Kind -ceq 'Device') {
            $phoneLease = Enter-BookCoverResource -Context $Context -Resource PHONE -Owner $Owner -Purpose "QA test; release evidence: $PhoneReleaseEvidence" -Serial $Serial -BuildLeaseId $buildLease.LeaseId
        }
        $run = Start-BookCoverRun -Context $Context -BuildLease $buildLease -Kind $Kind -Serial $Serial
        $adb = Set-BCExecutionEnvironment -Context $Context -ReuseExistingCache:($null -eq $NativeRunner)
        Push-Location -LiteralPath $Context.CheckoutRoot; $locationPushed = $true
        if ($Kind -ceq 'Build') {
            $property = "sdk.dir=$($env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:'))"
            [IO.File]::WriteAllText((Join-Path $Context.CheckoutRoot 'local.properties'), $property, [Text.UTF8Encoding]::new($false))
            $arguments = @('assembleDebug', 'testQaUnitTest', 'lintDebug', 'lintQa', '--console=plain', '--project-cache-dir', $Context.ProjectCache, "-Pbookcover.buildRoot=$($Context.BuildRoot)")
        } else {
            if (-not [IO.File]::Exists($adb)) { throw 'adb was not found in the selected SDK.' }
            $env:ANDROID_SERIAL = $Serial
            foreach ($probe in @(@('devices'), @('-s', $Serial, 'shell', 'dumpsys', 'window', 'policy'))) {
                $null = Assert-BookCoverResource -Context $Context -Lease $phoneLease
                $nativeStarted = $true; $nativeStopped = $false
                $result = Invoke-BookCoverNative -FilePath $adb -Arguments $probe -NativeRunner $NativeRunner
                $nativeStopped = $true
                if ($result.ExitCode -ne 0) { throw 'Phone availability probe failed.' }
                $probeText = @($result.Output) -join "`n"
                if ($probe[0] -eq 'devices' -and $probeText -notmatch ('(?m)^' + [regex]::Escape($Serial) + '\s+device\s*$')) { throw 'The explicitly reserved phone is not connected and authorized.' }
                if ($probe[0] -ne 'devices' -and ($probeText -match '\bshowing=true\b' -or $probeText -notmatch '\bshowing=false\b')) { throw 'Unlock the selected phone; lock-screen state is unavailable or locked.' }
            }
            $arguments = @('connectedQaAndroidTest', '--console=plain', '--project-cache-dir', $Context.ProjectCache, "-Pbookcover.buildRoot=$($Context.BuildRoot)")
            if ($TestClass) { $arguments += "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass" }
        }
        $null = Assert-BookCoverResource -Context $Context -Lease $buildLease
        if ($null -ne $phoneLease) { $null = Assert-BookCoverResource -Context $Context -Lease $phoneLease }
        $nativeStarted = $true; $nativeStopped = $false
        $result = Invoke-BookCoverNative -FilePath (Join-Path $Context.CheckoutRoot 'gradlew.bat') -Arguments $arguments -NativeRunner $NativeRunner
        $nativeStopped = $true
        if ($result.ExitCode -ne 0) {
            $null = Complete-BookCoverRun -Context $Context -BuildLease $buildLease -Run $run -ExitCode $result.ExitCode -OperationStopped
            throw "Gradle exited $($result.ExitCode)."
        }
        if ($Kind -ceq 'Build') {
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Context.ApkPath)) | Out-Null
            Copy-Item -LiteralPath $Context.BuiltApkPath -Destination $Context.ApkPath -Force
            $hash = (Get-BCFileRecord $Context.ApkPath).Sha256
            [IO.File]::WriteAllText($Context.ChecksumPath, "$hash  $($Context.ApkName)`n", [Text.UTF8Encoding]::new($false))
        }
        $completed = Complete-BookCoverRun -Context $Context -BuildLease $buildLease -Run $run -ExitCode 0 -OperationStopped
        $evidence = $null
        if ($Kind -ceq 'Build') {
            $evidence = Write-BookCoverEvidence -Context $Context -BuildLease $buildLease -SourceSnapshotId $completed.SourceSnapshotId
        } elseif ([IO.File]::Exists($Context.BuildReceiptPath)) {
            $buildRecord = Read-BCJson $Context.BuildReceiptPath
            if ($buildRecord.Status -ceq 'Passed' -and $buildRecord.SourceSnapshotId -ceq $completed.SourceSnapshotId) {
                $evidence = Write-BookCoverEvidence -Context $Context -BuildLease $buildLease -SourceSnapshotId $completed.SourceSnapshotId -IncludeDeviceResults
            }
        }
        [pscustomobject]@{ Context = $Context; EvidenceManifest = $evidence; SourceSnapshotId = $completed.SourceSnapshotId; ReceiptPath = $completed.ReceiptPath; DeviceReceiptPath = $(if ($Kind -ceq 'Device') { $completed.ReceiptPath } else { $null }) }
    } catch {
        if ($null -ne $run -and (-not $nativeStarted -or $nativeStopped)) {
            try {
                $latest = Read-BCJson $run.ReceiptPath
                if ($latest.OperationId -ceq $run.OperationId -and $latest.Status -ceq 'Running') { $null = Complete-BookCoverRun -Context $Context -BuildLease $buildLease -Run $run -ExitCode 1 -OperationStopped }
            } catch { } # Retain the failed receipt and the original error.
        }
        throw
    } finally {
        if ($locationPushed) { Pop-Location }
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        if (-not $nativeStarted -or $nativeStopped) {
            if ($null -ne $phoneLease) { Exit-BookCoverResource -Context $Context -Lease $phoneLease -OperationStopped -ReleaseEvidence "$Kind process ended; see $($Context.DeviceReceiptPath)" }
            if ($null -ne $buildLease) { Exit-BookCoverResource -Context $Context -Lease $buildLease -OperationStopped -ReleaseEvidence "$Kind operation ended; see task receipt" }
        } else { Write-Warning 'Native process completion is uncertain. Reservations are retained; the coordinator must investigate. No process was killed.' }
    }
}

function Invoke-BookCoverBuild {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)][string]$Owner, [scriptblock]$NativeRunner)
    Invoke-BookCoverTaskOperation -Context $Context -Owner $Owner -Kind Build -NativeRunner $NativeRunner
}

function Invoke-BookCoverDeviceTests {
    param([Parameter(Mandatory = $true)]$Context, [Parameter(Mandatory = $true)][string]$Owner,
        [string]$Serial = '', [string]$PhoneReleaseEvidence = '', [string]$TestClass = '', [scriptblock]$NativeRunner)
    Invoke-BookCoverTaskOperation -Context $Context -Owner $Owner -Kind Device -Serial $Serial -PhoneReleaseEvidence $PhoneReleaseEvidence -TestClass $TestClass -NativeRunner $NativeRunner
}
