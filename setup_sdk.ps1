$ErrorActionPreference = "Stop"
$SdkDir = "D:\java all files\Verse\android-sdk"
$ZipPath = "D:\java all files\Verse\cmdline-tools.zip"

if (-not (Test-Path $SdkDir)) { New-Item -ItemType Directory -Path $SdkDir | Out-Null }

if (-not (Test-Path $ZipPath)) {
    Write-Host "Downloading Android command line tools..."
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $ZipPath
}

$CmdlineToolsDir = "$SdkDir\cmdline-tools"
$LatestDir = "$CmdlineToolsDir\latest"

if (-not (Test-Path $LatestDir)) {
    Write-Host "Extracting tools..."
    Expand-Archive -Path $ZipPath -DestinationPath $SdkDir -Force
    Rename-Item -Path "$SdkDir\cmdline-tools" -NewName "latest"
    New-Item -ItemType Directory -Path $CmdlineToolsDir | Out-Null
    Move-Item -Path "$SdkDir\latest" -Destination $CmdlineToolsDir
}

Write-Host "Accepting licenses..."
$SdkManager = "$LatestDir\bin\sdkmanager.bat"
"y`ny`ny`ny`ny`ny`ny`n" | & $SdkManager --licenses

Write-Host "Downloading SDK platform and build tools..."
& $SdkManager "platform-tools" "platforms;android-36" "build-tools;34.0.0"

Write-Host "Setting local.properties..."
$LocalProps = "D:\java all files\Verse\local.properties"
$SdkEscaped = $SdkDir.Replace("\", "\\")
"sdk.dir=$SdkEscaped" | Out-File -FilePath $LocalProps -Encoding ascii

Write-Host "Android SDK Setup Complete!"
