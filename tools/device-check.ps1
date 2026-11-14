#requires -Version 7.0
param([string]$Serial, [switch]$RunAutomation)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $devices=@(adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if (-not $Serial -and $devices.Count -eq 1) { $Serial=$devices[0] }
    if (-not $Serial -or $Serial -notin $devices) { Write-Output 'NOT RUN: connect and authorize one Android device, or specify -Serial.'; exit 2 }
    if (-not $RunAutomation) { Write-Output 'Device is available. Use -RunAutomation for debug-only tests; complete docs/DEVICE_TEST.md separately.'; exit 0 }
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Test build failed.' }
    adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Debug installation failed.' }
    adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    $output=adb -s $Serial shell am instrument -w -e class app.audioflow.ImprovementDeviceTest,app.audioflow.NativeAudioTest app.audioflow.debug.test/androidx.test.runner.AndroidJUnitRunner
    $output | Set-Content -Encoding utf8 -LiteralPath build/device-check.log
    if (($output -join "`n") -notmatch 'OK \([0-9]+ tests?\)' -or ($output -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw 'Device tests failed or did not finish.' }
    Write-Output 'Automated tests passed. Audible YouTube playback, Bluetooth and the system installer still require the manual checklist.'
} finally { Pop-Location }
