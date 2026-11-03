$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$ok = $true

function Check([string]$Name, [bool]$Condition, [string]$Fix) {
    if ($Condition) { Write-Host "[OK]   $Name" -ForegroundColor Green }
    else { Write-Host "[MISS] $Name — $Fix" -ForegroundColor Yellow; $script:ok = $false }
}

$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    $version = (& java -version 2>&1 | Select-String 'version' | Select-Object -First 1).ToString()
    Write-Host "Java: $version"
    Check 'JDK 17 доступен' ($version -match 'version \"17([.\"]|$)') 'Установите JDK 17 и задайте JAVA_HOME.'
} else {
    Check 'java в PATH' $false 'Установите JDK 17 и задайте JAVA_HOME.'
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $sdkCandidates = @(
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk"
    )
    $sdk = $sdkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
Check 'Android SDK найден' ([bool]$sdk -and (Test-Path $sdk)) 'Укажите ANDROID_HOME/ANDROID_SDK_ROOT или установите Android Studio.'

if ($sdk -and (Test-Path $sdk)) {
    Check 'Android 37 platform' (Test-Path (Join-Path $sdk 'platforms\android-37')) 'Установите platforms;android-37 через SDK Manager.'
    Check 'Build Tools 36.0.0' (Test-Path (Join-Path $sdk 'build-tools\36.0.0')) 'Установите build-tools;36.0.0 через SDK Manager.'
    Check 'Platform Tools / adb' (Test-Path (Join-Path $sdk 'platform-tools\adb.exe')) 'Установите platform-tools.'
    $sdkmanager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (-not (Test-Path $sdkmanager)) { $sdkmanager = Join-Path $sdk 'cmdline-tools\bin\sdkmanager.bat' }
    Check 'sdkmanager' (Test-Path $sdkmanager) 'Установите Android SDK Command-line Tools.'
}

$wrapperJar = Join-Path $Root 'gradle\wrapper\gradle-wrapper.jar'
Check 'Gradle wrapper jar' (Test-Path $wrapperJar) 'Запустите tools\bootstrap-gradle-wrapper.ps1.'

Write-Host ''
if ($ok) {
    Write-Host 'Окружение выглядит готовым. Можно запускать .\gradlew.bat :app:assembleDebug' -ForegroundColor Green
    exit 0
}
Write-Host 'Окружение требует исправлений. См. README.md.' -ForegroundColor Yellow
exit 1
