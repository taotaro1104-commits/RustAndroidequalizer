param([ValidateSet('Debug','Release')][string]$Configuration = 'Debug')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
    if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
    $env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk/27.2.12479018'
    rustup target add aarch64-linux-android x86_64-linux-android
    if ($LASTEXITCODE) { throw 'rustup failed' }
    & "$projectRoot/android/gradlew.bat" -p "$projectRoot/android" ":app:assemble$Configuration"
    if ($LASTEXITCODE) { throw 'Android build failed' }
} finally { Pop-Location }

