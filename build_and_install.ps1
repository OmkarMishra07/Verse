# Automated Build, Install, and Logcat script for Verse

# Step 0: Check if compilation is already running in another process
$adbPath = "C:\Users\princ\AppData\Local\Android\Sdk\platform-tools\adb.exe"

$isCompiling = $true
while ($isCompiling) {
    $gradleProcesses = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like "*gradle*" }
    if ($gradleProcesses) {
        Write-Host "compilation is happening..." -ForegroundColor Yellow
        Start-Sleep -Seconds 3
    } else {
        $isCompiling = $false
    }
}

# Step 1: Detect Connected Devices and verify both Realme and Samsung are available
if (-not (Test-Path $adbPath)) {
    Write-Error "adb.exe not found at expected path: $adbPath"
    exit 1
}

Write-Host "=== Detecting Connected Devices ===" -ForegroundColor Green
$deviceLines = & $adbPath devices -l

$realmeFound = $false
$samsungFound = $false
$targetDevices = @()

foreach ($line in $deviceLines) {
    if ($line -match "device\s+product:") {
        if ($line -match "^(.*?)\s+device\s+product:") {
            $id = $Matches[1].Trim()
            if ($line -like "*RMX*" -or $line -like "*realme*") {
                $realmeFound = $true
                $targetDevices += $id
                Write-Host "Found Realme Device: $id" -ForegroundColor Gray
            }
            elseif ($line -like "*SM-*" -or $line -like "*samsung*" -or $id -like "*R9Z*") {
                $samsungFound = $true
                $targetDevices += $id
                Write-Host "Found Samsung Device: $id" -ForegroundColor Gray
            }
        }
    }
}

if (-not $realmeFound -or -not $samsungFound) {
    if (-not $realmeFound -and -not $samsungFound) {
        Write-Error "Error: Both Realme and Samsung devices are missing!"
    } elseif (-not $realmeFound) {
        Write-Error "Error: Realme device is missing!"
    } else {
        Write-Error "Error: Samsung device is missing!"
    }
    exit 1
}

Write-Host "=== Step 2: Compiling and Assembling Debug APK ===" -ForegroundColor Green
.\gradlew assembleDebug
if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed!"
    exit $LASTEXITCODE
}

$apkPath = "D:\java all files\Verse\app\build\outputs\apk\debug\Verse-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Error "Built APK not found at: $apkPath"
    exit 1
}

Write-Host "=== Step 3: Installing APK on Target Devices ===" -ForegroundColor Green
foreach ($device in $targetDevices) {
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

Write-Host "=== Build and Installation finished successfully! ===" -ForegroundColor Green
