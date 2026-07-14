# Real-time multi-device logcat streaming script for Verse
$adbPath = "C:\Users\princ\AppData\Local\Android\Sdk\platform-tools\adb.exe"

if (-not (Test-Path $adbPath)) {
    Write-Error "adb.exe not found at expected path: $adbPath"
    exit 1
}

$deviceLines = & $adbPath devices -l
$targetDevices = @()
$deviceNames = @{}

foreach ($line in $deviceLines) {
    if ($line -match "device\s+product:") {
        if ($line -match "^(.*?)\s+device\s+product:") {
            $id = $Matches[1].Trim()
            if ($line -like "*RMX*" -or $line -like "*realme*") {
                $targetDevices += $id
                $deviceNames[$id] = "Realme"
            }
            elseif ($line -like "*SM-*" -or $line -like "*samsung*" -or $id -like "*R9Z*") {
                $targetDevices += $id
                $deviceNames[$id] = "Samsung"
            }
        }
    }
}

if ($targetDevices.Count -eq 0) {
    Write-Warning "No connected target devices found!"
    exit 1
}

Write-Host "Streaming logs for connected devices: ($($targetDevices -join ', '))" -ForegroundColor Yellow
Write-Host "Press Ctrl+C to terminate logs." -ForegroundColor Green

$jobs = @()
foreach ($device in $targetDevices) {
    $name = $deviceNames[$device]
    Write-Host "Launching background logcat thread for $name..." -ForegroundColor Cyan
    
    # Start PowerShell job to read device logs
    $job = Start-Job -ScriptBlock {
        param($adb, $dev, $devName)
        & $adb -s $dev logcat -c # Clear buffer first
        & $adb -s $dev logcat -v time | Select-String -Pattern "JammingService|MusicPlayerViewModel|VerseMusicService|WebViewHolder|MainActivity" | ForEach-Object {
            "[$devName] $_"
        }
    } -ArgumentList $adbPath, $device, $name
    $jobs += $job
}

# Stream job output continuously until interrupted
try {
    while ($true) {
        Receive-Job -Job $jobs -Keep:$false | Write-Host
        Start-Sleep -Milliseconds 200
    }
} finally {
    Write-Host "`nStopping logcat streams..." -ForegroundColor Yellow
    $jobs | Stop-Job
    $jobs | Remove-Job
}
