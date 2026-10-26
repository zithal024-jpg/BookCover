param([string]$TestClass = '', [string]$Serial = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $env:LOCALAPPDATA 'BookCoverBuild'
if (Test-Path -LiteralPath (Join-Path $toolRoot 'java')) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'java') -Directory | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (Test-Path -LiteralPath (Join-Path $toolRoot 'sdk')) { $env:ANDROID_HOME = Join-Path $toolRoot 'sdk' }
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Set JAVA_HOME to JDK 17 and ANDROID_HOME to your Android SDK.' }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$appConfiguration = Get-Content -LiteralPath (Join-Path $projectRoot 'app/build.gradle.kts') -Raw
if ($appConfiguration -notmatch 'testBuildType\s*=\s*"qa"' -or $appConfiguration -notmatch 'applicationIdSuffix\s*=\s*"\.qa"') {
    throw 'Device testing requires the isolated .qa application. Never target the primary reader with connected-test cleanup.'
}
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if ($Serial) { $env:ANDROID_SERIAL = $Serial }
$connected = @(& $adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
if (-not $env:ANDROID_SERIAL) {
    if ($connected.Count -ne 1) { throw 'Connect one unlocked test device or provide -Serial DEVICE_SERIAL.' }
    $env:ANDROID_SERIAL = $connected[0]
}
$phonePolicy = & $adb -s $env:ANDROID_SERIAL shell dumpsys window policy
if ($LASTEXITCODE -ne 0 -or ($phonePolicy -match '\bshowing=true\b')) { throw 'Unlock the selected phone before starting native UI tests.' }
Push-Location -LiteralPath $projectRoot
try {
    $arguments = @('connectedQaAndroidTest', '--console=plain', '--project-cache-dir', "$toolRoot\project-cache", "-Pbookcover.buildRoot=$toolRoot\project-output")
    if ($TestClass) { $arguments += "-Pandroid.testInstrumentationRunnerArguments.class=$TestClass" }
    & '.\gradlew.bat' @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Device tests failed; inspect the connected-test results.' }
} finally { Pop-Location }
