$ErrorActionPreference = "Continue"

$java = "C:\Program Files\jdk-17.0.14+7\bin\java.exe"
$jar  = "C:\jifeng-assessment\uat\assessment-1.0.0-SNAPSHOT.jar"
$dist = "C:\jifeng-assessment\uat\dist"
$psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"

Write-Host "[1/3] Stopping old backend..." -ForegroundColor Cyan
Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

Write-Host "[2/3] Starting new backend (Flyway will run V10-V19)..." -ForegroundColor Cyan
$env:DATASOURCE_URL      = "jdbc:postgresql://localhost:5432/jifeng_uat"
$env:DATASOURCE_DRIVER   = "org.postgresql.Driver"
$env:DATASOURCE_USERNAME = "postgres"
$env:DATASOURCE_PASSWORD = "P@ssw0rd123"
$env:PGPASSWORD          = "P@ssw0rd123"

Start-Process $java -ArgumentList "-jar",$jar,"--spring.profiles.active=prod" `
  -RedirectStandardOutput "C:\jifeng-assessment\uat\backend.log" `
  -RedirectStandardError  "C:\jifeng-assessment\uat\backend-error.log" `
  -WindowStyle Hidden

Write-Host "    Waiting 25s for startup + migration..." -ForegroundColor Yellow
Start-Sleep -Seconds 25

Write-Host "[3/3] Verifying..." -ForegroundColor Cyan
$proc = Get-Process -Name java -ErrorAction SilentlyContinue
if ($proc) {
  Write-Host "    Backend process: RUNNING (pid $($proc.Id))" -ForegroundColor Green
} else {
  Write-Host "    Backend process: NOT RUNNING" -ForegroundColor Red
  Write-Host "    --- backend-error.log (last 30 lines) ---" -ForegroundColor Yellow
  if (Test-Path "C:\jifeng-assessment\uat\backend-error.log") {
    Get-Content "C:\jifeng-assessment\uat\backend-error.log" -Tail 30
  } else {
    Write-Host "    (no backend-error.log found)"
  }
}

Write-Host "    Flyway history (latest first):" -ForegroundColor Cyan
& $psql -U postgres -h localhost -d jifeng_uat -c "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 12;"

Write-Host ""
Write-Host "DONE. Check above: backend RUNNING + top flyway version = 19." -ForegroundColor Green
