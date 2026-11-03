param(
    [string]$GradleVersion = "9.7.0"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$wrapperDir = Join-Path $root "gradle\wrapper"
New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null
$jarUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-wrapper.jar"
$shaUrl = "$jarUrl.sha256"
$jarTmp = Join-Path $env:TEMP "gradle-wrapper-$GradleVersion.jar"
$shaTmp = Join-Path $env:TEMP "gradle-wrapper-$GradleVersion.jar.sha256"
try {
    Invoke-WebRequest -UseBasicParsing -Uri $jarUrl -OutFile $jarTmp
    Invoke-WebRequest -UseBasicParsing -Uri $shaUrl -OutFile $shaTmp
    $expected = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
    $published = (Get-Content $shaTmp -Raw).Trim().Split()[0].ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 $jarTmp).Hash.ToLowerInvariant()
    if ($published -ne $expected) { throw "Official Gradle wrapper checksum changed unexpectedly. Expected $expected, published $published." }
    if ($actual -ne $expected) { throw "gradle-wrapper.jar SHA-256 mismatch. Expected $expected, got $actual." }
    Copy-Item $jarTmp (Join-Path $wrapperDir "gradle-wrapper.jar") -Force
    Write-Host "Gradle $GradleVersion wrapper JAR installed and checksum verified." -ForegroundColor Green
} finally {
    Remove-Item $jarTmp,$shaTmp -Force -ErrorAction SilentlyContinue
}
