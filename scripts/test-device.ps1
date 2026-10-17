param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw '本测试会重置应用数据，仅允许指定专用测试模拟器。' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$sdkRoot = Join-Path $projectRoot '.tools\android-sdk'
if ($env:ANDROID_SDK_ROOT) { $sdkRoot = $env:ANDROID_SDK_ROOT }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$gradle = Join-Path $projectRoot '.tools\gradle-8.2\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $projectRoot 'gradlew.bat' }
Push-Location $projectRoot
try {
    & $gradle assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw '测试包构建失败' }
    & $adb -s $Serial install -r 'app\build\outputs\apk\debug\app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw '应用安装失败' }
    & $adb -s $Serial install -r 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    if ($LASTEXITCODE -ne 0) { throw '测试包安装失败' }
    $result = & $adb -s $Serial shell am instrument -w -r cn.riyousuode.app.test/androidx.test.runner.AndroidJUnitRunner
    $result | Tee-Object -FilePath 'docs\instrumentation-results-0.3.2.txt'
    if (($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw '设备测试未全部通过，见 docs/instrumentation-results-0.3.2.txt' }
} finally { Pop-Location }

