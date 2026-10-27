param([string]$BuildRoot = '', [string]$ProjectCache = '', [string]$ApkName = 'BookCover-prototype.apk')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $env:LOCALAPPDATA 'BookCoverBuild'
if (-not $BuildRoot) { $BuildRoot = Join-Path $toolRoot 'project-output' }
if (-not $ProjectCache) { $ProjectCache = Join-Path $toolRoot 'project-cache' }
if ([IO.Path]::GetFileName($ApkName) -ne $ApkName -or -not $ApkName.EndsWith('.apk')) {
    throw 'ApkName must be an APK filename without a directory.'
}
if (Test-Path -LiteralPath (Join-Path $toolRoot 'java')) {
    $jdk = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'java') -Directory | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (Test-Path -LiteralPath (Join-Path $toolRoot 'sdk')) {
    $env:ANDROID_HOME = Join-Path $toolRoot 'sdk'
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw 'Java was not found. See docs/DEVELOPMENT.md or ask Codex to restore the local build tools.'
}
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    throw 'Android SDK was not found. See docs/DEVELOPMENT.md.'
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location -LiteralPath $projectRoot
try {
    "sdk.dir=$($env:ANDROID_HOME.Replace('\', '/').Replace(':', '\:'))" | Set-Content -LiteralPath 'local.properties'
    $generatedRoot = $BuildRoot
    & '.\gradlew.bat' assembleDebug testQaUnitTest lintDebug lintQa --console=plain --project-cache-dir $ProjectCache "-Pbookcover.buildRoot=$generatedRoot"
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed. Please share the error above with Codex.' }
    New-Item -ItemType Directory -Path 'output' -Force | Out-Null
    $apkPath = Join-Path 'output' $ApkName
    Copy-Item -LiteralPath (Join-Path $generatedRoot 'app\outputs\apk\debug\app-debug.apk') -Destination $apkPath -Force
    $hash = Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  $ApkName" | Set-Content -LiteralPath ([IO.Path]::ChangeExtension($apkPath, 'sha256'))
    Write-Host "Finished. Install: $projectRoot\$apkPath"
} finally {
    Pop-Location
}
