$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $env:LOCALAPPDATA 'BookCoverBuild'
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
    $generatedRoot = Join-Path $toolRoot 'project-output'
    & '.\gradlew.bat' assembleDebug testQaUnitTest lintDebug lintQa --console=plain --project-cache-dir "$toolRoot\project-cache" "-Pbookcover.buildRoot=$generatedRoot"
    if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed. Please share the error above with Codex.' }
    New-Item -ItemType Directory -Path 'output' -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $generatedRoot 'app\outputs\apk\debug\app-debug.apk') -Destination 'output\BookCover-prototype.apk' -Force
    $hash = Get-FileHash -LiteralPath 'output\BookCover-prototype.apk' -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  BookCover-prototype.apk" | Set-Content -LiteralPath 'output\BookCover-prototype.sha256'
    Write-Host "Finished. Install: $projectRoot\output\BookCover-prototype.apk"
} finally {
    Pop-Location
}
