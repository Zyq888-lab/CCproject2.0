$ErrorActionPreference = "Continue"
$DIR = "C:\installers"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Install Java 17 + PostgreSQL 16 + Nginx" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "[1/3] Installing Java 17..." -ForegroundColor Yellow
$javaZip = Join-Path $DIR "jdk17.zip"
if (Test-Path $javaZip) {
    Expand-Archive -Path $javaZip -DestinationPath "C:\Program Files" -Force
    $jdkDir = Get-ChildItem "C:\Program Files\jdk-17*" | Select-Object -First 1
    [System.Environment]::SetEnvironmentVariable("JAVA_HOME", $jdkDir.FullName, "Machine")
    $currentPath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
    [System.Environment]::SetEnvironmentVariable("Path", "$currentPath;$($jdkDir.FullName)\bin", "Machine")
    $env:PATH = "$env:PATH;$($jdkDir.FullName)\bin"
    Write-Host "  OK: Java 17 installed" -ForegroundColor Green
    & java -version 2>&1 | Select-Object -First 1
} else {
    Write-Host "  ERROR: jdk17.zip not found in $DIR" -ForegroundColor Red
}

Write-Host "[2/3] Installing PostgreSQL 16..." -ForegroundColor Yellow
$pgExe = Join-Path $DIR "postgresql-16.exe"
if (Test-Path $pgExe) {
    Write-Host "  (silent install, 1-2 minutes...)" -ForegroundColor White
    Start-Process $pgExe -ArgumentList "--mode unattended --superpassword P@ssw0rd123 --servicename postgresql" -Wait
    Write-Host "  OK: PostgreSQL 16 installed" -ForegroundColor Green
} else {
    Write-Host "  ERROR: postgresql-16.exe not found in $DIR" -ForegroundColor Red
}

Write-Host "[3/3] Installing Nginx..." -ForegroundColor Yellow
$nginxZip = Join-Path $DIR "nginx.zip"
if (Test-Path $nginxZip) {
    Expand-Archive -Path $nginxZip -DestinationPath "C:\" -Force
    Write-Host "  OK: Nginx installed to C:\nginx-1.26.2" -ForegroundColor Green
} else {
    Write-Host "  ERROR: nginx.zip not found in $DIR" -ForegroundColor Red
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Installation complete!" -ForegroundColor Cyan
Write-Host "  Verify: java -version" -ForegroundColor White
Write-Host "  Verify: psql --version" -ForegroundColor White
Write-Host "  Verify: C:\nginx-1.26.2\nginx.exe -v" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
