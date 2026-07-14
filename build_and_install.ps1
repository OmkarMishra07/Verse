# Automated Build, Install, and Logcat script for Verse

Write-Host "=== Step 1: Compiling and Assembling Debug APK ===" -ForegroundColor Green
.\gradlew assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed!"
    exit $LASTEXITCODE
}

Write-Host "=== Step 2: Locating adb.exe ===" -ForegroundColor Green
$adbPath = "C:\Users\princ\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    Write-Error "adb.exe not found at expected path: $adbPath"
    exit 1
}

Write-Host "=== Step 3: Detecting Connected Devices ===" -ForegroundColor Green
$devices = & $adbPath devices | Select-String -Pattern "device$" | ForEach-Object { $_.Line.Split("`t")[0] }

if ($devices.Count -eq 0) {
    Write-Warning "No connected ADB devices detected!"
    exit 1
}

$apkPath = "D:\java all files\Verse\app\build\outputs\apk\debug\Verse-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "Built APK not found at: $apkPath"
    exit 1
}

foreach ($device in $devices) {
    Write-Host "Installing on device: $device" -ForegroundColor Cyan
    & $adbPath -s $device install -r $apkPath
    if ($LASTEXITCODE -ne 0) {
         Write-Error "Failed to install on device $device"
    } else {
         Write-Host "Successfully installed on $device! Restarting app..." -ForegroundColor Green
         & $adbPath -s $device shell am force-stop com.example.verse
         & $adbPath -s $device shell am start -n com.example.verse/com.example.MainActivity
    }
}

Write-Host "=== Step 4: Starting Logcat Streaming for Verse App ===" -ForegroundColor Green
if ($devices.Count -gt 0) {
    $targetDevice = $devices[0]
    Write-Host "Streaming logcat for device $targetDevice (Ctrl+C to stop)..." -ForegroundColor Yellow
    & $adbPath -s $targetDevice logcat -v time | Select-String -Pattern "JammingService|MusicPlayerViewModel|VerseMusicService|WebViewHolder|MainActivity"
}
