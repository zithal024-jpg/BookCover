<#
Host-only integration checks for task-aware build/device wrappers. Every checkout,
tool, artifact and resource-state path is a disposable fixture under OS temp.
NativeRunner intercepts every tool call: no Gradle, Java, adb, SDK or phone runs.
#>
[CmdletBinding()]
param([string]$OperationsPath = '')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
if ([string]::IsNullOrWhiteSpace($OperationsPath)) {
    $OperationsPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'Task-Operations.ps1'
}
if (-not (Test-Path -LiteralPath $OperationsPath -PathType Leaf)) { throw "Missing task operations helper: $OperationsPath" }
. $OperationsPath

$script:IntegrationPassed = 0
$script:IntegrationFailed = 0
$script:IntegrationCaseNumber = 0
$script:IntegrationUtf8 = New-Object System.Text.UTF8Encoding -ArgumentList $false
$script:IntegrationTempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$script:IntegrationTempLeaf = 'BookCover-task-integration-tests-' + [guid]::NewGuid().ToString('N')
$script:IntegrationTempRoot = Join-Path $script:IntegrationTempParent $script:IntegrationTempLeaf
$script:IntegrationScriptsRoot = Split-Path -Parent (Resolve-Path -LiteralPath $OperationsPath).ProviderPath
New-Item -ItemType Directory -Path $script:IntegrationTempRoot | Out-Null

function Assert-IntegrationTrue([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-IntegrationEqual($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw ("{0}; expected '{1}', got '{2}'." -f $Message, $Expected, $Actual) }
}
function Assert-IntegrationThrows([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw $Message }
}
function Invoke-IntegrationCheck([string]$Name, [scriptblock]$Body) {
    try {
        & $Body | Out-Null
        $script:IntegrationPassed++
        Write-Host ("PASS {0}" -f $Name)
    } catch {
        $script:IntegrationFailed++
        Write-Host ("FAIL {0}: {1}" -f $Name, $_.Exception.Message)
    }
}
function Write-IntegrationText([string]$Path, [string]$Text) {
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent -PathType Container)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [IO.File]::WriteAllText($Path, $Text, $script:IntegrationUtf8)
}
function New-IntegrationFixture([switch]$WithoutState, [string]$CheckoutLeaf = 'checkout', [string]$ToolLeaf = 'tools') {
    $script:IntegrationCaseNumber++
    $root = Join-Path $script:IntegrationTempRoot ('case-{0:d3}' -f $script:IntegrationCaseNumber)
    $checkout = Join-Path $root $CheckoutLeaf
    $toolRoot = Join-Path $root $ToolLeaf
    $stateRoot = Join-Path $root 'state'
    $callsRoot = Join-Path $root 'calls'
    foreach ($directory in @($checkout, $toolRoot, $callsRoot, (Join-Path $checkout 'scripts'))) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    foreach ($name in @('Build.ps1', 'Test-Device.ps1', 'Task-Resources.ps1', 'Task-Operations.ps1')) {
        Copy-Item -LiteralPath (Join-Path $script:IntegrationScriptsRoot $name) -Destination (Join-Path $checkout "scripts\$name")
    }
    Write-IntegrationText (Join-Path $checkout 'gradlew.bat') '@echo off
rem Host fixture only. NativeRunner intercepts this path; this file must never execute.
exit /b 99
'
    Write-IntegrationText (Join-Path $checkout 'build.gradle.kts') '// Host fixture: no Android build is performed.'
    Write-IntegrationText (Join-Path $checkout 'gradle.properties') 'org.gradle.daemon=false'
    Write-IntegrationText (Join-Path $checkout 'settings.gradle.kts') 'rootProject.name = "HostFixture"'
    Write-IntegrationText (Join-Path $checkout 'app\build.gradle.kts') 'android { testBuildType = "qa"; buildTypes { create("qa") { applicationIdSuffix = ".qa" } } }'
    Write-IntegrationText (Join-Path $checkout 'app\src\main\HostFixture.kt') '// Stable host-only source fixture.'
    Write-IntegrationText (Join-Path $toolRoot 'java\jdk\bin\java.exe') 'Empty fake Java leaf; never executed.'
    Write-IntegrationText (Join-Path $toolRoot 'sdk\platform-tools\adb.exe') 'Empty fake adb leaf; never executed.'
    $context = Get-BookCoverTaskContext -CheckoutRoot $checkout -TaskId 'integration' -ToolRoot $toolRoot -StateRoot $stateRoot
    if (-not $WithoutState) {
        Initialize-BookCoverResources -StateRoot $stateRoot -Coordinator 'host-integration-coordinator' `
            -PreviousReleaseEvidence 'All paths are new fake fixtures; no native build or device has used them.' | Out-Null
    }
    $fixture = [pscustomobject]@{
        Root = $root; Checkout = $checkout; ToolRoot = $toolRoot; StateRoot = $stateRoot; CallsRoot = $callsRoot; Context = $context
        Config = @{ GradleExitCode = 0; AdbExitCode = 0; Locked = $false; MutateSource = $false; ThrowNative = $false }
        Runner = $null
    }
    $fakeFixture = $fixture
    $fixture.Runner = {
        param($FilePath, [string[]]$Arguments)
        $recordedState = [IO.File]::ReadAllText((Join-Path $fakeFixture.StateRoot 'resources.json')) | ConvertFrom-Json
        $record = [pscustomobject]@{
            FilePath = $FilePath; Arguments = @($Arguments)
            GradleUserHome = $env:GRADLE_USER_HOME; GradleOpts = $env:GRADLE_OPTS
            AndroidSerial = $env:ANDROID_SERIAL; Resources = $recordedState.resources
        }
        Write-IntegrationText (Join-Path $fakeFixture.CallsRoot ([guid]::NewGuid().ToString('N') + '.json')) ($record | ConvertTo-Json -Depth 12)
        if ($fakeFixture.Config.ThrowNative) { throw 'Host fixture throws synchronously before launching any process.' }
        if ([IO.Path]::GetFileName($FilePath) -ieq 'adb.exe') {
            $lines = @()
            if ($Arguments.Count -eq 1 -and $Arguments[0] -eq 'devices') { $lines = @('List of devices attached', "HOST-ONLY`tdevice") }
            elseif ($Arguments -contains 'policy') { $lines = @('mKeyguardShowing=' + $fakeFixture.Config.Locked.ToString().ToLowerInvariant(), 'showing=' + $fakeFixture.Config.Locked.ToString().ToLowerInvariant()) }
            else { throw 'The fixture intercepted an unexpected adb command.' }
            return [pscustomobject]@{ ExitCode = $fakeFixture.Config.AdbExitCode; Output = $lines }
        }
        if ([IO.Path]::GetFileName($FilePath) -ine 'gradlew.bat') { throw "Unexpected fake native path: $FilePath" }
        if ($fakeFixture.Config.MutateSource) {
            Write-IntegrationText (Join-Path $fakeFixture.Checkout 'app\src\main\HostFixture.kt') '// Source changed while the fake Gradle invocation was running.'
        }
        if ($fakeFixture.Config.GradleExitCode -eq 0) {
            if ($Arguments -contains 'connectedQaAndroidTest') {
                Write-IntegrationText (Join-Path $fakeFixture.Context.DeviceResultsPath 'TEST-host-qa.xml') '<testsuite name="com.bookcover.diagnostic.qa.Host" tests="1" failures="0" errors="0" skipped="0"><testcase name="passes" /></testsuite>'
            } else {
                Write-IntegrationText $fakeFixture.Context.BuiltApkPath 'Fake host-only APK bytes.'
                Write-IntegrationText (Join-Path $fakeFixture.Context.UnitResultsPath 'TEST-host.xml') '<testsuite name="Host" tests="1" failures="0" errors="0" skipped="0"><testcase name="passes" /></testsuite>'
                Write-IntegrationText $fakeFixture.Context.LintDebugPath '<issues format="6" />'
                Write-IntegrationText $fakeFixture.Context.LintQaPath '<issues format="6" />'
            }
        }
        return [pscustomobject]@{ ExitCode = $fakeFixture.Config.GradleExitCode; Output = @() }
    }.GetNewClosure()
    return $fixture
}
function Get-IntegrationCalls($Fixture) {
    foreach ($file in @(Get-ChildItem -LiteralPath $Fixture.CallsRoot -File -Filter '*.json')) {
        [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
    }
}
function Assert-IntegrationFree($Fixture) {
    $state = [IO.File]::ReadAllText((Join-Path $Fixture.StateRoot 'resources.json')) | ConvertFrom-Json
    Assert-IntegrationEqual @($state.resources.PSObject.Properties).Count 0 'A fake operation left a resource held'
}
function Invoke-FakeBuild($Fixture) {
    Invoke-BookCoverBuild -Context $Fixture.Context -Owner 'host-integration-owner' -NativeRunner $Fixture.Runner
}
function Invoke-FakeDevice($Fixture) {
    Invoke-BookCoverDeviceTests -Context $Fixture.Context -Owner 'host-integration-owner' -Serial 'HOST-ONLY' `
        -PhoneReleaseEvidence 'The fake phone is a new host fixture and is known free.' -NativeRunner $Fixture.Runner
}
function Get-IntegrationPackage($Fixture, $Result) {
    Get-BookCoverPackageInputs -Context $Fixture.Context -ManifestPath $Result.EvidenceManifest -SourceSnapshotId $Result.SourceSnapshotId
}

try {
    Invoke-IntegrationCheck 'CLI plans isolate tasks and reject conflicting legacy paths without tools' {
        $fixture = New-IntegrationFixture -WithoutState
        $buildScript = Join-Path $fixture.Checkout 'scripts\Build.ps1'
        $deviceScript = Join-Path $fixture.Checkout 'scripts\Test-Device.ps1'
        $common = @{ ToolRoot = $fixture.ToolRoot; StateRoot = $fixture.StateRoot; Plan = $true }
        $local = (& $buildScript @common) | ConvertFrom-Json
        Assert-IntegrationEqual $local.TaskId 'local' 'CLI default task changed'
        $selected = (& $buildScript @common -TaskId 'integration' -BuildRoot $fixture.Context.BuildRoot `
            -ProjectCache $fixture.Context.ProjectCache -ApkName $fixture.Context.ApkName) | ConvertFrom-Json
        Assert-IntegrationEqual $selected.TaskKey $fixture.Context.TaskKey 'CLI plan disagrees with the resource context'
        foreach ($legacy in @('BuildRoot', 'ProjectCache', 'ApkName')) {
            $invalid = @{ TaskId = 'integration'; ToolRoot = $fixture.ToolRoot; StateRoot = $fixture.StateRoot; Plan = $true }
            $invalid[$legacy] = 'unrelated-legacy-value'
            Assert-IntegrationThrows { & $buildScript @invalid } "Build CLI accepted conflicting $legacy"
        }
        Assert-IntegrationThrows { & $deviceScript @common -TaskId 'integration' -BuildRoot 'unrelated-legacy-value' } 'Device CLI accepted a conflicting BuildRoot'
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'CLI plan invoked a tool'
        Assert-IntegrationTrue (-not (Test-Path -LiteralPath $fixture.StateRoot)) 'CLI plan initialized coordination state'
        Assert-IntegrationTrue (-not (Test-Path -LiteralPath $fixture.Context.TaskRoot)) 'CLI plan created task output'
    }

    Invoke-IntegrationCheck 'missing resource state and a conflicting BUILD both stop before tools' {
        $fixture = New-IntegrationFixture -WithoutState
        Assert-IntegrationThrows { Invoke-FakeBuild $fixture } 'Build operation initialized absent state'
        Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device operation initialized absent state'
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'Unknown ownership reached a fake native tool'
        Initialize-BookCoverResources -StateRoot $fixture.StateRoot -Coordinator 'host-integration-coordinator' `
            -PreviousReleaseEvidence 'New unused fake state.' | Out-Null
        $foreign = Enter-BookCoverResource -Context $fixture.Context -Resource BUILD -Owner 'other-owner' -Purpose 'Host grant-conflict fixture'
        Assert-IntegrationThrows { Invoke-FakeBuild $fixture } 'Build ignored a conflicting resource grant'
        Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device ignored a conflicting resource grant'
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'Conflicting ownership reached a fake native tool'
        Assert-BookCoverResource -Context $fixture.Context -Lease $foreign | Out-Null
        Exit-BookCoverResource -Context $fixture.Context -Lease $foreign -ReleaseEvidence 'Host-only grant fixture has stopped.' -OperationStopped | Out-Null
    }

    Invoke-IntegrationCheck 'batch expansion characters in task paths reject before grants or tools' {
        foreach ($location in @('checkout', 'tools')) {
            if ($location -eq 'checkout') { $fixture = New-IntegrationFixture -CheckoutLeaf 'checkout&blocked' }
            else { $fixture = New-IntegrationFixture -ToolLeaf 'tools!blocked' }
            $statePath = Join-Path $fixture.StateRoot 'resources.json'
            $before = [IO.File]::ReadAllText($statePath)
            Assert-IntegrationThrows { Invoke-FakeBuild $fixture } "Build accepted batch expansion characters in $location"
            Assert-IntegrationThrows { Invoke-FakeDevice $fixture } "Device flow accepted batch expansion characters in $location"
            Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'An unsafe batch path reached a fake native tool'
            Assert-IntegrationEqual ([IO.File]::ReadAllText($statePath)) $before 'Unsafe batch paths changed resource state'
        }
    }

    Invoke-IntegrationCheck 'invalid TestClass rejects before grants or tools' {
        $fixture = New-IntegrationFixture
        $statePath = Join-Path $fixture.StateRoot 'resources.json'
        $before = [IO.File]::ReadAllText($statePath)
        foreach ($testClass in @('Host;echo bad', 'Host & echo bad', 'Host%COMSPEC%', "Host`nOther")) {
            Assert-IntegrationThrows {
                Invoke-BookCoverDeviceTests -Context $fixture.Context -Owner 'host-integration-owner' -Serial 'HOST-ONLY' `
                    -PhoneReleaseEvidence 'New known-free fake phone.' -TestClass $testClass -NativeRunner $fixture.Runner
            } 'Device flow accepted a TestClass argument outside the supported whitelist'
        }
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'An unsafe TestClass reached a fake native tool'
        Assert-IntegrationEqual ([IO.File]::ReadAllText($statePath)) $before 'Invalid TestClass arguments changed resource state'
    }

    Invoke-IntegrationCheck 'successful build isolates arguments and environment and releases BUILD' {
        $fixture = New-IntegrationFixture
        $names = @('GRADLE_USER_HOME', 'GRADLE_OPTS', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SERIAL', 'Path')
        $saved = @{}
        foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
        try {
            $env:GRADLE_USER_HOME = 'host-prior-gradle-home'
            $env:GRADLE_OPTS = 'host-prior-gradle-options'
            $result = Invoke-FakeBuild $fixture
            Assert-IntegrationEqual $env:GRADLE_USER_HOME 'host-prior-gradle-home' 'Build did not restore GRADLE_USER_HOME'
            Assert-IntegrationEqual $env:GRADLE_OPTS 'host-prior-gradle-options' 'Build did not restore GRADLE_OPTS'
            foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SERIAL', 'Path')) {
                Assert-IntegrationEqual ([Environment]::GetEnvironmentVariable($name, 'Process')) $saved[$name] "Build did not restore $name"
            }
        } finally {
            foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        }
        $calls = @(Get-IntegrationCalls $fixture)
        Assert-IntegrationEqual $calls.Count 1 'Build should invoke only fake Gradle'
        $call = $calls[0]
        foreach ($task in @('assembleDebug', 'testQaUnitTest', 'lintDebug', 'lintQa')) {
            Assert-IntegrationTrue ($call.Arguments -contains $task) "Build omitted task $task"
        }
        Assert-IntegrationTrue ($call.Arguments -contains ("-Pbookcover.buildRoot={0}" -f $fixture.Context.BuildRoot)) 'Build used a shared output root'
        $cacheIndex = [array]::IndexOf($call.Arguments, '--project-cache-dir')
        Assert-IntegrationTrue ($cacheIndex -ge 0) 'Build omitted the task project cache'
        Assert-IntegrationEqual $call.Arguments[$cacheIndex + 1] $fixture.Context.ProjectCache 'Build used a shared project cache'
        Assert-IntegrationEqual $call.GradleUserHome $fixture.Context.GradleUserHome 'Build used a shared Gradle home'
        Assert-IntegrationEqual $call.GradleOpts '-Dorg.gradle.daemon=false' 'Build allowed a persistent shared daemon'
        Assert-IntegrationEqual $call.Resources.BUILD.Owner 'host-integration-owner' 'Gradle ran without the expected BUILD owner'
        Assert-IntegrationEqual @($call.Resources.PSObject.Properties).Count 1 'Build held unexpected resources'
        $package = Get-IntegrationPackage $fixture $result
        Assert-IntegrationEqual $package.ApkPath $fixture.Context.ApkPath 'Build evidence selected another APK'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'failed Gradle invalidates prior successful build evidence and restores environment' {
        $fixture = New-IntegrationFixture
        $prior = Invoke-FakeBuild $fixture
        $previousHome = $env:GRADLE_USER_HOME
        $previousOptions = $env:GRADLE_OPTS
        $fixture.Config.GradleExitCode = 7
        Assert-IntegrationThrows { Invoke-FakeBuild $fixture } 'Build accepted failed fake Gradle'
        Assert-IntegrationEqual $env:GRADLE_USER_HOME $previousHome 'Failed build changed the caller Gradle home'
        Assert-IntegrationEqual $env:GRADLE_OPTS $previousOptions 'Failed build changed caller Gradle options'
        Assert-IntegrationThrows { Get-IntegrationPackage $fixture $prior } 'Prior build evidence survived a later failed build'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'preflight failure invalidates prior build evidence before tool invocation' {
        $fixture = New-IntegrationFixture
        $prior = Invoke-FakeBuild $fixture
        Remove-Item -LiteralPath (Join-Path $fixture.ToolRoot 'java\jdk\bin\java.exe')
        Assert-IntegrationThrows { Invoke-FakeBuild $fixture } 'Build accepted missing fake Java'
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 1 'Failed Java preflight reached a fake native tool'
        Assert-IntegrationThrows { Get-IntegrationPackage $fixture $prior } 'Prior build evidence survived a later preflight failure'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'source changes during and after a build reject stale evidence' {
        $fixture = New-IntegrationFixture
        $prior = Invoke-FakeBuild $fixture
        $fixture.Config.MutateSource = $true
        Assert-IntegrationThrows { Invoke-FakeBuild $fixture } 'Build accepted source changes during fake Gradle'
        Assert-IntegrationThrows { Get-IntegrationPackage $fixture $prior } 'Packaging accepted source changes after the earlier build'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'device operations require explicit serial and previous phone-release evidence' {
        $fixture = New-IntegrationFixture
        Assert-IntegrationThrows {
            Invoke-BookCoverDeviceTests -Context $fixture.Context -Owner 'host-integration-owner' -PhoneReleaseEvidence 'Known-free fake phone.' -NativeRunner $fixture.Runner
        } 'Device operation accepted an implicit serial'
        Assert-IntegrationThrows {
            Invoke-BookCoverDeviceTests -Context $fixture.Context -Owner 'host-integration-owner' -Serial 'HOST-ONLY' -PhoneReleaseEvidence '' -NativeRunner $fixture.Runner
        } 'Device operation accepted empty phone-release evidence'
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'Missing phone authorization reached a fake native tool'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'successful QA flow holds BUILD plus matching PHONE before every tool' {
        $fixture = New-IntegrationFixture
        Invoke-FakeBuild $fixture | Out-Null
        $result = Invoke-FakeDevice $fixture
        $calls = @(Get-IntegrationCalls $fixture)
        $deviceCalls = @($calls | Where-Object { $_.Resources.PSObject.Properties.Name -contains 'PHONE:HOST-ONLY' })
        Assert-IntegrationEqual $deviceCalls.Count 3 'QA should call fake adb twice and fake Gradle once'
        foreach ($call in $deviceCalls) {
            $phone = $call.Resources.PSObject.Properties['PHONE:HOST-ONLY'].Value
            Assert-IntegrationEqual $phone.BuildLeaseId $call.Resources.BUILD.LeaseId 'PHONE lacks its matching BUILD lease'
            Assert-IntegrationEqual $phone.Owner 'host-integration-owner' 'Tool ran under another phone owner'
            Assert-IntegrationTrue (-not ($call.Arguments -contains 'connectedDebugAndroidTest')) 'Device flow requested main-app tests'
            Assert-IntegrationTrue (-not ($call.Arguments -contains 'uninstall')) 'Device flow requested an uninstall'
            Assert-IntegrationTrue (-not ($call.Arguments -contains 'clear')) 'Device flow requested a data clear'
        }
        $gradle = @($deviceCalls | Where-Object { [IO.Path]::GetFileName($_.FilePath) -ieq 'gradlew.bat' })
        Assert-IntegrationEqual $gradle.Count 1 'QA flow invoked an unexpected Gradle command'
        Assert-IntegrationTrue ($gradle[0].Arguments -contains 'connectedQaAndroidTest') 'QA flow omitted the isolated QA task'
        Assert-IntegrationEqual $gradle[0].AndroidSerial 'HOST-ONLY' 'QA Gradle did not receive the explicit serial'
        Assert-IntegrationTrue (Test-Path -LiteralPath $result.DeviceReceiptPath -PathType Leaf) 'QA flow did not write a completed receipt'
        $package = Get-IntegrationPackage $fixture $result
        Assert-IntegrationEqual @($package.DeviceReports).Count 1 'QA evidence omitted its isolated report'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'unsafe QA configuration fails before adb or Gradle' {
        $fixture = New-IntegrationFixture
        Write-IntegrationText (Join-Path $fixture.Checkout 'app\build.gradle.kts') 'android { testBuildType = "debug" }'
        Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device operation accepted a main-app test configuration'
        Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'Unsafe QA configuration reached a fake native tool'
        Assert-IntegrationFree $fixture
    }

    $unsafeQaConfigurations = @(
        @{
            Name = 'comment-only QA safety tokens reject before grants or tools'
            Text = '// testBuildType = "qa"; create("qa") { applicationIdSuffix = ".qa" }'
        },
        @{
            Name = 'commented qa cannot override an actual debug testBuildType'
            Text = 'android { testBuildType = "debug"; buildTypes { create("qa") { applicationIdSuffix = ".qa" } } } // testBuildType = "qa"'
        },
        @{
            Name = 'a suffix outside the actual qa build type cannot authorize device tests'
            Text = 'android { testBuildType = "qa"; buildTypes { create("qa") { }; create("debug") { applicationIdSuffix = ".qa" } } }'
        },
        @{
            Name = 'commented correct suffix cannot override the actual wrong qa suffix'
            Text = 'android { testBuildType = "qa"; buildTypes { create("qa") { applicationIdSuffix = ".wrong" /* applicationIdSuffix = ".qa" */ } } }'
        }
    )
    foreach ($unsafeConfiguration in $unsafeQaConfigurations) {
        Invoke-IntegrationCheck $unsafeConfiguration.Name {
            $fixture = New-IntegrationFixture
            Write-IntegrationText (Join-Path $fixture.Checkout 'app\build.gradle.kts') $unsafeConfiguration.Text
            $statePath = Join-Path $fixture.StateRoot 'resources.json'
            $before = [IO.File]::ReadAllText($statePath)
            Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device operation trusted misleading QA safety tokens'
            Assert-IntegrationEqual @(Get-IntegrationCalls $fixture).Count 0 'Misleading QA safety tokens reached a fake native tool'
            Assert-IntegrationEqual ([IO.File]::ReadAllText($statePath)) $before 'Misleading QA safety tokens changed resource state'
        }
    }

    Invoke-IntegrationCheck 'a locked fake phone stops before Gradle and releases both grants' {
        $fixture = New-IntegrationFixture
        $fixture.Config.Locked = $true
        Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device operation accepted a locked phone'
        $gradleCalls = @(Get-IntegrationCalls $fixture | Where-Object { [IO.Path]::GetFileName($_.FilePath) -ieq 'gradlew.bat' })
        Assert-IntegrationEqual $gradleCalls.Count 0 'A locked phone reached fake Gradle'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'failed QA invalidates earlier combined evidence and releases both grants' {
        $fixture = New-IntegrationFixture
        Invoke-FakeBuild $fixture | Out-Null
        $prior = Invoke-FakeDevice $fixture
        $previousSerial = $env:ANDROID_SERIAL
        $fixture.Config.GradleExitCode = 8
        Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device operation accepted failed QA Gradle'
        Assert-IntegrationEqual $env:ANDROID_SERIAL $previousSerial 'Failed QA changed the caller device serial'
        Assert-IntegrationThrows { Get-IntegrationPackage $fixture $prior } 'Earlier combined evidence survived a later failed QA run'
        Assert-IntegrationFree $fixture
    }

    Invoke-IntegrationCheck 'uncertain runner completion retains grants until explicit host-fixture release' {
        $fixture = New-IntegrationFixture
        Invoke-FakeBuild $fixture | Out-Null
        $fixture.Config.ThrowNative = $true
        Assert-IntegrationThrows { Invoke-FakeDevice $fixture } 'Device operation accepted a runner that never reported completion'
        $state = [IO.File]::ReadAllText((Join-Path $fixture.StateRoot 'resources.json')) | ConvertFrom-Json
        Assert-IntegrationEqual @($state.resources.PSObject.Properties).Count 2 'Uncertain completion released a resource grant'
        $build = $state.resources.BUILD
        $phone = $state.resources.PSObject.Properties['PHONE:HOST-ONLY'].Value
        Assert-BookCoverResource -Context $fixture.Context -Lease $build | Out-Null
        Assert-BookCoverResource -Context $fixture.Context -Lease $phone | Out-Null
        $receipt = [IO.File]::ReadAllText($fixture.Context.DeviceReceiptPath) | ConvertFrom-Json
        Assert-IntegrationEqual $receipt.Status 'Running' 'Uncertain completion produced a completed receipt'
        # This callback only wrote a fake call marker and synchronously threw.
        # The harness therefore knows no native child was ever created.
        Assert-IntegrationThrows {
            Complete-BookCoverRun -Context $fixture.Context -BuildLease $build -Run $receipt -ExitCode 1 -OperationStopped
        } 'The explicit host-fixture failure produced a successful receipt'
        Exit-BookCoverResource -Context $fixture.Context -Lease $phone -OperationStopped `
            -ReleaseEvidence 'Host callback synchronously threw before launching a child; harness confirms it has stopped.' | Out-Null
        Exit-BookCoverResource -Context $fixture.Context -Lease $build -OperationStopped `
            -ReleaseEvidence 'Host-only callback ended and its PHONE grant was explicitly released; no native process was launched.' | Out-Null
        Assert-IntegrationFree $fixture
    }
} finally {
    if (Test-Path -LiteralPath $script:IntegrationTempRoot) {
        $resolvedRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $script:IntegrationTempRoot).ProviderPath).TrimEnd('\', '/')
        $resolvedParent = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $script:IntegrationTempParent).ProviderPath).TrimEnd('\', '/')
        $expectedRoot = Join-Path $resolvedParent $script:IntegrationTempLeaf
        if ($script:IntegrationTempLeaf -notmatch '^BookCover-task-integration-tests-[0-9a-f]{32}$' -or
                -not $resolvedRoot.Equals($expectedRoot, [StringComparison]::OrdinalIgnoreCase) -or
                -not $resolvedRoot.StartsWith($resolvedParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing recursive cleanup outside the verified OS temp fixture path: $resolvedRoot"
        }
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
Write-Host ("Task integration host checks: {0} passed; {1} failed." -f $script:IntegrationPassed, $script:IntegrationFailed)
if ($script:IntegrationFailed -gt 0) { throw "$($script:IntegrationFailed) task integration host check(s) failed." }
