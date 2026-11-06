param(
    [string]$TaskId = 'release', [string]$Owner = $env:USERNAME,
    [string]$ToolRoot = (Join-Path $env:LOCALAPPDATA 'BookCoverBuild'),
    [string]$StateRoot = (Join-Path $env:USERPROFILE '.bookcover-coordination'),
    [string]$SigningProperties = (Join-Path $env:USERPROFILE 'BookCover-Signing\signing.properties'),
    [string]$ExpectedPackage = 'com.frankrodriguez.bookcover.reader'
)
# Builds the signed, shareable release APK under the same BUILD reservation as Build.ps1, then
# checks the finished file: package, version, signer, permissions and that it is not debuggable.
# The owner's com.bookcover.diagnostic app and the isolated .qa app are unaffected.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Task-Operations.ps1')
$context = Get-BookCoverTaskContext -CheckoutRoot (Split-Path -Parent $PSScriptRoot) -TaskId $TaskId -ToolRoot $ToolRoot -StateRoot $StateRoot
if (-not [IO.File]::Exists($SigningProperties)) { throw "Release signing file not found: $SigningProperties. See docs/RELEASING.md." }
$signingFull = Get-BCFullPath $SigningProperties
if ($signingFull.StartsWith($context.CheckoutRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'The signing file must live outside the checkout.' }
if ($signingFull -match '\\OneDrive') { throw 'The signing file must not live in OneDrive.' }

$saved = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'PATH', 'GRADLE_USER_HOME', 'GRADLE_OPTS')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$lease = $null; $pushed = $false; $stopped = $true
try {
    $lease = Enter-BookCoverResource -Context $context -Resource BUILD -Owner $Owner -Purpose 'Signed release build'
    $null = Set-BCExecutionEnvironment -Context $context -ReuseExistingCache
    Push-Location -LiteralPath $context.CheckoutRoot; $pushed = $true
    $property = "sdk.dir=$($env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:'))"
    [IO.File]::WriteAllText((Join-Path $context.CheckoutRoot 'local.properties'), $property, [Text.UTF8Encoding]::new($false))
    $commit = (& git rev-parse HEAD).Trim()
    $dirty = [bool](& git status --porcelain --untracked-files=no -- app build.gradle.kts settings.gradle.kts gradle gradle.properties)
    $arguments = @('assembleRelease', 'lintRelease', '--console=plain', '--project-cache-dir', $context.ProjectCache,
        "-Pbookcover.buildRoot=$($context.BuildRoot)", "-Pbookcover.signingProperties=$signingFull")
    $null = Assert-BookCoverResource -Context $context -Lease $lease
    $stopped = $false
    $result = Invoke-BookCoverNative -FilePath (Join-Path $context.CheckoutRoot 'gradlew.bat') -Arguments $arguments
    $stopped = $true
    if ($result.ExitCode -ne 0) { throw "Gradle exited $($result.ExitCode)." }

    $built = Join-Path $context.BuildRoot 'app\outputs\apk\release\app-release.apk'
    if (-not [IO.File]::Exists($built)) { throw 'No signed release APK was produced (app-release.apk missing).' }
    $buildTools = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
    $badging = (& (Join-Path $buildTools.FullName 'aapt2.exe') dump badging $built) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not read the APK.' }
    $certs = (& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $built) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed.' }
    if ($badging -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") { throw 'Package line missing from badging.' }
    $package = $Matches[1]; $versionCode = [int]$Matches[2]; $versionName = $Matches[3]
    if ($package -cne $ExpectedPackage) { throw "Unexpected package $package (expected $ExpectedPackage)." }
    if ($badging -match "uses-permission: name='android.permission.INTERNET'") { throw 'The release APK requests INTERNET permission.' }
    if ($badging -match 'application-debuggable') { throw 'The release APK is debuggable.' }
    if ($certs -notmatch 'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})') { throw 'Signer certificate digest missing.' }
    $signer = $Matches[1]
    if ($certs -match 'Signer #1 certificate DN: CN=Android Debug') { throw 'The release APK is signed with a debug key.' }
    $permissions = @([regex]::Matches($badging, "uses-permission: name='([^']+)'") | ForEach-Object { $_.Groups[1].Value })

    $name = "BookCover-$versionName.apk"
    $outDir = Join-Path $context.CheckoutRoot 'output\release'
    [IO.Directory]::CreateDirectory($outDir) | Out-Null
    $apk = Join-Path $outDir $name
    Copy-Item -LiteralPath $built -Destination $apk -Force
    $record = Get-BCFileRecord $apk
    [IO.File]::WriteAllText((Join-Path $outDir "$name.sha256"), "$($record.Sha256)  $name`n", [Text.UTF8Encoding]::new($false))
    $identity = [ordered]@{
        File = $name; Bytes = $record.Bytes; Sha256 = $record.Sha256; Package = $package
        VersionName = $versionName; VersionCode = $versionCode; SignerSha256 = $signer; Permissions = $permissions
        SourceCommit = $commit; UncommittedBuildInputs = $dirty; BuiltUtc = [DateTime]::UtcNow.ToString('o')
        LintReport = (Join-Path $context.BuildRoot 'app\reports\lint-results-release.xml')
    }
    [IO.File]::WriteAllText((Join-Path $outDir "$name.identity.json"), ($identity | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    Write-Host "Release APK: $apk"
    [pscustomobject]$identity
} finally {
    if ($pushed) { Pop-Location }
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    if ($null -ne $lease) {
        if ($stopped) { Exit-BookCoverResource -Context $context -Lease $lease -OperationStopped -ReleaseEvidence 'Release build ended' }
        else { Write-Warning 'Gradle completion is uncertain; the BUILD reservation is retained for investigation.' }
    }
}
