# LocalSync Desktop Installer Build Script
# Run this script to rebuild the LocalSync.msi Windows Installer.

$ErrorActionPreference = "Stop"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "   LocalSync Windows Installer Build Script   " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# 1. Compile and Package the Desktop App
Write-Host "`n[1/4] Compiling and packaging Java application..." -ForegroundColor Yellow
& mvn clean package
if ($LASTEXITCODE -ne 0) {
    Write-Error "Maven build failed."
    exit 1
}

# Copy JAR file to target/libs
Write-Host "Copying packaged JAR to libs folder..."
Copy-Item -Path "target/desktop-sync-1.0-SNAPSHOT.jar" -Destination "target/libs/" -Force

# 2. Set Up WiX Toolset
$wixZip = Join-Path $PSScriptRoot "wix311-binaries.zip"
$wixDir = Join-Path $PSScriptRoot "wix"

if (-not (Test-Path $wixDir)) {
    Write-Host "`n[2/4] WiX Toolset not found. Downloading binaries..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip" -OutFile $wixZip
    
    Write-Host "Extracting WiX Toolset..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Path $wixDir -Force | Out-Null
    Expand-Archive -Path $wixZip -DestinationPath $wixDir -Force
    
    # Remove ZIP file
    Remove-Item -Path $wixZip -Force
} else {
    Write-Host "`n[2/4] Using cached WiX Toolset binaries in '$wixDir'." -ForegroundColor Yellow
}

# 3. Generate MSI Installer Wizard using jpackage
Write-Host "`n[3/4] Running jpackage to compile MSI installer..." -ForegroundColor Yellow

# Add WiX to path temporarily for the jpackage execution
$env:PATH = "$wixDir;" + $env:PATH

$jpackagePath = "C:\Program Files\Java\jdk-25.0.3\bin\jpackage.exe"
if (-not (Test-Path $jpackagePath)) {
    # Fallback to resolving from system path or JAVA_HOME
    $jpackagePath = "jpackage"
}

& $jpackagePath `
  --type msi `
  --dest target/dist `
  --name LocalSync `
  --app-version 1.0.0 `
  --vendor "LocalSync" `
  --icon src/main/resources/icon.ico `
  --input target/libs `
  --main-jar desktop-sync-1.0-SNAPSHOT.jar `
  --main-class com.localsync.desktop.DesktopApp `
  --win-dir-chooser `
  --win-shortcut `
  --win-menu `
  --win-menu-group "LocalSync"

if ($LASTEXITCODE -ne 0) {
    Write-Error "jpackage compilation failed."
    exit 1
}

# 4. Clean up temporary WiX folder
Write-Host "`n[4/4] Cleaning up temporary files..." -ForegroundColor Yellow
if (Test-Path $wixDir) {
    Remove-Item -Path $wixDir -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "`n=============================================" -ForegroundColor Green
Write-Host " SUCCESS: Windows Installer built successfully!" -ForegroundColor Green
Write-Host " Output file: $(Join-Path $PSScriptRoot 'target\dist\LocalSync-1.0.0.msi')" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
